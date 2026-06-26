package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.WsConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import doobie.*
import zio.{Clock as _, *}
import zio.http.*
import zio.http.ChannelEvent.UserEvent
import zio.json.*
import zio.json.ast.Json
import zio.test.*

import java.time.{Instant, LocalDateTime}

/**
 * #1971 (S4 of the SPA-websocket rollout, design `docs/design/spa-websocket.md` §5.3): the
 * `trafficUsage` live-edge aggregator. Exercises the FULL chain end to end — a real [[Server]] + ws
 * [[Client]], the forked [[SpaPush]] consumer draining the [[SpaEventBus]], the REAL
 * [[RouterIngestService]] usage write site over an embedded Postgres (never mocked, real
 * `traffic_reports` rows), AND the REAL `GET /api/usage/traffic` mounted alongside — proving:
 *
 *   - a usage ingest pushes ONLY the current/most-recent bucket as a `TrafficUsageResponse`, and
 *     the streamed bucket is byte-identical to what the `GET` returns for that window (SSOT — both
 *     run the one `UsageTrafficQuery.aggregate`);
 *   - no subscriber for a param-set → no push (and, structurally, no query);
 *   - a `raw`-bucket subscription pushes per ingest cycle, still aggregated by the subscription's
 *     `groupBy` (one profile point, not ungrouped per-host `rawRows`) (§1.3);
 *   - a burst of ingests converges on the freshest cumulative head (latest-wins, §6.3), and the
 *     drain-loop coalesce collapses the contentless burst triggers to one recompute;
 *   - role-gating: `trafficUsage` is admin/adult-only, so a child's subscribe is `ack`-rejected and
 *     no frame is ever pushed (admin/adult are full-visibility, so one body serves all recipients —
 *     the per-profile role filter is the visibleTo gate, exactly as `now`).
 *
 * The injected [[Clock]] is fixed at 14:00:30 — deliberately OFF a 1m boundary so the head window
 * `[floor(now,1m)=14:00:00, now=14:00:30)` is non-empty and the seeded period-14:00:00 row lands in
 * it.
 */
