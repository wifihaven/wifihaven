-- #761 / #105: app concept — household-scoped named bundles of host patterns
-- (FQDN apex; subdomains implicit at dnsmasq match time). Authoring/UX layer
-- only; the policy snapshot still ships flat per-MAC extraBlocked /
-- extraAllowed lists (#105 §0).
--
-- Single-tenant deployment today, so no households table / household_id yet.
-- slug is globally unique; if multi-tenancy ever lands the unique key moves
-- to (household_id, slug).

CREATE TABLE apps (
  id          BIGSERIAL PRIMARY KEY,
  name        TEXT NOT NULL,
  slug        TEXT NOT NULL UNIQUE,
  template_id TEXT,
  icon        TEXT,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE app_hosts (
  app_id BIGINT NOT NULL REFERENCES apps(id) ON DELETE CASCADE,
  host   TEXT   NOT NULL,
  PRIMARY KEY (app_id, host)
);

CREATE TABLE app_policy_assignments (
  id                BIGSERIAL PRIMARY KEY,
  app_id            BIGINT  NOT NULL REFERENCES apps(id)     ON DELETE CASCADE,
  profile_id        BIGINT  NOT NULL REFERENCES profiles(id) ON DELETE CASCADE,
  mode              TEXT    NOT NULL CHECK (mode IN ('blocked','allowed','time_limited')),
  daily_minutes     INT,
  exempt_from_daily BOOLEAN NOT NULL DEFAULT TRUE,
  UNIQUE (app_id, profile_id)
);

CREATE INDEX idx_app_policy_assignments_profile ON app_policy_assignments(profile_id);
