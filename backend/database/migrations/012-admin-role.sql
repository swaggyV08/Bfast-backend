-- ============================================================
-- Migration 012: Admin role flag on users
-- ============================================================
-- Adds is_admin column to users table for admin API access control.
-- ============================================================

ALTER TABLE users ADD COLUMN is_admin BOOLEAN NOT NULL DEFAULT FALSE;
