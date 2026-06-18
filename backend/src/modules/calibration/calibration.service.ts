import { logger } from '@/shared/utils/logger';
import * as calibrationRepo from './calibration.repository';
import type { StoreCalibrationInput } from './calibration.schemas';
import { DeviceCalibrationRow } from '@/shared/types';

export async function storeCalibration(
  input: StoreCalibrationInput,
  userId: string,
  correlationId: string,
): Promise<DeviceCalibrationRow> {
  const profile = await calibrationRepo.upsertCalibration({
    deviceId: input.deviceId,
    userId,
    tapThresholdMs2: input.tapThresholdMs2,
    tapDurationMaxMs: input.tapDurationMaxMs,
    gyroDriftBaselineRads: input.gyroDriftBaselineRads,
    gyroRejectionThresholdRads: input.gyroRejectionThresholdRads,
    rssi1mCalibrationDbm: input.rssi1mCalibrationDbm,
    emaAlphaQuiet: input.emaAlphaQuiet,
    emaAlphaNoisy: input.emaAlphaNoisy,
    pathLossExponent: input.pathLossExponent,
  }, correlationId);

  logger.info('Device calibration stored', {
    correlationId,
    deviceId: input.deviceId,
    tapThreshold: input.tapThresholdMs2,
    rssi1m: input.rssi1mCalibrationDbm,
  });

  return profile;
}

export async function getCalibration(
  deviceId: string,
  correlationId: string,
): Promise<DeviceCalibrationRow | null> {
  return calibrationRepo.getCalibrationByDeviceId(deviceId, correlationId);
}
