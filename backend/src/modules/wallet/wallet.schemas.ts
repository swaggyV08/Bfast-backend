import { z } from 'zod';

export const topupSchema = z.object({
  amountPaise: z.number().int().min(100, 'Minimum top-up is ₹1 (100 paise)').max(10000000, 'Maximum top-up is ₹1,00,000'),
});

export const ledgerQuerySchema = z.object({
  page: z.coerce.number().int().min(1).default(1),
  limit: z.coerce.number().int().min(1).max(100).default(20),
});

export type TopupInput = z.infer<typeof topupSchema>;
export type LedgerQueryInput = z.infer<typeof ledgerQuerySchema>;
