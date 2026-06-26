package wifihaven.api.feature

import wifihaven.api.routes.{SpaConnId, SpaTopic, SpaWsRegistry, SpaWsRoutes}
import wifihaven.shared.Clock
import wifihaven.testinfra.*
import zio.{Clock as _, *}
import zio.http.*
import zio.http.ChannelEvent.UserEvent
import zio.test.*

import java.time.LocalDateTime

/**
 * #1968 (S1 of the SPA-websocket rollout, design `docs/design/spa-websocket.md`): the
 * browser-facing `GET /api/ws` skeleton. Exercises the real endpoint end to end — a real [[Server]]
 * on an ephemeral port and a real ws [[Client]] — to prove the `{op, payload, seq?}` envelope
 * demux, the control ops (`hello`→`ready`, `subscribe`/`unsubscribe` with per-connection
 * subscription state + topic-keyed `ack`, `ping`/`pong`), the role-gated subscription
 * authorization, and the unknown-op ignore+meter rule. Mirrors the [[RouterWsSpec]] round-trip
 * recipe (drive the ws client inside a LOCAL `ZIO.scoped`, not forkScoped on the test scope).
 *
 * S1 has no upgrade auth (that is S2 / #1969) — the connection registers with a stub
 * least-privilege (`Child`) role at upgrade and the `hello` payload's `{role}` is the
 * test-handshake override, so the subscription machinery is exercised without a real cookie. REST
 * is untouched.
 */
object SpaWsSpec extends ZIOSpec[Clock] {

  private val testClockAt: LocalDateTime = LocalDateTime.of(2026, 6, 25, 14, 0, 0)

  override val bootstrap: ULayer[Clock] = TestLayers.withClock(testClockAt)

  private def buildRoutes: ZIO[Clock, Nothing, (Routes[Any, Response], SpaWsRegistry)] =
    for {
      clock <- ZIO.service[Clock]
      reg   <- SpaWsRegistry.make
    } yield (SpaWsRoutes.routes(reg, clock), reg)

  // Each test builds a FRESH registry with one connection, so the first (and only) connection is
  // deterministically assigned SpaConnId(1) — the seq counter starts at 0 and increments to 1 on the
  // single register. Lets a test inspect that connection's server-side subscription state directly.
  private val onlyConn: SpaConnId = SpaConnId(1L)

  /**
   * Open a ws connection to the bound server, drive it with `send` on handshake, and collect every
   * text frame the server sends. Resolves once a frame satisfies `until`, then runs `probe` while
   * the connection is still open (so a registry-state check observes the live channel, not the
   * post-scope deregister). Returns the matching frame, ALL frames received up to that point, and
   * the probe.
   */
  private def connectAndCapture[B](
      port: Int,
      send: WebSocketChannel => ZIO[Any, Throwable, Unit],
      until: String => Boolean,
      probe: ZIO[Any, Throwable, B] = ZIO.unit,
  ): ZIO[Client, Throwable, (String, Chunk[String], B)] =
    for {
      matched <- Promise.make[Nothing, String]
      frames  <- Ref.make(Chunk.empty[String])
      app = Handler.webSocket { channel =>
        channel.receiveAll {
          case ChannelEvent.UserEventTriggered(UserEvent.HandshakeComplete) =>
            send(channel)
          case ChannelEvent.Read(WebSocketFrame.Text(t))                    =>
            frames.update(_ :+ t) *> ZIO.when(until(t))(matched.succeed(t)).unit
          case _                                                            =>
            ZIO.unit
        }
      }
      result <- ZIO.scoped {
        for {
          _   <- app.connect(s"ws://localhost:$port/api/ws").forkScoped
          t   <- matched.await
            .timeoutFail(new RuntimeException("no matching server frame within 30s"))(30.seconds)
          all <- frames.get
          b   <- probe
        } yield (t, all, b)
      }
    } yield result

  /** Send a sequence of text frames in order on handshake. */
  private def sendFrames(channel: WebSocketChannel, frames: String*): ZIO[Any, Throwable, Unit] =
    ZIO.foreachDiscard(frames)(f => channel.send(ChannelEvent.read(WebSocketFrame.text(f))))

