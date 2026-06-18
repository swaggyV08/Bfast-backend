import { generateSecureRandom, encryptAES256GCM } from '@/shared/utils/crypto';
import { logger } from '@/shared/utils/logger';
import * as sessionRepo from './session.repository';
import { SessionCodeRow } from '@/shared/types';

/**
 * Session service — business logic for BLE session code management.
 *
 * Session codes are the cryptographic handshake at the heart of B-FAST:
 * - Receiver generates a session code (32-byte random, encrypted with AES-256-GCM)
 * - Broadcast via GATT to nearby sender devices
 * - Sender reads it and includes it in the payment intent
 * - Each code has a 30-second TTL and can only be used once
 */

export interface GeneratedSession {
  scId: string;
  encryptedCode: string;
  expiresAt: Date;
}

/**
 * Generates a new session code for a receiver device.
 *
 * 1. Generates 32-byte cryptographic random code
 * 2. Encrypts with AES-256-GCM
 * 3. Stores in DB with 30s TTL
 * 4. Returns encrypted code + sc_id for GATT broadcast
 */
export async function generateSession(
  receiverDeviceId: string,
  receiverUserId: string,
  correlationId: string,
): Promise<GeneratedSession> {
  // Generate unique session code ID (human-readable, used for lookups)
  const scId = generateSecureRandom(16); // 32 hex chars

  // Generate the actual session code (this is what gets encrypted and broadcast)
  const rawSessionCode = generateSecureRandom(32); // 64 hex chars

  // Encrypt the session code for BLE transmission
  const encryptedCode = encryptAES256GCM(rawSessionCode);

  // Store in DB
  const session = await sessionRepo.createSessionCode({
    scId,
    receiverDeviceId,
    receiverUserId,
    encryptedCodeB64: encryptedCode,
  }, correlationId);

  logger.info('Session code generated', {
    correlationId,
    scId,
    receiverDeviceId,
    expiresAt: session.expires_at.toISOString(),
  });

  return {
    scId: session.sc_id,
    encryptedCode,
    expiresAt: session.expires_at,
  };
}

/**
 * Refreshes the session code for a receiver device.
 * Called every 25 seconds by the receiver app (5-second buffer before 30s TTL).
 *
 * 1. Expires the old session code
 * 2. Generates a new one
 */
export async function refreshSession(
  receiverDeviceId: string,
  receiverUserId: string,
  correlationId: string,
): Promise<GeneratedSession> {
  // Expire any active sessions for this device
  const expired = await sessionRepo.expireSessionsForDevice(
    receiverDeviceId, correlationId,
  );

  logger.debug('Expired old sessions before refresh', {
    correlationId,
    receiverDeviceId,
    expiredCount: expired,
  });

  // Generate fresh session
  return generateSession(receiverDeviceId, receiverUserId, correlationId);
}

/**
 * Validates a session code during transaction processing.
 * Checks: exists, active, not expired, not consumed, device matches.
 */
export async function validateSession(
  scId: string,
  receiverDeviceId: string,
  correlationId: string,
): Promise<SessionCodeRow> {
  const session = await sessionRepo.findActiveSessionByScId(scId, correlationId);

  if (!session) {
    throw new Error('Session code not found or expired');
  }

  // SQL already filters by status='active' AND expires_at > NOW(),
  // so returned sessions are guaranteed valid at DB level.

  if (session.receiver_device_id !== receiverDeviceId) {
    throw new Error('Session code does not belong to this receiver device');
  }

  return session;
}

/**
 * Gets the current active session for a receiver device.
 */
export async function getActiveSession(
  receiverDeviceId: string,
  correlationId: string,
): Promise<SessionCodeRow | null> {
  return sessionRepo.getActiveSessionForDevice(receiverDeviceId, correlationId);
}
