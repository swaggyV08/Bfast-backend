import crypto from 'crypto';
import bcrypt from 'bcryptjs';
import jwt from 'jsonwebtoken';
import { env } from '@/config/env';
import { JwtPayload } from '@/shared/types';
import { UnauthorizedError } from '@/shared/errors/AppError';

// ─── Password Hashing ──────────────────────────────────────────────────────

const BCRYPT_ROUNDS = 12;
// 12 rounds = ~250ms on modern hardware.
// Fast enough for login UX, slow enough to defeat GPU brute-force.

/**
 * Hashes a plaintext password using bcrypt.
 * The salt is embedded in the output — no need to store it separately.
 */
export async function hashPassword(password: string): Promise<string> {
  return bcrypt.hash(password, BCRYPT_ROUNDS);
}

/**
 * Compares a plaintext password against a bcrypt hash.
 * Uses constant-time comparison — prevents timing attacks.
 */
export async function verifyPassword(
  plaintext: string,
  hash: string,
): Promise<boolean> {
  return bcrypt.compare(plaintext, hash);
}

// ─── JWT ───────────────────────────────────────────────────────────────────

/**
 * Issues a short-lived access token (15 minutes).
 * The token is signed with HS256 using JWT_SECRET.
 */
export function issueAccessToken(payload: Omit<JwtPayload, 'iat' | 'exp' | 'type'>): string {
  return jwt.sign(
    { ...payload, type: 'access' },
    env.JWT_SECRET,
    { expiresIn: env.JWT_EXPIRES_IN as any, algorithm: 'HS256' },
  );
}

/**
 * Issues a long-lived refresh token (7 days).
 * Uses a DIFFERENT secret suffix to prevent refresh tokens being used as access tokens.
 */
export function issueRefreshToken(payload: Omit<JwtPayload, 'iat' | 'exp' | 'type'>): string {
    return jwt.sign(
        { ...payload, type: 'refresh' },
        env.JWT_SECRET + '_refresh', // Different secret = different token family
        { expiresIn: env.JWT_REFRESH_EXPIRES_IN as any, algorithm: 'HS256' },
    );
}

/**
 * Verifies and decodes an access token.
 * Throws UnauthorizedError if invalid, expired, or wrong type.
 */
export function verifyAccessToken(token: string): JwtPayload {
  try {
    const decoded = jwt.verify(token, env.JWT_SECRET, {
      algorithms: ['HS256'],
    }) as JwtPayload;

    // Prevent refresh tokens from being used as access tokens
    if (decoded.type !== 'access') {
      throw new UnauthorizedError('Invalid token type');
    }

    return decoded;
  } catch (error) {
    if (error instanceof UnauthorizedError) throw error;
    if (error instanceof jwt.TokenExpiredError) {
      throw new UnauthorizedError('Token expired');
    }
    throw new UnauthorizedError('Invalid token');
  }
}

/**
 * Verifies and decodes a refresh token.
 */
export function verifyRefreshToken(token: string): JwtPayload {
  try {
    const decoded = jwt.verify(token, env.JWT_SECRET + '_refresh', {
      algorithms: ['HS256'],
    }) as JwtPayload;

    if (decoded.type !== 'refresh') {
      throw new UnauthorizedError('Invalid token type');
    }

    return decoded;
  } catch (error) {
    if (error instanceof UnauthorizedError) throw error;
    if (error instanceof jwt.TokenExpiredError) {
      throw new UnauthorizedError('Refresh token expired — please log in again');
    }
    throw new UnauthorizedError('Invalid refresh token');
  }
}

// ─── Refresh Token Hashing ─────────────────────────────────────────────────

/**
 * Hashes a refresh token for secure DB storage.
 * We use SHA-256 here (not bcrypt) because:
 * - We're hashing a cryptographically random token, not a user password
 * - SHA-256 is deterministic — we need to look it up by value
 * - Refresh tokens are already 256 bits of entropy — no need for bcrypt's slowness
 */
export function hashRefreshToken(token: string): string {
  return crypto.createHash('sha256').update(token).digest('hex');
}

// ─── Secure Random ─────────────────────────────────────────────────────────

/**
 * Generates a cryptographically secure random string.
 * Used for session codes, nonces, and idempotency keys.
 */
export function generateSecureRandom(bytes = 32): string {
  return crypto.randomBytes(bytes).toString('hex');
}

// ─── AES-256-GCM Encryption ────────────────────────────────────────────────

const AES_ALGORITHM = 'aes-256-gcm';
const IV_LENGTH = 12;   // 96 bits — NIST recommended for GCM
const TAG_LENGTH = 16;  // 128-bit auth tag

/**
 * Derives the AES key buffer from the hex string in env.
 * Called once at module load — not per request.
 */
function getAESKey(): Buffer {
  return Buffer.from(env.AES_KEY_HEX, 'hex');
}

/**
 * Encrypts plaintext using AES-256-GCM.
 *
 * Output format (base64-encoded):
 *   [12-byte IV] + [ciphertext] + [16-byte auth tag]
 *
 * Every call generates a unique random IV, so encrypting the same
 * plaintext twice produces different ciphertext — critical for security.
 */
export function encryptAES256GCM(plaintext: string): string {
  const iv = crypto.randomBytes(IV_LENGTH);
  const cipher = crypto.createCipheriv(AES_ALGORITHM, getAESKey(), iv, {
    authTagLength: TAG_LENGTH,
  });

  const encrypted = Buffer.concat([
    cipher.update(plaintext, 'utf8'),
    cipher.final(),
  ]);

  const tag = cipher.getAuthTag();

  // Pack: IV + ciphertext + tag → base64
  const packed = Buffer.concat([iv, encrypted, tag]);
  return packed.toString('base64');
}

/**
 * Decrypts an AES-256-GCM ciphertext produced by encryptAES256GCM.
 *
 * Validates the authentication tag — if the ciphertext was tampered with,
 * this function throws (preventing forgery attacks).
 */
export function decryptAES256GCM(ciphertextB64: string): string {
  const packed = Buffer.from(ciphertextB64, 'base64');

  // Unpack: first 12 bytes = IV, last 16 bytes = tag, middle = ciphertext
  const iv = packed.subarray(0, IV_LENGTH);
  const tag = packed.subarray(packed.length - TAG_LENGTH);
  const encrypted = packed.subarray(IV_LENGTH, packed.length - TAG_LENGTH);

  const decipher = crypto.createDecipheriv(AES_ALGORITHM, getAESKey(), iv, {
    authTagLength: TAG_LENGTH,
  });
  decipher.setAuthTag(tag);

  const decrypted = Buffer.concat([
    decipher.update(encrypted),
    decipher.final(),
  ]);

  return decrypted.toString('utf8');
}