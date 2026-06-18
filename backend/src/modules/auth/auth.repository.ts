import { query } from '@/database/query';
import { UserRow, WalletRow, DeviceRow } from '@/shared/types';


/**
 * The Repository pattern separates database queries from business logic.
 *
 * - auth.repository.ts  → raw SQL, returns DB rows
 * - auth.service.ts     → business logic, calls repository
 * - auth.controller.ts  → HTTP handling, calls service
 *
 * This means if you ever switch databases, you only update the repository.
 */

// ─── User queries ──────────────────────────────────────────────────────────

export async function findUserByEmail(
  email: string,
  correlationId?: string,
): Promise<UserRow | null> {
  const result = await query<UserRow>(
    `SELECT * FROM users WHERE email = $1 AND deleted_at IS NULL`,
    [email],
    correlationId,
  );
  return result.rows[0] ?? null;
}

export async function findUserByPhoneNumber(
  phoneNumber: string,
  correlationId?: string,
): Promise<UserRow | null> {
  const result = await query<UserRow>(
    `SELECT * FROM users WHERE phone_number = $1 AND deleted_at IS NULL`,
    [phoneNumber],
    correlationId,
  );
  return result.rows[0] ?? null;
}

export async function findUserById(
  userId: string,
  correlationId?: string,
): Promise<UserRow | null> {
  const result = await query<UserRow>(
    `SELECT * FROM users WHERE id = $1 AND deleted_at IS NULL`,
    [userId],
    correlationId,
  );
  return result.rows[0] ?? null;
}

export async function createUser(
  data: {
    email?: string;
    passwordHash?: string;
    displayName: string;
    phoneNumber?: string;
    cognitoSub: string;
  },
  correlationId?: string,
): Promise<UserRow> {
  // We store the password hash in cognito_sub field for MVP
  // In production, Cognito handles auth and cognito_sub is the Cognito user ID
  // For MVP without Cognito, we use it to store the bcrypt hash
  const result = await query<UserRow>(
    `INSERT INTO users
       (cognito_sub, email, display_name, phone_number)
     VALUES ($1, $2, $3, $4)
     RETURNING *`,
    [data.cognitoSub, data.email ?? null, data.displayName, data.phoneNumber ?? null],
    correlationId,
  );
  return result.rows[0]!;
}

export async function updateUserLastLogin(
  userId: string,
  correlationId?: string,
): Promise<void> {
  await query(
    `UPDATE users SET last_login_at = NOW() WHERE id = $1`,
    [userId],
    correlationId,
  );
}

// ─── Wallet queries ────────────────────────────────────────────────────────

export async function createWallet(
  userId: string,
  correlationId?: string,
): Promise<WalletRow> {
  const result = await query<WalletRow>(
    `INSERT INTO wallets (user_id, wallet_type, balance_paise, currency)
     VALUES ($1, 'MAIN', 0, 'INR')
     RETURNING *`,
    [userId],
    correlationId,
  );
  return result.rows[0]!;
}

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

export async function setWalletBalance(
  walletId: string,
  balancePaise: number,
  correlationId?: string,
): Promise<void> {
  await query(
    `UPDATE wallets SET balance_paise = $1, updated_at = NOW() WHERE id = $2`,
    [balancePaise, walletId],
    correlationId,
  );
}

// ─── Device queries ────────────────────────────────────────────────────────

export async function upsertDevice(
  data: {
    userId: string;
    deviceId: string;
    platform: string;
    deviceModel?: string;
    osVersion?: string;
    appVersion?: string;
    pushToken?: string;
  },
  correlationId?: string,
): Promise<DeviceRow> {
  // UPSERT: insert if device is new, update if it already exists
  // This handles re-installs and app updates cleanly
  const result = await query<DeviceRow>(
    `INSERT INTO devices
       (user_id, device_id, platform, device_model, os_version, app_version, push_token)
     VALUES ($1, $2, $3, $4, $5, $6, $7)
     ON CONFLICT (device_id)
     DO UPDATE SET
       last_active_at = NOW(),
       app_version    = EXCLUDED.app_version,
       push_token     = COALESCE(EXCLUDED.push_token, devices.push_token),
       os_version     = COALESCE(EXCLUDED.os_version, devices.os_version)
     RETURNING *`,
    [
      data.userId,
      data.deviceId,
      data.platform,
      data.deviceModel ?? null,
      data.osVersion ?? null,
      data.appVersion ?? null,
      data.pushToken ?? null,
    ],
    correlationId,
  );
  return result.rows[0]!;
}

