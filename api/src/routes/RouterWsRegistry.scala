package wifihaven.api.routes

import wifihaven.api.metrics.AppMetrics
import wifihaven.api.policy.PolicySnapshotPublisher
import wifihaven.shared.PolicySnapshot
import wifihaven.shared.types.RouterId
import zio.*
import zio.http.{ChannelEvent, WebSocketChannel, WebSocketFrame}
import zio.json.*

/**
 * #1846: the per-router websocket connection registry. Tracks the live [[WebSocketChannel]]s open
 * for each [[RouterId]] so a later push path (#1849 — policy pushed on change) can look up a
 * router's socket(s) and fan a `policy` frame out to them, and so the server can expose a "is this
 * router connected right now?" signal (§5.5).
 *
 * Single-process, in-memory (`Ref[Map[RouterId, Set[WebSocketChannel]]]`). This is the right shape
 * for the single-household model; multi-tenant pub/sub fan-out across instances is explicitly out
 * of scope (#1023, design §6.1). A router may hold more than one channel transiently (a reconnect
 * whose old socket has not yet been deregistered), hence a `Set` per id.
 *
 * Every mutation refreshes the `router_ws_connections_active` gauge to the live total channel
 * count, so the gauge ages out cleanly on disconnect (§7).
 */
trait RouterWsRegistry {

  /** Record a freshly-upgraded channel for `id`. Refreshes the active-connections gauge. */
  def register(id: RouterId, channel: WebSocketChannel): UIO[Unit]

  /** Drop a closed/closing channel for `id`. Refreshes the active-connections gauge. */
  def deregister(id: RouterId, channel: WebSocketChannel): UIO[Unit]

  /** The live channels for `id` (empty if the router is not connected) — the #1849 push lookup. */
  def channelsFor(id: RouterId): UIO[Set[WebSocketChannel]]

  /** True iff `id` has at least one live channel right now (§5.5 link-up signal). */
  def isConnected(id: RouterId): UIO[Boolean]

  /** Total open channels across all routers — backs `router_ws_connections_active`. */
  def activeCount: UIO[Int]

  /**
   * #1849: fan a freshly-changed snapshot out as one `policy` frame to every connected channel. The
   * server-side end of push-on-change: [[wifihaven.api.policy.PolicyService.reevaluate]] calls this
   * (via the [[PolicySnapshotPublisher]] seam) only when the snapshot's ETag actually moved, so a
   * change is computed once and pushed, not recomputed per poll per router (#1512). A send failure
   * (a racing disconnect) deregisters that channel and is metered `channel_closed`.
   */
  def publishPolicy(snap: PolicySnapshot): UIO[Unit]

  /**
   * #1849: push the current snapshot to a single freshly-connected channel (design §6.1 first-
   * policy-on-connect), so a router that just opened the socket gets policy immediately rather than
   * at the next change. A send failure is metered `channel_closed` (the caller's receive loop will
   * deregister on close).
   */
  def pushPolicyTo(channel: WebSocketChannel, snap: PolicySnapshot): UIO[Unit]
}

object RouterWsRegistry {

  def make: UIO[RouterWsRegistry] =
    Ref.make(Map.empty[RouterId, Set[WebSocketChannel]]).map(new RouterWsRegistryLive(_))

  /** The `{op:"policy", payload:<snapshot>}` envelope a pushed snapshot rides (design §1.2). */
  private[routes] def policyFrameText(snap: PolicySnapshot): String =
    s"""{"op":"policy","payload":${snap.toJson}}"""
}

final class RouterWsRegistryLive(
    state: Ref[Map[RouterId, Set[WebSocketChannel]]],
) extends RouterWsRegistry
    with PolicySnapshotPublisher {

  // Recompute the live total and publish it. Called after every mutation so a deregister on
  // disconnect drives the gauge back down rather than leaving a stale high-water value.
  private def publishActive(m: Map[RouterId, Set[WebSocketChannel]]): UIO[Unit] =
    AppMetrics.setWsConnectionsActive(m.valuesIterator.map(_.size).sum)

  def register(id: RouterId, channel: WebSocketChannel): UIO[Unit] =
    state
      .updateAndGet(m => m.updated(id, m.getOrElse(id, Set.empty) + channel))
      .flatMap(publishActive)

  def deregister(id: RouterId, channel: WebSocketChannel): UIO[Unit] =
    state
      .updateAndGet { m =>
        val remaining = m.getOrElse(id, Set.empty) - channel
        if remaining.isEmpty then m.removed(id) else m.updated(id, remaining)
      }
      .flatMap(publishActive)

  def channelsFor(id: RouterId): UIO[Set[WebSocketChannel]] =
    state.get.map(_.getOrElse(id, Set.empty))

  def isConnected(id: RouterId): UIO[Boolean] =
    state.get.map(_.get(id).exists(_.nonEmpty))

  def activeCount: UIO[Int] =
    state.get.map(_.valuesIterator.map(_.size).sum)

  // The `PolicySnapshotPublisher` sink PolicyService.reevaluate pushes changed snapshots to.
  def publish(snap: PolicySnapshot): UIO[Unit] = publishPolicy(snap)

  def publishPolicy(snap: PolicySnapshot): UIO[Unit] =
    state.get.flatMap { m =>
      val frame = RouterWsRegistry.policyFrameText(snap)
      ZIO.foreachDiscard(m.toList) { case (id, channels) =>
        ZIO.foreachDiscard(channels)(ch => sendPolicyFrame(Some(id), ch, frame))
      }
    }

  def pushPolicyTo(channel: WebSocketChannel, snap: PolicySnapshot): UIO[Unit] =
    sendPolicyFrame(None, channel, RouterWsRegistry.policyFrameText(snap))

  private def sendPolicyFrame(
      id: Option[RouterId],
      channel: WebSocketChannel,
      frame: String,
  ): UIO[Unit] =
    // #2168: time the outbound policy-push send as router_ws_message_duration_seconds{op=policy,
    // direction=out} — the outbound half of the per-ws-op latency. `ensuring` fires the observation
    // on every exit (ok or racing-disconnect), same as the inbound timing in RouterWsRoutes.
    Clock.nanoTime.flatMap { start =>
      channel
        .send(ChannelEvent.read(WebSocketFrame.text(frame)))
        .foldZIO(
          _ =>
            // A racing disconnect: meter the failed push and (when we know which router it was) drop
            // the dead channel. The receive loop's `ensuring` also deregisters, but doing it here too
            // keeps the gauge honest if the push raced ahead of the close event.
            AppMetrics.recordWsPolicyPush("channel_closed") *>
              ZIO.foreachDiscard(id)(deregister(_, channel)),
          _ =>
            AppMetrics.recordWsFrame("policy", "out", "ok") *>
              AppMetrics.recordWsPolicyPush("ok"),
        )
        .ensuring(
          Clock.nanoTime.flatMap(end =>
            AppMetrics.recordWsMessageDuration("policy", "out", (end - start) / 1e9d),
          ),
        )
    }
}
