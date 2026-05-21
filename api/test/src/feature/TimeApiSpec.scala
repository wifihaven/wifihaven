package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.policy.PolicyServiceLive
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
import zio.test.Assertion.*

import java.time.{LocalDate, ZoneOffset}

object TimeApiSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg   = JwtConfig(secret = "test-secret-at-least-32-chars!!", expiryHours = 1)
  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)
  private def cleanDb  = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

  private val testMac = "aa:bb:cc:dd:ee:01"

  // Seed a router row so traffic_reports FK passes. A single enrollment is enough — tests don't
  // care about router identity, just that the inserts succeed.
  private def seedRouter: ZIO[RouterRepo, Throwable, RouterId] =
    ZIO.serviceWithZIO[RouterRepo] { rr =>
      for {
        id <- rr.create("test-router", Sha256Hex.unsafe("t" * 64))
        _  <- rr.completeEnrollment(id, Sha256Hex.unsafe("u" * 64))
      } yield id
    }

  /**
   * Seed `minutes` minutes of traffic for (mac, hostname, date) as `minutes/5` non-overlapping
   * 5-min buckets starting at `bucketOffset * 300s` past midnight UTC. Returns the next free bucket
   * index so consecutive seeds for the same mac don't collide — which is what would happen in the
   * wild between different hostnames the device touched at the same instant.
   *
   * Bucket-level non-overlap matters: presence-based accounting counts each `(mac, period_start)`
   * once, so two hostnames sharing a bucket would only contribute one bucket's worth of minutes to
   * the device's total. Tests that want "30m on A + 20m on B = 50m on this device" must use
   * distinct bucket ranges.
   */
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
          // #789: real-traffic byte volume so the default heartbeat filter (10KB floor) keeps
          // these rows in the rollup.
          500_000L,
          500_000L,
        )
      }.toList
      tr.insertBatch(inserts).as(bucketOffset + buckets)
    }

  def spec = suite("Time API")(
    suite("GET /api/time/status")(
      test("shows zero usage for a new device") {
        for {
          _               <- cleanDb
          profileRepo     <- ZIO.service[ProfileRepo]
          tlRepo          <- ZIO.service[TimeLimitRepo]
          stlRepo         <- ZIO.service[SiteTimeLimitRepo]
          schedRepo       <- ZIO.service[ScheduleRepo]
          deviceRepo      <- ZIO.service[DeviceRepo]
          trafficRepo     <- ZIO.service[TrafficReportRepo]
          extRepo         <- ZIO.service[TimeExtensionRepo]
          auth            <- makeAuth
          token           <- auth.login("admin", "changeme").map(_.token.value)
          kidsId          <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _               <- tlRepo.upsert(kidsId, 120)
          _               <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            stlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            clock,
          )
          req    = Request
            .get(URL.decode(s"/api/time/status/$testMac").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp   <- routes.runZIO(req)
          body   <- resp.body.asString
          status <- ZIO.fromEither(body.fromJson[DeviceTimeStatus])
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(status.dailyLimitMins.contains(120)) &&
          assertTrue(status.usedMins == 0) &&
          assertTrue(status.extensionMins == 0) &&
          assertTrue(status.remainingMins.contains(120))
      },
      test("reflects accumulated usage correctly") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          stlRepo     <- ZIO.service[SiteTimeLimitRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- tlRepo.upsert(kidsId, 120)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          off1            <- seedTraffic(routerId, testMac, "minecraft.net", today, 45)
          _               <- seedTraffic(routerId, testMac, "google.com", today, 30, off1)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            stlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            clock,
          )
          req    = Request
            .get(URL.decode(s"/api/time/status/$testMac").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp   <- routes.runZIO(req)
          body   <- resp.body.asString
          status <- ZIO.fromEither(body.fromJson[DeviceTimeStatus])
        } yield assertTrue(status.usedMins == 75) &&
          assertTrue(status.remainingMins.contains(45))
      },
      test("site-specific usage shown separately and not counted in total") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          stlRepo     <- ZIO.service[SiteTimeLimitRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- tlRepo.upsert(kidsId, 120)
          _           <- stlRepo.replaceForProfile(
            kidsId,
            List(
              // default exemptFromDaily = true → does NOT count toward 120-min cap
              SiteTimeLimitRequest("*.youtube.com", 30, "YouTube"),
            ),
          )
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          // 60 min general browsing + 20 min YouTube (site-specific, should NOT count toward 120)
          off1            <- seedTraffic(routerId, testMac, "google.com", today, 60)
          _               <- seedTraffic(routerId, testMac, "youtube.com", today, 20, off1)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            stlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            clock,
          )
          req    = Request
            .get(URL.decode(s"/api/time/status/$testMac").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp   <- routes.runZIO(req)
          body   <- resp.body.asString
          status <- ZIO.fromEither(body.fromJson[DeviceTimeStatus])
        } yield assertTrue(status.usedMins == 60) && // YouTube NOT counted in total
          assertTrue(status.remainingMins.contains(60)) &&
          assertTrue(
            status.siteUsage.exists(su =>
              su.label == "YouTube" && su.usedMins == 20 && su.remainingMins == 10,
            ),
          )
      },
      test("included site (exemptFromDaily=false): usage IS counted in daily total") {
        for
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          stlRepo     <- ZIO.service[SiteTimeLimitRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- tlRepo.upsert(kidsId, 120)
          _           <- stlRepo.replaceForProfile(
            kidsId,
            List(
              // exemptFromDaily=false → YouTube minutes count against the 120-min daily cap
              SiteTimeLimitRequest("*.youtube.com", 60, "YouTube", exemptFromDaily = false),
            ),
          )
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          // 60 min of YouTube usage; since exemptFromDaily=false it must appear in usedMins
          _               <- seedTraffic(routerId, testMac, "youtube.com", today, 60)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            stlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            clock,
          )
          req    = Request
            .get(URL.decode(s"/api/time/status/$testMac").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp   <- routes.runZIO(req)
          body   <- resp.body.asString
          status <- ZIO.fromEither(body.fromJson[DeviceTimeStatus])
        yield assertTrue(status.usedMins == 60) &&         // YouTube IS counted in total
          assertTrue(status.remainingMins.contains(60)) && // 120 - 60 = 60
          assertTrue(
            status.siteUsage.exists(su =>
              su.label == "YouTube" && su.usedMins == 60 && su.remainingMins == 0,
            ),
          )
      },
    ),
    suite("POST /api/time/extend")(
      test("admin can grant profile extension which increases remaining for all devices") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          stlRepo     <- ZIO.service[SiteTimeLimitRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- tlRepo.upsert(kidsId, 120)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          _               <- seedTraffic(routerId, testMac, "minecraft.net", today, 120)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          routes  = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            stlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
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
        } yield assertTrue(extResp.status == Status.Ok) &&
          assertTrue(status.extensionMins == 30) &&
          assertTrue(status.remainingMins.contains(30))
      },
      test("extension is logged with granting admin username") {
        for {
          _               <- cleanDb
          profileRepo     <- ZIO.service[ProfileRepo]
          tlRepo          <- ZIO.service[TimeLimitRepo]
          stlRepo         <- ZIO.service[SiteTimeLimitRepo]
          schedRepo       <- ZIO.service[ScheduleRepo]
          deviceRepo      <- ZIO.service[DeviceRepo]
          trafficRepo     <- ZIO.service[TrafficReportRepo]
          extRepo         <- ZIO.service[TimeExtensionRepo]
          auth            <- makeAuth
          token           <- auth.login("admin", "changeme").map(_.token.value)
          kidsId          <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _               <- tlRepo.upsert(kidsId, 60)
          _               <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            stlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            clock,
          )
          body   = GrantExtensionRequest(kidsId, 15, Some("Good behavior")).toJson
          req    = Request
            .post(URL.decode("/api/time/extend").toOption.get, Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json))
          _    <- routes.runZIO(req)
          exts <- extRepo.listForProfile(kidsId, TestClock.schoolDayAfternoon.toLocalDate)
        } yield assertTrue(exts.length == 1) &&
          assertTrue(exts.head.grantedBy == "admin") &&
          assertTrue(exts.head.extraMinutes == 15) &&
          assertTrue(exts.head.note.contains("Good behavior"))
      },
      test("child user cannot grant extensions") {
        for {
          _               <- cleanDb
          profileRepo     <- ZIO.service[ProfileRepo]
          tlRepo          <- ZIO.service[TimeLimitRepo]
          stlRepo         <- ZIO.service[SiteTimeLimitRepo]
          schedRepo       <- ZIO.service[ScheduleRepo]
          deviceRepo      <- ZIO.service[DeviceRepo]
          trafficRepo     <- ZIO.service[TrafficReportRepo]
          extRepo         <- ZIO.service[TimeExtensionRepo]
          userRepo        <- ZIO.service[UserRepo]
          auth            <- makeAuth
          hash            <- auth.hashPassword("pass")
          _               <- userRepo.create("kidview", hash, "child")
          token           <- auth.login("kidview", "pass").map(_.token.value)
          kidsId          <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _               <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            stlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            clock,
          )
          body   = GrantExtensionRequest(kidsId, 30, None).toJson
          req    = Request
            .post(URL.decode("/api/time/extend").toOption.get, Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
        } yield assertTrue(resp.status == Status.Forbidden)
      },
      test("multiple extensions accumulate at profile level") {
        for {
          _               <- cleanDb
          profileRepo     <- ZIO.service[ProfileRepo]
          tlRepo          <- ZIO.service[TimeLimitRepo]
          stlRepo         <- ZIO.service[SiteTimeLimitRepo]
          schedRepo       <- ZIO.service[ScheduleRepo]
          deviceRepo      <- ZIO.service[DeviceRepo]
          trafficRepo     <- ZIO.service[TrafficReportRepo]
          extRepo         <- ZIO.service[TimeExtensionRepo]
          auth            <- makeAuth
          token           <- auth.login("admin", "changeme").map(_.token.value)
          kidsId          <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _               <- tlRepo.upsert(kidsId, 60)
          _               <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            stlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
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
        } yield assertTrue(exts.length == 3) &&
          assertTrue(total == 60)
      },
    ),

    // ── per-profile status rollup ───────────────────────────────────────────
    suite("GET /api/time/status — per-profile rollup")(
      test("returns one ProfileTimeStatus per profile, not per device") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          stlRepo     <- ZIO.service[SiteTimeLimitRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- tlRepo.upsert(kidsId, 120)
          _           <- TestLayers.seedDevice(deviceRepo, "aa:bb:cc:dd:ee:01", "iPad", kidsId)
          _           <- TestLayers.seedDevice(deviceRepo, "aa:bb:cc:dd:ee:02", "iPhone", kidsId)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            stlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            clock,
          )
          req    = Request
            .get(URL.decode("/api/time/status").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          list <- ZIO.fromEither(body.fromJson[List[ProfileTimeStatus]])
          kids = list.find(_.profileId == kidsId).get
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(list.count(_.profileId == kidsId) == 1) && // one entry, not two
          assertTrue(kids.devices.length == 2) &&               // both devices in breakdown
          assertTrue(kids.devices.map(_.deviceName).toSet == Set("iPad", "iPhone"))
      },
      // #795: per-profile scope so the SPA can fetch one card's worth of data
      // instead of fanning out N sub-rollups. The filter is applied before the
      // per-profile loop runs, so only the requested profile's queries fire.
      test("?profileId=N narrows the response to one profile") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          stlRepo     <- ZIO.service[SiteTimeLimitRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          adultsId    <- profileRepo
            .create("Adults", Nil)
            .flatMap(pid =>
              profileRepo
                .update(
                  Profile(
                    pid,
                    "Adults",
                    Nil,
                    Nil,
                    Nil,
                    paused = false,
                    FailureMode.LastKnownGood,
                    blockIpOnly = false,
                  ),
                )
                .as(pid),
            )
          _           <- tlRepo.upsert(kidsId, 60)
          _           <- TestLayers.seedDevice(deviceRepo, "aa:bb:cc:dd:ee:01", "iPad", kidsId)
          _           <- TestLayers.seedDevice(deviceRepo, "aa:bb:cc:dd:ee:02", "iPhone", adultsId)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            stlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            clock,
          )
          resp <- routes.runZIO(
            Request
              .get(URL.decode(s"/api/time/status?profileId=${kidsId.value}").toOption.get)
              .addHeader(Header.Authorization.Bearer(token)),
          )
          body <- resp.body.asString
          list <- ZIO.fromEither(body.fromJson[List[ProfileTimeStatus]])
          // Bad-input case: 400, not silently-ignored.
          bad  <- routes.runZIO(
            Request
              .get(URL.decode("/api/time/status?profileId=not-a-number").toOption.get)
              .addHeader(Header.Authorization.Bearer(token)),
          )
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(list.length == 1) &&
          assertTrue(list.head.profileId == kidsId) &&
          assertTrue(bad.status == Status.BadRequest)
      },
      test("two devices on same profile share a combined usage pool") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          stlRepo     <- ZIO.service[SiteTimeLimitRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- tlRepo.upsert(kidsId, 60)
          mac1 = "aa:bb:cc:dd:ee:01"
          mac2 = "aa:bb:cc:dd:ee:02"
          _        <- TestLayers.seedDevice(deviceRepo, mac1, "iPad", kidsId)
          _        <- TestLayers.seedDevice(deviceRepo, mac2, "iPhone", kidsId)
          routerId <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          // Device 1: 40 min, Device 2: 35 min → combined 75 min > 60 min limit
          _               <- seedTraffic(routerId, mac1, "minecraft.net", today, 40)
          _               <- seedTraffic(routerId, mac2, "youtube.com", today, 35)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            stlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            clock,
          )
          req    = Request
            .get(URL.decode("/api/time/status").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          list <- ZIO.fromEither(body.fromJson[List[ProfileTimeStatus]])
          kids = list.find(_.profileId == kidsId).get
        } yield assertTrue(kids.usedMins == 75) &&      // both devices summed
          assertTrue(kids.dailyLimitMins.contains(60)) &&
          assertTrue(kids.remainingMins.contains(0)) && // clamped to 0, not negative
          assertTrue(
            kids.devices.find(_.deviceMac == MacAddress.unsafe(mac1)).get.usedMins == 40,
          ) &&
          assertTrue(kids.devices.find(_.deviceMac == MacAddress.unsafe(mac2)).get.usedMins == 35)
      },
      test("per-app site usage aggregated across all profile devices") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          stlRepo     <- ZIO.service[SiteTimeLimitRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- tlRepo.upsert(kidsId, 120)
          _           <- stlRepo.replaceForProfile(
            kidsId,
            List(SiteTimeLimitRequest("youtube.com", 30, "YouTube")),
          )
          mac1 = "aa:bb:cc:dd:ee:01"
          mac2 = "aa:bb:cc:dd:ee:02"
          _        <- TestLayers.seedDevice(deviceRepo, mac1, "iPad", kidsId)
          _        <- TestLayers.seedDevice(deviceRepo, mac2, "iPhone", kidsId)
          routerId <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          // Each device uses 20 min YouTube → combined 40 min > 30 min limit
          _               <- seedTraffic(routerId, mac1, "youtube.com", today, 20)
          _               <- seedTraffic(routerId, mac2, "youtube.com", today, 20)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            stlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
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
        } yield assertTrue(yt.usedMins == 40) && // both devices summed
          assertTrue(yt.limitMins == 30) &&
          assertTrue(yt.remainingMins == 0) &&   // clamped to 0
          assertTrue(kids.usedMins == 0)         // site usage NOT counted in total
      },
      test("hostUsage breakdown sums across all profile devices, sorted desc (#262)") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          stlRepo     <- ZIO.service[SiteTimeLimitRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- tlRepo.upsert(kidsId, 120)
          mac1 = "aa:bb:cc:dd:ee:01"
          mac2 = "aa:bb:cc:dd:ee:02"
          _        <- TestLayers.seedDevice(deviceRepo, mac1, "iPad", kidsId)
          _        <- TestLayers.seedDevice(deviceRepo, mac2, "iPhone", kidsId)
          routerId <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          // mac1: youtube 20m + khan 10m (distinct buckets); mac2: youtube 15m + roblox 5m
          o1 <- seedTraffic(routerId, mac1, "youtube.com", today, 20)
          _  <- seedTraffic(routerId, mac1, "khan-academy.org", today, 10, bucketOffset = o1)
          o2 <- seedTraffic(routerId, mac2, "youtube.com", today, 15)
          _  <- seedTraffic(routerId, mac2, "roblox.com", today, 5, bucketOffset = o2)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            stlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            clock,
          )
          req    = Request
            .get(URL.decode("/api/time/status").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          list <- ZIO.fromEither(body.fromJson[List[ProfileTimeStatus]])
          kids    = list.find(_.profileId == kidsId).get
          hostMap = kids.hostUsage.map(hu => hu.host.value -> hu.usedMins).toMap
        } yield assertTrue(kids.hostUsage.length == 3) &&
          assertTrue(
            hostMap == Map(
              "youtube.com"      -> 35,
              "khan-academy.org" -> 10,
              "roblox.com"       -> 5,
            ),
          ) &&
          assertTrue(kids.hostUsage.map(_.usedMins) == List(35, 10, 5)) // sorted desc
      },
      test("hostUsage capped at top 10, smallest dropped (#262)") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          stlRepo     <- ZIO.service[SiteTimeLimitRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- tlRepo.upsert(kidsId, 240)
          mac = "aa:bb:cc:dd:ee:01"
          _        <- TestLayers.seedDevice(deviceRepo, mac, "iPad", kidsId)
          routerId <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          // Seed 12 distinct hosts; minutes = (12-i)*5 so host0 has the most.
          _               <- ZIO.foreachDiscard(0 until 12) { i =>
            val mins = (12 - i) * 5
            seedTraffic(routerId, mac, s"host$i.example.com", today, mins, bucketOffset = i * 20)
          }
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            stlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            clock,
          )
          req    = Request
            .get(URL.decode("/api/time/status").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          list <- ZIO.fromEither(body.fromJson[List[ProfileTimeStatus]])
          kids  = list.find(_.profileId == kidsId).get
          hosts = kids.hostUsage.map(_.host.value).toSet
        } yield assertTrue(kids.hostUsage.length == 10) &&
          // The two smallest (host10=10m, host11=5m) dropped; host0..host9 retained.
          assertTrue(!hosts.contains("host10.example.com")) &&
          assertTrue(!hosts.contains("host11.example.com")) &&
          assertTrue(hosts.contains("host0.example.com")) &&
          assertTrue(hosts.contains("host9.example.com"))
      },
      test("policy snapshot has profile-level time_used_today aggregated across devices") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          stlRepo     <- ZIO.service[SiteTimeLimitRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- tlRepo.upsert(kidsId, 60)
          mac1 = "aa:bb:cc:dd:ee:01"
          mac2 = "aa:bb:cc:dd:ee:02"
          _        <- TestLayers.seedDevice(deviceRepo, mac1, "iPad", kidsId)
          _        <- TestLayers.seedDevice(deviceRepo, mac2, "iPhone", kidsId)
          routerId <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          _             <- seedTraffic(routerId, mac1, "minecraft.net", today, 30)
          _             <- seedTraffic(routerId, mac2, "roblox.com", today, 25)
          // Build policy snapshot directly via PolicyService
          blocklistRepo <- ZIO.service[BlocklistRepo]
          hsRepo        <- ZIO.service[HouseholdSettingsRepo]
          clock         <- ZIO.service[Clock]
          policyService = PolicyServiceLive(
            profileRepo,
            schedRepo,
            hsRepo,
            tlRepo,
            stlRepo,
            deviceRepo,
            blocklistRepo,
            trafficRepo,
            extRepo,
            clock,
          )
          snapshot <- policyService.snapshot
          kidsPolicy = snapshot.profiles(kidsId)
          // #354: snapshot no longer carries dailyMinutes / timeUsedToday.
          // 55 minutes of presence against a 60-minute cap leaves the
          // profile unblocked; we just assert the cap hasn't been hit.
        } yield assertTrue(!kidsPolicy.rules.blocked) &&
          assertTrue(kidsPolicy.rules.blockReason.isEmpty)
      },
      test("GET /api/time/heartbeat-explain returns per-row classification matching live config") {
        // #714: explain endpoint surfaces every traffic_reports row that feeds Presence with the
        // current heartbeat-filter verdict, so the operator can tune thresholds against real data.
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          stlRepo     <- ZIO.service[SiteTimeLimitRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          hsRepoSvc   <- ZIO.service[HouseholdSettingsRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          today  = TestClock.schoolDayAfternoon.toLocalDate
          today0 = today.atStartOfDay(ZoneOffset.UTC).toInstant
          // Two rows on the same device today:
          //   - apns.apple.com: 60-byte heartbeat, 5s active within 60s
          //   - youtube.com:    500_000 bytes, 60s active within 60s
          _               <- trafficRepo.insertBatch(
            List(
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(testMac),
                None,
                HostId.Fqdn(Hostname.unsafe("apns.apple.com")),
                today,
                today0,
                today0.plusSeconds(60),
                5,
                30L,
                30L,
              ),
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(testMac),
                None,
                HostId.Fqdn(Hostname.unsafe("youtube.com")),
                today,
                today0.plusSeconds(120),
                today0.plusSeconds(180),
                60,
                250_000L,
                250_000L,
              ),
            ),
          )
          // Flip the filter on with the production defaults so the explain output reflects them.
          _               <- hsRepoSvc.update(
            HouseholdSettings(
              java.time.LocalTime.of(0, 0),
              java.time.ZoneId.of("UTC"),
              HeartbeatFilter(enabled = true, bytesThreshold = 2048),
            ),
          )
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            stlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            clock,
          )
          req    = Request
            .get(URL.decode(s"/api/time/heartbeat-explain/$testMac").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          out  <- ZIO.fromEither(body.fromJson[HeartbeatExplainResponse])
          apns = out.rows.find(_.host.value == "apns.apple.com").get
          yt   = out.rows.find(_.host.value == "youtube.com").get
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(out.filter.enabled) &&
          assertTrue(out.rows.length == 2) &&
          assertTrue(apns.classified == "heartbeat") &&
          assertTrue(apns.reasons.exists(_.startsWith("bytes<"))) &&
          assertTrue(yt.classified == "active") &&
          assertTrue(yt.reasons.isEmpty)
      },
    ) @@ TestAspect.sequential,

    // ── #723 weekly view ────────────────────────────────────────────────────
    suite("GET /api/time/status/week")(
      test("per-day totals match a Today view computed for each date") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          stlRepo     <- ZIO.service[SiteTimeLimitRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- tlRepo.upsert(kidsId, 120)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          // Seed distinct minute counts on three days within the week; the other
          // four days are intentionally empty (must surface as 0m bars).
          _               <- seedTraffic(routerId, testMac, "minecraft.net", today.minusDays(6), 20)
          _               <- seedTraffic(routerId, testMac, "youtube.com", today.minusDays(3), 35)
          _               <- seedTraffic(routerId, testMac, "khan-academy.org", today, 15)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            stlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            clock,
          )
          req    = Request
            .get(URL.decode("/api/time/status/week").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          list <- ZIO.fromEither(body.fromJson[List[ProfileTimeStatusWeek]])
          kids  = list.find(_.profileId == kidsId).get
          byDay = kids.perDay.map(d => d.date -> d.usedMins).toMap
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(kids.from == today.minusDays(6).toString) &&
          assertTrue(kids.to == today.toString) &&
          assertTrue(kids.perDay.length == 7) &&                 // all 7 days present
          assertTrue(byDay(today.minusDays(6).toString) == 20) &&
          assertTrue(byDay(today.minusDays(3).toString) == 35) &&
          assertTrue(byDay(today.toString) == 15) &&
          assertTrue(byDay(today.minusDays(5).toString) == 0) && // empty days are 0
          assertTrue(kids.totalMins == 70) &&                    // 20+35+15
          assertTrue(kids.devices.head.usedMins == 70) &&
          assertTrue(kids.dailyLimitMins.contains(120))
      },
      // #795: same scoping for the week endpoint.
      test("?profileId=N narrows the weekly response to one profile") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          stlRepo     <- ZIO.service[SiteTimeLimitRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          adultsId    <- profileRepo
            .create("Adults", Nil)
            .flatMap(pid =>
              profileRepo
                .update(
                  Profile(
                    pid,
                    "Adults",
                    Nil,
                    Nil,
                    Nil,
                    paused = false,
                    FailureMode.LastKnownGood,
                    blockIpOnly = false,
                  ),
                )
                .as(pid),
            )
          _           <- TestLayers.seedDevice(deviceRepo, "aa:bb:cc:dd:ee:01", "iPad", kidsId)
          _           <- TestLayers.seedDevice(deviceRepo, "aa:bb:cc:dd:ee:02", "iPhone", adultsId)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            stlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            clock,
          )
          resp <- routes.runZIO(
            Request
              .get(URL.decode(s"/api/time/status/week?profileId=${kidsId.value}").toOption.get)
              .addHeader(Header.Authorization.Bearer(token)),
          )
          body <- resp.body.asString
          list <- ZIO.fromEither(body.fromJson[List[ProfileTimeStatusWeek]])
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(list.length == 1) &&
          assertTrue(list.head.profileId == kidsId)
      },
      test("per-host weekly totals are bucket-deduped across the range") {
        // Two devices on the same profile both hit youtube.com on multiple days. The host
        // total must be the sum of bucket-deduped per-day contributions (presence semantics
        // applied per-mac at the bucket level, summed across days), not a naive sum of
        // per-day per-device minutes that would double-count anything.
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          stlRepo     <- ZIO.service[SiteTimeLimitRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- tlRepo.upsert(kidsId, 120)
          mac1 = "aa:bb:cc:dd:ee:01"
          mac2 = "aa:bb:cc:dd:ee:02"
          _        <- TestLayers.seedDevice(deviceRepo, mac1, "iPad", kidsId)
          _        <- TestLayers.seedDevice(deviceRepo, mac2, "iPhone", kidsId)
          routerId <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          // mac1: youtube 20m today + 15m yesterday → 35m total on youtube
          _               <- seedTraffic(routerId, mac1, "youtube.com", today, 20)
          _               <- seedTraffic(routerId, mac1, "youtube.com", today.minusDays(1), 15)
          // mac2: youtube 10m today + 5m two days ago → 15m total on youtube
          _               <- seedTraffic(routerId, mac2, "youtube.com", today, 10)
          _               <- seedTraffic(routerId, mac2, "youtube.com", today.minusDays(2), 5)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            stlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            clock,
          )
          req    = Request
            .get(URL.decode("/api/time/status/week").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          list <- ZIO.fromEither(body.fromJson[List[ProfileTimeStatusWeek]])
          kids = list.find(_.profileId == kidsId).get
          yt   = kids.hostUsage.find(_.host.value == "youtube.com").get
        } yield assertTrue(yt.usedMins == 50) && // 35 + 15 across both macs
          assertTrue(kids.totalMins == 50) &&
          assertTrue(
            kids.devices.find(_.deviceMac == MacAddress.unsafe(mac1)).get.usedMins == 35,
          ) &&
          assertTrue(
            kids.devices.find(_.deviceMac == MacAddress.unsafe(mac2)).get.usedMins == 15,
          )
      },
      test("?to= anchors a trailing 7-day window") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          stlRepo     <- ZIO.service[SiteTimeLimitRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          today  = TestClock.schoolDayAfternoon.toLocalDate
          anchor = today.minusDays(10) // outside the default trailing-week window
          _               <- seedTraffic(routerId, testMac, "minecraft.net", anchor, 25)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            stlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            clock,
          )
          // Default window (anchor=today): outside-range traffic must NOT appear.
          dflt     <- routes.runZIO(
            Request
              .get(URL.decode("/api/time/status/week").toOption.get)
              .addHeader(Header.Authorization.Bearer(token)),
          )
          dfltBody <- dflt.body.asString
          dfltList <- ZIO.fromEither(dfltBody.fromJson[List[ProfileTimeStatusWeek]])
          dfltKids = dfltList.find(_.profileId == kidsId).get
          // Anchored window covering the seeded day.
          shifted     <- routes.runZIO(
            Request
              .get(URL.decode(s"/api/time/status/week?to=${anchor.toString}").toOption.get)
              .addHeader(Header.Authorization.Bearer(token)),
          )
          shiftedBody <- shifted.body.asString
          shiftedList <- ZIO.fromEither(shiftedBody.fromJson[List[ProfileTimeStatusWeek]])
          shiftedKids = shiftedList.find(_.profileId == kidsId).get
        } yield assertTrue(dfltKids.totalMins == 0) &&
          assertTrue(shiftedKids.totalMins == 25) &&
          assertTrue(shiftedKids.to == anchor.toString) &&
          assertTrue(shiftedKids.from == anchor.minusDays(6).toString)
      },
    ) @@ TestAspect.sequential,

    // ── per-device weekly variant ───────────────────────────────────────────
    suite("GET /api/time/status/{mac}/week")(
      test("per-day totals scoped to one device, per-host across the range") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          stlRepo     <- ZIO.service[SiteTimeLimitRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- tlRepo.upsert(kidsId, 120)
          mac1 = testMac
          mac2 = "aa:bb:cc:dd:ee:02"
          _        <- TestLayers.seedDevice(deviceRepo, mac1, "iPad", kidsId)
          _        <- TestLayers.seedDevice(deviceRepo, mac2, "iPhone", kidsId)
          routerId <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          // mac1: 20m today + 15m two days ago. mac2: noise that must NOT
          // leak into mac1's per-device weekly.
          _               <- seedTraffic(routerId, mac1, "minecraft.net", today, 20)
          _               <- seedTraffic(routerId, mac1, "youtube.com", today.minusDays(2), 15)
          _               <- seedTraffic(routerId, mac2, "youtube.com", today, 30)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            stlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            clock,
          )
          req    = Request
            .get(URL.decode(s"/api/time/status/$mac1/week").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp   <- routes.runZIO(req)
          body   <- resp.body.asString
          status <- ZIO.fromEither(body.fromJson[DeviceTimeStatusWeek])
          byDay = status.perDay.map(d => d.date -> d.usedMins).toMap
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(status.deviceMac == MacAddress.unsafe(mac1)) &&
          assertTrue(status.perDay.length == 7) &&
          assertTrue(status.totalMins == 35) && // only mac1
          assertTrue(byDay(today.toString) == 20) &&
          assertTrue(byDay(today.minusDays(2).toString) == 15) &&
          assertTrue(byDay(today.minusDays(1).toString) == 0) &&
          assertTrue(
            status.hostUsage.map(_.host.value).toSet == Set("minecraft.net", "youtube.com"),
          ) &&
          assertTrue(status.dailyLimitMins.contains(120)) &&
          assertTrue(status.profileName == "Kids")
      },
      test("404 when device not found") {
        for {
          _               <- cleanDb
          profileRepo     <- ZIO.service[ProfileRepo]
          tlRepo          <- ZIO.service[TimeLimitRepo]
          stlRepo         <- ZIO.service[SiteTimeLimitRepo]
          deviceRepo      <- ZIO.service[DeviceRepo]
          trafficRepo     <- ZIO.service[TrafficReportRepo]
          extRepo         <- ZIO.service[TimeExtensionRepo]
          auth            <- makeAuth
          token           <- auth.login("admin", "changeme").map(_.token.value)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            stlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            clock,
          )
          req    = Request
            .get(URL.decode("/api/time/status/aa:bb:cc:dd:ee:ff/week").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
        } yield assertTrue(resp.status == Status.NotFound)
      },
    ) @@ TestAspect.sequential,
  ) @@ TestAspect.sequential
}