// ─── Refresh token queries ─────────────────────────────────────────────────

export async function storeRefreshToken(
  data: {
    userId: string;
    deviceId: string;
    tokenHash: string;
    expiresAt: Date;
    ipAddress?: string;
    userAgent?: string;
  },
  correlationId?: string,
): Promise<string> {
  const result = await query<{ id: string }>(
    `INSERT INTO refresh_tokens
       (user_id, device_id, token_hash, expires_at, ip_address, user_agent)
     VALUES ($1, $2, $3, $4, $5::inet, $6)
     RETURNING id`,
    [
      data.userId,
      data.deviceId,
      data.tokenHash,
      data.expiresAt,
      data.ipAddress ?? null,
      data.userAgent ?? null,
    ],
    correlationId,
  );
  return result.rows[0]!.id;
}

export async function findRefreshToken(
  tokenHash: string,
  correlationId?: string,
): Promise<{ id: string; user_id: string; device_id: string; is_revoked: boolean; expires_at: Date } | null> {
  const result = await query<{
    id: string;
    user_id: string;
    device_id: string;
    is_revoked: boolean;
    expires_at: Date;
  }>(
    `SELECT id, user_id, device_id, is_revoked, expires_at
     FROM refresh_tokens
     WHERE token_hash = $1`,
    [tokenHash],
    correlationId,
  );
  return result.rows[0] ?? null;
}

export async function revokeRefreshToken(
  tokenId: string,
  reason: string,
  correlationId?: string,
): Promise<void> {
  await query(
    `UPDATE refresh_tokens
     SET is_revoked = TRUE, revoked_at = NOW(), revoked_reason = $1
     WHERE id = $2`,
    [reason, tokenId],
    correlationId,
  );
}

export async function revokeAllUserDeviceTokens(
  userId: string,
  deviceId: string,
  reason: string,
  correlationId?: string,
): Promise<void> {
  await query(
    `UPDATE refresh_tokens
     SET is_revoked = TRUE, revoked_at = NOW(), revoked_reason = $1
     WHERE user_id = $2 AND device_id = $3 AND is_revoked = FALSE`,
    [reason, userId, deviceId],
    correlationId,
  );
}

// ─── Password storage (MVP — replaces Cognito for now) ────────────────────

// We need a place to store password hashes for MVP (before Cognito is wired)
// We'll add a separate passwords table

export async function storePasswordHash(
  userId: string,
  passwordHash: string,
  correlationId?: string,
): Promise<void> {
  await query(
    `INSERT INTO user_passwords (user_id, password_hash)
     VALUES ($1, $2)
     ON CONFLICT (user_id) DO UPDATE SET
       password_hash = EXCLUDED.password_hash,
       updated_at = NOW()`,
    [userId, passwordHash],
    correlationId,
  );
}

export async function getPasswordHash(
  userId: string,
  correlationId?: string,
): Promise<string | null> {
  const result = await query<{ password_hash: string }>(
    `SELECT password_hash FROM user_passwords WHERE user_id = $1`,
    [userId],
    correlationId,
  );
  return result.rows[0]?.password_hash ?? null;
}

export async function storePasscodeHash(
  userId: string,
  passcodeHash: string,
  correlationId?: string,
): Promise<void> {
  await query(
    `INSERT INTO user_passwords (user_id, passcode_hash)
     VALUES ($1, $2)
     ON CONFLICT (user_id) DO UPDATE SET
       passcode_hash = EXCLUDED.passcode_hash,
       updated_at = NOW()`,
    [userId, passcodeHash],
    correlationId,
  );
}

export async function getPasscodeHash(
  userId: string,
  correlationId?: string,
): Promise<string | null> {
  const result = await query<{ passcode_hash: string }>(
    `SELECT passcode_hash FROM user_passwords WHERE user_id = $1`,
    [userId],
    correlationId,
  );
  return result.rows[0]?.passcode_hash ?? null;
}