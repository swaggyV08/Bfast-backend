import { generateSecureRandom } from '@/shared/utils/crypto';
import { logger } from '@/shared/utils/logger';
import { NotFoundError, ValidationError } from '@/shared/errors/AppError';
import * as bumpRepo from './bump.repository';
import * as calibrationRepo from '@/modules/calibration/calibration.repository';
import type { SenderBumpInput, ReceiverBumpInput, SensorSnapshot } from './bump.schemas';
import { BumpMatchRow, NearbyDevice } from '@/shared/types';

/**
 * Bump matching service — server-side BLE + sensor match validation.
 *
 * When two devices are physically close and a tap is detected, both
 * post their sensor readings + RSSI data to the server. The server:
 *
 *  1. Validates accelerometer/gyroscope data against calibration
 *  2. Correlates tap timestamps (±500ms window)
 *  3. Ranks by RSSI (closest device = highest signal)
 *  4. Creates a bump_match with full audit trail
 */

// ─── Constants ─────────────────────────────────────────────────────────────

/** Max time difference between sender & receiver taps to be considered simultaneous */
const BUMP_TIME_WINDOW_MS = 500;

/** Default sensor thresholds for uncalibrated devices (permissive) */
const DEFAULT_CALIBRATION = {
  tap_threshold_ms2: 1.5,
  tap_duration_max_ms: 300,
  gyro_drift_baseline_rads: 0.0,
  gyro_rejection_threshold_rads: 1.0,
} as const;

// ─── Types ─────────────────────────────────────────────────────────────────

export interface BumpMatchResult {
  matchId: string;
  receiverDeviceId: string;
  receiverUserId: string;
  rssiScore: number;
  timeDeltaMs: number;
  sensorValidated: boolean;
}

export interface SensorValidationResult {
  valid: boolean;
  reason?: string;
}

// ─── Sensor Validation ─────────────────────────────────────────────────────

/**
 * Validates a sensor snapshot against the device's calibration profile.
 *
 * Three checks are performed in order:
 *  1. Acceleration magnitude ≥ tap threshold (real tap, not vibration)
 *  2. Tap duration ≤ max duration (sharp impact, not a slow push/drop)
 *  3. Gyroscope magnitude ≤ rejection threshold (phone wasn't being waved)
 *
 * If the device has no calibration profile, permissive defaults are used
 * so first-time users can still bump successfully.
 */
async function validateSensorData(
  deviceId: string,
  snapshot: SensorSnapshot,
  correlationId: string,
): Promise<SensorValidationResult> {
  const calibration = await calibrationRepo.getCalibrationByDeviceId(deviceId, correlationId);

  // Use calibrated values or permissive defaults for uncalibrated devices
  const thresholds = calibration
    ? {
        tapThreshold: calibration.tap_threshold_ms2,
        tapMaxDuration: calibration.tap_duration_max_ms,
        gyroDriftBaseline: calibration.gyro_drift_baseline_rads,
        gyroRejection: calibration.gyro_rejection_threshold_rads,
      }
    : {
        tapThreshold: DEFAULT_CALIBRATION.tap_threshold_ms2,
        tapMaxDuration: DEFAULT_CALIBRATION.tap_duration_max_ms,
        gyroDriftBaseline: DEFAULT_CALIBRATION.gyro_drift_baseline_rads,
        gyroRejection: DEFAULT_CALIBRATION.gyro_rejection_threshold_rads,
      };

  // Check 1: Acceleration must be strong enough (real tap, not ambient vibration)
  if (snapshot.accelPeakMs2 < thresholds.tapThreshold) {
    return {
      valid: false,
      reason: `Acceleration ${snapshot.accelPeakMs2.toFixed(2)} m/s² below tap threshold ${thresholds.tapThreshold.toFixed(2)} m/s²`,
    };
  }

  // Check 2: Tap must be quick (sharp impact, not a slow push or phone drop)
  if (snapshot.accelDurationMs > thresholds.tapMaxDuration) {
    return {
      valid: false,
      reason: `Tap duration ${snapshot.accelDurationMs}ms exceeds max ${thresholds.tapMaxDuration}ms`,
    };
  }

  // Check 3: Gyroscope must be calm (phone wasn't being waved/rotated)
  // Add drift baseline to account for per-device gyroscope offset
  const effectiveGyroThreshold = thresholds.gyroRejection + thresholds.gyroDriftBaseline;
  if (snapshot.gyroMagnitudeRads > effectiveGyroThreshold) {
    return {
      valid: false,
      reason: `Gyro magnitude ${snapshot.gyroMagnitudeRads.toFixed(3)} rad/s exceeds threshold ${effectiveGyroThreshold.toFixed(3)} rad/s`,
    };
  }

  return { valid: true };
}

