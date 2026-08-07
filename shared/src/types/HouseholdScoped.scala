package wifihaven.shared.types

/**
 * #2630: a value that may only be read by the household it was built for.
 *
 * This exists because a household filter written as an `if` is a filter someone can forget. The ws
 * policy push fanned every household's snapshot to every connected router for the whole of the
 * multi-tenant period — a router in household B received, persisted and ENFORCED household A's
 * device names, MAC addresses, profile names and blocked hosts — and nothing in the type of
 * `publishPolicy(household, snap)` made the unscoped `foreach` over the registry wrong. It compiled
 * and it read fine. The same class of hole is #2603 (SPA query cache keys) and #2533 (an unscoped
 * settings write); each was closed with a test, and each time the NEXT one was found in production.
 *
 * So the payload is not reachable without presenting a household id:
 * {{{
 *   val scoped = HouseholdScoped(snapshotHousehold, snap)
 *   scoped.forHousehold(router.householdId)   // Some(snap) iff the households match, else None
 * }}}
 *
 * A fan-out loop can therefore only produce a frame for a recipient it has already matched — the
 * unscoped version does not typecheck, because there is no way to get a `PolicySnapshot` out of a
 * `HouseholdScoped[PolicySnapshot]` without naming the recipient. That is the whole design: the
 * guard is the only accessor, so "forgot to filter" and "cannot compile" are the same event.
 *
 * Deliberately NOT on the wire. The router snapshot is a minimal functional shape (`AGENTS.md`) and
 * the router is a dumb applier that never learns which household it belongs to; this wrapper is a
 * server-side routing fact and it is unwrapped before anything is serialised.
 *
 * @param household
 *   the household `value` was built for — exposed via [[owner]] for diagnostics ONLY.
 */
final class HouseholdScoped[+A] private (private val household: HouseholdId, private val value: A) {

  /**
   * The payload iff `recipient` is the household this value was built for, else `None`. The ONLY
   * accessor — see the class doc for why there is no plain `get`.
   */
  def forHousehold(recipient: HouseholdId): Option[A] =
    if (recipient == household) Some(value) else None

  /** True iff `recipient` may read this value. For a check that needs no payload. */
  def belongsTo(recipient: HouseholdId): Boolean = recipient == household

  /**
   * The owning household, for LOG LINES AND METRICS ONLY.
   *
   * Do not launder the payload back out with `forHousehold(owner)`: that reproduces exactly the
   * unscoped read this type exists to prevent, and it is a review blocker. The accessor is here
   * because a refused delivery is only debuggable if the log can name both households.
   */
  def owner: HouseholdId = household

  /** Re-scope a derived value to the same household. */
  def map[B](f: A => B): HouseholdScoped[B] = new HouseholdScoped(household, f(value))
}

object HouseholdScoped {

  /**
   * Scope `value` to `household`. The caller must be the code that KNOWS the household the value
   * was built for — for a policy snapshot that is `PolicyService`, which built it from that
   * household's rows. Wrapping at any later point just re-asserts an assumption.
   */
  def apply[A](household: HouseholdId, value: A): HouseholdScoped[A] =
    new HouseholdScoped(household, value)
}
