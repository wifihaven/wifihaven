-- V4__connection_events.sql
-- One row per new outbound connection attempt observed by a router.
-- Replaces the role of `query_logs` (a DNS-server log) now that enforcement
-- moved to the gateway. The dashboard/log routes will switch to read this
-- table in #95; for now we only write.
CREATE TABLE connection_events (
  id         BIGSERIAL PRIMARY KEY,
  router_id  UUID NOT NULL REFERENCES routers(id) ON DELETE CASCADE,
  mac        TEXT,
  hostname   TEXT NOT NULL,
  dest_ip    TEXT,
  allowed    BOOLEAN NOT NULL,
  reason     TEXT NOT NULL,
  ts         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_conn_events_ts          ON connection_events(ts DESC);
CREATE INDEX idx_conn_events_mac_ts      ON connection_events(mac, ts DESC);
CREATE INDEX idx_conn_events_allowed_ts  ON connection_events(allowed, ts DESC);
CREATE INDEX idx_conn_events_router_ts   ON connection_events(router_id, ts DESC);

-- Store time-on-site as seconds, not minutes. The previous V1 column
-- minutes_used stays (immutability) but is no longer written by the new
-- /api/router/usage endpoint; it'll be removed alongside query_logs in a
-- future migration once the dashboard reads seconds_used.
ALTER TABLE time_usage
  ADD COLUMN seconds_used BIGINT NOT NULL DEFAULT 0;
