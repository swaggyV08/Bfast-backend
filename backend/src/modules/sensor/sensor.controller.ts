import { Request, Response, NextFunction } from 'express';
import * as sensorService from './sensor.service';
import { AuthenticatedRequest, ApiResponse } from '@/shared/types';
import type { BatchSensorReadingsInput } from './sensor.schemas';

export async function uploadBatchController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const input = req.body as BatchSensorReadingsInput;
    const authReq = req as AuthenticatedRequest;

    await sensorService.processBatchReadings(
      authReq.user.sub,
      input,
      authReq.correlationId,
    );

    const response: ApiResponse = {
      success: true,
      data: { message: 'Batch uploaded successfully' },
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
