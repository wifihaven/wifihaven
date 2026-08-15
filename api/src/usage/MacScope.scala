package wifihaven.api.usage

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

  /** A filter was supplied and selected these devices. Non-empty by construction. */
  case Only(macs: ::[MacAddress])

  /** A filter WAS supplied but selected no devices — the result is empty without querying. */
  case NoDevices

  /**
   * The only way to get a mac list out of a scope: `ifNothing` is the result when the filter
   * selected nothing, `ifRead` receives the repo-level mac filter (`Nil` == "all in household",
   * which every rollup/raw read bounds with its `HouseholdId`).
   */
  def fold[A](ifNothing: => A)(ifRead: List[MacAddress] => A): A = this match {
    case AllInHousehold => ifRead(Nil)
    case Only(macs)     => ifRead(macs)
    case NoDevices      => ifNothing
  }
}

object MacScope {

  /**
   * Build the scope for a filter that WAS supplied, from the devices it resolved to. An empty
   * `resolved` is [[NoDevices]] — never a widening.
   */
  def filtered(resolved: List[MacAddress]): MacScope = resolved match {
    case Nil    => MacScope.NoDevices
    case h :: t => MacScope.Only(::(h, t))
  }
}
