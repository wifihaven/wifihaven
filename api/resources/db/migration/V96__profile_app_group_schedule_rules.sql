-- V96__profile_app_group_schedule_rules.sql
-- Group schedule rules (epic #2930, design docs/design/weekly-limits-and-app-groups.md §18).
--
-- ── What this adds ───────────────────────────────────────────────────────────
-- `profile_app_group_schedule_rules`: a profile's group attachment
-- (`profile_app_groups`) takes the same named-schedule rules an app assignment
-- takes in `app_policy_schedule_rules` (V51): any number of
-- (named schedule, 'allowed_during' | 'blocked_during') pairs. Same shape as V51,
-- keyed to the attachment instead of the assignment.
--
-- Composite foreign keys make a rule that points at another household's
-- attachment or schedule unrepresentable, as V92 does for groups. They need two
-- new `UNIQUE (id, household_id)` constraints, on `profile_app_groups` and on
-- `named_schedules`. `id` is already unique on both, so neither can fail on
-- existing data. Nothing updates either table's `household_id`, so the FKs
-- never block an existing write.
--
-- Detaching a group, or deleting the schedule, drops its rules (ON DELETE
-- CASCADE). Changing an attachment's mode or limits keeps them: the upsert's
-- ON CONFLICT (profile_id, group_id) DO UPDATE keeps the row id.
--
-- ── Inert until the adoption PR ─────────────────────────────────────────────
-- Schema only (docs/process/migrations.md#migrations-back-compat). Nothing in
-- image N-1 reads or writes the new table, so no behaviour changes. Deleting an
-- attachment or a named schedule in N-1 cascades into an empty table.
--
-- ── Cost ─────────────────────────────────────────────────────────────────────
-- The two unique constraints build indexes over one row per attachment and one
-- row per named schedule. The new table starts empty. No growth table
-- (`traffic_reports`, `connection_events`, `block_events`, rollups) is scanned,
-- rewritten or re-indexed.

ALTER TABLE named_schedules
  ADD CONSTRAINT uq_named_schedules_id_household UNIQUE (id, household_id);

ALTER TABLE profile_app_groups
  ADD CONSTRAINT uq_profile_app_groups_id_household UNIQUE (id, household_id);

CREATE TABLE profile_app_group_schedule_rules (
  id            BIGSERIAL   PRIMARY KEY,
  household_id  BIGINT      NOT NULL,
  attachment_id BIGINT      NOT NULL,
  schedule_id   BIGINT      NOT NULL,
  mode          TEXT        NOT NULL
                  CHECK (mode IN ('allowed_during', 'blocked_during')),
  created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT profile_app_group_schedule_rules_attachment_fk
    FOREIGN KEY (attachment_id, household_id)
    REFERENCES profile_app_groups (id, household_id) ON DELETE CASCADE,
  CONSTRAINT profile_app_group_schedule_rules_schedule_fk
    FOREIGN KEY (schedule_id, household_id)
    REFERENCES named_schedules (id, household_id) ON DELETE CASCADE,
  -- Also serves the per-profile read, which joins on attachment_id.
  CONSTRAINT uq_profile_app_group_schedule_rules
    UNIQUE (attachment_id, schedule_id, mode)
);

-- "What references this named schedule": the cascade on schedule delete.
CREATE INDEX idx_profile_app_group_schedule_rules_schedule
  ON profile_app_group_schedule_rules (schedule_id);
