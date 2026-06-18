-- ============================================================
-- Migration 008: Bump requests and matches
-- ============================================================
-- When two devices are physically close and a tap is detected,
-- both post to /api/bumps/match. These tables track the matching state.
-- ============================================================

CREATE TABLE bump_requests (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  device_id       VARCHAR(36) NOT NULL,
  user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  role            VARCHAR(10) NOT NULL CHECK (role IN ('SENDER', 'RECEIVER')),

  -- RSSI values of nearby devices (JSON array)
  -- e.g. [{"deviceId":"abc","rssi":-48}, {"deviceId":"xyz","rssi":-61}]
  nearby_devices  JSONB,

  -- The RSSI of this device as seen by others
  rssi            INTEGER,

  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  -- Requests older than 10 seconds are stale and cleaned up
  expires_at      TIMESTAMPTZ NOT NULL DEFAULT (NOW() + INTERVAL '10 seconds')
);

CREATE INDEX idx_bump_requests_role ON bump_requests(role, created_at DESC);
CREATE INDEX idx_bump_requests_device ON bump_requests(device_id);
CREATE INDEX idx_bump_requests_expires ON bump_requests(expires_at);

-- ─── Bump matches: confirmed pairs ────────────────────────────────────────

CREATE TABLE bump_matches (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  match_id        VARCHAR(32) UNIQUE NOT NULL,

  sender_device_id    VARCHAR(36) NOT NULL,
  sender_user_id      UUID NOT NULL REFERENCES users(id),
  receiver_device_id  VARCHAR(36) NOT NULL,
  receiver_user_id    UUID NOT NULL REFERENCES users(id),

  rssi_score      INTEGER,  -- RSSI at match time (higher = closer)

  matched_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  -- Once a transaction is created from this match, it's consumed
  consumed_at     TIMESTAMPTZ,
  consumed_by_tx_id UUID
);

CREATE INDEX idx_bump_matches_match_id ON bump_matches(match_id);
CREATE INDEX idx_bump_matches_sender ON bump_matches(sender_device_id);
