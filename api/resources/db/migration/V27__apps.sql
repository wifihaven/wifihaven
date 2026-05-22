-- V27__apps.sql
-- #761: schema foundation for the "app" concept — household-scoped named bundles
-- of host patterns + per-profile policy assignments. See #105 design comment §2.
--
-- This is the schema + model PR. CRUD endpoints (#762) and PolicyService
-- snapshot expansion (#763) land in follow-up PRs. The router wire shape is
-- NOT touched here.
--
-- This codebase is single-household (only `household_settings` exists as a
-- single-row config). `apps.slug` is uniquely scoped to that implicit
-- household.

CREATE TABLE apps (
  id           BIGSERIAL PRIMARY KEY,
  name         TEXT NOT NULL,
  slug         TEXT NOT NULL UNIQUE,
  template_id  TEXT,
  icon         TEXT,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE app_hosts (
  app_id  BIGINT NOT NULL REFERENCES apps(id) ON DELETE CASCADE,
  host    TEXT NOT NULL,
  PRIMARY KEY (app_id, host)
);
-- Reverse lookup host -> app(s), used by #763 snapshot builder and group-by-app views.
CREATE INDEX idx_app_hosts_host ON app_hosts(host);

CREATE TABLE app_policy_assignments (
  id                BIGSERIAL PRIMARY KEY,
  app_id            BIGINT NOT NULL REFERENCES apps(id) ON DELETE CASCADE,
  profile_id        BIGINT NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  mode              TEXT NOT NULL CHECK (mode IN ('blocked','allowed','time_limited')),
  daily_minutes     INT CHECK (daily_minutes IS NULL OR daily_minutes > 0),
  exempt_from_daily BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE (app_id, profile_id)
);
-- Per-profile reverse lookup, used by #763 snapshot builder.
CREATE INDEX idx_app_policy_assignments_profile ON app_policy_assignments(profile_id);
