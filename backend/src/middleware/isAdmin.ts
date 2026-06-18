import { Request, Response, NextFunction } from 'express';
import { AuthenticatedRequest } from '@/shared/types';
import { ForbiddenError } from '@/shared/errors/AppError';
import { query } from '@/database/query';

/**
 * Admin middleware — checks if the authenticated user has admin privileges.
 * Must be used AFTER the authenticate middleware.
 */
export async function isAdmin(
  req: Request,
  _res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const authReq = req as AuthenticatedRequest;
    const userId = authReq.user.sub;

    const result = await query<{ is_admin: boolean }>(
      `SELECT is_admin FROM users WHERE id = $1`,
      [userId],
      authReq.correlationId,
    );

    const user = result.rows[0];
    if (!user || !user.is_admin) {
      throw new ForbiddenError('Admin access required');
    }

    next();
  } catch (error) {
    next(error);
  }
}
