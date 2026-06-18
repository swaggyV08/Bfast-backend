import { Request, Response, NextFunction } from 'express';
import * as sessionService from './session.service';
import { AuthenticatedRequest, ApiResponse } from '@/shared/types';
import type { GenerateSessionInput, RefreshSessionInput } from './session.schemas';

export async function generateSessionController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const input = req.body as GenerateSessionInput;
    const authReq = req as AuthenticatedRequest;

    const session = await sessionService.generateSession(
      input.deviceId,
      authReq.user.sub,
      authReq.correlationId,
    );

    const response: ApiResponse<typeof session> = {
      success: true,
      data: session,
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

export async function refreshSessionController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const input = req.body as RefreshSessionInput;
    const authReq = req as AuthenticatedRequest;

    const session = await sessionService.refreshSession(
      input.deviceId,
      authReq.user.sub,
      authReq.correlationId,
    );

    const response: ApiResponse<typeof session> = {
      success: true,
      data: session,
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

export async function getActiveSessionController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const authReq = req as AuthenticatedRequest;

    const session = await sessionService.getActiveSession(
      authReq.user.deviceId,
      authReq.correlationId,
    );

    const response: ApiResponse = {
      success: true,
      data: session ? {
        scId: session.sc_id,
        encryptedCode: session.encrypted_code_b64,
        expiresAt: session.expires_at,
        status: session.status,
      } : null,
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
