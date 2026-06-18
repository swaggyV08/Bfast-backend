-- ============================================================
-- Migration 009: Refresh token management
-- ============================================================
-- Refresh tokens are long-lived (7 days). We store them in the DB
-- so we can:
--   1. Revoke them on logout (security)
--   2. Rotate them (each use issues a new one, invalidating the old)
--   3. Detect theft (if a revoked token is used, someone stole it)
-- ============================================================

CREATE TABLE refresh_tokens (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  device_id       VARCHAR(36) NOT NULL,

  -- The token value (stored as a hash — never raw)
  -- Same principle as passwords: if DB is breached, tokens are useless
  token_hash      VARCHAR(64) UNIQUE NOT NULL,

  -- Token rotation: when this token is used, it's replaced by a new one
  -- The old token is marked replaced_by
  replaced_by     UUID REFERENCES refresh_tokens(id),
  is_revoked      BOOLEAN NOT NULL DEFAULT FALSE,
  revoked_at      TIMESTAMPTZ,
  revoked_reason  VARCHAR(50), -- 'logout', 'rotation', 'theft_detected', 'admin'

  -- Metadata for security analysis
  issued_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  expires_at      TIMESTAMPTZ NOT NULL,
  last_used_at    TIMESTAMPTZ,
  user_agent      TEXT,
  ip_address      INET
);

CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id);
CREATE INDEX idx_refresh_tokens_hash ON refresh_tokens(token_hash);
CREATE INDEX idx_refresh_tokens_device ON refresh_tokens(device_id);

-- Clean up expired tokens automatically (run this as a cron job in production)
-- For now it's here as a reference query
-- DELETE FROM refresh_tokens WHERE expires_at < NOW() AND is_revoked = TRUE;
