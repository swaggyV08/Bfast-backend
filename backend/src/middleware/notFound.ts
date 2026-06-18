import { Request, Response } from 'express';
import { ApiResponse } from '@/shared/types';

/**
 * 404 handler — catches any request that didn't match a registered route.
 * Must be registered AFTER all routes but BEFORE the error handler.
 */
export function notFoundHandler(req: Request, res: Response): void {
  const correlationId = (req as { correlationId?: string }).correlationId ?? 'unknown';

  const response: ApiResponse = {
    success: false,
    error: {
      code: 'NOT_FOUND',
      message: `Route ${req.method} ${req.originalUrl} not found`,
    },
    meta: {
      timestamp: new Date().toISOString(),
      correlationId,
    },
  };

  res.status(404).json(response);
}