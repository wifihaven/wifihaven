-- V92__weekly_limits_app_groups.sql
-- #2931 (epic #2930, Usage & Screen-Time; design docs/design/weekly-limits-and-app-groups.md §5).
--
-- ── What this adds ───────────────────────────────────────────────────────────
-- 1. `household_settings.week_start_day`: the household-local day a week starts
--    on, ISO numbering (1 = Monday ... 7 = Sunday), default Monday (design D1).
--    A week is seven household-local dates; it begins at `daily_reset_time` on
--    that day in the household's reset time zone.
-- 2. `app_policy_assignments.weekly_minutes`: a weekly limit beside
--    `daily_minutes`. NULL means "no weekly limit", as NULL `daily_minutes`
--    means "no daily limit" (#1627).
-- 3. `app_groups` / `app_group_members`: a household-scoped group is a name
--    and a member list, nothing else. An app is in at most one group per
--    household (D2).
-- 4. `profile_app_groups`: a profile's attachment of a group, carrying that
--    profile's daily and/or weekly limit (at least one, D4) and its "counts
--    toward daily limit" choice (`exempt_from_daily`, default TRUE as on
--    `app_policy_assignments`, D5). Limits live here, on the profile, so the
--    household group is not a policy tier (#1452). On a profile with the group
--    attached, the group covers its members; covered apps need no
--    `app_policy_assignments` row (D6).
-- 5. `app_group_used_daily`: per (profile, group, date) engaged seconds, the
--    group counterpart of `app_used_daily` (V53). A row whose `rolled_through`
--    reaches the end of its date is "settled"; weekly usage reads settled rows
--    for past days (design §6.4).
--
-- Composite foreign keys make a cross-household row unrepresentable: a
-- member's household is its group's, and an attachment's or a usage row's
-- household is both its group's and its profile's. The profile half needs a
-- new `UNIQUE (id, household_id)` on `profiles`. Nothing updates
-- `profiles.household_id`, so the FK never blocks an existing write.
--
-- ── Inert until the adoption PRs ────────────────────────────────────────────
-- Schema only (docs/process/migrations.md#migrations-back-compat). Nothing in
-- image N-1 reads or writes the new tables or columns, so no behaviour changes.
-- The repos, the budget primitive and enforcement land in #2932 and later.
--
-- ── Cost ─────────────────────────────────────────────────────────────────────
-- The two ALTER TABLE ... ADD COLUMN statements add a constant default and a
-- nullable column, which are metadata-only on Postgres 11+. The `profiles`
-- unique constraint builds an index over one row per profile. The two CHECKs
-- validate by scanning `household_settings` (one row per household) and
-- `app_policy_assignments` (bounded by profiles x apps); both are tiny. The new
-- tables start empty. No growth table (`traffic_reports`, `connection_events`,
-- `block_events`, rollups) is scanned, rewritten or re-indexed.
--
-- ── Old image compatibility ─────────────────────────────────────────────────
-- Additive only. N-1's `household_settings` and `app_policy_assignments`
-- INSERTs omit the new columns and get the default / NULL.

ALTER TABLE household_settings
  ADD COLUMN week_start_day SMALLINT NOT NULL DEFAULT 1,
  ADD CONSTRAINT household_settings_week_start_day_check
    CHECK (week_start_day BETWEEN 1 AND 7);

ALTER TABLE profiles
  ADD CONSTRAINT uq_profiles_id_household UNIQUE (id, household_id);

ALTER TABLE app_policy_assignments
  ADD COLUMN weekly_minutes INT NULL,
  ADD CONSTRAINT app_policy_assignments_weekly_minutes_check
    CHECK (weekly_minutes IS NULL OR weekly_minutes > 0);

CREATE TABLE app_groups (
  id           BIGSERIAL   PRIMARY KEY,
  household_id BIGINT      NOT NULL REFERENCES households(id),
  name         TEXT        NOT NULL,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT app_groups_name_nonblank CHECK (btrim(name) <> ''),
  CONSTRAINT uq_app_groups_household_name UNIQUE (household_id, name),
  -- Target of the composite FKs below; `id` alone is already unique.
  CONSTRAINT uq_app_groups_id_household UNIQUE (id, household_id)
);

CREATE TABLE app_group_members (
  group_id     BIGINT NOT NULL,
  household_id BIGINT NOT NULL,
  app_id       BIGINT NOT NULL REFERENCES apps(id) ON DELETE CASCADE,
  PRIMARY KEY (group_id, app_id),
  CONSTRAINT app_group_members_group_fk FOREIGN KEY (group_id, household_id)
    REFERENCES app_groups (id, household_id) ON DELETE CASCADE,
  -- An app is in at most one group per household (D2).
  CONSTRAINT uq_app_group_members_household_app UNIQUE (household_id, app_id)
);

CREATE TABLE profile_app_groups (
  id                BIGSERIAL   PRIMARY KEY,
  household_id      BIGINT      NOT NULL,
  profile_id        BIGINT      NOT NULL,
  group_id          BIGINT      NOT NULL,
  daily_minutes     INT         NULL,
  weekly_minutes    INT         NULL,
  exempt_from_daily BOOLEAN     NOT NULL DEFAULT TRUE,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT profile_app_groups_profile_fk FOREIGN KEY (profile_id, household_id)
    REFERENCES profiles (id, household_id) ON DELETE CASCADE,
  CONSTRAINT profile_app_groups_group_fk FOREIGN KEY (group_id, household_id)
    REFERENCES app_groups (id, household_id) ON DELETE CASCADE,
  CONSTRAINT profile_app_groups_daily_check
    CHECK (daily_minutes IS NULL OR daily_minutes > 0),
  CONSTRAINT profile_app_groups_weekly_check
    CHECK (weekly_minutes IS NULL OR weekly_minutes > 0),
  -- An attachment with no limit would be a group that limits nothing (D4).
  CONSTRAINT profile_app_groups_has_limit
    CHECK (daily_minutes IS NOT NULL OR weekly_minutes IS NOT NULL),
  CONSTRAINT uq_profile_app_groups_profile_group UNIQUE (profile_id, group_id)
);

-- "Which profiles is group G attached to": the membership-edit invalidation
-- and the "Also used by" list. The (profile_id, group_id) unique index serves
-- the per-profile read.
CREATE INDEX idx_profile_app_groups_group ON profile_app_groups (group_id);

CREATE TABLE app_group_used_daily (
  household_id    BIGINT      NOT NULL,
  profile_id      BIGINT      NOT NULL,
  group_id        BIGINT      NOT NULL,
  date            DATE        NOT NULL,
  engaged_seconds BIGINT      NOT NULL,
  rolled_through  TIMESTAMPTZ NOT NULL,
  rolled_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (profile_id, group_id, date),
  CONSTRAINT app_group_used_daily_profile_fk FOREIGN KEY (profile_id, household_id)
    REFERENCES profiles (id, household_id) ON DELETE CASCADE,
  CONSTRAINT app_group_used_daily_group_fk FOREIGN KEY (group_id, household_id)
    REFERENCES app_groups (id, household_id) ON DELETE CASCADE
);

-- Settings-save invalidation and retention sweeps, household-bounded, mirroring
-- idx_app_used_daily_date (V53). The PK serves the per-profile week read.
CREATE INDEX idx_app_group_used_daily_household_date
  ON app_group_used_daily (household_id, date DESC);
-- Membership-edit invalidation deletes one group's rows across profiles.
CREATE INDEX idx_app_group_used_daily_group ON app_group_used_daily (group_id);
