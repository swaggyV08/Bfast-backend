import { Request } from 'express';

// ─── JWT ───────────────────────────────────────────────────────────────────

export interface JwtPayload {
  sub: string;        // User ID
  deviceId: string;   // Device UUID
  email: string | null;
  iat: number;        // Issued at
  exp: number;        // Expiry
  type: 'access' | 'refresh';  // Prevent using refresh token as access token
}

// ─── Express ───────────────────────────────────────────────────────────────

export interface AuthenticatedRequest extends Request {
  user: JwtPayload;
  correlationId: string;
}

// ─── API Response ──────────────────────────────────────────────────────────

export interface ApiResponse<T = unknown> {
  success: boolean;
  data?: T;
  error?: {
    code: string;
    message: string;
    details?: Record<string, string[]>; // Field-level validation errors
  };
  meta?: {
    timestamp: string;
    correlationId: string;
  };
}

// ─── Database Row Types ────────────────────────────────────────────────────
// These mirror exactly what PostgreSQL returns — no guessing

export interface UserRow {
  id: string;
  cognito_sub: string;
  email: string;
  phone_number: string | null;
  display_name: string;
  status: 'active' | 'suspended' | 'deactivated';
  kyc_verified: boolean;
  tx_limit_paise: number;
  created_at: Date;
  updated_at: Date;
  last_login_at: Date | null;
  deleted_at: Date | null;
}

export interface WalletRow {
  id: string;
  user_id: string;
  wallet_type: string;
  balance_paise: number;
  currency: string;
  is_frozen: boolean;
  created_at: Date;
  updated_at: Date;
}

export interface DeviceRow {
  id: string;
  user_id: string;
  device_id: string;
  platform: 'android' | 'ios' | 'web';
  device_model: string | null;
  os_version: string | null;
  app_version: string | null;
  push_token: string | null;
  first_seen_at: Date;
  last_active_at: Date;
  is_blocked: boolean;
}

export interface RefreshTokenRow {
  id: string;
  user_id: string;
  device_id: string;
  token_hash: string;
  is_revoked: boolean;
  expires_at: Date;
  issued_at: Date;
}

// ─── Session Codes ─────────────────────────────────────────────────────────

export interface SessionCodeRow {
  id: string;
  sc_id: string;
  receiver_device_id: string;
  receiver_user_id: string;
  encrypted_code_b64: string;
  status: 'active' | 'consumed' | 'expired';
  created_at: Date;
  expires_at: Date;
  consumed_at: Date | null;
  consumed_by_tx_id: string | null;
}

// ─── Transactions ──────────────────────────────────────────────────────────

export interface TransactionRow {
  id: string;
  tx_ref: string;
  sender_user_id: string;
  sender_device_id: string;
  receiver_user_id: string;
  receiver_device_id: string;
  amount_paise: number;
  currency: string;
  session_code_id: string | null;
  status: 'pending' | 'confirmed' | 'failed' | 'reversed';
  idempotency_key: string | null;
  nonce: string;
  rssi_at_payment: number | null;
  failure_code: string | null;
  failure_message: string | null;
  note: string | null;
  initiated_at: Date;
  confirmed_at: Date | null;
  failed_at: Date | null;
  client_initiated_at: Date | null;
  latency_ms: number | null;
}

// ─── Ledger Entries ────────────────────────────────────────────────────────

export interface LedgerEntryRow {
  id: string;
  transaction_id: string;
  wallet_id: string;
  user_id: string;
  entry_type: 'debit' | 'credit';
  amount_paise: number;
  balance_before_paise: number;
  balance_after_paise: number;
  created_at: Date;
}

// ─── Bump Requests & Matches ───────────────────────────────────────────────

export interface NearbyDevice {
  deviceId: string;
  rssi: number;
  bleConfidence?: number;
}

export interface BumpRequestRow {
  id: string;
  device_id: string;
  user_id: string;
  role: 'SENDER' | 'RECEIVER';
  nearby_devices: NearbyDevice[] | null;
  rssi: number | null;
  accel_peak_ms2: number | null;
  accel_duration_ms: number | null;
  gyro_magnitude_rads: number | null;
  tap_timestamp: Date | null;
  sensor_validated: boolean;
  created_at: Date;
  expires_at: Date;
}

export interface BumpMatchRow {
  id: string;
  match_id: string;
  sender_device_id: string;
  sender_user_id: string;
  receiver_device_id: string;
  receiver_user_id: string;
  rssi_score: number | null;
  time_delta_ms: number | null;
  sender_accel_ms2: number | null;
  receiver_accel_ms2: number | null;
  matched_at: Date;
  consumed_at: Date | null;
  consumed_by_tx_id: string | null;
}

// ─── Device Calibration ────────────────────────────────────────────────────

export interface DeviceCalibrationRow {
  id: string;
  device_id: string;
  user_id: string;
  tap_threshold_ms2: number;
  tap_duration_max_ms: number;
  gyro_drift_baseline_rads: number;
  gyro_rejection_threshold_rads: number;
  rssi_1m_calibration_dbm: number;
  ema_alpha_quiet: number;
  ema_alpha_noisy: number;
  path_loss_exponent: number;
  calibrated_at: Date;
  tx_success_count: number;
  updated_at: Date;
}

// ─── PostgreSQL Function Return Types ──────────────────────────────────────

export interface TransferResult {
  success: boolean;
  error_code: string | null;
  sender_balance: number;
  receiver_balance: number;
}