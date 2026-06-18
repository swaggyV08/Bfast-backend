import { createApp } from '@/app';
import { env } from '@/config/env';
import { logger } from '@/shared/utils/logger';
import { connectDatabase, disconnectDatabase } from '@/database/pool';
import { startCleanupWorker, stopCleanupWorker } from '@/workers/cleanup';

async function bootstrap(): Promise<void> {
  // Step 1: Verify database is reachable before accepting any traffic
  await connectDatabase();

  // Step 2: Create and configure Express app
  const app = createApp();

  // Step 3: Start listening
  const server = app.listen(Number(env.PORT), '0.0.0.0', () => {
    logger.info('B-FAST server started', {
      port: env.PORT,
      environment: env.NODE_ENV,
      apiVersion: env.API_VERSION,
    });

    // Step 4: Start background workers
    startCleanupWorker();
  });

  // ─── Graceful Shutdown ──────────────────────────────────────────────────
  async function gracefulShutdown(signal: string): Promise<void> {
    logger.info(`Received ${signal}. Starting graceful shutdown...`);

    // 1. Stop accepting new HTTP requests
    server.close(async (err) => {
      if (err) {
        logger.error('Error closing HTTP server', { message: err.message });
        process.exit(1);
      }

      // 2. Stop background workers
      stopCleanupWorker();

      // 3. Close all DB connections cleanly
      await disconnectDatabase();

      logger.info('Graceful shutdown complete.');
      process.exit(0);
    });

    // Force exit after 10s if shutdown hangs
    setTimeout(() => {
      logger.error('Graceful shutdown timed out. Forcing exit.');
      process.exit(1);
    }, 10_000);
  }

  process.on('SIGTERM', () => void gracefulShutdown('SIGTERM'));
  process.on('SIGINT', () => void gracefulShutdown('SIGINT'));

  process.on('unhandledRejection', (reason: unknown) => {
    logger.error('Unhandled promise rejection', {
      reason: reason instanceof Error ? reason.message : String(reason),
    });
    if (env.IS_PRODUCTION) process.exit(1);
  });

  process.on('uncaughtException', (err: Error) => {
    logger.error('Uncaught exception', { message: err.message, stack: err.stack });
    process.exit(1);
  });
}

// Start the server
bootstrap().catch((err: unknown) => {
  console.error('Failed to start server:', err);
  process.exit(1);
});