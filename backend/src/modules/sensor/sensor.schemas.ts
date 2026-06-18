import { z } from 'zod';

export const sensorReadingSchema = z.object({
  deviceId: z.string().min(1),
  accelX: z.number(),
  accelY: z.number(),
  accelZ: z.number(),
  accelMagnitude: z.number(),
  gyroX: z.number(),
  gyroY: z.number(),
  gyroZ: z.number(),
  gyroMagnitude: z.number(),
  tapDetected: z.boolean(),
  tapConfidence: z.number().min(0).max(1).optional(),
  sampleRateHz: z.number().int().optional(),
  recordedAt: z.string().datetime({ offset: true }),
});

export const batchSensorReadingsSchema = z.object({
  readings: z.array(sensorReadingSchema).max(1000, 'Max 1000 readings per batch'),
});

export type SensorReadingInput = z.infer<typeof sensorReadingSchema>;
export type BatchSensorReadingsInput = z.infer<typeof batchSensorReadingsSchema>;
