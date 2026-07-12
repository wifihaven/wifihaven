package wifihaven.api.feature

import wifihaven.api.{BetaConfig, JwtConfig}
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
    def alertCreated(a: Alert): UIO[Unit]                                                 = ZIO.unit
    def betaHouseholdProvisioned(email: String, slug: String, hh: HouseholdId): UIO[Unit] = ZIO.unit
  }

  // #2132: build the beta pipeline stack (service + routes) over the real repos.
  private def makeBetaRoutes
      : ZIO[TestDatabase.AllRepos & Clock, Nothing, (BetaService, Routes[Any, Response])] =
    for {
      br   <- ZIO.service[BetaRequestRepo]
      hr   <- ZIO.service[HouseholdRepo]
      ur   <- ZIO.service[UserRepo]
      clk  <- ZIO.service[Clock]
      auth <- makeAuth
    } yield {
      val svc = BetaService(br, hr, ur, auth, noopNotifier, clk, BetaConfig())
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

  // #2140: usernames are unique per household, so a login must name the target household by slug
  // (except the default household, whose slug is `default` / absent). adminB lives in household B.
  private def login(
      auth: AuthService,
      user: String,
      pw: String,
      slug: Option[String] = None,
  ): Task[String] =
    auth
      .login(user, pw, slug)
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
        // The new user can log in (via household B's slug) and the minted JWT carries hh = B
        // (#2105). #2140: kid-b lives in household B, so login must name it — no slug would resolve
        // to the default household, where kid-b does not exist.
        kidLogin    <- auth
          .login("kid-b", "kid-b-password!", Some(two.slugB))
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
        _             <- postJson(
          betaRoutes,
          "/api/beta/request",
          None,
          CreateBetaRequest("x@example.com").toJson,
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
  ) @@ TestAspect.sequential
}
