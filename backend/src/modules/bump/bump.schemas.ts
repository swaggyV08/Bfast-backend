import { z } from 'zod';

const nearbyDeviceSchema = z.object({
  deviceId: z.string().min(1, 'Device ID required'),
  rssi: z.number().int().min(-120).max(0),
  bleConfidence: z.number().min(0).max(1).default(0),
});

/**
 * Sensor snapshot captured at the moment of tap by the Android app.
 *
 * The phone's accelerometer detects the impact spike and the gyroscope
 * confirms the phone was stationary (not being waved). These processed
 * values are validated server-side against the device's calibration profile.
 */
const sensorSnapshotSchema = z.object({
  // Peak acceleration magnitude at tap (m/s²). Range: 0–100.
  // A real tap on most Android devices produces 3–20 m/s².
  accelPeakMs2: z.number().min(0).max(100),

  // Duration of the acceleration spike (ms). Range: 10–1000.
  // A genuine tap lasts 30–150ms; a drop or push is longer.
  accelDurationMs: z.number().int().min(10).max(1000),

  // Angular velocity magnitude at tap time (rad/s). Range: 0–50.
  // A stationary phone reads <0.3 rad/s; a waving phone reads 2–10+.
  gyroMagnitudeRads: z.number().min(0).max(50),

  // Client-side tap detection timestamp (ISO 8601 with timezone).
  // Used for ±500ms correlation between sender and receiver bumps.
  tapTimestamp: z.string().datetime({ offset: true }),

  // Confidences from DSP layers
  accelConfidence: z.number().min(0).max(1).default(0),
  gyroConfidence: z.number().min(0).max(1).default(0),
});

export const senderBumpSchema = z.object({
  deviceId: z.string().uuid('Device ID must be a valid UUID'),
  nearbyDevices: z.array(nearbyDeviceSchema).min(1, 'At least one nearby device required'),
  rssi: z.number().int().min(-120).max(0).optional(),
  sensorSnapshot: sensorSnapshotSchema,
});

export const receiverBumpSchema = z.object({
  deviceId: z.string().uuid('Device ID must be a valid UUID'),
  rssi: z.number().int().min(-120).max(0).optional(),
  sensorSnapshot: sensorSnapshotSchema,
});

export type SensorSnapshot = z.infer<typeof sensorSnapshotSchema>;
export type SenderBumpInput = z.infer<typeof senderBumpSchema>;
export type ReceiverBumpInput = z.infer<typeof receiverBumpSchema>;
