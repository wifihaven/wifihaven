package wifihaven.api.routes

import wifihaven.api.metrics.AppMetrics
import wifihaven.shared.types.RouterId
import zio.*
import zio.http.WebSocketChannel

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
}

object RouterWsRegistry {

  def make: UIO[RouterWsRegistry] =
    Ref.make(Map.empty[RouterId, Set[WebSocketChannel]]).map(new RouterWsRegistryLive(_))
}

final class RouterWsRegistryLive(
    state: Ref[Map[RouterId, Set[WebSocketChannel]]],
) extends RouterWsRegistry {

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
}
