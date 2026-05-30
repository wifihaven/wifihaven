-- V44__time_used_app_daily.sql
-- #1167: per-(profile, today, app) sub-bucket cache that extends the V43
-- profile-total rollup. Sub-bucket rows share the parent profile's
-- `rolled_through` watermark, written by the same rollup tick — so the read
-- path can compose `rolled + tail` with a single-key lookup per (profile, app)
-- without joining back to `time_used_daily`. The rollup writer guarantees the
-- two `rolled_through` values match for any (profile, date) it touches in a
-- given tick.
--
-- Scope mirrors V43: today only, no historical rows. Past-day reads of
-- per-app usage fall through to the live path (same as the profile total).
--
-- Read semantics: a row covers presence buckets on `date` with
-- `period_start < rolled_through`. The read path is
--   rolled_seconds + live_per_app_aggregate(presence rows where
--                                            period_start >= rolled_through)
-- using `TimeStatusService.usedSecondsByApp` for both sides of the equation
-- (the same helper the rollup writer uses) so the cached and live results
-- are structurally identical (#1167 source-of-truth invariant).
--
-- Invalidation:
--   - Wholesale `DELETE FROM time_used_app_daily` from
--     `HouseholdSettingsRepoLive.update` (same transaction as the V43 delete)
--     so heartbeat / tz / reset-time changes refill both tables from first
--     principles on the next tick.
--   - `DELETE FROM time_used_app_daily` from every mutating method on
--     `AppRepoLive` (host add/remove, assignment add/remove/change, app
--     delete) — per-app rows are keyed on app membership, so any change to
--     the (app, hosts) inventory or to a profile's per-app assignment
--     invalidates the cache. Cheap because the table is bounded by today ×
--     profiles × apps.

CREATE TABLE time_used_app_daily (
  profile_id      BIGINT       NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  date            DATE         NOT NULL,
  app_id          BIGINT       NOT NULL REFERENCES apps(id) ON DELETE CASCADE,
  used_seconds    BIGINT       NOT NULL,
  rolled_through  TIMESTAMPTZ  NOT NULL,
  rolled_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  PRIMARY KEY (profile_id, date, app_id)
);
CREATE INDEX idx_time_used_app_daily_date ON time_used_app_daily (date DESC);
