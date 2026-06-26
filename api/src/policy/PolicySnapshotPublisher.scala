package wifihaven.api.policy

import wifihaven.shared.PolicySnapshot
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
 */
trait PolicySnapshotPublisher {
  def publish(snap: PolicySnapshot): UIO[Unit]
}

object PolicySnapshotPublisher {
  val noop: PolicySnapshotPublisher = new PolicySnapshotPublisher {
    def publish(snap: PolicySnapshot): UIO[Unit] = ZIO.unit
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
      def publish(snap: PolicySnapshot): UIO[Unit] =
        ZIO.foreachDiscard(sinks)(_.publish(snap))
    }
}
