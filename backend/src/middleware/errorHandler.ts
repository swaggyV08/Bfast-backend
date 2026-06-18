import { Request, Response, NextFunction } from 'express';
import { AppError } from '@/shared/errors/AppError';
import { logger } from '@/shared/utils/logger';
import { ApiResponse } from '@/shared/types';
import { env } from '@/config/env';

/**
 * Global Express error handler.
 *
 * In Express, error-handling middleware takes 4 parameters (err, req, res, next).
 * Express identifies it as an error handler by the 4-parameter signature.
 *
 * This must be the LAST middleware registered in app.ts.
 */
export function errorHandler(
  err: Error,
  req: Request,
  res: Response,
  _next: NextFunction,
): void {
  const correlationId = (req as { correlationId?: string }).correlationId ?? 'unknown';

  if (err instanceof AppError) {
    // Operational error — something we anticipated (bad request, not found, etc.)
    if (!err.isOperational) {
      // This is a programming bug or infrastructure failure
      logger.error('Unexpected application error', {
        correlationId,
        errorCode: err.errorCode,
        message: err.message,
        stack: err.stack,
      });
    } else {
      logger.warn('Operational error', {
        correlationId,
        errorCode: err.errorCode,
        message: err.message,
        statusCode: err.statusCode,
      });
    }

    const response: ApiResponse = {
      success: false,
      error: {
        code: err.errorCode,
        message: err.message,
      },
      meta: {
        timestamp: new Date().toISOString(),
        correlationId,
      },
    };

    res.status(err.statusCode).json(response);
    return;
  }

  // Unknown error (not an AppError) — this is a bug
  logger.error('Unhandled error', {
    correlationId,
    message: err.message,
    stack: err.stack,
  });

  const response: ApiResponse = {
    success: false,
    error: {
      code: 'INTERNAL_ERROR',
      // Never expose internal error details in production
      message: env.IS_PRODUCTION ? 'An unexpected error occurred' : err.message,
    },
    meta: {
      timestamp: new Date().toISOString(),
      correlationId,
    },
  };

  res.status(500).json(response);
}