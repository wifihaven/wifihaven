-- V63__ambient_host_baseline.sql
-- #2077: schema foundation for the idle-traffic discrimination pass — the
-- isolation-learned ambient-host baseline + engagement-anchor gate. Design
-- and prod evidence: docs/design/idle-traffic-discrimination.md.
--
-- Two pieces:
--
-- 1. Tuning knobs on household_settings (V52 presence-tuning precedent —
--    operator-adjustable, never hardcoded):
--
--    - ambient_gate_enabled — master switch for the anchor gate (a device
--      presence span counts toward screen time iff it contains an
--      app-attributed row or a non-ambient host). Default FALSE: learning
--      runs and is inspectable before the operator enables enforcement.
--    - ambient_isolation_max_hosts — a merged device span with at most this
--      many distinct hosts (and no app-attributed row) is "isolated" for
--      learning purposes. Default 2 (prod idle windows show p90 = 1 distinct
--      non-infra host; real use shows p50 = 12).
--    - ambient_min_isolated_days — distinct isolated days within the window
--      before a host is learned ambient. Default 3 (causally validated on
--      prod: 2 is aggressive, 4 leaves phantom overnight minutes).
--    - ambient_learning_window_days — trailing window for the day counts.
--      Default 14.
--
-- 2. ambient_host_days — one row per (host, local day) on which the host
--    appeared in an isolated device span. Written incrementally by a daily
--    learner job (previous day only, partition-pruned against
--    traffic_reports); the ambient set at read time is the hosts with
--    >= ambient_min_isolated_days distinct days inside the trailing window.
--    The learner prunes rows older than the window, so the table is bounded
--    at (distinct background hosts) x (window days) — a few hundred rows on
--    the prod household, never traffic-volume-shaped. `isolated_span_count`
--    is diagnostic (explain surface) only, never read for the threshold.
--
-- This is a SCHEMA-ONLY migration (AGENTS.md §migrations-back-compat): no
-- source, no tests. The learner job, the Presence anchor-gate entry point,
-- the settings plumbing, and the explain endpoint are the follow-up code PR.
-- Until then every column/table here is inert — nothing reads or writes it,
-- existing rows get the defaults, and behavior is unchanged.
--
-- Cost: household_settings is the small singleton metadata table — ADD
-- COLUMN with a constant default is metadata-only in PG 11+ (no rewrite).
-- CREATE TABLE of an empty table is trivially safe on the Flyway startup
-- critical path at prod volume; neither touches the unbounded-growth event
-- tables.
--
-- Nullable-by-default contract: all new columns are NOT NULL DEFAULT, so
-- image-(N-1) — which never names them in any INSERT/UPDATE — keeps working
-- unchanged.

ALTER TABLE household_settings
  ADD COLUMN ambient_gate_enabled        BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN ambient_isolation_max_hosts INTEGER NOT NULL DEFAULT 2,
  ADD COLUMN ambient_min_isolated_days   INTEGER NOT NULL DEFAULT 3,
  ADD COLUMN ambient_learning_window_days INTEGER NOT NULL DEFAULT 14;

CREATE TABLE ambient_host_days (
  host                TEXT NOT NULL,
  day                 DATE NOT NULL,
  isolated_span_count INTEGER NOT NULL DEFAULT 1,
  created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (host, day)
);

-- Read path is "count distinct days per host within the trailing window";
-- the PK (host, day) already serves it. The learner's prune ("delete rows
-- with day < cutoff") wants the day-first order:
CREATE INDEX idx_ambient_host_days_day ON ambient_host_days (day);
