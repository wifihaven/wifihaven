package wifihaven.api.feature

import wifihaven.api.{BetaConfig, JwtConfig, WsConfig}
import wifihaven.api.auth.*
import wifihaven.api.beta.*
import wifihaven.api.db.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.api.notify.Notifier
import wifihaven.api.policy.*
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import doobie.*
import doobie.implicits.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.interop.catz.*
import zio.{Clock as _, *}
import zio.http.*
import zio.http.ChannelEvent.UserEvent
import zio.json.*
import zio.test.*

import java.time.Instant

/**
 * #2108 (multi-tenant sub-issue E, epic #622) — THE ACCEPTANCE GATE for the isolation substrate.
 *
 * The one load-bearing invariant (design §0): *a household-A principal (user JWT or router token)
 * must never read or write a household-B row.* Every test below is a NEGATIVE test — the point is
 * the ABSENCE of cross-household leakage. Seeded with TWO real households in embedded Postgres (no
 * repo mocks), so the SQL predicate itself is what's under test (design §7).
 *
 * Pin map (design §7): 1 user READ isolation — /profiles, /devices, /alerts, /logs, /time/status 2
 * user WRITE isolation — cross-household PATCH/PUT → 404, hh-B row untouched 3 snapshot scoping —
 * GET /api/router/policy (D #2107 landed it; re-asserted here) 4a ingest write scoping — hh-A's
 * router usage writes ONLY under (hhA, mac); hh-B byte-identical 4b new-device discovery — a
 * never-seen MAC → unmanaged device in hhA, in NO other household 5 blocklist auth — shared global
 * catalog, byte-identical across households
 *
 * NOTE on pin 4a's mechanism: V65 (#2104) kept the GLOBAL `devices_mac_key`/`time_usage` uniques
 * (dropped in a follow-up schema-only PR), so the SAME MAC cannot yet exist in two households — the
 * literal same-MAC write-collision is unrepresentable until that drop. Pin 4a therefore proves the
 * achievable-and-equivalent property: hh-A's router write is CONSTRUCTIVELY keyed to (hhA, mac) and
 * does not touch hh-B's distinct-MAC rows. The same-MAC collision variant lands with the drop PR.
 */
object MultiTenantIsolationSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val macA = MacAddress.unsafe("aa:bb:cc:00:00:0a")
  private val macB = MacAddress.unsafe("aa:bb:cc:00:00:0b")
  private val macC = MacAddress.unsafe("aa:bb:cc:00:00:0c") // never-seen (pin 4b)

  private val jwt = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)

  private val noopNotifier: Notifier = new Notifier {
    def alertCreated(a: Alert): UIO[Unit]                                               = ZIO.unit
    def betaInvite(email: String, slug: String, inviteUrl: String, ttl: Int): UIO[Unit] = ZIO.unit
    def betaFlipNotice(
        householdId: wifihaven.shared.types.HouseholdId,
        slug: String,
        window: String,
        flipDate: java.time.Instant,
        daysUntilFlip: Int,
    ): UIO[Unit] = ZIO.unit
  }

  // #2132: build the beta pipeline stack (service + routes) over the real repos.
  private def makeBetaRoutes
      : ZIO[TestDatabase.AllRepos & Clock, Nothing, (BetaService, Routes[Any, Response])] =
    for {
      br   <- ZIO.service[BetaRequestRepo]
      hr   <- ZIO.service[HouseholdRepo]
      ur   <- ZIO.service[UserRepo]
      hbr  <- ZIO.service[HouseholdBillingRepo]
      clk  <- ZIO.service[Clock]
      auth <- makeAuth
    } yield {
      // #2135: billing disabled (no keys) — the provisioning Customer seam is a no-op here.
      val billing = wifihaven.api.billing.BillingService(
        wifihaven.api.billing.StripeClient.noop,
        hbr,
        clk,
        wifihaven.api.StripeConfig(),
      )
      val svc     = BetaService(br, hr, ur, auth, noopNotifier, clk, BetaConfig(), billing)
      (svc, BetaRoutes.routes(auth, svc, br, ur, RateLimiter.allowAll))
    }

  private def postJson(
      routes: Routes[Any, Response],
      path: String,
      token: Option[String],
      body: String,
  ): Task[(Status, String)] = {
    val base = Request
      .post(URL.decode(path).toOption.get, Body.fromString(body))
      .addHeader(Header.ContentType(MediaType.application.json))
    val req  = token.fold(base)(t => base.addHeader(Header.Authorization.Bearer(t)))
    routes.runZIO(req).flatMap(r => r.body.asString.map((r.status, _)))
  }

  // Run request → approve → accept, returning the new household's (slug, invite token). The accepted
  // admin is always `admin` (design §3.4, 2026-07-10) with email bound from the request; a caller
  // logging in as it passes the returned slug (per-household usernames, #2140).
  private def provisionHousehold(
      betaRoutes: Routes[Any, Response],
      opToken: String,
      email: String,
      name: String,
  ): Task[(String, String)] =
    for {
      _         <- postJson(
        betaRoutes,
        "/api/beta/request",
        None,
        CreateBetaRequest(email, Some(name)).toJson,
      )
      (_, lst)  <- getJson(betaRoutes, "/api/operator/beta-requests", opToken)
      reqId     <- ZIO
        .fromEither(lst.fromJson[List[BetaRequestSummary]])
        .mapError(new RuntimeException(_))
        .map(_.filter(_.email == email.toLowerCase).head.id)
      (_, appr) <- postJson(
        betaRoutes,
        s"/api/operator/beta-requests/${reqId.value}/approve",
        Some(opToken),
        "",
      )
      approve   <- ZIO
        .fromEither(appr.fromJson[ApproveBetaResponse])
        .mapError(new RuntimeException(_))
      token = approve.inviteUrl.split("token=").last
      _ <- postJson(
        betaRoutes,
        "/api/beta/accept",
        None,
        AcceptInviteRequest(token, "supersecret123").toJson,
      )
    } yield (approve.slug, token)

  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      // #2140: inject the DB-backed HouseholdRepo so login can resolve household B's slug (the
      // default-only stub the 3-arg constructor supplies knows only slug `default`).
      hr    <- ZIO.service[HouseholdRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwt, clock, hr): AuthService

  // #2164: single-identifier login. Usernames are unique per household, so a non-default household's
  // user names its household via the `slug/username` identifier form; the default household (slug
  // `default`) is reachable by a bare username. adminB lives in household B.
  private def login(
      auth: AuthService,
      user: String,
      pw: String,
      slug: Option[String] = None,
  ): Task[String] =
    auth
      .login(slug.fold(user)(s => s"$s/$user"), pw)
      .mapError(e => new RuntimeException(s"login failed: $e"))
      .map(_.token.value)

  private def getJson(
      routes: Routes[Any, Response],
      path: String,
      token: String,
  ): Task[(Status, String)] =
    for {
      resp <- routes.runZIO(
        Request.get(URL.decode(path).toOption.get).addHeader(Header.Authorization.Bearer(token)),
      )
      body <- resp.body.asString
    } yield (resp.status, body)

  private def makePolicyService =
    for {
      pr     <- ZIO.service[ProfileRepo]
      hsr    <- ZIO.service[HouseholdSettingsRepo]
      tlr    <- ZIO.service[TimeLimitRepo]
      atlr   <- ZIO.service[AppTimeLimitRepo]
      dr     <- ZIO.service[DeviceRepo]
      blr    <- ZIO.service[BlocklistRepo]
      trRepo <- ZIO.service[TrafficReportRepo]
      er     <- ZIO.service[TimeExtensionRepo]
      ar     <- ZIO.service[AppRepo]
      clock  <- ZIO.service[Clock]
    } yield PolicyServiceLive(pr, hsr, tlr, atlr, dr, blr, trRepo, er, ar, clock): PolicyService

  // #2251: connect TWO real `now` ws subscribers (household A + household B) over a live server,
  // wait for BOTH subscribe acks, publish ONE `SpaEvent.NowChanged`, and return each client's first
  // `now` push frame. The forked [[SpaPush]] consumer must already be running in the enclosing
  // scope. This exercises the FULL push path (registry household grouping → per-household
  // `computeNow` → per-recipient `deliver`) end to end, so the assertion is on the real wire frame
  // each household receives — not a stubbed body.
  private val nowSubscribe =
    List("""{"op":"hello"}""", """{"op":"subscribe","payload":{"topic":"now"}}""")

  private def wsNowIsolation(
      port: Int,
      tokenA: String,
      tokenB: String,
      bus: SpaEventBus,
  ): ZIO[Client, Throwable, (Option[String], Option[String])] =
    for {
      ackA   <- Promise.make[Nothing, Unit]
      ackB   <- Promise.make[Nothing, Unit]
      frameA <- Promise.make[Nothing, String]
      frameB <- Promise.make[Nothing, String]
      app = (ack: Promise[Nothing, Unit], frame: Promise[Nothing, String]) =>
        Handler.webSocket { channel =>
          channel.receiveAll {
            case ChannelEvent.UserEventTriggered(UserEvent.HandshakeComplete) =>
              ZIO.foreachDiscard(nowSubscribe)(f =>
                channel.send(ChannelEvent.read(WebSocketFrame.text(f))),
              )
            case ChannelEvent.Read(WebSocketFrame.Text(t))                    =>
              ZIO.when(t.contains("\"op\":\"ack\""))(ack.succeed(())) *>
                ZIO.when(t.contains("\"op\":\"now\""))(frame.succeed(t)).unit
            case _                                                            =>
              ZIO.unit
          }
        }
      res <- ZIO.scoped {
        for {
          _ <- app(ackA, frameA)
            .connect(s"ws://localhost:$port/api/ws", Headers("Cookie", s"wh_ws=$tokenA"))
            .forkScoped
          _ <- app(ackB, frameB)
            .connect(s"ws://localhost:$port/api/ws", Headers("Cookie", s"wh_ws=$tokenB"))
            .forkScoped
          _ <- ackA.await.timeoutFail(new RuntimeException("no ack A"))(20.seconds)
          _ <- ackB.await.timeoutFail(new RuntimeException("no ack B"))(20.seconds)
          // Both subscribed — one trigger fans a per-household body to each.
          _ <- bus.publish(SpaEvent.NowChanged)
          a <- frameA.await.timeout(20.seconds)
          b <- frameB.await.timeout(20.seconds)
        } yield (a, b)
      }
    } yield res

  // #2257: the generalized two-household ws isolation helper — connect a household-A and a
  // household-B subscriber over ONE live server (each with its own subscribe frames), wait for both
  // subscribe acks, run `trigger` (publish the driving `SpaEvent`), and return each client's first
  // `op` push frame (None if it never arrives within the timeout). Mirrors [[wsNowIsolation]] but is
  // parametric over the subscription frames, the push `op` to capture, and the trigger — so the
  // `timeStatus` / `appUsage` / `trafficUsage` push pins can each drive their own topic. The forked
  // [[SpaPush]] consumer must already be running in the enclosing scope.
  private def wsIsolation(
      port: Int,
      tokenA: String,
      tokenB: String,
      subsA: List[String],
      subsB: List[String],
      op: String,
      trigger: UIO[Unit],
  ): ZIO[Client, Throwable, (Option[String], Option[String])] =
    for {
      ackA   <- Promise.make[Nothing, Unit]
      ackB   <- Promise.make[Nothing, Unit]
      frameA <- Promise.make[Nothing, String]
      frameB <- Promise.make[Nothing, String]
      app = (subs: List[String], ack: Promise[Nothing, Unit], frame: Promise[Nothing, String]) =>
        Handler.webSocket { channel =>
          channel.receiveAll {
            case ChannelEvent.UserEventTriggered(UserEvent.HandshakeComplete) =>
              ZIO.foreachDiscard(subs)(f => channel.send(ChannelEvent.read(WebSocketFrame.text(f))))
            case ChannelEvent.Read(WebSocketFrame.Text(t))                    =>
              ZIO.when(t.contains("\"op\":\"ack\""))(ack.succeed(())) *>
                ZIO.when(t.contains(s"""\"op\":\"$op\""""))(frame.succeed(t)).unit
            case _                                                            =>
              ZIO.unit
          }
        }
      res <- ZIO.scoped {
        for {
          _ <- app(subsA, ackA, frameA)
            .connect(s"ws://localhost:$port/api/ws", Headers("Cookie", s"wh_ws=$tokenA"))
            .forkScoped
          _ <- app(subsB, ackB, frameB)
            .connect(s"ws://localhost:$port/api/ws", Headers("Cookie", s"wh_ws=$tokenB"))
            .forkScoped
          // The timeouts must run on the LIVE clock (`Live.live`): these specs inject a non-advancing
          // TestClock, and a `now`-refused recipient (the `appUsage` cross-household pin) never
          // receives a frame — a TestClock `.timeout` would then never fire and hang the suite.
          _ <- Live.live(ackA.await.timeoutFail(new RuntimeException("no ack A"))(20.seconds))
          _ <- Live.live(ackB.await.timeoutFail(new RuntimeException("no ack B"))(20.seconds))
          // Both subscribed — one trigger fans a per-household body to each.
          _ <- trigger
          a <- Live.live(frameA.await.timeout(20.seconds))
          b <- Live.live(frameB.await.timeout(20.seconds))
        } yield (a, b)
      }
    } yield res

  def spec = suite("Multi-tenant isolation — THE acceptance gate (#2108)")(
    // ── Pin 1: user READ isolation ─────────────────────────────────────────────
    test("pin 1 — GET /api/profiles returns ONLY the caller's household profiles") {
      for {
        _      <- cleanDb
        two    <- TestLayers.seedTwoHouseholds(macA, macB)
        pr     <- ZIO.service[ProfileRepo]
        tlr    <- ZIO.service[TimeLimitRepo]
        up     <- ZIO.service[UserProfileRepo]
        ur     <- ZIO.service[UserRepo]
        nsr    <- ZIO.service[NamedScheduleRepo]
        auth   <- makeAuth
        tokenA <- login(auth, two.adminA, two.password)
        tokenB <- login(auth, two.adminB, two.password, Some(two.slugB))
        routes = ProfileRoutes.routes(auth, pr, tlr, up, ur, nsr)
        (sA, bodyA) <- getJson(routes, "/api/profiles", tokenA)
        (sB, bodyB) <- getJson(routes, "/api/profiles", tokenB)
      } yield assertTrue(sA == Status.Ok, sB == Status.Ok) &&
        // Household A's admin sees A's profile, never B's — even as `admin`.
        assertTrue(bodyA.contains(s""""id":${two.profileA.value}""")) &&
        assertTrue(!bodyA.contains(s""""id":${two.profileB.value}""")) &&
        // Household B's admin sees ONLY B's profile — none of household A's (Kids/Adults/A-Kids).
        assertTrue(bodyB.contains(s""""id":${two.profileB.value}""")) &&
        assertTrue(!bodyB.contains(s""""id":${two.profileA.value}"""))
    },
    test("pin 1 — GET /api/devices returns ONLY the caller's household devices") {
      for {
        _      <- cleanDb
        two    <- TestLayers.seedTwoHouseholds(macA, macB)
        dr     <- ZIO.service[DeviceRepo]
        up     <- ZIO.service[UserProfileRepo]
        pr     <- ZIO.service[ProfileRepo]
        auth   <- makeAuth
        tokenA <- login(auth, two.adminA, two.password)
        tokenB <- login(auth, two.adminB, two.password, Some(two.slugB))
        routes = DeviceRoutes.routes(auth, dr, up, pr)
        (sA, bodyA) <- getJson(routes, "/api/devices", tokenA)
        (sB, bodyB) <- getJson(routes, "/api/devices", tokenB)
      } yield assertTrue(sA == Status.Ok, sB == Status.Ok) &&
        assertTrue(bodyA.contains(macA.value), !bodyA.contains(macB.value)) &&
        assertTrue(bodyB.contains(macB.value), !bodyB.contains(macA.value))
    },
    test("pin 1 — GET /api/alerts returns ONLY the caller's household alerts") {
      for {
        _      <- cleanDb
        two    <- TestLayers.seedTwoHouseholds(macA, macB)
        ar     <- ZIO.service[AlertRepo]
        dr     <- ZIO.service[DeviceRepo]
        pr     <- ZIO.service[ProfileRepo]
        er     <- ZIO.service[TimeExtensionRepo]
        apr    <- ZIO.service[AppRepo]
        hsr    <- ZIO.service[HouseholdSettingsRepo]
        clk    <- ZIO.service[Clock]
        _      <- ar.raiseNewDevice(macA, Instant.parse("2026-05-07T14:00:00Z"))
        _      <- ar.raiseNewDevice(macB, Instant.parse("2026-05-07T14:00:00Z"))
        auth   <- makeAuth
        tokenA <- login(auth, two.adminA, two.password)
        routes = AlertRoutes.routes(
          auth,
          ar,
          dr,
          pr,
          er,
          apr,
          hsr,
          noopNotifier,
          clk,
          RateLimiter.allowAll,
        )
        (sA, bodyA) <- getJson(routes, "/api/alerts?all=true", tokenA)
      } yield assertTrue(sA == Status.Ok) &&
        assertTrue(bodyA.contains(macA.value), !bodyA.contains(macB.value))
    },
    test("pin 1 — GET /api/logs returns ONLY the caller's household connection logs") {
      for {
        _   <- cleanDb
        two <- TestLayers.seedTwoHouseholds(macA, macB)
        cer <- ZIO.service[ConnectionEventRepo]
        up  <- ZIO.service[UserProfileRepo]
        ts = Instant.parse("2026-05-07T14:00:00Z")
        _      <- cer.insertBatch(
          List(
            ConnectionEventInsert(
              two.routerIdA,
              Some(macA),
              HostId.Fqdn(Hostname.unsafe("a.example.com")),
              None,
              true,
              BlockReason.fromWire("allow"),
              ts,
            ),
            ConnectionEventInsert(
              two.routerIdB,
              Some(macB),
              HostId.Fqdn(Hostname.unsafe("b.example.com")),
              None,
              true,
              BlockReason.fromWire("allow"),
              ts,
            ),
          ),
        )
        auth   <- makeAuth
        tokenA <- login(auth, two.adminA, two.password)
        routes = LogRoutes.routes(auth, cer, up)
        // `hours` widens the connection_events window (anchored at SQL NOW(), real wall-clock) so it
        // reaches back to the fixed-instant events seeded above.
        (sA, bodyA) <- getJson(routes, "/api/logs?hours=1000000", tokenA)
      } yield assertTrue(sA == Status.Ok) &&
        assertTrue(bodyA.contains(macA.value), !bodyA.contains(macB.value))
    },
    test("pin 1 — GET /api/stats counts ONLY the caller's household (leak pin #2282)") {
      for {
        _   <- cleanDb
        two <- TestLayers.seedTwoHouseholds(macA, macB)
        cer <- ZIO.service[ConnectionEventRepo]
        // Real wall-clock now: the stat-card windows are anchored on SQL NOW() — the 1h tiles on raw
        // `connection_events`, the 24h totals/top on the `connection_events_hourly` rollup — not on
        // the injected TestClock, so the seeded events must be near real now to land in-window.
        now = Instant.now()
        // Household A's router produces 3 events (2 allowed + 1 blocked); household B produces NONE.
        _      <- cer.insertBatch(
          List(
            ConnectionEventInsert(
              two.routerIdA,
              Some(macA),
              HostId.Fqdn(Hostname.unsafe("a1.example.com")),
              None,
              true,
              BlockReason.fromWire("allowed"),
              now.minusSeconds(30),
            ),
            ConnectionEventInsert(
              two.routerIdA,
              Some(macA),
              HostId.Fqdn(Hostname.unsafe("a2.example.com")),
              None,
              true,
              BlockReason.fromWire("allowed"),
              now.minusSeconds(20),
            ),
            ConnectionEventInsert(
              two.routerIdA,
              Some(macA),
              HostId.Fqdn(Hostname.unsafe("ads.example.com")),
              None,
              false,
              BlockReason.fromWire("category:adult"),
              now.minusSeconds(10),
            ),
          ),
        )
        // Populate the hourly rollup so the 24h totals/top tiles have data to scope.
        _      <- cer.rerollConnEventsHourly(now.minus(java.time.Duration.ofHours(2)))
        statsA <- cer.stats(two.hhA)
        statsB <- cer.stats(two.hhB)
      } yield
      // Positive (sees-own-data). hh-A is `HouseholdId.Default`, so this ALSO proves the null-safe
      // scope is a NO-OP for the single backfill household — it drops none of A's legitimately-owned
      // rows (the #2258 regression lesson).
      assertTrue(
        statsA.totalHour == 3,
        statsA.blockedHour == 1,
        statsA.totalToday == 3,
        statsA.blockedToday == 1,
        statsA.topBlocked.map(_.host).contains(HostId.Fqdn(Hostname.unsafe("ads.example.com"))),
      ) &&
        // Negative (leak pin, #2282). hh-B has NO routers/events, so every count is ZERO even while
        // hh-A has activity — the brand-new-household cross-tenant leak this fixes.
        assertTrue(
          statsB.totalHour == 0,
          statsB.blockedHour == 0,
          statsB.totalToday == 0,
          statsB.blockedToday == 0,
          statsB.topBlocked.isEmpty,
        )
    },
    test("pin 1 — GET /api/time/status returns ONLY the caller's household profiles") {
      for {
        _    <- cleanDb
        two  <- TestLayers.seedTwoHouseholds(macA, macB)
        dr   <- ZIO.service[DeviceRepo]
        tlr  <- ZIO.service[TimeLimitRepo]
        atlr <- ZIO.service[AppTimeLimitRepo]
        tr   <- ZIO.service[TrafficReportRepo]
        er   <- ZIO.service[TimeExtensionRepo]
        pr   <- ZIO.service[ProfileRepo]
        up   <- ZIO.service[UserProfileRepo]
        hsr  <- ZIO.service[HouseholdSettingsRepo]
        clk  <- ZIO.service[Clock]
        tss = new TimeStatusServiceLive(pr, tlr, atlr, dr, tr, er)
        auth   <- makeAuth
        tokenA <- login(auth, two.adminA, two.password)
        tokenB <- login(auth, two.adminB, two.password, Some(two.slugB))
        routes = TimeRoutes.routes(auth, dr, tlr, atlr, tr, er, pr, up, hsr, tss, clk)
        (sA, bodyA) <- getJson(routes, "/api/time/status", tokenA)
        (sB, bodyB) <- getJson(routes, "/api/time/status", tokenB)
      } yield assertTrue(sA == Status.Ok, sB == Status.Ok) &&
        assertTrue(
          bodyA.contains(s""""profileId":${two.profileA.value}""") || bodyA.contains(
            s""""id":${two.profileA.value}""",
          ),
        ) &&
        assertTrue(!bodyA.contains(s""""profileId":${two.profileB.value}""")) &&
        assertTrue(!bodyB.contains(s""""profileId":${two.profileA.value}"""))
    },
    // ── Pin 2: user WRITE isolation ────────────────────────────────────────────
    test("pin 2 — hh-A admin cannot write hh-B's profile (404, row untouched)") {
      for {
        _      <- cleanDb
        two    <- TestLayers.seedTwoHouseholds(macA, macB)
        pr     <- ZIO.service[ProfileRepo]
        tlr    <- ZIO.service[TimeLimitRepo]
        up     <- ZIO.service[UserProfileRepo]
        ur     <- ZIO.service[UserRepo]
        nsr    <- ZIO.service[NamedScheduleRepo]
        xa     <- ZIO.service[Transactor[Task]]
        auth   <- makeAuth
        tokenA <- login(auth, two.adminA, two.password)
        routes = ProfileRoutes.routes(auth, pr, tlr, up, ur, nsr)
        // hh-A admin attempts to attach schedules to hh-B's profile.
        resp  <- routes.runZIO(
          Request
            .put(
              URL.decode(s"/api/profiles/${two.profileB.value}/schedules").toOption.get,
              Body.fromString(SetProfileSchedulesRequest(Nil).toJson),
            )
            .addHeader(Header.Authorization.Bearer(tokenA)),
        )
        // hh-B's profile name is unchanged (write never landed).
        nameB <- sql"SELECT name FROM profiles WHERE id=${two.profileB}"
          .query[String]
          .unique
          .transact(xa)
      } yield assertTrue(resp.status == Status.NotFound || resp.status == Status.Forbidden) &&
        assertTrue(nameB == "B-Kids")
    },
    test("pin 2 — hh-A admin cannot write hh-B's device by MAC (404, row untouched)") {
      for {
        _      <- cleanDb
        two    <- TestLayers.seedTwoHouseholds(macA, macB)
        dr     <- ZIO.service[DeviceRepo]
        up     <- ZIO.service[UserProfileRepo]
        pr     <- ZIO.service[ProfileRepo]
        xa     <- ZIO.service[Transactor[Task]]
        auth   <- makeAuth
        tokenA <- login(auth, two.adminA, two.password)
        routes = DeviceRoutes.routes(auth, dr, up, pr)
        // hh-A admin attempts to rename hh-B's device.
        resp  <- routes.runZIO(
          Request
            .patch(
              URL.decode(s"/api/devices/${macB.value}").toOption.get,
              Body.fromString("""{"name":"HIJACKED"}"""),
            )
            .addHeader(Header.Authorization.Bearer(tokenA)),
        )
        nameB <- sql"SELECT name FROM devices WHERE mac=$macB AND household_id=${two.hhB}"
          .query[String]
          .unique
          .transact(xa)
      } yield assertTrue(resp.status == Status.NotFound || resp.status == Status.Forbidden) &&
        assertTrue(nameB == "devB")
    },
    test("pin 2 — hh-B admin POST /api/users creates the user in household B, invisible to A") {
      // #2130: UserRepo.create used to omit household_id, so the INSERT fell back to V65's
      // `DEFAULT 1` and a household-B admin's new user was planted in household A(=1) — a
      // cross-tenant write the B admin couldn't even see back through the scoped read.
      for {
        _      <- cleanDb
        two    <- TestLayers.seedTwoHouseholds(macA, macB)
        ur     <- ZIO.service[UserRepo]
        up     <- ZIO.service[UserProfileRepo]
        xa     <- ZIO.service[Transactor[Task]]
        auth   <- makeAuth
        tokenA <- login(auth, two.adminA, two.password)
        tokenB <- login(auth, two.adminB, two.password, Some(two.slugB))
        routes     = AuthRoutes.routes(auth, ur, up, RateLimiter.allowAll)
        createBody = CreateUserRequest("kid-b", "kid-b-password!", UserRole.Child, Nil).toJson
        resp        <- routes.runZIO(
          Request
            .post(URL.decode("/api/users").toOption.get, Body.fromString(createBody))
            .addHeader(Header.Authorization.Bearer(tokenB))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
        // The row is stamped with household B, not V65's DEFAULT 1.
        hh          <- sql"SELECT household_id FROM users WHERE username='kid-b'"
          .query[HouseholdId]
          .unique
          .transact(xa)
        // Visible to B's admin via the scoped read, invisible to A's.
        (sB, bodyB) <- getJson(routes, "/api/users", tokenB)
        (sA, bodyA) <- getJson(routes, "/api/users", tokenA)
        // The new user can log in (via household B's slug/username identifier) and the minted JWT
        // carries hh = B (#2105). #2164: kid-b lives in household B, so login names it — a bare
        // username would resolve to the default household, where kid-b does not exist.
        kidLogin    <- auth
          .login(s"${two.slugB}/kid-b", "kid-b-password!")
          .mapError(e => new RuntimeException(s"login failed: $e"))
        kidClaims   <- auth
          .verify(kidLogin.token.value)
          .mapError(e => new RuntimeException(s"verify failed: $e"))
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(hh == two.hhB) &&
        assertTrue(sB == Status.Ok, bodyB.contains(""""username":"kid-b"""")) &&
        assertTrue(sA == Status.Ok, !bodyA.contains("kid-b")) &&
        assertTrue(kidClaims.hh == two.hhB)
    },
    // ── Pin 1b: single-identifier login resolves the household from the identifier (#2164) ──────
    // Design §4 / §9: the login UI has no household field; the identifier itself carries the
    // household (email '@' / slug/username '/' / bare → default). These pins prove the server-side
    // resolution over two real households.
    test("pin 1b — slug/username composite routes each user to its own household") {
      for {
        _    <- cleanDb
        two  <- TestLayers.seedTwoHouseholds(macA, macB)
        auth <- makeAuth
        // adminA lives in the default household (slug `default`), adminB in `household-b`. Each is
        // reached via its own slug/username identifier and mints its own hh claim. Usernames are
        // distinct here only because V65's global users_username_key is still present
        // (TODO(#2147)); the CODE path findByUsername(hh, u) already handles a shared username, and
        // the per-slug routing below is exactly the achievable-and-equivalent property.
        tokA <- login(auth, two.adminA, two.password, Some(two.slugA))
        tokB <- login(auth, two.adminB, two.password, Some(two.slugB))
        clA  <- auth.verify(tokA)
        clB  <- auth.verify(tokB)
      } yield assertTrue(clA.hh == two.hhA, clB.hh == two.hhB, clA.hh != clB.hh)
    },
    test("pin 1b — email login lands in exactly the email owner's household") {
      for {
        _     <- cleanDb
        two   <- TestLayers.seedTwoHouseholds(macA, macB)
        xa    <- ZIO.service[Transactor[Task]]
        // Bind a globally-unique email to each household's admin (V67 users.email; in prod the
        // invite-accept flow does this, #2132). The '@' identifier resolves the household with no
        // slug in hand.
        _     <-
          sql"UPDATE users SET email='a@example.com' WHERE username='admin' AND household_id=${two.hhA}".update.run
            .transact(xa)
        _     <-
          sql"UPDATE users SET email='b@example.com' WHERE username='adminB' AND household_id=${two.hhB}".update.run
            .transact(xa)
        auth  <- makeAuth
        respA <- auth
          .login("a@example.com", two.password)
          .mapError(e => new RuntimeException(s"$e"))
        respB <- auth
          .login("b@example.com", two.password)
          .mapError(e => new RuntimeException(s"$e"))
        clA   <- auth.verify(respA.token.value)
        clB   <- auth.verify(respB.token.value)
      } yield assertTrue(clA.hh == two.hhA, clA.sub == "admin") &&
        assertTrue(clB.hh == two.hhB, clB.sub == "adminB") &&
        // The response carries the resolved slug so the SPA can (re)write its wh_household cookie.
        assertTrue(respA.householdSlug == Some(two.slugA), respB.householdSlug == Some(two.slugB))
    },
    test("pin 1b — a bare username resolves to the DEFAULT household (self-hosted back-compat)") {
      for {
        _    <- cleanDb
        two  <- TestLayers.seedTwoHouseholds(macA, macB)
        auth <- makeAuth
        resp <- auth.login(two.adminA, two.password).mapError(e => new RuntimeException(s"$e"))
        cl   <- auth.verify(resp.token.value)
      } yield assertTrue(cl.hh == HouseholdId.Default, cl.sub == "admin")
    },
    test(
      "pin 1b — wrong slug / unknown email fail IDENTICALLY to a bad password (no enumeration)",
    ) {
      // The observable outcome of every failure mode is the SAME AuthError.InvalidCredentials.
      // Timing uniformity is structural, not asserted here (that would be flaky): every path runs
      // exactly one bcrypt verify — real hash on a hit, a fixed dummy hash on a miss (AuthService).
      for {
        _            <- cleanDb
        two          <- TestLayers.seedTwoHouseholds(macA, macB)
        xa           <- ZIO.service[Transactor[Task]]
        _            <-
          sql"UPDATE users SET email='b@example.com' WHERE username='adminB' AND household_id=${two.hhB}".update.run
            .transact(xa)
        auth         <- makeAuth
        // Right password, but the WRONG household slug for adminB (adminB is not in the default hh).
        wrongSlug    <- auth.login(s"${two.slugA}/adminB", two.password).either
        // A syntactically-valid but UNKNOWN household slug.
        unknownSlug  <- auth.login("no-such-house/adminB", two.password).either
        // A well-formed email that maps to NO user.
        unknownEmail <- auth.login("nobody@example.com", two.password).either
        // The genuine wrong-password baseline (right email, wrong password).
        badPw        <- auth.login("b@example.com", "wrongpassword").either
      } yield assertTrue(badPw == Left(AuthError.InvalidCredentials)) &&
        assertTrue(wrongSlug == badPw, unknownSlug == badPw, unknownEmail == badPw)
    },
    test("pin 1b — a cookie-assisted bare login (client-composed slug/username) reaches its hh") {
      for {
        _    <- cleanDb
        two  <- TestLayers.seedTwoHouseholds(macA, macB)
        auth <- makeAuth
        // The SPA, holding a wh_household cookie of `household-b`, composes `household-b/adminB` from
        // a bare `adminB` before POSTing (LoginPage.composeIdentifier). Server-side that is the
        // slug/username form and must land in household B.
        resp <- auth
          .login(s"${two.slugB}/${two.adminB}", two.password)
          .mapError(e => new RuntimeException(s"$e"))
        cl   <- auth.verify(resp.token.value)
      } yield assertTrue(
        cl.hh == two.hhB,
        cl.sub == "adminB",
        resp.householdSlug == Some(two.slugB),
      )
    },
    // ── Pin 3: snapshot scoping (re-asserted from #2107) ───────────────────────
    test("pin 3 — GET /api/router/policy returns ONLY the router's household MACs/profiles") {
      for {
        _   <- cleanDb
        two <- TestLayers.seedTwoHouseholds(macA, macB)
        ber <- ZIO.service[BlockEventRepo]
        rr  <- ZIO.service[RouterRepo]
        ps  <- makePolicyService
        routes = RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)
        (_, bodyA) <- getJson(routes, "/api/router/policy", two.tokenA)
        snapA <- ZIO.fromEither(bodyA.fromJson[PolicySnapshot]).mapError(new RuntimeException(_))
      } yield assertTrue(snapA.devices.contains(macA), !snapA.devices.contains(macB)) &&
        assertTrue(snapA.profiles.contains(two.profileA), !snapA.profiles.contains(two.profileB))
    },
    // ── Pin 4a: ingest write scoping — hh-A writes only under (hhA, mac) ────────
    test("pin 4a — hh-A router usage writes under its own household; hh-B rows byte-identical") {
      for {
        _   <- cleanDb
        two <- TestLayers.seedTwoHouseholds(macA, macB)
        rr  <- ZIO.service[RouterRepo]
        tr  <- ZIO.service[TrafficReportRepo]
        tu  <- ZIO.service[TimeUsageRepo]
        dr  <- ZIO.service[DeviceRepo]
        cer <- ZIO.service[ConnectionEventRepo]
        ar  <- ZIO.service[AlertRepo]
        hsr <- ZIO.service[HouseholdSettingsRepo]
        xa  <- ZIO.service[Transactor[Task]]
        auth   = RouterAuthLive(rr)
        routes = RouterIngestRoutes.routes(auth, rr, tr, tu, dr, cer, ar, hsr)
        // Snapshot hh-B's device + time_usage state BEFORE the hh-A ingest.
        hhBDevBefore   <- sql"SELECT name FROM devices WHERE household_id=${two.hhB} ORDER BY mac"
          .query[String]
          .to[List]
          .transact(xa)
        hhBUsageBefore <- sql"SELECT COUNT(*) FROM time_usage WHERE household_id=${two.hhB}"
          .query[Long]
          .unique
          .transact(xa)
        rec  = UsageRecord(
          macA,
          Some(IpAddress.unsafe("192.168.1.10")),
          HostId.Fqdn(Hostname.unsafe("youtube.com")),
          240L,
          1000L,
          500L,
        )
        body = UsageReport(
          two.routerIdA,
          "2026-05-07T14:00:00Z",
          "2026-05-07T14:05:00Z",
          List(rec),
        ).toJson
        resp          <- routes.runZIO(
          Request
            .post(URL.decode("/api/router/usage").toOption.get, Body.fromString(body))
            .addHeader(Header.ContentType(MediaType.application.json))
            .addHeader(Header.Authorization.Bearer(two.tokenA)),
        )
        // hh-A's usage landed under household A.
        hhAUsage      <-
          sql"SELECT COUNT(*) FROM time_usage WHERE household_id=${two.hhA} AND device_mac=$macA"
            .query[Long]
            .unique
            .transact(xa)
        // hh-B is byte-identical.
        hhBDevAfter   <- sql"SELECT name FROM devices WHERE household_id=${two.hhB} ORDER BY mac"
          .query[String]
          .to[List]
          .transact(xa)
        hhBUsageAfter <- sql"SELECT COUNT(*) FROM time_usage WHERE household_id=${two.hhB}"
          .query[Long]
          .unique
          .transact(xa)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(hhAUsage == 1L) &&
        assertTrue(hhBDevBefore == hhBDevAfter) &&
        assertTrue(hhBUsageBefore == hhBUsageAfter)
    },
    // ── Pin 4b: new-device discovery preserved, stamped into the router's hh ────
    test(
      "pin 4b — hh-A router reporting a never-seen MAC creates an unmanaged device in hhA only",
    ) {
      for {
        _   <- cleanDb
        two <- TestLayers.seedTwoHouseholds(macA, macB)
        rr  <- ZIO.service[RouterRepo]
        tr  <- ZIO.service[TrafficReportRepo]
        tu  <- ZIO.service[TimeUsageRepo]
        dr  <- ZIO.service[DeviceRepo]
        cer <- ZIO.service[ConnectionEventRepo]
        ar  <- ZIO.service[AlertRepo]
        hsr <- ZIO.service[HouseholdSettingsRepo]
        xa  <- ZIO.service[Transactor[Task]]
        auth       = RouterAuthLive(rr)
        routes     = RouterIngestRoutes.routes(auth, rr, tr, tu, dr, cer, ar, hsr)
        // A first_seen_mac event for a never-before-seen MAC.
        eventsBody =
          s"""{"routerId":"${two.routerIdA}","events":[{"type":"first_seen_mac","mac":"${macC.value}","ts":"2026-05-07T14:00:00Z"}]}"""
        resp    <- routes.runZIO(
          Request
            .post(URL.decode("/api/router/events").toOption.get, Body.fromString(eventsBody))
            .addHeader(Header.ContentType(MediaType.application.json))
            .addHeader(Header.Authorization.Bearer(two.tokenA)),
        )
        // The new device is unmanaged (profile_id NULL) and stamped household A.
        row     <- sql"SELECT household_id, profile_id IS NULL FROM devices WHERE mac=$macC"
          .query[(HouseholdId, Boolean)]
          .to[List]
          .transact(xa)
        // It exists in NO other household.
        otherHh <- sql"SELECT COUNT(*) FROM devices WHERE mac=$macC AND household_id <> ${two.hhA}"
          .query[Long]
          .unique
          .transact(xa)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(row == List((two.hhA, true))) &&
        assertTrue(otherHh == 0L)
    },
    // ── Pin 6: router-cap entitlement is scoped per household (#2134) ───────────
    test("pin 6 — hh-B's router count never affects hh-A's router-cap check") {
      // hh-A (household 1) is backfilled to router_cap 10; hh-B defaults to 1 and is seeded with one
      // router already (gwB) — i.e. hh-B is AT its cap. hh-A's create must still succeed: each
      // household's cap check counts ONLY its own routers, never the other tenant's.
      for {
        _      <- cleanDb
        two    <- TestLayers.seedTwoHouseholds(macA, macB)
        rr     <- ZIO.service[RouterRepo]
        ur     <- ZIO.service[UserRepo]
        en     <- ZIO.service[EntitlementsRepo]
        xa     <- ZIO.service[Transactor[Task]]
        auth   <- makeAuth
        // Confirm the fixture puts hh-B at its cap of 1 (default), while hh-A sits at 10.
        capB   <- sql"SELECT router_cap FROM households WHERE id=${two.hhB}"
          .query[Int]
          .unique
          .transact(xa)
        countB <- rr.listAllForHousehold(two.hhB).map(_.size)
        tokenA <- login(auth, two.adminA, two.password)
        routes = AdminRouterRoutes.routes(auth, rr, ur, en)
        respA       <- routes.runZIO(
          Request
            .post(
              URL.decode("/api/admin/routers").toOption.get,
              Body.fromString(CreateRouterRequest("gwA-2").toJson),
            )
            .addHeader(Header.Authorization.Bearer(tokenA)),
        )
        // hh-A gained a router; hh-B's count is untouched by A's create.
        countAAfter <- rr.listAllForHousehold(two.hhA).map(_.size)
        countBAfter <- rr.listAllForHousehold(two.hhB).map(_.size)
      } yield assertTrue(capB == 1, countB == 1) &&
        assertTrue(respA.status == Status.Ok) &&
        assertTrue(countAAfter == 2, countBAfter == 1)
    },
    // ── Pin 5: blocklist authorization over a SHARED global catalog ─────────────
    test("pin 5 — GET /api/blocklists/<id> serves byte-identical content to both households") {
      for {
        _   <- cleanDb
        two <- TestLayers.seedTwoHouseholds(macA, macB)
        blr <- ZIO.service[BlocklistRepo]
        _   <- blr.insertBatch(List(("doubleclick.net", "kidsafe"), ("ads.example.com", "kidsafe")))
        ber <- ZIO.service[BlockEventRepo]
        rr  <- ZIO.service[RouterRepo]
        ps  <- makePolicyService
        routes = RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)
        (sA, bodyA) <- getJson(routes, "/api/blocklists/kidsafe", two.tokenA)
        (sB, bodyB) <- getJson(routes, "/api/blocklists/kidsafe", two.tokenB)
      } yield assertTrue(sA == Status.Ok, sB == Status.Ok) &&
        assertTrue(bodyA == bodyB, bodyA.contains("doubleclick.net\n"))
    },
    // ── Pin (#2251 / #2120): dashboard NOW (GET + ws push) is household-scoped ───
    // DashboardNowRoutes read `deviceRepo.listAll` / `profileRepo.listAll` (GLOBAL) then only
    // ROLE-narrowed, so a household-B admin saw household A's devices/profiles in the NOW section
    // (design §2 gaps 3+4). The fix scopes the reads (GET + ws push + online-now) to `claims.hh`.
    test("pin (#2251) — GET /api/dashboard/now returns ONLY the caller's household entities") {
      for {
        _    <- cleanDb
        two  <- TestLayers.seedTwoHouseholds(macA, macB)
        tr   <- ZIO.service[TrafficReportRepo]
        cer  <- ZIO.service[ConnectionEventRepo]
        dr   <- ZIO.service[DeviceRepo]
        pr   <- ZIO.service[ProfileRepo]
        up   <- ZIO.service[UserProfileRepo]
        atlr <- ZIO.service[AppTimeLimitRepo]
        clk  <- ZIO.service[Clock]
        // Make each household's device "online now" — a connection_event within the 5m recent
        // window of the test clock (Mon 2025-01-06 14:00Z), under its OWN router. This exercises the
        // online-now (`lastSeenByMacSince`) scope too: B must not see A's online MAC.
        ts = Instant.parse("2025-01-06T13:59:30Z")
        _      <- cer.insertBatch(
          List(
            ConnectionEventInsert(
              two.routerIdA,
              Some(macA),
              HostId.Fqdn(Hostname.unsafe("a.example.com")),
              None,
              true,
              BlockReason.fromWire("allow"),
              ts,
            ),
            ConnectionEventInsert(
              two.routerIdB,
              Some(macB),
              HostId.Fqdn(Hostname.unsafe("b.example.com")),
              None,
              true,
              BlockReason.fromWire("allow"),
              ts,
            ),
          ),
        )
        auth   <- makeAuth
        tokenA <- login(auth, two.adminA, two.password)
        tokenB <- login(auth, two.adminB, two.password, Some(two.slugB))
        routes = DashboardNowRoutes.routes(auth, tr, cer, dr, pr, up, atlr, clk)
        (sA, bodyA) <- getJson(routes, "/api/dashboard/now", tokenA)
        (sB, bodyB) <- getJson(routes, "/api/dashboard/now", tokenB)
      } yield assertTrue(sA == Status.Ok, sB == Status.Ok) &&
        // Positive (sees-own-data): A's admin sees A's profile + A's online device.
        assertTrue(bodyA.contains("A-Kids"), bodyA.contains(macA.value)) &&
        // Negative: A never sees B's profile name or online MAC (even as admin).
        assertTrue(!bodyA.contains("B-Kids"), !bodyA.contains(macB.value)) &&
        // Positive: B's admin sees B's own profile.
        assertTrue(bodyB.contains("B-Kids")) &&
        // Negative: B never sees A's profile name or A's online MAC (online-now scope).
        assertTrue(!bodyB.contains("A-Kids"), !bodyB.contains(macA.value))
    },
    test("pin (#2251) — a newly provisioned empty household's NOW carries no other household") {
      for {
        _     <- cleanDb
        two   <- TestLayers.seedTwoHouseholds(macA, macB)
        tr    <- ZIO.service[TrafficReportRepo]
        cer   <- ZIO.service[ConnectionEventRepo]
        dr    <- ZIO.service[DeviceRepo]
        pr    <- ZIO.service[ProfileRepo]
        up    <- ZIO.service[UserProfileRepo]
        atlr  <- ZIO.service[AppTimeLimitRepo]
        clk   <- ZIO.service[Clock]
        // Household A has an online device — noise the empty tenant must not see.
        _     <- cer.insertBatch(
          List(
            ConnectionEventInsert(
              two.routerIdA,
              Some(macA),
              HostId.Fqdn(Hostname.unsafe("a.example.com")),
              None,
              true,
              BlockReason.fromWire("allow"),
              Instant.parse("2025-01-06T13:59:30Z"),
            ),
          ),
        )
        auth  <- makeAuth
        opTok <- login(auth, two.adminA, two.password)
        bt    <- makeBetaRoutes
        (_, betaRoutes) = bt
        prov <- provisionHousehold(betaRoutes, opTok, "now-c@example.com", "NOW C Household")
        (cSlug, _) = prov
        cTok <- login(auth, "admin", "supersecret123", Some(cSlug))
        routes = DashboardNowRoutes.routes(auth, tr, cer, dr, pr, up, atlr, clk)
        (sC, bodyC) <- getJson(routes, "/api/dashboard/now", cTok)
      } yield assertTrue(sC == Status.Ok) &&
        // Empty NOW: zero profiles, and NONE of household A/B's names or MACs.
        assertTrue(bodyC.contains(""""profiles":[]""")) &&
        assertTrue(!bodyC.contains("A-Kids"), !bodyC.contains("B-Kids")) &&
        assertTrue(!bodyC.contains(macA.value), !bodyC.contains(macB.value))
    },
    test("pin (#2251 / #2120) — the ws `now` push carries ONLY the caller's household entities") {
      (for {
        _      <- cleanDb
        two    <- TestLayers.seedTwoHouseholds(macA, macB)
        clk    <- ZIO.service[Clock]
        tr     <- ZIO.service[TrafficReportRepo]
        rlr    <- ZIO.service[RollupRepo]
        cer    <- ZIO.service[ConnectionEventRepo]
        dr     <- ZIO.service[DeviceRepo]
        pr     <- ZIO.service[ProfileRepo]
        apr    <- ZIO.service[AppRepo]
        atlr   <- ZIO.service[AppTimeLimitRepo]
        auth   <- makeAuth
        tokenA <- login(auth, two.adminA, two.password)
        tokenB <- login(auth, two.adminB, two.password, Some(two.slugB))
        reg    <- SpaWsRegistry.make
        bus    <- SpaEventBus.make
        routes = SpaWsRoutes.routes(
          auth,
          reg,
          clk,
          WsConfig(allowedOrigins = "", expiryCheckSeconds = 60),
        )
        port <- Server.install(routes)
        out  <- ZIO.scoped {
          SpaPush.run(bus, reg, tr, rlr, cer, dr, pr, apr, atlr, clk) *>
            wsNowIsolation(port, tokenA, tokenB, bus)
        }
        (aFrame, bFrame) = out
      } yield assertTrue(aFrame.isDefined, bFrame.isDefined) &&
        // A's live NOW push has A's profile, never B's; B's has B's, never A's.
        assertTrue(aFrame.exists(f => f.contains("A-Kids") && !f.contains("B-Kids"))) &&
        assertTrue(bFrame.exists(f => f.contains("B-Kids") && !f.contains("A-Kids"))))
        .provideSome[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]](
          Server.defaultWithPort(0),
          Client.default,
        )
    },
    test(
      "pin (#2257) — the ws `timeStatus` push carries ONLY the caller's household profiles",
    ) {
      (for {
        _      <- cleanDb
        two    <- TestLayers.seedTwoHouseholds(macA, macB)
        clk    <- ZIO.service[Clock]
        tr     <- ZIO.service[TrafficReportRepo]
        rlr    <- ZIO.service[RollupRepo]
        cer    <- ZIO.service[ConnectionEventRepo]
        dr     <- ZIO.service[DeviceRepo]
        pr     <- ZIO.service[ProfileRepo]
        apr    <- ZIO.service[AppRepo]
        atlr   <- ZIO.service[AppTimeLimitRepo]
        tlr    <- ZIO.service[TimeLimitRepo]
        extR   <- ZIO.service[TimeExtensionRepo]
        hsR    <- ZIO.service[HouseholdSettingsRepo]
        upR    <- ZIO.service[UserProfileRepo]
        auth   <- makeAuth
        tokenA <- login(auth, two.adminA, two.password)
        tokenB <- login(auth, two.adminB, two.password, Some(two.slugB))
        reg    <- SpaWsRegistry.make
        bus    <- SpaEventBus.make
        timeStatus = new TimeStatusServiceLive(pr, tlr, atlr, dr, tr, extR)
        routes     = SpaWsRoutes.routes(
          auth,
          reg,
          clk,
          WsConfig(allowedOrigins = "", expiryCheckSeconds = 60),
        )
        port <- Server.install(routes)
        subs = List(
          """{"op":"hello"}""",
          """{"op":"subscribe","payload":{"topic":"timeStatus"}}""",
        )
        out <- ZIO.scoped {
          SpaPush.run(
            bus,
            reg,
            tr,
            rlr,
            cer,
            dr,
            pr,
            apr,
            atlr,
            clk,
            Some(SpaPush.TimeUsageDeps(timeStatus, hsR, upR)),
          ) *>
            wsIsolation(
              port,
              tokenA,
              tokenB,
              subs,
              subs,
              "timeStatus",
              bus.publish(SpaEvent.TimeStatusChanged),
            )
        }
        (aFrame, bFrame) = out
      } yield assertTrue(aFrame.isDefined, bFrame.isDefined) &&
        assertTrue(aFrame.exists(f => f.contains("A-Kids") && !f.contains("B-Kids"))) &&
        assertTrue(bFrame.exists(f => f.contains("B-Kids") && !f.contains("A-Kids"))))
        .provideSome[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]](
          Server.defaultWithPort(0),
          Client.default,
        )
    },
    test(
      "pin (#2257) — the ws `appUsage` push refuses another household's profileId",
    ) {
      (for {
        _      <- cleanDb
        two    <- TestLayers.seedTwoHouseholds(macA, macB)
        clk    <- ZIO.service[Clock]
        tr     <- ZIO.service[TrafficReportRepo]
        rlr    <- ZIO.service[RollupRepo]
        cer    <- ZIO.service[ConnectionEventRepo]
        dr     <- ZIO.service[DeviceRepo]
        pr     <- ZIO.service[ProfileRepo]
        apr    <- ZIO.service[AppRepo]
        atlr   <- ZIO.service[AppTimeLimitRepo]
        tlr    <- ZIO.service[TimeLimitRepo]
        extR   <- ZIO.service[TimeExtensionRepo]
        hsR    <- ZIO.service[HouseholdSettingsRepo]
        upR    <- ZIO.service[UserProfileRepo]
        auth   <- makeAuth
        tokenA <- login(auth, two.adminA, two.password)
        tokenB <- login(auth, two.adminB, two.password, Some(two.slugB))
        reg    <- SpaWsRegistry.make
        bus    <- SpaEventBus.make
        timeStatus = new TimeStatusServiceLive(pr, tlr, atlr, dr, tr, extR)
        routes     = SpaWsRoutes.routes(
          auth,
          reg,
          clk,
          WsConfig(allowedOrigins = "", expiryCheckSeconds = 60),
        )
        port <- Server.install(routes)
        // BOTH clients subscribe to household-A's profile id. A is entitled (it is A's own household);
        // B (household B) must be REFUSED — the pid is not in B's household, so B receives no frame.
        subs = List(
          """{"op":"hello"}""",
          s"""{"op":"subscribe","payload":{"topic":"appUsage","params":{"profileId":${two.profileA.value}}}}""",
        )
        out <- ZIO.scoped {
          SpaPush.run(
            bus,
            reg,
            tr,
            rlr,
            cer,
            dr,
            pr,
            apr,
            atlr,
            clk,
            Some(SpaPush.TimeUsageDeps(timeStatus, hsR, upR)),
          ) *>
            wsIsolation(
              port,
              tokenA,
              tokenB,
              subs,
              subs,
              "appUsage",
              bus.publish(SpaEvent.TimeStatusChanged),
            )
        }
        (aFrame, bFrame) = out
      } yield
      // A sees its OWN profile's app usage; B never receives another household's profile's usage.
      assertTrue(aFrame.isDefined, bFrame.isEmpty))
        .provideSome[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]](
          Server.defaultWithPort(0),
          Client.default,
        )
    },
    test(
      "pin (#2257) — the ws `trafficUsage` push carries ONLY the caller's household traffic",
    ) {
      val periodStart = java.time.Instant.parse("2026-06-25T14:00:00Z")
      val periodEnd   = periodStart.plusSeconds(60)
      val date        = java.time.LocalDate.parse("2026-06-25")
      (for {
        _      <- cleanDb
        two    <- TestLayers.seedTwoHouseholds(macA, macB)
        clk    <- ZIO.service[Clock]
        tr     <- ZIO.service[TrafficReportRepo]
        rlr    <- ZIO.service[RollupRepo]
        cer    <- ZIO.service[ConnectionEventRepo]
        dr     <- ZIO.service[DeviceRepo]
        pr     <- ZIO.service[ProfileRepo]
        apr    <- ZIO.service[AppRepo]
        atlr   <- ZIO.service[AppTimeLimitRepo]
        // Seed one traffic row per household so each household's head bucket has data. The row is
        // attributed to that household's device/router; the scoped device read decides which bucket
        // each subscriber's body is built from.
        _      <- tr.insertBatch(
          List(
            TrafficReportInsert(
              two.routerIdA,
              macA,
              None,
              HostId.Fqdn(Hostname.unsafe("a.example")),
              date,
              periodStart,
              periodEnd,
              60,
              1000L,
              2000L,
            ),
            TrafficReportInsert(
              two.routerIdB,
              macB,
              None,
              HostId.Fqdn(Hostname.unsafe("b.example")),
              date,
              periodStart,
              periodEnd,
              60,
              3000L,
              4000L,
            ),
          ),
        )
        auth   <- makeAuth
        tokenA <- login(auth, two.adminA, two.password)
        tokenB <- login(auth, two.adminB, two.password, Some(two.slugB))
        reg    <- SpaWsRegistry.make
        bus    <- SpaEventBus.make
        routes = SpaWsRoutes.routes(
          auth,
          reg,
          clk,
          WsConfig(allowedOrigins = "", expiryCheckSeconds = 60),
        )
        port <- Server.install(routes)
        // Identical params in BOTH households (household-wide, group by profile) — the leak the fix
        // closes is exactly two households sharing a param-set and one global body reaching both.
        subs = List(
          """{"op":"hello"}""",
          """{"op":"subscribe","payload":{"topic":"trafficUsage","params":{"groupBy":["profile"],"bucket":"1m"}}}""",
        )
        out <- ZIO.scoped {
          SpaPush.run(bus, reg, tr, rlr, cer, dr, pr, apr, atlr, clk) *>
            wsIsolation(
              port,
              tokenA,
              tokenB,
              subs,
              subs,
              "trafficUsage",
              bus.publish(SpaEvent.UsageIngested(periodStart, periodEnd)),
            )
        }
        (aFrame, bFrame) = out
      } yield assertTrue(aFrame.isDefined, bFrame.isDefined) &&
        assertTrue(aFrame.exists(f => f.contains("A-Kids") && !f.contains("B-Kids"))) &&
        assertTrue(bFrame.exists(f => f.contains("B-Kids") && !f.contains("A-Kids"))))
        .provideSome[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]](
          Server.defaultWithPort(0),
          Client.default,
        )
    },
    // ── Provisioning pins (#2132): a newly-provisioned household is isolated ─────
    test("pin (#2132) — a newly provisioned household's admin sees ZERO other-household rows") {
      for {
        _     <- cleanDb
        two   <- TestLayers.seedTwoHouseholds(macA, macB)
        auth  <- makeAuth
        opTok <- login(auth, two.adminA, two.password) // adminA is the operator (household 1)
        bt    <- makeBetaRoutes
        (_, betaRoutes) = bt
        prov <- provisionHousehold(betaRoutes, opTok, "c@example.com", "C Household")
        (cSlug, _) = prov
        // The freshly-provisioned admin (`admin`) logs into household C by its slug (#2140).
        cTok <- login(auth, "admin", "supersecret123", Some(cSlug))
        pr   <- ZIO.service[ProfileRepo]
        tlr  <- ZIO.service[TimeLimitRepo]
        up   <- ZIO.service[UserProfileRepo]
        ur   <- ZIO.service[UserRepo]
        nsr  <- ZIO.service[NamedScheduleRepo]
        dr   <- ZIO.service[DeviceRepo]
        profRoutes = ProfileRoutes.routes(auth, pr, tlr, up, ur, nsr)
        devRoutes  = DeviceRoutes.routes(auth, dr, up, pr)
        (sP, bodyP) <- getJson(profRoutes, "/api/profiles", cTok)
        (sD, bodyD) <- getJson(devRoutes, "/api/devices", cTok)
      } yield assertTrue(sP == Status.Ok, sD == Status.Ok) &&
        // Sees NEITHER household A's nor household B's profiles…
        assertTrue(!bodyP.contains(s""""id":${two.profileA.value}""")) &&
        assertTrue(!bodyP.contains(s""""id":${two.profileB.value}""")) &&
        // …nor their devices.
        assertTrue(!bodyD.contains(macA.value), !bodyD.contains(macB.value))
    },
    test("pin (#2132) — a non-operator (household-B) admin cannot read or approve beta_requests") {
      for {
        _     <- cleanDb
        two   <- TestLayers.seedTwoHouseholds(macA, macB)
        auth  <- makeAuth
        opTok <- login(auth, two.adminA, two.password)
        // household B admin — NOT the operator; #2140: name household B by its slug.
        bTok  <- login(auth, two.adminB, two.password, Some(two.slugB))
        bt    <- makeBetaRoutes
        (_, betaRoutes) = bt
        // Seed a pending request so there is something an over-reaching admin could try to act on.
        // #2217: the intake now requires a household name.
        _             <- postJson(
          betaRoutes,
          "/api/beta/request",
          None,
          CreateBetaRequest("x@example.com", Some("Over Reach House")).toJson,
        )
        (_, lst)      <- getJson(betaRoutes, "/api/operator/beta-requests", opTok)
        reqId         <- ZIO
          .fromEither(lst.fromJson[List[BetaRequestSummary]])
          .mapError(new RuntimeException(_))
          .map(_.head.id)
        // Household B admin is forbidden from the operator surface (the §3.2 gate).
        (sList, _)    <- getJson(betaRoutes, "/api/operator/beta-requests", bTok)
        (sApprove, _) <- postJson(
          betaRoutes,
          s"/api/operator/beta-requests/${reqId.value}/approve",
          Some(bTok),
          "",
        )
      } yield assertTrue(sList == Status.Forbidden, sApprove == Status.Forbidden)
    },
    test("pin (#2132) — an accepted invite token cannot be replayed") {
      for {
        _     <- cleanDb
        two   <- TestLayers.seedTwoHouseholds(macA, macB)
        auth  <- makeAuth
        opTok <- login(auth, two.adminA, two.password)
        bt    <- makeBetaRoutes
        (_, betaRoutes) = bt
        prov <- provisionHousehold(betaRoutes, opTok, "r@example.com", "R Household")
        (_, token) = prov
        // Replay the (now consumed) token.
        (sReplay, _) <- postJson(
          betaRoutes,
          "/api/beta/accept",
          None,
          AcceptInviteRequest(token, "supersecret123").toJson,
        )
      } yield assertTrue(sReplay == Status.BadRequest)
    },
    test("pin (#2132) — an accepted admin is bound to its email and findable GLOBALLY (V67)") {
      // Design §3.4/§4 (2026-07-10): accept binds the admin's email from beta_requests.email; email
      // is the GLOBAL login identifier (uq_users_email). Prove the bound email resolves to exactly
      // one user, in the newly provisioned household — no other household, no duplicate.
      for {
        _     <- cleanDb
        two   <- TestLayers.seedTwoHouseholds(macA, macB)
        auth  <- makeAuth
        xa    <- ZIO.service[Transactor[Task]]
        opTok <- login(auth, two.adminA, two.password)
        bt    <- makeBetaRoutes
        (_, betaRoutes) = bt
        _     <- provisionHousehold(betaRoutes, opTok, "e@example.com", "E Household")
        // Exactly one user carries this email, globally.
        count <- sql"SELECT COUNT(*) FROM users WHERE email='e@example.com'"
          .query[Long]
          .unique
          .transact(xa)
        // …and it belongs to the provisioned household (neither the operator's nor household B's).
        hh    <- sql"SELECT household_id FROM users WHERE email='e@example.com'"
          .query[HouseholdId]
          .unique
          .transact(xa)
      } yield assertTrue(count == 1L) &&
        assertTrue(hh != HouseholdId.Default, hh != two.hhB)
    },
    // ── Pin (#2313): screen-time / daily-cap used-minutes are household-scoped for a SHARED MAC ──
    // Enforcement-critical. After V74 the SAME MAC can be a device in two households. The
    // `traffic_reports` presence reads that feed `TimeStatusService` (the daily-limit / screen-time
    // read path the policy snapshot consumes) filtered by bare MAC with NO household/router
    // predicate, so a profile's used-minutes was inflated by ANOTHER household's traffic on the same
    // MAC → wrongful cap block/allow. This pin seeds ONE MAC behind BOTH households' routers, posts
    // DISTINCT traffic under each router on a past date (routes `dayStateAll` to the all-live
    // presence read), and asserts each profile's used-minutes reflects ONLY its own household's
    // traffic. RED pre-fix: household A's minutes = 10 + 20 = 30 (leaked B's usage of the shared MAC).
    test("pin (#2313) — used-minutes for a shared MAC count ONLY the caller's household traffic") {
      val date = java.time.LocalDate.parse("2025-01-05") // a PAST date → dayStateAll goes all-live
      val macM = MacAddress.unsafe("aa:bb:cc:00:00:99")  // the SAME MAC in BOTH households
      val tA = java.time.Instant.parse("2025-01-05T10:00:00Z") // hh-A's window (10 min)
      val tB = java.time.Instant.parse("2025-01-05T18:00:00Z") // hh-B's window (20 min), far apart
      for {
        _    <- cleanDb
        two  <- TestLayers.seedTwoHouseholds(macA, macB)
        dr   <- ZIO.service[DeviceRepo]
        tlr  <- ZIO.service[TimeLimitRepo]
        atlr <- ZIO.service[AppTimeLimitRepo]
        tr   <- ZIO.service[TrafficReportRepo]
        er   <- ZIO.service[TimeExtensionRepo]
        pr   <- ZIO.service[ProfileRepo]
        hsr  <- ZIO.service[HouseholdSettingsRepo]
        clk  <- ZIO.service[Clock]
        now  <- clk.instant
        // The SAME MAC is a device in BOTH households (representable post-V74), each under that
        // household's own profile. uq_devices_household_mac keeps (hhA, M) and (hhB, M) distinct rows.
        _    <- dr.upsert(macM, "sharedA", Some(two.profileA), "192.168.1.20", two.hhA)
        _    <- dr.upsert(macM, "sharedB", Some(two.profileB), "192.168.1.21", two.hhB)
        // Distinct traffic for the shared MAC under each household's router — 10 min for A, 20 for B.
        // Bytes are well above the default heartbeat threshold (V23: 10240) so the rows count.
        _    <- tr.insertBatch(
          List(
            TrafficReportInsert(
              two.routerIdA,
              macM,
              None,
              HostId.Fqdn(Hostname.unsafe("a.example")),
              date,
              tA,
              tA.plusSeconds(600),
              600,
              40000L,
              50000L,
            ),
            TrafficReportInsert(
              two.routerIdB,
              macM,
              None,
              HostId.Fqdn(Hostname.unsafe("b.example")),
              date,
              tB,
              tB.plusSeconds(1200),
              1200,
              60000L,
              70000L,
            ),
          ),
        )
        tss = new TimeStatusServiceLive(pr, tlr, atlr, dr, tr, er)
        setA <- hsr.getForHousehold(two.hhA)
        setB <- hsr.getForHousehold(two.hhB)
        dayA <- tss.dayStateAll(two.hhA, now, date, setA)
        dayB <- tss.dayStateAll(two.hhB, now, date, setB)
        usedA = dayA.get(two.profileA).map(_.usedMinutes)
        usedB = dayB.get(two.profileB).map(_.usedMinutes)
      } yield
      // Each household's profile counts ONLY its own router's traffic on the shared MAC — never the
      // other tenant's. Pre-fix `usedA` was 30 (10 + the leaked 20).
      assertTrue(usedA == Some(10), usedB == Some(20))
    },
  ) @@ TestAspect.sequential
}
