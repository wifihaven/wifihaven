package wifihaven.api.usage

import wifihaven.api.db.PresenceSpans
import wifihaven.shared.types.MacAddress

/**
 * #2708: which devices a traffic read covers.
 *
 * The traffic read paths used to carry this as a bare `List[MacAddress]`, where `Nil` had to mean
 * BOTH "no filter was supplied, so read everything" AND "a filter was supplied and it selected no
 * devices, so read nothing". Those are opposite instructions, and every caller had to disambiguate
 * them out-of-band by re-inspecting the raw request (`if (macs.isEmpty && (macsRaw.nonEmpty ||
 * profileIds.nonEmpty))` in `UsageRoutes`, `parsed.filterRequested && resolvedMacs.isEmpty` in
 * `SpaPush`). A household with zero devices hits neither guard — its no-filter read legitimately
 * resolves to `Nil` — which is how the unscoped rollup read in #2708 widened to every tenant.
 *
 * Making the two cases distinct constructors removes the ambiguity at its source: the collapse is
 * no longer representable, and [[fold]] is the only way to reach a mac list, so a caller cannot
 * pass [[NoDevices]] to a repo as an unfiltered read even by accident.
 *
 * This is the SEMANTIC half of the #2708 fix. The other half is that "read everything" is now
 * bounded by a mandatory `HouseholdId` on every rollup read (as the raw tier has been since #2313),
 * so even [[AllInHousehold]] cannot cross a tenant boundary.
 */
enum MacScope {

  /** No mac/profile filter was supplied — every device in the caller's household. */
  case AllInHousehold

  /**
   * A filter was supplied and selected these devices. Non-empty by construction.
   *
   * #2844: `spans` is `Some` when the filter named profiles. It then carries those profiles'
   * [[wifihaven.api.db.AttributionScope]] spans over the read's window, and a row is kept only when
   * it falls in one of them, so a device moved between profiles contributes to each only its
   * in-interval traffic. `None` is a device-only filter (`?mac=` without `?profileId=`): every row
   * of those devices.
   */
  case Only(macs: ::[MacAddress], spans: Option[PresenceSpans])

  /** A filter WAS supplied but selected no devices — the result is empty without querying. */
  case NoDevices

  /**
   * The only way to get a mac list out of a scope: `ifNothing` is the result when the filter
   * selected nothing, `ifRead` receives the repo-level mac filter (`Nil` == "all in household",
   * which every rollup/raw read bounds with its `HouseholdId`) and the #2844 span restriction
   * (`None` == every row of those macs).
   */
  def fold[A](ifNothing: => A)(ifRead: (List[MacAddress], Option[PresenceSpans]) => A): A =
    this match {
      case AllInHousehold    => ifRead(Nil, None)
      case Only(macs, spans) => ifRead(macs, spans)
      case NoDevices         => ifNothing
    }
}

object MacScope {

  /**
   * Build the scope for a filter that WAS supplied, from the devices it resolved to. An empty
   * `resolved` is [[NoDevices]] — never a widening.
   */
  def filtered(resolved: List[MacAddress]): MacScope = filtered(resolved, None)

  /** [[filtered]] for a profile filter, restricted to the profiles' `spans` (#2844). */
  def filtered(resolved: List[MacAddress], spans: Option[PresenceSpans]): MacScope =
    resolved match {
      case Nil    => MacScope.NoDevices
      case h :: t => MacScope.Only(::(h, t), spans)
    }
}
