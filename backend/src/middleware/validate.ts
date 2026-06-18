import { Request, Response, NextFunction, RequestHandler } from 'express';
import { ZodSchema, ZodError } from 'zod';
import { ApiResponse } from '@/shared/types';

export function validate(schema: ZodSchema): RequestHandler {
  return (req: Request, res: Response, next: NextFunction): void => {
    const result = schema.safeParse(req.body);

    if (!result.success) {
      const fieldErrors: Record<string, string[]> = {};

      (result.error as ZodError).issues.forEach((err) => {
        const field = err.path.join('.') || 'body';
        if (!fieldErrors[field]) fieldErrors[field] = [];
        fieldErrors[field]!.push(err.message);
      });

      const response: ApiResponse = {
        success: false,
        error: {
          code: 'VALIDATION_ERROR',
          message: 'Request validation failed',
          details: fieldErrors,
        },
        meta: {
          timestamp: new Date().toISOString(),
          correlationId: (req as unknown as Record<string, string>)['correlationId'] ?? 'unknown',
        },
      };

      res.status(400).json(response);
      return;
    }

    req.body = result.data as unknown;
    next();
  };
}