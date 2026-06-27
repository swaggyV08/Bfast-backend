import { z } from 'zod';

export const generateSessionSchema = z.object({
  deviceId: z.string().uuid('Device ID must be a valid UUID'),
});

export const refreshSessionSchema = z.object({
  deviceId: z.string().uuid('Device ID must be a valid UUID'),
});

export const validateSessionSchema = z.object({
  scId: z.string().min(1, 'Session code ID is required'),
  receiverDeviceId: z.string().uuid('Receiver device ID must be a valid UUID'),
});

export type GenerateSessionInput = z.infer<typeof generateSessionSchema>;
export type RefreshSessionInput = z.infer<typeof refreshSessionSchema>;
export type ValidateSessionInput = z.infer<typeof validateSessionSchema>;

export const correlateTapSchema = z.object({
  sessionId: z.string().min(1, 'Session ID is required'),
  senderDeviceId: z.string().min(1, 'Sender Device ID is required'),
  receiverDeviceId: z.string().min(1, 'Receiver Device ID is required'),
  amountPaise: z.number().int().positive('Amount must be positive'),
  rangingMethod: z.string().min(1, 'Ranging method is required'), // "CS" or "RSSI"
  reportedDistanceCm: z.number().nullable().optional(),
  senderRssiDb: z.number().int().nullable().optional(),
  receiverRssiDb: z.number().int().nullable().optional()
});

export type CorrelateTapInput = z.infer<typeof correlateTapSchema>;

// ── Tap Event (receiver reports a physical tap to backend) ─────────────────
export const tapEventSchema = z.object({
  receiverDeviceId: z.string().min(1, 'Receiver device ID is required'),
  senderDeviceId: z.string().optional().default(''),
  accelPeakMs2: z.number().min(0, 'Acceleration must be non-negative'),
  rssi: z.number().int().optional().default(0),
  tapTimestamp: z.string().optional().default(() => new Date().toISOString()),
});

export type TapEventInput = z.infer<typeof tapEventSchema>;
