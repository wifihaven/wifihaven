-- V21__heartbeat_filter.sql
-- #714: server-side heartbeat filter for device/profile screen-time totals.
--
-- Adds three knobs on household_settings to control a heartbeat filter applied
-- inside Presence.totalSecondsByMac. The filter drops a traffic_reports row
-- from per-device/per-profile totals (NOT from time_usage / per-site totals)
-- when EITHER heuristic flags it as a heartbeat:
--
--   - bytes (bytes_in + bytes_out) below `heartbeat_bytes_threshold`
--   - activeSeconds / period_seconds below `heartbeat_active_fraction_pct`
--
-- Raw traffic_reports rows are NOT modified — the filter is applied at the
-- Presence aggregation stage only, so the underlying data remains debuggable.
--
-- Defaults: filter is OFF for the first roll. Operator flips it on after
-- validating against prod data via the explain debug surface.

ALTER TABLE household_settings
  ADD COLUMN heartbeat_filter_enabled       BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN heartbeat_bytes_threshold      INT     NOT NULL DEFAULT 2048,
  ADD COLUMN heartbeat_active_fraction_pct  INT     NOT NULL DEFAULT 20;
