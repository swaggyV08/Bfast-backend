-- ============================================================
-- Migration 006: Transactions table
-- ============================================================
-- The central record of every payment in B-FAST.
-- Once a transaction row exists with status='confirmed', it is
-- IMMUTABLE — we never update or delete it. Financial audit trail.
-- ============================================================

CREATE TABLE transactions (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),

  -- Human-readable transaction reference shown to users
  -- Format: tx_YYYYMMDD_randomhex
  tx_ref          VARCHAR(40) UNIQUE NOT NULL,

  -- Parties
  sender_user_id    UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  sender_device_id  VARCHAR(36) NOT NULL,
  receiver_user_id  UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
  receiver_device_id VARCHAR(36) NOT NULL,

  -- Payment details (all monetary values in paise)
  amount_paise    BIGINT NOT NULL,
  currency        VARCHAR(3) NOT NULL DEFAULT 'INR',

  CONSTRAINT chk_amount_positive CHECK (amount_paise > 0),
  CONSTRAINT chk_no_self_payment CHECK (sender_user_id != receiver_user_id),

  -- Session code used (for audit trail — links payment to the BLE handshake)
  session_code_id UUID REFERENCES session_codes(id),

  -- Lifecycle
  status          transaction_status NOT NULL DEFAULT 'pending',

  -- Idempotency key: if the app retries the same payment (network error),
  -- we return the original result instead of charging twice.
  -- Indexed with UNIQUE to enforce at the DB level.
  idempotency_key VARCHAR(64) UNIQUE,

  -- The one-time nonce from the payment intent (replay attack prevention)
  nonce           VARCHAR(64) UNIQUE NOT NULL,

  -- RSSI value at time of payment (logged for analytics and fraud detection)
  rssi_at_payment INTEGER,

  -- Failure details (populated if status = 'failed')
  failure_code    VARCHAR(50),
  failure_message TEXT,

  -- Optional user-provided note ("Coffee at Chai Stop")
  note            TEXT,

  -- Timestamps
  initiated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  confirmed_at    TIMESTAMPTZ,
  failed_at       TIMESTAMPTZ,

  -- Latency tracking (ms from client tap to server confirmation)
  -- Used to measure if we're hitting our <800ms target
  client_initiated_at TIMESTAMPTZ,  -- Timestamp on the phone when user tapped
  latency_ms      INTEGER           -- Calculated: confirmed_at - client_initiated_at

);

-- Indexes for the most common query patterns
CREATE INDEX idx_transactions_sender ON transactions(sender_user_id, initiated_at DESC);
CREATE INDEX idx_transactions_receiver ON transactions(receiver_user_id, initiated_at DESC);
CREATE INDEX idx_transactions_status ON transactions(status);
CREATE INDEX idx_transactions_tx_ref ON transactions(tx_ref);
CREATE INDEX idx_transactions_nonce ON transactions(nonce);

-- Partial index for pending transactions (the hot path — these need fast lookups)
CREATE INDEX idx_transactions_pending
  ON transactions(sender_user_id, initiated_at)
  WHERE status = 'pending';
  