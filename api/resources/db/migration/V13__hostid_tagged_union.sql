-- V12__hostid_tagged_union.sql
-- Promote the wire-contract `hostname` field from an unconstrained string to
-- a tagged union of FQDN | IPv4 | IPv6 (see docs/architecture.md §6.2, §7.2;
-- fixes #391). Storage now carries `host_type` alongside `host_value` on
-- every table that previously held a hostname-or-IP-literal string:
--
--   * time_usage          (was: `domain`     TEXT)
--   * connection_events   (was: `hostname`   TEXT)
--   * block_events        (was: `hostname`   TEXT)
--   * traffic_reports     (was: `hostname`   TEXT)
--
-- The column is renamed to `host_value`; `host_type` is a discriminator in
-- {'fqdn','ipv4','ipv6'}. Backfill uses a simple regex heuristic that errs
-- on FQDN when ambiguous — this is acceptable because pre-deployment there
-- is no production data we must preserve perfectly (the DB can be wiped if
-- the heuristic mis-classifies anything that matters).

-- ── time_usage ──────────────────────────────────────────────────────────
ALTER TABLE time_usage RENAME COLUMN domain TO host_value;
ALTER TABLE time_usage ADD COLUMN host_type TEXT;
UPDATE time_usage SET host_type = CASE
  WHEN host_value ~ '^[0-9]{1,3}(\.[0-9]{1,3}){3}$' THEN 'ipv4'
  WHEN host_value ~ ':' THEN 'ipv6'
  ELSE 'fqdn'
END;
ALTER TABLE time_usage ALTER COLUMN host_type SET NOT NULL;
ALTER TABLE time_usage
  ADD CONSTRAINT time_usage_host_type_check
  CHECK (host_type IN ('fqdn','ipv4','ipv6'));
ALTER TABLE time_usage DROP CONSTRAINT time_usage_device_mac_domain_date_key;
ALTER TABLE time_usage
  ADD CONSTRAINT time_usage_device_mac_host_date_key
  UNIQUE (device_mac, host_type, host_value, date);
DROP INDEX idx_time_usage_domain;
CREATE INDEX idx_time_usage_host ON time_usage(host_type, host_value);

-- ── connection_events ───────────────────────────────────────────────────
ALTER TABLE connection_events RENAME COLUMN hostname TO host_value;
ALTER TABLE connection_events ADD COLUMN host_type TEXT;
UPDATE connection_events SET host_type = CASE
  WHEN host_value ~ '^[0-9]{1,3}(\.[0-9]{1,3}){3}$' THEN 'ipv4'
  WHEN host_value ~ ':' THEN 'ipv6'
  ELSE 'fqdn'
END;
ALTER TABLE connection_events ALTER COLUMN host_type SET NOT NULL;
ALTER TABLE connection_events
  ADD CONSTRAINT connection_events_host_type_check
  CHECK (host_type IN ('fqdn','ipv4','ipv6'));

-- ── block_events ────────────────────────────────────────────────────────
ALTER TABLE block_events RENAME COLUMN hostname TO host_value;
ALTER TABLE block_events ADD COLUMN host_type TEXT;
UPDATE block_events SET host_type = CASE
  WHEN host_value ~ '^[0-9]{1,3}(\.[0-9]{1,3}){3}$' THEN 'ipv4'
  WHEN host_value ~ ':' THEN 'ipv6'
  ELSE 'fqdn'
END;
ALTER TABLE block_events ALTER COLUMN host_type SET NOT NULL;
ALTER TABLE block_events
  ADD CONSTRAINT block_events_host_type_check
  CHECK (host_type IN ('fqdn','ipv4','ipv6'));

-- ── traffic_reports ─────────────────────────────────────────────────────
ALTER TABLE traffic_reports RENAME COLUMN hostname TO host_value;
ALTER TABLE traffic_reports ADD COLUMN host_type TEXT;
UPDATE traffic_reports SET host_type = CASE
  WHEN host_value ~ '^[0-9]{1,3}(\.[0-9]{1,3}){3}$' THEN 'ipv4'
  WHEN host_value ~ ':' THEN 'ipv6'
  ELSE 'fqdn'
END;
ALTER TABLE traffic_reports ALTER COLUMN host_type SET NOT NULL;
ALTER TABLE traffic_reports
  ADD CONSTRAINT traffic_reports_host_type_check
  CHECK (host_type IN ('fqdn','ipv4','ipv6'));
ALTER TABLE traffic_reports
  DROP CONSTRAINT traffic_reports_router_id_period_start_mac_hostname_key;
ALTER TABLE traffic_reports
  ADD CONSTRAINT traffic_reports_router_id_period_start_mac_host_key
  UNIQUE (router_id, period_start, mac, host_type, host_value);
