-- #311: per-profile failover mode. When the OpenWRT agent loses contact
-- with the API for >5 minutes:
--   'closed' → drop all forwarded traffic for the profile's devices
--              (fail-safe; recommended for children)
--   'open'   → keep enforcing the cached snapshot exactly as-is
--              (recommended for adults; avoids ISP-blip lockouts)
--
-- Default 'closed' is pessimistic — existing rows acquire fail-closed on
-- migration. The API layer keeps the same default at profile creation; the
-- web UI surfaces the override so admins can opt adult profiles into Open.
ALTER TABLE profiles
  ADD COLUMN failure_mode TEXT NOT NULL DEFAULT 'closed'
  CHECK (failure_mode IN ('open', 'closed'));
