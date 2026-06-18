import { query } from '@/database/query';
import { DeviceCalibrationRow } from '@/shared/types';

export async function upsertCalibration(
  data: {
    deviceId: string;
    userId: string;
    tapThresholdMs2: number;
    tapDurationMaxMs: number;
    gyroDriftBaselineRads: number;
    gyroRejectionThresholdRads: number;
    rssi1mCalibrationDbm: number;
    emaAlphaQuiet: number;
    emaAlphaNoisy: number;
    pathLossExponent: number;
  },
  correlationId?: string,
): Promise<DeviceCalibrationRow> {
  const result = await query<DeviceCalibrationRow>(
    `INSERT INTO device_calibrations
       (device_id, user_id, tap_threshold_ms2, tap_duration_max_ms,
        gyro_drift_baseline_rads, gyro_rejection_threshold_rads,
        rssi_1m_calibration_dbm, ema_alpha_quiet, ema_alpha_noisy,
        path_loss_exponent, calibrated_at)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, NOW())
     ON CONFLICT (device_id) DO UPDATE SET
       tap_threshold_ms2 = EXCLUDED.tap_threshold_ms2,
       tap_duration_max_ms = EXCLUDED.tap_duration_max_ms,
       gyro_drift_baseline_rads = EXCLUDED.gyro_drift_baseline_rads,
       gyro_rejection_threshold_rads = EXCLUDED.gyro_rejection_threshold_rads,
       rssi_1m_calibration_dbm = EXCLUDED.rssi_1m_calibration_dbm,
       ema_alpha_quiet = EXCLUDED.ema_alpha_quiet,
       ema_alpha_noisy = EXCLUDED.ema_alpha_noisy,
       path_loss_exponent = EXCLUDED.path_loss_exponent,
       calibrated_at = NOW()
     RETURNING *`,
    [
      data.deviceId, data.userId,
      data.tapThresholdMs2, data.tapDurationMaxMs,
      data.gyroDriftBaselineRads, data.gyroRejectionThresholdRads,
      data.rssi1mCalibrationDbm,
      data.emaAlphaQuiet, data.emaAlphaNoisy,
      data.pathLossExponent,
    ],
    correlationId,
  );
  return result.rows[0]!;
}

export async function getCalibrationByDeviceId(
  deviceId: string,
  correlationId?: string,
): Promise<DeviceCalibrationRow | null> {
  const result = await query<DeviceCalibrationRow>(
    `SELECT * FROM device_calibrations WHERE device_id = $1`,
    [deviceId],
    correlationId,
  );
  return result.rows[0] ?? null;
}

export async function incrementTxSuccessCount(
  deviceId: string,
  correlationId?: string,
): Promise<void> {
  await query(
    `UPDATE device_calibrations
     SET tx_success_count = tx_success_count + 1
     WHERE device_id = $1`,
    [deviceId],
    correlationId,
  );
}
