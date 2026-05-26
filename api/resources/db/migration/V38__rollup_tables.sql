-- V38__rollup_tables.sql
-- #809: pre-aggregated tables for usage queries spanning more than a few hours.
-- The base table (traffic_reports) is one row per (router, mac, host, 5-min
-- bucket). Past ~14 days a single device can have ~4k rows; aggregating over
-- a 90-day window for the Usage page touches millions of rows on real data.
--
-- The strategy from docs/design/usage-retention.md §6 is to maintain two
-- rollup tables — hourly and daily — that the /api/usage endpoints can read
-- instead of the base table once the requested window is wider than a few
-- hours. #813 wires the routing.
--
-- Hostname semantics: the rollup tables store the *post-resolved* hostname,
-- mirroring what /api/usage/traffic returns today via its read-time LATERAL
-- join over connection_events.resolved_host_value. Doing the resolve once at
-- rollup-write time keeps the read path free of joins. ipv4/ipv6-typed rows
-- whose dest_ip never gets a fqdn binding stay under their bare-IP hostname
-- — same behavior as the existing endpoint.
--
-- Idempotency: rollups are strictly UPSERT-keyed. The hourly fiber re-rolls
-- the trailing N hours every tick; the daily fiber re-rolls the previous
-- (and current) day every tick. Re-running either job changes nothing if
-- the source rows haven't moved.
--
-- Lock behavior: plain CREATE INDEX, same constraint as V25 — Flyway 10's
-- `executeInTransaction=false` doesn't play with the ARM-embedded Postgres
-- the tests use. The new tables are empty at migration time so the indexes
-- build instantly; only the one-shot backfill below does meaningful work.

-- ── traffic_hourly ─────────────────────────────────────────────────────────
CREATE TABLE traffic_hourly (
  router_id      UUID         NOT NULL REFERENCES routers(id) ON DELETE CASCADE,
  mac            TEXT         NOT NULL,
  hostname       TEXT         NOT NULL,
  bucket_start   TIMESTAMPTZ  NOT NULL,
  active_seconds INT          NOT NULL DEFAULT 0,
  bytes_in       BIGINT       NOT NULL DEFAULT 0,
  bytes_out      BIGINT       NOT NULL DEFAULT 0,
  sample_count   INT          NOT NULL DEFAULT 0,
  rolled_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  PRIMARY KEY (router_id, mac, hostname, bucket_start)
);
CREATE INDEX idx_traffic_hourly_bucket_start ON traffic_hourly (bucket_start DESC);
CREATE INDEX idx_traffic_hourly_mac_bucket   ON traffic_hourly (mac, bucket_start DESC);

-- ── traffic_daily ─────────────────────────────────────────────────────────
CREATE TABLE traffic_daily (
  router_id      UUID         NOT NULL REFERENCES routers(id) ON DELETE CASCADE,
  mac            TEXT         NOT NULL,
  hostname       TEXT         NOT NULL,
  date           DATE         NOT NULL,
  active_seconds INT          NOT NULL DEFAULT 0,
  bytes_in       BIGINT       NOT NULL DEFAULT 0,
  bytes_out      BIGINT       NOT NULL DEFAULT 0,
  sample_count   INT          NOT NULL DEFAULT 0,
  rolled_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
  PRIMARY KEY (router_id, mac, hostname, date)
);
CREATE INDEX idx_traffic_daily_date     ON traffic_daily (date DESC);
CREATE INDEX idx_traffic_daily_mac_date ON traffic_daily (mac, date DESC);

-- ── rollup_runs (observability) ────────────────────────────────────────────
-- Each fiber tick (and the one-shot backfill below) appends one row.
-- /api/admin/rollup-status reads the last N for a quick "are the rollups
-- alive" check from the admin UI.
CREATE TABLE rollup_runs (
  id            BIGSERIAL PRIMARY KEY,
  job           TEXT        NOT NULL,
  started_at    TIMESTAMPTZ NOT NULL,
  finished_at   TIMESTAMPTZ NOT NULL,
  status        TEXT        NOT NULL,
  error         TEXT,
  rows_upserted INT         NOT NULL DEFAULT 0
);
CREATE INDEX idx_rollup_runs_started_at ON rollup_runs (started_at DESC);

-- ── one-shot backfill ─────────────────────────────────────────────────────
-- Aggregate every historical traffic_reports row into both rollup tables.
-- Single-household prod has weeks of data ⇒ a few seconds. The LATERAL join
-- mirrors TrafficReportRepoLive.listRawInRange so the rolled hostname matches
-- what /api/usage/traffic surfaces.
--
-- Strictly UPSERT-keyed; re-running this migration (or invoking the same
-- shape via the scheduled fibers below) is a no-op once it's settled.

