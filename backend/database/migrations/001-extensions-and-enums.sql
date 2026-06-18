-- ============================================================
-- Migration 001: PostgreSQL extensions and custom enum types
-- ============================================================
-- Extensions add capabilities to PostgreSQL.
-- Enums ensure only valid values can be stored in certain columns
-- (e.g. a transaction status can only be 'pending', 'confirmed', or 'failed').
-- ============================================================

-- pgcrypto: provides gen_random_uuid() for generating UUIDs in the DB
-- and cryptographic functions we use for server-side hashing
CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- uuid-ossp: alternative UUID generation (belt-and-suspenders)
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ─── Enum Types ────────────────────────────────────────────────────────────

-- User account status
CREATE TYPE user_status AS ENUM (
  'active',      -- Normal, can transact
  'suspended',   -- Temporarily blocked (fraud flag)
  'deactivated'  -- Permanently closed
);

-- Transaction lifecycle states
CREATE TYPE transaction_status AS ENUM (
  'pending',    -- Payment intent created, server validation in progress
  'confirmed',  -- Ledger write successful, both wallets updated
  'failed',     -- Validation or ledger write failed
  'reversed'    -- Refunded/reversed after confirmation (dispute resolution)
);

-- Wallet transaction types (double-entry bookkeeping)
CREATE TYPE ledger_entry_type AS ENUM (
  'debit',   -- Money leaving a wallet
  'credit'   -- Money entering a wallet
);

-- Device platform
CREATE TYPE device_platform AS ENUM (
  'android',
  'ios',
  'web'     -- Future: browser-based admin
);

-- Session code states
CREATE TYPE session_code_status AS ENUM (
  'active',    -- Valid and available for use
  'consumed',  -- Used in a transaction — cannot be reused
  'expired'    -- TTL elapsed — cannot be used
);