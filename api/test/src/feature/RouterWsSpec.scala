package wifihaven.api.feature

import wifihaven.api.{ErrorBoundary, Readiness}
import wifihaven.api.db.*
import wifihaven.api.metrics.{HttpMetrics, RouterMetricsService}
import wifihaven.api.observability.LoggingMiddleware
import wifihaven.api.policy.PolicyServiceLive
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
      pRepo   <- ZIO.service[ProfileRepo]
      tlr     <- ZIO.service[TimeLimitRepo]
      atlr    <- ZIO.service[AppTimeLimitRepo]
      er      <- ZIO.service[TimeExtensionRepo]
      ar      <- ZIO.service[AppRepo]
      clk     <- ZIO.service[Clock]
      blr     <- ZIO.service[BlocklistRepo]
      metrics <- RouterMetricsService.make
      reg     <- RouterWsRegistry.make
      auth   = new RouterAuthLive(rRepo)
      ingest = new RouterIngestService(rRepo, tRepo, tu, dRepo, cRepo, aRepo, hsr)
      // #1849: a PolicyService so the ws endpoint can push the current snapshot on connect. Cache
      // off here (the `apply` default) — the connect push reads `snapshot`, which builds fresh.
      policy = PolicyServiceLive(pRepo, hsr, tlr, atlr, dRepo, blr, tRepo, er, ar, clk)
      // Mount the ws route through the SAME aspect stack `Main` wraps the router routes in
      // (HttpMetrics.instrument → LoggingMiddleware.annotate → Readiness.gate → ErrorBoundary.observe)
      // so the test proves the HTTP/1.1 upgrade survives the production middleware, not just the raw
      // route. These aspects are response-transparent for a websocket Response, but pinning it here
      // closes the gap between the test path and the assembled prod path.
      raw    = RouterWsRoutes.routes(auth, reg, ingest, metrics, rRepo, policy)
      routes = HttpMetrics.instrument(
        LoggingMiddleware.annotate(
          Readiness.gate(ErrorBoundary.observe(raw), ZIO.succeed(true)),
        ),
      )
    } yield (routes, reg)

  /**
   * Open a ws connection to the bound server, drive it with `send` on handshake, and collect every
   * text frame the server sends. Resolves once a frame satisfies `until` (so a test can wait for
   * the specific `ack`/`pong`/`policy` it cares about, ignoring the others — #1849's connect push
   * means a `policy` frame now arrives first, ahead of any ack/pong). Returns the matching frame,
   * ALL frames received up to that point, and the probe result. Connects with the given bearer
   * header.
   */
  private def connectAndCapture[B](
      port: Int,
      bearer: Option[String],
      send: WebSocketChannel => ZIO[Any, Throwable, Unit],
      probe: ZIO[Client, Throwable, B],
      until: String => Boolean = _ => true,
  ): ZIO[Client, Throwable, (String, Chunk[String], B)] =
    for {
      matched <- Promise.make[Nothing, String]
      frames  <- Ref.make(Chunk.empty[String])
      app     = Handler.webSocket { channel =>
        channel.receiveAll {
          case ChannelEvent.UserEventTriggered(UserEvent.HandshakeComplete) =>
            send(channel)
          case ChannelEvent.Read(WebSocketFrame.Text(t))                    =>
            frames.update(_ :+ t) *> ZIO.when(until(t))(matched.succeed(t)).unit
          case _                                                            =>
            ZIO.unit
        }
      }
      headers = bearer.fold(Headers.empty)(t => Headers(Header.Authorization.Bearer(t)))
      // Drive the connection in a LOCAL scope closed as soon as we have the server's reply, so the
      // ws fiber is interrupted promptly and the test does not wait on connection teardown. `probe`
      // runs INSIDE the scope (connection still open) so a registry-liveness check observes the live
      // channel rather than racing the post-scope deregister.
      result <- ZIO.scoped {
        for {
          _   <- app.connect(s"ws://localhost:$port/api/router/ws", headers).forkScoped
          t   <- matched.await
            .timeoutFail(new RuntimeException("no matching server frame within 30s"))(30.seconds)
          all <- frames.get
          b   <- probe
        } yield (t, all, b)
      }
    } yield result

  /**
   * Poll the registry's live channel count until it settles on `target` (register/deregister are
   * driven by the server-side ws fiber, so they race the client's view of the connection). Returns
   * `-1` on timeout so a stuck count fails the assertion loudly instead of hanging to the suite
   * timeout.
   */
  private def awaitActiveCount(reg: RouterWsRegistry, target: Int): UIO[Int] =
    reg.activeCount
      .repeat(zio.Schedule.spaced(50.millis) && zio.Schedule.recurUntil[Int](_ == target))
      .map(_._2)
      .timeout(15.seconds)
      .map(_.getOrElse(-1))

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
          until = _.contains("\"op\":\"ack\""),
        )
        (ack, all, connected) = result
        rows <- tRepo.listForRouter(id, 100)
      } yield assertTrue(ack.contains("\"op\":\"ack\"")) &&
        assertTrue(ack.contains("\"status\":\"ok\"")) &&
        assertTrue(ack.contains("\"seq\":7")) &&
        // #1849: the connect-time policy push arrives before the ack.
        assertTrue(all.exists(_.contains("\"op\":\"policy\""))) &&
        assertTrue(rows.size == 1) &&
        assertTrue(connected)).provideSome[
        TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task],
      ](Server.defaultWithPort(0), Client.default)
    },
    test("#2268: a usage frame SPLIT across Text(non-final)+Continuation frames is reassembled") {
      // The mirror of #1959: an intermediary (Render's edge) fragments a large frame at ~4 KiB, so a
      // single logical `usage` message arrives as `Text(isFinal=false)` + `Continuation…`. Before the
      // reassembler, the server decoded only the truncated first fragment ("Unexpected end of input")
      // and ingested nothing. Here we send the identical usage message as THREE ws frames and assert
      // it still acks ok and lands exactly one traffic_report row.
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
        frame     = s"""{"op":"usage","seq":9,"payload":$usageBody}"""
        // Fragment the message into a lead Text(fin=false) + two Continuation frames (the last
        // fin=true), the RFC 6455 §5.4 shape an intermediary produces. Bytes are UTF-8 (ASCII here).
        thirds    = frame.grouped(math.max(1, frame.length / 3)).toList
        bytesOf   = (s: String) =>
          Chunk.fromArray(s.getBytes(java.nio.charset.StandardCharsets.UTF_8))
        result <- connectAndCapture(
          port,
          Some(tk),
          ch =>
            ch.send(ChannelEvent.read(WebSocketFrame.Text(thirds.head, isFinal = false))) *>
              ZIO.foreachDiscard(thirds.tail.init)(part =>
                ch.send(
                  ChannelEvent.read(WebSocketFrame.Continuation(bytesOf(part), isFinal = false)),
                ),
              ) *>
              ch.send(
                ChannelEvent.read(WebSocketFrame.Continuation(bytesOf(thirds.last), isFinal = true)),
              ),
          reg.isConnected(id),
          until = _.contains("\"op\":\"ack\""),
        )
        (ack, _, _) = result
        rows <- tRepo.listForRouter(id, 100)
      } yield assertTrue(ack.contains("\"op\":\"ack\"")) &&
        assertTrue(ack.contains("\"status\":\"ok\"")) &&
        assertTrue(ack.contains("\"seq\":9")) &&
        // The reassembled body ingested exactly one row — proof the continuation frames were rejoined.
        assertTrue(rows.size == 1)).provideSome[
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
          until = _.contains("\"op\":\"pong\""),
        )
        pong = result._1
      } yield assertTrue(pong.contains("\"op\":\"pong\""))).provideSome[
        TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task],
      ](Server.defaultWithPort(0), Client.default)
    },
    test("#1849: a freshly-connected router is pushed the current policy snapshot on connect") {
      (for {
        _           <- cleanDb
        rRepo       <- ZIO.service[RouterRepo]
        pRepo       <- ZIO.service[ProfileRepo]
        dRepo       <- ZIO.service[DeviceRepo]
        _           <- seedKnownDevice(dRepo, pRepo)
        (_, tk)     <- seedRouter(rRepo)
        (routes, _) <- buildWsRoutes
        port        <- Server.install(routes)
        // Send nothing; just wait for the server's unsolicited first-policy push.
        result      <- connectAndCapture(
          port,
          Some(tk),
          _ => ZIO.unit,
          ZIO.unit,
          until = _.contains("\"op\":\"policy\""),
        )
        policyFrame = result._1
      } yield
      // The pushed frame is the `{op:"policy", payload:<snapshot>}` envelope carrying the real
      // snapshot — it must include the device the snapshot enumerates and an etag.
      assertTrue(policyFrame.contains("\"op\":\"policy\"")) &&
        assertTrue(policyFrame.contains("\"etag\"")) &&
        assertTrue(policyFrame.contains(knownMac))).provideSome[
        TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task],
      ](Server.defaultWithPort(0), Client.default)
    },
    test("#2561: a second connection for the same router supersedes the first (registry holds 1)") {
      // The prod leak: a router whose socket went half-open reconnects, the server still holds the
      // dead channel, and `router_ws_connections_active` reads 2 for one router — permanently, since
      // the stale channel's teardown never fires. Here both sockets are real and concurrently open
      // against the real endpoint; the registry must still report exactly ONE channel for the router,
      // and zero once the live one closes.
      (for {
        _             <- cleanDb
        rRepo         <- ZIO.service[RouterRepo]
        pRepo         <- ZIO.service[ProfileRepo]
        dRepo         <- ZIO.service[DeviceRepo]
        _             <- seedKnownDevice(dRepo, pRepo)
        (id, tk)      <- seedRouter(rRepo)
        (routes, reg) <- buildWsRoutes
        port          <- Server.install(routes)
        // Nest the two connections so BOTH are open at the same time when we sample the registry —
        // the first is still in scope while the second connects, exactly the overlap that leaked.
        counts        <- connectAndCapture(
          port,
          Some(tk),
          _ => ZIO.unit,
          connectAndCapture(
            port,
            Some(tk),
            _ => ZIO.unit,
            // Sampled with both sockets open. Poll: the server-side register for the second
            // connection races the client-side handshake completing.
            awaitActiveCount(reg, 1),
            until = _.contains("\"op\":\"policy\""),
          ).map(_._3),
          until = _.contains("\"op\":\"policy\""),
        ).map(_._3)
        // Both scopes closed: every channel has torn down, so the gauge source is back to zero.
        afterTeardown <- awaitActiveCount(reg, 0)
      } yield assertTrue(counts == 1) && assertTrue(afterTeardown == 0)).provideSome[
        TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task],
      ](Server.defaultWithPort(0), Client.default)
    },
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(90.seconds)
}
