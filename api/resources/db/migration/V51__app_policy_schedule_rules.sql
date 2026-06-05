-- V51__app_policy_schedule_rules.sql
-- #1378 (per-app schedules epic #1376): let an individual app carry its own
-- time window, layered on top of the profile's general schedule — "allowed
-- during W" (reachable even during profile downtime) or "blocked during W"
-- (dropped even when the profile is otherwise unrestricted). See the merged
-- design docs/design/per-app-schedules.md §3.3.
--
-- This is a SCHEMA-ONLY migration (per the migration-isolation rule in
-- CLAUDE.md): it ships with no source and no tests. The Models / repo / route /
-- PolicyService evaluation that READ this table land in the follow-up
-- API-adoption PR (#1379). Until then the table is inert — nothing reads
-- `app_policy_schedule_rules`, so there is NO functional change from this
-- migration alone. That inertness is exactly what makes it backward-compatible
-- with image-(N-1): the existing api.test suite applies this migration against
-- the currently-deployed code and passes unchanged (the isolation gate).
--
-- ── Shape: (assignment, named-schedule, mode), identical to V50's profile rules
-- This is the EXACT same shape as V50's `profile_schedule_rules`, but for apps:
-- it FKs an `app_policy_assignments` row (the per-(app, profile) policy row from
-- V28) instead of a `profiles` row, and references the #1069 household-scoped
-- `named_schedules` table (V50) — NOT the V1 profile-scoped `schedules` table
-- (the name collision and why named_schedules exists are documented in V50).
-- Profiles and apps deliberately converge on one (entity, schedule, mode) model
-- (design §3.3). A schedule's day/time math lives entirely in its
-- `schedule_windows` rows (V50), so this table carries no inline windows — a
-- compound schedule is just multiple windows on the referenced named schedule.
--
-- ── Many rules per app, each block- OR allow-mode ──────────────────────────
-- An app may attach more than one (schedule, mode) pair to a single assignment
-- (e.g. "allowed during Bedtime" AND "blocked during Homework"), so this is a
-- child table, not a single `app_policy_assignments.schedule_id` column — the
-- single-reference column was deliberately rejected in V50 (can't carry a mode,
-- caps at one schedule). Each row's mode decides what an active window does:
--   * allowed_during — while any window of the schedule is active, the app's
--     hosts are carved into extraAllowed, beating the profile's whole-MAC block
--     (#421 allow-beats-block) — the headline "reachable during bedtime" case.
--   * blocked_during — while active, the app's hosts go to extraBlocked, even
--     when the profile is otherwise unrestricted.
-- The server-side collapse into the existing extraAllowed/extraBlocked wire
-- fields (no snapshot/router change) lives in PolicyService — design §4.
--
-- ── ON DELETE CASCADE on both FKs ─────────────────────────────────────────
-- Deleting an assignment (or a named schedule) just drops its rule rows — the
-- rule's effect simply stops applying, no orphan. UNIQUE(assignment_id,
-- schedule_id, mode) lets a schedule be attached once per mode and prevents
-- exact dupes (attaching the same schedule as both modes is nonsensical but
-- harmless).
--
-- ── Prod-volume safety ─────────────────────────────────────────────────────
-- Bounded metadata surface — rows scale with apps × profiles × rules (tens of
-- rows), NOT with event volume. Not an unbounded-growth table (traffic_reports,
-- connection_events, block_events, rollups). Index builds are on an empty
-- surface. Safe on the Flyway startup critical path even at full prod volume.

CREATE TABLE app_policy_schedule_rules (
  id             BIGSERIAL PRIMARY KEY,
  assignment_id  BIGINT NOT NULL
                   REFERENCES app_policy_assignments(id) ON DELETE CASCADE,
  schedule_id    BIGINT NOT NULL
                   REFERENCES named_schedules(id) ON DELETE CASCADE,  -- #1069 named schedule
  mode           TEXT NOT NULL
                   CHECK (mode IN ('allowed_during', 'blocked_during')),
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (assignment_id, schedule_id, mode)
);
-- The snapshot builder / decide fallback probe by assignment (per-app fold over
-- an assignment's rules — design §4.1); the SPA / cascade check probes by
-- schedule ("what references this named schedule before I delete it"). Mirrors
-- the two indexes V50 added on profile_schedule_rules.
CREATE INDEX idx_app_policy_schedule_rules_assignment
  ON app_policy_schedule_rules(assignment_id);
CREATE INDEX idx_app_policy_schedule_rules_schedule
  ON app_policy_schedule_rules(schedule_id);
