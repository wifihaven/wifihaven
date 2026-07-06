-- V64__auth_hardening.sql
-- #369-audit follow-ups #2080 / #2083. Schema-only PR per
-- docs/process/migrations.md §migrations-back-compat — the consuming code
-- (JWT token-version checks, enrollment-token TTL enforcement) lands in a
-- follow-up PR once this merges.
--
-- 1. users.token_version (#2080): bumped on every password change; stamped
--    into the JWT at login and checked on every verify() so a leaked token
--    stops working the moment the password is rotated (previously a JWT was
--    valid for its full 30-day expiry regardless of a password change).
ALTER TABLE users ADD COLUMN token_version INTEGER NOT NULL DEFAULT 0;

-- 2. routers.enrollment_expires_at (#2083): enrollment tokens were correctly
--    single-use but never expired. Backfill existing outstanding (unused)
--    enrollment tokens with created_at + 1h so the same TTL the audit wanted
--    applies retroactively — any token older than an hour is now already
--    expired rather than perpetually valid.
ALTER TABLE routers ADD COLUMN enrollment_expires_at TIMESTAMPTZ;
UPDATE routers
SET enrollment_expires_at = created_at + INTERVAL '1 hour'
WHERE enrollment_token_hash IS NOT NULL;
