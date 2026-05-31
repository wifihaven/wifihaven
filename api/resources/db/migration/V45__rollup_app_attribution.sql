-- V45: pre-attribute app ownership at rollup-write time (#1091).
--
-- Lets the per-app read paths (#769 groupBy=app, #1061 usage-by-app)
-- aggregate via SQL `GROUP BY app_id` against the rollup tables instead of
-- re-running the in-memory HostMatch.lookupApex tail-walk over every row at
-- read time. The rollup writer computes the owning app(s) once, at
-- rollup-write time, and persists them here.
--
-- Invalidation is Option 2 (lazy): every app_hosts mutation bumps a global
-- counter; each rollup row records the counter value it was attributed at.
-- On read, if any in-window rollup row carries a version older than the
-- current counter (or NULL = never attributed), the read path falls back to
-- live in-memory matching for that window. No re-roll on the app_hosts
-- mutation hot path; stale rollup attribution degrades gracefully instead of
-- silently lying.
--
-- Prod-data-volume note (AGENTS.md §migrations-prod-data-volume):
-- traffic_hourly / traffic_daily are unbounded-growth tables, but the two
-- ALTERs below add NULLable columns with NO default, which Postgres records
-- as a catalog-only change — no table rewrite, no full scan. Safe at prod
-- scale (unlike a NOT NULL / DEFAULT add, which would rewrite). The side
-- tables and the counter table are brand-new and empty.

-- Monotonic counter, bumped on every app_hosts mutation. Single row enforced
-- by the boolean PK + CHECK; readers do `SELECT version FROM
-- app_hosts_version`, writers do `UPDATE ... SET version = version + 1`.
CREATE TABLE app_hosts_version (
  singleton BOOLEAN PRIMARY KEY DEFAULT TRUE,
  version   BIGINT  NOT NULL DEFAULT 0,
  CONSTRAINT app_hosts_version_singleton CHECK (singleton)
);
INSERT INTO app_hosts_version (singleton, version) VALUES (TRUE, 0);

-- The app_hosts version each rollup row was attributed at. NULL = never
-- attributed (rolled before V45, or before the writer ran the attribution
-- step). A value < the current counter = stale → read-time fallback.
ALTER TABLE traffic_hourly ADD COLUMN app_hosts_version BIGINT;
ALTER TABLE traffic_daily  ADD COLUMN app_hosts_version BIGINT;

-- Per-rollup-row → owning app(s). A host that belongs to N apps fans out to N
-- rows here, so app_id is part of the PK. The composite FK back to the parent
-- rollup row cascades deletes (router teardown, re-roll churn); the FK to apps
-- cascades app removal. Indexed on app_id for the `GROUP BY app_id` read path.
CREATE TABLE traffic_hourly_apps (
  router_id         UUID        NOT NULL,
  mac               TEXT        NOT NULL,
  hostname          TEXT        NOT NULL,
  bucket_start      TIMESTAMPTZ NOT NULL,
  app_id            BIGINT      NOT NULL REFERENCES apps(id) ON DELETE CASCADE,
  app_hosts_version BIGINT      NOT NULL,
  PRIMARY KEY (router_id, mac, hostname, bucket_start, app_id),
  FOREIGN KEY (router_id, mac, hostname, bucket_start)
    REFERENCES traffic_hourly (router_id, mac, hostname, bucket_start) ON DELETE CASCADE
);
CREATE INDEX idx_traffic_hourly_apps_app ON traffic_hourly_apps (app_id);

CREATE TABLE traffic_daily_apps (
  router_id         UUID        NOT NULL,
  mac               TEXT        NOT NULL,
  hostname          TEXT        NOT NULL,
  date              DATE        NOT NULL,
  app_id            BIGINT      NOT NULL REFERENCES apps(id) ON DELETE CASCADE,
  app_hosts_version BIGINT      NOT NULL,
  PRIMARY KEY (router_id, mac, hostname, date, app_id),
  FOREIGN KEY (router_id, mac, hostname, date)
    REFERENCES traffic_daily (router_id, mac, hostname, date) ON DELETE CASCADE
);
CREATE INDEX idx_traffic_daily_apps_app ON traffic_daily_apps (app_id);
