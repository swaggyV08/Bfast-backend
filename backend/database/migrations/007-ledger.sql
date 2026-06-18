-- ============================================================
-- Migration 007: Ledger entries table
-- ============================================================
-- Double-entry bookkeeping: every transaction creates EXACTLY TWO
-- ledger rows — one debit (money leaving sender) and one credit
-- (money entering receiver). If you sum all ledger entries for a
-- wallet, you get its current balance. This is the audit trail.
--
-- This table is APPEND-ONLY. Rows are never updated or deleted.
-- ============================================================

CREATE TABLE ledger_entries (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),

  transaction_id  UUID NOT NULL REFERENCES transactions(id) ON DELETE RESTRICT,
  wallet_id       UUID NOT NULL REFERENCES wallets(id) ON DELETE RESTRICT,
  user_id         UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,

  entry_type      ledger_entry_type NOT NULL,  -- 'debit' or 'credit'
  amount_paise    BIGINT NOT NULL,
  CONSTRAINT chk_ledger_amount_positive CHECK (amount_paise > 0),

  -- Snapshot of balance BEFORE and AFTER this entry
  -- Critical for reconciliation and dispute resolution
  balance_before_paise  BIGINT NOT NULL,
  balance_after_paise   BIGINT NOT NULL,

  -- Immutability timestamp — this row was created at this time, forever
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()

  -- No updated_at — this table is append-only
);

CREATE INDEX idx_ledger_transaction ON ledger_entries(transaction_id);
CREATE INDEX idx_ledger_wallet ON ledger_entries(wallet_id, created_at DESC);
CREATE INDEX idx_ledger_user ON ledger_entries(user_id, created_at DESC);

-- ─── The Core Ledger Function ──────────────────────────────────────────────
-- This PostgreSQL function executes the atomic double-entry transfer.
-- Called from Node.js as: SELECT * FROM process_transfer(...)
--
-- Why in the database and not in Node.js?
-- The database can guarantee atomicity at the SQL level.
-- If Node.js crashes mid-transfer, the DB transaction automatically rolls back.

CREATE OR REPLACE FUNCTION process_transfer(
  p_transaction_id    UUID,
  p_sender_wallet_id  UUID,
  p_receiver_wallet_id UUID,
  p_amount_paise      BIGINT
)
RETURNS TABLE(
  success          BOOLEAN,
  error_code       TEXT,
  sender_balance   BIGINT,
  receiver_balance BIGINT
)
LANGUAGE plpgsql
AS $$
DECLARE
  v_sender_balance    BIGINT;
  v_receiver_balance  BIGINT;
  v_sender_user_id    UUID;
  v_receiver_user_id  UUID;
BEGIN
  -- Lock both wallets in consistent order (lower UUID first) to prevent deadlocks
  -- Two concurrent transfers between A↔B and B↔A could deadlock without this.
  IF p_sender_wallet_id < p_receiver_wallet_id THEN
    SELECT balance_paise, user_id INTO v_sender_balance, v_sender_user_id
      FROM wallets WHERE id = p_sender_wallet_id FOR UPDATE;
    SELECT balance_paise, user_id INTO v_receiver_balance, v_receiver_user_id
      FROM wallets WHERE id = p_receiver_wallet_id FOR UPDATE;
  ELSE
    SELECT balance_paise, user_id INTO v_receiver_balance, v_receiver_user_id
      FROM wallets WHERE id = p_receiver_wallet_id FOR UPDATE;
    SELECT balance_paise, user_id INTO v_sender_balance, v_sender_user_id
      FROM wallets WHERE id = p_sender_wallet_id FOR UPDATE;
  END IF;

  -- Check sufficient funds
  IF v_sender_balance < p_amount_paise THEN
    RETURN QUERY SELECT FALSE, 'INSUFFICIENT_FUNDS'::TEXT,
      v_sender_balance, v_receiver_balance;
    RETURN;
  END IF;

  -- Check wallets are not frozen
  IF (SELECT is_frozen FROM wallets WHERE id = p_sender_wallet_id) THEN
    RETURN QUERY SELECT FALSE, 'SENDER_WALLET_FROZEN'::TEXT,
      v_sender_balance, v_receiver_balance;
    RETURN;
  END IF;

  -- Debit sender
  UPDATE wallets
    SET balance_paise = balance_paise - p_amount_paise
    WHERE id = p_sender_wallet_id;

  -- Credit receiver
  UPDATE wallets
    SET balance_paise = balance_paise + p_amount_paise
    WHERE id = p_receiver_wallet_id;

  -- Write debit ledger entry
  INSERT INTO ledger_entries
    (transaction_id, wallet_id, user_id, entry_type, amount_paise, balance_before_paise, balance_after_paise)
  VALUES
    (p_transaction_id, p_sender_wallet_id, v_sender_user_id, 'debit',
     p_amount_paise, v_sender_balance, v_sender_balance - p_amount_paise);

  -- Write credit ledger entry
  INSERT INTO ledger_entries
    (transaction_id, wallet_id, user_id, entry_type, amount_paise, balance_before_paise, balance_after_paise)
  VALUES
    (p_transaction_id, p_receiver_wallet_id, v_receiver_user_id, 'credit',
     p_amount_paise, v_receiver_balance, v_receiver_balance + p_amount_paise);

  -- Return success with new balances
  RETURN QUERY SELECT TRUE, NULL::TEXT,
    v_sender_balance - p_amount_paise,
    v_receiver_balance + p_amount_paise;
END;
$$;