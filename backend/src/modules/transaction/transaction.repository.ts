import { query } from '@/database/query';
import { TransactionRow, TransferResult } from '@/shared/types';

export async function createTransaction(
  data: {
    txRef: string;
    senderUserId: string;
    senderDeviceId: string;
    receiverUserId: string;
    receiverDeviceId: string;
    amountPaise: number;
    currency: string;
    sessionCodeId?: string;
    nonce: string;
    rssiAtPayment?: number;
    idempotencyKey?: string;
    note?: string;
    clientInitiatedAt?: string;
  },
  correlationId?: string,
): Promise<TransactionRow> {
  const result = await query<TransactionRow>(
    `INSERT INTO transactions
       (tx_ref, sender_user_id, sender_device_id,
        receiver_user_id, receiver_device_id,
        amount_paise, currency, session_code_id,
        nonce, rssi_at_payment, idempotency_key, note,
        client_initiated_at)
     VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)
     RETURNING *`,
    [
      data.txRef,
      data.senderUserId, data.senderDeviceId,
      data.receiverUserId, data.receiverDeviceId,
      data.amountPaise, data.currency,
      data.sessionCodeId ?? null,
      data.nonce,
      data.rssiAtPayment ?? null,
      data.idempotencyKey ?? null,
      data.note ?? null,
      data.clientInitiatedAt ?? null,
    ],
    correlationId,
  );
  return result.rows[0]!;
}

export async function confirmTransaction(
  transactionId: string,
  correlationId?: string,
): Promise<TransactionRow> {
  const now = new Date();
  const result = await query<TransactionRow>(
    `UPDATE transactions
     SET status = 'confirmed',
         confirmed_at = $1,
         latency_ms = EXTRACT(MILLISECONDS FROM ($1::timestamptz - COALESCE(client_initiated_at, initiated_at)))::INTEGER
     WHERE id = $2
     RETURNING *`,
    [now, transactionId],
    correlationId,
  );
  return result.rows[0]!;
}

export async function failTransaction(
  transactionId: string,
  failureCode: string,
  failureMessage: string,
  correlationId?: string,
): Promise<void> {
  await query(
    `UPDATE transactions
     SET status = 'failed', failed_at = NOW(),
         failure_code = $1, failure_message = $2
     WHERE id = $3`,
    [failureCode, failureMessage, transactionId],
    correlationId,
  );
}

export async function findByIdempotencyKey(
  idempotencyKey: string,
  correlationId?: string,
): Promise<TransactionRow | null> {
  const result = await query<TransactionRow>(
    `SELECT * FROM transactions WHERE idempotency_key = $1`,
    [idempotencyKey],
    correlationId,
  );
  return result.rows[0] ?? null;
}

export async function findByNonce(
  nonce: string,
  correlationId?: string,
): Promise<TransactionRow | null> {
  const result = await query<TransactionRow>(
    `SELECT * FROM transactions WHERE nonce = $1`,
    [nonce],
    correlationId,
  );
  return result.rows[0] ?? null;
}

export async function getTransactionById(
  transactionId: string,
  correlationId?: string,
): Promise<TransactionRow | null> {
  const result = await query<TransactionRow>(
    `SELECT * FROM transactions WHERE id = $1`,
    [transactionId],
    correlationId,
  );
  return result.rows[0] ?? null;
}

export async function getTransactionByTxRef(
  txRef: string,
  correlationId?: string,
): Promise<TransactionRow | null> {
  const result = await query<TransactionRow>(
    `SELECT * FROM transactions WHERE tx_ref = $1`,
    [txRef],
    correlationId,
  );
  return result.rows[0] ?? null;
}

export async function getUserTransactionHistory(
  userId: string,
  page: number,
  limit: number,
  correlationId?: string,
): Promise<{ transactions: TransactionRow[]; total: number }> {
  const offset = (page - 1) * limit;

  const [txResult, countResult] = await Promise.all([
    query<TransactionRow>(
      `SELECT * FROM transactions
       WHERE sender_user_id = $1 OR receiver_user_id = $1
       ORDER BY initiated_at DESC
       LIMIT $2 OFFSET $3`,
      [userId, limit, offset],
      correlationId,
    ),
    query<{ count: string }>(
      `SELECT COUNT(*) as count FROM transactions
       WHERE sender_user_id = $1 OR receiver_user_id = $1`,
      [userId],
      correlationId,
    ),
  ]);

  return {
    transactions: txResult.rows,
    total: parseInt(countResult.rows[0]?.count ?? '0', 10),
  };
}

/**
 * Execute the atomic double-entry transfer via PostgreSQL function.
 */
export async function executeTransfer(
  transactionId: string,
  senderWalletId: string,
  receiverWalletId: string,
  amountPaise: number,
  correlationId?: string,
): Promise<TransferResult> {
  const result = await query<TransferResult>(
    `SELECT * FROM process_transfer($1, $2, $3, $4)`,
    [transactionId, senderWalletId, receiverWalletId, amountPaise],
    correlationId,
  );
  return result.rows[0]!;
}
