-- V2__openwrt.sql
-- OpenWRT router enrollment, periodic traffic reports, and block events.
-- Spec: docs/architecture-openwrt.md (#74), tracking issue #67.

-- Registered OpenWRT gateways. One row per physical router.
-- enrollment_token_hash is set when an admin creates the row in the UI; it is
-- consumed once by POST /api/router/register, which writes token_hash and
-- clears enrollment_token_hash. token_hash is the bearer token used for all
-- subsequent /api/router/* calls. last_etag is the last policy-snapshot etag
-- the agent acknowledged, used to short-circuit GET /api/router/policy with
-- 304 Not Modified.
CREATE TABLE routers (
  id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name                  TEXT NOT NULL,
  enrollment_token_hash TEXT,
  token_hash            TEXT,
  last_seen_at          TIMESTAMPTZ,
  last_etag             TEXT,
  created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_routers_enrollment_token_hash ON routers(enrollment_token_hash)
  WHERE enrollment_token_hash IS NOT NULL;
CREATE INDEX idx_routers_token_hash ON routers(token_hash)
  WHERE token_hash IS NOT NULL;

-- Raw audit log of POST /api/router/usage payloads. Each row is one
-- (router, mac, hostname) record from one 5-minute reporting window. The
-- unique constraint makes router retries idempotent.
CREATE TABLE traffic_reports (
  id              BIGSERIAL PRIMARY KEY,
  router_id       UUID NOT NULL REFERENCES routers(id) ON DELETE CASCADE,
  mac             TEXT NOT NULL,
  ip              TEXT,
  hostname        TEXT NOT NULL,
  date            DATE NOT NULL,
  period_start    TIMESTAMPTZ NOT NULL,
  period_end      TIMESTAMPTZ NOT NULL,
  active_seconds  INT NOT NULL DEFAULT 0,
  bytes_in        BIGINT NOT NULL DEFAULT 0,
  bytes_out       BIGINT NOT NULL DEFAULT 0,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE(router_id, period_start, mac, hostname)
);
CREATE INDEX idx_traffic_reports_router       ON traffic_reports(router_id);
CREATE INDEX idx_traffic_reports_date         ON traffic_reports(date);
CREATE INDEX idx_traffic_reports_mac_date     ON traffic_reports(mac, date);
CREATE INDEX idx_traffic_reports_period_start ON traffic_reports(period_start DESC);

-- Record of block decisions surfaced to the user (router /decision responses
-- and /blocked redirects). Distinct from query_logs, which tracks every DNS
-- query; block_events tracks only the moments a user actually saw a block.
CREATE TABLE block_events (
  id        BIGSERIAL PRIMARY KEY,
  mac       TEXT,
  hostname  TEXT NOT NULL,
  reason    TEXT NOT NULL,
  ts        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_block_events_ts  ON block_events(ts DESC);
CREATE INDEX idx_block_events_mac ON block_events(mac);

-- Add byte counters to time_usage so /api/router/usage can roll bytes up
-- alongside minutes (existing minutes_used column).
ALTER TABLE time_usage
  ADD COLUMN bytes_in  BIGINT NOT NULL DEFAULT 0,
  ADD COLUMN bytes_out BIGINT NOT NULL DEFAULT 0;
