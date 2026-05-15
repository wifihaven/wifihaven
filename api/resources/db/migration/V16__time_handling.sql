-- V14__time_handling.sql
-- #334: timezone-aware schedules + per-household daily-reset config.
--
-- Schedules now carry a single IANA timezone; start/end are wall-clock
-- LocalTimes interpreted in that zone. PolicyService converts Instant.now()
-- to that zone before comparing, so DST is handled automatically.
--
-- household_settings is a new single-row table holding the household-wide
-- daily-reset time + tz. Previously the daily reset was hardcoded to
-- UTC midnight in PolicyService; now it's configurable.
--
-- Backfill: existing block_from/block_until strings are reinterpreted as
-- LocalTime + tz='UTC'. That preserves the old behavior bit-for-bit
-- (since the old code did all math in server-UTC). Operators can change
-- the tz per-schedule afterward.

-- ── household_settings ────────────────────────────────────────────────────
-- Single-row table: id is constrained to 1. The row is created at boot
-- time by HouseholdSettingsRepo.ensureDefault, which uses the API JVM's
-- ZoneId.systemDefault for the install-time tz default.
CREATE TABLE household_settings (
  id                INT PRIMARY KEY CHECK (id = 1),
  daily_reset_time  TIME    NOT NULL DEFAULT '00:00',
  daily_reset_tz    TEXT    NOT NULL DEFAULT 'UTC',
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ── schedules tz-aware columns ────────────────────────────────────────────
ALTER TABLE schedules
  ADD COLUMN start_local TIME,
  ADD COLUMN end_local   TIME,
  ADD COLUMN tz          TEXT;

-- Backfill from existing TEXT 'HH:MM' strings.
UPDATE schedules
   SET start_local = block_from::TIME,
       end_local   = block_until::TIME,
       tz          = 'UTC';

ALTER TABLE schedules
  ALTER COLUMN start_local SET NOT NULL,
  ALTER COLUMN end_local   SET NOT NULL,
  ALTER COLUMN tz          SET NOT NULL;

ALTER TABLE schedules
  DROP COLUMN block_from,
  DROP COLUMN block_until;
