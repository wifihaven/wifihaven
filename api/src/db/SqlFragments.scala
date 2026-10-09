package wifihaven.api.db

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.shared.types.HouseholdId

import java.time.{Instant, LocalDate, ZoneOffset}

// Shared SQL fragments used across repos to keep duplicated read-side joins
// in one place (#1532 SSOT audit, #1741).
object SqlFragments {

  // #2872: the one `date_bin` origin for every connection-event bucket — the `/series` window, its
  // rollup `last_seen`, and the hourly rollup writer — so stored and on-the-fly buckets line up.
  // It is a `timestamptz` literal with an explicit `+00`: a bare `TIMESTAMP '2000-01-01 00:00:00'`
  // is converted in the SESSION time zone, which put daily buckets at 07:00Z under America/Denver
  // and hourly buckets at :30 under a half-hour zone.
  val BucketOrigin: Fragment = Fragment.const("TIMESTAMPTZ '2000-01-01 00:00:00+00'")

  // #2107 (multi-tenant, epic #622): the per-household tenancy predicate. Every household-scoped
  // read AND-composes this so it sees only its own tenant's rows. `column` lets callers qualify the
  // predicate when the query joins another table (e.g. `d.household_id` when `devices` is aliased
  // `d`); it defaults to the bare `household_id`. The V65 indexes
  // (`idx_{profiles,devices,routers,users}_household`, and the leading column of the composite
  // unique constraints) keep this predicate index-backed. Reused by the broader user-facing read
  // sweep in sub-issue E (#2108).
  //
  // `column` is spliced verbatim via `Fragment.const` (NOT a bound parameter), so it MUST be a
  // trusted compile-time literal — never user input — or it is a SQL-injection vector. Only `hh` is
  // parameterized. All current callers pass string constants.
  def householdEq(hh: HouseholdId, column: String = "household_id"): Fragment =
    Fragment.const(column) ++ fr"= $hh"

  // #2313 (multi-tenant, epic #622): the per-household tenancy predicate for `router_id`-keyed
  // growth tables (`traffic_reports`). Those tables carry no `household_id` of their own, so the
  // scope is TRANSITIVE via `routers.household_id` — mirroring the `connection_events` scope
  // (`ConnectionEventRepoLive.hhRouterScope`, #2282). The `traffic_reports` presence/usage reads
  // that feed `TimeStatusService` (the daily-limit / screen-time enforcement read path) AND-compose
  // this so a profile's used-minutes can never be inflated by ANOTHER household's traffic on the
  // SAME MAC (representable once V74 dropped the global `devices_mac_key`). Emits the leading `AND`.
  //
  // `column` qualifies `router_id` when the query aliases `traffic_reports` (e.g. `tr.router_id`);
  // it is spliced verbatim via `Fragment.const` — a trusted compile-time literal, NEVER user input
  // — exactly like [[householdEq]]'s `column`. Only `hh` is parameterized. Index-backed by
  // idx_routers_household (V65) on the subquery and idx_traffic_reports_router (V41) on the outer
  // filter; for a single-household install the subquery returns that install's routers and the
  // predicate is a no-op that drops none of its own rows.
  def householdRouterScope(hh: HouseholdId, column: String = "router_id"): Fragment =
    fr"AND" ++ Fragment.const(column) ++ fr"IN (SELECT id FROM routers WHERE" ++
      householdEq(hh) ++ fr")"

  // #2314 (same-MAC-across-households, epic #622): the optional, AND-composed household predicate
  // for connection_events reads. connection_events are `router_id`-keyed, so household scope is
  // transitive through the already-joined `routers` table — callers pass `column = "r.household_id"`.
  // Returns an empty fragment when the caller omits `household` — an UNSCOPED read across every
  // household, which survives as a back-compat default and has no live caller (see `LogFilter`).
  // Both `querySeries` and `querySeriesRollup` compose this so the tenancy predicate lives in
  // exactly one place (SSOT) rather than being hand-copied. Same `column`-is-trusted-literal /
  // `hh`-is-parameterized contract as `householdEq`. #2609: this scopes the ROW SET; the LABELS on
  // those rows are a separate predicate, [[deviceLabelJoin]] below.
  def householdFilter(household: Option[HouseholdId], column: String): Fragment =
    household.fold(Fragment.empty)(hh => fr"AND" ++ householdEq(hh, column))

