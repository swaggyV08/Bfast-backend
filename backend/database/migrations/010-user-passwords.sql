-- ============================================================
-- Migration 010: User passwords (MVP — pre-Cognito)
-- ============================================================
-- In production, AWS Cognito handles password storage.
-- For MVP, we store bcrypt hashes here.
-- When Cognito is integrated, this table is deprecated.
-- ============================================================

CREATE TABLE user_passwords (
  user_id       UUID PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
  password_hash VARCHAR(72) NOT NULL,  -- bcrypt output is always 60 chars
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TRIGGER trigger_user_passwords_updated_at
  BEFORE UPDATE ON user_passwords
  FOR EACH ROW
  EXECUTE FUNCTION update_updated_at_column();