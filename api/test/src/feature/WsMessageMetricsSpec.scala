package wifihaven.api.feature

import wifihaven.api.{ErrorBoundary, MetricsConfig, Readiness}
import wifihaven.api.db.*
import wifihaven.api.metrics.{AppMetrics, HttpMetrics, MetricsRuntime, RouterMetricsService}
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
import zio.metrics.connectors.prometheus.PrometheusPublisher
import zio.test.*

import java.time.{Instant, LocalDateTime}

/**
 * #2168: per-ws-message-process latency (`router_ws_message_duration_seconds` /
 * `spa_ws_message_duration_seconds`) — the ws analog of `http_request_duration_seconds`. After the
 * #1023 cutover every logical operation is a frame on ONE persistent connection, so the
 * per-HTTP-route duration histogram no longer observes it; without this series we would go blind to
 * slow-op regressions (the class that caught `/api/usage/traffic`) once the REST poll is removed
 * (#1850).
 *
 * The router test drives the REAL `/api/router/ws` handler end to end (a real [[Server]] + ws
 * [[Client]], real repos, no mocks) so the assertion covers the whole path: dispatch → shared
 * ingest → the timing wrapper. It then scrapes the live Prometheus publisher (the same `GET
 * /metrics` path prod scrapes) and asserts the histogram emitted with the BOUNDED `op`/`direction`
 * labels and NO per-mac / per-router high-cardinality label — the §4 cardinality firewall. The SPA
 * test locks the `spa_ws_message_duration_seconds` allowlist entry + bounded labels through the
 * same firewall ([[AppMetrics.recordSpaWsMessageDuration]] is the exact call the [[SpaWsRoutes]] /
 * [[SpaWsRegistry]] wiring makes).
 */
object WsMessageMetricsSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  private val testClockAt: LocalDateTime = LocalDateTime.of(2026, 5, 7, 14, 0, 0)

  override val bootstrap = TestDatabase.layer ++ TestLayers.withClock(testClockAt)

  private val cleanDb = TestDatabase.cleanAndMigrate

  // Poll interval for the Prometheus snapshot fiber; the test advances the zio TestClock by exactly
  // this much to drive one deterministic scrape (the #2042 pattern from MetricsSelfMetricsSpec).
  private val pollInterval = 100.millis

  private val tickPublisher: UIO[Unit] =
    zio.test.TestClock.sleeps.repeatUntil(_.nonEmpty) *> zio.test.TestClock.adjust(pollInterval)

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
      policy = PolicyServiceLive(pRepo, hsr, tlr, atlr, dRepo, blr, tRepo, er, ar, clk)
      raw    = RouterWsRoutes.routes(auth, reg, ingest, metrics, rRepo, policy)
      routes = HttpMetrics.instrument(
        LoggingMiddleware.annotate(
          Readiness.gate(ErrorBoundary.observe(raw), ZIO.succeed(true)),
        ),
      )
    } yield (routes, reg)

  /** Open a ws connection, drive it with `send` on handshake, and collect frames until `until`. */
  private def connectAndCapture(
      port: Int,
      bearer: Option[String],
      send: WebSocketChannel => ZIO[Any, Throwable, Unit],
      until: String => Boolean,
  ): ZIO[Client, Throwable, Chunk[String]] =
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
      all <- ZIO.scoped {
        for {
          _   <- app.connect(s"ws://localhost:$port/api/router/ws", headers).forkScoped
          _   <- matched.await
            .timeoutFail(new RuntimeException("no matching server frame within 30s"))(30.seconds)
          all <- frames.get
        } yield all
      }
    } yield all

  private def scrape: ZIO[PrometheusPublisher, Response, String] =
    for {
      pub <- ZIO.service[PrometheusPublisher]
      routes = MetricsRoutes.routes(MetricsConfig(enabled = true), pub)
      resp <- routes(Request.get("/metrics"))
      body <- resp.body.asString.orElseFail(
        Response.internalServerError("scrape body decode failed"),
      )
    } yield body

  private def seriesLines(body: String, metric: String): List[String] =
    body.linesIterator.filter(l => !l.startsWith("#") && l.startsWith(metric)).toList

  def spec = suite("Per-ws-message-process latency metrics (#2168)")(
    test(
      "driving real router ws frames (usage + ping) emits router_ws_message_duration_seconds{op,direction=in} with bounded labels and no per-mac/router dimension",
    ) {
      (for {
        _           <- cleanDb
        rRepo       <- ZIO.service[RouterRepo]
        pRepo       <- ZIO.service[ProfileRepo]
        dRepo       <- ZIO.service[DeviceRepo]
        _           <- seedKnownDevice(dRepo, pRepo)
        (id, tk)    <- seedRouter(rRepo)
        (routes, _) <- buildWsRoutes
        port        <- Server.install(routes)
        rec        = UsageRecord(
          MacAddress.unsafe(knownMac),
          Some(IpAddress.unsafe("192.168.1.10")),
          HostId.Fqdn(Hostname.unsafe("youtube.com")),
          240L,
          1000L,
          500L,
        )
        usageBody  = UsageReport(id, periodStart.toString, periodEnd.toString, List(rec)).toJson
        usageFrame = s"""{"op":"usage","seq":7,"payload":$usageBody}"""
        // Send a usage frame, wait for its ack, then a ping, wait for the pong.
        _    <- connectAndCapture(
          port,
          Some(tk),
          ch => ch.send(ChannelEvent.read(WebSocketFrame.text(usageFrame))),
          until = _.contains("\"op\":\"ack\""),
        )
        _    <- connectAndCapture(
          port,
          Some(tk),
          ch => ch.send(ChannelEvent.read(WebSocketFrame.text("""{"op":"ping"}"""))),
          until = _.contains("\"op\":\"pong\""),
        )
        _    <- tickPublisher
        body <- scrape.catchAll(resp => resp.body.asString.orDie)
        durLines   = seriesLines(body, "router_ws_message_duration_seconds")
        frameLines = seriesLines(body, "router_ws_frames_total")
      } yield
      // The new latency histogram is present with the bounded op/direction labels.
      assertTrue(body.contains("router_ws_message_duration_seconds")) &&
        assertTrue(
          durLines.exists(l => l.contains("""op="usage"""") && l.contains("""direction="in"""")),
        ) &&
        assertTrue(
          durLines.exists(l => l.contains("""op="ping"""") && l.contains("""direction="in"""")),
        ) &&
        // The success/error counter (the ws analog of http_requests_total{status}) is present too.
        assertTrue(
          frameLines.exists(l => l.contains("""op="usage"""") && l.contains("""result="ok"""")),
        ) &&
        // §4 cardinality firewall: no per-mac / per-router / per-host dimension on the histogram.
        assertTrue(!durLines.exists(_.contains(knownMac))) &&
        assertTrue(!durLines.exists(_.contains("mac="))) &&
        assertTrue(!durLines.exists(_.contains("router_id="))) &&
        assertTrue(!durLines.exists(_.contains("host=")))).provideSome[
        TestDatabase.AllRepos & EmbeddedPostgres & Clock & PrometheusPublisher,
      ](Server.defaultWithPort(0), Client.default)
    },
    test(
      "recordSpaWsMessageDuration emits spa_ws_message_duration_seconds{op,direction} for inbound and outbound ops through the cardinality firewall",
    ) {
      for {
        // The exact calls the SpaWsRoutes (in) / SpaWsRegistry (out) wiring makes.
        _    <- AppMetrics.recordSpaWsMessageDuration("subscribe", "in", 0.012)
        _    <- AppMetrics.recordSpaWsMessageDuration("now", "out", 0.004)
        _    <- tickPublisher
        body <- scrape.catchAll(resp => resp.body.asString.orDie)
        lines = seriesLines(body, "spa_ws_message_duration_seconds")
      } yield assertTrue(body.contains("spa_ws_message_duration_seconds")) &&
        assertTrue(
          lines.exists(l => l.contains("""op="subscribe"""") && l.contains("""direction="in"""")),
        ) &&
        assertTrue(
          lines.exists(l => l.contains("""op="now"""") && l.contains("""direction="out"""")),
        ) &&
        // Bounded labels only — no per-session / per-profile dimension leaked in.
        assertTrue(!lines.exists(_.contains("session="))) &&
        assertTrue(!lines.exists(_.contains("profile_id=")))
    },
  ).provideSomeLayer[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]](
    MetricsRuntime.prometheus(pollInterval),
  ) @@ TestAspect.sequential
}