  // #2609 (same-MAC-across-households, epic #622): the device+profile LABEL join for the
  // `connection_events` read paths. Every one of them used to join `LEFT JOIN devices d ON d.mac =
  // <mac>` with no household predicate, which was only ever safe because V1's global
  // `devices_mac_key` made `devices.mac` unique. V74 (#2277) dropped that, so post-V74 a bare-MAC
  // join is not "missing a filter" — it is SEMANTICALLY UNDEFINED: two households can each own a
  // device row for the same (randomized) MAC and there is no single correct row to resolve to
  // without naming the household. The unqualified join leaked household B's `d.name` / `p.name`
  // into household A's rows AND fanned one event into one output row per matching device.
  //
  // The predicate is `d.household_id = <routerAlias>.household_id`, NOT `= $callerHousehold`:
  //   - it derives tenancy from the EVENT's OWN row (`connection_events.router_id` V42 and
  //     `connection_events_{hourly,daily}.router_id` V47 are all `NOT NULL` with an FK to `routers`,
  //     and `routers.household_id` is `NOT NULL` V65/V66, so the joined `r` always exists and always
  //     carries a household). It therefore does not depend on `LogFilter.household` being set. All
  //     three live constructors DO set it (`Routes.scala` /api/logs + /series from `claims.hh`,
  //     `SpaPush.pushConnectionEvents` per subscribed household since #2636) — the `None` default
  //     has no live caller today, and this form is what keeps the LABELS scoped if one reappears,
  //     since a caller-derived predicate would simply be absent there;
  //   - it never accepts a household from the client: the value is a column of a row the query
  //     already reached through `router_id`, which is itself pinned to the authenticated router.
  // It composes with, and does not replace, [[householdFilter]] — that scopes the ROW SET, this
  // scopes the LABELS. Index-backed by V65's `uq_devices_household_mac` UNIQUE(household_id, mac),
  // whose leading column is exactly this predicate, so the join stays a single index lookup.
  //
  // #2845 (epic #2841, design `docs/design/shared-devices.md` §6.3): the PROFILE label is the
  // profile that held the device at `tsExpr` (the event's timestamp, or a rollup bucket's start),
  // read from `device_profile_assignments` (V90), not `devices.profile_id`. The current profile
  // would re-label a device's whole history every time it moves (or a shared device is checked in).
  // `started_at IS NULL` is V90's open-ended start (every backfilled row), so the lower bound is
  // `(started_at IS NULL OR ts >= started_at)`: a bare `ts >= started_at` is NULL for those rows
  // and would drop the label from almost every device's history. Intervals are half-open
  // [started_at, ended_at) and one device's rows never overlap (`DeviceAssignment.assign` closes the
  // open row at the instant it opens the next), so this joins at most one row per event. Index-
  // backed by `idx_dpa_device_started` (V90). The profile column every read path selects and
  // filters on is [[labelProfileId]].
  //
  // `macColumn` and `routerAlias` are spliced verbatim via `Fragment.const` — trusted compile-time
  // literals, NEVER user input, exactly like [[householdEq]]'s `column`; `tsExpr` is a caller-built
  // fragment over the source table's columns. The emitted fragment requires `routerAlias` to be
  // joined BEFORE `devices` in the FROM clause: Postgres only resolves an ON clause against tables
  // already introduced to its left.
  def deviceLabelJoin(macColumn: String, tsExpr: Fragment, routerAlias: String = "r"): Fragment =
    fr"LEFT JOIN devices d ON d.mac =" ++ Fragment.const(macColumn) ++
      fr"AND d.household_id =" ++ Fragment.const(s"$routerAlias.household_id") ++
      fr"LEFT JOIN device_profile_assignments dpa ON dpa.device_id = d.id" ++
      fr"AND (dpa.started_at IS NULL OR" ++ tsExpr ++ fr">= dpa.started_at)" ++
      fr"AND (dpa.ended_at IS NULL OR" ++ tsExpr ++ fr"< dpa.ended_at)" ++
      fr"LEFT JOIN profiles p ON p.id = dpa.profile_id"

  // #2845: the event-time profile id from [[deviceLabelJoin]]. Every `connection_events` read path
  // selects and filters on this, so the label and the filter can never come from different rows.
  val labelProfileId: Fragment = fr"dpa.profile_id"

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

  // #2844 (design `docs/design/shared-devices.md` §6.1): restrict a usage read to the device
  // intervals of an attribution scope — a row is kept when one of its MAC's spans covers its
  // timestamp, half-open `[from, until)`, `None` meaning unbounded. Composes AFTER the read's own
  // `mac IN (...)` predicate, which stays the index condition; this is a row filter on top of it.
  //
  // `[windowStart, windowEnd)` must contain every row the read can return (the caller passes the
  // window its own WHERE clause bounds `tsColumn` to). A span bound at or beyond that window cannot
  // exclude any of those rows, so it is elided here, where the window is known exactly. A device
  // therefore gets a time term only when one of its assignment changes falls inside the read's
  // window; every other device rides one `mac IN (...)` disjunct, and when none has a change in the
  // window the fragment is empty and the read is the pre-#2844 query. `macColumn` / `tsColumn` are
  // trusted compile-time literals spliced via `Fragment.const`, like [[householdEq]]'s `column`.
  def spanFilter(
      spans: PresenceSpans,
      macColumn: String,
      tsColumn: String,
      windowStart: Instant,
      windowEnd: Instant,
  ): Fragment = {
    val within = spans.within(windowStart, windowEnd)
    if (within.unbounded) Fragment.empty
    else {
      val mac             = Fragment.const(macColumn)
      val ts              = Fragment.const(tsColumn)
      val (open, bounded) = within.spans.partition(_.unbounded)
      val openTerm        = cats.data.NonEmptyList
        .fromList(open.map(_.mac.value).distinct)
        .map(nel => fr"(" ++ Fragments.in(mac, nel) ++ fr")")
      val boundedTerms    = bounded.map { s =>
        fr"(" ++ mac ++ fr"= ${s.mac}" ++
          s.from.fold(Fragment.empty)(f => fr"AND" ++ ts ++ fr">= $f") ++
          s.until.fold(Fragment.empty)(u => fr"AND" ++ ts ++ fr"< $u") ++ fr")"
      }
      fr"AND (" ++ (openTerm.toList ++ boundedTerms).reduce(_ ++ fr"OR" ++ _) ++ fr")"
    }
  }

  // #2844: an instant window containing every `traffic_reports` row whose household-local `date` is
  // in `from`..`to`, whatever the household's `daily_reset_tz` (UTC-12..UTC+14) and
  // `daily_reset_time` (a day starting as late as 23:59): such a row's `period_start` is within
  // `[from 00:00 UTC - 1 day, to 00:00 UTC + 3 days)`. Used as `spanFilter`'s window for the
  // date-keyed presence reads, which do not know the household's settings.
  def dateReadWindow(from: LocalDate, to: LocalDate): (Instant, Instant) =
    (
      from.minusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant,
      to.plusDays(3).atStartOfDay(ZoneOffset.UTC).toInstant,
    )
}
