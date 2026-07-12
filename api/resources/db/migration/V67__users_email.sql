-- V67__users_email.sql
-- Multi-tenant Phase-5 login revision (operator decision 2026-07-10; #2159,
-- epic #622). Authored against the authoritative Phase-5 design doc
-- docs/design/multi-tenant-launch.md §4 (single-identifier login, PR #2160
-- merged) + §3.4 (invite-accept binds the admin's email from
-- beta_requests.email).
--
-- SCHEMA-ONLY PR: per docs/process/migrations.md#migrations-back-compat this
-- migration ships alone (SQL + docs). The source that reads/writes users.email
-- and relies on the username charset guard lands in follow-ups #2140 (login
-- rework: single-identifier resolution) and #2132 (invite accept binds the
-- first admin's email). The existing feature suite is the back-compat gate.
--
-- ── Prod data-volume (docs/process/migrations.md#migrations-prod-data-volume) ─
-- Touches only the small bounded `users` table (prod: 6 rows, 2026-07-06 count
-- from V65 header). No unbounded-growth table (traffic_reports,
-- connection_events, block_events, rollups) is scanned or rewritten. ADD COLUMN
-- with no default is metadata-only; the two ADD CONSTRAINTs build/validate over
-- a handful of rows — sub-second, well inside the 15-minute Render port-scan
-- window. No V-split needed.
--
-- Contents:
--   1. users.email TEXT   — nullable global login identifier (doc §4 form 1)
--   2. uq_users_email      — GLOBAL unique on email (NOT per-household: email is
--                            the global identifier, that's the whole point)
--   3. ck_users_username_no_at_slash — username may not contain '@' or '/', so
--                            the three login-identifier forms stay syntactically
--                            disjoint (doc §4)

-- ── 1. users.email ───────────────────────────────────────────────────────────
-- Nullable: existing users (incl. the V1 self-hosted seed `admin`,
-- V1__init.sql:130-131) stay email IS NULL and keep username login via the
-- default-household fallback (doc §4 form 3). No email backfill. Admins created
-- through the invite-accept flow (#2132, doc §3.4) get their email bound from
-- the approved beta_requests.email; any member who later adds one upgrades to
-- email login (doc §4 form 1).
ALTER TABLE users ADD COLUMN email TEXT;

-- ── 2. uq_users_email — GLOBAL uniqueness ────────────────────────────────────
-- Email is the GLOBAL login identifier: a single posted string containing '@'
-- resolves to exactly one user row (and thus one household) with no household
-- knowledge in hand (doc §4 form 1). So the constraint is deliberately global,
-- NOT scoped by household_id like username's V65 uq_users_household_username.
--
-- Postgres UNIQUE treats NULLs as distinct (default NULLS DISTINCT — the
-- documented PostgreSQL behavior; NULLS NOT DISTINCT is opt-in only), so any
-- number of no-email users coexist under this constraint — only non-NULL emails
-- must be unique. Existing users (all email IS NULL after this migration, no
-- backfill) therefore satisfy it trivially; the feature suite applying V67 over
-- the seeded schema (embedded Postgres) is the clean-apply / back-compat gate.
ALTER TABLE users ADD CONSTRAINT uq_users_email UNIQUE (email);

-- ── 3. ck_users_username_no_at_slash — keep the login forms disjoint ─────────
-- The single-identifier login (doc §4) distinguishes three forms purely by
-- charset, with no parsing precedence: email contains '@', the slug/username
-- composite contains '/', a bare username contains neither. Slugs are already
-- constrained to [a-z0-9-] (V66 households_slug_format_check); this guard is the
-- username half — a username may contain neither '@' nor '/'. `!~` is Postgres
-- case-sensitive regex non-match, so the CHECK passes iff username has no '@'
-- or '/'. username is NOT NULL (V1), so no NULL-tolerance concern.
--
-- Verified before adding: no existing username contains '@' or '/'. The only
-- seeded username is `admin` (V1__init.sql:130-131); prod checked read-only
-- (render psql, 6 users: admin, quintus, prima, octavius, raytrace, sameer —
-- `username ~ '[@/]'` false for all). See PR body.
ALTER TABLE users ADD CONSTRAINT ck_users_username_no_at_slash
  CHECK (username !~ '[@/]');
