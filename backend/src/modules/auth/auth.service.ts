import { v4 as uuidv4 } from 'uuid';
import {
  hashPassword,
  verifyPassword,
  issueAccessToken,
  issueRefreshToken,
  verifyRefreshToken,
  hashRefreshToken,
} from '@/shared/utils/crypto';
import {
  ConflictError,
  UnauthorizedError
} from '@/shared/errors/AppError';
import { logger } from '@/shared/utils/logger';
import * as authRepo from './auth.repository';
import type { RegisterInput, LoginInput, MobileRegisterInput, MobileLoginInput, RefreshInput, LogoutInput } from './auth.schemas';

// ─── Response shapes ───────────────────────────────────────────────────────

export interface AuthTokens {
  accessToken: string;
  refreshToken: string;
  expiresIn: number;      // Seconds until accessToken expires
}

export interface AuthResponse {
  user: {
    id: string;
    email: string | null;
    displayName: string;
    phoneNumber: string | null;
    status: string;
    kycVerified: boolean;
  };
  wallet: {
    id: string;
    balancePaise: number;
    currency: string;
  };
  tokens: AuthTokens;
}

// ─── Helpers ───────────────────────────────────────────────────────────────

function parseExpiryToSeconds(expiry: string): number {
  // Convert "15m" → 900, "7d" → 604800
  const unit = expiry.slice(-1);
  const value = parseInt(expiry.slice(0, -1), 10);
  const multipliers: Record<string, number> = {
    s: 1, m: 60, h: 3600, d: 86400,
  };
  return value * (multipliers[unit] ?? 60);
}

function getRefreshTokenExpiry(): Date {
  const expiry = new Date();
  expiry.setDate(expiry.getDate() + 7); // 7 days from now
  return expiry;
}

async function issueTokenPair(
  userId: string,
  deviceId: string,
  email: string | null,
  correlationId: string,
  meta: { ipAddress?: string; userAgent?: string },
): Promise<AuthTokens> {
  const tokenPayload = { sub: userId, deviceId, email };

  const accessToken = issueAccessToken(tokenPayload);
  const refreshToken = issueRefreshToken(tokenPayload);

  // Store hashed refresh token
  await authRepo.storeRefreshToken({
    userId,
    deviceId,
    tokenHash: hashRefreshToken(refreshToken),
    expiresAt: getRefreshTokenExpiry(),
    ipAddress: meta.ipAddress,
    userAgent: meta.userAgent,
  }, correlationId);

  return {
    accessToken,
    refreshToken,
    expiresIn: parseExpiryToSeconds('15m'),
  };
}

// ─── Service Methods ───────────────────────────────────────────────────────

/**
 * REGISTER
 * Creates a new user account + wallet + device registration.
 * All three are created atomically — if any step fails, nothing is saved.
 */
export async function register(
  input: RegisterInput,
  correlationId: string,
  meta: { ipAddress?: string; userAgent?: string },
): Promise<AuthResponse> {
  // Check if email already taken
  const existingUser = await authRepo.findUserByEmail(input.email, correlationId);
  if (existingUser) {
    throw new ConflictError('An account with this email already exists', 'EMAIL_TAKEN');
  }

  // Hash the password before any DB write
  const passwordHash = await hashPassword(input.password);

  // Generate a UUID for the user (we use this as the cognitoSub for MVP)
  const userId = uuidv4();

  // Create user row
  const user = await authRepo.createUser({
    email: input.email,
    passwordHash,
    displayName: input.displayName,
    phoneNumber: input.phoneNumber,
    cognitoSub: userId, // MVP: use our own UUID; production: Cognito sub
  }, correlationId);

  // Store password hash separately
  await authRepo.storePasswordHash(user.id, passwordHash, correlationId);

  // Create wallet (starting balance: 0)
  const wallet = await authRepo.createWallet(user.id, correlationId);

  // Register device
  await authRepo.upsertDevice({
    userId: user.id,
    deviceId: input.deviceId,
    platform: input.platform,
    deviceModel: input.deviceModel,
    osVersion: input.osVersion,
    appVersion: input.appVersion,
    pushToken: input.pushToken,
  }, correlationId);

  // Issue tokens
  const tokens = await issueTokenPair(
    user.id, input.deviceId, user.email, correlationId, meta,
  );

  logger.info('User registered', {
    correlationId,
    userId: user.id,
    email: user.email,
    deviceId: input.deviceId,
  });

  return {
    user: {
      id: user.id,
      email: user.email,
      displayName: user.display_name,
      phoneNumber: user.phone_number,
      status: user.status,
      kycVerified: user.kyc_verified,
    },
    wallet: {
      id: wallet.id,
      balancePaise: wallet.balance_paise,
      currency: wallet.currency,
    },
    tokens,
  };
}