object SpaWsS4Spec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  // 14:00:30 — off the 1m/5m boundary so the current head bucket window is non-empty.
  private val testClockAt: LocalDateTime = LocalDateTime.of(2026, 6, 25, 14, 0, 30)

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(testClockAt)

  private val jwtCfg  = JwtConfig(secret = "test-secret-at-least-32-chars!!", expiryHours = 1)
  private val openCfg = WsConfig(allowedOrigins = "", expiryCheckSeconds = 60)
  private val cleanDb = TestDatabase.cleanAndMigrate

  private val knownMac    = "aa:bb:cc:11:22:33"
  // Usage rows are dated inside the current 1m head window [14:00:00, 14:00:30).
  private val periodStart = "2026-06-25T14:00:00Z"
  private val periodEnd   = "2026-06-25T14:00:30Z"
  // The head-window query the push runs and the GET we compare against use the same explicit window.
  private val headFrom    = "2026-06-25T14:00:00Z"
  private val headTo      = "2026-06-25T14:00:30Z"

  private def makeAuth(clock: Clock): URIO[UserRepo, AuthServiceLive] =
    ZIO.serviceWith[UserRepo](ur => AuthServiceLive(ur, jwtCfg, clock))

  private def tokenFor(
      auth: AuthService,
      role: String,
      username: String,
  ): ZIO[UserRepo, Throwable, String] =
    for {
      hash <- auth.hashPassword("pass")
      _    <- ZIO.serviceWithZIO[UserRepo](_.create(username, hash, role))
      tok  <- auth.login(username, "pass").mapError(e => new RuntimeException(s"login: $e"))
    } yield tok.token.value

  // The default admin (`changeme`) is the one user without the force-change-password flag, so it is
  // the bearer the REST `GET /api/usage/traffic` accepts (it runs `requirePasswordChanged`; the ws
  // path uses bare `verify`, so a freshly-created adult works there but not for the GET).
  private def adminToken(auth: AuthService): ZIO[Any, Throwable, String] =
    auth
      .login("admin", "changeme")
      .mapError(e => new RuntimeException(s"login: $e"))
      .map(_.token.value)

  private def seedRouter: ZIO[RouterRepo, Throwable, Router] =
    for {
      rRepo  <- ZIO.service[RouterRepo]
      id     <- rRepo.create("test-router", Sha256Hex.unsafe("m" * 64))
      _      <- rRepo.completeEnrollment(
        id,
        Sha256Hex.unsafe(RouterAuth.sha256Hex("ROUTER_TOKEN_PLAIN")),
      )
      router <- rRepo.findById(id).someOrFail(new RuntimeException("router not found after seed"))
    } yield router

  private def seedDevice: ZIO[DeviceRepo & ProfileRepo, Throwable, Unit] =
    for {
      pRepo <- ZIO.service[ProfileRepo]
      dRepo <- ZIO.service[DeviceRepo]
      pid   <- pRepo.create("Kids", List(BlocklistId.unsafe("adult")))
      _     <- dRepo.upsert(MacAddress.unsafe(knownMac), "kid-ipad", Some(pid), "192.168.1.10")
    } yield ()

  private def usageRecord(host: String, bytesIn: Long, bytesOut: Long): UsageRecord =
    UsageRecord(
      mac = MacAddress.unsafe(knownMac),
      ip = Some(IpAddress.unsafe("192.168.1.10")),
      host = HostId.Fqdn(Hostname.unsafe(host)),
      activeSeconds = 30L,
      bytesIn = bytesIn,
      bytesOut = bytesOut,
    )

  private def ingestUsage(
      ingest: RouterIngestService,
      router: Router,
      records: UsageRecord*,
  ): Task[Unit] =
    ingest
      .ingestUsage(
        router,
        UsageReport(router.id, periodStart, periodEnd, records.toList).toJson,
      )
      .mapError(e => new RuntimeException(s"ingest: $e"))

  private def pushCount(op: String, result: String): UIO[Double] =
    zio.metrics.Metric
      .counter("spa_ws_push_total")
      .tagged("op", op)
      .tagged("result", result)
      .value
      .map(_.count)

  /**
   * Connect a ws client, send `hello` + the subscribe frames, await the subscribe `ack`, run the
   * trigger, then COLLECT every frame for `wait` (we don't stop at the first match — the
   * latest-wins test needs the last `trafficUsage` frame). Returns all received frames.
   */
  private def collect(
      port: Int,
      token: String,
      subscribeFrames: List[String],
      trigger: Task[Unit],
      wait: Duration,
      awaitAck: Boolean = true,
  ): ZIO[Client, Throwable, Chunk[String]] =
    for {
      ackP   <- Promise.make[Nothing, Unit]
      frames <- Ref.make(Chunk.empty[String])
      app = Handler.webSocket { channel =>
        channel.receiveAll {
          case ChannelEvent.UserEventTriggered(UserEvent.HandshakeComplete) =>
            ZIO.foreachDiscard("""{"op":"hello"}""" :: subscribeFrames)(f =>
              channel.send(ChannelEvent.read(WebSocketFrame.text(f))),
            )
          case ChannelEvent.Read(WebSocketFrame.Text(t))                    =>
            frames.update(_ :+ t) *>
              ZIO.when(t.contains("\"op\":\"ack\""))(ackP.succeed(())).unit
          case _                                                            =>
            ZIO.unit
        }
      }
      out <- ZIO.scoped {
        for {
          _   <- app
            .connect(s"ws://localhost:$port/api/ws", Headers("Cookie", s"wh_ws=$token"))
            .forkScoped
          _   <- ZIO.when(awaitAck)(
            ackP.await.timeoutFail(new RuntimeException("no subscribe ack"))(20.seconds),
          )
          _   <- trigger
          _   <- ZIO.sleep(wait)
          all <- frames.get
        } yield all
      }
    } yield out

  /**
   * Hit the real `GET /api/usage/traffic` and parse the body (the SSOT authority for the stream).
   */
  private def getTraffic(
      port: Int,
      token: String,
      query: String,
  ): ZIO[Client, Throwable, TrafficUsageResponse] =
    ZIO.serviceWithZIO[Client] { client =>
      ZIO
        .scoped(
          client
            .request(
              Request
                .get(s"http://localhost:$port/api/usage/traffic?$query")
                .addHeader(Header.Authorization.Bearer(token)),
            )
            .flatMap(_.body.asString),
        )
        .flatMap(body =>
          ZIO
            .fromEither(body.fromJson[TrafficUsageResponse])
            .mapError(e => new RuntimeException(s"parse traffic ($e): $body")),
        )
    }

  private def trafficFrames(frames: Chunk[String]): Chunk[String] =
    frames.filter(_.contains("\"op\":\"trafficUsage\""))

  /** Parse the `payload` of a `trafficUsage` frame back into a `TrafficUsageResponse`. */
  private def parsePush(frame: String): Either[String, TrafficUsageResponse] =
    frame.fromJson[Json].flatMap {
      case Json.Obj(fields) =>
        fields
          .collectFirst { case (k, v) if k == "payload" => v }
          .toRight("no payload")
          .flatMap(_.toJson.fromJson[TrafficUsageResponse])
      case _                => Left("frame not an object")
    }

  private type BootstrapEnv =
    TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]

  private def withHarness[A](
      body: (Int, RouterIngestService, Router) => ZIO[Client & BootstrapEnv, Throwable, A],
  ): ZIO[BootstrapEnv, Throwable, A] =
    (for {
      _           <- cleanDb
      clock       <- ZIO.service[Clock]
      auth        <- makeAuth(clock)
      trafficRepo <- ZIO.service[TrafficReportRepo]
      rollupRepo  <- ZIO.service[RollupRepo]
      connRepo    <- ZIO.service[ConnectionEventRepo]
      deviceRepo  <- ZIO.service[DeviceRepo]
      profileRepo <- ZIO.service[ProfileRepo]
      appRepo     <- ZIO.service[AppRepo]
      atlRepo     <- ZIO.service[AppTimeLimitRepo]
      usageRepo   <- ZIO.service[TimeUsageRepo]
      alertRepo   <- ZIO.service[AlertRepo]
      hsRepo      <- ZIO.service[HouseholdSettingsRepo]
      upRepo      <- ZIO.service[UserProfileRepo]
      appUsedRepo <- ZIO.service[AppUsedRollupRepo]
      routerRepo  <- ZIO.service[RouterRepo]
      _           <- seedDevice
      router      <- seedRouter
      reg         <- SpaWsRegistry.make
      bus         <- SpaEventBus.make
      ingest = new RouterIngestService(
        routerRepo,
        trafficRepo,
        usageRepo,
        deviceRepo,
        connRepo,
        alertRepo,
        hsRepo,
        bus,
      )
      routes = SpaWsRoutes.routes(auth, reg, clock, openCfg) ++
        UsageRoutes.routes(
          auth,
          deviceRepo,
          trafficRepo,
          upRepo,
          profileRepo,
          appRepo,
          rollupRepo,
          hsRepo,
          atlRepo,
          appUsedRepo,
          clock,
        )
      port <- Server.install(routes)
      out  <- ZIO.scoped {
        SpaPush.run(
          bus,
          reg,
          trafficRepo,
          rollupRepo,
          connRepo,
          deviceRepo,
          profileRepo,
          appRepo,
          atlRepo,
          clock,
        ) *> body(port, ingest, router)
      }
    } yield out).provideSome[BootstrapEnv](Server.defaultWithPort(0), Client.default)

  def spec = suite("SPA websocket S4 trafficUsage aggregator (#1971)")(
    test(
      "trafficUsage{groupBy:[profile],bucket:1m} gets ONLY the current bucket, equal to the GET (SSOT)",
    ) {
      withHarness { (port, ingest, router) =>
        for {
          tok    <- ZIO.serviceWithZIO[Clock](makeAuth).flatMap(adminToken)
          before <- pushCount("trafficUsage", "ok")
          frames <- collect(
            port,
            tok,
            List(
              """{"op":"subscribe","payload":{"topic":"trafficUsage","params":{"groupBy":["profile"],"bucket":"1m"}}}""",
            ),
            trigger = ingestUsage(ingest, router, usageRecord("youtube.com", 1000, 2000)),
            wait = 4.seconds,
          )
          after  <- pushCount("trafficUsage", "ok")
          tFrames = trafficFrames(frames)
          pushed <- ZIO.fromEither(parsePush(tFrames.last)).mapError(e => new RuntimeException(e))
          got    <- getTraffic(
            port,
            tok,
            s"bucket=1m&groupBy=profile&from=$headFrom&to=$headTo&tz=UTC",
          )
        } yield assertTrue(tFrames.nonEmpty) &&
          assertTrue(after - before >= 1.0) &&
          // Only the current bucket — one windowStart, == floor(now,1m).
          assertTrue(
            pushed.aggregateRows.map(_.windowStart).distinct == List("2026-06-25T14:00:00Z"),
          ) &&
          assertTrue(
            pushed.aggregateRows.exists(r => r.totalBytesIn == 1000 && r.totalBytesOut == 2000),
          ) &&
          // SSOT: the streamed bucket is byte-identical to the GET's head bucket.
          assertTrue(pushed.aggregateRows.toSet == got.aggregateRows.toSet) &&
          assertTrue(pushed.bucket == "1m" && pushed.groupBy == List("profile")) &&
          // It is the AGGREGATE shape (per-group rows), never raw per-host rows.
          assertTrue(pushed.rawRows.isEmpty)
      }
    },
    test("no trafficUsage subscriber → no trafficUsage push (and no query) on usage ingest") {
      withHarness { (port, ingest, router) =>
        for {
          tok    <- ZIO.serviceWithZIO[Clock](makeAuth).flatMap(a => tokenFor(a, "adult", "mom"))
          before <- pushCount("trafficUsage", "ok")
          // Subscribe to `now` only — a real connection that doesn't watch bandwidth.
          frames <- collect(
            port,
            tok,
            List("""{"op":"subscribe","payload":{"topic":"now"}}"""),
            trigger = ingestUsage(ingest, router, usageRecord("youtube.com", 1000, 2000)),
            wait = 4.seconds,
          )
          after  <- pushCount("trafficUsage", "ok")
        } yield assertTrue(trafficFrames(frames).isEmpty) && assertTrue(after == before)
      }
    },
    test(
      "raw-bucket subscription pushes the live edge per ingest, aggregated by groupBy (not rawRows)",
    ) {
      withHarness { (port, ingest, router) =>
        for {
          tok    <- ZIO.serviceWithZIO[Clock](makeAuth).flatMap(a => tokenFor(a, "adult", "mom"))
          frames <- collect(
            port,
            tok,
            List(
              """{"op":"subscribe","payload":{"topic":"trafficUsage","params":{"groupBy":["profile"],"bucket":"raw"}}}""",
            ),
            trigger = ingestUsage(ingest, router, usageRecord("youtube.com", 500, 600)),
            wait = 4.seconds,
          )
          tFrames = trafficFrames(frames)
          pushed <- ZIO.fromEither(parsePush(tFrames.last)).mapError(e => new RuntimeException(e))
        } yield assertTrue(tFrames.nonEmpty) &&
          assertTrue(pushed.bucket == "raw") &&
          // Aggregated per groupBy at the ingest-period granularity — ONE profile point, not rawRows.
          assertTrue(pushed.rawRows.isEmpty) &&
          assertTrue(pushed.aggregateRows.size == 1) &&
          assertTrue(
            pushed.aggregateRows.exists(r => r.totalBytesIn == 500 && r.totalBytesOut == 600),
          ) &&
          // raw floors to the 5-min boundary.
          assertTrue(pushed.aggregateRows.head.windowStart == "2026-06-25T14:00:00Z")
      }
    },
    test(
      "a burst of ingests converges on the freshest cumulative head bucket (latest-wins, §6.3)",
    ) {
      withHarness { (port, ingest, router) =>
        for {
          tok    <- ZIO.serviceWithZIO[Clock](makeAuth).flatMap(a => tokenFor(a, "adult", "mom"))
          frames <- collect(
            port,
            tok,
            List(
              """{"op":"subscribe","payload":{"topic":"trafficUsage","params":{"groupBy":["profile"],"bucket":"1m"}}}""",
            ),
            // Three distinct hosts, same period → three traffic_reports rows summed into one profile row.
            trigger = ingestUsage(ingest, router, usageRecord("a.com", 100, 0)) *>
              ingestUsage(ingest, router, usageRecord("b.com", 200, 0)) *>
              ingestUsage(ingest, router, usageRecord("c.com", 300, 0)),
            wait = 6.seconds,
          )
          tFrames = trafficFrames(frames)
          last <- ZIO.fromEither(parsePush(tFrames.last)).mapError(e => new RuntimeException(e))
        } yield assertTrue(tFrames.nonEmpty) &&
          // The freshest head reflects ALL ingested bytes (cumulative DB state at the last push).
          assertTrue(last.aggregateRows.map(_.totalBytesIn).sum == 600L) &&
          assertTrue(last.aggregateRows.map(_.windowStart).distinct == List("2026-06-25T14:00:00Z"))
      }
    },
    test(
      "coalesce collapses a burst of contentless triggers to one recompute each (latest-wins logic)",
    ) {
      val t1    = Instant.parse("2026-06-25T13:59:00Z")
      val t2    = Instant.parse("2026-06-25T13:59:30Z")
      val burst = Chunk(
        SpaEvent.NowChanged,
        SpaEvent.UsageIngested,
        SpaEvent.UsageIngested,
        SpaEvent.NowChanged,
        SpaEvent.UsageIngested,
        SpaEvent.ConnectionEventsIngested(t1),
        SpaEvent.ConnectionEventsIngested(t1),
        SpaEvent.ConnectionEventsIngested(t2),
      )
      val out   = SpaPush.coalesce(burst)
      ZIO.succeed(
        assertTrue(out.count(_ == SpaEvent.UsageIngested) == 1) &&
          assertTrue(out.count(_ == SpaEvent.NowChanged) == 1) &&
          // Distinct `since`d connection-event events are NOT collapsed (their head re-read differs).
          assertTrue(out.count(_ == SpaEvent.ConnectionEventsIngested(t1)) == 1) &&
          assertTrue(out.count(_ == SpaEvent.ConnectionEventsIngested(t2)) == 1),
      )
    },
    test(
      "role gate: a child's trafficUsage subscribe is acked reject and no frame is ever pushed",
    ) {
      withHarness { (port, ingest, router) =>
        for {
          childTok <- ZIO.serviceWithZIO[Clock](makeAuth).flatMap(a => tokenFor(a, "child", "kid"))
          frames   <- collect(
            port,
            childTok,
            List(
              """{"op":"subscribe","payload":{"topic":"trafficUsage","params":{"groupBy":["profile"],"bucket":"1m"}}}""",
            ),
            trigger = ingestUsage(ingest, router, usageRecord("youtube.com", 1000, 2000)),
            wait = 4.seconds,
          )
        } yield assertTrue(trafficFrames(frames).isEmpty) &&
          assertTrue(
            frames.exists(f =>
              f.contains("\"op\":\"ack\"") && f.contains("\"topic\":\"trafficUsage\"") &&
                f.contains("\"status\":\"reject\""),
            ),
          )
      }
    },
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(120.seconds)
}
