import { Request, Response, NextFunction } from 'express';
import * as adminRepo from './admin.repository';
import { AuthenticatedRequest, ApiResponse } from '@/shared/types';
import { logger } from '@/shared/utils/logger';

export async function getStatsController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const authReq = req as AuthenticatedRequest;
    const stats = await adminRepo.getSystemStats(authReq.correlationId);

    const response: ApiResponse<typeof stats> = {
      success: true,
      data: {
        ...stats,
        totalVolumeINR: (stats.totalVolumePaise / 100).toFixed(2),
      } as typeof stats & { totalVolumeINR: string },
      meta: {
        timestamp: new Date().toISOString(),
        correlationId: authReq.correlationId,
      },
    };

    res.status(200).json(response);
  } catch (error) {
    next(error);
  }
}

export async function getTransactionsController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const authReq = req as AuthenticatedRequest;
    const page = parseInt(req.query['page'] as string || '1', 10);
    const limit = parseInt(req.query['limit'] as string || '20', 10);
    const status = req.query['status'] as string | undefined;

    const result = await adminRepo.getAllTransactions(
      page, limit, status, authReq.correlationId,
    );

    const response: ApiResponse = {
      success: true,
      data: { ...result, page, limit },
      meta: {
        timestamp: new Date().toISOString(),
        correlationId: authReq.correlationId,
      },
    };

    res.status(200).json(response);
  } catch (error) {
    next(error);
  }
}

export async function suspendUserController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const authReq = req as AuthenticatedRequest;
    const userId = req.params['id'] as string;

    const user = await adminRepo.suspendUser(userId, authReq.correlationId);

    logger.warn('User suspended by admin', {
      correlationId: authReq.correlationId,
      targetUserId: userId,
      adminUserId: authReq.user.sub,
    });

    const response: ApiResponse = {
      success: true,
      data: { userId: user.id, status: user.status },
      meta: {
        timestamp: new Date().toISOString(),
        correlationId: authReq.correlationId,
      },
    };

    res.status(200).json(response);
  } catch (error) {
    next(error);
  }
}

export async function activateUserController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const authReq = req as AuthenticatedRequest;
    const userId = req.params['id'] as string;

    const user = await adminRepo.activateUser(userId, authReq.correlationId);

    logger.info('User activated by admin', {
      correlationId: authReq.correlationId,
      targetUserId: userId,
      adminUserId: authReq.user.sub,
    });

    const response: ApiResponse = {
      success: true,
      data: { userId: user.id, status: user.status },
      meta: {
        timestamp: new Date().toISOString(),
        correlationId: authReq.correlationId,
      },
    };

    res.status(200).json(response);
  } catch (error) {
    next(error);
  }
}

export async function freezeWalletController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const authReq = req as AuthenticatedRequest;
    const walletId = req.params['id'] as string;

    const wallet = await adminRepo.freezeWalletAdmin(walletId, authReq.correlationId);

    logger.warn('Wallet frozen by admin', {
      correlationId: authReq.correlationId,
      walletId,
      adminUserId: authReq.user.sub,
    });

    const response: ApiResponse = {
      success: true,
      data: { walletId: wallet.id, isFrozen: wallet.is_frozen },
      meta: {
        timestamp: new Date().toISOString(),
        correlationId: authReq.correlationId,
      },
    };

    res.status(200).json(response);
  } catch (error) {
    next(error);
  }
}

export async function unfreezeWalletController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const authReq = req as AuthenticatedRequest;
    const walletId = req.params['id'] as string;

    const wallet = await adminRepo.unfreezeWalletAdmin(walletId, authReq.correlationId);

    logger.info('Wallet unfrozen by admin', {
      correlationId: authReq.correlationId,
      walletId,
      adminUserId: authReq.user.sub,
    });

    const response: ApiResponse = {
      success: true,
      data: { walletId: wallet.id, isFrozen: wallet.is_frozen },
      meta: {
        timestamp: new Date().toISOString(),
        correlationId: authReq.correlationId,
      },
    };

    res.status(200).json(response);
  } catch (error) {
    next(error);
  }
}
