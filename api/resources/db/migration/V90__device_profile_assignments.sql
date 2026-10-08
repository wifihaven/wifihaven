-- V90__device_profile_assignments.sql
-- #2842 (epic #2841, Shared Devices; design docs/design/shared-devices.md §5).
--
-- ── What this adds ───────────────────────────────────────────────────────────
-- 1. `device_profile_assignments`: which profile held each device, and when.
--    Today every per-profile usage read attributes a device's WHOLE history to
--    whatever `devices.profile_id` is right now, so moving a device between
--    profiles (or checking a shared device in) would re-attribute its past
--    usage. Usage reads move onto these intervals in #2844 / #2845.
-- 2. `devices.shared`: a shared device is never permanently assigned; profiles
--    check it in and out (`kind = 'check_in'` rows). Device ATTRIBUTE only — it
--    gates who may hold the device, never what it may reach (#1452).
-- 3. `household_settings.shared_device_idle_minutes`: idle auto-checkout
--    threshold, per household (#2849). The floor of 5 keeps it well above one
--    usage-report period (agent `usage_report_interval`, default 60s) plus
--    ingest and tick lag, so idle cannot fire between two reports of an
--    active device.
--
-- ── Invariant ────────────────────────────────────────────────────────────────
-- At most one OPEN row (`ended_at IS NULL`) per device — `uq_dpa_device_open`.
-- For a shared device that is the one-holder rule. `devices.profile_id` stays
-- the "current" read and equals the open row's `profile_id` (or NULL when there
-- is no open row); #2843 makes a single writer maintain both together.
--
-- `started_at IS NULL` means "open-ended start": the row covers everything
-- before `ended_at`. Only the backfill below writes it. NULL rather than
-- '-infinity' so no reader has to map an infinite timestamp through JDBC.
--
-- ── Backfill ─────────────────────────────────────────────────────────────────
-- One open, open-ended `assigned` row per device that currently has a profile.
-- `household_id` is copied from the device row.
-- That reproduces today's attribution exactly (current profile owns all of the
-- device's history), so this migration changes no behaviour.
--
-- ── Cost ─────────────────────────────────────────────────────────────────────
-- Touches only `devices` (one row per device per household),
-- `household_settings` (one row per household) and the new table. No growth
-- table (`traffic_reports`, `connection_events`, `block_events`, rollups) is
-- scanned, rewritten or re-indexed. The two ALTER TABLE ... ADD COLUMN
-- statements have constant defaults, so they are metadata-only on Postgres
-- 11+. The idle-minutes CHECK validates by scanning `household_settings`, and
-- the ACCESS EXCLUSIVE lock from `ALTER TABLE devices` is held through the
-- backfill until commit; both are trivial at those sizes.
--
-- ── Old image compatibility ─────────────────────────────────────────────────
-- Additive only. Image N-1 never reads the new table or columns; its device
-- INSERTs omit `shared` and get the default. Between this deploy and #2843,
-- N-1 can reassign a device without writing history; #2843's standing drift
-- check repairs any device whose open row disagrees with `devices.profile_id`
-- on the next per-household reevaluate tick.

CREATE TABLE device_profile_assignments (
  id            BIGSERIAL   PRIMARY KEY,
  household_id  BIGINT      NOT NULL REFERENCES households(id),
  device_id     BIGINT      NOT NULL REFERENCES devices(id)  ON DELETE CASCADE,
  -- CASCADE mirrors devices.profile_id's ON DELETE SET NULL: when a profile is
  -- deleted the device loses its current assignment and the history for that
  -- profile goes with it, so no open row can outlive its profile.
  profile_id    BIGINT      NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  started_at    TIMESTAMPTZ NULL,
  ended_at      TIMESTAMPTZ NULL,
  kind          TEXT        NOT NULL,
  started_by    BIGINT      NULL REFERENCES users(id) ON DELETE SET NULL,
  ended_by      BIGINT      NULL REFERENCES users(id) ON DELETE SET NULL,
  end_cause     TEXT        NULL,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT dpa_kind_check CHECK (kind IN ('assigned', 'check_in')),
  CONSTRAINT dpa_end_cause_check CHECK (end_cause IN (
    'reassigned', 'unassigned', 'check_out', 'forced', 'time_limit',
    'schedule', 'paused', 'idle', 'day_reset', 'made_shared', 'unshared')),
  -- A closed row says why it closed; an open row has no cause.
  CONSTRAINT dpa_end_cause_iff_ended CHECK ((ended_at IS NULL) = (end_cause IS NULL)),
  -- `>=`, not `>`: a row closed at the instant it opened is a valid, empty
  -- interval. Readers match half-open [started_at, ended_at), so it covers no
  -- presence. Under an injected test clock an open-then-close at the same
  -- instant is the normal case.
  CONSTRAINT dpa_interval_order CHECK (
    ended_at IS NULL OR started_at IS NULL OR ended_at >= started_at),
  -- Only the backfill writes an open-ended start, and only for 'assigned'.
  CONSTRAINT dpa_open_start_is_assigned CHECK (started_at IS NOT NULL OR kind = 'assigned')
);

-- One open assignment per device (= one holder per shared device).
CREATE UNIQUE INDEX uq_dpa_device_open
  ON device_profile_assignments (device_id)
  WHERE ended_at IS NULL;

-- Attribution reads: "which devices did profile P hold during [from, until)",
-- household-bounded (#2844 builds AttributionScope from this).
CREATE INDEX idx_dpa_household_profile
  ON device_profile_assignments (household_id, profile_id, started_at);

-- Event-time label join: "which profile held device D at ts" (#2845).
CREATE INDEX idx_dpa_device_started
  ON device_profile_assignments (device_id, started_at);

ALTER TABLE devices
  ADD COLUMN shared BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE household_settings
  ADD COLUMN shared_device_idle_minutes INT NOT NULL DEFAULT 15,
  ADD CONSTRAINT household_settings_shared_device_idle_minutes_check
    CHECK (shared_device_idle_minutes BETWEEN 5 AND 1440);

INSERT INTO device_profile_assignments (household_id, device_id, profile_id, started_at, kind)
SELECT d.household_id, d.id, d.profile_id, NULL, 'assigned'
FROM devices d
WHERE d.profile_id IS NOT NULL;
