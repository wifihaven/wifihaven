-- V93__profile_app_groups_mode.sql
-- Group modes (epic #2930, design docs/design/weekly-limits-and-app-groups.md §17).
--
-- ── What this adds ───────────────────────────────────────────────────────────
-- 1. `profile_app_groups.mode`: a profile's attachment of a group has the same
--    three modes as an app assignment (`app_policy_assignments.mode`, V28):
--    'blocked' blocks every member, 'allowed' allows every member, and
--    'time_limited' is the V92 group budget. TEXT with a CHECK, as V28 does.
-- 2. `profile_app_groups.allowed_during_schedule_block`: the attachment's #1679
--    toggle, read for an Allowed attachment only (design D19). Default TRUE, as
--    on `app_policy_assignments` (V56).
-- 3. `profile_app_groups_has_limit` (V92, D4) is replaced by "a limit if and
--    only if time_limited": a TimeLimited attachment still needs a daily or
--    weekly limit, and an Allowed or Blocked one carries none (D21).
--
-- ── Inert until the adoption PRs ────────────────────────────────────────────
-- Schema only (docs/process/migrations.md#migrations-back-compat). Every
-- existing row, and every INSERT from image N-1 (which omits both columns),
-- gets 'time_limited' and TRUE. N-1 always writes a limit (D4), so its rows
-- satisfy the new CHECK. Nothing reads the new columns until the enforcement
-- slice, so no behaviour changes.
--
-- ── Cost ─────────────────────────────────────────────────────────────────────
-- Both ADD COLUMNs add a constant default, which Postgres stores without a
-- table rewrite. The two CHECKs validate by scanning `profile_app_groups`, one
-- row per (profile, attached group). No growth table is scanned, rewritten or
-- re-indexed.

ALTER TABLE profile_app_groups
  ADD COLUMN mode TEXT NOT NULL DEFAULT 'time_limited',
  ADD COLUMN allowed_during_schedule_block BOOLEAN NOT NULL DEFAULT TRUE,
  ADD CONSTRAINT profile_app_groups_mode_check
    CHECK (mode IN ('blocked', 'allowed', 'time_limited')),
  DROP CONSTRAINT profile_app_groups_has_limit,
  ADD CONSTRAINT profile_app_groups_limit_iff_time_limited
    CHECK ((mode = 'time_limited') = (daily_minutes IS NOT NULL OR weekly_minutes IS NOT NULL));
