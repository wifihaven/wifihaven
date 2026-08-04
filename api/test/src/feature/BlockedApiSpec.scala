package wifihaven.api.feature

import wifihaven.api.auth.RateLimiter
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

import java.time.{LocalDate, LocalDateTime, ZoneOffset}

/**
 * #959: kid-side block-page endpoint. Verifies the unauthenticated reason-class shape (no schedule
 * end times, no minute counts) and that unknown MACs return a generic not-blocked response rather
 * than leaking enrollment.
 */
object BlockedApiSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

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
    } yield (
      PolicyServiceLive(
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
      ): PolicyService,
      clk: Clock,
    )

  private def makeTimeStatus =
    for {
      pr   <- ZIO.service[ProfileRepo]
      tlr  <- ZIO.service[TimeLimitRepo]
      atlr <- ZIO.service[AppTimeLimitRepo]
      dr   <- ZIO.service[DeviceRepo]
      trr  <- ZIO.service[TrafficReportRepo]
      er   <- ZIO.service[TimeExtensionRepo]
    } yield new TimeStatusServiceLive(pr, tlr, atlr, dr, trr, er): TimeStatusService

  private def seedRouter: ZIO[RouterRepo, Throwable, RouterId] =
    ZIO.serviceWithZIO[RouterRepo] { rr =>
      for {
        id <- rr.create("test-router", Sha256Hex.unsafe("t" * 64))
        _  <- rr.completeEnrollment(id, Sha256Hex.unsafe("u" * 64))
      } yield id
    }

  // Mirrors TimeApiSpec.seedTraffic: `minutes/5` non-overlapping 5-min buckets.
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
          500_000L,
          500_000L,
        )
      }.toList
      tr.insertBatch(inserts).as(bucketOffset + buckets)
    }

  private def callBlocked(
      routes: Routes[Any, Response],
      mac: String,
      host: String,
  ): Task[BlockedInfoResponse] =
    for {
      resp <- routes.runZIO(
        Request.get(URL.decode(s"/api/blocked?mac=$mac&host=$host").toOption.get),
      )
      body <- resp.body.asString
      r    <- ZIO.fromEither(body.fromJson[BlockedInfoResponse]).mapError(new RuntimeException(_))
    } yield r

  def spec = suite("GET /api/blocked")(
    test("unknown MAC → blocked:false (no enrollment leak)") {
      for {
        _     <- cleanDb
        pr    <- ZIO.service[ProfileRepo]
        dr    <- ZIO.service[DeviceRepo]
        blr   <- ZIO.service[BlocklistRepo]
        psClk <- makePsAt(TestClock.schoolDayAfternoon)
        (ps, clk) = psClk
        tss <- makeTimeStatus
        hsr <- ZIO.service[HouseholdSettingsRepo]
        routes = BlockedRoutes.routes(
          ps,
          dr,
          pr,
          blr,
          tss,
          hsr,
          clk,
          BlockPageHousehold.defaultOnly,
          RateLimiter.allowAll,
          RateLimiter.allowAll,
        )
        info <- callBlocked(routes, "ff:ff:ff:ff:ff:ff", "example.com")
      } yield assertTrue(!info.blocked) &&
        assertTrue(info.reasonClass.isEmpty) &&
        assertTrue(info.profileName.isEmpty)
    },
    test("paused profile → blocked:true, reasonClass=paused, profileName populated") {
      for {
        _     <- cleanDb
        pr    <- ZIO.service[ProfileRepo]
        dr    <- ZIO.service[DeviceRepo]
        blr   <- ZIO.service[BlocklistRepo]
        kid   <- TestLayers.seedKidsProfile(pr)
        _     <- pr.setPaused(kid, true)
        _     <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        psClk <- makePsAt(TestClock.schoolDayAfternoon)
        (ps, clk) = psClk
        tss <- makeTimeStatus
        hsr <- ZIO.service[HouseholdSettingsRepo]
        routes = BlockedRoutes.routes(
          ps,
          dr,
          pr,
          blr,
          tss,
          hsr,
          clk,
          BlockPageHousehold.defaultOnly,
          RateLimiter.allowAll,
          RateLimiter.allowAll,
        )
        info <- callBlocked(routes, "aa:bb:cc:11:22:33", "example.com")
      } yield assertTrue(info.blocked) &&
        assertTrue(info.reasonClass.contains("paused")) &&
        assertTrue(info.profileName.contains("Kids")) &&
        assertTrue(info.categoryName.isEmpty)
    },
    test("active schedule → reasonClass=schedule, NO expiresAt/end-time leak") {
      for {
        _     <- cleanDb
        pr    <- ZIO.service[ProfileRepo]
        dr    <- ZIO.service[DeviceRepo]
        blr   <- ZIO.service[BlocklistRepo]
        kid   <- TestLayers.seedKidsProfile(pr)
        _     <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        psClk <- makePsAt(TestClock.bedtime)
        (ps, clk) = psClk
        tss <- makeTimeStatus
        hsr <- ZIO.service[HouseholdSettingsRepo]
        routes = BlockedRoutes.routes(
          ps,
          dr,
          pr,
          blr,
          tss,
          hsr,
          clk,
          BlockPageHousehold.defaultOnly,
          RateLimiter.allowAll,
          RateLimiter.allowAll,
        )
        info <- callBlocked(routes, "aa:bb:cc:11:22:33", "example.com")
      } yield assertTrue(info.blocked) &&
        assertTrue(info.reasonClass.contains("schedule")) &&
        assertTrue(info.profileName.contains("Kids"))
    },
    test("malformed mac → blocked:false (no error leakage)") {
      for {
        _     <- cleanDb
        pr    <- ZIO.service[ProfileRepo]
        dr    <- ZIO.service[DeviceRepo]
        blr   <- ZIO.service[BlocklistRepo]
        psClk <- makePsAt(TestClock.schoolDayAfternoon)
        (ps, clk) = psClk
        tss <- makeTimeStatus
        hsr <- ZIO.service[HouseholdSettingsRepo]
        routes = BlockedRoutes.routes(
          ps,
          dr,
          pr,
          blr,
          tss,
          hsr,
          clk,
          BlockPageHousehold.defaultOnly,
          RateLimiter.allowAll,
          RateLimiter.allowAll,
        )
        info <- callBlocked(routes, "not-a-mac", "example.com")
      } yield assertTrue(!info.blocked)
    },
    test("allowed host on enrolled device → blocked:false") {
      for {
        _      <- cleanDb
        pr     <- ZIO.service[ProfileRepo]
        dr     <- ZIO.service[DeviceRepo]
        blr    <- ZIO.service[BlocklistRepo]
        adults <- TestLayers.seedAdultsProfile(pr)
        _      <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:55", "ipad", adults)
        psClk  <- makePsAt(TestClock.schoolDayAfternoon)
        (ps, clk) = psClk
        tss <- makeTimeStatus
        hsr <- ZIO.service[HouseholdSettingsRepo]
        routes = BlockedRoutes.routes(
          ps,
          dr,
          pr,
          blr,
          tss,
          hsr,
          clk,
          BlockPageHousehold.defaultOnly,
          RateLimiter.allowAll,
          RateLimiter.allowAll,
        )
        info <- callBlocked(routes, "aa:bb:cc:11:22:55", "example.com")
      } yield assertTrue(!info.blocked)
    },
    // #1545: regression for the silent `case _ => extra_blocked` fallthrough in
    // BlockedRoutes.mapReason. A reason the API doesn't recognize (e.g. a future
    // `decide()` reason an older block-page build hasn't learned) must render the
    // GENERIC block copy, NOT be mislabeled as "extra_blocked" ("a specific site").
    test("unknown block reason → generic reasonClass, NOT mislabeled extra_blocked") {
      val stubPolicy = new PolicyService {
        def snapshot(household: HouseholdId): Task[PolicySnapshot] =
          ZIO.dieMessage("snapshot unused in this test")
        def renderBlocklist(
            household: HouseholdId,
            id: BlocklistId,
        ): Task[Option[(ETag, String)]] =
          ZIO.dieMessage("renderBlocklist unused in this test")
        def decide(
            household: HouseholdId,
            mac: String,
            hostname: String,
        ): Task[RouterDecisionResponse] =
          ZIO.succeed(
            RouterDecisionResponse(ConnectionDecision.Block, "future_app:slack", None),
          )
        def invalidate: UIO[Unit]                                  = ZIO.unit
        def reevaluate: UIO[Unit]                                  = ZIO.unit
        def setPublisher(publisher: wifihaven.api.policy.PolicySnapshotPublisher): UIO[Unit] =
          ZIO.unit
      }
      for {
        _   <- cleanDb
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        blr <- ZIO.service[BlocklistRepo]
        tss <- makeTimeStatus
        hsr <- ZIO.service[HouseholdSettingsRepo]
        clk <- ZIO.service[Clock]
        routes = BlockedRoutes.routes(
          stubPolicy,
          dr,
          pr,
          blr,
          tss,
          hsr,
          clk,
          BlockPageHousehold.defaultOnly,
          RateLimiter.allowAll,
          RateLimiter.allowAll,
        )
        info <- callBlocked(routes, "aa:bb:cc:11:22:99", "weird.example.com")
      } yield assertTrue(info.blocked) &&
        assertTrue(!info.reasonClass.contains("extra_blocked")) &&
        assertTrue(info.reasonClass.contains("blocked")) &&
        assertTrue(info.categoryName.isEmpty)
    },
    // #1532: pins the reasonClass invariant — for every BlockReason case that
    // BlockedRoutes.mapReason recognizes, the reasonClass it emits MUST equal that
    // case's wireKind (or the documented generic "blocked" fallthrough for the
    // unrecognized-on-the-block-page bucket). This is the test-pin behind the
    // collapse that sources reasonClass strings from `wireKind` instead of
    // hand-written literals — the test holds both before and after.
    test("reasonClass strings track BlockReason.wireKind for every recognized case") {
      def stub(wire: String): PolicyService = new PolicyService {
        def snapshot(household: HouseholdId): Task[PolicySnapshot] =
          ZIO.dieMessage("snapshot unused in this test")
        def renderBlocklist(
            household: HouseholdId,
            id: BlocklistId,
        ): Task[Option[(ETag, String)]] =
          ZIO.dieMessage("renderBlocklist unused in this test")
        def decide(
            household: HouseholdId,
            mac: String,
            hostname: String,
        ): Task[RouterDecisionResponse] =
          ZIO.succeed(RouterDecisionResponse(ConnectionDecision.Block, wire, None))
        def invalidate: UIO[Unit]                                  = ZIO.unit
        def reevaluate: UIO[Unit]                                  = ZIO.unit
        def setPublisher(publisher: wifihaven.api.policy.PolicySnapshotPublisher): UIO[Unit] =
          ZIO.unit
      }
      // Each entry is (wire reason string emitted by decide(), expected reasonClass).
      // The expected value is taken directly from the case's `wireKind` — if a
      // wireKind is renamed in shared/Models the same value flows through here.
      val cases: List[(String, String)]     = List(
        BlockReason.asWire(MacBlockReason.Paused)    -> MacBlockReason.Paused.wireKind,
        BlockReason.asWire(MacBlockReason.Schedule)  -> MacBlockReason.Schedule.wireKind,
        BlockReason.asWire(MacBlockReason.TimeLimit) -> MacBlockReason.TimeLimit.wireKind,
        BlockReason.asWire(BlockReason.ExtraBlocked) -> BlockReason.ExtraBlocked.wireKind,
        BlockReason.asWire(
          BlockReason.ExtraBlockedBy(
            Hostname.unsafe("example.com"),
          ),
        )                                            -> BlockReason.ExtraBlocked.wireKind,
        BlockReason.asWire(BlockReason.AppTimeLimit("YouTube")) -> BlockReason
          .AppTimeLimit("YouTube")
          .wireKind,
      )
      // The stub PolicyService short-circuits before any repo read, so a single
      // cleanDb + shared service binding is sufficient for all cases — no need to
      // pay the embedded-Postgres reset per case.
      for {
        _       <- cleanDb
        pr      <- ZIO.service[ProfileRepo]
        dr      <- ZIO.service[DeviceRepo]
        blr     <- ZIO.service[BlocklistRepo]
        tss     <- makeTimeStatus
        hsr     <- ZIO.service[HouseholdSettingsRepo]
        clk     <- ZIO.service[Clock]
        results <- ZIO.foreach(cases) { case (wire, expected) =>
          val routes = BlockedRoutes.routes(
            stub(wire),
            dr,
            pr,
            blr,
            tss,
            hsr,
            clk,
            BlockPageHousehold.defaultOnly,
            RateLimiter.allowAll,
            RateLimiter.allowAll,
          )
          callBlocked(routes, "aa:bb:cc:dd:ee:ff", "example.com").map { info =>
            assertTrue(info.blocked) && assertTrue(info.reasonClass.contains(expected))
          }
        }
      } yield results.reduce(_ && _)
    },
    // #335: kid-side block page must show today's usage so a restricted kid can see
    // *why* their time is gone, not just that it is. The fields are populated for any
    // enrolled MAC (blocked or not) so the page can render usage on a non-blocked
    // request too. Values come from the canonical TimeStatusService.todaysState — no
    // re-derivation here (single source of truth).
    test("enrolled MAC → response includes today's usage (used/cap/extension/remaining)") {
      for {
        _   <- cleanDb
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        blr <- ZIO.service[BlocklistRepo]
        tlr <- ZIO.service[TimeLimitRepo]
        kid <- TestLayers.seedKidsProfile(pr)
        _   <- tlr.upsert(kid, 120)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:44", "kid-ipad", kid)
        rid <- seedRouter
        today = TestClock.bedtime.toLocalDate
        _     <- seedTraffic(rid, "aa:bb:cc:11:22:44", "minecraft.net", today, 45)
        psClk <- makePsAt(TestClock.bedtime)
        (ps, clk) = psClk
        tss <- makeTimeStatus
        hsr <- ZIO.service[HouseholdSettingsRepo]
        routes = BlockedRoutes.routes(
          ps,
          dr,
          pr,
          blr,
          tss,
          hsr,
          clk,
          BlockPageHousehold.defaultOnly,
          RateLimiter.allowAll,
          RateLimiter.allowAll,
        )
        info <- callBlocked(routes, "aa:bb:cc:11:22:44", "example.com")
      } yield assertTrue(info.blocked) &&
        assertTrue(info.reasonClass.contains("schedule")) &&
        assertTrue(info.dailyLimitMinutes.contains(120)) &&
        assertTrue(info.usedMinutes.contains(45)) &&
        assertTrue(info.extensionMinutes.contains(0)) &&
        assertTrue(info.remainingMinutes.contains(75))
    },
    test("unknown MAC → usage fields are None (no enrollment leak)") {
      for {
        _     <- cleanDb
        pr    <- ZIO.service[ProfileRepo]
        dr    <- ZIO.service[DeviceRepo]
        blr   <- ZIO.service[BlocklistRepo]
        psClk <- makePsAt(TestClock.schoolDayAfternoon)
        (ps, clk) = psClk
        tss <- makeTimeStatus
        hsr <- ZIO.service[HouseholdSettingsRepo]
        routes = BlockedRoutes.routes(
          ps,
          dr,
          pr,
          blr,
          tss,
          hsr,
          clk,
          BlockPageHousehold.defaultOnly,
          RateLimiter.allowAll,
          RateLimiter.allowAll,
        )
        info <- callBlocked(routes, "ff:ff:ff:ff:ff:ff", "example.com")
      } yield assertTrue(!info.blocked) &&
        assertTrue(info.usedMinutes.isEmpty) &&
        assertTrue(info.dailyLimitMinutes.isEmpty) &&
        assertTrue(info.remainingMinutes.isEmpty)
    },
  ) @@ TestAspect.sequential
}
