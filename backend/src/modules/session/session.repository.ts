import { query } from '@/database/query';
import { SessionCodeRow } from '@/shared/types';

/**
 * Session code repository — raw SQL, returns DB rows.
 */

export async function createSessionCode(
  data: {
    scId: string;
    receiverDeviceId: string;
    receiverUserId: string;
    encryptedCodeB64: string;
  },
  correlationId?: string,
): Promise<SessionCodeRow> {
  const result = await query<SessionCodeRow>(
    `INSERT INTO session_codes
       (sc_id, receiver_device_id, receiver_user_id, encrypted_code_b64)
     VALUES ($1, $2, $3, $4)
     RETURNING *`,
    [data.scId, data.receiverDeviceId, data.receiverUserId, data.encryptedCodeB64],
    correlationId,
  );
  return result.rows[0]!;
}

export async function findActiveSessionByScId(
  scId: string,
  correlationId?: string,
): Promise<SessionCodeRow | null> {
  const result = await query<SessionCodeRow>(
    `SELECT * FROM session_codes
     WHERE sc_id = $1 AND status = 'active' AND expires_at > NOW()`,
    [scId],
    correlationId,
  );
  return result.rows[0] ?? null;
}

export async function getActiveSessionForDevice(
  receiverDeviceId: string,
  correlationId?: string,
): Promise<SessionCodeRow | null> {
  const result = await query<SessionCodeRow>(
    `SELECT * FROM session_codes
     WHERE receiver_device_id = $1 AND status = 'active' AND expires_at > NOW()
     ORDER BY created_at DESC LIMIT 1`,
    [receiverDeviceId],
    correlationId,
  );
  return result.rows[0] ?? null;
}

export async function consumeSessionCode(
  sessionCodeId: string,
  transactionId: string,
  correlationId?: string,
): Promise<void> {
  await query(
    `UPDATE session_codes
     SET status = 'consumed', consumed_at = NOW(), consumed_by_tx_id = $1
     WHERE id = $2`,
    [transactionId, sessionCodeId],
    correlationId,
  );
}

export async function expireSessionsForDevice(
  receiverDeviceId: string,
  correlationId?: string,
): Promise<number> {
  const result = await query(
    `UPDATE session_codes
     SET status = 'expired'
     WHERE receiver_device_id = $1 AND status = 'active'`,
    [receiverDeviceId],
    correlationId,
  );
  return result.rowCount ?? 0;
}

export async function expireStaleSessionCodes(
  correlationId?: string,
): Promise<number> {
  const result = await query(
    `UPDATE session_codes
     SET status = 'expired'
     WHERE expires_at < NOW() AND status = 'active'`,
    [],
    correlationId,
  );
  return result.rowCount ?? 0;
}
