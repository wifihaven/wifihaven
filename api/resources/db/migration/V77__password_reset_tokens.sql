-- V77__password_reset_tokens.sql
-- #2308 (epic #622, Beta Launch): forgot-password / reset-via-email-link flow.
-- A household admin who forgets their password has no recovery path today; this
-- table backs a single-use, short-TTL reset token minted on
-- POST /api/auth/forgot-password and consumed by POST /api/auth/reset-password.
--
-- SCHEMA-ONLY PR: per docs/process/migrations.md#migrations-back-compat this
-- migration ships alone (SQL + docs). The source that writes/reads these rows
-- (PasswordResetTokenRepo + the two /api/auth routes + SPA) lands in the
-- follow-up source PR once this merges. The existing feature suite applying V77
-- over the seeded schema (embedded Postgres) is the clean-apply / back-compat
-- gate.
--
-- ── Prod data-volume (docs/process/migrations.md#migrations-prod-data-volume) ─
-- Fresh CREATE TABLE, no scan/rewrite of any existing table. The only reference
-- is the FK to the small bounded `users` table (prod: single-digit rows). No
-- unbounded-growth table (traffic_reports, connection_events, block_events,
-- rollups) is touched. Sub-second, well inside the 15-minute Render port-scan
-- window. No V-split needed.
--
-- Design (see PR body / issue #2308):
--   * Store only the token HASH, never the plaintext token. The plaintext is
--     emailed to the user in the reset link and never persisted; a DB read
--     cannot reconstruct a usable token.
--   * Single-use: used_at is stamped when the token is consumed; a token with
--     used_at IS NOT NULL is rejected on reset.
--   * Short-TTL: expires_at (~30-60 min after mint, enforced by the injected
--     Clock in the source layer); a token past expires_at is rejected.
--   * ON DELETE CASCADE: if a user is removed, their outstanding reset tokens
--     go with them (they are meaningless without the user row).

CREATE TABLE password_reset_tokens (
  id          BIGSERIAL PRIMARY KEY,
  token_hash  TEXT NOT NULL UNIQUE,
  user_id     BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  expires_at  TIMESTAMPTZ NOT NULL,
  used_at     TIMESTAMPTZ,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Lookup on reset is by token_hash (the hash of the plaintext token posted by
-- the client). The UNIQUE constraint above already provides a btree index on
-- token_hash, so no separate CREATE INDEX is needed — this comment documents
-- that the find-valid-by-hash access path is covered.