  def spec = suite("SPA websocket /api/ws")(
    test("hello is answered with ready carrying the role + serverTime") {
      (for {
        rr <- buildRoutes
        (routes, _) = rr
        port          <- Server.install(routes)
        (ready, _, _) <- connectAndCapture(
          port,
          ch => sendFrames(ch, """{"op":"hello","payload":{"role":"admin"}}"""),
          until = _.contains("\"op\":\"ready\""),
        )
      } yield assertTrue(ready.contains("\"op\":\"ready\"")) &&
        assertTrue(ready.contains("\"role\":\"admin\"")) &&
        // serverTime comes from the injected test clock (2026-06-25T14:00:00Z), never wall-clock.
        assertTrue(ready.contains("\"serverTime\":\"2026-06-25T14:00:00Z\""))).provideSome[Clock](
        Server.defaultWithPort(0),
        Client.default,
      )
    },
    test("subscribe to an authorized topic is acked ok and the subscription is held server-side") {
      (for {
        rr <- buildRoutes
        (routes, reg) = rr
        port <- Server.install(routes)
        // Probe the registry AFTER the ack arrives (still in-scope): the ack is sent only after
        // SpaWsRegistry.subscribe completes, so an ack-ok that failed to actually store the
        // subscription would be caught here — not just on the wire.
        res  <- connectAndCapture(
          port,
          ch =>
            sendFrames(
              ch,
              """{"op":"hello","payload":{"role":"admin"}}""",
              """{"op":"subscribe","payload":{"topic":"trafficUsage","params":{"groupBy":["profile"]}}}""",
            ),
          until = _.contains("\"op\":\"ack\""),
          probe = reg.subscriptionsFor(onlyConn),
        )
        (ack, _, subs) = res
      } yield assertTrue(ack.contains("\"op\":\"ack\"")) &&
        assertTrue(ack.contains("\"topic\":\"trafficUsage\"")) &&
        assertTrue(ack.contains("\"status\":\"ok\"")) &&
        assertTrue(subs.keySet == Set(SpaTopic.TrafficUsage))).provideSome[Clock](
        Server.defaultWithPort(0),
        Client.default,
      )
    },
    test("subscribe to a topic the role can't see is acked reject and not held") {
      (for {
        rr <- buildRoutes
        (routes, reg) = rr
        port <- Server.install(routes)
        // A child role may see only its own time topics, never the household-wide connectionEvents
        // feed (S1 placeholder authz, §4.4 — refined by S2's real cookie auth). The rejected
        // subscription must NOT be stored.
        res  <- connectAndCapture(
          port,
          ch =>
            sendFrames(
              ch,
              """{"op":"hello","payload":{"role":"child"}}""",
              """{"op":"subscribe","payload":{"topic":"connectionEvents"}}""",
            ),
          until = _.contains("\"op\":\"ack\""),
          probe = reg.subscriptionsFor(onlyConn),
        )
        (ack, _, subs) = res
      } yield assertTrue(ack.contains("\"op\":\"ack\"")) &&
        assertTrue(ack.contains("\"topic\":\"connectionEvents\"")) &&
        assertTrue(ack.contains("\"status\":\"reject\"")) &&
        assertTrue(subs.isEmpty)).provideSome[Clock](
        Server.defaultWithPort(0),
        Client.default,
      )
    },
    test("unsubscribe removes the held subscription (no further ack, ping confirms liveness)") {
      (for {
        rr <- buildRoutes
        (routes, reg) = rr
        port <- Server.install(routes)
        // subscribe (acked), then unsubscribe (no ack), then ping — the pong (sent only after the
        // unsubscribe was processed) proves the socket stayed live AND the subscription was removed:
        // the post-pong registry probe must show an empty subscription set.
        res  <- connectAndCapture(
          port,
          ch =>
            sendFrames(
              ch,
              """{"op":"hello","payload":{"role":"admin"}}""",
              """{"op":"subscribe","payload":{"topic":"now"}}""",
              """{"op":"unsubscribe","payload":{"topic":"now"}}""",
              """{"op":"ping"}""",
            ),
          until = _.contains("\"op\":\"pong\""),
          probe = reg.subscriptionsFor(onlyConn),
        )
        (pong, all, subs) = res
      } yield assertTrue(pong.contains("\"op\":\"pong\"")) &&
        assertTrue(
          all.exists(f => f.contains("\"op\":\"ack\"") && f.contains("\"status\":\"ok\"")),
        ) &&
        assertTrue(subs.isEmpty)).provideSome[Clock](
        Server.defaultWithPort(0),
        Client.default,
      )
    },
    test("an unknown op is ignored (no reply) while a known op still answers") {
      (for {
        rr <- buildRoutes
        (routes, _) = rr
        port           <- Server.install(routes)
        // Send an unknown op, then a ping. The unknown op must produce no frame; the pong proves the
        // socket survived (ignore + meter, forward-compat — design §2.2).
        (pong, all, _) <- connectAndCapture(
          port,
          ch =>
            sendFrames(
              ch,
              """{"op":"definitelyNotARealOp","payload":{}}""",
              """{"op":"ping"}""",
            ),
          until = _.contains("\"op\":\"pong\""),
        )
      } yield assertTrue(pong.contains("\"op\":\"pong\"")) &&
        // The only server frame is the pong — the unknown op generated no reply.
        assertTrue(all.count(_.contains("\"op\":")) == 1)).provideSome[Clock](
        Server.defaultWithPort(0),
        Client.default,
      )
    },
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(90.seconds)
}
