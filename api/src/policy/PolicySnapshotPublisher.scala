package wifihaven.api.policy

import wifihaven.shared.PolicySnapshot
import wifihaven.shared.types.{HouseholdId, HouseholdScoped}
import zio.*

/**
 * #1849: the sink a freshly-rebuilt [[PolicySnapshot]] is pushed to when policy actually changes
 * (its ETag moved). The production implementation is the websocket connection registry
 * ([[wifihaven.api.routes.RouterWsRegistry]]), which fans the snapshot out as one `policy` frame to
 * every connected router channel — so a change is computed ONCE (in [[PolicyService.reevaluate]])
 * and pushed, rather than recomputed once per poll per router (#1512).
 *
 * Kept as a tiny one-method seam in the `policy` package (rather than depending on the `routes`
 * registry directly) so [[PolicyServiceLive]] has no compile dependency on the transport layer and
 * tests can substitute a probe. `PolicyService` is wired to the real publisher imperatively at
 * startup via [[PolicyService.setPublisher]] (the registry is constructed alongside the routes,
 * after the policy layer), defaulting to [[PolicySnapshotPublisher.noop]] until then — so a
 * snapshot rebuilt before the registry exists, or in a test that never sets a publisher, simply
 * isn't pushed.
 *
 * #2619/#2630: `publish` takes a [[HouseholdScoped]] snapshot rather than a bare one. A
 * `PolicySnapshot` has no household on it (the wire is deliberately tenant-blind — the router is a
 * dumb applier and never learns which household it belongs to), so without the wrapper a sink
 * cannot tell whose policy it is holding, and #2630 is what that cost: the router registry fanned
 * whatever it was handed to every connected channel across every tenant. The wrapper makes that
 * unwritable — a sink cannot read the snapshot at all without naming the recipient household.
 */
trait PolicySnapshotPublisher {

  /** Push a changed snapshot to whichever recipients this sink has for its household. */
  def publish(scoped: HouseholdScoped[PolicySnapshot]): UIO[Unit]

  /**
   * #2630: the households this sink currently has a recipient for — for the router registry, the
   * households with at least one connected router. [[PolicyService.reevaluate]] rebuilds and pushes
   * ONCE PER household in this set (plus [[HouseholdId.Default]]) instead of rebuilding household 1
   * and broadcasting it, so a non-default household's routers get their OWN policy on change rather
   * than someone else's.
   *
   * Required, not defaulted: a sink that silently reported no targets would be pushed nothing, and
   * a push path that goes quiet without saying so is the shape `docs/process/no-dark-by-default.md`
   * forbids. A sink with no household dimension (the SPA change-bus bridge, which fires a
   * contentless nudge and lets each subscriber rebuild under its own scope) returns the empty set
   * explicitly.
   */
  def targetHouseholds: UIO[Set[HouseholdId]]
}

object PolicySnapshotPublisher {
  val noop: PolicySnapshotPublisher = new PolicySnapshotPublisher {
    def publish(scoped: HouseholdScoped[PolicySnapshot]): UIO[Unit] = ZIO.unit
    def targetHouseholds: UIO[Set[HouseholdId]]                     = ZIO.succeed(Set.empty)
  }

  /**
   * #1970 (S3, design `docs/design/spa-websocket.md` §5.2.1): widen the single-sink publisher to a
   * MULTI-subscriber fan-out so a changed snapshot reaches more than one consumer — the
   * [[wifihaven.api.routes.RouterWsRegistry]] (which fans the full snapshot out as a `policy`
   * frame) AND the SPA push path (which derives a `now` recompute + `stale` nudge from "policy
   * changed").
   *
   * Behavior-PRESERVING for the router subscriber: `broadcast` invokes each sink's `publish` in
   * order, SYNCHRONOUSLY, exactly as the old single sink was invoked — so the router's #1846/#1849
   * push timing (reconcile-tick fan-out, first-policy-on-connect) is unchanged. We deliberately do
   * NOT route the snapshot through a `Hub` here: a Hub would interpose an async consumer fiber
   * between [[PolicyService.reevaluate]] and the router fan-out, changing that timing and risking
   * the router-ws tests; the SPA side instead gets its OWN async hub
   * ([[wifihaven.api.routes.SpaEventBus]]) fed by a thin sink in this list (`snap =>
   * spaEventBus.publish(NowChanged)`), so the router path stays synchronous and the SPA path is
   * decoupled. A failing/never-failing sink can't affect the others — each `publish` returns `UIO`.
   */
  def broadcast(sinks: List[PolicySnapshotPublisher]): PolicySnapshotPublisher =
    new PolicySnapshotPublisher {
      def publish(scoped: HouseholdScoped[PolicySnapshot]): UIO[Unit] =
        ZIO.foreachDiscard(sinks)(_.publish(scoped))

      // #2630: the union — reevaluate must rebuild a household if ANY sink has a recipient for it.
      def targetHouseholds: UIO[Set[HouseholdId]] =
        ZIO.foreach(sinks)(_.targetHouseholds).map(_.foldLeft(Set.empty[HouseholdId])(_ ++ _))
    }
}
