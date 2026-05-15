-- V14__drop_router_clock_skew.sql
-- Issue #415: drop the per-router clock-skew column added in V9 (#312).
-- The router no longer makes time-based decisions (Truth 2 / #350), so the
-- skew telemetry the agent used to report is dead weight. The API server's
-- clock is authoritative for all enforcement; see #334 for the API-side
-- time-handling audit.

ALTER TABLE routers
  DROP COLUMN last_clock_skew_seconds;
