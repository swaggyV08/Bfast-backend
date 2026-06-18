import { Request, Response, NextFunction } from 'express';
import * as calibrationService from './calibration.service';
import { AuthenticatedRequest, ApiResponse } from '@/shared/types';
import type { StoreCalibrationInput } from './calibration.schemas';

export async function storeCalibrationController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const input = req.body as StoreCalibrationInput;
    const authReq = req as AuthenticatedRequest;

    const profile = await calibrationService.storeCalibration(
      input, authReq.user.sub, authReq.correlationId,
    );

    const response: ApiResponse = {
      success: true,
      data: {
        deviceId: profile.device_id,
        tapThresholdMs2: profile.tap_threshold_ms2,
        tapDurationMaxMs: profile.tap_duration_max_ms,
        gyroDriftBaselineRads: profile.gyro_drift_baseline_rads,
        gyroRejectionThresholdRads: profile.gyro_rejection_threshold_rads,
        rssi1mCalibrationDbm: profile.rssi_1m_calibration_dbm,
        emaAlphaQuiet: profile.ema_alpha_quiet,
        emaAlphaNoisy: profile.ema_alpha_noisy,
        pathLossExponent: profile.path_loss_exponent,
        calibratedAt: profile.calibrated_at,
        txSuccessCount: profile.tx_success_count,
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

export async function getCalibrationController(
  req: Request,
  res: Response,
  next: NextFunction,
): Promise<void> {
  try {
    const authReq = req as AuthenticatedRequest;
    const deviceId = req.params['deviceId'] as string;

    const profile = await calibrationService.getCalibration(
      deviceId, authReq.correlationId,
    );

    const response: ApiResponse = {
      success: true,
      data: profile ? {
        deviceId: profile.device_id,
        tapThresholdMs2: profile.tap_threshold_ms2,
        tapDurationMaxMs: profile.tap_duration_max_ms,
        gyroDriftBaselineRads: profile.gyro_drift_baseline_rads,
        gyroRejectionThresholdRads: profile.gyro_rejection_threshold_rads,
        rssi1mCalibrationDbm: profile.rssi_1m_calibration_dbm,
        emaAlphaQuiet: profile.ema_alpha_quiet,
        emaAlphaNoisy: profile.ema_alpha_noisy,
        pathLossExponent: profile.path_loss_exponent,
        calibratedAt: profile.calibrated_at,
        txSuccessCount: profile.tx_success_count,
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
