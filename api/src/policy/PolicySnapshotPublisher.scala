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
}
