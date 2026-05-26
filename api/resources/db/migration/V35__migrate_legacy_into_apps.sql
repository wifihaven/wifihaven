-- V35__migrate_legacy_into_apps.sql
-- #764: backfill legacy profiles.extra_blocked / profiles.extra_allowed /
-- site_time_limits into the apps schema (#761/#762/#763), then drop the legacy
-- columns and table.
--
-- Wire-shape continuity is provided by #763, which already expands app
-- assignments into the same per-profile extraBlocked / extraAllowed /
-- siteTimeLimits snapshot buckets the legacy fields used to feed. Once this
-- migration runs the router-facing snapshot is byte-identical for any
-- household whose policy lived entirely in the legacy fields.
--
-- This is single-household — `apps.slug` is uniquely scoped to the implicit
-- household, so we key on the host string itself (apex form, leading "*."
-- stripped).

-- ── 1. site_time_limits → time_limited apps ──────────────────────────────────

INSERT INTO apps (name, slug, template_id, icon)
SELECT DISTINCT
  CASE WHEN domain_pattern LIKE '*.%' THEN substr(domain_pattern, 3) ELSE domain_pattern END,
  CASE WHEN domain_pattern LIKE '*.%' THEN substr(domain_pattern, 3) ELSE domain_pattern END,
  NULL, NULL
FROM site_time_limits
ON CONFLICT (slug) DO NOTHING;

INSERT INTO app_hosts (app_id, host)
SELECT DISTINCT a.id, a.slug
FROM apps a
WHERE a.slug IN (
  SELECT
    CASE WHEN domain_pattern LIKE '*.%' THEN substr(domain_pattern, 3) ELSE domain_pattern END
  FROM site_time_limits
)
ON CONFLICT DO NOTHING;

INSERT INTO app_policy_assignments (app_id, profile_id, mode, daily_minutes, exempt_from_daily)
SELECT
  a.id,
  stl.profile_id,
  'time_limited',
  stl.daily_minutes,
  stl.exempt_from_daily
FROM site_time_limits stl
JOIN apps a ON a.slug = CASE
  WHEN stl.domain_pattern LIKE '*.%' THEN substr(stl.domain_pattern, 3)
  ELSE stl.domain_pattern
END
ON CONFLICT (app_id, profile_id) DO NOTHING;

-- ── 2. profiles.extra_blocked → blocked apps ─────────────────────────────────

INSERT INTO apps (name, slug, template_id, icon)
SELECT DISTINCT
  CASE WHEN entry LIKE '*.%' THEN substr(entry, 3) ELSE entry END,
  CASE WHEN entry LIKE '*.%' THEN substr(entry, 3) ELSE entry END,
  NULL, NULL
FROM profiles p, unnest(p.extra_blocked) AS entry
ON CONFLICT (slug) DO NOTHING;

INSERT INTO app_hosts (app_id, host)
SELECT DISTINCT a.id, a.slug
FROM apps a
WHERE a.slug IN (
  SELECT
    CASE WHEN entry LIKE '*.%' THEN substr(entry, 3) ELSE entry END
  FROM profiles p, unnest(p.extra_blocked) AS entry
)
ON CONFLICT DO NOTHING;

INSERT INTO app_policy_assignments (app_id, profile_id, mode, daily_minutes, exempt_from_daily)
SELECT
  a.id,
  p.id,
  'blocked',
  NULL,
  TRUE
FROM profiles p
CROSS JOIN LATERAL unnest(p.extra_blocked) AS entry
JOIN apps a ON a.slug = CASE
  WHEN entry LIKE '*.%' THEN substr(entry, 3) ELSE entry
END
ON CONFLICT (app_id, profile_id) DO NOTHING;

-- ── 3. profiles.extra_allowed → allowed apps ─────────────────────────────────

INSERT INTO apps (name, slug, template_id, icon)
SELECT DISTINCT
  CASE WHEN entry LIKE '*.%' THEN substr(entry, 3) ELSE entry END,
  CASE WHEN entry LIKE '*.%' THEN substr(entry, 3) ELSE entry END,
  NULL, NULL
FROM profiles p, unnest(p.extra_allowed) AS entry
ON CONFLICT (slug) DO NOTHING;

INSERT INTO app_hosts (app_id, host)
SELECT DISTINCT a.id, a.slug
FROM apps a
WHERE a.slug IN (
  SELECT
    CASE WHEN entry LIKE '*.%' THEN substr(entry, 3) ELSE entry END
  FROM profiles p, unnest(p.extra_allowed) AS entry
)
ON CONFLICT DO NOTHING;

-- A host present in both extra_blocked and extra_allowed for the same profile
-- can't be expressed as two separate assignments (UNIQUE (app_id, profile_id)).
-- Allowed wins — the wire's `extraAllowed` already beats `extraBlocked` at the
-- router (feedback_extraallowed_beats_blocked / #421). Upsert mode to 'allowed'
-- if a blocked assignment was already written in step 2.
INSERT INTO app_policy_assignments (app_id, profile_id, mode, daily_minutes, exempt_from_daily)
SELECT
  a.id,
  p.id,
  'allowed',
  NULL,
  TRUE
FROM profiles p
CROSS JOIN LATERAL unnest(p.extra_allowed) AS entry
JOIN apps a ON a.slug = CASE
  WHEN entry LIKE '*.%' THEN substr(entry, 3) ELSE entry
END
ON CONFLICT (app_id, profile_id) DO UPDATE SET
  mode = 'allowed',
  daily_minutes = NULL,
  exempt_from_daily = TRUE;

-- ── 4. Drop legacy storage ──────────────────────────────────────────────────

DROP TABLE site_time_limits;

ALTER TABLE profiles
  DROP COLUMN extra_blocked,
  DROP COLUMN extra_allowed;
