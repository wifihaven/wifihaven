package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.WsConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.policy.TimeStatusServiceLive
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

import java.time.LocalDateTime

/**
 * #1974 (S6a of the SPA-websocket rollout, design `docs/design/spa-websocket.md` §1.2/§3.1/§5.2):
 * the live time-usage pushes — `timeStatus` (per-profile used/remaining, the `/api/time/status`
 * `ProfileTimeStatus[]` body) and `appUsage` (per-app minutes, the
 * `/api/profiles/{id}/usage-by-app` body). Exercises the FULL chain end to end — a real [[Server]]
 * + ws [[Client]], the forked [[SpaPush]] consumer draining the [[SpaEventBus]], the REAL
 * [[RouterIngestService]] usage write site and the REAL `POST /api/time/extend` grant over an
 * embedded Postgres (never mocked), AND the REAL `GET /api/time/status` + `GET
 * /api/profiles/{id}/usage-by-app` mounted alongside — proving:
 *
 *   - crediting usage pushes a `timeStatus` body byte-identical to what the `GET` returns (SSOT —
 *     both run `TimeStatusService` + the one `assembleProfileTimeStatus` wire-shape builder);
 *   - a #1849 time-boundary tick (modelled as the publisher sink's `TimeStatusChanged` with NO new
 *     usage) still pushes a fresh `timeStatus`;
 *   - a `POST /api/time/extend` grant pushes a fresh `timeStatus` (the grant moves
 *     remaining-minutes);
 *   - an `appUsage{profileId}` subscriber gets a body byte-identical to the matching `GET` (SSOT);
 *   - per-role filtering: a child linked to ONE profile receives ONLY that profile's `timeStatus`,
 *     never a sibling's (design §4.4 — the same `UserProfileRepo` filter the GET uses);
 *   - no subscriber for `timeStatus`/`appUsage` → no push (and structurally no query).
 *
 * The injected [[Clock]] is fixed at 2026-06-25 14:00:30 (matching S4) so the seeded usage lands in
 * the household-local "today" both the push and the GET read.
 */
