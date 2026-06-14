-- V58__widen_host_type_label.sql
-- Widen the `host_type` CHECK constraint on every table that carries a
-- `HostId` discriminator so the new `label` variant (#1708) can be
-- inserted. This is the prerequisite schema PR for the OpenWRT agent's
-- switch from `fqdn`-typed static-IP-range labels to a distinct
-- `label`-typed HostId.
--
-- Tables touched (constraint definitions originally in V13; redefined on
-- the partitioned parents in V41/V42):
--   * time_usage             (V13)
--   * block_events           (V13)
--   * traffic_reports        (V41, partitioned parent)
--   * connection_events      (V42, partitioned parent)
--
-- Why NOT VALID + (no VALIDATE):
--
-- The new value set is a strict superset of the old, so every existing
-- row is already valid by construction. A plain ADD CONSTRAINT would
-- take AccessExclusiveLock and full-scan each partition to re-prove
-- something we already know — at prod scale (the unbounded-growth tables
-- `traffic_reports` and `connection_events`) that scan can run past the
-- 15-minute Render port-bind timeout (see AGENTS.md
-- migrations-prod-data-volume; cf. #1197). ADD CONSTRAINT ... NOT VALID
-- is an O(1) catalog change that takes a brief lock and starts enforcing
-- on new INSERTs/UPDATEs immediately, which is the only thing we need.
--
-- Existing data is provably valid (subset relation), so VALIDATE
-- CONSTRAINT is left for a later operator-applied step if desired; it is
-- not required for correctness.

ALTER TABLE time_usage
  DROP CONSTRAINT time_usage_host_type_check;
ALTER TABLE time_usage
  ADD CONSTRAINT time_usage_host_type_check
  CHECK (host_type IN ('fqdn','ipv4','ipv6','label')) NOT VALID;

ALTER TABLE block_events
  DROP CONSTRAINT block_events_host_type_check;
ALTER TABLE block_events
  ADD CONSTRAINT block_events_host_type_check
  CHECK (host_type IN ('fqdn','ipv4','ipv6','label')) NOT VALID;

ALTER TABLE traffic_reports
  DROP CONSTRAINT traffic_reports_host_type_check;
ALTER TABLE traffic_reports
  ADD CONSTRAINT traffic_reports_host_type_check
  CHECK (host_type IN ('fqdn','ipv4','ipv6','label')) NOT VALID;

ALTER TABLE connection_events
  DROP CONSTRAINT connection_events_host_type_check;
ALTER TABLE connection_events
  ADD CONSTRAINT connection_events_host_type_check
  CHECK (host_type IN ('fqdn','ipv4','ipv6','label')) NOT VALID;
