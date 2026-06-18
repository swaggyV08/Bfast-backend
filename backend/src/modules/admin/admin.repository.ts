import { query } from '@/database/query';
import { TransactionRow, UserRow, WalletRow } from '@/shared/types';

export interface SystemStats {
  totalUsers: number;
  totalTransactions: number;
  totalVolumePaise: number;
  activeSessions: number;
  confirmedTransactions: number;
  failedTransactions: number;
}

export async function getSystemStats(
  correlationId?: string,
): Promise<SystemStats> {
  const [users, transactions, volume, sessions, confirmed, failed] = await Promise.all([
    query<{ count: string }>('SELECT COUNT(*) as count FROM users WHERE deleted_at IS NULL', [], correlationId),
    query<{ count: string }>('SELECT COUNT(*) as count FROM transactions', [], correlationId),
    query<{ total: string }>('SELECT COALESCE(SUM(amount_paise), 0) as total FROM transactions WHERE status = \'confirmed\'', [], correlationId),
    query<{ count: string }>('SELECT COUNT(*) as count FROM session_codes WHERE status = \'active\' AND expires_at > NOW()', [], correlationId),
    query<{ count: string }>('SELECT COUNT(*) as count FROM transactions WHERE status = \'confirmed\'', [], correlationId),
    query<{ count: string }>('SELECT COUNT(*) as count FROM transactions WHERE status = \'failed\'', [], correlationId),
  ]);

  return {
    totalUsers: parseInt(users.rows[0]?.count ?? '0', 10),
    totalTransactions: parseInt(transactions.rows[0]?.count ?? '0', 10),
    totalVolumePaise: parseInt(volume.rows[0]?.total ?? '0', 10),
    activeSessions: parseInt(sessions.rows[0]?.count ?? '0', 10),
    confirmedTransactions: parseInt(confirmed.rows[0]?.count ?? '0', 10),
    failedTransactions: parseInt(failed.rows[0]?.count ?? '0', 10),
  };
}

export async function getAllTransactions(
  page: number,
  limit: number,
  status?: string,
  correlationId?: string,
): Promise<{ transactions: TransactionRow[]; total: number }> {
  const offset = (page - 1) * limit;
  const statusFilter = status ? 'WHERE status = $3' : '';
  const params = status ? [limit, offset, status] : [limit, offset];

  const [txResult, countResult] = await Promise.all([
    query<TransactionRow>(
      `SELECT * FROM transactions ${statusFilter} ORDER BY initiated_at DESC LIMIT $1 OFFSET $2`,
      params,
      correlationId,
    ),
    query<{ count: string }>(
      `SELECT COUNT(*) as count FROM transactions ${statusFilter}`,
      status ? [status] : [],
      correlationId,
    ),
  ]);

  return {
    transactions: txResult.rows,
    total: parseInt(countResult.rows[0]?.count ?? '0', 10),
  };
}

export async function suspendUser(
  userId: string,
  correlationId?: string,
): Promise<UserRow> {
  const result = await query<UserRow>(
    `UPDATE users SET status = 'suspended' WHERE id = $1 RETURNING *`,
    [userId],
    correlationId,
  );
  return result.rows[0]!;
}

export async function activateUser(
  userId: string,
  correlationId?: string,
): Promise<UserRow> {
  const result = await query<UserRow>(
    `UPDATE users SET status = 'active' WHERE id = $1 RETURNING *`,
    [userId],
    correlationId,
  );
  return result.rows[0]!;
}

export async function freezeWalletAdmin(
  walletId: string,
  correlationId?: string,
): Promise<WalletRow> {
  const result = await query<WalletRow>(
    `UPDATE wallets SET is_frozen = TRUE WHERE id = $1 RETURNING *`,
    [walletId],
    correlationId,
  );
  return result.rows[0]!;
}

export async function unfreezeWalletAdmin(
  walletId: string,
  correlationId?: string,
): Promise<WalletRow> {
  const result = await query<WalletRow>(
    `UPDATE wallets SET is_frozen = FALSE WHERE id = $1 RETURNING *`,
    [walletId],
    correlationId,
  );
  return result.rows[0]!;
}
