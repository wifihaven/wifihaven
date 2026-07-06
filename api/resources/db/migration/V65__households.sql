-- V65__households.sql
-- Multi-tenant isolation sub-issue A (#2104, epic #622, design
-- docs/design/multi-tenant-isolation.md §5.1 + §3.4).
--
-- Introduces the tenancy key: a `households` table plus a `household_id`
-- column on the four root tables (users/routers/profiles/devices),
-- household_settings, and the bare-MAC-keyed screen-time tables
-- (time_usage/time_extensions). The single existing install is backfilled as
-- household_id = 1. New per-household uniqueness constraints are ADDED
-- alongside the existing global ones (see "Additive only" below).
--
-- SCHEMA-ONLY PR: per docs/process/migrations.md#migrations-back-compat this
-- migration ships alone (SQL + docs). The source that reads/writes
-- household_id lands in follow-ups B–F (#2105–#2109). The existing feature
-- suite is the back-compat gate.
--
-- ── Additive only — why this migration does NOT drop the global constraints ──
-- The design §5.1 sketch "relaxes" devices.mac / users.username / time_usage's
-- key by DROP + re-ADD. That is NOT back-compatible and fails the gate:
-- image-(N-1) source upserts with ON CONFLICT on the OLD key columns
--   - devices:    ON CONFLICT(mac)                            (Repos.scala:1205,1231)
--   - time_usage: ON CONFLICT(device_mac,host_type,host_value,date) (Repos.scala:1508)
-- Dropping those unique constraints removes the ON CONFLICT target, so the old
-- code's upserts fail ("no unique or exclusion constraint matching the ON
-- CONFLICT specification") and the feature suite goes red (verified: dropping
-- time_usage's key turned 308 tests red). So this migration is PURELY ADDITIVE:
-- it ADDS the per-household unique constraints and KEEPS the global ones. With
-- every row in household 1 the new constraint is functionally 1:1 with the old
-- one, so old code sees identical behavior. The DROP of the now-redundant
-- global constraints (and the alerts.mac FK repoint, and the household_settings
-- single-row CHECK removal) is deferred to follow-up E (#2108), which switches
-- the ON CONFLICT clauses to the new keys and introduces multi-household writes.
-- Because devices_mac_key stays, alerts.mac's FK stays valid and alerts needs
-- no change here (it is not in this issue's enumerated scope).
--
-- ── Deviation: NOT NULL DEFAULT 1, not the sketch's two-step w/o default ─────
-- A NOT NULL column with no default breaks the gate: image-(N-1) source inserts
-- into every one of these tables WITHOUT household_id, so a defaultless NOT NULL
-- column fails those inserts. A CONSTANT default (1) also makes ADD COLUMN
-- metadata-only in PG 11+ (no table rewrite), so it subsumes the rewrite-lock
-- avoidance the sketch's two-step was for. Follow-up E (#2108) drops the default
-- once every write path sets household_id explicitly (expand/contract).
--
-- ── Prod data-volume (docs/process/migrations.md#migrations-prod-data-volume) ─
-- Read-only prod counts 2026-07-06: time_usage 242,888 rows, time_extensions
-- 109, devices 26, users 6, profiles 8, routers 1. time_usage at ~243K rows is
-- small: the FK-validation scan and the added unique-index build are each
-- sub-second, far inside the 15-minute Render port-scan window — no V66 split.
--
-- ── Verified live constraint / key names (\d against a fresh V64 schema) ─────
--   devices.mac unique   = devices_mac_key                        (kept)
--   users.username unique= users_username_key                     (kept)
--   household_settings   = household_settings_id_check            (kept)
--   time_usage unique    = time_usage_device_mac_host_date_key    (kept; real
--     key is (device_mac, host_type, host_value, date) — the sketch's `domain`
--     column was replaced by the host_type/host_value tagged union in V13)

CREATE TABLE households (
  id         BIGSERIAL PRIMARY KEY,
  name       TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Seed the single existing install as household_id = 1.
INSERT INTO households (name) VALUES ('Default household');

-- ── household_id on the four roots + household_settings + screen-time tables ─
-- NOT NULL DEFAULT 1 (constant default → metadata-only add; existing rows and
-- future old-source inserts both land in household 1).
ALTER TABLE users              ADD COLUMN household_id BIGINT NOT NULL DEFAULT 1 REFERENCES households(id);
ALTER TABLE routers            ADD COLUMN household_id BIGINT NOT NULL DEFAULT 1 REFERENCES households(id);
ALTER TABLE profiles           ADD COLUMN household_id BIGINT NOT NULL DEFAULT 1 REFERENCES households(id);
ALTER TABLE devices            ADD COLUMN household_id BIGINT NOT NULL DEFAULT 1 REFERENCES households(id);
ALTER TABLE household_settings ADD COLUMN household_id BIGINT NOT NULL DEFAULT 1 REFERENCES households(id);
ALTER TABLE time_usage         ADD COLUMN household_id BIGINT NOT NULL DEFAULT 1 REFERENCES households(id);
ALTER TABLE time_extensions    ADD COLUMN household_id BIGINT NOT NULL DEFAULT 1 REFERENCES households(id);

-- ── Per-household unique constraints, ADDED alongside the global ones ────────
-- (The global constraints are dropped in follow-up E once code adopts these.)
ALTER TABLE devices ADD CONSTRAINT uq_devices_household_mac
  UNIQUE (household_id, mac);
ALTER TABLE users   ADD CONSTRAINT uq_users_household_username
  UNIQUE (household_id, username);
ALTER TABLE time_usage ADD CONSTRAINT uq_time_usage_household_mac_host_date
  UNIQUE (household_id, device_mac, host_type, host_value, date);
-- time_extensions has no unique key to widen (only non-unique indexes); it just
-- gains the household_id column above.
ALTER TABLE household_settings ADD CONSTRAINT uq_household_settings_household
  UNIQUE (household_id);

-- ── Partial/plain indexes to keep the per-household predicate cheap (§5.1) ───
-- devices/users are already covered by the leading column of their composite
-- unique constraints above; the standalone indexes are added per the design
-- §5.1 enumeration for symmetry with profiles/routers.
CREATE INDEX idx_profiles_household ON profiles(household_id);
CREATE INDEX idx_devices_household  ON devices(household_id);
CREATE INDEX idx_routers_household  ON routers(household_id);
CREATE INDEX idx_users_household    ON users(household_id);
