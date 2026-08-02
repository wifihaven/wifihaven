package wifihaven.api.feature

import wifihaven.api.routes.RouterWsRegistry
import wifihaven.shared.types.RouterId
import zio.*
import zio.http.*
import zio.metrics.Metric
import zio.test.*

import java.util.UUID

/**
 * #2561: the `router_ws_connections_active` gauge leaked upward on prod — it read 2 while exactly
 * one router was connected, and the log showed the cause: two consecutive `router ws: connected`
 * for the SAME router id with no `disconnected` between them (a half-open socket whose teardown
 * `ensuring` never fired, so its registry entry was never dropped).
 *
 * The gauge is not a separately-incremented counter — [[RouterWsRegistry]] recomputes it from the
 * live map on every mutation, so scrape-time derivation would report the same wrong number. The
 * leak is a stale REGISTRY entry, and the fix is that a re-connect for a router id that is already
 * present SUPERSEDES the old channel (evict + shut down) rather than accumulating alongside it.
 *
 * This spec is the regression pin: connect the same router id twice with no intervening clean
 * disconnect, tear down only the live one, and assert the gauge is back to 0.
 *
 * The gauge is read back straight off the ZIO metric registry (`Metric.gauge(name).value`), the
 * same key `MetricGuard.gauge` writes with an empty label set. That read is SYNCHRONOUS — there is
 * no Prometheus publisher, no snapshot listener, and therefore no background fiber to wait on, so
 * this spec needs no wall-clock polling (`docs/process/testing.md` — never wait on wall-clock time
 * for async work; #2042).
 */
object RouterWsConnectionsGaugeSpec extends ZIOSpec[Any] {

  override val bootstrap: ZLayer[Any, Nothing, Any] = ZLayer.empty

  private val routerId = RouterId(UUID.fromString("3498967e-3842-41d2-960f-b521c7809cf4"))

  /**
   * A minimal [[WebSocketChannel]] stand-in. The registry only ever holds a channel for identity
   * and calls `shutdown` on a superseded one, so nothing here needs real transport behaviour — the
   * `shutdown` flag is what proves the stale channel was actively torn down rather than merely
   * forgotten. Distinct instances compare by reference, which is exactly how the registry's
   * per-channel `Set` distinguishes an old socket from its replacement. (A network socket is
   * external I/O — the one thing `docs/process/testing.md` does allow a test to stand in for; the
   * end-to-end path over a REAL client and server is pinned in [[RouterWsSpec]].)
   */
  private final class StubChannel(val shutdownCalled: Ref[Boolean]) extends WebSocketChannel {
    def awaitShutdown(implicit trace: Trace): UIO[Unit]                                 = ZIO.unit
    def receive(implicit trace: Trace): Task[WebSocketChannelEvent]                     = ZIO.never
    def receiveAll[Env, Err](f: WebSocketChannelEvent => ZIO[Env, Err, Any])(
        implicit trace: Trace,
    ): ZIO[Env, Err, Unit] = ZIO.never
    def send(in: WebSocketChannelEvent)(implicit trace: Trace): Task[Unit]              = ZIO.unit
    def sendAll(in: Iterable[WebSocketChannelEvent])(implicit trace: Trace): Task[Unit] = ZIO.unit
    def shutdown(implicit trace: Trace): UIO[Unit]                                      =
      shutdownCalled.set(true)
  }

  private def stubChannel: UIO[StubChannel] = Ref.make(false).map(new StubChannel(_))

  /**
   * The live `router_ws_connections_active` value, read off the same unlabelled key it is set on.
   */
  private def connectionsActive: UIO[Double] =
    Metric.gauge("router_ws_connections_active").value.map(_.value)

  /** The cumulative `router_ws_connections_superseded_total` count (asserted as a delta). */
  private def supersededTotal: UIO[Double] =
    Metric.counter("router_ws_connections_superseded_total").value.map(_.count)

  def spec = suite("router_ws_connections_active gauge (#2561)")(
    test("a reconnect for an already-connected router supersedes the stale channel") {
      for {
        reg            <- RouterWsRegistry.make
        stale          <- stubChannel
        live           <- stubChannel
        supersededPre  <- supersededTotal
        // The prod sequence: `connected`, then `connected` again for the SAME router with no
        // `disconnected` between (the first socket went half-open; its teardown never ran).
        _              <- reg.register(routerId, stale)
        _              <- reg.register(routerId, live)
        afterSecond    <- reg.activeCount
        gaugeWhileUp   <- connectionsActive
        staleClosed    <- stale.shutdownCalled.get
        supersededPost <- supersededTotal
        // Only the live socket ever tears down cleanly — the stale one never will.
        _              <- reg.deregister(routerId, live)
        afterTeardown  <- reg.activeCount
        gaugeAfter     <- connectionsActive
        stillConnected <- reg.isConnected(routerId)
      } yield assertTrue(afterSecond == 1) &&
        assertTrue(gaugeWhileUp == 1.0) &&
        assertTrue(staleClosed) &&
        // The eviction is metered, so the half-open rate the fix absorbs stays observable.
        assertTrue(supersededPost - supersededPre == 1.0) &&
        assertTrue(afterTeardown == 0) &&
        assertTrue(gaugeAfter == 0.0) &&
        assertTrue(!stillConnected)
    },
    test("distinct routers each hold their own channel — superseding is per router id") {
      for {
        reg <- RouterWsRegistry.make
        other = RouterId(UUID.fromString("f04dd490-a8c1-431a-9aec-44f64bff3b23"))
        chA     <- stubChannel
        chB     <- stubChannel
        _       <- reg.register(routerId, chA)
        _       <- reg.register(other, chB)
        both    <- reg.activeCount
        _       <- reg.deregister(routerId, chA)
        oneGone <- reg.activeCount
        otherUp <- reg.isConnected(other)
        aClosed <- chA.shutdownCalled.get
      } yield assertTrue(both == 2) &&
        assertTrue(oneGone == 1) &&
        assertTrue(otherUp) &&
        // A different router's connect must NOT shut this one's channel down.
        assertTrue(!aClosed)
    },
  ) @@ TestAspect.sequential
}
