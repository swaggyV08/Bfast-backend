-- ============================================================
-- Migration 014: Mobile Auth
-- ============================================================
-- Support phone number + 4-digit passcode auth.
-- ============================================================

-- Make email nullable for mobile-only users
ALTER TABLE users ALTER COLUMN email DROP NOT NULL;
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_email_key;
CREATE UNIQUE INDEX users_email_unique ON users (email) WHERE email IS NOT NULL;

-- Add passcode hash to user_passwords
ALTER TABLE user_passwords ADD COLUMN IF NOT EXISTS passcode_hash VARCHAR(72);

-- Ensure phone_number uniqueness for mobile login
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_phone_number_key;
ALTER TABLE users DROP CONSTRAINT IF EXISTS users_phone_unique;
ALTER TABLE users ADD CONSTRAINT users_phone_unique UNIQUE (phone_number);
