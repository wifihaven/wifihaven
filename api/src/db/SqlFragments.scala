package wifihaven.api.db

import doobie.*
import doobie.implicits.*

// Shared SQL fragments used across repos to keep duplicated read-side joins
// in one place (#1532 SSOT audit, #1741).
object SqlFragments {

  // Promotes ipv4/ipv6-typed `traffic_reports` rows to their resolved fqdn by
  // looking up the most recent `connection_events` row for the same
  // (mac, dest_ip) that has a resolved_host_value, within the row's own day.
  // The partial index added in V22 (idx_conn_events_mac_dest_resolved) —
  // tightened in V34 after the #1240 / #1254 prod outage — is what keeps the
  // join cheap; any change to this join's columns or bounds must keep that
  // index covering it. Expects the outer query to alias `traffic_reports`
  // as `tr`; binds the result as `ce`.
  // TODO(#730): remove once usage records carry dest_ip directly and the
  // read-side resolve is no longer needed.
  val resolvedHostLateral: Fragment =
    fr"""LEFT JOIN LATERAL (
           SELECT resolved_host_value
           FROM connection_events
           WHERE mac          = tr.mac
             AND dest_ip      = tr.host_value
             AND resolved_host_value IS NOT NULL
             AND ts >= tr.date::TIMESTAMPTZ
             AND ts <  (tr.date + INTERVAL '1 day')::TIMESTAMPTZ
           ORDER BY ts DESC LIMIT 1
         ) ce ON tr.host_type IN ('ipv4','ipv6')"""
}
