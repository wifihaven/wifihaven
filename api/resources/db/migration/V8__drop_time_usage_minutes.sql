-- V8__drop_time_usage_minutes.sql
-- The legacy OPNsense usage-reporting path was never implemented (#113), and
-- the OpenWRT agent writes only seconds_used + bytes_*. minutes_used is dead.
-- Drop it. UI reads + policy snapshot now derive minutes from seconds_used / 60.

ALTER TABLE time_usage
  DROP COLUMN minutes_used;
