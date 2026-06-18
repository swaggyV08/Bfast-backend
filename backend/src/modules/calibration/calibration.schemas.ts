import { z } from 'zod';

export const storeCalibrationSchema = z.object({
  deviceId: z.string().uuid('Device ID must be a valid UUID'),
  tapThresholdMs2: z.number().min(0.5).max(10).default(2.5),
  tapDurationMaxMs: z.number().int().min(50).max(500).default(200),
  gyroDriftBaselineRads: z.number().min(0).max(2).default(0),
  gyroRejectionThresholdRads: z.number().min(0.1).max(3).default(0.5),
  rssi1mCalibrationDbm: z.number().int().min(-100).max(-20).default(-59),
  emaAlphaQuiet: z.number().min(0.01).max(1).default(0.5),
  emaAlphaNoisy: z.number().min(0.01).max(1).default(0.12),
  pathLossExponent: z.number().min(1).max(6).default(2.0),
});

export type StoreCalibrationInput = z.infer<typeof storeCalibrationSchema>;
