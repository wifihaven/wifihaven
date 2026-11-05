-- V94__profile_app_groups_mode.sql
-- Group modes (#2979, epic #2930, design docs/design/weekly-limits-and-app-groups.md §17).
--
-- ── What this adds ───────────────────────────────────────────────────────────
-- 1. `profile_app_groups.mode`: a profile's attachment of a group has the same
--    three modes as an app assignment (`app_policy_assignments.mode`, V28):
--    'blocked' blocks every member, 'allowed' allows every member, and
--    'time_limited' is the V92 group budget. TEXT with a CHECK, as V28 does.
-- 2. `profile_app_groups.allowed_during_schedule_block`: the attachment's #1679
--    toggle, read for an Allowed attachment only (design D19). Default TRUE, as
--    on `app_policy_assignments` (V56).
-- 3. An Allowed or Blocked attachment carries no limit (D21). A TimeLimited one
--    may still have none: image N-1 (#2973) writes limitless rows without a
--    `mode`, so they arrive as 'time_limited', and a stricter CHECK would fail
--    those writes. The adopting code reads a limitless TimeLimited row as
--    Allowed (D23: a limitless attachment is an Allowed one).
--
-- No backfill moves today's limitless rows to 'allowed'. Until the adopting
-- code deploys, image N-1 offers a limits drawer on those rows and its upsert
-- never writes `mode`, so setting a limit on a backfilled row would fail the
-- CHECK. The adopting code's reading covers them instead.
--
-- ── Inert until the adoption PRs ────────────────────────────────────────────
-- Schema only (docs/process/migrations.md#migrations-back-compat). Image N-1
-- reads neither column, and its INSERTs and upserts (which omit both columns)
-- leave 'time_limited' and TRUE, which pass both CHECKs whatever limits they
-- carry.
--
-- ── Cost ─────────────────────────────────────────────────────────────────────
-- Both ADD COLUMNs add a constant default, which Postgres stores without a
-- table rewrite. The two CHECKs validate by scanning only `profile_app_groups`,
-- one row per (profile, attached group). No growth table is scanned, rewritten
-- or re-indexed.

ALTER TABLE profile_app_groups
  ADD COLUMN mode TEXT NOT NULL DEFAULT 'time_limited',
  ADD COLUMN allowed_during_schedule_block BOOLEAN NOT NULL DEFAULT TRUE,
  ADD CONSTRAINT profile_app_groups_mode_check
    CHECK (mode IN ('blocked', 'allowed', 'time_limited')),
  ADD CONSTRAINT profile_app_groups_limit_only_if_time_limited
    CHECK (mode = 'time_limited' OR (daily_minutes IS NULL AND weekly_minutes IS NULL));
