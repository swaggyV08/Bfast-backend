import { z } from 'zod';

// ─── What is Zod? ──────────────────────────────────────────────────────────
// Zod validates incoming request data at runtime.
// TypeScript types are compile-time only — they don't protect you from
// malicious users sending {"amount": "DROP TABLE users"} in the request body.
// Zod parses + validates + types in one step.

export const registerSchema = z.object({
  email: z
    .string()
    .email('Must be a valid email address')
    .toLowerCase()          // Normalize: "User@Email.COM" → "user@email.com"
    .max(255, 'Email too long'),

  password: z
    .string()
    .min(8, 'Password must be at least 8 characters')
    .max(72, 'Password too long')  // bcrypt truncates at 72 chars
    .regex(
      /^(?=.*[a-z])(?=.*[A-Z])(?=.*\d)/,
      'Password must contain uppercase, lowercase, and a number',
    ),

  displayName: z
    .string()
    .min(2, 'Display name too short')
    .max(100, 'Display name too long')
    .trim(),

  phoneNumber: z
    .string()
    .regex(/^\+[1-9]\d{6,14}$/, 'Phone must be in E.164 format: +919876543210')
    .optional(),

  deviceId: z
    .string()
    .uuid('Device ID must be a valid UUID'),

  platform: z.enum(['android', 'ios', 'web']),

  deviceModel: z.string().max(100).optional(),
  osVersion: z.string().max(50).optional(),
  appVersion: z.string().max(20).optional(),
  pushToken: z.string().max(500).optional(),
});

export const loginSchema = z.object({
  email: z
    .string()
    .email()
    .toLowerCase(),

  password: z
    .string()
    .min(1, 'Password is required'),

  deviceId: z
    .string()
    .uuid('Device ID must be a valid UUID'),

  // Optional — update device info on login
  pushToken: z.string().max(500).optional(),
  appVersion: z.string().max(20).optional(),
});

export const mobileRegisterSchema = z.object({
  phoneNumber: z.string()
    .regex(/^\+91\d{10}$/, 'Must be a valid Indian number: +91 followed by exactly 10 digits'),
  displayName: z.string().min(2).max(100).trim(),
  passcode: z.string().regex(/^\d{4}$/, 'Passcode must be exactly 4 digits'),
  confirmPasscode: z.string().regex(/^\d{4}$/, 'Confirm Passcode must be exactly 4 digits'),
  deviceId: z.string().uuid('Device ID must be a valid UUID'),
  platform: z.enum(['android', 'ios', 'web']).default('android'),
}).refine(d => d.passcode === d.confirmPasscode, {
  message: 'Passcodes do not match',
  path: ['confirmPasscode'],
});

export const mobileLoginSchema = z.object({
  phoneNumber: z.string()
    .regex(/^\+91\d{10}$/, 'Must be a valid Indian number: +91 followed by exactly 10 digits'),
  passcode: z.string().regex(/^\d{4}$/, 'Passcode must be exactly 4 digits'),
  deviceId: z.string().uuid('Device ID must be a valid UUID'),
});

export const refreshSchema = z.object({
  refreshToken: z
    .string()
    .min(1, 'Refresh token is required'),

  deviceId: z
    .string()
    .uuid('Device ID must be a valid UUID'),
});

export const logoutSchema = z.object({
  refreshToken: z
    .string()
    .min(1, 'Refresh token is required'),
});

// TypeScript types inferred directly from the Zod schemas
// One source of truth for both validation and typing
export type RegisterInput = z.infer<typeof registerSchema>;
export type LoginInput = z.infer<typeof loginSchema>;
export type MobileRegisterInput = z.infer<typeof mobileRegisterSchema>;
export type MobileLoginInput = z.infer<typeof mobileLoginSchema>;
export type RefreshInput = z.infer<typeof refreshSchema>;
export type LogoutInput = z.infer<typeof logoutSchema>;