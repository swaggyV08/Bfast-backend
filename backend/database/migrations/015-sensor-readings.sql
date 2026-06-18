-- ============================================================
-- Migration 015: Sensor Readings
-- ============================================================
-- Stores raw accelerometer and gyroscope data from the 
-- BFast Sensor Test screen.
-- ============================================================

CREATE TABLE sensor_readings (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  device_id       VARCHAR(64) NOT NULL,
  user_id         UUID NOT NULL REFERENCES users(id),
  
  -- Accelerometer (m/s²)
  accel_x         REAL NOT NULL,
  accel_y         REAL NOT NULL,
  accel_z         REAL NOT NULL,
  accel_magnitude REAL NOT NULL,
  
  -- Gyroscope (rad/s)
  gyro_x          REAL NOT NULL,
  gyro_y          REAL NOT NULL,
  gyro_z          REAL NOT NULL,
  gyro_magnitude  REAL NOT NULL,
  
  -- Tap detection result
  tap_detected    BOOLEAN NOT NULL DEFAULT FALSE,
  tap_confidence  REAL,  -- 0.0 to 1.0
  
  -- Metadata
  sample_rate_hz  INTEGER,
  recorded_at     TIMESTAMPTZ NOT NULL,  -- client timestamp
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sensor_readings_device ON sensor_readings(device_id);
CREATE INDEX idx_sensor_readings_user ON sensor_readings(user_id);
CREATE INDEX idx_sensor_readings_time ON sensor_readings(recorded_at DESC);
