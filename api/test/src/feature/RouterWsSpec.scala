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
 * #1846: server-side websocket transport. Exercises the real `/api/router/ws` endpoint end to end —
 * a real [[Server]] on an ephemeral port and a real ws [[Client]] — to prove upgrade-time auth, the
 * `{op, payload}` demux dispatching into the shared ingest service, the per-router connection
 * registry, and the heartbeat (`ping`→`pong`). The REST ingest path stays covered by
 * [[RouterIngestSpec]], which is the back-compat gate for the transport-agnostic ingest extraction.
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

  private def buildWsRoutes =
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
      raw    = RouterWsRoutes.routes(auth, reg, ingest, metrics, rRepo)
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

  def spec = suite("Router websocket /api/router/ws")(
    test("rejects the upgrade with 401 when the bearer token is missing or invalid") {
      for {
        _           <- cleanDb
        (routes, _) <- buildWsRoutes
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
        (routes, reg) <- buildWsRoutes
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
        (routes, _) <- buildWsRoutes
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
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(90.seconds)
}
