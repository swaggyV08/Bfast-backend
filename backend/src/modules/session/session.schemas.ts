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
