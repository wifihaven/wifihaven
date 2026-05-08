package familydns.api.feature

import familydns.api.JwtConfig
import familydns.api.auth.*
import familydns.api.db.*
import familydns.api.policy.PolicyServiceLive
import familydns.api.routes.*
import familydns.shared.*
import familydns.shared.Clock.TestClock
import familydns.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*
import zio.test.Assertion.*

object TimeApiSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg   = JwtConfig(secret = "test-secret-at-least-32-chars!!", expiryHours = 1)
  private def makeAuth =
    for
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    yield AuthServiceLive(ur, jwtCfg, clock)
  private def cleanDb  = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

  private val testMac = "aa:bb:cc:dd:ee:01"

  def spec = suite("Time API")(
    suite("GET /api/time/status")(
      test("shows zero usage for a new device") {
        for
          _               <- cleanDb
          profileRepo     <- ZIO.service[ProfileRepo]
          tlRepo          <- ZIO.service[TimeLimitRepo]
          stlRepo         <- ZIO.service[SiteTimeLimitRepo]
          schedRepo       <- ZIO.service[ScheduleRepo]
          deviceRepo      <- ZIO.service[DeviceRepo]
          usageRepo       <- ZIO.service[TimeUsageRepo]
          extRepo         <- ZIO.service[TimeExtensionRepo]
          auth            <- makeAuth
          token           <- auth.login("admin", "changeme").map(_.token)
          kidsId          <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _               <- tlRepo.upsert(kidsId, 120)
          _               <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          clock           <- ZIO.service[Clock]
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            stlRepo,
            usageRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            clock,
          )
          req    = Request
            .get(URL.decode(s"/api/time/status/$testMac").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp   <- routes.runZIO(req)
          body   <- resp.body.asString
          status <- ZIO.fromEither(body.fromJson[DeviceTimeStatus])
        yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(status.dailyLimitMins.contains(120)) &&
          assertTrue(status.usedMins == 0) &&
          assertTrue(status.extensionMins == 0) &&
          assertTrue(status.remainingMins.contains(120))
      },
      test("reflects accumulated usage correctly") {
        for
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          stlRepo     <- ZIO.service[SiteTimeLimitRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          usageRepo   <- ZIO.service[TimeUsageRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- tlRepo.upsert(kidsId, 120)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          today = TestClock.schoolDayAfternoon.toLocalDate
          _               <- usageRepo.incrementUsage(testMac, "minecraft.net", today, 45)
          _               <- usageRepo.incrementUsage(testMac, "google.com", today, 30)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          clock           <- ZIO.service[Clock]
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            stlRepo,
            usageRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            clock,
          )
          req    = Request
            .get(URL.decode(s"/api/time/status/$testMac").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp   <- routes.runZIO(req)
          body   <- resp.body.asString
          status <- ZIO.fromEither(body.fromJson[DeviceTimeStatus])
        yield assertTrue(status.usedMins == 75) &&
          assertTrue(status.remainingMins.contains(45))
      },
      test("site-specific usage shown separately and not counted in total") {
        for
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          stlRepo     <- ZIO.service[SiteTimeLimitRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          usageRepo   <- ZIO.service[TimeUsageRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- tlRepo.upsert(kidsId, 120)
          _           <- stlRepo.replaceForProfile(
            kidsId,
            List(
              SiteTimeLimitRequest("*.youtube.com", 30, "YouTube"),
            ),
          )
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          today = TestClock.schoolDayAfternoon.toLocalDate
          // 60 min general browsing + 20 min YouTube (site-specific, should NOT count toward 120)
          _               <- usageRepo.incrementUsage(testMac, "google.com", today, 60)
          _               <- usageRepo.incrementUsage(testMac, "youtube.com", today, 20)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          clock           <- ZIO.service[Clock]
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            stlRepo,
            usageRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            clock,
          )
          req    = Request
            .get(URL.decode(s"/api/time/status/$testMac").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp   <- routes.runZIO(req)
          body   <- resp.body.asString
          status <- ZIO.fromEither(body.fromJson[DeviceTimeStatus])
        yield assertTrue(status.usedMins == 60) && // YouTube NOT counted in total
          assertTrue(status.remainingMins.contains(60)) &&
          assertTrue(
            status.siteUsage.exists(su =>
              su.label == "YouTube" && su.usedMins == 20 && su.remainingMins == 10,
            ),
          )
      },
    ),
    suite("POST /api/time/extend")(
      test("admin can grant profile extension which increases remaining for all devices") {
        for
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          stlRepo     <- ZIO.service[SiteTimeLimitRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          usageRepo   <- ZIO.service[TimeUsageRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- tlRepo.upsert(kidsId, 120)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          today = TestClock.schoolDayAfternoon.toLocalDate
          _               <- usageRepo.incrementUsage(testMac, "minecraft.net", today, 120)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          clock           <- ZIO.service[Clock]
          routes  = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            stlRepo,
            usageRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            clock,
          )
          extBody = GrantExtensionRequest(kidsId, 30, Some("Homework finished early")).toJson
          extReq  = Request
            .post(URL.decode("/api/time/extend").toOption.get, Body.fromString(extBody))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json))
          extResp <- routes.runZIO(extReq)
          statusReq = Request
            .get(URL.decode(s"/api/time/status/$testMac").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          statusResp <- routes.runZIO(statusReq)
          body       <- statusResp.body.asString
          status     <- ZIO.fromEither(body.fromJson[DeviceTimeStatus])
        yield assertTrue(extResp.status == Status.Ok) &&
          assertTrue(status.extensionMins == 30) &&
          assertTrue(status.remainingMins.contains(30))
      },
      test("extension is logged with granting admin username") {
        for
          _               <- cleanDb
          profileRepo     <- ZIO.service[ProfileRepo]
          tlRepo          <- ZIO.service[TimeLimitRepo]
          stlRepo         <- ZIO.service[SiteTimeLimitRepo]
          schedRepo       <- ZIO.service[ScheduleRepo]
          deviceRepo      <- ZIO.service[DeviceRepo]
          usageRepo       <- ZIO.service[TimeUsageRepo]
          extRepo         <- ZIO.service[TimeExtensionRepo]
          auth            <- makeAuth
          token           <- auth.login("admin", "changeme").map(_.token)
          kidsId          <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _               <- tlRepo.upsert(kidsId, 60)
          _               <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          clock           <- ZIO.service[Clock]
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            stlRepo,
            usageRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            clock,
          )
          body   = GrantExtensionRequest(kidsId, 15, Some("Good behavior")).toJson
          req    = Request
            .post(URL.decode("/api/time/extend").toOption.get, Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json))
          _    <- routes.runZIO(req)
          exts <- extRepo.listForProfile(kidsId, TestClock.schoolDayAfternoon.toLocalDate)
        yield assertTrue(exts.length == 1) &&
          assertTrue(exts.head.grantedBy == "admin") &&
          assertTrue(exts.head.extraMinutes == 15) &&
          assertTrue(exts.head.note.contains("Good behavior"))
      },
      test("child user cannot grant extensions") {
        for
          _               <- cleanDb
          profileRepo     <- ZIO.service[ProfileRepo]
          tlRepo          <- ZIO.service[TimeLimitRepo]
          stlRepo         <- ZIO.service[SiteTimeLimitRepo]
          schedRepo       <- ZIO.service[ScheduleRepo]
          deviceRepo      <- ZIO.service[DeviceRepo]
          usageRepo       <- ZIO.service[TimeUsageRepo]
          extRepo         <- ZIO.service[TimeExtensionRepo]
          userRepo        <- ZIO.service[UserRepo]
          auth            <- makeAuth
          hash            <- auth.hashPassword("pass")
          _               <- userRepo.create("kidview", hash, "child")
          token           <- auth.login("kidview", "pass").map(_.token)
          kidsId          <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _               <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          clock           <- ZIO.service[Clock]
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            stlRepo,
            usageRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            clock,
          )
          body   = GrantExtensionRequest(kidsId, 30, None).toJson
          req    = Request
            .post(URL.decode("/api/time/extend").toOption.get, Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
        yield assertTrue(resp.status == Status.Forbidden)
      },
      test("multiple extensions accumulate at profile level") {
        for
          _               <- cleanDb
          profileRepo     <- ZIO.service[ProfileRepo]
          tlRepo          <- ZIO.service[TimeLimitRepo]
          stlRepo         <- ZIO.service[SiteTimeLimitRepo]
          schedRepo       <- ZIO.service[ScheduleRepo]
          deviceRepo      <- ZIO.service[DeviceRepo]
          usageRepo       <- ZIO.service[TimeUsageRepo]
          extRepo         <- ZIO.service[TimeExtensionRepo]
          auth            <- makeAuth
          token           <- auth.login("admin", "changeme").map(_.token)
          kidsId          <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _               <- tlRepo.upsert(kidsId, 60)
          _               <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          clock           <- ZIO.service[Clock]
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            stlRepo,
            usageRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            clock,
          )
          grant  = (mins: Int) =>
            routes.runZIO(
              Request
                .post(
                  URL.decode("/api/time/extend").toOption.get,
                  Body.fromString(GrantExtensionRequest(kidsId, mins, None).toJson),
                )
                .addHeader(Header.Authorization.Bearer(token))
                .addHeader(Header.ContentType(MediaType.application.json)),
            )
          _ <- grant(15)
          _ <- grant(15)
          _ <- grant(30)
          today = TestClock.schoolDayAfternoon.toLocalDate
          exts  <- extRepo.listForProfile(kidsId, today)
          total <- extRepo.getProfileTotalExtension(kidsId, today)
        yield assertTrue(exts.length == 3) &&
          assertTrue(total == 60)
      },
    ),

    // ── per-profile status rollup ───────────────────────────────────────────
    suite("GET /api/time/status — per-profile rollup")(
      test("returns one ProfileTimeStatus per profile, not per device") {
        for
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          stlRepo     <- ZIO.service[SiteTimeLimitRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          usageRepo   <- ZIO.service[TimeUsageRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- tlRepo.upsert(kidsId, 120)
          _           <- TestLayers.seedDevice(deviceRepo, "aa:bb:cc:dd:ee:01", "iPad", kidsId)
          _           <- TestLayers.seedDevice(deviceRepo, "aa:bb:cc:dd:ee:02", "iPhone", kidsId)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          clock           <- ZIO.service[Clock]
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            stlRepo,
            usageRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            clock,
          )
          req    = Request
            .get(URL.decode("/api/time/status").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          list <- ZIO.fromEither(body.fromJson[List[ProfileTimeStatus]])
          kids = list.find(_.profileId == kidsId).get
        yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(list.count(_.profileId == kidsId) == 1) && // one entry, not two
          assertTrue(kids.devices.length == 2) &&               // both devices in breakdown
          assertTrue(kids.devices.map(_.deviceName).toSet == Set("iPad", "iPhone"))
      },
      test("two devices on same profile share a combined usage pool") {
        for
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          stlRepo     <- ZIO.service[SiteTimeLimitRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          usageRepo   <- ZIO.service[TimeUsageRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- tlRepo.upsert(kidsId, 60)
          mac1 = "aa:bb:cc:dd:ee:01"
          mac2 = "aa:bb:cc:dd:ee:02"
          _ <- TestLayers.seedDevice(deviceRepo, mac1, "iPad", kidsId)
          _ <- TestLayers.seedDevice(deviceRepo, mac2, "iPhone", kidsId)
          today = TestClock.schoolDayAfternoon.toLocalDate
          // Device 1: 40 min, Device 2: 35 min → combined 75 min > 60 min limit
          _               <- usageRepo.incrementUsage(mac1, "minecraft.net", today, 40)
          _               <- usageRepo.incrementUsage(mac2, "youtube.com", today, 35)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          clock           <- ZIO.service[Clock]
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            stlRepo,
            usageRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            clock,
          )
          req    = Request
            .get(URL.decode("/api/time/status").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          list <- ZIO.fromEither(body.fromJson[List[ProfileTimeStatus]])
          kids = list.find(_.profileId == kidsId).get
        yield assertTrue(kids.usedMins == 75) &&        // both devices summed
          assertTrue(kids.dailyLimitMins.contains(60)) &&
          assertTrue(kids.remainingMins.contains(0)) && // clamped to 0, not negative
          assertTrue(kids.devices.find(_.deviceMac == mac1).get.usedMins == 40) &&
          assertTrue(kids.devices.find(_.deviceMac == mac2).get.usedMins == 35)
      },
      test("per-app site usage aggregated across all profile devices") {
        for
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          stlRepo     <- ZIO.service[SiteTimeLimitRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          usageRepo   <- ZIO.service[TimeUsageRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- tlRepo.upsert(kidsId, 120)
          _           <- stlRepo.replaceForProfile(
            kidsId,
            List(SiteTimeLimitRequest("youtube.com", 30, "YouTube")),
          )
          mac1 = "aa:bb:cc:dd:ee:01"
          mac2 = "aa:bb:cc:dd:ee:02"
          _ <- TestLayers.seedDevice(deviceRepo, mac1, "iPad", kidsId)
          _ <- TestLayers.seedDevice(deviceRepo, mac2, "iPhone", kidsId)
          today = TestClock.schoolDayAfternoon.toLocalDate
          // Each device uses 20 min YouTube → combined 40 min > 30 min limit
          _               <- usageRepo.incrementUsage(mac1, "youtube.com", today, 20)
          _               <- usageRepo.incrementUsage(mac2, "youtube.com", today, 20)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          clock           <- ZIO.service[Clock]
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            stlRepo,
            usageRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            clock,
          )
          req    = Request
            .get(URL.decode("/api/time/status").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          list <- ZIO.fromEither(body.fromJson[List[ProfileTimeStatus]])
          kids = list.find(_.profileId == kidsId).get
          yt   = kids.siteUsage.find(_.label == "YouTube").get
        yield assertTrue(yt.usedMins == 40) && // both devices summed
          assertTrue(yt.limitMins == 30) &&
          assertTrue(yt.remainingMins == 0) && // clamped to 0
          assertTrue(kids.usedMins == 0)       // site usage NOT counted in total
      },
      test("policy snapshot has profile-level time_used_today aggregated across devices") {
        for
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          stlRepo     <- ZIO.service[SiteTimeLimitRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          usageRepo   <- ZIO.service[TimeUsageRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- tlRepo.upsert(kidsId, 60)
          mac1 = "aa:bb:cc:dd:ee:01"
          mac2 = "aa:bb:cc:dd:ee:02"
          _ <- TestLayers.seedDevice(deviceRepo, mac1, "iPad", kidsId)
          _ <- TestLayers.seedDevice(deviceRepo, mac2, "iPhone", kidsId)
          today = TestClock.schoolDayAfternoon.toLocalDate
          _             <- usageRepo.incrementUsage(mac1, "minecraft.net", today, 30)
          _             <- usageRepo.incrementUsage(mac2, "roblox.com", today, 25)
          // Build policy snapshot directly via PolicyService
          blocklistRepo <- ZIO.service[BlocklistRepo]
          clock         <- ZIO.service[Clock]
          policyService = PolicyServiceLive(
            profileRepo,
            schedRepo,
            tlRepo,
            stlRepo,
            deviceRepo,
            blocklistRepo,
            usageRepo,
            extRepo,
            clock,
          )
          snapshot <- policyService.snapshot
          kidsPolicy = snapshot.profiles.find(_.id == kidsId).get
        yield assertTrue(kidsPolicy.dailyMinutes.contains(60)) &&
          assertTrue(kidsPolicy.timeUsedToday.totalMinutes == 55) // 30 + 25 across both devices
      },
    ) @@ TestAspect.sequential,
  ) @@ TestAspect.sequential
}
