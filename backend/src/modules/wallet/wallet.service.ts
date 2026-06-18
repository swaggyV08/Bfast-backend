import { logger } from '@/shared/utils/logger';
import { NotFoundError, ValidationError } from '@/shared/errors/AppError';
import { env } from '@/config/env';
import * as walletRepo from './wallet.repository';
import { LedgerEntryRow } from '@/shared/types';

export interface WalletBalance {
  walletId: string;
  balancePaise: number;
  balanceINR: string;
  currency: string;
  isFrozen: boolean;
}

export async function getBalance(
  userId: string,
  correlationId: string,
): Promise<WalletBalance> {
  const wallet = await walletRepo.getWalletByUserId(userId, correlationId);
  if (!wallet) {
    throw new NotFoundError('Wallet');
  }

  return {
    walletId: wallet.id,
    balancePaise: wallet.balance_paise,
    balanceINR: (wallet.balance_paise / 100).toFixed(2),
    currency: wallet.currency,
    isFrozen: wallet.is_frozen,
  };
}

export async function topup(
  userId: string,
  amountPaise: number,
  correlationId: string,
): Promise<WalletBalance> {
  // Top-up is dev/test only
  if (env.IS_PRODUCTION) {
    throw new ValidationError('Top-up is disabled in production');
  }

  const wallet = await walletRepo.topupWallet(userId, amountPaise, correlationId);

  logger.info('Wallet topped up', {
    correlationId,
    userId,
    amountPaise,
    newBalancePaise: wallet.balance_paise,
  });

  return {
    walletId: wallet.id,
    balancePaise: wallet.balance_paise,
    balanceINR: (wallet.balance_paise / 100).toFixed(2),
    currency: wallet.currency,
    isFrozen: wallet.is_frozen,
  };
}

export async function getLedger(
  userId: string,
  page: number,
  limit: number,
  correlationId: string,
): Promise<{ entries: LedgerEntryRow[]; total: number; page: number; limit: number }> {
  const result = await walletRepo.getLedgerEntries(userId, page, limit, correlationId);
  return { ...result, page, limit };
}
