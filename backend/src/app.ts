import express, { Application } from 'express';
import path from 'path';
import helmet from 'helmet';
import cors from 'cors';
import { rateLimit } from 'express-rate-limit';
import { env } from '@/config/env';
import { correlationIdMiddleware } from '@/middleware/correlationId';
import { errorHandler } from '@/middleware/errorHandler';
import { notFoundHandler } from '@/middleware/notFound';
import { logger } from '@/shared/utils/logger';
import { authRouter } from '@/modules/auth/auth.router';
import { sessionRouter } from '@/modules/session/session.router';
import { bumpRouter } from '@/modules/bump/bump.router';
import { transactionRouter } from '@/modules/transaction/transaction.router';
import { walletRouter } from '@/modules/wallet/wallet.router';
import { calibrationRouter } from '@/modules/calibration/calibration.router';
import { adminRouter } from '@/modules/admin/admin.router';
import { sensorRouter } from '@/modules/sensor/sensor.router';
import swaggerUi from 'swagger-ui-express';
import { swaggerSpec } from '@/config/swagger';
/**
 * Creates and configures the Express application.
 *
 * We export a factory function (not the app directly) so that tests
 * can create a fresh app instance for each test suite — avoiding state leakage.
 */
export function createApp(): Application {
  const app = express();

  // ─── Security Headers ──────────────────────────────────────────────────────
  // Helmet sets 11+ HTTP headers that defend against common web attacks:
  // - Content-Security-Policy: prevents XSS
  // - Strict-Transport-Security: forces HTTPS
  // - X-Frame-Options: prevents clickjacking
  // - X-Content-Type-Options: prevents MIME sniffing
  app.use(
    helmet({
      // Disable CSP in development so Swagger UI and sensor test page work.
      // Swagger UI needs unsafe-inline, unsafe-eval, and CDN access for its
      // bundled scripts/styles which are incompatible with strict CSP.
      contentSecurityPolicy: env.IS_PRODUCTION ? {
        directives: {
          defaultSrc: ["'self'"],
          scriptSrc: ["'self'"],
          styleSrc: ["'self'"],
          imgSrc: ["'self'", 'data:'],
        },
      } : false,
    }),
  );

  // ─── CORS ──────────────────────────────────────────────────────────────────
  // Only allow requests from our own Android app's domain (and localhost in dev)
  app.use(
    cors({
      origin: (origin, callback) => {
        // Allow requests with no origin (mobile apps, Postman, curl)
        if (!origin) {
          callback(null, true);
          return;
        }
        // In development, allow the phone's local IP to access the API
        if (!env.IS_PRODUCTION || env.ALLOWED_ORIGINS.includes(origin)) {
          callback(null, true);
        } else {
          callback(new Error(`CORS: origin ${origin} not allowed`));
        }
      },
      credentials: true,
      methods: ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'],
      allowedHeaders: [
        'Content-Type',
        'Authorization',
        'X-Correlation-ID',
        'Idempotency-Key',
      ],
    }),
  );

  // ─── Rate Limiting ─────────────────────────────────────────────────────────
  // Global rate limit: 100 requests per minute per IP/device
  // This protects against brute-force attacks and DDoS
  const globalLimiter = rateLimit({
    windowMs: env.RATE_LIMIT_WINDOW_MS,
    max: env.RATE_LIMIT_MAX_REQUESTS,
    standardHeaders: true,   // Sends X-RateLimit-* headers to clients
    legacyHeaders: false,
    message: {
      success: false,
      error: {
        code: 'RATE_LIMIT_EXCEEDED',
        message: 'Too many requests. Please slow down.',
      },
    },
    handler: (req, res, _next, options) => {
      logger.warn('Rate limit exceeded', {
        ip: req.ip,
        path: req.path,
        correlationId: (req as { correlationId?: string }).correlationId,
      });
      res.status(options.statusCode).json(options.message);
    },
  });

  app.use(globalLimiter);

  // ─── Request Parsing ───────────────────────────────────────────────────────
  // Parse incoming JSON bodies (limit 10kb to prevent payload attacks)
  app.use(express.json({ limit: '10kb' }));
  app.use(express.urlencoded({ extended: true, limit: '10kb' }));

  // ─── Correlation ID ────────────────────────────────────────────────────────
  // Attach a unique ID to every request for end-to-end tracing
  app.use(correlationIdMiddleware);

  // ─── Request Logger ────────────────────────────────────────────────────────
  // Log every incoming request (useful for debugging and auditing)
  app.use((req, _res, next) => {
    logger.info('Incoming request', {
      method: req.method,
      path: req.path,
      correlationId: (req as { correlationId?: string }).correlationId,
      ip: req.ip,
    });
    next();
  });

  // ─── Health Check ──────────────────────────────────────────────────────────
  // AWS load balancers and monitoring tools ping this to check if the server is up
  app.get('/health', (_req, res) => {
    res.status(200).json({
      success: true,
      data: {
        status: 'healthy',
        version: process.env['npm_package_version'] ?? '0.0.1',
        environment: env.NODE_ENV,
        timestamp: new Date().toISOString(),
      },
    });
  });

  // ─── Sensor Test Page (dev only) ────────────────────────────────────────────
  // Serves the HTML sensor test page for testing bump detection from a phone
  if (!env.IS_PRODUCTION) {
    app.get('/sensor-test', (_req, res) => {
      res.sendFile(path.resolve(__dirname, 'sensor-test.html'));
    });
  }

  // ─── Swagger UI ─────────────────────────────────────────────────────────────

  app.use('/api-docs', swaggerUi.serve, swaggerUi.setup(swaggerSpec, {
    customCss: `
      .swagger-ui .topbar { display: none }
      .swagger-ui .info .title { font-size: 2.2em; font-weight: 700 }
    `,
    customSiteTitle: 'BFast API Documentation',
    customfavIcon: '',
    swaggerOptions: {
      persistAuthorization: true,
      docExpansion: 'list',
      filter: true,
      tryItOutEnabled: true,
    },
  }));

  // Serve raw OpenAPI JSON
  app.get('/api-docs.json', (_req, res) => {
    res.setHeader('Content-Type', 'application/json');
    res.send(swaggerSpec);
  });

  // ─── API Routes ────────────────────────────────────────────────────────────

  app.use(`/api/${env.API_VERSION}/auth`, authRouter);
  app.use(`/api/${env.API_VERSION}/session`, sessionRouter);
  app.use(`/api/${env.API_VERSION}/bumps`, bumpRouter);
  app.use(`/api/${env.API_VERSION}/transactions`, transactionRouter);
  app.use(`/api/${env.API_VERSION}/wallet`, walletRouter);
  app.use(`/api/${env.API_VERSION}/calibration`, calibrationRouter);
  app.use(`/api/${env.API_VERSION}/admin`, adminRouter);
  app.use(`/api/${env.API_VERSION}/sensor-readings`, sensorRouter);

  // ─── 404 Handler ──────────────────────────────────────────────────────────
  // Catches any request that didn't match any route above
  app.use(notFoundHandler);

  // ─── Global Error Handler ─────────────────────────────────────────────────
  // Must be LAST. Catches errors thrown anywhere in the app.
  app.use(errorHandler);

  return app;
}