object SpaWsS6aSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  private val testClockAt: LocalDateTime = LocalDateTime.of(2026, 6, 25, 14, 0, 30)

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(testClockAt)

  private val jwtCfg  = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)
  private val openCfg = WsConfig(allowedOrigins = "", expiryCheckSeconds = 60)
  private val cleanDb = TestDatabase.cleanAndMigrate

  private val knownMac    = "aa:bb:cc:11:22:33"
  private val periodStart = "2026-06-25T14:00:00Z"
  private val periodEnd   = "2026-06-25T14:00:30Z"

  private def makeAuth(clock: Clock): URIO[UserRepo, AuthServiceLive] =
    ZIO.serviceWith[UserRepo](ur => AuthServiceLive(ur, jwtCfg, clock))

  private def tokenFor(
      auth: AuthService,
      role: String,
      username: String,
  ): ZIO[UserRepo, Throwable, (String, UserId)] =
    for {
      hash <- auth.hashPassword("pass")
      uid  <- ZIO.serviceWithZIO[UserRepo](_.create(username, hash, role))
      tok  <- auth.login(username, "pass").mapError(e => new RuntimeException(s"login: $e"))
    } yield (tok.token.value, uid)

  // The default admin (`changeme`) is the one user without the force-change-password flag, so it is
  // the bearer the REST GETs accept (`requireAuth` runs `requirePasswordChanged`); the ws path uses
  // bare `verify`, so a freshly-created child works there but not for the GET.
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

  // One profile "Kids" with a device + a daily limit, so used/remaining minutes are meaningful.
  private def seedKids: ZIO[DeviceRepo & ProfileRepo & TimeLimitRepo, Throwable, ProfileId] =
    for {
      pRepo <- ZIO.service[ProfileRepo]
      dRepo <- ZIO.service[DeviceRepo]
      tlr   <- ZIO.service[TimeLimitRepo]
      pid   <- pRepo.create("Kids", List(BlocklistId.unsafe("adult")))
      _     <- dRepo.upsert(MacAddress.unsafe(knownMac), "kid-ipad", Some(pid), "192.168.1.10")
      _     <- tlr.upsert(pid, 120)
    } yield pid

  private def usageRecord(host: String, secs: Long): UsageRecord =
    UsageRecord(
      mac = MacAddress.unsafe(knownMac),
      ip = Some(IpAddress.unsafe("192.168.1.10")),
      host = HostId.Fqdn(Hostname.unsafe(host)),
      activeSeconds = secs,
      bytesIn = 1000,
      bytesOut = 2000,
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
   * trigger, then COLLECT every frame for `wait` (latest-wins needs the last matching frame).
   */
  private def collect(
      port: Int,
      token: String,
      subscribeFrames: List[String],
      trigger: ZIO[Client, Throwable, Unit],
      wait: Duration,
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
          _   <- ackP.await.timeoutFail(new RuntimeException("no subscribe ack"))(20.seconds)
          _   <- trigger
          // Live clock (suite-level `withLiveClock`): this awaits a real push frame traversing
          // the actual Netty WS socket against a live server — not a timer the TestClock can
          // advance — so it stays a wall-clock settle.
          _   <- ZIO.sleep(wait)
          all <- frames.get
        } yield all
      }
    } yield out

  private def getStr(port: Int, token: String, path: String): ZIO[Client, Throwable, String] =
    ZIO.serviceWithZIO[Client] { client =>
      ZIO.scoped(
        client
          .request(
            Request
              .get(s"http://localhost:$port$path")
              .addHeader(Header.Authorization.Bearer(token)),
          )
          .flatMap(_.body.asString),
      )
    }

  private def framesOf(frames: Chunk[String], op: String): Chunk[String] =
    frames.filter(_.contains(s"\"op\":\"$op\""))

  private def payloadOf(frame: String): Either[String, Json] =
    frame.fromJson[Json].flatMap {
      case Json.Obj(fields) =>
        fields.collectFirst { case (k, v) if k == "payload" => v }.toRight("no payload")
      case _                => Left("frame not an object")
    }

  private def parseTimeStatus(frame: String): Either[String, List[ProfileTimeStatus]] =
    payloadOf(frame).flatMap(_.toJson.fromJson[List[ProfileTimeStatus]])

  private def parseAppUsage(frame: String): Either[String, ProfileUsageByApp] =
    payloadOf(frame).flatMap(_.toJson.fromJson[ProfileUsageByApp])

  private type BootstrapEnv =
    TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]

  private def withHarness[A](
      body: (
          Int,
          RouterIngestService,
          Router,
          SpaEventBus,
      ) => ZIO[Client & BootstrapEnv, Throwable, A],
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
      tlRepo      <- ZIO.service[TimeLimitRepo]
      extRepo     <- ZIO.service[TimeExtensionRepo]
      usageRepo   <- ZIO.service[TimeUsageRepo]
      alertRepo   <- ZIO.service[AlertRepo]
      hsRepo      <- ZIO.service[HouseholdSettingsRepo]
      upRepo      <- ZIO.service[UserProfileRepo]
      appUsedRepo <- ZIO.service[AppUsedRollupRepo]
      routerRepo  <- ZIO.service[RouterRepo]
      _           <- seedKids
      router      <- seedRouter
      reg         <- SpaWsRegistry.make
      bus         <- SpaEventBus.make
      timeStatus = new TimeStatusServiceLive(
        profileRepo,
        tlRepo,
        atlRepo,
        deviceRepo,
        trafficRepo,
        extRepo,
      )
      ingest     = new RouterIngestService(
        routerRepo,
        trafficRepo,
        usageRepo,
        deviceRepo,
        connRepo,
        alertRepo,
        hsRepo,
        bus,
      )
      routes     = SpaWsRoutes.routes(auth, reg, clock, openCfg) ++
        TimeRoutes.routes(
          auth,
          deviceRepo,
          tlRepo,
          atlRepo,
          trafficRepo,
          extRepo,
          profileRepo,
          upRepo,
          hsRepo,
          timeStatus,
          clock,
          spaBus = bus,
        ) ++
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
          Some(SpaPush.TimeUsageDeps(timeStatus, hsRepo, upRepo)),
        ) *> body(port, ingest, router, bus)
      }
    } yield out).provideSome[BootstrapEnv](Server.defaultWithPort(0), Client.default)

  private val subTimeStatus = """{"op":"subscribe","payload":{"topic":"timeStatus"}}"""
  private def subAppUsage(pid: ProfileId) =
    s"""{"op":"subscribe","payload":{"topic":"appUsage","params":{"profileId":${pid.value}}}}"""

  def spec = suite("SPA websocket S6a live time-usage push (#1974)")(
    test("crediting usage pushes a timeStatus body byte-identical to GET /api/time/status (SSOT)") {
      withHarness { (port, ingest, router, _) =>
        for {
          tok    <- ZIO.serviceWithZIO[Clock](makeAuth).flatMap(adminToken)
          before <- pushCount("timeStatus", "ok")
          frames <- collect(
            port,
            tok,
            List(subTimeStatus),
            trigger = ingestUsage(ingest, router, usageRecord("youtube.com", 120)),
            wait = 4.seconds,
          )
          after  <- pushCount("timeStatus", "ok")
          tFrames = framesOf(frames, "timeStatus")
          pushed  <- ZIO.fromEither(parseTimeStatus(tFrames.last)).mapError(new RuntimeException(_))
          getBody <- getStr(port, tok, "/api/time/status")
          got     <- ZIO
            .fromEither(getBody.fromJson[List[ProfileTimeStatus]])
            .mapError(e => new RuntimeException(s"parse GET ($e): $getBody"))
        } yield assertTrue(tFrames.nonEmpty) &&
          assertTrue(after - before >= 1.0) &&
          assertTrue(pushed.toSet == got.toSet) &&
          assertTrue(pushed.exists(_.profileName == "Kids"))
      }
    },
    test(
      "a #1849 boundary tick (TimeStatusChanged, no new usage) still pushes a fresh timeStatus",
    ) {
      withHarness { (port, ingest, router, bus) =>
        for {
          tok    <- ZIO.serviceWithZIO[Clock](makeAuth).flatMap(adminToken)
          // Seed some usage FIRST so used/remaining are non-trivial, then the boundary tick fires
          // with NO new usage — exactly the schedule-edge / cap-exhaustion path (design §5.2).
          _      <- ingestUsage(ingest, router, usageRecord("youtube.com", 120))
          frames <- collect(
            port,
            tok,
            List(subTimeStatus),
            trigger = bus.publish(SpaEvent.TimeStatusChanged),
            wait = 4.seconds,
          )
          tFrames = framesOf(frames, "timeStatus")
          pushed  <- ZIO.fromEither(parseTimeStatus(tFrames.last)).mapError(new RuntimeException(_))
          getBody <- getStr(port, tok, "/api/time/status")
          got     <- ZIO
            .fromEither(getBody.fromJson[List[ProfileTimeStatus]])
            .mapError(e => new RuntimeException(s"parse GET ($e): $getBody"))
        } yield assertTrue(tFrames.nonEmpty) && assertTrue(pushed.toSet == got.toSet)
      }
    },
    test("POST /api/time/extend grant pushes a fresh timeStatus reflecting the new extension") {
      withHarness { (port, ingest, router, _) =>
        for {
          tok <- ZIO.serviceWithZIO[Clock](makeAuth).flatMap(adminToken)
          pid <- ZIO.serviceWithZIO[ProfileRepo](_.listAll).map(_.head.id)
          grant = ZIO.serviceWithZIO[Client] { client =>
            ZIO
              .scoped(
                client.request(
                  Request
                    .post(
                      s"http://localhost:$port/api/time/extend",
                      Body.fromString(
                        s"""{"profileId":${pid.value},"extraMinutes":15}""",
                      ),
                    )
                    .addHeader(Header.Authorization.Bearer(tok)),
                ),
              )
              .unit
          }
          frames <- collect(port, tok, List(subTimeStatus), trigger = grant, wait = 4.seconds)
          tFrames = framesOf(frames, "timeStatus")
          pushed <- ZIO.fromEither(parseTimeStatus(tFrames.last)).mapError(new RuntimeException(_))
        } yield assertTrue(tFrames.nonEmpty) &&
          assertTrue(pushed.exists(p => p.profileName == "Kids" && p.extensionMins == 15))
      }
    },
    test("appUsage{profileId} push equals GET /api/profiles/{id}/usage-by-app (SSOT)") {
      withHarness { (port, ingest, router, _) =>
        for {
          tok    <- ZIO.serviceWithZIO[Clock](makeAuth).flatMap(adminToken)
          pid    <- ZIO.serviceWithZIO[ProfileRepo](_.listAll).map(_.head.id)
          before <- pushCount("appUsage", "ok")
          frames <- collect(
            port,
            tok,
            List(subAppUsage(pid)),
            trigger = ingestUsage(ingest, router, usageRecord("youtube.com", 120)),
            wait = 4.seconds,
          )
          after  <- pushCount("appUsage", "ok")
          aFrames = framesOf(frames, "appUsage")
          pushed  <- ZIO.fromEither(parseAppUsage(aFrames.last)).mapError(new RuntimeException(_))
          getBody <- getStr(port, tok, s"/api/profiles/${pid.value}/usage-by-app")
          got     <- ZIO
            .fromEither(getBody.fromJson[ProfileUsageByApp])
            .mapError(e => new RuntimeException(s"parse GET ($e): $getBody"))
        } yield assertTrue(aFrames.nonEmpty) &&
          assertTrue(after - before >= 1.0) &&
          assertTrue(pushed == got)
      }
    },
    test("role filter: a child linked to one profile receives ONLY that profile's timeStatus") {
      withHarness { (port, ingest, router, _) =>
        for {
          // A second profile the child must NOT see, plus the child linked to Kids only.
          kidsPid <- ZIO.serviceWithZIO[ProfileRepo](_.listAll).map(_.head.id)
          _       <- ZIO.serviceWithZIO[ProfileRepo](_.create("Teens", Nil))
          childTk <- ZIO.serviceWithZIO[Clock](makeAuth).flatMap(a => tokenFor(a, "child", "kid"))
          (childTok, childUid) = childTk
          _      <- ZIO.serviceWithZIO[UserProfileRepo](_.addLink(childUid, kidsPid))
          frames <- collect(
            port,
            childTok,
            List(subTimeStatus),
            trigger = ingestUsage(ingest, router, usageRecord("youtube.com", 120)),
            wait = 4.seconds,
          )
          tFrames = framesOf(frames, "timeStatus")
          pushed <- ZIO.fromEither(parseTimeStatus(tFrames.last)).mapError(new RuntimeException(_))
        } yield assertTrue(tFrames.nonEmpty) &&
          assertTrue(pushed.map(_.profileName) == List("Kids"))
      }
    },
    test("no timeStatus/appUsage subscriber → no timeStatus/appUsage push on usage ingest") {
      withHarness { (port, ingest, router, _) =>
        for {
          tok     <- ZIO.serviceWithZIO[Clock](makeAuth).flatMap(a => tokenFor(a, "adult", "mom"))
          beforeT <- pushCount("timeStatus", "ok")
          beforeA <- pushCount("appUsage", "ok")
          frames  <- collect(
            port,
            tok._1,
            List("""{"op":"subscribe","payload":{"topic":"now"}}"""),
            trigger = ingestUsage(ingest, router, usageRecord("youtube.com", 120)),
            wait = 4.seconds,
          )
          afterT  <- pushCount("timeStatus", "ok")
          afterA  <- pushCount("appUsage", "ok")
        } yield assertTrue(framesOf(frames, "timeStatus").isEmpty) &&
          assertTrue(framesOf(frames, "appUsage").isEmpty) &&
          assertTrue(afterT == beforeT) && assertTrue(afterA == beforeA)
      }
    },
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(120.seconds)
}
