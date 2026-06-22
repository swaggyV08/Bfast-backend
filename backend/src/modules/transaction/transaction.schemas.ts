import { z } from 'zod';

export const createTransactionSchema = z.object({
  sessionCodeId: z.string().min(1, 'Session code ID is required'),
  receiverDeviceId: z.string().uuid('Receiver device ID must be a valid UUID'),
  amountPaise: z.number().int().min(100, 'Minimum amount is ₹1 (100 paise)'),
  currency: z.string().length(3).default('INR'),
  nonce: z.string().min(16, 'Nonce must be at least 16 characters'),
  clientInitiatedAt: z.string().datetime().optional(),
  rssiAtPayment: z.number().int().min(-120).max(0).optional(),
  idempotencyKey: z.string().max(64).optional(),
  note: z.string().max(200).optional(),
});

export const relayTransactionSchema = z.object({
  sessionCodeId: z.string().min(1, 'Session code ID is required'),
  senderUserId: z.string().uuid('Sender user ID must be a valid UUID'),
  senderDeviceId: z.string().uuid('Sender device ID must be a valid UUID'),
  amountPaise: z.number().int().min(100, 'Minimum amount is ₹1 (100 paise)'),
  currency: z.string().length(3).default('INR'),
  nonce: z.string().min(16, 'Nonce must be at least 16 characters'),
  clientInitiatedAt: z.string().datetime().optional(),
  rssiAtPayment: z.number().int().min(-120).max(0).optional(),
  idempotencyKey: z.string().max(64).optional(),
  note: z.string().max(200).optional(),
});

export const transactionHistorySchema = z.object({
  page: z.coerce.number().int().min(1).default(1),
  limit: z.coerce.number().int().min(1).max(100).default(20),
});

export type CreateTransactionInput = z.infer<typeof createTransactionSchema>;
export type RelayTransactionInput = z.infer<typeof relayTransactionSchema>;
export type TransactionHistoryInput = z.infer<typeof transactionHistorySchema>;

export const commitTransactionSchema = z.object({
  correlationId: z.string().min(1, 'Correlation ID is required'),
  idempotencyKey: z.string().min(1, 'Idempotency Key is required'),
  receiverDeviceId: z.string().min(1, 'Receiver Device ID is required'),
  amountPaise: z.number().int().positive('Amount must be positive'),
});

export const reverseTransactionSchema = z.object({
  idempotencyKey: z.string().min(1, 'Idempotency Key is required'),
  reason: z.string().optional(),
});

export type CommitTransactionInput = z.infer<typeof commitTransactionSchema>;
export type ReverseTransactionInput = z.infer<typeof reverseTransactionSchema>;
