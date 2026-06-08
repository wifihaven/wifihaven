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

import java.time.LocalDateTime

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
      sr     <- ZIO.service[ScheduleRepo]
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
      sr,
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
        _   <- cleanDb
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        blr <- ZIO.service[BlocklistRepo]
        ps  <- makePsAt(TestClock.schoolDayAfternoon)
        routes = BlockedRoutes.routes(ps, dr, pr, blr)
        info <- callBlocked(routes, "ff:ff:ff:ff:ff:ff", "example.com")
      } yield assertTrue(!info.blocked) &&
        assertTrue(info.reasonClass.isEmpty) &&
        assertTrue(info.profileName.isEmpty)
    },
    test("paused profile → blocked:true, reasonClass=paused, profileName populated") {
      for {
        _   <- cleanDb
        pr  <- ZIO.service[ProfileRepo]
        sr  <- ZIO.service[ScheduleRepo]
        dr  <- ZIO.service[DeviceRepo]
        blr <- ZIO.service[BlocklistRepo]
        kid <- TestLayers.seedKidsProfile(pr, sr)
        _   <- pr.setPaused(kid, true)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        ps  <- makePsAt(TestClock.schoolDayAfternoon)
        routes = BlockedRoutes.routes(ps, dr, pr, blr)
        info <- callBlocked(routes, "aa:bb:cc:11:22:33", "example.com")
      } yield assertTrue(info.blocked) &&
        assertTrue(info.reasonClass.contains("paused")) &&
        assertTrue(info.profileName.contains("Kids")) &&
        assertTrue(info.categoryName.isEmpty)
    },
    test("active schedule → reasonClass=schedule, NO expiresAt/end-time leak") {
      for {
        _   <- cleanDb
        pr  <- ZIO.service[ProfileRepo]
        sr  <- ZIO.service[ScheduleRepo]
        dr  <- ZIO.service[DeviceRepo]
        blr <- ZIO.service[BlocklistRepo]
        kid <- TestLayers.seedKidsProfile(pr, sr)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:33", "kid-ipad", kid)
        ps  <- makePsAt(TestClock.bedtime)
        routes = BlockedRoutes.routes(ps, dr, pr, blr)
        info <- callBlocked(routes, "aa:bb:cc:11:22:33", "example.com")
      } yield assertTrue(info.blocked) &&
        assertTrue(info.reasonClass.contains("schedule")) &&
        assertTrue(info.profileName.contains("Kids"))
    },
    test("malformed mac → blocked:false (no error leakage)") {
      for {
        _   <- cleanDb
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        blr <- ZIO.service[BlocklistRepo]
        ps  <- makePsAt(TestClock.schoolDayAfternoon)
        routes = BlockedRoutes.routes(ps, dr, pr, blr)
        info <- callBlocked(routes, "not-a-mac", "example.com")
      } yield assertTrue(!info.blocked)
    },
    test("allowed host on enrolled device → blocked:false") {
      for {
        _      <- cleanDb
        pr     <- ZIO.service[ProfileRepo]
        sr     <- ZIO.service[ScheduleRepo]
        dr     <- ZIO.service[DeviceRepo]
        blr    <- ZIO.service[BlocklistRepo]
        adults <- TestLayers.seedAdultsProfile(pr)
        _      <- TestLayers.seedDevice(dr, "aa:bb:cc:11:22:55", "ipad", adults)
        ps     <- makePsAt(TestClock.schoolDayAfternoon)
        routes = BlockedRoutes.routes(ps, dr, pr, blr)
        info <- callBlocked(routes, "aa:bb:cc:11:22:55", "example.com")
      } yield assertTrue(!info.blocked)
    },
    // #1545: regression for the silent `case _ => extra_blocked` fallthrough in
    // BlockedRoutes.mapReason. A reason the API doesn't recognize (e.g. a future
    // `decide()` reason an older block-page build hasn't learned) must render the
    // GENERIC block copy, NOT be mislabeled as "extra_blocked" ("a specific site").
    test("unknown block reason → generic reasonClass, NOT mislabeled extra_blocked") {
      val stubPolicy = new PolicyService {
        def snapshot: Task[PolicySnapshot]                                      =
          ZIO.dieMessage("snapshot unused in this test")
        def renderBlocklist(id: BlocklistId): Task[Option[(ETag, String)]]      =
          ZIO.dieMessage("renderBlocklist unused in this test")
        def decide(mac: String, hostname: String): Task[RouterDecisionResponse] =
          ZIO.succeed(
            RouterDecisionResponse(ConnectionDecision.Block, "future_app:slack", None),
          )
      }
      for {
        _   <- cleanDb
        pr  <- ZIO.service[ProfileRepo]
        dr  <- ZIO.service[DeviceRepo]
        blr <- ZIO.service[BlocklistRepo]
        routes = BlockedRoutes.routes(stubPolicy, dr, pr, blr)
        info <- callBlocked(routes, "aa:bb:cc:11:22:99", "weird.example.com")
      } yield assertTrue(info.blocked) &&
        assertTrue(!info.reasonClass.contains("extra_blocked")) &&
        assertTrue(info.reasonClass.contains("blocked")) &&
        assertTrue(info.categoryName.isEmpty)
    },
  ) @@ TestAspect.sequential
}