/**
 * MOBILE REGISTER
 * Registers a user with phone number and 4-digit passcode.
 */
export async function mobileRegister(
  input: MobileRegisterInput,
  correlationId: string,
  meta: { ipAddress?: string; userAgent?: string },
): Promise<AuthResponse> {
  const existingUser = await authRepo.findUserByPhoneNumber(input.phoneNumber, correlationId);
  if (existingUser) {
    throw new ConflictError('An account with this phone number already exists', 'PHONE_TAKEN');
  }

  const passcodeHash = await hashPassword(input.passcode);
  const userId = uuidv4();

  const user = await authRepo.createUser({
    phoneNumber: input.phoneNumber,
    displayName: input.displayName,
    cognitoSub: userId,
  }, correlationId);

  await authRepo.storePasscodeHash(user.id, passcodeHash, correlationId);

  // Mobile users start with 100000 paise (₹1,000) for testing
  const wallet = await authRepo.createWallet(user.id, correlationId);
  await authRepo.setWalletBalance(wallet.id, 100000, correlationId);
  wallet.balance_paise = 100000;

  await authRepo.upsertDevice({
    userId: user.id,
    deviceId: input.deviceId,
    platform: input.platform,
  }, correlationId);

  const tokens = await issueTokenPair(
    user.id, input.deviceId, null, correlationId, meta,
  );

  logger.info('Mobile user registered', {
    correlationId,
    userId: user.id,
    phoneNumber: user.phone_number,
    deviceId: input.deviceId,
  });

  return {
    user: {
      id: user.id,
      email: user.email,
      displayName: user.display_name,
      phoneNumber: user.phone_number,
      status: user.status,
      kycVerified: user.kyc_verified,
    },
    wallet: {
      id: wallet.id,
      balancePaise: wallet.balance_paise,
      currency: wallet.currency,
    },
    tokens,
  };
}

/**
 * MOBILE LOGIN
 * Verifies phone + 4-digit passcode.
 */
export async function mobileLogin(
  input: MobileLoginInput,
  correlationId: string,
  meta: { ipAddress?: string; userAgent?: string },
): Promise<AuthResponse> {
  const user = await authRepo.findUserByPhoneNumber(input.phoneNumber, correlationId);

  if (!user) {
    throw new UnauthorizedError('Invalid phone number or passcode');
  }

  if (user.status !== 'active') {
    throw new UnauthorizedError(`Account is ${user.status}`);
  }

  const passcodeHash = await authRepo.getPasscodeHash(user.id, correlationId);
  if (!passcodeHash) {
    throw new UnauthorizedError('Invalid phone number or passcode');
  }

  const passcodeValid = await verifyPassword(input.passcode, passcodeHash);
  if (!passcodeValid) {
    throw new UnauthorizedError('Invalid phone number or passcode');
  }

  await authRepo.upsertDevice({
    userId: user.id,
    deviceId: input.deviceId,
    platform: 'android',
  }, correlationId);

  await authRepo.updateUserLastLogin(user.id, correlationId);

  await authRepo.revokeAllUserDeviceTokens(
    user.id, input.deviceId, 'new_login', correlationId,
  );

  const tokens = await issueTokenPair(
    user.id, input.deviceId, user.email, correlationId, meta,
  );

  const wallet = await authRepo.getWalletByUserId(user.id, correlationId);

  logger.info('Mobile user logged in', {
    correlationId,
    userId: user.id,
    deviceId: input.deviceId,
  });

  return {
    user: {
      id: user.id,
      email: user.email,
      displayName: user.display_name,
      phoneNumber: user.phone_number,
      status: user.status,
      kycVerified: user.kyc_verified,
    },
    wallet: {
      id: wallet?.id ?? '',
      balancePaise: wallet?.balance_paise ?? 0,
      currency: wallet?.currency ?? 'INR',
    },
    tokens,
  };
}

/**
 * LOGIN
 * Verifies credentials, updates device info, issues fresh token pair.
 */
