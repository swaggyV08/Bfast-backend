-- ============================================================
-- Migration 011: Device calibration profiles
-- ============================================================
-- Stores per-device sensor calibration data for adaptive tap
-- detection. Each device has unique BLE antenna placement,
-- accelerometer sensitivity, and gyroscope drift — storing
-- calibrated thresholds enables >90% tap detection accuracy
-- across diverse Android hardware.
-- ============================================================

CREATE TABLE device_calibrations (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  device_id       VARCHAR(36) UNIQUE NOT NULL REFERENCES devices(device_id) ON DELETE CASCADE,
  user_id         UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,

  -- Accelerometer calibration (Phase A Step 1)
  tap_threshold_ms2           REAL NOT NULL DEFAULT 2.5,   -- μ - 1.5σ from calibration
  tap_duration_max_ms         INTEGER NOT NULL DEFAULT 200,

  -- Gyroscope calibration (Phase A Step 2)
  gyro_drift_baseline_rads    REAL NOT NULL DEFAULT 0.0,   -- resting angular velocity
  gyro_rejection_threshold_rads REAL NOT NULL DEFAULT 0.5,

  -- RSSI calibration (Phase A Step 3)
  rssi_1m_calibration_dbm     INTEGER NOT NULL DEFAULT -59, -- measured at 1m distance

  -- EMA alpha adaptation (Phase B)
  ema_alpha_quiet             REAL NOT NULL DEFAULT 0.5,   -- clean RF environment
  ema_alpha_noisy             REAL NOT NULL DEFAULT 0.12,  -- noisy RF environment

  -- Path-loss model
  path_loss_exponent          REAL NOT NULL DEFAULT 2.0,   -- environment-learned

  -- Online learning counters
  tx_success_count            INTEGER NOT NULL DEFAULT 0,

  calibrated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_device_calibrations_device ON device_calibrations(device_id);
CREATE INDEX idx_device_calibrations_user ON device_calibrations(user_id);

CREATE TRIGGER trigger_device_calibrations_updated_at
  BEFORE UPDATE ON device_calibrations
  FOR EACH ROW
  EXECUTE FUNCTION update_updated_at_column();
