package wifihaven.api.feature

import wifihaven.api.MetricsConfig
import wifihaven.api.metrics.MetricsRuntime
import wifihaven.api.routes.{MetricsRoutes, RouterWsRegistry}
import wifihaven.shared.types.RouterId
import zio.*
import zio.http.*
import zio.metrics.connectors.prometheus.PrometheusPublisher
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
 * disconnect, tear down only the live one, and assert the scraped gauge is back to 0.
 */
object RouterWsConnectionsGaugeSpec extends ZIOSpec[PrometheusPublisher] {

  override val bootstrap = MetricsRuntime.prometheus(100.millis)

  private val routerId = RouterId(UUID.fromString("3498967e-3842-41d2-960f-b521c7809cf4"))

  /**
   * A minimal [[WebSocketChannel]] stand-in. The registry only ever holds a channel for identity
   * and calls `shutdown` on a superseded one, so nothing here needs real transport behaviour — the
   * `shutdown` flag is what proves the stale channel was actively torn down rather than merely
   * forgotten. Distinct instances compare by reference, which is exactly how the registry's
   * per-channel `Set` distinguishes an old socket from its replacement.
   */
  private final class StubChannel(val shutdownCalled: Ref[Boolean]) extends WebSocketChannel {
    def awaitShutdown(implicit trace: Trace): UIO[Unit]                                 = ZIO.unit
    def receive(implicit trace: Trace): Task[WebSocketChannelEvent]                     = ZIO.never
    def receiveAll[Env, Err](f: WebSocketChannelEvent => ZIO[Env, Err, Any])(
        implicit trace: Trace,
    ): ZIO[Env, Err, Unit] = ZIO.never
    def send(in: WebSocketChannelEvent)(implicit trace: Trace): Task[Unit]              = ZIO.unit
    def sendAll(in: Iterable[WebSocketChannelEvent])(implicit trace: Trace): Task[Unit] = ZIO.unit
    def shutdown(implicit trace: Trace): UIO[Unit] = shutdownCalled.set(true)
  }

  private def stubChannel: UIO[StubChannel] = Ref.make(false).map(new StubChannel(_))

  private def scrape: ZIO[PrometheusPublisher, Nothing, String] =
    for {
      pub <- ZIO.service[PrometheusPublisher]
      routes = MetricsRoutes.routes(MetricsConfig(enabled = true), pub)
      resp <- routes(Request.get("/metrics")).merge
      body <- resp.body.asString.orDie
    } yield body

  /**
   * Parse the unlabelled `router_ws_connections_active` gauge out of the exposition. The
   * zio-metrics prometheus line is `name value [timestamp]`, so the value is the SECOND token, not
   * the last (that's the scrape timestamp).
   */
  private def gaugeValue(body: String): Option[Double] =
    body.linesIterator
      .filterNot(_.startsWith("#"))
      .map(_.trim.split("\\s+"))
      .collectFirst {
        case parts if parts.length >= 2 && parts(0) == "router_ws_connections_active" =>
          parts(1).toDoubleOption.getOrElse(Double.NaN)
      }

  /** The publisher's snapshot listener is async, so poll until the gauge settles on `target`. */
  private def awaitGauge(target: Double): ZIO[PrometheusPublisher, Nothing, Double] =
    scrape
      .map(gaugeValue(_).getOrElse(Double.NaN))
      .repeat(Schedule.spaced(50.millis) && Schedule.recurUntil[Double](_ == target))
      .map(_._2)
      .timeoutTo(Double.NaN)(identity)(10.seconds)

  def spec = suite("router_ws_connections_active gauge (#2561)")(
    test("a reconnect for an already-connected router supersedes the stale channel") {
      for {
        reg            <- RouterWsRegistry.make
        stale          <- stubChannel
        live           <- stubChannel
        // The prod sequence: `connected`, then `connected` again for the SAME router with no
        // `disconnected` between (the first socket went half-open; its teardown never ran).
        _              <- reg.register(routerId, stale)
        _              <- reg.register(routerId, live)
        afterSecond    <- reg.activeCount
        gaugeWhileUp   <- awaitGauge(1.0)
        staleClosed    <- stale.shutdownCalled.get
        // Only the live socket ever tears down cleanly — the stale one never will.
        _              <- reg.deregister(routerId, live)
        afterTeardown  <- reg.activeCount
        gaugeAfter     <- awaitGauge(0.0)
        stillConnected <- reg.isConnected(routerId)
      } yield assertTrue(afterSecond == 1) &&
        assertTrue(gaugeWhileUp == 1.0) &&
        assertTrue(staleClosed) &&
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
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(60.seconds)
}
