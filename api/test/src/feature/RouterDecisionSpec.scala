package familydns.api.feature

import familydns.api.db.*
import familydns.api.policy.*
import familydns.api.routes.*
import familydns.shared.*
import familydns.shared.Clock.TestClock
import familydns.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

import java.time.{Instant, LocalDate, LocalDateTime}
import java.util.UUID

object RouterDecisionSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private def cleanDb = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

  private def makePsAt(dt: LocalDateTime) =
    for {
      pr   <- ZIO.service[ProfileRepo]
      sr   <- ZIO.service[ScheduleRepo]
      tlr  <- ZIO.service[TimeLimitRepo]
      stlr <- ZIO.service[SiteTimeLimitRepo]
      dr   <- ZIO.service[DeviceRepo]
      blr  <- ZIO.service[BlocklistRepo]
      ur   <- ZIO.service[TimeUsageRepo]
      er   <- ZIO.service[TimeExtensionRepo]
      ref  <- Ref.make(dt)
      clk = new Clock.TestClock(ref)
    } yield (new PolicyServiceLive(pr, sr, tlr, stlr, dr, blr, ur, er, clk)): PolicyService

  private def makePsDefault = makePsAt(TestClock.schoolDayAfternoon)

  /** Enroll a new router and return its bearer token. */
  private def seedAndEnrollRouter(
      rr: RouterRepo,
      routes: Routes[Any, Response],
  ): Task[String] = {
    val et   = "et_" + UUID.randomUUID().toString.replace("-", "")
    val hash = PolicyService.hashToken(et)
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
      tok: String,
      mac: String,
      host: String,
  ): Task[Response] =
    routes.runZIO(
      Request
        .post(
          URL.decode("/api/router/decision").toOption.get,
          Body.fromString(RouterDecisionRequest(mac, host).toJson),
        )
        .addHeader(Header.Authorization.Bearer(tok)),
    )

  def spec = suite("POST /api/router/decision")(
    test("requires bearer token: missing → 401, wrong → 401") {
      for {
        _   <- cleanDb
        rr  <- ZIO.service[RouterRepo]
        ber <- ZIO.service[BlockEventRepo]
        ps  <- makePsDefault
        routes = RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)
        tok    <- seedAndEnrollRouter(rr, routes)
        noAuth <- routes.runZIO(
          Request.post(
            URL.decode("/api/router/decision").toOption.get,
            Body.fromString(RouterDecisionRequest("aa:bb:cc:11:22:33", "example.com").toJson),
          ),
        )
        wrong  <- routes.runZIO(
          Request
            .post(
              URL.decode("/api/router/decision").toOption.get,
              Body.fromString(RouterDecisionRequest("aa:bb:cc:11:22:33", "example.com").toJson),
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
        routes = RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)
        tok    <- seedAndEnrollRouter(rr, routes)
        resp   <- callDecide(routes, tok, "ff:ff:ff:ff:ff:ff", "example.com")
        body   <- resp.body.asString
        dr     <- ZIO.fromEither(body.fromJson[RouterDecisionResponse])
        events <- ber.recent(10)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(dr.decision == "allow") &&
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
        _  <- dRepo.upsertUnknown(mac, "mystery-laptop", Some("10.0.0.5"), Instant.now())
        ps <- makePsDefault
        routes = RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)
        tok  <- seedAndEnrollRouter(rr, routes)
        resp <- callDecide(routes, tok, mac, "example.com")
        body <- resp.body.asString
        dr   <- ZIO.fromEither(body.fromJson[RouterDecisionResponse])
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(dr.decision == "allow") &&
        assertTrue(dr.reason == "no_profile")
    },
    test("paused profile → block:paused, null expires_at, block_event recorded") {
      for {
        _   <- cleanDb
        rr  <- ZIO.service[RouterRepo]
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        sr  <- ZIO.service[ScheduleRepo]
        ber <- ZIO.service[BlockEventRepo]
        kid <- TestLayers.seedKidsProfile(pr, sr)
        _   <- pr.setPaused(kid, true)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        ps  <- makePsDefault
        routes = RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)
        tok    <- seedAndEnrollRouter(rr, routes)
        resp   <- callDecide(routes, tok, "aa:bb:cc:11:22:33", "example.com")
        body   <- resp.body.asString
        dr     <- ZIO.fromEither(body.fromJson[RouterDecisionResponse])
        events <- ber.recent(10)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(dr.decision == "block") &&
        assertTrue(dr.reason == "paused") &&
        assertTrue(dr.expiresAt.isEmpty) &&
        assertTrue(
          events.exists(e =>
            e.mac.contains(
              "aa:bb:cc:11:22:33",
            ) && e.hostname == "example.com" && e.reason == "paused",
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
        sr  <- ZIO.service[ScheduleRepo]
        ber <- ZIO.service[BlockEventRepo]
        kid <- TestLayers.seedKidsProfile(pr, sr)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        ps  <- makePsAt(TestClock.bedtime) // Monday 21:30
        routes = RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)
        tok    <- seedAndEnrollRouter(rr, routes)
        resp   <- callDecide(routes, tok, "aa:bb:cc:11:22:33", "example.com")
        body   <- resp.body.asString
        dr     <- ZIO.fromEither(body.fromJson[RouterDecisionResponse])
        events <- ber.recent(10)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(dr.decision == "block") &&
        assertTrue(dr.reason == "schedule") &&
        assertTrue(dr.expiresAt.exists(s => s.startsWith("2025-01-07T07:00") && s.endsWith("Z"))) &&
        assertTrue(events.exists(e => e.reason == "schedule"))
    },
    test("early morning during overnight schedule → block:schedule, expires_at = today 07:00") {
      // earlyMorning = Monday 2025-01-06 06:00. Overnight schedule started Sunday 21:00 →
      // ends Monday 07:00.
      for {
        _   <- cleanDb
        rr  <- ZIO.service[RouterRepo]
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        sr  <- ZIO.service[ScheduleRepo]
        ber <- ZIO.service[BlockEventRepo]
        kid <- TestLayers.seedKidsProfile(pr, sr)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        ps  <- makePsAt(TestClock.earlyMorning) // Monday 06:00
        routes = RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)
        tok  <- seedAndEnrollRouter(rr, routes)
        resp <- callDecide(routes, tok, "aa:bb:cc:11:22:33", "example.com")
        body <- resp.body.asString
        dr   <- ZIO.fromEither(body.fromJson[RouterDecisionResponse])
      } yield assertTrue(dr.decision == "block") &&
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
        sr  <- ZIO.service[ScheduleRepo]
        tlr <- ZIO.service[TimeLimitRepo]
        ur  <- ZIO.service[TimeUsageRepo]
        ber <- ZIO.service[BlockEventRepo]
        kid <- TestLayers.seedKidsProfile(pr, sr)
        _   <- tlr.upsert(kid, 120)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        _   <- ur.incrementSecondsAndBytes(
          "aa:bb:cc:11:22:33",
          "cnn.com",
          LocalDate.of(2025, 1, 6),
          121L * 60L,
          0L,
          0L,
        )
        ps  <- makePsDefault // schoolDayAfternoon: 14:00, no schedule active
        routes = RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)
        tok    <- seedAndEnrollRouter(rr, routes)
        resp   <- callDecide(routes, tok, "aa:bb:cc:11:22:33", "cnn.com")
        body   <- resp.body.asString
        dr     <- ZIO.fromEither(body.fromJson[RouterDecisionResponse])
        events <- ber.recent(10)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(dr.decision == "block") &&
        assertTrue(dr.reason == "time_limit") &&
        assertTrue(dr.expiresAt.exists(s => s.startsWith("2025-01-07T00:00") && s.endsWith("Z"))) &&
        assertTrue(events.exists(_.reason == "time_limit"))
    },
    test("extension grants extra minutes: usage at limit + extension → allow") {
      for {
        _   <- cleanDb
        rr  <- ZIO.service[RouterRepo]
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        sr  <- ZIO.service[ScheduleRepo]
        tlr <- ZIO.service[TimeLimitRepo]
        ur  <- ZIO.service[TimeUsageRepo]
        er  <- ZIO.service[TimeExtensionRepo]
        ber <- ZIO.service[BlockEventRepo]
        kid <- TestLayers.seedKidsProfile(pr, sr)
        _   <- tlr.upsert(kid, 120)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        _   <- ur.incrementSecondsAndBytes(
          "aa:bb:cc:11:22:33",
          "cnn.com",
          LocalDate.of(2025, 1, 6),
          121L * 60L,
          0L,
          0L,
        )
        _   <- er.grantForProfile(kid, LocalDate.of(2025, 1, 6), 30, "admin", None)
        ps  <- makePsDefault
        routes = RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)
        tok  <- seedAndEnrollRouter(rr, routes)
        resp <- callDecide(routes, tok, "aa:bb:cc:11:22:33", "cnn.com")
        body <- resp.body.asString
        dr   <- ZIO.fromEither(body.fromJson[RouterDecisionResponse])
      } yield assertTrue(dr.decision == "allow")
    },
    test("blocked by category blocklist → block:category:ads, block_event recorded") {
      for {
        _   <- cleanDb
        rr  <- ZIO.service[RouterRepo]
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        sr  <- ZIO.service[ScheduleRepo]
        blr <- ZIO.service[BlocklistRepo]
        ber <- ZIO.service[BlockEventRepo]
        kid <- pr.create("Kids", List("ads", "gambling"))
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        _   <- blr.insertBatch(List(("doubleclick.net", "ads"), ("ads.example.com", "ads")))
        ps  <- makePsDefault
        routes = RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)
        tok    <- seedAndEnrollRouter(rr, routes)
        resp   <- callDecide(routes, tok, "aa:bb:cc:11:22:33", "doubleclick.net")
        body   <- resp.body.asString
        dr     <- ZIO.fromEither(body.fromJson[RouterDecisionResponse])
        events <- ber.recent(10)
      } yield assertTrue(dr.decision == "block") &&
        assertTrue(dr.reason == "category:ads") &&
        assertTrue(dr.expiresAt.isEmpty) &&
        assertTrue(events.exists(_.reason == "category:ads"))
    },
    test("subdomain matched by blocklist parent → block:category") {
      for {
        _   <- cleanDb
        rr  <- ZIO.service[RouterRepo]
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        sr  <- ZIO.service[ScheduleRepo]
        blr <- ZIO.service[BlocklistRepo]
        ber <- ZIO.service[BlockEventRepo]
        kid <- pr.create("Kids", List("ads"))
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        _   <- blr.insertBatch(List(("doubleclick.net", "ads")))
        ps  <- makePsDefault
        routes = RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)
        tok  <- seedAndEnrollRouter(rr, routes)
        resp <- callDecide(routes, tok, "aa:bb:cc:11:22:33", "sub.doubleclick.net")
        body <- resp.body.asString
        dr   <- ZIO.fromEither(body.fromJson[RouterDecisionResponse])
      } yield assertTrue(dr.decision == "block") &&
        assertTrue(dr.reason == "category:ads")
    },
    test("allowed host: no blocklist match, no limit hit, no schedule → allow, no block_event") {
      for {
        _   <- cleanDb
        rr  <- ZIO.service[RouterRepo]
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        sr  <- ZIO.service[ScheduleRepo]
        ber <- ZIO.service[BlockEventRepo]
        kid <- TestLayers.seedKidsProfile(pr, sr)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        ps  <- makePsDefault // 14:00, no schedule active
        routes = RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)
        tok    <- seedAndEnrollRouter(rr, routes)
        resp   <- callDecide(routes, tok, "aa:bb:cc:11:22:33", "example.com")
        body   <- resp.body.asString
        dr     <- ZIO.fromEither(body.fromJson[RouterDecisionResponse])
        events <- ber.recent(10)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(dr.decision == "allow") &&
        assertTrue(events.isEmpty)
    },
    test("extra_blocked domain → block:extra_blocked") {
      for {
        _   <- cleanDb
        rr  <- ZIO.service[RouterRepo]
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        sr  <- ZIO.service[ScheduleRepo]
        ber <- ZIO.service[BlockEventRepo]
        kid <- pr.create("Kids", List.empty)
        p   <- pr.findById(kid).map(_.get)
        _   <- pr.update(p.copy(extraBlocked = List("badsite.com")))
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        ps  <- makePsDefault
        routes = RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)
        tok  <- seedAndEnrollRouter(rr, routes)
        resp <- callDecide(routes, tok, "aa:bb:cc:11:22:33", "badsite.com")
        body <- resp.body.asString
        dr   <- ZIO.fromEither(body.fromJson[RouterDecisionResponse])
      } yield assertTrue(dr.decision == "block") &&
        assertTrue(dr.reason == "extra_blocked")
    },
    test("block_event NOT recorded on allow decision") {
      for {
        _   <- cleanDb
        rr  <- ZIO.service[RouterRepo]
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        sr  <- ZIO.service[ScheduleRepo]
        ber <- ZIO.service[BlockEventRepo]
        kid <- TestLayers.seedKidsProfile(pr, sr)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        ps  <- makePsDefault
        routes = RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)
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
        _    <- cleanDb
        rr   <- ZIO.service[RouterRepo]
        pr   <- ZIO.service[ProfileRepo]
        dr   <- ZIO.service[DeviceRepo]
        sr   <- ZIO.service[ScheduleRepo]
        tlr  <- ZIO.service[TimeLimitRepo]
        stlr <- ZIO.service[SiteTimeLimitRepo]
        ur   <- ZIO.service[TimeUsageRepo]
        ber  <- ZIO.service[BlockEventRepo]
        kid  <- TestLayers.seedKidsProfile(pr, sr)
        _    <- tlr.upsert(kid, 120)
        _    <- stlr.replaceForProfile(
          kid,
          List(SiteTimeLimitRequest("youtube.com", 200, "YouTube", exemptFromDaily = false)),
        )
        _    <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        _    <- ur.incrementSecondsAndBytes(
          "aa:bb:cc:11:22:33",
          "youtube.com",
          LocalDate.of(2025, 1, 6),
          121L * 60L,
          0L,
          0L,
        )
        ps   <- makePsDefault
        routes = RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)
        tok  <- seedAndEnrollRouter(rr, routes)
        resp <- callDecide(routes, tok, "aa:bb:cc:11:22:33", "youtube.com")
        body <- resp.body.asString
        dr   <- ZIO.fromEither(body.fromJson[RouterDecisionResponse])
      yield assertTrue(dr.decision == "block") &&
        assertTrue(dr.reason == "time_limit")
    },
    test(
      "exempt site (exemptFromDaily=true): site usage does NOT count toward daily total → allow",
    ) {
      // Daily cap is 120. 121 min of YouTube usage, but YouTube is exempt from daily cap.
      // YouTube site cap is 200 (not hit either) → should allow.
      for
        _    <- cleanDb
        rr   <- ZIO.service[RouterRepo]
        pr   <- ZIO.service[ProfileRepo]
        dr   <- ZIO.service[DeviceRepo]
        sr   <- ZIO.service[ScheduleRepo]
        tlr  <- ZIO.service[TimeLimitRepo]
        stlr <- ZIO.service[SiteTimeLimitRepo]
        ur   <- ZIO.service[TimeUsageRepo]
        ber  <- ZIO.service[BlockEventRepo]
        kid  <- TestLayers.seedKidsProfile(pr, sr)
        _    <- tlr.upsert(kid, 120)
        _    <- stlr.replaceForProfile(
          kid,
          List(
            // exemptFromDaily=true (default): YouTube time does NOT eat into 120-min daily cap
            SiteTimeLimitRequest("youtube.com", 200, "YouTube", exemptFromDaily = true),
          ),
        )
        _    <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        _    <- ur.incrementSecondsAndBytes(
          "aa:bb:cc:11:22:33",
          "youtube.com",
          LocalDate.of(2025, 1, 6),
          121L * 60L,
          0L,
          0L,
        )
        ps   <- makePsDefault
        routes = RouterRoutes.routes(rr, ps, RouterAuthLive(rr), ber)
        tok  <- seedAndEnrollRouter(rr, routes)
        resp <- callDecide(routes, tok, "aa:bb:cc:11:22:33", "youtube.com")
        body <- resp.body.asString
        dr   <- ZIO.fromEither(body.fromJson[RouterDecisionResponse])
      yield assertTrue(dr.decision == "allow")
    },
  ) @@ TestAspect.sequential
}
