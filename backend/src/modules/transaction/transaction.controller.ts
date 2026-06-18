import { Request, Response, NextFunction } from 'express';
import * as transactionService from './transaction.service';
import { AuthenticatedRequest, ApiResponse } from '@/shared/types';
import type { CreateTransactionInput, RelayTransactionInput } from './transaction.schemas';

export async function createTransactionController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const input = req.body as CreateTransactionInput;
    const authReq = req as AuthenticatedRequest;

    const result = await transactionService.createTransaction(
      input,
      authReq.user.sub,
      authReq.user.deviceId,
      authReq.correlationId,
    );

    const response: ApiResponse<typeof result> = {
      success: true,
      data: result,
      meta: {
        timestamp: new Date().toISOString(),
        correlationId: authReq.correlationId,
      },
    };

    res.status(201).json(response);
  } catch (error) {
    next(error);
  }
}

export async function relayTransactionController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const input = req.body as RelayTransactionInput;
    const authReq = req as AuthenticatedRequest;

    const result = await transactionService.relayTransaction(
      input,
      authReq.user.sub,
      authReq.user.deviceId,
      authReq.correlationId,
    );

    const response: ApiResponse<typeof result> = {
      success: true,
      data: result,
      meta: {
        timestamp: new Date().toISOString(),
        correlationId: authReq.correlationId,
      },
    };

    res.status(201).json(response);
  } catch (error) {
    next(error);
  }
}

export async function getTransactionController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const authReq = req as AuthenticatedRequest;
    const txRef = req.params['txRef'] as string;

    const tx = await transactionService.getTransactionByRef(
      txRef, authReq.user.sub, authReq.correlationId,
    );

    const response: ApiResponse = {
      success: true,
      data: {
        transactionId: tx.id,
        txRef: tx.tx_ref,
        status: tx.status,
        amountPaise: tx.amount_paise,
        amountINR: (tx.amount_paise / 100).toFixed(2),
        currency: tx.currency,
        senderUserId: tx.sender_user_id,
        receiverUserId: tx.receiver_user_id,
        rssiAtPayment: tx.rssi_at_payment,
        note: tx.note,
        initiatedAt: tx.initiated_at,
        confirmedAt: tx.confirmed_at,
        failedAt: tx.failed_at,
        failureCode: tx.failure_code,
        latencyMs: tx.latency_ms,
      },
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

export async function getHistoryController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const authReq = req as AuthenticatedRequest;
    const page = parseInt(req.query['page'] as string || '1', 10);
    const limit = parseInt(req.query['limit'] as string || '20', 10);

    const result = await transactionService.getHistory(
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
