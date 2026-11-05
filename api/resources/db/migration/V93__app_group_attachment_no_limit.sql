-- V93__app_group_attachment_no_limit.sql
-- #2973 (epic #2930; design docs/design/weekly-limits-and-app-groups.md D4,
-- revised 2026-11-03).
--
-- ── What this changes ────────────────────────────────────────────────────────
-- Drops `profile_app_groups_has_limit` (V92), which required an attachment to
-- carry a daily or a weekly limit. An attachment with neither is now a bundle
-- with no shared budget: it is never exhausted, the same way a NULL
-- `daily_minutes` means "no daily limit" (#1627). The per-column checks
-- (`profile_app_groups_daily_check`, `profile_app_groups_weekly_check`: NULL
-- or > 0) stay.
--
-- ── Inert until the adoption PR ─────────────────────────────────────────────
-- Schema only (docs/process/migrations.md#migrations-back-compat). Image N-1's
-- `PUT /api/profiles/:id/app-groups/:gid` still rejects a body with neither
-- limit (`AppGroupRoutes.validLimits`), so no limitless row is written until
-- the code PR for #2973 deploys. Rolling back to N-1 with limitless rows
-- present keeps enforcement correct: N-1 already treats each NULL limit as
-- "no limit" (`PolicyService.exhaustedBudgets`), and no N-1 reader unwraps
-- one. N-1's SPA would show such a group's limit chip with no text.
--
-- ── Cost ─────────────────────────────────────────────────────────────────────
-- Dropping a CHECK constraint is a catalog change: no scan, no rewrite. It
-- takes a brief ACCESS EXCLUSIVE lock on `profile_app_groups`, a table with
-- one row per (profile, attached group). No growth table is touched.

ALTER TABLE profile_app_groups
  DROP CONSTRAINT profile_app_groups_has_limit;
