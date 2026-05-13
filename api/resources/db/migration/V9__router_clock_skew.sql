-- V9__router_clock_skew.sql
-- Issue #312: persist the last clock-skew measurement reported by the
-- OpenWRT agent on each /api/router/usage POST. Signed integer seconds —
-- positive means the router clock is ahead of the API. NULL means the
-- agent has never (yet) reported a measurement, which suppresses the
-- admin-UI banner so brand-new enrolments don't flag themselves while
-- the first poll cycle is in flight.

ALTER TABLE routers
  ADD COLUMN last_clock_skew_seconds INTEGER;
