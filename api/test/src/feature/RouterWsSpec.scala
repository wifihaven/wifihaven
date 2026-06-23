package wifihaven.api.feature

import wifihaven.api.{ErrorBoundary, Readiness}
import wifihaven.api.db.*
import wifihaven.api.metrics.{HttpMetrics, RouterMetricsService}
import wifihaven.api.observability.LoggingMiddleware
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import doobie.Transactor
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.http.ChannelEvent.UserEvent
import zio.json.*
import zio.test.*

import java.time.{Instant, LocalDateTime}

/**
 * #1846 + #1847: server-side websocket transport. Exercises the real `/api/router/ws` endpoint end
 * to end — a real [[Server]] on an ephemeral port and a real ws [[Client]] — to prove upgrade-time
 * auth, the `{op, payload}` demux dispatching into the shared ingest service, the per-router
 * connection registry, the heartbeat (`ping`→`pong`), and the #1847 capability handshake:
 * `hello`→`ready` with `snapshotVersion` negotiation (`min` rule), the future-agent back-compat
 * down-negotiation, the below-floor refusal (close 4003), and the hello-timeout refusal (close
 * 4002). The REST ingest path stays covered by [[RouterIngestSpec]], which is the back-compat gate
 * for the transport-agnostic ingest extraction.
 */
object RouterWsSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  private val testClockAt: LocalDateTime = LocalDateTime.of(2026, 5, 7, 14, 0, 0)

  override val bootstrap = TestDatabase.layer ++ TestLayers.withClock(testClockAt)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val periodStart = Instant.parse("2026-05-07T14:00:00Z")
  private val periodEnd   = Instant.parse("2026-05-07T14:05:00Z")
  private val knownMac    = "aa:bb:cc:11:22:33"

  private def seedRouter(rRepo: RouterRepo): Task[(RouterId, String)] = {
    val token = "ROUTER_TOKEN_PLAIN"
    val hash  = Sha256Hex.unsafe(RouterAuth.sha256Hex(token))
    for {
      id <- rRepo.create("test-router", Sha256Hex.unsafe("m" * 64))
      _  <- rRepo.completeEnrollment(id, hash)
    } yield (id, token)
  }

  private def seedKnownDevice(dRepo: DeviceRepo, profileRepo: ProfileRepo): Task[Unit] =
    for {
      pid <- profileRepo.create("Kids", List(BlocklistId.unsafe("adult")))
      _   <- dRepo.upsert(MacAddress.unsafe(knownMac), "kid-ipad", Some(pid), "192.168.1.10")
    } yield ()

  private def buildWsRoutes(
      helloTimeout: Duration = RouterWsRoutes.DefaultHelloTimeout,
  ) =
    for {
      rRepo   <- ZIO.service[RouterRepo]
      tRepo   <- ZIO.service[TrafficReportRepo]
      tu      <- ZIO.service[TimeUsageRepo]
      dRepo   <- ZIO.service[DeviceRepo]
      cRepo   <- ZIO.service[ConnectionEventRepo]
      aRepo   <- ZIO.service[AlertRepo]
      hsr     <- ZIO.service[HouseholdSettingsRepo]
      metrics <- RouterMetricsService.make
      reg     <- RouterWsRegistry.make
      auth   = new RouterAuthLive(rRepo)
      ingest = new RouterIngestService(rRepo, tRepo, tu, dRepo, cRepo, aRepo, hsr)
      // Mount the ws route through the SAME aspect stack `Main` wraps the router routes in
      // (HttpMetrics.instrument → LoggingMiddleware.annotate → Readiness.gate → ErrorBoundary.observe)
      // so the test proves the HTTP/1.1 upgrade survives the production middleware, not just the raw
      // route. These aspects are response-transparent for a websocket Response, but pinning it here
      // closes the gap between the test path and the assembled prod path.
      raw    = RouterWsRoutes.routes(auth, reg, ingest, metrics, rRepo, helloTimeout)
      routes = HttpMetrics.instrument(
        LoggingMiddleware.annotate(
          Readiness.gate(ErrorBoundary.observe(raw), ZIO.succeed(true)),
        ),
      )
    } yield (routes, reg)

  /**
   * Open a ws connection to the bound server, run `clientBehavior` (which drives the channel and
   * resolves `firstServerText` with the first text frame the server sends back), and return that
   * frame. Connects with the given bearer header.
   */
  private def connectAndCapture[B](
      port: Int,
      bearer: Option[String],
      send: WebSocketChannel => ZIO[Any, Throwable, Unit],
      probe: ZIO[Any, Throwable, B],
  ): ZIO[Client, Throwable, (String, B)] =
    Promise.make[Nothing, String].flatMap { firstServerText =>
      val app     = Handler.webSocket { channel =>
        channel.receiveAll {
          case ChannelEvent.UserEventTriggered(UserEvent.HandshakeComplete) =>
            send(channel)
          case ChannelEvent.Read(WebSocketFrame.Text(t))                    =>
            firstServerText.succeed(t).unit
          case _                                                            =>
            ZIO.unit
        }
      }
      val headers = bearer.fold(Headers.empty)(t => Headers(Header.Authorization.Bearer(t)))
      // Drive the connection in a LOCAL scope closed as soon as we have the server's reply, so the
      // ws fiber is interrupted promptly and the test does not wait on connection teardown. `probe`
      // runs INSIDE the scope (connection still open) so a registry-liveness check observes the live
      // channel rather than racing the post-scope deregister.
      ZIO.scoped {
        for {
          _ <- app.connect(s"ws://localhost:$port/api/router/ws", headers).forkScoped
          t <- firstServerText.await
            .timeoutFail(new RuntimeException("no server frame within 30s"))(30.seconds)
          b <- probe
        } yield (t, b)
      }
    }

  /**
   * Open a ws connection, run `send` on handshake-complete, and resolve with the close code the
   * server sends (the #1847 handshake-refusal paths: 4002 hello-required, 4003 version-exceeded).
   */
  private def connectAndCaptureClose(
      port: Int,
      bearer: Option[String],
      send: WebSocketChannel => ZIO[Any, Throwable, Unit],
  ): ZIO[Client, Throwable, Int] =
    Promise.make[Nothing, Int].flatMap { closeCode =>
      // Forward close frames to userland (Netty's default `handleCloseFrames=true` would otherwise
      // swallow the server's Close and complete the handshake silently) so we can assert the exact
      // application close code (4002 hello-required / 4003 version-exceeded).
      val app     = Handler
        .webSocket { channel =>
          channel.receiveAll {
            case ChannelEvent.UserEventTriggered(UserEvent.HandshakeComplete) =>
              send(channel)
            case ChannelEvent.Read(WebSocketFrame.Close(code, _))             =>
              closeCode.succeed(code).unit
            case _                                                            =>
              ZIO.unit
          }
        }
        .withConfig(WebSocketConfig.default.forwardCloseFrames(true))
      val headers = bearer.fold(Headers.empty)(t => Headers(Header.Authorization.Bearer(t)))
      ZIO.scoped {
        for {
          _ <- app.connect(s"ws://localhost:$port/api/router/ws", headers).forkScoped
          c <- closeCode.await
            .timeoutFail(new RuntimeException("no close frame within 30s"))(30.seconds)
        } yield c
      }
    }

  def spec = suite("Router websocket /api/router/ws")(
    test("rejects the upgrade with 401 when the bearer token is missing or invalid") {
      for {
        _           <- cleanDb
        (routes, _) <- buildWsRoutes()
        noToken     <- routes.runZIO(
          Request.get(URL.decode("/api/router/ws").toOption.get),
        )
        badToken    <- routes.runZIO(
          Request
            .get(URL.decode("/api/router/ws").toOption.get)
            .addHeader(Header.Authorization.Bearer("not-a-real-token")),
        )
      } yield assertTrue(noToken.status == Status.Unauthorized) &&
        assertTrue(badToken.status == Status.Unauthorized)
    },
    test("a usage frame ingests through the shared service and the server acks ok") {
      (for {
        _             <- cleanDb
        rRepo         <- ZIO.service[RouterRepo]
        pRepo         <- ZIO.service[ProfileRepo]
        dRepo         <- ZIO.service[DeviceRepo]
        tRepo         <- ZIO.service[TrafficReportRepo]
        _             <- seedKnownDevice(dRepo, pRepo)
        (id, tk)      <- seedRouter(rRepo)
        (routes, reg) <- buildWsRoutes()
        port          <- Server.install(routes)
        rec       = UsageRecord(
          MacAddress.unsafe(knownMac),
          Some(IpAddress.unsafe("192.168.1.10")),
          HostId.Fqdn(Hostname.unsafe("youtube.com")),
          240L,
          1000L,
          500L,
        )
        usageBody = UsageReport(id, periodStart.toString, periodEnd.toString, List(rec)).toJson
        frame     = s"""{"op":"usage","seq":7,"payload":$usageBody}"""
        result <- connectAndCapture(
          port,
          Some(tk),
          ch => ch.send(ChannelEvent.read(WebSocketFrame.text(frame))),
          reg.isConnected(id),
        )
        (ack, connected) = result
        rows <- tRepo.listForRouter(id, 100)
      } yield assertTrue(ack.contains("\"op\":\"ack\"")) &&
        assertTrue(ack.contains("\"status\":\"ok\"")) &&
        assertTrue(ack.contains("\"seq\":7")) &&
        assertTrue(rows.size == 1) &&
        assertTrue(connected)).provideSome[
        TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task],
      ](Server.defaultWithPort(0), Client.default)
    },
    test("a ping frame is answered with a pong") {
      (for {
        _           <- cleanDb
        rRepo       <- ZIO.service[RouterRepo]
        (id, tk)    <- seedRouter(rRepo)
        (routes, _) <- buildWsRoutes()
        port        <- Server.install(routes)
        result      <- connectAndCapture(
          port,
          Some(tk),
          ch => ch.send(ChannelEvent.read(WebSocketFrame.text("""{"op":"ping"}"""))),
          ZIO.unit,
        )
        pong = result._1
      } yield assertTrue(pong.contains("\"op\":\"pong\""))).provideSome[
        TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task],
      ](Server.defaultWithPort(0), Client.default)
    },
    // #1847 — capability handshake. A v1 `hello` negotiates `snapshotVersion: 1` and gets the
    // server's capability set back in `ready`.
    test("a hello at snapshotVersion 1 is answered with ready (negotiated v1 + server caps)") {
      (for {
        _           <- cleanDb
        rRepo       <- ZIO.service[RouterRepo]
        (_, tk)     <- seedRouter(rRepo)
        (routes, _) <- buildWsRoutes()
        port        <- Server.install(routes)
        hello =
          """{"op":"hello","payload":{"agentCapabilities":["ws-transport-v1"],"snapshotVersion":1,"agentVersion":"0.3.1"}}"""
        result <- connectAndCapture(
          port,
          Some(tk),
          ch => ch.send(ChannelEvent.read(WebSocketFrame.text(hello))),
          ZIO.unit,
        )
        ready = result._1
      } yield assertTrue(ready.contains("\"op\":\"ready\"")) &&
        assertTrue(ready.contains("\"snapshotVersion\":1")) &&
        assertTrue(ready.contains("ws-transport-v1")) &&
        assertTrue(ready.contains("ack-frames"))).provideSome[
        TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task],
      ](Server.defaultWithPort(0), Client.default)
    },
    // #1847 / #376 back-compat: a FUTURE agent that knows a higher snapshotVersion polling a v1
    // server is handed today's v1 shape (negotiated = min(agent.max, server.max)) — no flag day.
    test("a hello at a future snapshotVersion negotiates down to the server's v1") {
      (for {
        _           <- cleanDb
        rRepo       <- ZIO.service[RouterRepo]
        (_, tk)     <- seedRouter(rRepo)
        (routes, _) <- buildWsRoutes()
        port        <- Server.install(routes)
        hello =
          """{"op":"hello","payload":{"agentCapabilities":["ws-transport-v1","policy-diff"],"snapshotVersion":5,"agentVersion":"9.9.9"}}"""
        result <- connectAndCapture(
          port,
          Some(tk),
          ch => ch.send(ChannelEvent.read(WebSocketFrame.text(hello))),
          ZIO.unit,
        )
        ready = result._1
      } yield assertTrue(ready.contains("\"op\":\"ready\"")) &&
        assertTrue(ready.contains("\"snapshotVersion\":1"))).provideSome[
        TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task],
      ](Server.defaultWithPort(0), Client.default)
    },
    // #1847 / §2.3 version ceiling: an agent that doesn't even understand v1 (snapshotVersion 0)
    // has no shape in common with the server → close 4003 version-exceeded, no `ready`.
    test("a hello below the server's version floor is refused with close 4003") {
      (for {
        _           <- cleanDb
        rRepo       <- ZIO.service[RouterRepo]
        (_, tk)     <- seedRouter(rRepo)
        (routes, _) <- buildWsRoutes()
        port        <- Server.install(routes)
        hello =
          """{"op":"hello","payload":{"agentCapabilities":[],"snapshotVersion":0,"agentVersion":"0.0.1"}}"""
        code <- connectAndCaptureClose(
          port,
          Some(tk),
          ch => ch.send(ChannelEvent.read(WebSocketFrame.text(hello))),
        )
      } yield assertTrue(code == 4003)).provideSome[
        TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task],
      ](Server.defaultWithPort(0), Client.default)
    },
    // #1847 / §2.2 hello-timeout: a connection that never sends `hello` is closed 4002 after the
    // window (driven short here so the test does not wait the real 5 s).
    test("a connection that never sends hello is closed 4002 after the timeout") {
      (for {
        _           <- cleanDb
        rRepo       <- ZIO.service[RouterRepo]
        (_, tk)     <- seedRouter(rRepo)
        (routes, _) <- buildWsRoutes(200.millis)
        port        <- Server.install(routes)
        code        <- connectAndCaptureClose(port, Some(tk), _ => ZIO.unit)
      } yield assertTrue(code == 4002)).provideSome[
        TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task],
      ](Server.defaultWithPort(0), Client.default)
    },
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(90.seconds)
}
