-- V50__named_schedules.sql
-- #1069: household-scoped reusable named schedules ("Bedtime", "School hours",
-- ...). The foundation primitive that anything time-bound can reference:
-- profiles today, per-app policy assignments (#1378) and schedule-driven
-- blocklists (#1067) next. See the #1069 spec and
-- docs/design/per-app-schedules.md §3.
--
-- This is a SCHEMA-ONLY migration (per the migration-isolation rule in
-- CLAUDE.md): it ships with no source or tests. The Models / repos / routes /
-- PolicyService snapshot expansion that READ these tables land in the follow-up
-- API-adoption PR; the Settings -> Schedules SPA lands after that. Until then
-- the tables are inert — nothing reads `named_schedules` / `schedule_windows` /
-- `profile_schedule_rules`, the existing per-profile `schedules` table (V1/V16)
-- still backs all schedule enforcement, so there is NO functional change from
-- this migration alone. That inertness is exactly what makes it
-- backward-compatible with image-(N-1): the existing api.test suite applies
-- this migration against the currently-deployed code and passes unchanged (the
-- isolation gate).
--
-- ── Table-name collision (read this) ──────────────────────────────────────
-- #1069's spec names the new table `schedules`, but V1 already defines a
-- profile-scoped `schedules` table that deployed enforcement code still reads
-- (its current columns, after V16, are: profile_id, name, days TEXT[],
-- start_local TIME, end_local TIME, tz TEXT). We may NOT drop or retype those
-- columns here — that would fail the isolation gate and break rollback (#1176
-- class). So the new household-scoped primitive lands under a distinct name,
-- `named_schedules`, fully additively. Once the API rolls forward to read
-- `named_schedules` and the old `schedules` rows are dead, a later
-- deprecation-window migration can drop the V1 table and rename
-- `named_schedules` -> `schedules`. (docs/design/per-app-schedules.md §3.3
-- already anticipates this: "If #1069's final schema names the table
-- differently, this FK target tracks that name.")
--
-- ── Windows are a typed child table, mirroring the V1 schedule columns ─────
-- A named schedule owns one-or-more windows via the `schedule_windows` child
-- table, whose columns are exactly the existing V1 `schedules` time columns
-- (days TEXT[], start_local TIME, end_local TIME, tz TEXT) minus profile_id.
-- This is deliberate over a JSONB `windows` blob:
--   * It reuses the existing typed `Schedule` domain model and the
--     DST-correct `PolicyService.scheduleActiveAt` / `scheduleEndInstantAfter`
--     time math unchanged — no JSON parsing, no synthetic Schedule rebuild.
--   * It preserves a per-window `tz` (the V1 schedules table is per-row tz
--     since V16), so migrating existing profile schedules into this model is a
--     bit-for-bit `INSERT ... SELECT` with zero behaviour change.
--   * It matches this codebase's precedent for a named bundle with many
--     sub-items: `apps` owns `app_hosts` as a typed child table, not JSONB.
-- Compound schedules (e.g. "school hours: weekdays 08-15, Fri 08-12") are just
-- multiple `schedule_windows` rows. A window where start_local == end_local is
-- a never-active empty window (same convention as scheduleActiveAt).
--
-- ── A profile has MANY schedules, each block- OR allow-mode ────────────────
-- A profile references named schedules through the `profile_schedule_rules`
-- join table, NOT a single `profiles.schedule_id` column — so it can carry more
-- than one (e.g. "Bedtime" + "School hours"), and each attachment names a mode:
--   * blocked_during — while any window of the schedule is active, the profile
--     is in scheduled downtime (today's bedtime semantics).
--   * allowed_during — while active, the schedule carves the profile open,
--     beating block-mode windows / default-deny (the #421 allow-beats-block
--     rule). Paired with a default-deny profile (#1318) this expresses
--     "internet only during these hours".
-- This is the SAME (entity, schedule, mode) shape the per-app schedule design
-- uses (docs/design/per-app-schedules.md §3.3, `app_policy_schedule_rules`) —
-- profiles and apps converge on one model. The single-reference columns the
-- #1069 spec sketched (`profiles.schedule_id`, `app_policy_assignments
-- .schedule_id`) are deliberately NOT added: they can't carry a mode and cap at
-- one schedule. #1378 adds the analogous `app_policy_schedule_rules` for apps.
--
-- ── Single-household ──────────────────────────────────────────────────────
-- This codebase is single-household (only `household_settings` exists, as a
-- single-row config). Like `apps.slug`, `named_schedules.name` is uniquely
-- scoped to that implicit household — no `household_id` column / FK, matching
-- the spec's UNIQUE (household_id, name) under a one-household model.
--
-- ── Prod-volume safety ────────────────────────────────────────────────────
-- All three tables are bounded metadata surfaces — rows scale with the handful
-- of schedules (their windows, their per-profile attachments) the operator
-- defines, NOT with event volume. None is an unbounded-growth table
-- (traffic_reports, connection_events, block_events, rollups). All index builds
-- are on empty / tiny surfaces. Safe on the Flyway startup critical path even
-- at full prod data volume.

CREATE TABLE named_schedules (
  id           BIGSERIAL PRIMARY KEY,
  name         TEXT NOT NULL UNIQUE,
  description  TEXT,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- One row per window. Columns mirror the V1 `schedules` time columns exactly so
-- the existing Schedule model + scheduleActiveAt apply unchanged. `days` uses
-- the same short day-of-week token vocabulary as schedules.days. `start_local`
-- / `end_local` are wall-clock TIMEs interpreted in `tz`; an overnight window
-- (start_local > end_local) wraps past midnight, handled by scheduleActiveAt.
CREATE TABLE schedule_windows (
  id           BIGSERIAL PRIMARY KEY,
  schedule_id  BIGINT NOT NULL REFERENCES named_schedules(id) ON DELETE CASCADE,
  days         TEXT[] NOT NULL,
  start_local  TIME   NOT NULL,
  end_local    TIME   NOT NULL,
  tz           TEXT   NOT NULL
);
CREATE INDEX idx_schedule_windows_schedule ON schedule_windows(schedule_id);

-- Per-profile attachments. A profile may reference any number of named
-- schedules; each row carries the mode that decides what an active window does.
-- ON DELETE CASCADE on both FKs: deleting a schedule (or a profile) just drops
-- its attachment rows — the schedule's effect simply stops applying, no orphan.
-- UNIQUE(profile_id, schedule_id, mode) lets a schedule be attached once per
-- mode (attaching the same schedule as both blocked_during and allowed_during
-- is nonsensical but harmless; the unique key just prevents exact dupes).
CREATE TABLE profile_schedule_rules (
  id           BIGSERIAL PRIMARY KEY,
  profile_id   BIGINT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  schedule_id  BIGINT NOT NULL REFERENCES named_schedules(id) ON DELETE CASCADE,
  mode         TEXT NOT NULL CHECK (mode IN ('allowed_during', 'blocked_during')),
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (profile_id, schedule_id, mode)
);
-- Snapshot builder probes by profile (per-profile fold) and the SPA / cascade
-- check probes by schedule ("what references this before I delete it").
CREATE INDEX idx_profile_schedule_rules_profile  ON profile_schedule_rules(profile_id);
CREATE INDEX idx_profile_schedule_rules_schedule ON profile_schedule_rules(schedule_id);

-- Deliberately NOT added here: a blocklist-subscription schedule reference.
-- This codebase has no blocklist_subscriptions table — blocklists attach to a
-- profile via the profiles.blocked_categories TEXT[] array. #1067
-- (schedule-driven blocklist activation) will introduce a real subscription
-- model and add its own schedule reference against named_schedules at that time.
