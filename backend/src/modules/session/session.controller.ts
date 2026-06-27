import { Request, Response, NextFunction } from 'express';
import * as sessionService from './session.service';
import { AuthenticatedRequest, ApiResponse } from '@/shared/types';
import type { GenerateSessionInput, RefreshSessionInput, CorrelateTapInput, TapEventInput } from './session.schemas';

export async function correlateTapController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const input = req.body as CorrelateTapInput;
    const authReq = req as AuthenticatedRequest;

    const result = await sessionService.correlateTap(
      input,
      authReq.user.sub,
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

    res.status(200).json(response);
  } catch (error) {
    next(error);
  }
}

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

/**
 * POST /session/tap-event
 * Called by the RECEIVER immediately when its accelerometer detects a tap.
 */
export async function reportTapController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const input = req.body as TapEventInput;
    const authReq = req as AuthenticatedRequest;

    const result = await sessionService.reportReceiverTap(
      input,
      authReq.user.sub,
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

/**
 * GET /session/tap-poll?senderDeviceId=X&receiverDeviceId=Y
 * Called by the SENDER every ~300ms to check if the receiver confirmed a tap.
 */
export async function pollTapController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const { receiverDeviceId, senderDeviceId = '' } = req.query as {
      receiverDeviceId: string;
      senderDeviceId?: string;
    };
    const authReq = req as AuthenticatedRequest;

    const result = await sessionService.pollTapStatus(
      senderDeviceId,
      receiverDeviceId,
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

    res.status(200).json(response);
  } catch (error) {
    next(error);
  }
}

export async function getSessionStatusController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const authReq = req as AuthenticatedRequest;
    const sessionId = req.params.sessionId as string;

    const { sessionStatuses } = await import('@/modules/sensor/TapAlignmentEngine');
    const status = sessionStatuses.get(sessionId) || 'PENDING';

    const response: ApiResponse = {
      success: true,
      data: { status },
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
