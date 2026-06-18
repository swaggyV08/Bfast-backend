import { Request, Response, NextFunction } from 'express';
import * as walletService from './wallet.service';
import { AuthenticatedRequest, ApiResponse } from '@/shared/types';
import type { TopupInput } from './wallet.schemas';

export async function getBalanceController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const authReq = req as AuthenticatedRequest;
    const balance = await walletService.getBalance(authReq.user.sub, authReq.correlationId);

    const response: ApiResponse<typeof balance> = {
      success: true,
      data: balance,
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

export async function topupController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const input = req.body as TopupInput;
    const authReq = req as AuthenticatedRequest;

    const balance = await walletService.topup(
      authReq.user.sub, input.amountPaise, authReq.correlationId,
    );

    const response: ApiResponse<typeof balance> = {
      success: true,
      data: balance,
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

export async function getLedgerController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const authReq = req as AuthenticatedRequest;
    const page = parseInt(req.query['page'] as string || '1', 10);
    const limit = parseInt(req.query['limit'] as string || '20', 10);

    const result = await walletService.getLedger(
      authReq.user.sub, page, limit, authReq.correlationId,
    );

    const response: ApiResponse<typeof result> = {
      success: true,
      data: result,
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
