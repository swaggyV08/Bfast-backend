-- ============================================================
-- Migration 002: Users table
-- ============================================================
-- Stores B-FAST user accounts.
-- We store minimal PII — only what's needed for payments.
-- Cognito handles authentication; this table handles identity
-- within our own system.
-- ============================================================

CREATE TABLE users (
  -- Primary key: UUID instead of integer.
  -- Why UUID? It can be generated client-side without a DB round-trip,
  -- and it doesn't expose your user count to the world.
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),

  -- The Cognito sub (subject) — the unique ID Cognito assigns to this user.
  -- We use this to link our DB record to the Cognito identity.
  cognito_sub     VARCHAR(128) UNIQUE NOT NULL,

  -- Contact info
  email           VARCHAR(255) UNIQUE NOT NULL,
  phone_number    VARCHAR(20) UNIQUE,          -- E.164 format: +919876543210
  display_name    VARCHAR(100) NOT NULL,

  -- Account state
  status          user_status NOT NULL DEFAULT 'active',

  -- KYC (Know Your Customer) — required for RBI compliance in India
  -- We store whether KYC is verified, not the documents themselves
  kyc_verified    BOOLEAN NOT NULL DEFAULT FALSE,

  -- Single transaction limit (in paise — 1 INR = 100 paise)
  -- Storing money as integers avoids floating-point precision errors.
  -- ₹10,000 = 1,000,000 paise
  tx_limit_paise  BIGINT NOT NULL DEFAULT 1000000, -- ₹10,000 default

  -- Timestamps
  -- Using TIMESTAMPTZ (timestamp with timezone) everywhere.
  -- Always store UTC, display in local timezone in the app.
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  last_login_at   TIMESTAMPTZ,

  -- Soft delete: we never hard-delete user records (financial audit requirement)
  deleted_at      TIMESTAMPTZ
);

-- ─── Indexes ───────────────────────────────────────────────────────────────
-- Indexes speed up lookups. Without them, every query scans the entire table.

-- We look up users by email on every login
CREATE INDEX idx_users_email ON users(email);

-- We look up users by phone for P2P payments
CREATE INDEX idx_users_phone ON users(phone_number) WHERE phone_number IS NOT NULL;

-- We look up users by cognito_sub on every authenticated request
CREATE INDEX idx_users_cognito_sub ON users(cognito_sub);

-- Only return non-deleted users in normal queries
CREATE INDEX idx_users_active ON users(status) WHERE deleted_at IS NULL;

-- ─── Auto-update updated_at ────────────────────────────────────────────────
-- This trigger automatically sets updated_at = NOW() whenever a row is updated.
-- You never have to remember to set it in your application code.

CREATE OR REPLACE FUNCTION update_updated_at_column()
RETURNS TRIGGER AS $$
BEGIN
  NEW.updated_at = NOW();
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trigger_users_updated_at
  BEFORE UPDATE ON users
  FOR EACH ROW
  EXECUTE FUNCTION update_updated_at_column();
  