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

  private val jwtCfg   = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)
  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)
  private val cleanDb  = TestDatabase.cleanAndMigrate

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
          atlRepo         <- ZIO.service[AppTimeLimitRepo]
          deviceRepo      <- ZIO.service[DeviceRepo]
          trafficRepo     <- ZIO.service[TrafficReportRepo]
          extRepo         <- ZIO.service[TimeExtensionRepo]
          auth            <- makeAuth
          token           <- auth.login("admin", "changeme").map(_.token.value)
          kidsId          <- TestLayers.seedKidsProfile(profileRepo)
          _               <- tlRepo.upsert(kidsId, 120)
          _               <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
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
          atlRepo     <- ZIO.service[AppTimeLimitRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo)
          _           <- tlRepo.upsert(kidsId, 120)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          off1            <- seedTraffic(routerId, testMac, "minecraft.net", today, 45)
          _               <- seedTraffic(routerId, testMac, "google.com", today, 30, off1)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
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
          atlRepo     <- ZIO.service[AppTimeLimitRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo)
          _           <- tlRepo.upsert(kidsId, 120)
          appRepo     <- ZIO.service[AppRepo]
          _           <- TestLayers.seedAppAssignment(
            appRepo,
            kidsId,
            "*.youtube.com",
            AppMode.TimeLimited,
            dailyMinutes = Some(30),
            exemptFromDaily = true,
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
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
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
            status.appUsage.exists(su =>
              su.label == "app:*.youtube.com" && su.usedMins == 20 && su.remainingMins.contains(10),
            ),
          )
      },
      test("included site (exemptFromDaily=false): usage IS counted in daily total") {
        for
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          atlRepo     <- ZIO.service[AppTimeLimitRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo)
          _           <- tlRepo.upsert(kidsId, 120)
          appRepo     <- ZIO.service[AppRepo]
          _           <- TestLayers.seedAppAssignment(
            appRepo,
            kidsId,
            "*.youtube.com",
            AppMode.TimeLimited,
            dailyMinutes = Some(60),
            exemptFromDaily = false,
          )
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          // 60 min of YouTube usage; since exemptFromDaily=false it must appear in usedMins
          _               <- seedTraffic(routerId, testMac, "youtube.com", today, 60)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
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
            status.appUsage.exists(su =>
              su.label == "app:*.youtube.com" && su.usedMins == 60 && su.remainingMins.contains(0),
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
          atlRepo     <- ZIO.service[AppTimeLimitRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo)
          _           <- tlRepo.upsert(kidsId, 120)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          _               <- seedTraffic(routerId, testMac, "minecraft.net", today, 120)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          tss     = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes  = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
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
          atlRepo         <- ZIO.service[AppTimeLimitRepo]
          deviceRepo      <- ZIO.service[DeviceRepo]
          trafficRepo     <- ZIO.service[TrafficReportRepo]
          extRepo         <- ZIO.service[TimeExtensionRepo]
          auth            <- makeAuth
          token           <- auth.login("admin", "changeme").map(_.token.value)
          kidsId          <- TestLayers.seedKidsProfile(profileRepo)
          _               <- tlRepo.upsert(kidsId, 60)
          _               <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
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
          atlRepo         <- ZIO.service[AppTimeLimitRepo]
          deviceRepo      <- ZIO.service[DeviceRepo]
          trafficRepo     <- ZIO.service[TrafficReportRepo]
          extRepo         <- ZIO.service[TimeExtensionRepo]
          userRepo        <- ZIO.service[UserRepo]
          auth            <- makeAuth
          hash            <- auth.hashPassword("pass")
          _               <- userRepo.create("kidview", hash, "child")
          token           <- auth.login("kidview", "pass").map(_.token.value)
          kidsId          <- TestLayers.seedKidsProfile(profileRepo)
          _               <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
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
          atlRepo         <- ZIO.service[AppTimeLimitRepo]
          deviceRepo      <- ZIO.service[DeviceRepo]
          trafficRepo     <- ZIO.service[TrafficReportRepo]
          extRepo         <- ZIO.service[TimeExtensionRepo]
          auth            <- makeAuth
          token           <- auth.login("admin", "changeme").map(_.token.value)
          kidsId          <- TestLayers.seedKidsProfile(profileRepo)
          _               <- tlRepo.upsert(kidsId, 60)
          _               <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
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
          atlRepo     <- ZIO.service[AppTimeLimitRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo)
          _           <- tlRepo.upsert(kidsId, 120)
          _           <- TestLayers.seedDevice(deviceRepo, "aa:bb:cc:dd:ee:01", "iPad", kidsId)
          _           <- TestLayers.seedDevice(deviceRepo, "aa:bb:cc:dd:ee:02", "iPhone", kidsId)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
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
      // #1491 / #924 / #1465: per-app `proportionalMins` on `hostUsage` follows the
      // session-span model (#1488) — the length of a host's stitched
      // [period_start, period_end] sessions — NOT the old #715 byte-share weighting.
      // A host present across several contiguous windows stitches into one long
      // session and dominates a host present in only one window, deterministically
      // and independent of bytes. This pins the e2e #924 step
      // (scripts/e2e-router.sh) at the API level so it can't silently regress to a
      // byte-ratio expectation: heavy924 is active across three contiguous 5-min
      // windows (→ one ~15-min session) while light924 is active only in the last
      // (→ one ~5-min session). (If #1466's connection-event-anchored span edges
      // shift the exact magnitudes, update the 15/5 here in lockstep with the e2e
      // tolerance — the heavy ≥ 2× light ordering is the stable invariant.)
      test(
        "#924/#1465: per-app proportionalMins follows session span — heavy across 3 windows dominates light in 1",
      ) {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          atlRepo     <- ZIO.service[AppTimeLimitRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo)
          _           <- tlRepo.upsert(kidsId, 120)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          // heavy: buckets 0,1,2 → three contiguous windows that stitch into one
          // [0, 900s) session. light: bucket 2 only → a single [600s, 900s) window.
          _               <- seedTraffic(routerId, testMac, "heavy924.example.com", today, 15)
          _               <- seedTraffic(routerId, testMac, "light924.example.com", today, 5, 2)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
            clock,
          )
          resp <- routes.runZIO(
            Request
              .get(URL.decode(s"/api/time/status?profileId=${kidsId.value}").toOption.get)
              .addHeader(Header.Authorization.Bearer(token)),
          )
          body <- resp.body.asString
          list <- ZIO.fromEither(body.fromJson[List[ProfileTimeStatus]])
          kids   = list.find(_.profileId == kidsId).get
          byHost = kids.hostUsage.map(h => h.host.value -> h).toMap
          heavy  = byHost("heavy924.example.com")
          light  = byHost("light924.example.com")
        } yield assertTrue(resp.status == Status.Ok) &&
          // session-span: heavy's stitched 15-min session, light's single 5-min window.
          assertTrue(heavy.proportionalMins == 15) &&
          assertTrue(light.proportionalMins == 5) &&
          // the exact invariant the e2e #924 step asserts (heavy ≥ 2× light, both > 0).
          assertTrue(
            light.proportionalMins > 0 && heavy.proportionalMins >= 2 * light.proportionalMins,
          )
      },
      // #795: per-profile scope so the SPA can fetch one card's worth of data
      // instead of fanning out N sub-rollups. The filter is applied before the
      // per-profile loop runs, so only the requested profile's queries fire.
      test("?profileId=N narrows the response to one profile") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          atlRepo     <- ZIO.service[AppTimeLimitRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo)
          adultsId    <- profileRepo
            .create("Adults", Nil)
            .flatMap(pid =>
              profileRepo
                .update(
                  Profile(
                    pid,
                    "Adults",
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
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
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
          atlRepo     <- ZIO.service[AppTimeLimitRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo)
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
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
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
      // #751: Sum vs Dedup — two devices active in the same 5-min bucket.
      // Sum mode adds per-device totals (5+5=10); Dedup unions per-device
      // active buckets so overlap counts once (5).
      test("crossDeviceOverlapMode=sum: overlapping buckets across devices count twice") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          atlRepo     <- ZIO.service[AppTimeLimitRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo)
          _           <- tlRepo.upsert(kidsId, 60)
          mac1 = "aa:bb:cc:dd:ee:01"
          mac2 = "aa:bb:cc:dd:ee:02"
          _        <- TestLayers.seedDevice(deviceRepo, mac1, "iPad", kidsId)
          _        <- TestLayers.seedDevice(deviceRepo, mac2, "iPhone", kidsId)
          routerId <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          // Both devices in the SAME 5-min bucket (bucketOffset=0 on each).
          _               <- seedTraffic(routerId, mac1, "minecraft.net", today, 5, 0)
          _               <- seedTraffic(routerId, mac2, "youtube.com", today, 5, 0)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
            clock,
          )
          resp <- routes.runZIO(
            Request
              .get(URL.decode("/api/time/status").toOption.get)
              .addHeader(Header.Authorization.Bearer(token)),
          )
          body <- resp.body.asString
          list <- ZIO.fromEither(body.fromJson[List[ProfileTimeStatus]])
          kids = list.find(_.profileId == kidsId).get
        } yield assertTrue(kids.usedMins == 10) // sum mode (default): 5+5
      },
      test("crossDeviceOverlapMode=dedup: overlapping buckets count once") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          atlRepo     <- ZIO.service[AppTimeLimitRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo)
          // Flip the profile to Dedup mode.
          _           <- profileRepo.findById(kidsId).flatMap { opt =>
            ZIO.foreachDiscard(opt)(p =>
              profileRepo.update(p.copy(crossDeviceOverlapMode = CrossDeviceOverlapMode.Dedup)),
            )
          }
          _           <- tlRepo.upsert(kidsId, 60)
          mac1 = "aa:bb:cc:dd:ee:01"
          mac2 = "aa:bb:cc:dd:ee:02"
          _        <- TestLayers.seedDevice(deviceRepo, mac1, "iPad", kidsId)
          _        <- TestLayers.seedDevice(deviceRepo, mac2, "iPhone", kidsId)
          routerId <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          // Same bucket on both devices.
          _               <- seedTraffic(routerId, mac1, "minecraft.net", today, 5, 0)
          _               <- seedTraffic(routerId, mac2, "youtube.com", today, 5, 0)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
            clock,
          )
          resp <- routes.runZIO(
            Request
              .get(URL.decode("/api/time/status").toOption.get)
              .addHeader(Header.Authorization.Bearer(token)),
          )
          body <- resp.body.asString
          list <- ZIO.fromEither(body.fromJson[List[ProfileTimeStatus]])
          kids = list.find(_.profileId == kidsId).get
        } yield assertTrue(kids.usedMins == 5)
      },
      test("crossDeviceOverlapMode=dedup: non-overlapping buckets sum the same as sum mode") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          atlRepo     <- ZIO.service[AppTimeLimitRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo)
          _           <- profileRepo.findById(kidsId).flatMap { opt =>
            ZIO.foreachDiscard(opt)(p =>
              profileRepo.update(p.copy(crossDeviceOverlapMode = CrossDeviceOverlapMode.Dedup)),
            )
          }
          _           <- tlRepo.upsert(kidsId, 60)
          mac1 = "aa:bb:cc:dd:ee:01"
          mac2 = "aa:bb:cc:dd:ee:02"
          _        <- TestLayers.seedDevice(deviceRepo, mac1, "iPad", kidsId)
          _        <- TestLayers.seedDevice(deviceRepo, mac2, "iPhone", kidsId)
          routerId <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          // Different bucket ranges → no overlap.
          off1            <- seedTraffic(routerId, mac1, "minecraft.net", today, 10, 0)
          _               <- seedTraffic(routerId, mac2, "youtube.com", today, 10, off1)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
            clock,
          )
          resp <- routes.runZIO(
            Request
              .get(URL.decode("/api/time/status").toOption.get)
              .addHeader(Header.Authorization.Bearer(token)),
          )
          body <- resp.body.asString
          list <- ZIO.fromEither(body.fromJson[List[ProfileTimeStatus]])
          kids = list.find(_.profileId == kidsId).get
        } yield assertTrue(kids.usedMins == 20) // 10+10 with no overlap
      },
      test("per-app site usage aggregated across all profile devices") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          atlRepo     <- ZIO.service[AppTimeLimitRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo)
          _           <- tlRepo.upsert(kidsId, 120)
          appRepo     <- ZIO.service[AppRepo]
          _           <- TestLayers.seedAppAssignment(
            appRepo,
            kidsId,
            "youtube.com",
            AppMode.TimeLimited,
            dailyMinutes = Some(30),
            exemptFromDaily = true,
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
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
            clock,
          )
          req    = Request
            .get(URL.decode("/api/time/status").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          list <- ZIO.fromEither(body.fromJson[List[ProfileTimeStatus]])
          kids = list.find(_.profileId == kidsId).get
          yt   = kids.appUsage.find(_.label == "app:youtube.com").get
        } yield assertTrue(yt.usedMins == 40) &&      // both devices summed
          assertTrue(yt.limitMins.contains(30)) &&
          assertTrue(yt.remainingMins.contains(0)) && // clamped to 0
          assertTrue(kids.usedMins == 0)              // site usage NOT counted in total
      },
      // #1546: per-device `deviceSummaries` must share ONE exempt/overlap definition with the
      // canonical headline `usedMinutes` (via TimeStatusService.usedSecondsByMac), so the summed
      // per-device minutes reconcile with the headline instead of being independently recomputed.
      test("#1546 Sum mode: summed deviceSummaries minutes equal the headline usedMinutes") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          atlRepo     <- ZIO.service[AppTimeLimitRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo) // default overlap = Sum
          _           <- tlRepo.upsert(kidsId, 120)
          mac1 = "aa:bb:cc:dd:ee:01"
          mac2 = "aa:bb:cc:dd:ee:02"
          _        <- TestLayers.seedDevice(deviceRepo, mac1, "iPad", kidsId)
          _        <- TestLayers.seedDevice(deviceRepo, mac2, "iPhone", kidsId)
          routerId <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          // Whole-minute amounts so per-mac floor-to-minute and headline floor agree exactly.
          off1            <- seedTraffic(routerId, mac1, "minecraft.net", today, 20, 0)
          _               <- seedTraffic(routerId, mac2, "youtube.com", today, 30, off1)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
            clock,
          )
          resp <- routes.runZIO(
            Request
              .get(URL.decode("/api/time/status").toOption.get)
              .addHeader(Header.Authorization.Bearer(token)),
          )
          body <- resp.body.asString
          list <- ZIO.fromEither(body.fromJson[List[ProfileTimeStatus]])
          kids = list.find(_.profileId == kidsId).get
        } yield assertTrue(kids.usedMins == 50) &&
          assertTrue(kids.devices.map(_.usedMins).sum == kids.usedMins)
      },
      // #1546 regression: the prod divergence. Under Dedup the headline `usedMinutes` is a
      // cross-device UNION, but the route used to SUM each device's own engaged minutes for
      // `deviceSummaries` — so two devices overlapping in the same window summed to 2× the headline
      // (>100% display). usedSecondsByMac credits each device only its disjoint marginal, so the
      // summaries reconcile with the union and no single device exceeds it.
      test("#1546 Dedup mode: summed deviceSummaries never exceed the headline usedMinutes") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          atlRepo     <- ZIO.service[AppTimeLimitRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo)
          _           <- profileRepo.findById(kidsId).flatMap { opt =>
            ZIO.foreachDiscard(opt)(p =>
              profileRepo.update(p.copy(crossDeviceOverlapMode = CrossDeviceOverlapMode.Dedup)),
            )
          }
          _           <- tlRepo.upsert(kidsId, 120)
          mac1 = "aa:bb:cc:dd:ee:01"
          mac2 = "aa:bb:cc:dd:ee:02"
          _        <- TestLayers.seedDevice(deviceRepo, mac1, "iPad", kidsId)
          _        <- TestLayers.seedDevice(deviceRepo, mac2, "iPhone", kidsId)
          routerId <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          // Both devices active across the SAME 30 minutes → union headline is 30, but each
          // device's own engaged time is 30. Pre-fix the summaries summed to 60.
          _               <- seedTraffic(routerId, mac1, "cnn.com", today, 30, 0)
          _               <- seedTraffic(routerId, mac2, "cnn.com", today, 30, 0)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
            clock,
          )
          resp <- routes.runZIO(
            Request
              .get(URL.decode("/api/time/status").toOption.get)
              .addHeader(Header.Authorization.Bearer(token)),
          )
          body <- resp.body.asString
          list <- ZIO.fromEither(body.fromJson[List[ProfileTimeStatus]])
          kids = list.find(_.profileId == kidsId).get
        } yield assertTrue(kids.usedMins == 30) &&                     // union headline
          assertTrue(
            kids.devices.map(_.usedMins).sum == kids.usedMins,
          ) &&                                                         // disjoint, sums to union
          assertTrue(kids.devices.forall(_.usedMins <= kids.usedMins)) // never >100%
      },
      // #1546 / #1531 at per-device granularity: a device's `usedMins` must exclude exempt-from-daily
      // app time the SAME way the headline does — both derive exempt patterns from `usedSecondsByMac`.
      test(
        "#1546 exempt-app: per-device usedMins excludes exempt time identically to the headline",
      ) {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          atlRepo     <- ZIO.service[AppTimeLimitRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          appRepo     <- ZIO.service[AppRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo)
          _           <- tlRepo.upsert(kidsId, 120)
          _           <- TestLayers.seedAppAssignment(
            appRepo,
            kidsId,
            "khan.org",
            AppMode.TimeLimited,
            dailyMinutes = Some(60),
            exemptFromDaily = true,
          )
          mac = "aa:bb:cc:dd:ee:01"
          _        <- TestLayers.seedDevice(deviceRepo, mac, "iPad", kidsId)
          routerId <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          // 25 min exempt khan.org + 10 min counted cnn.com on the one device.
          off1            <- seedTraffic(routerId, mac, "khan.org", today, 25, 0)
          _               <- seedTraffic(routerId, mac, "cnn.com", today, 10, off1)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
            clock,
          )
          resp <- routes.runZIO(
            Request
              .get(URL.decode("/api/time/status").toOption.get)
              .addHeader(Header.Authorization.Bearer(token)),
          )
          body <- resp.body.asString
          list <- ZIO.fromEither(body.fromJson[List[ProfileTimeStatus]])
          kids = list.find(_.profileId == kidsId).get
          // The per-device endpoint headline excludes exempt time the same way.
          devResp <- routes.runZIO(
            Request
              .get(URL.decode(s"/api/time/status/$mac").toOption.get)
              .addHeader(Header.Authorization.Bearer(token)),
          )
          devBody <- devResp.body.asString
          dev     <- ZIO.fromEither(devBody.fromJson[DeviceTimeStatus])
        } yield assertTrue(kids.usedMins == 10) && // exempt 25 min excluded
          assertTrue(
            kids.devices.map(_.usedMins).sum == 10,
          ) &&                                     // per-device summary excludes it too
          assertTrue(dev.usedMins == 10)           // device endpoint headline agrees
      },
      test("hostUsage: heartbeat filter strips keepalive pollers from per-host surfaces (#1465)") {
        // Reproduce the prod shape from #715: device sat at ~60 used minutes
        // but a per-FQDN bucket-presence breakdown lists 10 polling hosts at
        // 50–80m each because each was touched in every 5-min bucket. The
        // per-host/per-site surfaces now apply the same heartbeat filter the
        // daily total uses (#1465), so the sub-threshold pollers drop out
        // entirely and `proportionalMins` becomes the host's session span (the
        // default household settings enable the filter at 10 240 bytes).
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          atlRepo     <- ZIO.service[AppTimeLimitRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo)
          _           <- tlRepo.upsert(kidsId, 240)
          mac = "aa:bb:cc:dd:ee:01"
          _        <- TestLayers.seedDevice(deviceRepo, mac, "iPad", kidsId)
          routerId <- seedRouter
          today   = TestClock.schoolDayAfternoon.toLocalDate
          today0  = today.atStartOfDay(ZoneOffset.UTC).toInstant
          heavy   = "youtube.com"
          pollers = (0 until 10).map(i => s"poll-$i.example.com").toList
          // 12 buckets × 5 min = 60 wall-clock mins. In every bucket: youtube
          // moves 5MB; each of 10 pollers moves 200 bytes.
          _               <- ZIO.foreachDiscard(0 until 12) { b =>
            val start    = today0.plusSeconds(b * 300L)
            val end      = start.plusSeconds(300)
            val heavyRow = TrafficReportInsert(
              routerId,
              MacAddress.unsafe(mac),
              None,
              HostId.Fqdn(Hostname.unsafe(heavy)),
              today,
              start,
              end,
              300,
              2_500_000L,
              2_500_000L,
            )
            val pollRows = pollers.map(p =>
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(mac),
                None,
                HostId.Fqdn(Hostname.unsafe(p)),
                today,
                start,
                end,
                300,
                100L,
                100L,
              ),
            )
            trafficRepo.insertBatch(heavyRow :: pollRows).unit
          }
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
            clock,
          )
          req    = Request
            .get(URL.decode("/api/time/status").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          list <- ZIO.fromEither(body.fromJson[List[ProfileTimeStatus]])
          kids     = list.find(_.profileId == kidsId).get
          ytRow    = kids.hostUsage.find(_.host.value == heavy).get
          pollRows = kids.hostUsage.filter(_.host.value.startsWith("poll-"))
        } yield
        // Daily cap math unchanged: 12 bucket-deduped × 5 min = 60 mins (the
        // mixed bucket still counts because youtube moves real bytes).
        assertTrue(kids.usedMins == 60) &&
          // Bucket-presence shows youtube at the full 60 minutes...
          assertTrue(ytRow.usedMins == 60) &&
          // ...and `proportionalMins` is now its session span: 12 contiguous
          // 5-min windows stitch into one 60-min session.
          assertTrue(ytRow.proportionalMins == 60) &&
          // The sub-threshold pollers are filtered out of the per-host surface
          // entirely — that is the #1465 fix (formerly they showed at 60 mins).
          assertTrue(pollRows.isEmpty) &&
          assertTrue(kids.hostUsage.head.host.value == heavy)
      },
      test("hostUsage breakdown sums across all profile devices, sorted desc (#262)") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          atlRepo     <- ZIO.service[AppTimeLimitRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo)
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
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
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
          atlRepo     <- ZIO.service[AppTimeLimitRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo)
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
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
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
          atlRepo     <- ZIO.service[AppTimeLimitRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo)
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
          appRepo       <- ZIO.service[AppRepo]
          clock         <- ZIO.service[Clock]
          policyService = PolicyServiceLive(
            profileRepo,
            hsRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            blocklistRepo,
            trafficRepo,
            extRepo,
            appRepo,
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
          atlRepo     <- ZIO.service[AppTimeLimitRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          hsRepoSvc   <- ZIO.service[HouseholdSettingsRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo)
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
            HouseholdId.Default,
            HouseholdSettings(
              java.time.LocalTime.of(0, 0),
              java.time.ZoneId.of("UTC"),
              HeartbeatFilter(enabled = true, bytesThreshold = 2048),
            ),
          )
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
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

    // ── #777 collapsed accordion summary endpoint ───────────────────────────
    suite("GET /api/time/status/summary")(
      test("returns headline totals per profile across all visible profiles") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          atlRepo     <- ZIO.service[AppTimeLimitRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo)
          adultsId    <- profileRepo
            .create("Adults", Nil)
            .flatMap(pid =>
              profileRepo
                .update(
                  Profile(
                    pid,
                    "Adults",
                    Nil,
                    paused = false,
                    FailureMode.LastKnownGood,
                    blockIpOnly = false,
                  ),
                )
                .as(pid),
            )
          _           <- tlRepo.upsert(kidsId, 60)
          // Adults has no daily limit.
          mac1 = "aa:bb:cc:dd:ee:01"
          mac2 = "aa:bb:cc:dd:ee:02"
          _        <- TestLayers.seedDevice(deviceRepo, mac1, "iPad", kidsId)
          _        <- TestLayers.seedDevice(deviceRepo, mac2, "Laptop", adultsId)
          routerId <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          // 45 min for kids, 20 min for adults. The summary should sum these
          // independently per profile from a single presence query.
          _               <- seedTraffic(routerId, mac1, "minecraft.net", today, 45)
          _               <- seedTraffic(routerId, mac2, "wikipedia.org", today, 20)
          // 15 min extension on the kids profile → remaining = 60 + 15 - 45 = 30.
          _               <- extRepo.grantForProfile(kidsId, today, 15, "admin", None)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
            clock,
          )
          resp <- routes.runZIO(
            Request
              .get(URL.decode("/api/time/status/summary").toOption.get)
              .addHeader(Header.Authorization.Bearer(token)),
          )
          body <- resp.body.asString
          list <- ZIO.fromEither(body.fromJson[List[ProfileTimeSummary]])
          kids   = list.find(_.profileId == kidsId).get
          adults = list.find(_.profileId == adultsId).get
        } yield assertTrue(resp.status == Status.Ok) &&
          // The V1 migration seeds default Kids+Adults profiles; my seeds are additional.
          // Just assert mine are present with the right numbers.
          assertTrue(list.exists(_.profileId == kidsId)) &&
          assertTrue(list.exists(_.profileId == adultsId)) &&
          assertTrue(kids.usedMins == 45) &&
          assertTrue(kids.dailyLimitMins.contains(60)) &&
          assertTrue(kids.extensionMins == 15) &&
          assertTrue(kids.remainingMins.contains(30)) &&
          assertTrue(adults.usedMins == 20) &&
          assertTrue(adults.dailyLimitMins.isEmpty) &&
          assertTrue(adults.remainingMins.isEmpty)
      },
      test("weekly summary returns totals over the trailing 7 days") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          atlRepo     <- ZIO.service[AppTimeLimitRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo)
          _           <- tlRepo.upsert(kidsId, 120)
          mac1 = "aa:bb:cc:dd:ee:01"
          _        <- TestLayers.seedDevice(deviceRepo, mac1, "iPad", kidsId)
          routerId <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          _               <- seedTraffic(routerId, mac1, "minecraft.net", today.minusDays(2), 30)
          _               <- seedTraffic(routerId, mac1, "youtube.com", today, 25)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
            clock,
          )
          resp <- routes.runZIO(
            Request
              .get(URL.decode("/api/time/status/summary/week").toOption.get)
              .addHeader(Header.Authorization.Bearer(token)),
          )
          body <- resp.body.asString
          list <- ZIO.fromEither(body.fromJson[List[ProfileTimeSummaryWeek]])
          kids = list.find(_.profileId == kidsId).get
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(kids.totalMins == 55) &&
          assertTrue(kids.dailyLimitMins.contains(120)) &&
          assertTrue(kids.from == today.minusDays(6).toString) &&
          assertTrue(kids.to == today.toString)
      },
    ) @@ TestAspect.sequential,

    // ── #723 weekly view ────────────────────────────────────────────────────
    suite("GET /api/time/status/week")(
      test("per-day totals match a Today view computed for each date") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          atlRepo     <- ZIO.service[AppTimeLimitRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo)
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
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
            clock,
          )
          req    = Request
            .get(URL.decode("/api/time/status/week").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          list <- ZIO.fromEither(body.fromJson[List[ProfileTimeStatusWeek]])
          kids  = list.find(_.profileId == kidsId).get
          // #794: perBucket is hourly UTC buckets (default offset=0). seedTraffic places buckets
          // at midnight UTC of each seeded date, so per-day rollup in UTC matches the seeded
          // dates exactly.
          byDay = kids.perBucket
            .groupBy(_.bucketStart.atZone(java.time.ZoneOffset.UTC).toLocalDate)
            .view
            .mapValues(_.map(_.usedMins).sum)
            .toMap
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(kids.from == today.minusDays(6).toString) &&
          assertTrue(kids.to == today.toString) &&
          assertTrue(byDay.getOrElse(today.minusDays(6), 0) == 20) &&
          assertTrue(byDay.getOrElse(today.minusDays(3), 0) == 35) &&
          assertTrue(byDay.getOrElse(today, 0) == 15) &&
          assertTrue(byDay.getOrElse(today.minusDays(5), 0) == 0) && // empty days omitted
          assertTrue(kids.totalMins == 70) &&                        // 20+35+15
          assertTrue(kids.devices.head.usedMins == 70) &&
          assertTrue(kids.dailyLimitMins.contains(120))
      },
      // #795: same scoping for the week endpoint.
      test("?profileId=N narrows the weekly response to one profile") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          atlRepo     <- ZIO.service[AppTimeLimitRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo)
          adultsId    <- profileRepo
            .create("Adults", Nil)
            .flatMap(pid =>
              profileRepo
                .update(
                  Profile(
                    pid,
                    "Adults",
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
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
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
          atlRepo     <- ZIO.service[AppTimeLimitRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo)
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
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
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
          atlRepo     <- ZIO.service[AppTimeLimitRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          today  = TestClock.schoolDayAfternoon.toLocalDate
          anchor = today.minusDays(10) // outside the default trailing-week window
          _               <- seedTraffic(routerId, testMac, "minecraft.net", anchor, 25)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
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
      test("bucketOffsetMin shifts the hourly grid alignment (#794)") {
        // Default alignment (offset=0) puts a 05:30Z period_start into the 05:00Z hourly slot.
        // Caller-supplied offset=30 (half-hour zones like India) puts the same period_start into
        // the 05:30Z slot, since the grid is now {…, 04:30, 05:30, 06:30, …}. Verifying both
        // confirms server-side alignment is driven by the query param, not a fixed UTC hour.
        //
        // Anchor at `today` (TestClock=schoolDayAfternoon) so the trailing-7-day window matches
        // the rest of the suite — past tests have shown that windows anchored outside the
        // default range can race with concurrent embedded-pg cleanup on CI.
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          atlRepo     <- ZIO.service[AppTimeLimitRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          hsRepo      <- ZIO.service[HouseholdSettingsRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          // Seed a single 5-min bucket at 05:30Z on `today`. seedTraffic places buckets at
          // `bucketOffset * 5min` past midnight UTC — offset=66 → 5h30m = 05:30Z.
          _ <- seedTraffic(routerId, testMac, "late.example", today, 5, bucketOffset = 66)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          clock           <- ZIO.service[Clock]
          tss        = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes     = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
            clock,
          )
          expected0  = today.atStartOfDay(java.time.ZoneOffset.UTC).toInstant.plusSeconds(5 * 3600)
          expected30 = expected0.plusSeconds(30 * 60)
          // offset=0 — buckets at :00 of each UTC hour. 05:30Z period_start → 05:00Z slot.
          respDefault <- routes.runZIO(
            Request
              .get(URL.decode("/api/time/status/week").toOption.get)
              .addHeader(Header.Authorization.Bearer(token)),
          )
          bodyDefault <- respDefault.body.asString
          listDefault <- ZIO.fromEither(bodyDefault.fromJson[List[ProfileTimeStatusWeek]])
          kidsDefault = listDefault.find(_.profileId == kidsId).get
          // offset=30 — buckets at :30 of each UTC hour. 05:30Z period_start → 05:30Z slot.
          respHalf <- routes.runZIO(
            Request
              .get(URL.decode("/api/time/status/week?bucketOffsetMin=30").toOption.get)
              .addHeader(Header.Authorization.Bearer(token)),
          )
          bodyHalf <- respHalf.body.asString
          listHalf <- ZIO.fromEither(bodyHalf.fromJson[List[ProfileTimeStatusWeek]])
          kidsHalf = listHalf.find(_.profileId == kidsId).get
        } yield assertTrue(respDefault.status == Status.Ok) &&
          assertTrue(kidsDefault.perBucket.length == 1) &&
          assertTrue(kidsDefault.perBucket.head.bucketStart == expected0) &&
          assertTrue(kidsDefault.perBucket.head.usedMins == 5) &&
          assertTrue(kidsDefault.totalMins == 5) &&
          assertTrue(respHalf.status == Status.Ok) &&
          assertTrue(kidsHalf.perBucket.length == 1) &&
          assertTrue(kidsHalf.perBucket.head.bucketStart == expected30) &&
          assertTrue(kidsHalf.perBucket.head.usedMins == 5)
      },
      test("bucketOffsetMin rejects values outside 0/15/30/45") {
        for {
          _               <- cleanDb
          profileRepo     <- ZIO.service[ProfileRepo]
          tlRepo          <- ZIO.service[TimeLimitRepo]
          atlRepo         <- ZIO.service[AppTimeLimitRepo]
          deviceRepo      <- ZIO.service[DeviceRepo]
          trafficRepo     <- ZIO.service[TrafficReportRepo]
          extRepo         <- ZIO.service[TimeExtensionRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          auth            <- makeAuth
          token           <- auth.login("admin", "changeme").map(_.token.value)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          clock           <- ZIO.service[Clock]
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
            clock,
          )
          resp <- routes.runZIO(
            Request
              .get(URL.decode("/api/time/status/week?bucketOffsetMin=22").toOption.get)
              .addHeader(Header.Authorization.Bearer(token)),
          )
        } yield assertTrue(resp.status == Status.BadRequest)
      },
    ) @@ TestAspect.sequential,

    // ── per-device weekly variant ───────────────────────────────────────────
    suite("GET /api/time/status/{mac}/week")(
      test("per-day totals scoped to one device, per-host across the range") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          atlRepo     <- ZIO.service[AppTimeLimitRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo)
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
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
            clock,
          )
          req    = Request
            .get(URL.decode(s"/api/time/status/$mac1/week").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp   <- routes.runZIO(req)
          body   <- resp.body.asString
          status <- ZIO.fromEither(body.fromJson[DeviceTimeStatusWeek])
          byDay = status.perBucket
            .groupBy(_.bucketStart.atZone(java.time.ZoneOffset.UTC).toLocalDate)
            .view
            .mapValues(_.map(_.usedMins).sum)
            .toMap
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(status.deviceMac == MacAddress.unsafe(mac1)) &&
          assertTrue(status.totalMins == 35) && // only mac1
          assertTrue(byDay.getOrElse(today, 0) == 20) &&
          assertTrue(byDay.getOrElse(today.minusDays(2), 0) == 15) &&
          assertTrue(byDay.getOrElse(today.minusDays(1), 0) == 0) &&
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
          atlRepo         <- ZIO.service[AppTimeLimitRepo]
          deviceRepo      <- ZIO.service[DeviceRepo]
          trafficRepo     <- ZIO.service[TrafficReportRepo]
          extRepo         <- ZIO.service[TimeExtensionRepo]
          auth            <- makeAuth
          token           <- auth.login("admin", "changeme").map(_.token.value)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
            clock,
          )
          req    = Request
            .get(URL.decode("/api/time/status/aa:bb:cc:dd:ee:ff/week").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
        } yield assertTrue(resp.status == Status.NotFound)
      },
    ) @@ TestAspect.sequential,
    // #1531: the displayed per-profile daily total must exclude presence on the WHOLE host-set of
    // every exempt-from-daily app — not just the app's apex host. This mirrors, on the counting/
    // display side, the multi-host-set generalization #1505/#1523 did for enforcement (#1513 is the
    // enforcement-side sibling). The headline `usedMins` powering the dashboard tile
    // (`/api/time/status/summary`) must equal `TimeStatusService.usedSecondsForProfile` exactly, so
    // it cannot drift from the snapshot's `blocked` decision (#1160 single-source-of-truth).
    suite("exempt-from-daily multi-host exclusion (#1531)")(
      test(
        "displayed daily total excludes the whole exempt app host-set; under cap → not blocked",
      ) {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          atlRepo     <- ZIO.service[AppTimeLimitRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          appRepo     <- ZIO.service[AppRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo)
          _           <- tlRepo.upsert(kidsId, 30)
          // One exempt-from-daily app ("Math Academy") with a TWO-host set. The apex (shortest host)
          // is `a.example`; `b.example` is an equally-named off-domain asset host. An apex-only
          // exclusion would still count `b.example` toward the daily total.
          appId       <- appRepo.create("Math Academy", "mathacademy", None, None)
          _           <- appRepo.setHosts(
            appId,
            List(Hostname.unsafe("a.example"), Hostname.unsafe("b.example")),
          )
          _        <- appRepo.upsertAssignment(appId, kidsId, AppMode.TimeLimited, Some(60), true)
          _        <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          // 45 min total presence split across the two exempt hosts and one non-exempt host. Only
          // the 15 min on `c.unexempt.example` counts toward the 30-min daily cap.
          off1            <- seedTraffic(routerId, testMac, "a.example", today, 15)
          off2            <- seedTraffic(routerId, testMac, "b.example", today, 15, off1)
          _               <- seedTraffic(routerId, testMac, "c.unexempt.example", today, 15, off2)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          settings        <- hsRepo.getForHousehold(HouseholdId.Default)
          now             <- clock.instant
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
            clock,
          )
          resp <- routes.runZIO(
            Request
              .get(URL.decode("/api/time/status/summary").toOption.get)
              .addHeader(Header.Authorization.Bearer(token)),
          )
          body <- resp.body.asString
          list <- ZIO.fromEither(body.fromJson[List[ProfileTimeSummary]])
          kids = list.find(_.profileId == kidsId).get
          // Single source of truth: the same day-state that drives the snapshot's `blocked`.
          state <- tss.dayState(HouseholdId.Default, now, today, settings, kidsId).map(_.get)
        } yield assertTrue(resp.status == Status.Ok) &&
          // Only `c.unexempt.example` (15 m) counts — both `a.example` AND `b.example` excluded.
          assertTrue(kids.usedMins == 15) &&
          assertTrue(kids.usedMins <= 30) &&
          assertTrue(state.usedMinutes == 15) &&
          assertTrue(!state.blocked)
      },
      test("non-exempt presence past the cap → blocked, even with exempt app traffic present") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          atlRepo     <- ZIO.service[AppTimeLimitRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          extRepo     <- ZIO.service[TimeExtensionRepo]
          appRepo     <- ZIO.service[AppRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token.value)
          kidsId      <- TestLayers.seedKidsProfile(profileRepo)
          _           <- tlRepo.upsert(kidsId, 30)
          appId       <- appRepo.create("Math Academy", "mathacademy", None, None)
          _           <- appRepo.setHosts(
            appId,
            List(Hostname.unsafe("a.example"), Hostname.unsafe("b.example")),
          )
          _        <- appRepo.upsertAssignment(appId, kidsId, AppMode.TimeLimited, Some(60), true)
          _        <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          // Same two exempt hosts, but non-exempt browsing now pushes past the 30-min cap.
          off1            <- seedTraffic(routerId, testMac, "a.example", today, 15)
          off2            <- seedTraffic(routerId, testMac, "b.example", today, 15, off1)
          _               <- seedTraffic(routerId, testMac, "c.unexempt.example", today, 35, off2)
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          clock           <- ZIO.service[Clock]
          settings        <- hsRepo.getForHousehold(HouseholdId.Default)
          now             <- clock.instant
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
            clock,
          )
          resp <- routes.runZIO(
            Request
              .get(URL.decode("/api/time/status/summary").toOption.get)
              .addHeader(Header.Authorization.Bearer(token)),
          )
          body <- resp.body.asString
          list <- ZIO.fromEither(body.fromJson[List[ProfileTimeSummary]])
          kids = list.find(_.profileId == kidsId).get
          state <- tss.dayState(HouseholdId.Default, now, today, settings, kidsId).map(_.get)
        } yield assertTrue(resp.status == Status.Ok) &&
          // Only the 35 m of non-exempt presence counts — exempt hosts still excluded.
          assertTrue(kids.usedMins == 35) &&
          assertTrue(state.usedMinutes == 35) &&
          assertTrue(state.blocked)
      },
    ) @@ TestAspect.sequential,
    suite("GET /api/presence/ambient-hosts (#2077)")(
      test("returns the learned window with ambient flags per the thresholds") {
        for {
          _               <- cleanDb
          profileRepo     <- ZIO.service[ProfileRepo]
          tlRepo          <- ZIO.service[TimeLimitRepo]
          atlRepo         <- ZIO.service[AppTimeLimitRepo]
          deviceRepo      <- ZIO.service[DeviceRepo]
          trafficRepo     <- ZIO.service[TrafficReportRepo]
          extRepo         <- ZIO.service[TimeExtensionRepo]
          userProfileRepo <- ZIO.service[UserProfileRepo]
          hsRepo          <- ZIO.service[HouseholdSettingsRepo]
          ahr             <- ZIO.service[AmbientHostsRepo]
          clock           <- ZIO.service[Clock]
          auth            <- makeAuth
          token           <- auth.login("admin", "changeme").map(_.token.value)
          settings        <- hsRepo.getForHousehold(HouseholdId.Default)
          now             <- clock.instant
          today = wifihaven.api.policy.PolicyService.householdLocalDate(now, settings)
          // valid.apple.com isolated on 3 days (ambient at the default threshold);
          // known-issues.apple.com on 1 day (candidate only).
          _ <- ZIO.foreachDiscard(1 to 3)(i =>
            ahr.upsertDay(today.minusDays(i.toLong), Map("valid.apple.com" -> 2)),
          )
          _ <- ahr.upsertDay(today.minusDays(1), Map("known-issues.apple.com" -> 1))
          tss    = new wifihaven.api.policy.TimeStatusServiceLive(
            profileRepo,
            tlRepo,
            atlRepo,
            deviceRepo,
            trafficRepo,
            extRepo,
          )
          routes = TimeRoutes.routes(
            auth,
            deviceRepo,
            tlRepo,
            atlRepo,
            trafficRepo,
            extRepo,
            profileRepo,
            userProfileRepo,
            hsRepo,
            tss,
            clock,
            ambientRepo = ahr,
          )
          resp   <- routes.runZIO(
            Request
              .get(URL.decode("/api/presence/ambient-hosts").toOption.get)
              .addHeader(Header.Authorization.Bearer(token)),
          )
          body   <- resp.body.asString
          parsed <- ZIO.fromEither(body.fromJson[AmbientHostsResponse])
        } yield assertTrue(resp.status == Status.Ok) &&
          // #2643: the seeded household is a fresh install, so the gate now starts ON. What this
          // test pins is the learned-window projection and the threshold flags, not the switch —
          // the endpoint reports the switch's real state either way.
          assertTrue(parsed.gateEnabled) &&
          assertTrue(parsed.minIsolatedDays == 3) &&
          assertTrue(parsed.learningWindowDays == 14) &&
          assertTrue(
            parsed.hosts.exists(h =>
              h.host == "valid.apple.com" && h.ambient && h.isolatedDays == 3,
            ),
          ) &&
          assertTrue(
            parsed.hosts
              .exists(h => h.host == "known-issues.apple.com" && !h.ambient && h.isolatedDays == 1),
          )
      },
    ) @@ TestAspect.sequential,
  ) @@ TestAspect.sequential
}