WITH resolved AS (
  SELECT
    tr.router_id,
    tr.mac,
    CASE WHEN tr.host_type IN ('ipv4','ipv6') AND ce.resolved_host_value IS NOT NULL
         THEN ce.resolved_host_value
         ELSE tr.host_value
    END AS hostname,
    tr.period_start,
    tr.date,
    tr.active_seconds,
    tr.bytes_in,
    tr.bytes_out
  FROM traffic_reports tr
  LEFT JOIN LATERAL (
    SELECT resolved_host_value
    FROM connection_events
    WHERE mac                 = tr.mac
      AND dest_ip             = tr.host_value
      AND resolved_host_value IS NOT NULL
      AND ts >= tr.date::TIMESTAMPTZ
      AND ts <  (tr.date + INTERVAL '1 day')::TIMESTAMPTZ
    ORDER BY ts DESC LIMIT 1
  ) ce ON tr.host_type IN ('ipv4','ipv6')
  WHERE tr.active_seconds > 0 OR tr.bytes_in > 0 OR tr.bytes_out > 0
)
INSERT INTO traffic_hourly
  (router_id, mac, hostname, bucket_start, active_seconds, bytes_in, bytes_out, sample_count, rolled_at)
SELECT
  router_id,
  mac,
  hostname,
  date_trunc('hour', period_start) AS bucket_start,
  SUM(active_seconds)::INT,
  SUM(bytes_in)::BIGINT,
  SUM(bytes_out)::BIGINT,
  COUNT(*)::INT,
  NOW()
FROM resolved
GROUP BY router_id, mac, hostname, date_trunc('hour', period_start)
ON CONFLICT (router_id, mac, hostname, bucket_start) DO UPDATE SET
  active_seconds = EXCLUDED.active_seconds,
  bytes_in       = EXCLUDED.bytes_in,
  bytes_out      = EXCLUDED.bytes_out,
  sample_count   = EXCLUDED.sample_count,
  rolled_at      = EXCLUDED.rolled_at;

WITH resolved AS (
  SELECT
    tr.router_id,
    tr.mac,
    CASE WHEN tr.host_type IN ('ipv4','ipv6') AND ce.resolved_host_value IS NOT NULL
         THEN ce.resolved_host_value
         ELSE tr.host_value
    END AS hostname,
    tr.date,
    tr.active_seconds,
    tr.bytes_in,
    tr.bytes_out
  FROM traffic_reports tr
  LEFT JOIN LATERAL (
    SELECT resolved_host_value
    FROM connection_events
    WHERE mac                 = tr.mac
      AND dest_ip             = tr.host_value
      AND resolved_host_value IS NOT NULL
      AND ts >= tr.date::TIMESTAMPTZ
      AND ts <  (tr.date + INTERVAL '1 day')::TIMESTAMPTZ
    ORDER BY ts DESC LIMIT 1
  ) ce ON tr.host_type IN ('ipv4','ipv6')
  WHERE tr.active_seconds > 0 OR tr.bytes_in > 0 OR tr.bytes_out > 0
)
INSERT INTO traffic_daily
  (router_id, mac, hostname, date, active_seconds, bytes_in, bytes_out, sample_count, rolled_at)
SELECT
  router_id,
  mac,
  hostname,
  date,
  SUM(active_seconds)::INT,
  SUM(bytes_in)::BIGINT,
  SUM(bytes_out)::BIGINT,
  COUNT(*)::INT,
  NOW()
FROM resolved
GROUP BY router_id, mac, hostname, date
ON CONFLICT (router_id, mac, hostname, date) DO UPDATE SET
  active_seconds = EXCLUDED.active_seconds,
  bytes_in       = EXCLUDED.bytes_in,
  bytes_out      = EXCLUDED.bytes_out,
  sample_count   = EXCLUDED.sample_count,
  rolled_at      = EXCLUDED.rolled_at;

-- Mark the one-shot backfill in rollup_runs so the admin UI shows the table
-- is alive even before the scheduled fibers tick.
INSERT INTO rollup_runs (job, started_at, finished_at, status, rows_upserted)
SELECT 'backfill_hourly', NOW(), NOW(), 'ok', COUNT(*)::INT FROM traffic_hourly;
INSERT INTO rollup_runs (job, started_at, finished_at, status, rows_upserted)
SELECT 'backfill_daily',  NOW(), NOW(), 'ok', COUNT(*)::INT FROM traffic_daily;
