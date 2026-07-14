-- V70__beta_cohort.sql
-- Multi-tenant Phase-5 sub-issue P5-6 (#2137, epic #622): the beta→paid flip
-- lifecycle. Authored against docs/design/multi-tenant-launch.md §5.4 as
-- superseded by the operator's 2026-07-14 flip-trigger decision (issue #2137
-- comment): the flip is EVENT-TRIGGERED, not a hand-set calendar date.
--
-- SCHEMA-ONLY PR: per docs/process/migrations.md#migrations-back-compat this
-- migration ships alone (SQL + docs). The source that reads/writes this table
-- (the flip job, the active-household counter, the Subscribe-CTA gating) lands
-- in the follow-up code PR of #2137. The existing feature suite is the
-- back-compat gate.
--
-- ── Prod data-volume (docs/process/migrations.md#migrations-prod-data-volume) ─
-- Creates one new table and inserts a single row. No unbounded-growth table
-- (traffic_reports, connection_events, block_events, rollups) is scanned or
-- rewritten — metadata-only + one INSERT; trivially inside the 15-minute
-- Render port-scan window.

-- ── beta_cohort ──────────────────────────────────────────────────────────────
-- The cohort-wide flip clock (design §5.4, event-triggered model). ONE
-- installation-wide row — the flip is cohort-wide in v1, not per-household.
--
-- The flip is event-triggered: the clock STARTS the first time the count of
-- active beta households reaches the configured threshold
-- (flip.thresholdHouseholds, default 25), then runs the configured window
-- (flip.windowDays, default 60) and LATCHES — a later dip in the active count
-- does not pause or reset it. At window end, unconverted (status='beta')
-- households flip to 'lapsed' (enforcement stops via a permissive snapshot;
-- never brick the network, §5.3).
--
--   clock_started_at  when the threshold was first reached (NULL until then).
--                     Persisted so the latch survives an API restart — the
--                     window end is clock_started_at + flip.windowDays and must
--                     not be recomputed from a fresh "now" after a bounce.
--   flipped_at        when the window-end flip was executed (NULL until then).
--                     A one-shot latch so the flip job runs the beta→lapsed
--                     sweep exactly once, and so the Subscribe-CTA gate can tell
--                     "window open" (started, not yet flipped) from "window
--                     ended".
--
-- Singleton enforced the canonical Postgres way: a BOOLEAN primary key pinned
-- TRUE by a CHECK, so at most one row can ever exist. Both timestamps default
-- NULL — the row exists from migration time (so the flip job always has a row
-- to read/UPDATE) but the clock has not started and nothing has flipped.
CREATE TABLE beta_cohort (
  id               BOOLEAN PRIMARY KEY DEFAULT TRUE CHECK (id),
  clock_started_at TIMESTAMPTZ,
  flipped_at       TIMESTAMPTZ
);

INSERT INTO beta_cohort (id) VALUES (TRUE);