// ─── RSSI Ranking ──────────────────────────────────────────────────────────

/**
 * Sorts nearby devices by signal strength (highest = closest).
 */
function rankByRSSI(nearbyDevices: NearbyDevice[]): NearbyDevice[] {
  return [...nearbyDevices].sort((a, b) => b.rssi - a.rssi);
}

// ─── Sender Bump ───────────────────────────────────────────────────────────

/**
 * Sender submits their bump request with sensor data and nearby RSSI readings.
 *
 * Pipeline:
 *  1. Validate sensor data against device calibration
 *  2. Store sender's bump request (with sensor snapshot)
 *  3. Rank nearby devices by RSSI (highest signal = closest)
 *  4. For each nearby device (in RSSI order):
 *     a. Check if there's a pending receiver bump
 *     b. Verify tap timestamps are within ±500ms
 *     c. First match wins → create bump_match with full audit trail
 */
export async function submitSenderBump(
  input: SenderBumpInput,
  userId: string,
  correlationId: string,
): Promise<BumpMatchResult | null> {
  // ── Step 1: Validate sensor data against calibration ──────────────────
  const sensorResult = await validateSensorData(
    input.deviceId, input.sensorSnapshot, correlationId,
  );

  if (!sensorResult.valid) {
    logger.warn('Sender bump rejected — sensor validation failed', {
      correlationId,
      deviceId: input.deviceId,
      reason: sensorResult.reason,
      accelPeak: input.sensorSnapshot.accelPeakMs2,
      gyrMag: input.sensorSnapshot.gyroMagnitudeRads,
    });
    throw new ValidationError(
      `Bump rejected: ${sensorResult.reason}`,
    );
  }

  // ── Step 2: Store sender's bump with sensor data ──────────────────────
  await bumpRepo.createBumpRequest({
    deviceId: input.deviceId,
    userId,
    role: 'SENDER',
    nearbyDevices: input.nearbyDevices,
    rssi: input.rssi,
    accelPeakMs2: input.sensorSnapshot.accelPeakMs2,
    accelDurationMs: input.sensorSnapshot.accelDurationMs,
    gyroMagnitudeRads: input.sensorSnapshot.gyroMagnitudeRads,
    tapTimestamp: input.sensorSnapshot.tapTimestamp,
    sensorValidated: true,
  }, correlationId);

  // ── Step 3: Rank nearby devices by signal strength ────────────────────
  const ranked = rankByRSSI(input.nearbyDevices);

  // ── Step 4: Find pending receiver bumps and match ─────────────────────
  const pendingReceivers = await bumpRepo.findPendingReceiverBumps(correlationId);
  const receiverDeviceIds = new Set(pendingReceivers.map(r => r.device_id));

  const senderTapTime = new Date(input.sensorSnapshot.tapTimestamp).getTime();

  for (const nearby of ranked) {
    if (!receiverDeviceIds.has(nearby.deviceId)) continue;

    const receiver = pendingReceivers.find(r => r.device_id === nearby.deviceId)!;

    // ── Timestamp correlation: bumps must be within ±500ms ────────────
    let timeDeltaMs = 0;
    if (receiver.tap_timestamp) {
      const receiverTapTime = new Date(receiver.tap_timestamp).getTime();
      timeDeltaMs = Math.abs(senderTapTime - receiverTapTime);

      if (timeDeltaMs > BUMP_TIME_WINDOW_MS) {
        logger.debug('Skipping receiver — tap timestamp too far apart', {
          correlationId,
          receiverDevice: nearby.deviceId,
          timeDeltaMs,
          maxWindowMs: BUMP_TIME_WINDOW_MS,
        });
        continue;
      }
    }

    // ── Match found! Run Sensor Fusion Engine ──────────────────────────
    const wBle = 0.35;
    const wAccel = 0.20;
    const wGyro = 0.10;
    const wCorr = 0.35;

    const bleConf = nearby.bleConfidence ?? 0.0;
    const accelConf = input.sensorSnapshot.accelConfidence ?? 0.0;
    const gyroConf = input.sensorSnapshot.gyroConfidence ?? 0.0;
    const corrConf = Math.max(0, 1.0 - (timeDeltaMs / 50.0));

    // Weighted geometric mean strongly penalizes any score near 0
    const tapConfidence = Math.pow(bleConf, wBle) * 
                          Math.pow(accelConf, wAccel) * 
                          Math.pow(gyroConf, wGyro) * 
                          Math.pow(corrConf, wCorr);

    if (tapConfidence < 0.75) {
       logger.warn('Bump match rejected by Sensor Fusion Engine', {
           correlationId,
           tapConfidence, bleConf, accelConf, gyroConf, corrConf, timeDeltaMs
       });
       continue;
    }

    // ── Valid Match! Create bump_match with full audit trail ──────────
    const matchId = generateSecureRandom(16);

    const match = await bumpRepo.createBumpMatch({
      matchId,
      senderDeviceId: input.deviceId,
      senderUserId: userId,
      receiverDeviceId: receiver.device_id,
      receiverUserId: receiver.user_id,
      rssiScore: nearby.rssi,
      timeDeltaMs,
      senderAccelMs2: input.sensorSnapshot.accelPeakMs2,
      receiverAccelMs2: receiver.accel_peak_ms2 ?? undefined,
    }, correlationId);

    logger.info('Bump match created', {
      correlationId,
      matchId: match.match_id,
      senderDevice: input.deviceId,
      receiverDevice: receiver.device_id,
      rssiScore: nearby.rssi,
      timeDeltaMs,
      senderAccel: input.sensorSnapshot.accelPeakMs2,
      receiverAccel: receiver.accel_peak_ms2,
    });

    return {
      matchId: match.match_id,
      receiverDeviceId: receiver.device_id,
      receiverUserId: receiver.user_id,
      rssiScore: nearby.rssi,
      timeDeltaMs,
      sensorValidated: true,
    };
  }

  logger.debug('No matching receiver found for sender bump', {
    correlationId,
    senderDevice: input.deviceId,
    nearbyCount: input.nearbyDevices.length,
    pendingReceiverCount: pendingReceivers.length,
  });

  return null;
}

