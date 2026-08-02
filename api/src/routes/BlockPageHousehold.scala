package wifihaven.api.routes

import wifihaven.api.auth.BlockPageToken
import wifihaven.api.db.RouterRepo
import wifihaven.api.metrics.AppMetrics
import wifihaven.shared.types.{HouseholdId, RouterId}
import zio.*

/**
 * #2566 / #2569 / #2322 — the ONE place the unauthenticated block-page endpoints resolve a
 * household.
 *
 * `GET /api/blocked` and `POST /api/access-requests` are two halves of the same page, so they must
 * derive the household identically or the page renders one household's decision and files the
 * child's request against another. Both call [[resolve]]; neither reimplements it (AGENTS.md
 * §single-source-of-truth).
 *
 * ==The fallback is deliberate — and the two endpoints fall back DIFFERENTLY==
 * An absent or unverifiable `bpt` must keep working: the API and the OpenWRT agent deploy
 * independently, so the API cannot hard-require a parameter the fleet does not send yet.
 *
 * What "keep working" means is NOT the same on both routes, because they did not do the same thing
 * before:
 *   - `GET /api/blocked` resolved every read against [[HouseholdId.Default]] unconditionally, so
 *     falling back to `Default` reproduces it exactly.
 *   - `POST /api/access-requests` did NOT use `Default` for the insert — it derived the household
 *     from the device row in-SQL (`ORDER BY d.household_id LIMIT 1`). Falling back to `Default`
 *     there would reject every non-default household's intake, since their MACs are absent from
 *     household 1. That route therefore applies its OWN fallback (`DeviceRepo.findOwningHousehold`).
 *
 * [[BlockPageHousehold.Resolution]] carries WHICH of the two cases happened precisely so each
 * caller can honour its own prior behaviour; a bare `HouseholdId` could not express it.
 *
 * None of this is a silent disable switch (AGENTS.md §no-dark-by-default): every outcome is metered
 * on `block_page_household_total{outcome}` and the degraded ones are logged, so "the fleet is still
 * falling back" is observable rather than invisible. The residual disclosure the read-side fallback
 * leaves is separately narrowed by the rate limit both routes now carry.
 */
trait BlockPageHousehold {

  /**
   * Resolve the household behind a block-page request from the redirect's `bpt`. Never fails — an
   * unusable token degrades rather than erroring, because the caller is normally a kid staring at a
   * block page, not an attacker.
   *
   * The result says WHETHER the household came from a verified token, not just which household it
   * is. Callers need that: the two endpoints fell back DIFFERENTLY before this change, so
   * "preserve the pre-change behaviour" means something different on each, and a caller that can't
   * tell a token-derived household from a fallback one cannot preserve either.
   */
  def resolve(bpt: Option[String]): UIO[BlockPageHousehold.Resolution]
}

object BlockPageHousehold {

  /**
   * Where the household came from.
   *
   *   - [[FromToken]] — a verified, router-bound `bpt` named a live router; this household is
   *     authoritative and a caller may hold the request to it strictly (e.g. refuse a MAC the
   *     household doesn't own).
   *   - [[Fallback]] — no usable token. `household` is [[HouseholdId.Default]], which is what
   *     `GET /api/blocked` did unconditionally pre-change. `POST /api/access-requests` did NOT do
   *     that — it derived the household from the device row in-SQL — so that caller must apply its
   *     OWN pre-change fallback rather than using this value. Carrying the case rather than a bare
   *     household is what makes that possible.
   */
  enum Resolution {
    case FromToken(household: HouseholdId)
    case Fallback(household: HouseholdId)

    def household: HouseholdId = this match {
      case FromToken(h) => h
      case Fallback(h)  => h
    }

    def fromToken: Boolean = this match {
      case FromToken(_) => true
      case Fallback(_)  => false
    }
  }

  /**
   * A resolver with no router directory: every request falls back to [[HouseholdId.Default]]. For
   * specs whose subject is NOT household derivation, so they don't have to thread a RouterRepo
   * through every route construction. Mirrors `HouseholdRepo.defaultOnly`. Never wired in
   * production — `HttpRoutes` always builds a [[BlockPageHouseholdLive]].
   */
  val defaultOnly: BlockPageHousehold = _ =>
    ZIO.succeed(Resolution.Fallback(HouseholdId.Default))
}

/**
 * @param secret
 *   the API's existing JWT HMAC secret, domain-separated by [[BlockPageToken]] — no new secret to
 *   provision, and no schema change.
 */
final class BlockPageHouseholdLive(secret: String, routerRepo: RouterRepo)
    extends BlockPageHousehold {

  /**
   * Outcomes, all metered on `block_page_household_total{outcome}`:
   *   - `token` — verified, and its router is known; that router's LIVE `household_id` wins. (Read
   *     live rather than carried on the token, so moving a router between households re-points it
   *     and deleting the router revokes it — there is no expiry to lean on.)
   *   - `absent` — no `bpt`: a pre-token agent, or a hand-typed URL.
   *   - `invalid` — a `bpt` that does not verify against our secret.
   *   - `unknown_router` — verified signature, but the router row is gone (deleted / re-enrolled).
   *   - `error` — the lookup itself failed; we still answer, from the default household.
   */
  import BlockPageHousehold.Resolution

  private val fallback = Resolution.Fallback(HouseholdId.Default)

  def resolve(bpt: Option[String]): UIO[Resolution] =
    bpt.map(_.trim).filter(_.nonEmpty) match {
      case None      => outcome("absent").as(fallback)
      case Some(tok) =>
        BlockPageToken.verify(secret, tok) match {
          case None      =>
            outcome("invalid") *>
              ZIO
                .logWarning("block-page token failed verification; falling back")
                .as(fallback)
          case Some(rid) => lookup(rid)
        }
    }

  private def lookup(rid: RouterId): UIO[Resolution] =
    routerRepo
      .findById(rid)
      .foldZIO(
        e =>
          outcome("error") *>
            ZIO
              .logError(s"block-page household lookup failed: ${e.getMessage}")
              .as(fallback),
        {
          case Some(router) => outcome("token").as(Resolution.FromToken(router.householdId))
          case None         =>
            outcome("unknown_router") *>
              ZIO
                .logWarning("block-page token names an unknown router; falling back")
                .as(fallback)
        },
      )

  private def outcome(o: String): UIO[Unit] = AppMetrics.blockPageHousehold(o)
}
