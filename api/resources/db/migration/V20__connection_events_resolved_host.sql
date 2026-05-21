-- V20__connection_events_resolved_host.sql
-- Defense-in-depth FQDN backfill (#720, follow-up to #583 / PR #650).
--
-- PR #650 closed the agent-side timing race that caused some
-- connection_attempt events to arrive with host_type='ipv4' even though the
-- agent's DNS-tail cache would have resolved the IP if the conntrack loop
-- had retried. This column lets the API back-fill an FQDN attribution from
-- *other* fqdn-typed events that share (router_id, dest_ip) within a small
-- window, independent of any agent guarantees, so the router can stay
-- frozen through Gate 2 (#654) without losing dashboard fidelity.
--
-- Design notes:
--   * Nullable. Set only when host_type ∈ ('ipv4','ipv6') AND we observed
--     a sibling fqdn-typed event for the same (router_id, dest_ip).
--   * Implicit host_type='fqdn' when populated — no separate discriminator
--     column needed.
--   * Original host_type / host_value remain immutable so event integrity
--     and idempotent replay (#338) keep working unchanged.
--   * Index supports the lookup the ingest path performs on every batch.

ALTER TABLE connection_events
  ADD COLUMN resolved_host_value TEXT;

CREATE INDEX idx_conn_events_router_dest_ip_ts
  ON connection_events(router_id, dest_ip, ts DESC)
  WHERE dest_ip IS NOT NULL;
