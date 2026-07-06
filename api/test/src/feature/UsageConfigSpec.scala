package wifihaven.api.feature

import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.routes.UsageRoutes
import wifihaven.api.usage.RetentionSweepJob
import wifihaven.shared.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

// #1743: GET /api/usage/config emits the bucket → grain mapping so the SPA
// (web/src/components/usage/retentionGating.ts) reads it at runtime instead of
// hand-maintaining a parallel literal that must mirror `BucketPolicy.grainForBucket`.
object UsageConfigSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val jwtCfg    = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)
  private def buildAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)

  private def buildRoutes =
    for {
      deviceRepo      <- ZIO.service[DeviceRepo]
      trafficRepo     <- ZIO.service[TrafficReportRepo]
      userProfileRepo <- ZIO.service[UserProfileRepo]
      profileRepo     <- ZIO.service[ProfileRepo]
      appRepo         <- ZIO.service[AppRepo]
      rollupRepo      <- ZIO.service[RollupRepo]
      hsRepo          <- ZIO.service[HouseholdSettingsRepo]
      atlRepo         <- ZIO.service[AppTimeLimitRepo]
      aruRepo         <- ZIO.service[AppUsedRollupRepo]
      clock           <- ZIO.service[Clock]
      auth            <- buildAuth
    } yield (
      UsageRoutes.routes(
        auth,
        deviceRepo,
        trafficRepo,
        userProfileRepo,
        profileRepo,
        appRepo,
        rollupRepo,
        hsRepo,
        atlRepo,
        aruRepo,
        clock,
      ),
      auth,
    )

  private def loginAdmin(auth: AuthService): Task[String] =
    auth
      .login("admin", "changeme")
      .mapError(e => new RuntimeException(e.toString))
      .map(_.token.value)

  def spec = suite("GET /api/usage/config (#1743 + #1740)")(
    test("returns the canonical bucket → grain mapping from BucketPolicy") {
      for {
        _  <- cleanDb
        rb <- buildRoutes
        (routes, auth) = rb
        token <- loginAdmin(auth)
        url = URL.decode("/api/usage/config").toOption.get
        resp <- routes.runZIO(Request.get(url).addHeader(Header.Authorization.Bearer(token)))
        body <- resp.body.asString
        out  <- ZIO.fromEither(body.fromJson[UsageConfig])
        expected = BucketPolicy.bucketTiers.view.mapValues(_.wire).toMap
      } yield assertTrue(
        resp.status == Status.Ok,
        out.bucketTiers == expected,
        // Spot-checks so a future drop-the-test-and-just-pin-the-map regression
        // doesn't silently green if BucketPolicy itself is broken.
        out.bucketTiers("raw") == "raw",
        out.bucketTiers("10m") == "raw",
        out.bucketTiers("1h") == "hourly",
        out.bucketTiers("12h") == "hourly",
        out.bucketTiers("1d") == "daily",
        out.bucketTiers("1w") == "daily",
      )
    },
    // #1740: horizons ride on the same endpoint. The day counts come directly
    // from `RetentionSweepJob` so the SPA's bucket gate (retentionGating.ts)
    // can't drift from the sweep job's actual behaviour.
    test("returns the retention horizons sourced from RetentionSweepJob") {
      for {
        _  <- cleanDb
        rb <- buildRoutes
        (routes, auth) = rb
        token <- loginAdmin(auth)
        url = URL.decode("/api/usage/config").toOption.get
        resp <- routes.runZIO(Request.get(url).addHeader(Header.Authorization.Bearer(token)))
        body <- resp.body.asString
        out  <- ZIO.fromEither(body.fromJson[UsageConfig])
      } yield assertTrue(
        resp.status == Status.Ok,
        out.horizons.rawDays == RetentionSweepJob.RawRetentionDays,
        out.horizons.hourlyDays == RetentionSweepJob.HourlyRetentionDays,
        out.horizons.dailyDays == RetentionSweepJob.DailyRetentionDays,
        out.horizons == RetentionHorizons(30, 90, 180),
      )
    },
    test("requires auth") {
      for {
        _  <- cleanDb
        rb <- buildRoutes
        (routes, _) = rb
        url         = URL.decode("/api/usage/config").toOption.get
        resp <- routes.runZIO(Request.get(url))
      } yield assertTrue(resp.status == Status.Unauthorized)
    },
  ) @@ TestAspect.sequential
}
