import { generateSecureRandom, encryptAES256GCM } from '@/shared/utils/crypto';
import { logger } from '@/shared/utils/logger';
import * as sessionRepo from './session.repository';
import { SessionCodeRow } from '@/shared/types';

// ── In-memory tap event store ───────────────────────────────────────────────
// key = "receiverDeviceId:senderDeviceId", TTL 5 seconds
// Receiver POSTs here on tap. Sender polls here every 300ms.
const TAP_EVENT_TTL_MS = 5000;

interface TapEvent {
  tapEventId: string;
  timestamp: number;
  accelPeakMs2: number;
  scId: string;
}
const tapEventStore = new Map<string, TapEvent>();

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

// ── Tap Event Endpoints ─────────────────────────────────────────────────────

/**
 * RECEIVER calls this immediately when its accelerometer detects a tap.
 * Stores the event in memory so the sender can poll for it.
 */
export async function reportReceiverTap(
  input: { receiverDeviceId: string; senderDeviceId?: string; accelPeakMs2: number; rssi?: number; tapTimestamp?: string },
  _userId: string,
  correlationId: string,
): Promise<{ tapEventId: string }> {
  const key = input.receiverDeviceId; // keyed only by receiver — sender ID not needed
  const tapEventId = generateSecureRandom(8);

  // Fetch the active session's scId so the sender can submit a transaction
  // without a GATT connection. Failure is non-fatal — scId just stays empty.
  let scId = '';
  try {
    const activeSession = await sessionRepo.getActiveSessionForDevice(
      input.receiverDeviceId, correlationId,
    );
    scId = activeSession?.sc_id ?? '';
  } catch (_) {}

  tapEventStore.set(key, {
    tapEventId,
    timestamp: Date.now(),
    accelPeakMs2: input.accelPeakMs2,
    scId,
  });

  // Auto-expire after TTL
  setTimeout(() => tapEventStore.delete(key), TAP_EVENT_TTL_MS);

  logger.info('Receiver tap event registered', {
    correlationId,
    key,
    tapEventId,
    accelPeakMs2: input.accelPeakMs2,
    rssi: input.rssi ?? 0,
  });

  return { tapEventId };
}

/**
 * SENDER polls this ~every 300ms to know when the receiver has confirmed a tap.
 * Returns confirmed=true if a fresh tap event exists for this device pair.
 * Does NOT consume the event (TTL handles cleanup — idempotent within window).
 */
export async function pollTapStatus(
  _senderDeviceId: string,
  receiverDeviceId: string,
  correlationId: string,
): Promise<{ confirmed: boolean; scId?: string; receiverDeviceId?: string }> {
  const key = receiverDeviceId; // keyed only by receiver — matches reportReceiverTap
  const event = tapEventStore.get(key);

  if (!event) {
    return { confirmed: false };
  }

  const age = Date.now() - event.timestamp;
  if (age > TAP_EVENT_TTL_MS) {
    tapEventStore.delete(key);
    return { confirmed: false };
  }

  logger.debug('Tap poll: confirmed', { correlationId, key, ageMs: age, scId: event.scId });
  return { confirmed: true, scId: event.scId, receiverDeviceId: key };
}

/**
 * Validates a Tap correlation intent from a Sender.
 */
export async function correlateTap(
  input: {
    sessionId: string;
    senderDeviceId: string;
    receiverDeviceId: string;
    amountPaise: number;
    rangingMethod: string;
    reportedDistanceCm?: number | null;
    senderRssiDb?: number | null;
    receiverRssiDb?: number | null;
  },
  _userId: string,
  requestCorrelationId: string
): Promise<{ correlationId: string, status: string }> {
  // Check if receiver has an active session matching the sessionId
  const session = await sessionRepo.findActiveSessionByScId(input.sessionId, requestCorrelationId);
  
  if (!session) {
    // If there is no active session matching this ID, the receiver is offline or the session expired.
    return { correlationId: '', status: 'PEER_OFFLINE' };
  }

  if (session.receiver_device_id !== input.receiverDeviceId) {
    return { correlationId: '', status: 'PEER_OFFLINE' };
  }

  // RSSI Spoof-Plausibility Check (Optional but recommended for legacy RSSI)
  if (input.rangingMethod === 'RSSI' && input.senderRssiDb != null && input.receiverRssiDb != null) {
    const diff = Math.abs(input.senderRssiDb - input.receiverRssiDb);
    if (diff > 30) { // Unlikely that two devices touching have 30dBm difference
      logger.warn('Spoof suspected: High RSSI variance', {
        senderRssiDb: input.senderRssiDb,
        receiverRssiDb: input.receiverRssiDb,
        diff
      });
      return { correlationId: '', status: 'SPOOF_SUSPECTED' };
    }
  }

  // Session is active and matches.
  // We generate a correlationId that the client will use for the final commit.
  const commitCorrelationId = generateSecureRandom(16);

  logger.info('Tap correlated successfully', {
    requestCorrelationId,
    commitCorrelationId,
    senderDeviceId: input.senderDeviceId,
    receiverDeviceId: input.receiverDeviceId
  });

  return { correlationId: commitCorrelationId, status: 'MATCHED' };
}
