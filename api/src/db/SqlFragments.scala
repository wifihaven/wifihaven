package wifihaven.api.db

import doobie.*
import doobie.implicits.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.shared.types.HouseholdId

// Shared SQL fragments used across repos to keep duplicated read-side joins
// in one place (#1532 SSOT audit, #1741).
object SqlFragments {

  // #2107 (multi-tenant, epic #622): the per-household tenancy predicate. Every household-scoped
  // read AND-composes this so it sees only its own tenant's rows. `column` lets callers qualify the
  // predicate when the query joins another table (e.g. `d.household_id` when `devices` is aliased
  // `d`); it defaults to the bare `household_id`. The V65 indexes
  // (`idx_{profiles,devices,routers,users}_household`, and the leading column of the composite
  // unique constraints) keep this predicate index-backed. Reused by the broader user-facing read
  // sweep in sub-issue E (#2108).
  def householdEq(hh: HouseholdId, column: String = "household_id"): Fragment =
    Fragment.const(column) ++ fr"= $hh"

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
