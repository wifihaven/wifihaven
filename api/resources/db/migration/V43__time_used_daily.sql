-- V43__time_used_daily.sql
-- #1160: per-(profile, household-local date) materialization of the
-- `usedMinutes` field on `ProfileDayState`. Today the UI computes this live
-- on every request by re-aggregating `traffic_reports` through the presence /
-- heartbeat-filter pipeline (`TimeStatusService.dayStateAll`). At single-
-- household real-data sizes the per-request scan is already noticeable on
-- /profiles (#1099) and grows with retention.
--
-- The rollup is a strict cache of `TimeStatusService.dayStateAll(now, date,
-- settings)` keyed by (profile, date). The other ProfileDayState fields
-- (`extensionMinutes`, `dailyLimitMinutes`, `blocked`, `blockReason`,
-- `perSite`) are either cheap or depend on `now` (schedule/paused), so they
-- stay computed live and are composed with the cached `used_minutes` at
-- read time.
--
-- Invalidation: any input that changes the active-minute definition or the
-- household-local day boundary deletes the rollup wholesale, and the
-- scheduled rollup fiber refills on its next tick. The two triggers wired
-- here are `household_settings.update` (covers daily_reset_tz, daily reset
-- time, and the heartbeat filter — see HouseholdSettingsRepoLive.update).
-- The midnight roll and routine refresh are handled by the fiber loop.
--
-- Retention: matches the raw `traffic_reports` retention (#811: 30 days) —
-- once raw data is gone the rollup cannot be recomputed, so retaining
-- longer would only hold stale numbers that disagree with a forced re-roll.
-- A separate longer-horizon archive is a follow-up if the UI grows a
-- multi-month trends view.

CREATE TABLE time_used_daily (
  profile_id    BIGINT       NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  date          DATE         NOT NULL,
  used_minutes  INT          NOT NULL,
  rolled_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  PRIMARY KEY (profile_id, date)
);
CREATE INDEX idx_time_used_daily_date ON time_used_daily (date DESC);
