-- V53__app_used_daily.sql
-- #1516 (sub-issue of #1510, App-level usage aggregation; epic Usage &
-- Screen-Time): a per-(profile, app, date) cache of app-aggregated, gap-bridged
-- *engaged* seconds — the engaged-time counterpart of V43's per-(profile, date)
-- `time_used_daily`, sliced by app.
--
-- ── Why this table exists ──────────────────────────────────────────────────
-- The graph path is inconsistent with the per-app cap today (#1516):
--   * `traffic_hourly` / `traffic_daily` (V38) store `active_seconds` — a
--     per-bucket quantity, NOT engaged session-stitch time — and the V45
--     `*_apps` attribution sits on top of that bucket quantity.
--   * `usage-by-app` (buildUsageByApp) sums per-host `proportionalHostSeconds`
--     into each app *after* stitching, so it never bridges an idle gap across an
--     app's hosts and won't match the gap-bridged cap aggregate.
-- The per-app cap, by contrast, ticks on engaged wall-clock time:
-- `Presence.patternGroupSecondsForProfile` (#1505/#1504, built on the #1464
-- session-stitch primitive) unions an app's whole host-set into one budget and
-- bridges idle gaps across those hosts. This table persists that same
-- engaged-seconds quantity per app per day so the #1516 graph series can derive
-- from it and reconcile with the cap. The #1492 "graphs reconcile with the
-- headline total" invariant, extended to the **app** dimension:
--   profile total (time_used_daily) ⇄ per-app rollup (this table) ⇄ per-app series.
--
-- ── Unit: seconds, mirroring time_used_daily (V43) ─────────────────────────
-- Stored as BIGINT *seconds*, identical to `time_used_daily.used_seconds`, NOT
-- the INT `active_seconds` of the V38 bucket rollups. Seconds (not minutes)
-- keeps the rolled + live-tail decomposition exact across the watermark
-- boundary — truncation to minutes happens once, at read time, matching how the
-- daily total is computed (floor-of-sum, never floor-then-sum). This is the
-- engaged quantity `patternGroupSecondsForProfile` returns, so a row is the
-- pre-aggregated portion of exactly that figure for one app.
--
-- ── App keying: FK to apps(id), the registered-app identity ────────────────
-- The per-app cap surface (#1505) keys each app by the string label
-- `app:<slug>` (TimeStatusService.SiteDayState.label /
-- Presence.patternGroupSecondsForProfile's group key), where `slug` is
-- `apps.slug` — UNIQUE per the single-household scope (V28). Post-V35 every
-- per-app cap entry is a real registered app: V35 migrated the legacy
-- `site_time_limits` into `apps` + `app_policy_assignments`, so there are no
-- orphan single-host "apps" left — the `app:<slug>` label deterministically
-- resolves to exactly one `apps` row via the unique slug. The rollup is
-- therefore *per registered app*, so it keys on `app_id BIGINT REFERENCES
-- apps(id)` — the same app identity V45 (`traffic_daily_apps`) uses, with
-- ON DELETE CASCADE so removing an app drops its rollup rows (mirroring how
-- `app_policy_assignments` and the V45 attribution tables cascade). The
-- adoption writer (below) resolves the cap's `app:<slug>` group key to `app_id`
-- via the unique slug it already has in hand when expanding assignments.
--
-- ── Watermark decomposition, mirroring time_used_daily exactly ─────────────
-- `time_used_daily` carries a `rolled_through` watermark, so this table mirrors
-- it column-for-column so the per-app rollup composes the same rolled + tail
-- way. Read semantics (per app, identical to V43):
--   engaged_seconds (rows for `date` whose presence period_start < rolled_through)
--     + live_aggregate(this app's host-set over presence rows where
--                       period_start >= rolled_through)
-- giving a real-time per-app figure with the bulk of the day pre-aggregated.
-- The decomposition is exact in seconds because presence buckets are 5-min
-- granular and disjoint on either side of the watermark — same argument as V43.
-- `rolled_at` is the wall-clock of the last roll (observability / staleness),
-- matching `time_used_daily.rolled_at` and the `rolled_at` on the V38 siblings;
-- no separate created_at/updated_at, mirroring those tables.
--
-- ── Inert until the adoption PR (isolation gate) ───────────────────────────
-- This is a SCHEMA-ONLY migration (per the migration-isolation rule in
-- CLAUDE.md): it ships with no source and no tests. The Models / repo / rollup
-- writer / series read path that WRITE and READ this table land in the
-- follow-up #1516 API-adoption PR. Until then the table is inert — nothing
-- references `app_used_daily`, so there is NO functional change from this
-- migration alone. That inertness is exactly what makes it backward-compatible
-- with image-(N-1): the existing api.test suite applies this migration against
-- the currently-deployed code and passes unchanged (the isolation gate).
--
-- ── Prod-volume safety (CLAUDE.md §migrations-prod-data-volume) ─────────────
-- This is a rollup table whose row count grows with profiles × apps × days —
-- bounded and tiny relative to the unbounded event tables (`traffic_reports`,
-- `connection_events`, `block_events`), and it starts EMPTY. Creating it and
-- its indexes touches no existing data: no scan, no rewrite, no ATTACH/VALIDATE
-- of a growth table. Safe on the Flyway startup critical path even at full prod
-- volume. There is deliberately NO backfill here — a backfill would scan the
-- event tables to reconstruct historical engaged seconds and is out of scope
-- for a schema-only migration; if one is wanted it is a later,
-- operator-coordinated step, not part of the startup path.

CREATE TABLE app_used_daily (
  profile_id      BIGINT       NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  app_id          BIGINT       NOT NULL REFERENCES apps(id)     ON DELETE CASCADE,
  date            DATE         NOT NULL,
  engaged_seconds BIGINT       NOT NULL,
  rolled_through  TIMESTAMPTZ  NOT NULL,
  rolled_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  PRIMARY KEY (profile_id, app_id, date)
);

-- Per-profile series read pattern (the #1516 graph: an app's bars across a date
-- window for one profile) — mirrors the (entity, date DESC) read index the V38
-- siblings carry (e.g. idx_traffic_daily_mac_date), with profile_id as the
-- entity. The PK already leads with profile_id, but this keeps the date range
-- contiguous regardless of app_id.
CREATE INDEX idx_app_used_daily_profile_date ON app_used_daily (profile_id, date DESC);
-- Date-only sweep for retention pruning / cross-profile reads, mirroring
-- idx_time_used_daily_date (V43) and idx_traffic_daily_date (V38).
CREATE INDEX idx_app_used_daily_date ON app_used_daily (date DESC);
