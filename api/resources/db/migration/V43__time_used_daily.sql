-- V43__time_used_daily.sql
-- #1160: per-(profile, today) cache of `ProfileDayState.usedMinutes`, stored as
-- seconds so the rollup + live-tail decomposition is exact (truncation to
-- minutes happens once at read time).
--
-- Scope: today only. Past dates are not cached here — the existing live path
-- (and the byte rollups in V38) cover historical reads. The hot path this
-- table exists for is `/api/time/status/summary` rendering the per-profile
-- screen-time figures, which today re-aggregates `traffic_reports` per request
-- (#1099). Caching past days would also work but is unnecessary: the row count
-- is small (one per profile per past day) but each row is immutable once the
-- raw retention horizon passes, so it's not what makes the UI slow.
--
-- Read semantics: a row covers presence rows for `date` whose
-- `period_start < rolled_through`. The read path is
--   rolled_seconds + live_aggregate(presence rows where period_start >= rolled_through)
-- which gives a real-time result with the bulk of the day pre-aggregated. The
-- decomposition is exact in seconds for both `Sum` and `Dedup` overlap modes
-- because buckets are 5-min granular and disjoint on either side of the
-- watermark.
--
-- Invalidation: wholesale `DELETE FROM time_used_daily` from
-- `HouseholdSettingsRepoLive.update`. Any change to the heartbeat filter or
-- the daily-reset boundary changes the active-minute definition, and the next
-- rollup tick refills the row with the new rules in force.

CREATE TABLE time_used_daily (
  profile_id      BIGINT       NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  date            DATE         NOT NULL,
  used_seconds    BIGINT       NOT NULL,
  rolled_through  TIMESTAMPTZ  NOT NULL,
  rolled_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  PRIMARY KEY (profile_id, date)
);
CREATE INDEX idx_time_used_daily_date ON time_used_daily (date DESC);
