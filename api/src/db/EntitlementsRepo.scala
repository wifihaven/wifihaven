package wifihaven.api.db

import doobie.*
import doobie.implicits.*
import wifihaven.shared.types.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.api.metrics.DbMetrics
import zio.*
import zio.interop.catz.*

/**
 * #2134 (multi-tenant P5-4, epic #622, design docs/design/multi-tenant-launch.md §6): a household's
 * resolved entitlements — the caps its subscription's Price/Product grants.
 *
 * The first (and only) entitlement today is [[routerCap]]. It is read PER HOUSEHOLD from the
 * `households.router_cap` column (V66), NOT a global constant — pricing-analysis §7's
 * forward-compat requirement, verbatim: entitlements *"resolve per household from the
 * subscription's Price/Product, not from a global constant."* That keeps the later multi-home tier
 * a pure data change ("add a second Price + raise `router_cap` for households on it"), and the
 * reserved future axes — retention length, alerting (pricing §6: reserved, NOT built now) — plug
 * into this same accessor later. No code special-cases household 1 or founding households; their
 * higher cap is just the column value.
 */
final case class Entitlements(routerCap: Int)

object Entitlements {

  /**
   * The schema default (`households.router_cap DEFAULT 1`, V66) — the most restrictive public plan.
   * Used only defensively when a household row is somehow absent; a real household (FK-referenced
   * by the admin's `users.household_id`) always has a row, so this fallback is never taken in
   * practice.
   */
  val Default: Entitlements = Entitlements(routerCap = 1)
}

trait EntitlementsRepo {

  /** Resolve the caps granted to `household` from its `households` row. */
  def forHousehold(household: HouseholdId): Task[Entitlements]
}

class EntitlementsRepoLive(xa: Transactor[Task]) extends EntitlementsRepo {
  def forHousehold(household: HouseholdId): Task[Entitlements] =
    DbMetrics.timed("entitlements.forHousehold")(
      sql"SELECT router_cap FROM households WHERE id = $household"
        .query[Int]
        .option
        .map(_.fold(Entitlements.Default)(Entitlements(_)))
        .transact(xa),
    )
}
