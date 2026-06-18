import { Request, Response, NextFunction } from 'express';
import { v4 as uuidv4 } from 'uuid';

export function correlationIdMiddleware(req: Request, res: Response, next: NextFunction): void {
  const correlationId =
    (req.headers['x-correlation-id'] as string | undefined) ?? uuidv4();

  // Cast through unknown first — required by strict TypeScript when
  // the target type doesn't overlap with the source type
  (req as unknown as Record<string, unknown>)['correlationId'] = correlationId;

  res.setHeader('X-Correlation-ID', correlationId);

  next();
}