-- V23__drop_heartbeat_active_fraction.sql
-- #789: drop the activeFractionPct knob from HeartbeatFilter.
--
-- From #779's replay across 145K prod rows: at bytes_threshold=10240, the
-- active-fraction co-signal adds zero discriminatory power. Keeping two knobs
-- that don't compose is API surface area for nothing.
--
-- Existing households lose the column silently — the filter behaves the same
-- as long as their bytes_threshold ≥ 2048 (the prior default). Also raises the
-- default bytes_threshold to 10240 (10 KB) for fresh installs and flips the
-- filter ON by default.

ALTER TABLE household_settings
  DROP COLUMN heartbeat_active_fraction_pct;

ALTER TABLE household_settings
  ALTER COLUMN heartbeat_filter_enabled  SET DEFAULT TRUE,
  ALTER COLUMN heartbeat_bytes_threshold SET DEFAULT 10240;
