-- V47__connection_events_rollups.sql
-- #1265 (sub-issue of #844; deferred in #847): pre-aggregated rollup tier for
-- the Connection Events page, mirroring the traffic rollup track (V38 / #809).
--
-- Today ConnectionEventRepo.querySeries runs `date_bin(...)` + GROUP BY against
-- the raw, unbounded-growth `connection_events` table on every request. Wide
-- windows (the 90-day Connection Events view) scan millions of rows. The fix is
-- the same two-tier rollup that traffic uses: an hourly and a daily table that
-- the read path routes to once the requested window is wider than a few hours
-- (the #1262 shared range→bucket-width mapping; routing lands in the follow-up
-- code PR, NOT here — this is the schema-only migration per AGENTS.md
-- #migrations-back-compat).
--
-- ── Schema decision: SPLIT count columns, `allowed` NOT in the PK ───────────
-- The issue offered two shapes: (a) `allowed` in the PK so a row is per
-- (…, bucket_start, allowed), or (b) split `count_succeeded` / `count_blocked`
-- columns so a row is per (…, bucket_start). We pick (b) — split columns.
-- Rationale: querySeries already projects exactly these two values via
--   COUNT(*) FILTER (WHERE ce.allowed)     AS count_succeeded
--   COUNT(*) FILTER (WHERE NOT ce.allowed) AS count_blocked
-- so the rollup read path can SUM(count_succeeded) / SUM(count_blocked) per
-- bucket with no GROUP-BY collapse over an `allowed` dimension, and the
-- allowed/blocked split survives a re-roll without doubling the row count. It
-- also keeps the PK identical in arity to V38's traffic rollups, so the read
-- routing, retention, and rollup_runs wiring stay structurally the same.
--
-- ── Stored dimensions ───────────────────────────────────────────────────────
-- Per #1265 §3 the rollup stores only (router_id, mac, hostname, bucket). The
-- Connection Events page's device / profile / app attribution all derive from a
-- READ-TIME join off `mac` (devices→profiles) or a read-time apex match off the
-- hostname (apps), exactly as querySeries does today and exactly as the traffic
-- rollups do — so those columns are NOT stored here. `hostname` is the
-- post-resolved host: COALESCE(resolved_host_value, host_value), resolved once
-- at roll-write time so the read path stays join-free for the host dimension.
-- Location filtering is `routers.name`, reached by joining `routers` on the
-- stored `router_id`.
--
-- ── Idempotency ─────────────────────────────────────────────────────────────
-- Strictly UPSERT-keyed, same contract as V38. The follow-up code PR's hourly
-- fiber re-rolls the trailing N hours every tick; the daily fiber re-rolls the
-- previous + current day. Re-rolling an already-rolled bucket replaces the row
-- with an identical value when the source hasn't moved.
--
-- ── Lock behavior ───────────────────────────────────────────────────────────
-- Plain CREATE INDEX; the tables are empty at migration time so the indexes
-- build instantly. No long lock, no critical-path scan here (see the backfill
-- note at the bottom — the historical backfill is deliberately NOT run on the
-- Flyway startup path).

-- ── connection_events_hourly ────────────────────────────────────────────────
-- `mac` is NOT NULL here even though connection_events.mac is nullable: it is a
-- PK column, and un-attributed (NULL-mac) events are folded into a '' bucket by
-- the reroll's COALESCE(ce.mac, '') so no counts are dropped and the empty-
-- groupBy window totals stay faithful to raw.
CREATE TABLE connection_events_hourly (
  router_id       UUID         NOT NULL REFERENCES routers(id) ON DELETE CASCADE,
  mac             TEXT         NOT NULL,
  hostname        TEXT         NOT NULL,
  bucket_start    TIMESTAMPTZ  NOT NULL,
  count_succeeded INT          NOT NULL DEFAULT 0,
  count_blocked   INT          NOT NULL DEFAULT 0,
  sample_count    INT          NOT NULL DEFAULT 0,
  rolled_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  PRIMARY KEY (router_id, mac, hostname, bucket_start)
);
-- Read-path indexes — analogues of V38's on traffic_hourly. The Connection
-- Events series filters/group-bys probe: bucket_start (window), mac (device &
-- profile joins both hang off ce.mac), allowed (now SPLIT into columns, so no
-- index needed), domain (ILIKE '%x%' — leading-wildcard, btree can't help, same
-- as raw today), location (routers.name via router_id, which leads the PK).
-- So the two V38-analogue indexes plus the PK cover the real probe set.
CREATE INDEX idx_ce_hourly_bucket_start ON connection_events_hourly (bucket_start DESC);
CREATE INDEX idx_ce_hourly_mac_bucket   ON connection_events_hourly (mac, bucket_start DESC);

-- ── connection_events_daily ─────────────────────────────────────────────────
CREATE TABLE connection_events_daily (
  router_id       UUID         NOT NULL REFERENCES routers(id) ON DELETE CASCADE,
  mac             TEXT         NOT NULL,
  hostname        TEXT         NOT NULL,
  date            DATE         NOT NULL,
  count_succeeded INT          NOT NULL DEFAULT 0,
  count_blocked   INT          NOT NULL DEFAULT 0,
  sample_count    INT          NOT NULL DEFAULT 0,
  rolled_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  PRIMARY KEY (router_id, mac, hostname, date)
);
CREATE INDEX idx_ce_daily_date     ON connection_events_daily (date DESC);
CREATE INDEX idx_ce_daily_mac_date ON connection_events_daily (mac, date DESC);

-- ── rollup_runs observability ───────────────────────────────────────────────
-- Reuse the existing V38 rollup_runs table (no schema change). The follow-up
-- code PR's fibers append rows under the job names
--   connection_events_hourly / connection_events_daily
-- and the one-shot operator backfill below uses
--   backfill_connection_events_hourly / backfill_connection_events_daily.
-- /api/admin/rollup-status already reads the last N runs regardless of job name.

-- ── Historical backfill — OPERATOR-APPLIED, OUT-OF-BAND. DO NOT UNCOMMENT. ───
-- AGENTS.md #migrations-prod-data-volume: `connection_events` is an
-- unbounded-growth event table. A full-history aggregation scans every row.
-- Against the empty embedded Postgres in tests this is milliseconds; against
-- prod it is MINUTES, and Flyway runs on the API startup critical path, so an
-- inline backfill here risks blowing Render's 15-minute port-scan deploy
-- timeout — the exact failure mode of #1197 (the V41/V42 partition migrations
-- whose "this is seconds" comment was measured on dev/staging and was false at
-- prod scale). So unlike V38, the backfill is NOT inlined and NOT run on
-- startup.
--
-- Going-forward population is handled entirely by the reroll fibers (follow-up
-- PR). For historical rows, the operator runs the block below out-of-band
-- (deploy quiesced / off the startup path), AFTER sizing it against the live
-- `connection_events` row count. The source scan is a bounded range scan per
-- bucket-grain via the existing idx_conn_events_ts (ts DESC) / idx_conn_events_
-- mac_ts (mac, ts DESC) — confirm with EXPLAIN (ANALYZE) against prod-like
-- volume before running (#1261).
--
-- INSERT INTO connection_events_hourly
--   (router_id, mac, hostname, bucket_start,
--    count_succeeded, count_blocked, sample_count, rolled_at)
-- SELECT
--   ce.router_id,
--   COALESCE(ce.mac, '')                                   AS mac,
--   COALESCE(ce.resolved_host_value, ce.host_value)        AS hostname,
--   date_bin(INTERVAL '1 hour', ce.ts, TIMESTAMP '2000-01-01 00:00:00') AS bucket_start,
--   COUNT(*) FILTER (WHERE ce.allowed)::INT                 AS count_succeeded,
--   COUNT(*) FILTER (WHERE NOT ce.allowed)::INT             AS count_blocked,
--   COUNT(*)::INT                                           AS sample_count,
--   NOW()
-- FROM connection_events ce
-- -- size before running: add `WHERE ce.ts >= '<start>'` to bound the scan
-- GROUP BY ce.router_id, COALESCE(ce.mac, ''),
--          COALESCE(ce.resolved_host_value, ce.host_value),
--          date_bin(INTERVAL '1 hour', ce.ts, TIMESTAMP '2000-01-01 00:00:00')
-- ON CONFLICT (router_id, mac, hostname, bucket_start) DO UPDATE SET
--   count_succeeded = EXCLUDED.count_succeeded,
--   count_blocked   = EXCLUDED.count_blocked,
--   sample_count    = EXCLUDED.sample_count,
--   rolled_at       = EXCLUDED.rolled_at;
--
-- INSERT INTO connection_events_daily
--   (router_id, mac, hostname, date,
--    count_succeeded, count_blocked, sample_count, rolled_at)
-- SELECT
--   ce.router_id,
--   COALESCE(ce.mac, '')                                   AS mac,
--   COALESCE(ce.resolved_host_value, ce.host_value)        AS hostname,
--   (ce.ts AT TIME ZONE 'UTC')::DATE                        AS date,
--   COUNT(*) FILTER (WHERE ce.allowed)::INT                 AS count_succeeded,
--   COUNT(*) FILTER (WHERE NOT ce.allowed)::INT             AS count_blocked,
--   COUNT(*)::INT                                           AS sample_count,
--   NOW()
-- FROM connection_events ce
-- GROUP BY ce.router_id, COALESCE(ce.mac, ''),
--          COALESCE(ce.resolved_host_value, ce.host_value),
--          (ce.ts AT TIME ZONE 'UTC')::DATE
-- ON CONFLICT (router_id, mac, hostname, date) DO UPDATE SET
--   count_succeeded = EXCLUDED.count_succeeded,
--   count_blocked   = EXCLUDED.count_blocked,
--   sample_count    = EXCLUDED.sample_count,
--   rolled_at       = EXCLUDED.rolled_at;
