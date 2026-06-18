import { Request, Response, NextFunction } from 'express';
import { verifyAccessToken } from '@/shared/utils/crypto';
import { UnauthorizedError } from '@/shared/errors/AppError';
import { query } from '@/database/query';

// This is the key fix: use Request instead of AuthenticatedRequest
// in the function signature so Express accepts it as middleware.
// We then attach .user and .correlationId to the request object at runtime.
export async function authenticate(
  req: Request,
  _res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const authHeader = req.headers.authorization;

    if (!authHeader || !authHeader.startsWith('Bearer ')) {
      throw new UnauthorizedError('Authorization header missing or malformed');
    }

    const token = authHeader.slice(7);
    const decoded = verifyAccessToken(token);

    // Verify user still exists and is active
    const userResult = await query<{ id: string; status: string; deleted_at: Date | null }>(
      `SELECT id, status, deleted_at FROM users WHERE id = $1`,
      [decoded.sub],
    );

    const user = userResult.rows[0];

    if (!user || user.deleted_at) {
      throw new UnauthorizedError('Account not found');
    }

    if (user.status !== 'active') {
      throw new UnauthorizedError(`Account is ${user.status}`);
    }

    // Verify device is not blocked
    const deviceResult = await query<{ is_blocked: boolean }>(
      `SELECT is_blocked FROM devices WHERE device_id = $1 AND user_id = $2`,
      [decoded.deviceId, decoded.sub],
    );

    const device = deviceResult.rows[0];

    if (!device) {
      throw new UnauthorizedError('Device not registered');
    }

    if (device.is_blocked) {
      throw new UnauthorizedError('Device is blocked');
    }

    // Attach user to request — cast through unknown to bypass TypeScript overlap check
    const r = req as unknown as Record<string, unknown>;
    r['user'] = decoded;

    next();
  } catch (error) {
    next(error);
  }
}