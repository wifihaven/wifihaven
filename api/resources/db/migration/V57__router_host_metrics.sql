-- V57__router_host_metrics.sql
-- #1718: router agent publishes native OS metrics (load / bandwidth / wireless
-- / connections) so operators can monitor router health from the cloud Grafana
-- dashboard the same way they would from LuCI locally.
--
-- Per AGENTS.md §migrations-back-compat this is the schema-only PR. The
-- follow-up PR adopts these tables in api/src (request decode + persist) and
-- adds the Grafana panels that query them. A second follow-up wires the agent
-- to actually emit the `host` block on the next metrics tick.
--
-- ── Three tables, not one ───────────────────────────────────────────────────
-- `host` in the wire JSON is one block, but it fans out into three relations
-- because the cardinalities are wildly different:
--
--   1. Scalar host vitals (load, cpu%, mem, conntrack) — one row per
--      (router, tick). Wide row, low write-rate.
--   2. Per-iface bandwidth — many ifaces per tick (br-lan, wan, phyN-apM…),
--      so (router, ts, iface) is the natural PK. Storing as a JSONB column
--      on table 1 would force per-iface graphs to unpack JSON for every
--      datapoint in Grafana — cheap inserts, expensive reads.
--   3. Per-MAC wireless clients — explicitly NOT a Prometheus dimension per
--      the cardinality firewall (AGENTS.md §instrument-new-functionality).
--      DB-resident only; Grafana surfaces this via an API-backed table-panel
--      drill-in, not via labeled time series.
--
-- ── Growth-table caveats (AGENTS.md §migrations-prod-data-volume) ───────────
-- All three are unbounded-growth (one row per router per tick, scaling with
-- fleet size). The migration itself is schema-metadata-only — `CREATE TABLE`
-- on empty relations is O(1) regardless of prod volume — so the
-- "fast-on-dev-slow-on-prod" risk doesn't apply to V57 itself. But every
-- query against these tables that the follow-up code adds must prove its plan
-- with EXPLAIN (ANALYZE) per §query-explain-before-merge before it merges.
-- The indexes below are sized for the read patterns Grafana will use:
--   * "last N hours of <scalar> per router" → idx_..._ts BRIN scan
--   * "currently connected wireless clients on this router" → PK prefix scan
--   * "show me this MAC's recent RSSI history" → idx_..._mac_ts
--
-- ── Retention ───────────────────────────────────────────────────────────────
-- Not enforced here. Retention/rollup follows the existing
-- `traffic_reports` / `connection_events` pattern and lands in a separate
-- issue once we know real-world growth on the prod fleet.

CREATE TABLE router_host_metrics (
  router_id        UUID         NOT NULL REFERENCES routers(id) ON DELETE CASCADE,
  ts               TIMESTAMPTZ  NOT NULL,
  load_m1          REAL,
  load_m5          REAL,
  load_m15         REAL,
  cpu_pct          REAL,
  mem_total_kb     BIGINT,
  mem_free_kb      BIGINT,
  mem_buffers_kb   BIGINT,
  mem_cached_kb    BIGINT,
  conntrack_count  INTEGER,
  PRIMARY KEY (router_id, ts)
);

-- BRIN over `ts` — the read pattern is "last N hours/days per router". A
-- BRIN on this monotonically-increasing column is ~1000× smaller than a
-- btree and competitive on the range scans Grafana actually issues.
CREATE INDEX idx_router_host_metrics_ts_brin
  ON router_host_metrics USING BRIN (ts);


CREATE TABLE router_host_iface_metrics (
  router_id  UUID         NOT NULL REFERENCES routers(id) ON DELETE CASCADE,
  ts         TIMESTAMPTZ  NOT NULL,
  iface      TEXT         NOT NULL,
  rx_bytes   BIGINT,
  tx_bytes   BIGINT,
  PRIMARY KEY (router_id, ts, iface)
);

CREATE INDEX idx_router_host_iface_metrics_ts_brin
  ON router_host_iface_metrics USING BRIN (ts);


CREATE TABLE router_wifi_clients (
  router_id     UUID         NOT NULL REFERENCES routers(id) ON DELETE CASCADE,
  ts            TIMESTAMPTZ  NOT NULL,
  iface         TEXT         NOT NULL,
  ssid          TEXT         NOT NULL,
  mac           TEXT         NOT NULL,
  rssi_dbm      SMALLINT,
  tx_rate_mbps  REAL,
  rx_rate_mbps  REAL,
  PRIMARY KEY (router_id, ts, mac)
);

-- "Show me this MAC's RSSI history across the fleet" — used by the
-- per-device drill-in panel. mac comes first because it is the selective
-- filter; ts second so the index also serves "most recent N for this MAC".
CREATE INDEX idx_router_wifi_clients_mac_ts
  ON router_wifi_clients (mac, ts DESC);

-- "What was connected to this router at time T" — Grafana's currently-
-- connected table-panel drill-in queries by (router_id, recent ts).
-- BRIN on ts is enough; the PK already covers (router_id, ts, mac).
CREATE INDEX idx_router_wifi_clients_ts_brin
  ON router_wifi_clients USING BRIN (ts);
