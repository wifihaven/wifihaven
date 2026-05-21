-- V21__time_usage_resolved_fqdn_index.sql
-- Stop-gap read-side FQDN join for time_usage / traffic_reports (#731).
--
-- PR #728 added resolved_host_value to connection_events so the connection
-- log surfaces resolved FQDNs for race-loser ipv4 events. The 5-minute
-- traffic rollup (time_usage / traffic_reports) has no dest_ip column, so
-- the same backfill cannot be applied at ingest time (#730 will fix that
-- properly once the router thaws after Gate 2 / #654).
--
-- This migration adds a partial index to make the read-time LATERAL join
-- efficient: the SELECT paths in TimeUsageRepoLive and TrafficReportRepoLive
-- LEFT JOIN connection_events on (mac, dest_ip) to look up a resolved
-- hostname for ipv4-typed rows. The partial index (WHERE resolved_host_value
-- IS NOT NULL) keeps the scan tight — only rows that actually carry a
-- resolution are indexed, which is a small fraction of the table.
--
-- TODO(#730): remove this index when the read-side join is deleted.

CREATE INDEX idx_conn_events_mac_dest_resolved
  ON connection_events(mac, dest_ip)
  WHERE resolved_host_value IS NOT NULL;
