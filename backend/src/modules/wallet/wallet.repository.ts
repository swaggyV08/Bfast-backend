import { query } from '@/database/query';
import { WalletRow, LedgerEntryRow } from '@/shared/types';

export async function getWalletByUserId(
  userId: string,
  correlationId?: string,
): Promise<WalletRow | null> {
  const result = await query<WalletRow>(
    `SELECT * FROM wallets WHERE user_id = $1 AND wallet_type = 'MAIN'`,
    [userId],
    correlationId,
  );
  return result.rows[0] ?? null;
}

export async function getWalletById(
  walletId: string,
  correlationId?: string,
): Promise<WalletRow | null> {
  const result = await query<WalletRow>(
    `SELECT * FROM wallets WHERE id = $1`,
    [walletId],
    correlationId,
  );
  return result.rows[0] ?? null;
}

export async function topupWallet(
  userId: string,
  amountPaise: number,
  correlationId?: string,
): Promise<WalletRow> {
  const result = await query<WalletRow>(
    `UPDATE wallets
     SET balance_paise = balance_paise + $1
     WHERE user_id = $2 AND wallet_type = 'MAIN'
     RETURNING *`,
    [amountPaise, userId],
    correlationId,
  );
  return result.rows[0]!;
}

export async function getLedgerEntries(
  userId: string,
  page: number,
  limit: number,
  correlationId?: string,
): Promise<{ entries: LedgerEntryRow[]; total: number }> {
  const offset = (page - 1) * limit;

  const [entriesResult, countResult] = await Promise.all([
    query<LedgerEntryRow>(
      `SELECT * FROM ledger_entries
       WHERE user_id = $1
       ORDER BY created_at DESC
       LIMIT $2 OFFSET $3`,
      [userId, limit, offset],
      correlationId,
    ),
    query<{ count: string }>(
      `SELECT COUNT(*) as count FROM ledger_entries WHERE user_id = $1`,
      [userId],
      correlationId,
    ),
  ]);

  return {
    entries: entriesResult.rows,
    total: parseInt(countResult.rows[0]?.count ?? '0', 10),
  };
}

export async function freezeWallet(
  walletId: string,
  reason: string,
  correlationId?: string,
): Promise<void> {
  await query(
    `UPDATE wallets SET is_frozen = TRUE, frozen_at = NOW(), frozen_reason = $1 WHERE id = $2`,
    [reason, walletId],
    correlationId,
  );
}

export async function unfreezeWallet(
  walletId: string,
  correlationId?: string,
): Promise<void> {
  await query(
    `UPDATE wallets SET is_frozen = FALSE, frozen_at = NULL, frozen_reason = NULL WHERE id = $1`,
    [walletId],
    correlationId,
  );
}
