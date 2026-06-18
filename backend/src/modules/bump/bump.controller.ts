import { Request, Response, NextFunction } from 'express';
import * as bumpService from './bump.service';
import { AuthenticatedRequest, ApiResponse } from '@/shared/types';
import type { SenderBumpInput, ReceiverBumpInput } from './bump.schemas';

export async function senderBumpController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const input = req.body as SenderBumpInput;
    const authReq = req as AuthenticatedRequest;

    const match = await bumpService.submitSenderBump(
      input, authReq.user.sub, authReq.correlationId,
    );

    const response: ApiResponse = {
      success: true,
      data: match ? {
        matched: true,
        matchId: match.matchId,
        receiverDeviceId: match.receiverDeviceId,
        receiverUserId: match.receiverUserId,
        rssiScore: match.rssiScore,
        timeDeltaMs: match.timeDeltaMs,
        sensorValidated: match.sensorValidated,
      } : {
        matched: false,
        message: 'No matching receiver found. Retry in a moment.',
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

export async function receiverBumpController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const input = req.body as ReceiverBumpInput;
    const authReq = req as AuthenticatedRequest;

    const result = await bumpService.submitReceiverBump(
      input, authReq.user.sub, authReq.correlationId,
    );

    const response: ApiResponse = {
      success: true,
      data: {
        registered: true,
        sensorValidated: result.sensorValidated,
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

export async function getMatchController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const authReq = req as AuthenticatedRequest;
    const matchId = req.params['matchId'] as string;

    const match = await bumpService.getMatch(matchId, authReq.correlationId);

    const response: ApiResponse = {
      success: true,
      data: {
        matchId: match.match_id,
        senderDeviceId: match.sender_device_id,
        senderUserId: match.sender_user_id,
        receiverDeviceId: match.receiver_device_id,
        receiverUserId: match.receiver_user_id,
        rssiScore: match.rssi_score,
        timeDeltaMs: match.time_delta_ms,
        senderAccelMs2: match.sender_accel_ms2,
        receiverAccelMs2: match.receiver_accel_ms2,
        matchedAt: match.matched_at,
        consumed: !!match.consumed_at,
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

