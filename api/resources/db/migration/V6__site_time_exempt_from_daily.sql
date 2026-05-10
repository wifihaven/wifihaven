-- Issue #15: per-app time limits can be exempt from (default) or included in the
-- overall daily limit.
--
-- exempt_from_daily = true  (default): the app's time does NOT count against the
--                                      profile's overall daily cap. Burning all of
--                                      YouTube does not eat into general screen time.
-- exempt_from_daily = false (included): the app's time DOES count against the daily
--                                       cap. The per-app limit acts as a sub-cap.

ALTER TABLE site_time_limits
  ADD COLUMN exempt_from_daily BOOLEAN NOT NULL DEFAULT TRUE;
