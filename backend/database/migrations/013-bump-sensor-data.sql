-- ============================================================
-- Migration 013: Add sensor data to bump_requests & matches
-- ============================================================
-- Stores the processed accelerometer/gyroscope snapshot that
-- the Android app sends when it detects a physical tap gesture.
-- The backend validates these values against the device's
-- calibration profile before allowing a bump match.
-- ============================================================

-- ─── Sensor columns on bump_requests ──────────────────────────────────────

ALTER TABLE bump_requests
  ADD COLUMN accel_peak_ms2       REAL,           -- peak acceleration magnitude at tap (m/s²)
  ADD COLUMN accel_duration_ms    INTEGER,        -- duration of the impact spike (ms)
  ADD COLUMN gyro_magnitude_rads  REAL,           -- angular velocity magnitude at tap (rad/s)
  ADD COLUMN tap_timestamp        TIMESTAMPTZ,    -- client-side tap detection timestamp
  ADD COLUMN sensor_validated     BOOLEAN NOT NULL DEFAULT FALSE;

-- Index on tap_timestamp for efficient timestamp-correlation queries
CREATE INDEX idx_bump_requests_tap_ts ON bump_requests(tap_timestamp)
  WHERE tap_timestamp IS NOT NULL;

-- ─── Audit columns on bump_matches ────────────────────────────────────────

ALTER TABLE bump_matches
  ADD COLUMN time_delta_ms        INTEGER,        -- ms between sender/receiver taps
  ADD COLUMN sender_accel_ms2     REAL,           -- sender's peak acceleration at match
  ADD COLUMN receiver_accel_ms2   REAL;           -- receiver's peak acceleration at match
