-- ============================================================
-- Migration 004: Wallets table
-- ============================================================
-- Each user has one primary wallet.
-- Balance stored in PAISE (smallest INR unit) as BIGINT.
-- NEVER use FLOAT or DECIMAL for money — integer arithmetic is exact.
-- ₹1 = 100 paise. ₹10,000 = 1,000,000 paise.
-- ============================================================

CREATE TABLE wallets (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,

  -- wallet_type allows future expansion (BCOIN rewards wallet, escrow wallet, etc.)
  wallet_type     VARCHAR(20) NOT NULL DEFAULT 'MAIN',

  -- Balance in paise. BIGINT handles up to ₹92 trillion — more than enough.
  balance_paise   BIGINT NOT NULL DEFAULT 0,

  -- Prevents balance from going negative at the DB level — belt AND suspenders.
  -- The application checks first, but this is the final safety net.
  CONSTRAINT chk_balance_non_negative CHECK (balance_paise >= 0),

  -- A user can only have one wallet of each type
  CONSTRAINT uq_user_wallet_type UNIQUE (user_id, wallet_type),

  currency        VARCHAR(3) NOT NULL DEFAULT 'INR',  -- ISO 4217

  -- Soft lock: prevents any transactions while true
  -- Used during fraud investigations or KYC re-verification
  is_frozen       BOOLEAN NOT NULL DEFAULT FALSE,
  frozen_at       TIMESTAMPTZ,
  frozen_reason   TEXT,

  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_wallets_user_id ON wallets(user_id);

CREATE TRIGGER trigger_wallets_updated_at
  BEFORE UPDATE ON wallets
  FOR EACH ROW
  EXECUTE FUNCTION update_updated_at_column();