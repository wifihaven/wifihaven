-- V41__partition_traffic_reports.sql
-- #805 (design: docs/design/db-partitioning.md, #793): convert traffic_reports
-- to a weekly RANGE-partitioned table on period_start. traffic_reports is the
-- largest growth surface; every hot read predicate is a period_start/date range
-- or a router_id lookup, and the unique key already includes period_start, so
-- native partitioning preserves uniqueness without weakening it.
--
-- Technique: detach-and-reattach. Rename the existing table to become a single
-- historical "omnibus" partition, create a new partitioned parent with an
-- identical column set, relocate any on/after-cutover rows into freshly-created
-- weekly partitions, then attach the historical remainder as the unbounded-below
-- partition. ATTACH validates (full scan, no rewrite); at current volume this is
-- seconds. Ongoing weekly-partition creation lands in #808; retention drop in
-- #812; redundant-index cleanup in #810.
--
-- Two points the design doc's worked example glosses over, resolved here:
--   1. `id BIGSERIAL PRIMARY KEY` is illegal as-is on a partitioned table — a
--      primary key must include the partition key. Nothing references this id
--      (no FKs, no lookups; it is a surrogate returned in read DTOs only), so we
--      drop the PK and keep `id` as a plain sequence-backed column. The existing
--      UNIQUE (router_id, period_start, mac, host_type, host_value) — which DOES
--      include the partition key — becomes the row-identity constraint.
--   2. The id sequence is detached (OWNED BY NONE) so it survives the eventual
--      DROP of expired partitions (#812); otherwise dropping the omnibus would
--      drop the sequence the parent still defaults from.
--
-- Partition seeding is anchored to CURRENT_DATE at migration time (not a
-- hardcoded date) so the test harness — which re-runs every migration against a
-- fresh embedded Postgres on each reset — always seeds partitions covering "now"
-- regardless of when the suite runs, and prod seeds relative to its deploy date.

-- Detach the id sequence so a future partition drop can't take it with it.
ALTER SEQUENCE traffic_reports_id_seq OWNED BY NONE;

-- Rename the existing table; it becomes the historical omnibus partition.
ALTER TABLE traffic_reports RENAME TO traffic_reports_pre_partition;

-- Strip indexes + constraints from the omnibus. Index and unique/PK-backing
-- index names share the schema namespace, so they must be freed before the new
-- parent re-establishes them under the canonical names. The parent re-creates
-- the UNIQUE and FK (the FK propagates to this partition on attach) and the
-- secondary indexes after attach. The host_type CHECK is deliberately KEPT:
-- ATTACH PARTITION requires the child to already carry an equivalent of each of
-- the parent's CHECK constraints, and the parent declares the same-named check,
-- so Postgres merges them on attach.
ALTER TABLE traffic_reports_pre_partition DROP CONSTRAINT traffic_reports_pkey;
ALTER TABLE traffic_reports_pre_partition
  DROP CONSTRAINT traffic_reports_router_id_period_start_mac_host_key;
ALTER TABLE traffic_reports_pre_partition DROP CONSTRAINT traffic_reports_router_id_fkey;
DROP INDEX idx_traffic_reports_router;
DROP INDEX idx_traffic_reports_date;
DROP INDEX idx_traffic_reports_mac_date;
DROP INDEX idx_traffic_reports_period_start;
DROP INDEX idx_traffic_reports_mac_period_start;

-- New range-partitioned parent. Identical columns/types/NOT NULLs/defaults to
-- the original; id continues monotonically from the preserved sequence.
CREATE TABLE traffic_reports (
  id              BIGINT      NOT NULL DEFAULT nextval('traffic_reports_id_seq'),
  router_id       UUID        NOT NULL REFERENCES routers(id) ON DELETE CASCADE,
  mac             TEXT        NOT NULL,
  ip              TEXT,
  host_value      TEXT        NOT NULL,
  date            DATE        NOT NULL,
  period_start    TIMESTAMPTZ NOT NULL,
  period_end      TIMESTAMPTZ NOT NULL,
  active_seconds  INT         NOT NULL DEFAULT 0,
  bytes_in        BIGINT      NOT NULL DEFAULT 0,
  bytes_out       BIGINT      NOT NULL DEFAULT 0,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  host_type       TEXT        NOT NULL,
  CONSTRAINT traffic_reports_host_type_check CHECK (host_type IN ('fqdn', 'ipv4', 'ipv6')),
  CONSTRAINT traffic_reports_router_id_period_start_mac_host_key
    UNIQUE (router_id, period_start, mac, host_type, host_value)
) PARTITION BY RANGE (period_start);

-- Seed weekly partitions (current ISO week + 4 ahead), relocate on/after-cutover
-- rows into them, then attach the remainder as the unbounded-below omnibus.
DO $$
DECLARE
  cutover DATE := date_trunc('week', CURRENT_DATE)::date; -- Monday of current ISO week
  wk      DATE;
  i       INT;
BEGIN
  FOR i IN 0..4 LOOP
    wk := cutover + (i * 7);
    EXECUTE format(
      'CREATE TABLE traffic_reports_%s PARTITION OF traffic_reports FOR VALUES FROM (%L) TO (%L)',
      to_char(wk, 'IYYY_IW'), wk::timestamptz, (wk + 7)::timestamptz
    );
  END LOOP;

  INSERT INTO traffic_reports
    (id, router_id, mac, ip, host_value, date, period_start, period_end,
     active_seconds, bytes_in, bytes_out, created_at, host_type)
  SELECT id, router_id, mac, ip, host_value, date, period_start, period_end,
     active_seconds, bytes_in, bytes_out, created_at, host_type
  FROM traffic_reports_pre_partition
  WHERE period_start >= cutover::timestamptz;

  DELETE FROM traffic_reports_pre_partition WHERE period_start >= cutover::timestamptz;

  EXECUTE format(
    'ALTER TABLE traffic_reports ATTACH PARTITION traffic_reports_pre_partition '
    || 'FOR VALUES FROM (MINVALUE) TO (%L)',
    cutover::timestamptz
  );
END $$;

-- Re-establish the original secondary indexes as local partitioned indexes
-- (CREATE INDEX on the parent propagates to every partition). Kept verbatim
-- through the burn-in window so a rollback stays cheap; the now-redundant ones
-- (date, mac_date, period_start) are dropped in the post-burn-in cleanup (#810).
CREATE INDEX idx_traffic_reports_router          ON traffic_reports (router_id);
CREATE INDEX idx_traffic_reports_date            ON traffic_reports (date);
CREATE INDEX idx_traffic_reports_mac_date        ON traffic_reports (mac, date);
CREATE INDEX idx_traffic_reports_period_start    ON traffic_reports (period_start DESC);
CREATE INDEX idx_traffic_reports_mac_period_start ON traffic_reports (mac, period_start);
