-- #586: create a read-only Postgres role for ad-hoc debugging.
--
-- wifihaven_ro is created with LOGIN so Render's external connection string
-- can reference it directly. No password is set here — the operator sets the
-- password out-of-band via Render's PG shell (see docs/render-readonly-role.md).
--
-- USAGE on schema public + SELECT on all current tables is granted explicitly.
-- ALTER DEFAULT PRIVILEGES ensures tables added by future migrations are also
-- readable without a new grant.
--
-- This migration is idempotent via IF NOT EXISTS; re-running on a DB that
-- already has the role is safe.

DO $$
BEGIN
  IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'wifihaven_ro') THEN
    CREATE ROLE wifihaven_ro LOGIN;
  END IF;
END$$;

GRANT USAGE ON SCHEMA public TO wifihaven_ro;
GRANT SELECT ON ALL TABLES IN SCHEMA public TO wifihaven_ro;

-- Future tables created by any role in the DB are also readable.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT ON TABLES TO wifihaven_ro;