// ─── Receiver Bump ─────────────────────────────────────────────────────────

/**
 * Receiver posts their bump request (passive side).
 *
 * The receiver's sensor data is validated and stored so that when a sender
 * arrives, the server can correlate timestamps and include receiver's
 * accelerometer reading in the match audit trail.
 */
export async function submitReceiverBump(
  input: ReceiverBumpInput,
  userId: string,
  correlationId: string,
): Promise<{ sensorValidated: boolean }> {
  // Validate sensor data against calibration
  const sensorResult = await validateSensorData(
    input.deviceId, input.sensorSnapshot, correlationId,
  );

  if (!sensorResult.valid) {
    logger.warn('Receiver bump rejected — sensor validation failed', {
      correlationId,
      deviceId: input.deviceId,
      reason: sensorResult.reason,
    });
    throw new ValidationError(
      `Bump rejected: ${sensorResult.reason}`,
    );
  }

  await bumpRepo.createBumpRequest({
    deviceId: input.deviceId,
    userId,
    role: 'RECEIVER',
    rssi: input.rssi,
    accelPeakMs2: input.sensorSnapshot.accelPeakMs2,
    accelDurationMs: input.sensorSnapshot.accelDurationMs,
    gyroMagnitudeRads: input.sensorSnapshot.gyroMagnitudeRads,
    tapTimestamp: input.sensorSnapshot.tapTimestamp,
    sensorValidated: true,
  }, correlationId);

  logger.debug('Receiver bump registered', {
    correlationId,
    receiverDevice: input.deviceId,
    accelPeak: input.sensorSnapshot.accelPeakMs2,
    tapTimestamp: input.sensorSnapshot.tapTimestamp,
  });

  return { sensorValidated: true };
}

// ─── Get Match ─────────────────────────────────────────────────────────────

/**
 * Get match details by match ID.
 */
export async function getMatch(
  matchId: string,
  correlationId: string,
): Promise<BumpMatchRow> {
  const match = await bumpRepo.findMatchById(matchId, correlationId);
  if (!match) {
    throw new NotFoundError('Bump match');
  }
  return match;
}