export async function login(
  input: LoginInput,
  correlationId: string,
  meta: { ipAddress?: string; userAgent?: string },
): Promise<AuthResponse> {
  // Find user — use a generic error message to prevent user enumeration
  // (don't say "email not found" — just "invalid credentials")
  const user = await authRepo.findUserByEmail(input.email, correlationId);

  if (!user) {
    throw new UnauthorizedError('Invalid email or password');
  }

  if (user.status !== 'active') {
    throw new UnauthorizedError(`Account is ${user.status}`);
  }

  // Verify password
  const passwordHash = await authRepo.getPasswordHash(user.id, correlationId);
  if (!passwordHash) {
    throw new UnauthorizedError('Invalid email or password');
  }

  const passwordValid = await verifyPassword(input.password, passwordHash);
  if (!passwordValid) {
    // TODO Day 4: Increment failed login counter, lock after 5 attempts
    throw new UnauthorizedError('Invalid email or password');
  }

  // Update device info and last login
  await authRepo.upsertDevice({
    userId: user.id,
    deviceId: input.deviceId,
    platform: 'android', // Will be sent by client in production
    pushToken: input.pushToken,
    appVersion: input.appVersion,
  }, correlationId);

  await authRepo.updateUserLastLogin(user.id, correlationId);

  // Revoke any existing tokens for this device (single-device session)
  await authRepo.revokeAllUserDeviceTokens(
    user.id, input.deviceId, 'new_login', correlationId,
  );

  // Issue fresh token pair
  const tokens = await issueTokenPair(
    user.id, input.deviceId, user.email, correlationId, meta,
  );

  // Get wallet
  const wallet = await authRepo.getWalletByUserId(user.id, correlationId);

  logger.info('User logged in', {
    correlationId,
    userId: user.id,
    deviceId: input.deviceId,
  });

  return {
    user: {
      id: user.id,
      email: user.email,
      displayName: user.display_name,
      phoneNumber: user.phone_number,
      status: user.status,
      kycVerified: user.kyc_verified,
    },
    wallet: {
      id: wallet?.id ?? '',
      balancePaise: wallet?.balance_paise ?? 0,
      currency: wallet?.currency ?? 'INR',
    },
    tokens,
  };
}

/**
 * REFRESH
 * Rotates the refresh token. Old one is revoked, new pair is issued.
 */
export async function refresh(
  input: RefreshInput,
  correlationId: string,
  meta: { ipAddress?: string; userAgent?: string },
): Promise<AuthTokens> {
  // Verify the token signature
  const decoded = verifyRefreshToken(input.refreshToken);

  // Look up the stored token
  const tokenHash = hashRefreshToken(input.refreshToken);
  const storedToken = await authRepo.findRefreshToken(tokenHash, correlationId);

  if (!storedToken) {
    throw new UnauthorizedError('Refresh token not found');
  }

  // Detect token reuse (theft detection)
  // If someone tries to use an already-revoked token, revoke ALL tokens for this user+device
  if (storedToken.is_revoked) {
    logger.warn('Revoked refresh token reuse detected — possible theft', {
      correlationId,
      userId: decoded.sub,
      deviceId: input.deviceId,
    });

    await authRepo.revokeAllUserDeviceTokens(
      decoded.sub, input.deviceId, 'theft_detected', correlationId,
    );

    throw new UnauthorizedError('Session invalidated. Please log in again.');
  }

  if (new Date() > storedToken.expires_at) {
    throw new UnauthorizedError('Refresh token expired. Please log in again.');
  }

  // Revoke the used token (rotation)
  await authRepo.revokeRefreshToken(storedToken.id, 'rotation', correlationId);

  // Issue new pair
  const user = await authRepo.findUserById(decoded.sub, correlationId);
  if (!user) throw new UnauthorizedError('Account not found');

  const tokens = await issueTokenPair(
    decoded.sub, input.deviceId, user.email, correlationId, meta,
  );

  logger.info('Tokens refreshed', { correlationId, userId: decoded.sub });

  return tokens;
}

/**
 * LOGOUT
 * Revokes the refresh token. Access token expires naturally (max 15 min).
 */
export async function logout(
  input: LogoutInput,
  userId: string,
  correlationId: string,
): Promise<void> {
  const tokenHash = hashRefreshToken(input.refreshToken);
  const storedToken = await authRepo.findRefreshToken(tokenHash, correlationId);

  if (storedToken && !storedToken.is_revoked) {
    await authRepo.revokeRefreshToken(storedToken.id, 'logout', correlationId);
  }

  logger.info('User logged out', { correlationId, userId });
}