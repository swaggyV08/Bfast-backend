import { query } from '@/database/query';
import { BumpRequestRow, BumpMatchRow, NearbyDevice } from '@/shared/types';

export async function createBumpRequest(
  data: {
    deviceId: string;
    userId: string;
    role: 'SENDER' | 'RECEIVER';
    nearbyDevices?: NearbyDevice[];
    rssi?: number;
    accelPeakMs2?: number;
    accelDurationMs?: number;
    gyroMagnitudeRads?: number;
    tapTimestamp?: string;
    sensorValidated?: boolean;
  },
  correlationId?: string,
): Promise<BumpRequestRow> {
  const result = await query<BumpRequestRow>(
    `INSERT INTO bump_requests
       (device_id, user_id, role, nearby_devices, rssi,
        accel_peak_ms2, accel_duration_ms, gyro_magnitude_rads,
        tap_timestamp, sensor_validated)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10)
     RETURNING *`,
    [
      data.deviceId,
      data.userId,
      data.role,
      data.nearbyDevices ? JSON.stringify(data.nearbyDevices) : null,
      data.rssi ?? null,
      data.accelPeakMs2 ?? null,
      data.accelDurationMs ?? null,
      data.gyroMagnitudeRads ?? null,
      data.tapTimestamp ?? null,
      data.sensorValidated ?? false,
    ],
    correlationId,
  );
  return result.rows[0]!;
}

export async function findPendingReceiverBumps(
  correlationId?: string,
): Promise<BumpRequestRow[]> {
  const result = await query<BumpRequestRow>(
    `SELECT * FROM bump_requests
     WHERE role = 'RECEIVER' AND expires_at > NOW()
     ORDER BY created_at DESC`,
    [],
    correlationId,
  );
  return result.rows;
}

export async function findPendingBumpByDevice(
  deviceId: string,
  role: 'SENDER' | 'RECEIVER',
  correlationId?: string,
): Promise<BumpRequestRow | null> {
  const result = await query<BumpRequestRow>(
    `SELECT * FROM bump_requests
     WHERE device_id = $1 AND role = $2 AND expires_at > NOW()
     ORDER BY created_at DESC LIMIT 1`,
    [deviceId, role],
    correlationId,
  );
  return result.rows[0] ?? null;
}

export async function createBumpMatch(
  data: {
    matchId: string;
    senderDeviceId: string;
    senderUserId: string;
    receiverDeviceId: string;
    receiverUserId: string;
    rssiScore?: number;
    timeDeltaMs?: number;
    senderAccelMs2?: number;
    receiverAccelMs2?: number;
  },
  correlationId?: string,
): Promise<BumpMatchRow> {
  const result = await query<BumpMatchRow>(
    `INSERT INTO bump_matches
       (match_id, sender_device_id, sender_user_id,
        receiver_device_id, receiver_user_id, rssi_score,
        time_delta_ms, sender_accel_ms2, receiver_accel_ms2)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9)
     RETURNING *`,
    [
      data.matchId,
      data.senderDeviceId, data.senderUserId,
      data.receiverDeviceId, data.receiverUserId,
      data.rssiScore ?? null,
      data.timeDeltaMs ?? null,
      data.senderAccelMs2 ?? null,
      data.receiverAccelMs2 ?? null,
    ],
    correlationId,
  );
  return result.rows[0]!;
}

export async function findMatchById(
  matchId: string,
  correlationId?: string,
): Promise<BumpMatchRow | null> {
  const result = await query<BumpMatchRow>(
    `SELECT * FROM bump_matches WHERE match_id = $1`,
    [matchId],
    correlationId,
  );
  return result.rows[0] ?? null;
}

export async function consumeMatch(
  matchId: string,
  transactionId: string,
  correlationId?: string,
): Promise<void> {
  await query(
    `UPDATE bump_matches
     SET consumed_at = NOW(), consumed_by_tx_id = $1
     WHERE match_id = $2`,
    [transactionId, matchId],
    correlationId,
  );
}

export async function cleanupExpiredBumps(
  correlationId?: string,
): Promise<number> {
  const result = await query(
    `DELETE FROM bump_requests WHERE expires_at < NOW()`,
    [],
    correlationId,
  );
  return result.rowCount ?? 0;
}

