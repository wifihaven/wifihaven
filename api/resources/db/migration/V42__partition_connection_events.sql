-- V42__partition_connection_events.sql
-- #806 (design: docs/design/db-partitioning.md, #793): convert connection_events
-- to a weekly RANGE-partitioned table on ts. One row per outbound connection
-- attempt; every non-trivial query has a `ts > NOW() - INTERVAL …` predicate or
-- is a bounded `ORDER BY ts DESC LIMIT N`. Retention-via-partition-drop (#812)
-- is the obvious win. Same detach-and-reattach technique as V41.
--
-- DISCREPANCY WITH THE DESIGN DOC (flagged here per the worktree TDD policy):
-- docs/design/db-partitioning.md states connection_events "has no unique
-- constraints" and lists only the V4 + V22 indexes. That predates V15, which
-- added UNIQUE INDEX idx_conn_events_event_id (event_id) — the dedup key for the
-- idempotent ingest upsert (#338). A unique index on a partitioned table MUST
-- include the partition key, so a bare UNIQUE (event_id) is illegal here. We
-- widen it to UNIQUE (event_id, ts); the ingest upsert's ON CONFLICT target is
-- updated to (event_id, ts) in the same PR. This preserves idempotency: a
-- retry-queue replay (#330) carries the same router-supplied ts as the original
-- event, so (event_id, ts) collapses replays exactly as (event_id) did. Two
-- genuinely distinct events never share an event_id (client UUID, else
-- gen_random_uuid()), so the widened key cannot admit a true duplicate.
--
-- As in V41: drop the id PRIMARY KEY (illegal without the partition key; id is a
-- surrogate used only as a keyset-pagination tiebreaker on (ts, id), which a
-- plain monotonic sequence still satisfies) and detach the id sequence so a
-- future partition drop can't take it with it. Partition seeding is anchored to
-- CURRENT_DATE at migration time (see V41 for why).

ALTER SEQUENCE connection_events_id_seq OWNED BY NONE;

ALTER TABLE connection_events RENAME TO connection_events_pre_partition;

-- Strip indexes + constraints from the omnibus to free the canonical names. The
-- host_type CHECK is deliberately KEPT: ATTACH PARTITION requires the child to
-- already carry an equivalent of each of the parent's CHECK constraints, which
-- Postgres merges with the same-named check the parent declares.
ALTER TABLE connection_events_pre_partition DROP CONSTRAINT connection_events_pkey;
ALTER TABLE connection_events_pre_partition DROP CONSTRAINT connection_events_router_id_fkey;
DROP INDEX idx_conn_events_ts;
DROP INDEX idx_conn_events_mac_ts;
DROP INDEX idx_conn_events_allowed_ts;
DROP INDEX idx_conn_events_router_ts;
DROP INDEX idx_conn_events_event_id;
DROP INDEX idx_conn_events_router_dest_ip_ts;
DROP INDEX idx_conn_events_mac_dest_resolved;

-- New range-partitioned parent. Identical columns/types/NOT NULLs/defaults to
-- the original (logical column order); id continues from the preserved sequence.
-- UNIQUE widened from (event_id) to (event_id, ts) to include the partition key.
CREATE TABLE connection_events (
  id                  BIGINT      NOT NULL DEFAULT nextval('connection_events_id_seq'),
  router_id           UUID        NOT NULL REFERENCES routers(id) ON DELETE CASCADE,
  mac                 TEXT,
  host_value          TEXT        NOT NULL,
  dest_ip             TEXT,
  allowed             BOOLEAN     NOT NULL,
  ts                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  host_type           TEXT        NOT NULL,
  event_id            UUID        NOT NULL DEFAULT gen_random_uuid(),
  resolved_host_value TEXT,
  reason              JSONB       NOT NULL,
  CONSTRAINT connection_events_host_type_check CHECK (host_type IN ('fqdn', 'ipv4', 'ipv6')),
  CONSTRAINT connection_events_event_id_ts_key UNIQUE (event_id, ts)
) PARTITION BY RANGE (ts);

DO $$
DECLARE
  cutover DATE := date_trunc('week', CURRENT_DATE)::date; -- Monday of current ISO week
  wk      DATE;
  i       INT;
BEGIN
  FOR i IN 0..4 LOOP
    wk := cutover + (i * 7);
    EXECUTE format(
      'CREATE TABLE connection_events_%s PARTITION OF connection_events FOR VALUES FROM (%L) TO (%L)',
      to_char(wk, 'IYYY_IW'), wk::timestamptz, (wk + 7)::timestamptz
    );
  END LOOP;

  INSERT INTO connection_events
    (id, router_id, mac, host_value, dest_ip, allowed, ts, host_type,
     event_id, resolved_host_value, reason)
  SELECT id, router_id, mac, host_value, dest_ip, allowed, ts, host_type,
     event_id, resolved_host_value, reason
  FROM connection_events_pre_partition
  WHERE ts >= cutover::timestamptz;

  DELETE FROM connection_events_pre_partition WHERE ts >= cutover::timestamptz;

  EXECUTE format(
    'ALTER TABLE connection_events ATTACH PARTITION connection_events_pre_partition '
    || 'FOR VALUES FROM (MINVALUE) TO (%L)',
    cutover::timestamptz
  );
END $$;

-- Re-establish the original secondary indexes as local partitioned indexes.
-- idx_conn_events_ts is now redundant with partition pruning and is dropped in
-- the post-burn-in cleanup (#810); kept here so a rollback stays cheap.
CREATE INDEX idx_conn_events_ts         ON connection_events (ts DESC);
CREATE INDEX idx_conn_events_mac_ts     ON connection_events (mac, ts DESC);
CREATE INDEX idx_conn_events_allowed_ts ON connection_events (allowed, ts DESC);
CREATE INDEX idx_conn_events_router_ts  ON connection_events (router_id, ts DESC);
CREATE INDEX idx_conn_events_router_dest_ip_ts
  ON connection_events (router_id, dest_ip, ts DESC)
  WHERE dest_ip IS NOT NULL;
CREATE INDEX idx_conn_events_mac_dest_resolved
  ON connection_events (mac, dest_ip)
  WHERE resolved_host_value IS NOT NULL;
