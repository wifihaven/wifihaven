package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.api.policy.*
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

import java.time.{Instant, LocalDate, LocalDateTime, ZoneOffset}
import java.util.UUID

object RouterDecisionSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private def makePsAt(dt: LocalDateTime) =
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
      nsr    <- ZIO.service[NamedScheduleRepo]
      ref    <- Ref.make(dt)
      clk = new Clock.TestClock(ref)
    } yield PolicyServiceLive(
      pr,
      hsr,
      tlr,
      atlr,
      dr,
      blr,
      trRepo,
      er,
      ar,
      clk,
      namedScheduleRepo = nsr,
    ): PolicyService

  private def makePsDefault = makePsAt(TestClock.schoolDayAfternoon)

  /** Seed a bare router row for FK-satisfying traffic_reports inserts. */
  private def seedRouterRow: ZIO[RouterRepo, Throwable, RouterId] =
    ZIO.serviceWithZIO[RouterRepo](_.create("gw-seed", Sha256Hex.unsafe("n" * 64)))

  /** Seed `minutes/5` non-overlapping 5-min traffic_reports buckets. */
  private def seedTraffic(
      routerId: RouterId,
      mac: String,
      hostname: String,
      date: LocalDate,
      minutes: Int,
      bucketOffset: Int = 0,
  ): ZIO[TrafficReportRepo, Throwable, Int] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      val buckets = minutes / 5
      val today0  = date.atStartOfDay(ZoneOffset.UTC).toInstant
      val inserts = (0 until buckets).map { i =>
        val start = today0.plusSeconds((bucketOffset + i) * 300L)
        val end   = start.plusSeconds(300)
        TrafficReportInsert(
          routerId,
          MacAddress.unsafe(mac),
          None,
          HostId.Fqdn(Hostname.unsafe(hostname)),
          date,
          start,
          end,
          300,
          // #789: above the default heartbeat-filter byte floor (10 KB) so rows aren't dropped.
          500_000L,
          500_000L,
        )
      }.toList
      tr.insertBatch(inserts).as(bucketOffset + buckets)
    }

  /** Enroll a new router and return its bearer token. */
  private def seedAndEnrollRouter(
      rr: RouterRepo,
      routes: Routes[Any, Response],
  ): Task[RouterToken] = {
    val et   = EnrollmentToken.unsafe("et_" + UUID.randomUUID().toString.replace("-", ""))
    val hash = PolicyService.hashToken(et.value)
    for {
      _    <- rr.create("gw", hash)
      reg  <- routes.runZIO(
        Request.post(
          URL.decode("/api/router/register").toOption.get,
          Body.fromString(RegisterRouterRequest(et).toJson),
        ),
      )
      body <- reg.body.asString
      resp <- ZIO
        .fromEither(body.fromJson[RegisterRouterResponse])
        .mapError(new RuntimeException(_))
    } yield resp.routerToken
  }

  private def callDecide(
      routes: Routes[Any, Response],
      tok: RouterToken,
      mac: String,
      host: String,
  ): Task[Response] =
    routes.runZIO(
      Request
        .post(
          URL.decode("/api/router/decision").toOption.get,
          Body.fromString(
            RouterDecisionRequest(MacAddress.unsafe(mac), Hostname.unsafe(host)).toJson,
          ),
        )
        .addHeader(Header.Authorization.Bearer(tok.value)),
    )

  def spec = suite("POST /api/router/decision")(
    test("requires bearer token: missing → 401, wrong → 401") {
      for {
        _   <- cleanDb
        rr  <- ZIO.service[RouterRepo]
        ber <- ZIO.service[BlockEventRepo]
        ps  <- makePsDefault
        routes = RouterRoutes.routes(
          rr,
          ps,
          RouterAuthLive(rr),
          ber,
          TestLayers.TestBlockPageSecret,
        )
        tok    <- seedAndEnrollRouter(rr, routes)
        noAuth <- routes.runZIO(
          Request.post(
            URL.decode("/api/router/decision").toOption.get,
            Body.fromString(
              RouterDecisionRequest(
                MacAddress.unsafe("aa:bb:cc:11:22:33"),
                Hostname.unsafe("example.com"),
              ).toJson,
            ),
          ),
        )
        wrong  <- routes.runZIO(
          Request
            .post(
              URL.decode("/api/router/decision").toOption.get,
              Body.fromString(
                RouterDecisionRequest(
                  MacAddress.unsafe("aa:bb:cc:11:22:33"),
                  Hostname.unsafe("example.com"),
                ).toJson,
              ),
            )
            .addHeader(Header.Authorization.Bearer("rt_wrong")),
        )
      } yield assertTrue(noAuth.status == Status.Unauthorized) &&
        assertTrue(wrong.status == Status.Unauthorized)
    },
    test("unrecognized mac → allow, no_profile, no block_event") {
      for {
        _   <- cleanDb
        rr  <- ZIO.service[RouterRepo]
        ber <- ZIO.service[BlockEventRepo]
        ps  <- makePsDefault
        routes = RouterRoutes.routes(
          rr,
          ps,
          RouterAuthLive(rr),
          ber,
          TestLayers.TestBlockPageSecret,
        )
        tok    <- seedAndEnrollRouter(rr, routes)
        resp   <- callDecide(routes, tok, "ff:ff:ff:ff:ff:ff", "example.com")
        body   <- resp.body.asString
        dr     <- ZIO.fromEither(body.fromJson[RouterDecisionResponse])
        events <- ber.recent(10)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(dr.decision == ConnectionDecision.Allow) &&
        assertTrue(dr.reason == "no_profile") &&
        assertTrue(events.isEmpty)
    },
    test("MAC in devices with NULL profile_id → allow, no_profile") {
      for {
        _     <- cleanDb
        rr    <- ZIO.service[RouterRepo]
        dRepo <- ZIO.service[DeviceRepo]
        ber   <- ZIO.service[BlockEventRepo]
        mac = "aa:bb:cc:11:22:99"
        _  <- dRepo.upsertUnknown(
          MacAddress.unsafe(mac),
          "mystery-laptop",
          Some(IpAddress.unsafe("10.0.0.5")),
          Instant.now(),
        )
        ps <- makePsDefault
        routes = RouterRoutes.routes(
          rr,
          ps,
          RouterAuthLive(rr),
          ber,
          TestLayers.TestBlockPageSecret,
        )
        tok  <- seedAndEnrollRouter(rr, routes)
        resp <- callDecide(routes, tok, mac, "example.com")
        body <- resp.body.asString
        dr   <- ZIO.fromEither(body.fromJson[RouterDecisionResponse])
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(dr.decision == ConnectionDecision.Allow) &&
        assertTrue(dr.reason == "no_profile")
    },
    test("paused profile → block:paused, null expires_at, block_event recorded") {
      for {
        _   <- cleanDb
        rr  <- ZIO.service[RouterRepo]
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        ber <- ZIO.service[BlockEventRepo]
        kid <- TestLayers.seedKidsProfile(pr)
        _   <- pr.setPaused(kid, true)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        ps  <- makePsDefault
        routes = RouterRoutes.routes(
          rr,
          ps,
          RouterAuthLive(rr),
          ber,
          TestLayers.TestBlockPageSecret,
        )
        tok    <- seedAndEnrollRouter(rr, routes)
        resp   <- callDecide(routes, tok, "aa:bb:cc:11:22:33", "example.com")
        body   <- resp.body.asString
        dr     <- ZIO.fromEither(body.fromJson[RouterDecisionResponse])
        events <- ber.recent(10)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(dr.decision == ConnectionDecision.Block) &&
        assertTrue(dr.reason == "paused") &&
        assertTrue(dr.expiresAt.isEmpty) &&
        assertTrue(
          events.exists(e =>
            e.mac.contains(
              MacAddress.unsafe("aa:bb:cc:11:22:33"),
            ) && e.host == HostId.Fqdn(
              Hostname.unsafe("example.com"),
            ) && e.reason == MacBlockReason.Paused,
          ),
        )
    },
    test(
      "#1413: paused profile + AppMode.Allowed host → allow:extra_allowed (extraAllowed beats pause)",
    ) {
      // Prod miss (Kids/Math Academy, 2026-06): an allowed-mode app was reported
      // (and, on agents that consult this endpoint, blocked) as paused. Per #421
      // extraAllowed beats EVERY whole-MAC block — including Paused — exactly as
      // the snapshot/router @blocked_macs carve-out does. The per-host fallback
      // must agree: an allowed app stays reachable while the profile is paused,
      // and no block_event is recorded for it.
      val mac = "aa:bb:cc:11:22:44"
      for {
        _     <- cleanDb
        rr    <- ZIO.service[RouterRepo]
        pr    <- ZIO.service[ProfileRepo]
        dr    <- ZIO.service[DeviceRepo]
        ar    <- ZIO.service[AppRepo]
        ber   <- ZIO.service[BlockEventRepo]
        kid   <- TestLayers.seedKidsProfile(pr)
        _     <- pr.setPaused(kid, true)
        _     <- TestLayers.seedDevice(dr, mac, "kid-mac", kid)
        appId <- ar.create("Math Academy", "math-academy", None, None)
        _     <- ar.setHosts(appId, List(Hostname.unsafe("mathacademy.com")))
        _     <- ar.upsertAssignment(appId, kid, AppMode.Allowed, None, true)
        ps    <- makePsDefault
        routes = RouterRoutes.routes(
          rr,
          ps,
          RouterAuthLive(rr),
          ber,
          TestLayers.TestBlockPageSecret,
        )
        tok    <- seedAndEnrollRouter(rr, routes)
        // The allowed app survives the pause.
        allow  <- callDecide(routes, tok, mac, "mathacademy.com")
        aBody  <- allow.body.asString
        aDr    <- ZIO.fromEither(aBody.fromJson[RouterDecisionResponse])
        // A non-allowlisted host is still blocked by the pause.
        block  <- callDecide(routes, tok, mac, "example.com")
        bBody  <- block.body.asString
        bDr    <- ZIO.fromEither(bBody.fromJson[RouterDecisionResponse])
        events <- ber.recent(10)
      } yield assertTrue(aDr.decision == ConnectionDecision.Allow) &&
        assertTrue(aDr.reason == "extra_allowed") &&
        assertTrue(bDr.decision == ConnectionDecision.Block) &&
        assertTrue(bDr.reason == "paused") &&
        // No block_event for the allowed host; the paused host still records one.
        assertTrue(
          !events.exists(_.host == HostId.Fqdn(Hostname.unsafe("mathacademy.com"))),
        ) &&
        assertTrue(
          events.exists(e =>
            e.host == HostId.Fqdn(Hostname.unsafe("example.com")) &&
              e.reason == MacBlockReason.Paused,
          ),
        )
    },
    test(
      "active schedule → block:schedule, expires_at = when schedule ends, block_event recorded",
    ) {
      // Bedtime = Monday 2025-01-06 21:30; schedule is 21:00–07:00 every day.
      // Overnight schedule started today → ends tomorrow at 07:00.
      for {
        _   <- cleanDb
        rr  <- ZIO.service[RouterRepo]
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        ber <- ZIO.service[BlockEventRepo]
        kid <- TestLayers.seedKidsProfile(pr)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        ps  <- makePsAt(TestClock.bedtime) // Monday 21:30
        routes = RouterRoutes.routes(
          rr,
          ps,
          RouterAuthLive(rr),
          ber,
          TestLayers.TestBlockPageSecret,
        )
        tok    <- seedAndEnrollRouter(rr, routes)
        resp   <- callDecide(routes, tok, "aa:bb:cc:11:22:33", "example.com")
        body   <- resp.body.asString
        dr     <- ZIO.fromEither(body.fromJson[RouterDecisionResponse])
        events <- ber.recent(10)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(dr.decision == ConnectionDecision.Block) &&
        assertTrue(dr.reason == "schedule") &&
        assertTrue(dr.expiresAt.exists(s => s.startsWith("2025-01-07T07:00") && s.endsWith("Z"))) &&
        assertTrue(events.exists(e => e.reason == MacBlockReason.Schedule))
    },
    test("early morning during overnight schedule → block:schedule, expires_at = today 07:00") {
      // earlyMorning = Monday 2025-01-06 06:00. Overnight schedule started Sunday 21:00 →
      // ends Monday 07:00.
      for {
        _   <- cleanDb
        rr  <- ZIO.service[RouterRepo]
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        ber <- ZIO.service[BlockEventRepo]
        kid <- TestLayers.seedKidsProfile(pr)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        ps  <- makePsAt(TestClock.earlyMorning) // Monday 06:00
        routes = RouterRoutes.routes(
          rr,
          ps,
          RouterAuthLive(rr),
          ber,
          TestLayers.TestBlockPageSecret,
        )
        tok  <- seedAndEnrollRouter(rr, routes)
        resp <- callDecide(routes, tok, "aa:bb:cc:11:22:33", "example.com")
        body <- resp.body.asString
        dr   <- ZIO.fromEither(body.fromJson[RouterDecisionResponse])
      } yield assertTrue(dr.decision == ConnectionDecision.Block) &&
        assertTrue(dr.reason == "schedule") &&
        assertTrue(dr.expiresAt.exists(s => s.startsWith("2025-01-06T07:00") && s.endsWith("Z")))
    },
    test("daily time limit hit → block:time_limit, expires_at = midnight, block_event recorded") {
      // Kids profile: 120 min/day limit.  Usage = 121 min, no extension.
      for {
        _   <- cleanDb
        rr  <- ZIO.service[RouterRepo]
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        tlr <- ZIO.service[TimeLimitRepo]
        ber <- ZIO.service[BlockEventRepo]
        kid <- TestLayers.seedKidsProfile(pr)
        _   <- tlr.upsert(kid, 120)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        rid <- seedRouterRow
        _   <- seedTraffic(rid, "aa:bb:cc:11:22:33", "cnn.com", LocalDate.of(2025, 1, 6), 125)
        ps  <- makePsDefault // schoolDayAfternoon: 14:00, no schedule active
        routes = RouterRoutes.routes(
          rr,
          ps,
          RouterAuthLive(rr),
          ber,
          TestLayers.TestBlockPageSecret,
        )
        tok    <- seedAndEnrollRouter(rr, routes)
        resp   <- callDecide(routes, tok, "aa:bb:cc:11:22:33", "cnn.com")
        body   <- resp.body.asString
        dr     <- ZIO.fromEither(body.fromJson[RouterDecisionResponse])
        events <- ber.recent(10)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(dr.decision == ConnectionDecision.Block) &&
        assertTrue(dr.reason == "time_limit") &&
        assertTrue(dr.expiresAt.exists(s => s.startsWith("2025-01-07T00:00") && s.endsWith("Z"))) &&
        assertTrue(events.exists(_.reason == MacBlockReason.TimeLimit))
    },
    test("extension grants extra minutes: usage at limit + extension → allow") {
      for {
        _   <- cleanDb
        rr  <- ZIO.service[RouterRepo]
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        tlr <- ZIO.service[TimeLimitRepo]
        er  <- ZIO.service[TimeExtensionRepo]
        ber <- ZIO.service[BlockEventRepo]
        kid <- TestLayers.seedKidsProfile(pr)
        _   <- tlr.upsert(kid, 120)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        rid <- seedRouterRow
        _   <- seedTraffic(rid, "aa:bb:cc:11:22:33", "cnn.com", LocalDate.of(2025, 1, 6), 125)
        _   <- er.grantForProfile(kid, LocalDate.of(2025, 1, 6), 30, "admin", None)
        ps  <- makePsDefault
        routes = RouterRoutes.routes(
          rr,
          ps,
          RouterAuthLive(rr),
          ber,
          TestLayers.TestBlockPageSecret,
        )
        tok  <- seedAndEnrollRouter(rr, routes)
        resp <- callDecide(routes, tok, "aa:bb:cc:11:22:33", "cnn.com")
        body <- resp.body.asString
        dr   <- ZIO.fromEither(body.fromJson[RouterDecisionResponse])
      } yield assertTrue(dr.decision == ConnectionDecision.Allow)
    },
    test("blocked by category blocklist → block:category:ads, block_event recorded") {
      for {
        _   <- cleanDb
        rr  <- ZIO.service[RouterRepo]
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        blr <- ZIO.service[BlocklistRepo]
        ber <- ZIO.service[BlockEventRepo]
        kid <- pr.create("Kids", List(BlocklistId.unsafe("ads"), BlocklistId.unsafe("gambling")))
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        _   <- blr.insertBatch(List(("doubleclick.net", "ads"), ("ads.example.com", "ads")))
        ps  <- makePsDefault
        routes = RouterRoutes.routes(
          rr,
          ps,
          RouterAuthLive(rr),
          ber,
          TestLayers.TestBlockPageSecret,
        )
        tok    <- seedAndEnrollRouter(rr, routes)
        resp   <- callDecide(routes, tok, "aa:bb:cc:11:22:33", "doubleclick.net")
        body   <- resp.body.asString
        dr     <- ZIO.fromEither(body.fromJson[RouterDecisionResponse])
        events <- ber.recent(10)
      } yield assertTrue(dr.decision == ConnectionDecision.Block) &&
        assertTrue(dr.reason == "category:ads") &&
        assertTrue(dr.expiresAt.isEmpty) &&
        assertTrue(events.exists(_.reason == BlockReason.Category(BlocklistId.unsafe("ads"))))
    },
    test("subdomain matched by blocklist parent → block:category") {
      for {
        _   <- cleanDb
        rr  <- ZIO.service[RouterRepo]
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        blr <- ZIO.service[BlocklistRepo]
        ber <- ZIO.service[BlockEventRepo]
        kid <- pr.create("Kids", List(BlocklistId.unsafe("ads")))
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        _   <- blr.insertBatch(List(("doubleclick.net", "ads")))
        ps  <- makePsDefault
        routes = RouterRoutes.routes(
          rr,
          ps,
          RouterAuthLive(rr),
          ber,
          TestLayers.TestBlockPageSecret,
        )
        tok  <- seedAndEnrollRouter(rr, routes)
        resp <- callDecide(routes, tok, "aa:bb:cc:11:22:33", "sub.doubleclick.net")
        body <- resp.body.asString
        dr   <- ZIO.fromEither(body.fromJson[RouterDecisionResponse])
      } yield assertTrue(dr.decision == ConnectionDecision.Block) &&
        assertTrue(dr.reason == "category:ads")
    },
    test("allowed host: no blocklist match, no limit hit, no schedule → allow, no block_event") {
      for {
        _   <- cleanDb
        rr  <- ZIO.service[RouterRepo]
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        ber <- ZIO.service[BlockEventRepo]
        kid <- TestLayers.seedKidsProfile(pr)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        ps  <- makePsDefault // 14:00, no schedule active
        routes = RouterRoutes.routes(
          rr,
          ps,
          RouterAuthLive(rr),
          ber,
          TestLayers.TestBlockPageSecret,
        )
        tok    <- seedAndEnrollRouter(rr, routes)
        resp   <- callDecide(routes, tok, "aa:bb:cc:11:22:33", "example.com")
        body   <- resp.body.asString
        dr     <- ZIO.fromEither(body.fromJson[RouterDecisionResponse])
        events <- ber.recent(10)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(dr.decision == ConnectionDecision.Allow) &&
        assertTrue(events.isEmpty)
    },
    test("extra_blocked domain → block:extra_blocked") {
      for {
        _   <- cleanDb
        rr  <- ZIO.service[RouterRepo]
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        ar  <- ZIO.service[AppRepo]
        ber <- ZIO.service[BlockEventRepo]
        kid <- pr.create("Kids", List.empty)
        _   <- TestLayers.seedAppAssignment(ar, kid, "badsite.com", AppMode.Blocked)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        ps  <- makePsDefault
        routes = RouterRoutes.routes(
          rr,
          ps,
          RouterAuthLive(rr),
          ber,
          TestLayers.TestBlockPageSecret,
        )
        tok  <- seedAndEnrollRouter(rr, routes)
        resp <- callDecide(routes, tok, "aa:bb:cc:11:22:33", "badsite.com")
        body <- resp.body.asString
        dr   <- ZIO.fromEither(body.fromJson[RouterDecisionResponse])
      } yield assertTrue(dr.decision == ConnectionDecision.Block) &&
        assertTrue(dr.reason == "extra_blocked")
    },
    test("block_event NOT recorded on allow decision") {
      for {
        _   <- cleanDb
        rr  <- ZIO.service[RouterRepo]
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        ber <- ZIO.service[BlockEventRepo]
        kid <- TestLayers.seedKidsProfile(pr)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        ps  <- makePsDefault
        routes = RouterRoutes.routes(
          rr,
          ps,
          RouterAuthLive(rr),
          ber,
          TestLayers.TestBlockPageSecret,
        )
        tok    <- seedAndEnrollRouter(rr, routes)
        _      <- callDecide(routes, tok, "aa:bb:cc:11:22:33", "allowed.example.com")
        events <- ber.recent(10)
      } yield assertTrue(events.isEmpty)
    },
    test(
      "included site (exemptFromDaily=false): site usage counts toward daily total → block:time_limit",
    ) {
      // Site cap is 200 (not hit). Daily cap is 120. 121 min of YouTube usage.
      // With exemptFromDaily=false the 121 min counts toward the 120 daily cap → time_limit.
      for
        _   <- cleanDb
        rr  <- ZIO.service[RouterRepo]
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        tlr <- ZIO.service[TimeLimitRepo]
        ber <- ZIO.service[BlockEventRepo]
        ar  <- ZIO.service[AppRepo]
        kid <- TestLayers.seedKidsProfile(pr)
        _   <- tlr.upsert(kid, 120)
        _   <- TestLayers.seedAppAssignment(
          ar,
          kid,
          "youtube.com",
          AppMode.TimeLimited,
          dailyMinutes = Some(200),
          exemptFromDaily = false,
        )
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        rid <- seedRouterRow
        _   <- seedTraffic(rid, "aa:bb:cc:11:22:33", "youtube.com", LocalDate.of(2025, 1, 6), 125)
        ps  <- makePsDefault
        routes = RouterRoutes.routes(
          rr,
          ps,
          RouterAuthLive(rr),
          ber,
          TestLayers.TestBlockPageSecret,
        )
        tok  <- seedAndEnrollRouter(rr, routes)
        resp <- callDecide(routes, tok, "aa:bb:cc:11:22:33", "youtube.com")
        body <- resp.body.asString
        dr   <- ZIO.fromEither(body.fromJson[RouterDecisionResponse])
      yield assertTrue(dr.decision == ConnectionDecision.Block) &&
        assertTrue(dr.reason == "time_limit")
    },
    test(
      "exempt site (exemptFromDaily=true): site usage does NOT count toward daily total → allow",
    ) {
      // Daily cap is 120. 121 min of YouTube usage, but YouTube is exempt from daily cap.
      // YouTube site cap is 200 (not hit either) → should allow.
      for
        _   <- cleanDb
        rr  <- ZIO.service[RouterRepo]
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        tlr <- ZIO.service[TimeLimitRepo]
        ber <- ZIO.service[BlockEventRepo]
        ar  <- ZIO.service[AppRepo]
        kid <- TestLayers.seedKidsProfile(pr)
        _   <- tlr.upsert(kid, 120)
        _   <- TestLayers.seedAppAssignment(
          ar,
          kid,
          "youtube.com",
          AppMode.TimeLimited,
          dailyMinutes = Some(200),
          exemptFromDaily = true,
        )
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        rid <- seedRouterRow
        _   <- seedTraffic(rid, "aa:bb:cc:11:22:33", "youtube.com", LocalDate.of(2025, 1, 6), 125)
        ps  <- makePsDefault
        routes = RouterRoutes.routes(
          rr,
          ps,
          RouterAuthLive(rr),
          ber,
          TestLayers.TestBlockPageSecret,
        )
        tok  <- seedAndEnrollRouter(rr, routes)
        resp <- callDecide(routes, tok, "aa:bb:cc:11:22:33", "youtube.com")
        body <- resp.body.asString
        dr   <- ZIO.fromEither(body.fromJson[RouterDecisionResponse])
      yield assertTrue(dr.decision == ConnectionDecision.Allow)
    },
    // ── #1515: snapshot ⇄ decide() agreement on the per-app cap ──────────────────────────────────
    // The snapshot collapses each app's cap into nftables host-sets (`extraBlocked` / `extraAllowed`)
    // the router applies blind, while /decision answers per host. They MUST agree for the same
    // profile/now or the block-page reason drifts from what nftables enforces. These tests build ONE
    // PolicyService and cross-check both readings; `routerAllows` mirrors the router's precedence
    // (`extraAllowed` beats whole-MAC `blocked` beats `extraBlocked`, #421) so a snapshot verdict can
    // be compared to a /decision verdict directly.
    test(
      "#1515 anti-divergence: over-cap multi-host app — snapshot blocks the whole host-set and decide() agrees",
    ) {
      val mac = "aa:bb:cc:11:22:33"
      for
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        dr   <- ZIO.service[DeviceRepo]
        ar   <- ZIO.service[AppRepo]
        kid  <- TestLayers.seedKidsProfile(pr)
        _    <- TestLayers.seedDevice(dr, mac, "kid-ipad", kid)
        app  <- ar.create("YouTube", "youtube", None, None)
        _    <- ar.setHosts(app, List(Hostname.unsafe("youtube.com"), Hostname.unsafe("ytimg.com")))
        _    <- ar.upsertAssignment(app, kid, AppMode.TimeLimited, Some(30), exemptFromDaily = true)
        rid  <- seedRouterRow
        // 30 m on the off-domain asset host alone → aggregate hits the 30 m cap (apex idle).
        _    <- seedTraffic(rid, mac, "ytimg.com", LocalDate.of(2025, 1, 6), 30)
        ps   <- makePsDefault
        snap <- ps.snapshot
        rules = snap.profiles(kid).rules
        apex  <- ps.decide(mac, "youtube.com")
        asset <- ps.decide(mac, "ytimg.com")
      yield assertTrue(rules.extraBlocked.map(_.value).toSet == Set("youtube.com", "ytimg.com")) &&
        assertTrue(!routerAllows(rules, "youtube.com")) &&
        assertTrue(!routerAllows(rules, "ytimg.com")) &&
        assertTrue(apex.decision == ConnectionDecision.Block) &&
        assertTrue(asset.decision == ConnectionDecision.Block) &&
        assertTrue(decisionAllows(asset) == routerAllows(rules, "ytimg.com")) &&
        assertTrue(decisionAllows(apex) == routerAllows(rules, "youtube.com"))
    },
    test(
      "#1515 anti-divergence: exempt app UNDER cap stays reachable when the profile is PAUSED (snapshot ⇄ decide())",
    ) {
      // The #1513 regression class at the /decision layer: an exempt-from-daily app under its own cap
      // is carved into `extraAllowed` for its WHOLE host-set, which beats the whole-MAC pause block at
      // the router (#421). /decision must report the same Allow — its pause short-circuit cannot fire
      // before the exempt carve, or the block page contradicts what nftables actually does.
      val mac = "aa:bb:cc:11:22:33"
      for
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        dr   <- ZIO.service[DeviceRepo]
        ar   <- ZIO.service[AppRepo]
        kid  <- TestLayers.seedKidsProfile(pr)
        _    <- pr.setPaused(kid, true)
        _    <- TestLayers.seedDevice(dr, mac, "kid-ipad", kid)
        app  <- ar.create("Khan", "khan", None, None)
        _    <- ar.setHosts(
          app,
          List(Hostname.unsafe("khanacademy.org"), Hostname.unsafe("cdn.kastatic.org")),
        )
        _    <- ar.upsertAssignment(app, kid, AppMode.TimeLimited, Some(60), exemptFromDaily = true)
        rid  <- seedRouterRow
        // 20 m on the apex alone → aggregate 20 < 60 cap → under-cap, the whole set is carved.
        _    <- seedTraffic(rid, mac, "khanacademy.org", LocalDate.of(2025, 1, 6), 20)
        ps   <- makePsDefault
        snap <- ps.snapshot
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
        apex  <- ps.decide(mac, "khanacademy.org")
        asset <- ps.decide(mac, "cdn.kastatic.org")
      yield assertTrue(rules.blocked) &&
        assertTrue(rules.blockReason.contains(MacBlockReason.Paused)) &&
        assertTrue(ea.contains("khanacademy.org")) &&
        assertTrue(ea.contains("cdn.kastatic.org")) &&
        assertTrue(routerAllows(rules, "khanacademy.org")) &&
        assertTrue(routerAllows(rules, "cdn.kastatic.org")) &&
        assertTrue(apex.decision == ConnectionDecision.Allow) &&
        assertTrue(asset.decision == ConnectionDecision.Allow) &&
        assertTrue(decisionAllows(apex) == routerAllows(rules, "khanacademy.org")) &&
        assertTrue(decisionAllows(asset) == routerAllows(rules, "cdn.kastatic.org"))
    },
    test(
      "#1513 invariant: exempt app under cap reachable when the profile DAILY cap is hit (snapshot ⇄ decide())",
    ) {
      // The original #1513 prod regression: the profile daily cap is exhausted by OTHER (non-exempt)
      // traffic, yet the exempt app under its own cap must stay reachable across its whole host-set.
      val mac = "aa:bb:cc:11:22:33"
      for
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        dr   <- ZIO.service[DeviceRepo]
        tlr  <- ZIO.service[TimeLimitRepo]
        ar   <- ZIO.service[AppRepo]
        kid  <- TestLayers.seedKidsProfile(pr)
        _    <- tlr.upsert(kid, 30)
        _    <- TestLayers.seedDevice(dr, mac, "kid-ipad", kid)
        app  <- ar.create("Khan", "khan", None, None)
        _    <- ar.setHosts(
          app,
          List(Hostname.unsafe("khanacademy.org"), Hostname.unsafe("cdn.kastatic.org")),
        )
        _    <- ar.upsertAssignment(app, kid, AppMode.TimeLimited, Some(60), exemptFromDaily = true)
        rid  <- seedRouterRow
        // 35 m on a non-exempt host exhausts the 30 m profile daily cap; 20 m exempt Khan stays
        // under its own 60 m cap and does NOT count toward the daily total.
        _    <- seedTraffic(rid, mac, "cnn.com", LocalDate.of(2025, 1, 6), 35)
        _    <- seedTraffic(
          rid,
          mac,
          "khanacademy.org",
          LocalDate.of(2025, 1, 6),
          20,
          bucketOffset = 12,
        )
        ps   <- makePsDefault
        snap <- ps.snapshot
        rules = snap.profiles(kid).rules
        ea    = rules.extraAllowed.map(_.value).toSet
        apex  <- ps.decide(mac, "khanacademy.org")
        asset <- ps.decide(mac, "cdn.kastatic.org")
      yield assertTrue(rules.blocked) &&
        assertTrue(rules.blockReason.contains(MacBlockReason.TimeLimit)) &&
        assertTrue(ea.contains("khanacademy.org")) &&
        assertTrue(ea.contains("cdn.kastatic.org")) &&
        assertTrue(apex.decision == ConnectionDecision.Allow) &&
        assertTrue(asset.decision == ConnectionDecision.Allow) &&
        assertTrue(decisionAllows(apex) == routerAllows(rules, "khanacademy.org")) &&
        assertTrue(decisionAllows(asset) == routerAllows(rules, "cdn.kastatic.org"))
    },
  ) @@ TestAspect.sequential

  /** True iff a /decision response allowed the connection. */
  private def decisionAllows(r: RouterDecisionResponse): Boolean =
    r.decision == ConnectionDecision.Allow

  /**
   * The router's effective verdict for `host` from a profile's snapshot `BlockRules`, mirroring the
   * nftables precedence the agent applies: `extraAllowed` carves out every drop (#421), then a
   * whole-MAC `blocked` drops everything, then `extraBlocked` drops the host. Used to assert the
   * /decision endpoint agrees with what the snapshot actually enforces. Hosts in these tests are
   * exact (no globs), so a literal membership test matches the router's pattern check.
   */
  private def routerAllows(rules: BlockRules, host: String): Boolean =
    if rules.extraAllowed.map(_.value).contains(host) then true
    else if rules.blocked then false
    else !rules.extraBlocked.map(_.value).contains(host)
}
