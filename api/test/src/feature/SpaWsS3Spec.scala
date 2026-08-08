package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.WsConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.policy.{HouseholdScoped, PolicySnapshotPublisher}
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
import zio.test.*

import java.time.LocalDateTime

/**
 * #1970 (S3 of the SPA-websocket rollout, design `docs/design/spa-websocket.md` §5.2): the change
 * sources. Exercises the FULL chain end to end — a real [[Server]] + ws [[Client]], the forked
 * [[SpaPush]] consumer draining the [[SpaEventBus]], and the REAL [[RouterIngestService]] write
 * site over an embedded Postgres (never mocked) — proving that a write site's one-line publish
 * becomes a subscription-gated, role-filtered `now`/`connectionEvents`/`stale` frame, and that a
 * connection receives a topic ONLY if it both subscribed AND is authorized (the two gates,
 * §1.4/§4.4).
 *
 * The publisher widening (§5.2.1) is proven behavior-PRESERVING for the router subscriber: the
 * existing `RouterWsSpec`/`RouterWsAgentMetricsAllowlistSpec` stay green (the router sink is
 * invoked synchronously, unchanged), and the focused broadcast test here asserts a router sink
 * still receives the full snapshot while the SPA sink additionally derives a `now` trigger.
 */
object SpaWsS3Spec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  private val testClockAt: LocalDateTime = LocalDateTime.of(2026, 6, 25, 14, 0, 0)

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(testClockAt)

  private val jwtCfg = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)

  // Origin enforcement OFF (self-hosted same-origin shape) keeps the client connect to just the
  // cookie — S2 already covers the Origin allowlist; S3 is about the change-source fan-out.
  private val openCfg = WsConfig(allowedOrigins = "", expiryCheckSeconds = 60)

  private val cleanDb = TestDatabase.cleanAndMigrate

  // Events are dated just before the test clock's "now" so SpaPush's `until = now` head query
  // (`ts <= now`) includes them.
  private val eventTs  = "2026-06-25T13:59:30Z"
  private val knownMac = "aa:bb:cc:11:22:33"

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

  private def adminToken(auth: AuthService): ZIO[Any, Throwable, String] =
    auth
      .login("admin", "changeme")
      .mapError(e => new RuntimeException(s"login: $e"))
      .map(_.token.value)

  /**
   * Seed an enrolled router and return the resolved [[Router]] the ingest service ingests against.
   */
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

  private def connEvent(host: String, allowed: Boolean): RouterEvent =
    RouterEvent(
      "connection_attempt",
      mac = Some(MacAddress.unsafe(knownMac)),
      host = Some(HostId.Fqdn(Hostname.unsafe(host))),
      destIp = Some(IpAddress.unsafe("1.2.3.4")),
      allowed = Some(allowed),
      reason = Some(if allowed then "allow" else "blocked"),
      ts = eventTs,
      eventId = Some(java.util.UUID.randomUUID()),
    )

  private def ingestEvents(
      ingest: RouterIngestService,
      router: Router,
      evs: RouterEvent*,
  ): Task[Unit] =
    ingest
      .ingestEvents(router, RouterEventsRequest(router.id, evs.toList).toJson)
      .mapError(e => new RuntimeException(s"ingest: $e"))

  private def pushCount(op: String, result: String): UIO[Double] =
    zio.metrics.Metric
      .counter("spa_ws_push_total")
      .tagged("op", op)
      .tagged("result", result)
      .value
      .map(_.count)

  /**
   * Drain the `spa_ws_push_total{op,result=ok}` counter until it reaches `target`, then return it.
   * The increment runs on the server fan-out fiber AFTER `channel.send` resolves, so it can lag the
   * client's frame receipt; polling until it lands (rather than reading once) removes that race
   * (#2324). Gently spaced (10ms) so the poll never starves the fan-out fiber on a constrained
   * runner, and caller-wrapped in `Live.live` so the delay/timeout run on the live clock. Fails
   * loudly if the counter never advances within the window rather than asserting on a stale read.
   */
  private def awaitPushCount(op: String, target: Double): Task[Double] = {
    def poll: UIO[Double] =
      pushCount(op, "ok").flatMap(v => if (v >= target) ZIO.succeed(v) else poll.delay(10.millis))
    poll.timeoutFail(
      new RuntimeException(s"spa_ws_push_total{op=$op,result=ok} never reached $target"),
    )(20.seconds)
  }

  /**
   * Connect a ws client, send `hello` + the given subscribe frames, wait for the subscribe `ack`
   * (so the subscription is registered server-side before the trigger fires), run `trigger` (a real
   * write site), then resolve to the first frame matching `until` within `wait` (None on timeout).
   * Also returns every frame received, for asserting the `ack` outcome. The forked [[SpaPush]]
   * consumer must already be running in the enclosing scope.
   */
  private def flow(
      port: Int,
      token: String,
      subscribeFrames: List[String],
      trigger: Task[Unit],
      until: String => Boolean,
      wait: Duration,
      awaitAck: Boolean = true,
  ): ZIO[Client, Throwable, (Option[String], Chunk[String])] =
    for {
      ackP    <- Promise.make[Nothing, Unit]
      matched <- Promise.make[Nothing, String]
      frames  <- Ref.make(Chunk.empty[String])
      app = Handler.webSocket { channel =>
        channel.receiveAll {
          case ChannelEvent.UserEventTriggered(UserEvent.HandshakeComplete) =>
            ZIO.foreachDiscard("""{"op":"hello"}""" :: subscribeFrames)(f =>
              channel.send(ChannelEvent.read(WebSocketFrame.text(f))),
            )
          case ChannelEvent.Read(WebSocketFrame.Text(t))                    =>
            frames.update(_ :+ t) *>
              ZIO.when(t.contains("\"op\":\"ack\""))(ackP.succeed(())) *>
              ZIO.when(until(t))(matched.succeed(t)).unit
          case _                                                            =>
            ZIO.unit
        }
      }
      res <- ZIO.scoped {
        for {
          _   <- app
            .connect(s"ws://localhost:$port/api/ws", Headers("Cookie", s"wh_ws=$token"))
            .forkScoped
          // Await the subscribe `ack` BEFORE the trigger: the server registers the subscription
          // (`registry.subscribe`) strictly before it sends the `ack` (SpaWsRoutes.handleSubscribe),
          // so an acked subscription is guaranteed visible to the SpaPush fan-out by the time we
          // trigger — no reliance on a wall-clock catch-up. The timeout runs on the LIVE clock
          // (`Live.live`, mirroring `MultiTenantIsolationSpec.wsIsolation`) so the wait can never
          // hang on a non-advancing injected `TestClock`.
          _   <- ZIO
            .when(awaitAck)(
              Live.live(
                ackP.await.timeoutFail(new RuntimeException("no subscribe ack"))(20.seconds),
              ),
            )
          _   <- trigger
          // Frame receipt is Promise-gated (drain until the expected push arrives), never a sleep;
          // the timeout is on the LIVE clock so a topic that legitimately never pushes (the negative
          // pins) bounds out instead of hanging.
          m   <- Live.live(matched.await.timeout(wait))
          all <- frames.get
        } yield (m, all)
      }
    } yield res

  /**
   * Stand up the registry + bus + ingest, fork the SpaPush consumer in the scope, install the
   * `/api/ws` server, and run `body` with the bound port + the three collaborators.
   */
  private type BootstrapEnv =
    TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]

  private def withHarness[A](
      body: (
          Int,
          RouterIngestService,
          SpaEventBus,
          Router,
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
      usageRepo   <- ZIO.service[TimeUsageRepo]
      alertRepo   <- ZIO.service[AlertRepo]
      hsRepo      <- ZIO.service[HouseholdSettingsRepo]
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
      routes = SpaWsRoutes.routes(auth, reg, clock, openCfg)
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
        ) *>
          body(port, ingest, bus, router)
      }
    } yield out).provideSome[BootstrapEnv](Server.defaultWithPort(0), Client.default)

  def spec = suite("SPA websocket S3 change sources (#1970)")(
    test("connectionEvents{blocked:true} subscriber receives the new BLOCKED head row on ingest") {
      withHarness { (port, ingest, _, router) =>
        for {
          adminTok     <- ZIO.serviceWithZIO[Clock](makeAuth).flatMap(adminToken)
          before       <- pushCount("connectionEvents", "ok")
          (matched, _) <- flow(
            port,
            adminTok,
            List(
              """{"op":"subscribe","payload":{"topic":"connectionEvents","params":{"blocked":true}}}""",
            ),
            trigger = ingestEvents(ingest, router, connEvent("youtube.com", allowed = false)),
            until = _.contains("\"op\":\"connectionEvents\""),
            wait = 20.seconds,
          )
          // `spa_ws_push_total{op=connectionEvents,result=ok}` is incremented on the SERVER fan-out
          // fiber STRICTLY AFTER `channel.send` resolves (SpaWsRegistry.sendPush), whereas `matched`
          // above resolves the instant the CLIENT receives that same frame — so a single read of the
          // counter here races the server-side increment (the intermittent merge-queue failure,
          // #2324). Drain until the counter reflects the push (bounded on the LIVE clock, gently
          // spaced so the poll never starves the fan-out fiber), rather than reading once. The
          // assertion is unchanged — we still prove the counter advanced by ≥1.
          after        <- Live.live(awaitPushCount("connectionEvents", before + 1.0))
        } yield assertTrue(matched.exists(_.contains("youtube.com"))) &&
          assertTrue(matched.exists(_.contains("\"blocked\":true"))) &&
          assertTrue(after - before >= 1.0)
      }
    },
    test(
      "connectionEvents{blocked:true} subscriber gets NOTHING when only an ALLOWED event ingests",
    ) {
      withHarness { (port, ingest, _, router) =>
        for {
          adminTok     <- ZIO.serviceWithZIO[Clock](makeAuth).flatMap(adminToken)
          (matched, _) <- flow(
            port,
            adminTok,
            List(
              """{"op":"subscribe","payload":{"topic":"connectionEvents","params":{"blocked":true}}}""",
            ),
            trigger = ingestEvents(ingest, router, connEvent("khanacademy.org", allowed = true)),
            until = _.contains("\"op\":\"connectionEvents\""),
            wait = 4.seconds,
          )
        } yield assertTrue(matched.isEmpty)
      }
    },
    test("a non-subscriber receives NO connectionEvents frame on a blocked ingest") {
      withHarness { (port, ingest, _, router) =>
        for {
          adminTok     <- ZIO.serviceWithZIO[Clock](makeAuth).flatMap(adminToken)
          (matched, _) <- flow(
            port,
            adminTok,
            subscribeFrames = Nil, // subscribe to nothing
            trigger = ingestEvents(ingest, router, connEvent("youtube.com", allowed = false)),
            until = _.contains("\"op\":\"connectionEvents\""),
            wait = 4.seconds,
            awaitAck = false,
          )
        } yield assertTrue(matched.isEmpty)
      }
    },
    test("a `now` subscriber receives a recomputed DashboardNow on connection-events ingest") {
      withHarness { (port, ingest, _, router) =>
        for {
          adminTok     <- ZIO.serviceWithZIO[Clock](makeAuth).flatMap(adminToken)
          (matched, _) <- flow(
            port,
            adminTok,
            List("""{"op":"subscribe","payload":{"topic":"now"}}"""),
            trigger = ingestEvents(ingest, router, connEvent("youtube.com", allowed = false)),
            until = _.contains("\"op\":\"now\""),
            wait = 20.seconds,
          )
        } yield assertTrue(matched.exists(_.contains("\"op\":\"now\""))) &&
          // DashboardNow body carries `asOf` + `profiles` (reused GET body, §0.3).
          assertTrue(matched.exists(f => f.contains("\"asOf\"") && f.contains("\"profiles\"")))
      }
    },
    test(
      "a `stale` subscriber gets the {topic:profiles} nudge when a profile-mutation event publishes",
    ) {
      withHarness { (port, _, bus, _) =>
        for {
          adminTok     <- ZIO.serviceWithZIO[Clock](makeAuth).flatMap(adminToken)
          (matched, _) <- flow(
            port,
            adminTok,
            List("""{"op":"subscribe","payload":{"topic":"stale"}}"""),
            // exactly what Main's profile-mutation callback publishes after policy.invalidate.
            trigger = bus.publish(SpaEvent.Stale(StaleTopic.Profiles)),
            until = _.contains("\"op\":\"stale\""),
            wait = 20.seconds,
          )
        } yield assertTrue(matched.exists(_.contains("\"topic\":\"profiles\"")))
      }
    },
    test("role gate: a child's `now` subscribe is acked reject and no `now` frame is ever pushed") {
      withHarness { (port, ingest, _, router) =>
        for {
          childTok <- ZIO.serviceWithZIO[Clock](makeAuth).flatMap(a => tokenFor(a, "child", "kid"))
          (matched, all) <- flow(
            port,
            childTok,
            List("""{"op":"subscribe","payload":{"topic":"now"}}"""),
            trigger = ingestEvents(ingest, router, connEvent("youtube.com", allowed = false)),
            until = _.contains("\"op\":\"now\""),
            wait = 4.seconds,
          )
        } yield assertTrue(matched.isEmpty) &&
          assertTrue(
            all.exists(f =>
              f.contains("\"op\":\"ack\"") && f.contains("\"topic\":\"now\"") &&
                f.contains("\"status\":\"reject\""),
            ),
          )
      }
    },
    // ── publisher widening is behavior-preserving (§5.2.1) ──────────────────────
    test("broadcast publisher still drives the router sink AND derives a SPA `now` trigger") {
      for {
        received <- Ref.make(Option.empty[PolicySnapshot])
        bus      <- SpaEventBus.make
        routerSink = new PolicySnapshotPublisher {
          // #2630: a sink reads the snapshot by naming a recipient household — here, the one the
          // snapshot was published for, which is what a registry entry would match against.
          def publish(scoped: HouseholdScoped[PolicySnapshot]): UIO[Unit] =
            received.set(scoped.forHousehold(HouseholdId.Default))
          def targetHouseholds: UIO[Set[HouseholdId]] = ZIO.succeed(Set(HouseholdId.Default))
        }
        spaSink    = new PolicySnapshotPublisher {
          def publish(scoped: HouseholdScoped[PolicySnapshot]): UIO[Unit] =
            bus.publish(SpaEvent.NowChanged)
          def targetHouseholds: UIO[Set[HouseholdId]]                     = ZIO.succeed(Set.empty)
        }
        pub        = PolicySnapshotPublisher.broadcast(List(routerSink, spaSink))
        snap       = PolicySnapshot(
          etag = ETag.unsafe("etag-test"),
          generatedAt = "2026-06-25T14:00:00Z",
          devices = Map.empty,
          profiles = Map.empty,
          blocklists = Map.empty,
        )
        spaEvent <- ZIO.scoped(
          bus.subscribe.flatMap(q =>
            pub.publish(HouseholdScoped(HouseholdId.Default, snap)) *> q.take,
          ),
        )
        got      <- received.get
      } yield assertTrue(got.contains(snap)) && assertTrue(spaEvent == SpaEvent.NowChanged)
    },
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential @@ TestAspect.timeout(120.seconds)
}
