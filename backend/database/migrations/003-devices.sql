-- ============================================================
-- Migration 003: Devices table
-- ============================================================
-- Each user can have multiple devices (phone + tablet, or upgrading phones).
-- The device_id is the UUID generated on first app install and stored
-- in Android Keystore / iOS Keychain — it persists across app updates
-- but is reset on factory reset.
-- ============================================================

CREATE TABLE devices (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  -- The device UUID generated on first install (stored in device secure storage)
  device_id       VARCHAR(36) UNIQUE NOT NULL,

  platform        device_platform NOT NULL,
  device_model    VARCHAR(100),    -- e.g. "Samsung Galaxy S24"
  os_version      VARCHAR(50),     -- e.g. "Android 14"
  app_version     VARCHAR(20),     -- e.g. "1.2.0"

  -- BLE capabilities — stored so we can decide feature availability server-side
  ble_version     VARCHAR(10),     -- e.g. "5.0"

  -- Push notification token (FCM on Android, APNs on iOS)
  -- Used to send payment confirmations
  push_token      TEXT,
  push_token_updated_at TIMESTAMPTZ,

  -- Security: track when a device was first seen and last active
  -- A device "new in last 30 days" triggers fraud alerts
  first_seen_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  last_active_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

  -- If a device is reported stolen or compromised, we block it here
  is_blocked      BOOLEAN NOT NULL DEFAULT FALSE,
  blocked_at      TIMESTAMPTZ,
  blocked_reason  TEXT,

  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_devices_user_id ON devices(user_id);
CREATE INDEX idx_devices_device_id ON devices(device_id);
CREATE INDEX idx_devices_push_token ON devices(push_token) WHERE push_token IS NOT NULL;

CREATE TRIGGER trigger_devices_updated_at
  BEFORE UPDATE ON devices
  FOR EACH ROW
  EXECUTE FUNCTION update_updated_at_column();
  