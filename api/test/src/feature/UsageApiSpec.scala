package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.policy.TimeStatusServiceLive
import wifihaven.api.routes.*
import wifihaven.api.usage.{AppUsedRollupServiceLive, TimeUsedRollupJob}
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

object UsageApiSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg   = JwtConfig(secret = "test-secret-at-least-32-chars!!", expiryHours = 1)
  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)
  private val cleanDb  = TestDatabase.cleanAndMigrate

  private val testMac = "aa:bb:cc:dd:ee:01"

  private def seedRouter: ZIO[RouterRepo, Throwable, RouterId] =
    ZIO.serviceWithZIO[RouterRepo] { rr =>
      for {
        id <- rr.create("test-router", Sha256Hex.unsafe("t" * 64))
        _  <- rr.completeEnrollment(id, Sha256Hex.unsafe("u" * 64))
      } yield id
    }

  /**
   * Insert one row into traffic_reports at (date, hour:minute UTC) for (mac, hostname). The full
   * bucket is `active_seconds = 300`, matching what the router emits in the wild.
   *
   * #1492: the usage series is now presence-based and heartbeat-filters its rows exactly as the
   * headline daily total does (default filter: drop buckets under 10 KB). The router only ever
   * emits bytes>0 rows; seed a realistic 50 KB so these buckets are real activity, not keepalives
   * that the presence model would (correctly) drop.
   */
  private def insertRow(
      routerId: RouterId,
      mac: String,
      hostname: String,
      date: LocalDate,
      hour: Int,
      minute: Int,
  ): ZIO[TrafficReportRepo, Throwable, Int] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      val start = date
        .atStartOfDay(ZoneOffset.UTC)
        .toInstant
        .plusSeconds(hour * 3600L + minute * 60L)
      val end   = start.plusSeconds(300)
      tr.insertBatch(
        List(
          TrafficReportInsert(
            routerId,
            MacAddress.unsafe(mac),
            None,
            HostId.Fqdn(Hostname.unsafe(hostname)),
            date,
            start,
            end,
            300,
            25_000L,
            25_000L,
          ),
        ),
      )
    }

  private def buildRoutes =
    for {
      deviceRepo      <- ZIO.service[DeviceRepo]
      trafficRepo     <- ZIO.service[TrafficReportRepo]
      userProfileRepo <- ZIO.service[UserProfileRepo]
      profileRepo     <- ZIO.service[ProfileRepo]
      appRepo         <- ZIO.service[AppRepo]
      rollupRepo      <- ZIO.service[wifihaven.api.db.RollupRepo]
      hsRepo          <- ZIO.service[wifihaven.api.db.HouseholdSettingsRepo]
      stlRepo         <- ZIO.service[wifihaven.api.db.SiteTimeLimitRepo]
      clock           <- ZIO.service[Clock]
      auth            <- makeAuth
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
        stlRepo,
        clock,
      ),
      auth,
    )

  // #1517 — the numbers along the per-app reconciliation chain for a single time-limited app, read
  // the way an operator would: the #1510 rollup accessor, the per-app cap aggregate
  // (`SiteDayState.usedMinutes`), the per-app graph series (`/usage/series?groupBy=app`), the profile
  // headline total, the legacy per-host proportional sum (what the un-bridged series plotted), and
  // the app's per-hour series minutes.
  private case class AppReco(
      status: Status,
      seriesDayMins: Option[Int],
      rollupMins: Option[Int],
      capMins: Option[Int],
      presenceTotalMins: Int,
      perHostPropMins: Int,
      appHourMins: Seq[Int],
  )

  // Seed one time-limited multi-host app + its traffic, then read every number on the chain. `traffic`
  // is a list of (host, hour, minute) 5-min buckets. Shared by the #1517 tests so the gap-bridge and
  // the overlap fixture exercise the exact same read path.
  private def appReco(
      appName: String,
      appSlug: String,
      hosts: List[String],
      traffic: List[(String, Int, Int)],
  ): ZIO[TestDatabase.AllRepos & EmbeddedPostgres & Clock, Any, AppReco] = {
    val today = TestClock.schoolDayAfternoon.toLocalDate
    for {
      _           <- cleanDb
      hsr         <- ZIO.service[HouseholdSettingsRepo]
      profileRepo <- ZIO.service[ProfileRepo]
      schedRepo   <- ZIO.service[ScheduleRepo]
      deviceRepo  <- ZIO.service[DeviceRepo]
      trafficRepo <- ZIO.service[TrafficReportRepo]
      appRepo     <- ZIO.service[AppRepo]
      timeLimRepo <- ZIO.service[TimeLimitRepo]
      stlRepo     <- ZIO.service[SiteTimeLimitRepo]
      extRepo     <- ZIO.service[TimeExtensionRepo]
      ruRepo      <- ZIO.service[TimeUsedRollupRepo]
      aruRepo     <- ZIO.service[AppUsedRollupRepo]
      // Pin the household reset tz to UTC so `now`'s local date is `today`.
      cur         <- hsr.get
      settings = cur.copy(dailyResetTz = ZoneOffset.UTC)
      _        <- hsr.update(settings)
      kidsId   <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
      _        <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
      routerId <- seedRouter
      appId    <- appRepo.create(appName, appSlug, None, Some("💬"))
      _        <- appRepo.setHosts(appId, hosts.map(Hostname.unsafe))
      _        <- appRepo.upsertAssignment(appId, kidsId, AppMode.TimeLimited, Some(60), false)
      _        <- ZIO.foreachDiscard(traffic) { case (h, hr, mn) =>
        insertRow(routerId, testMac, h, today, hr, mn)
      }
      now = LocalDateTime
        .of(today.getYear, today.getMonthValue, today.getDayOfMonth, 12, 0)
        .toInstant(ZoneOffset.UTC)
      // #1510 rollup read accessor (gap-bridged engaged minutes per app).
      _ <- TimeUsedRollupJob.oneTickForTest(
        ruRepo,
        aruRepo,
        profileRepo,
        deviceRepo,
        stlRepo,
        appRepo,
        trafficRepo,
        hsr,
        now,
      )
      reader = new AppUsedRollupServiceLive(
        profileRepo,
        deviceRepo,
        stlRepo,
        appRepo,
        trafficRepo,
        aruRepo,
      )
      rollup <- reader.appEngagedMinutes(now, today, settings, kidsId)
      // Per-app cap aggregate (SiteDayState.usedMinutes).
      tsvc = new TimeStatusServiceLive(
        profileRepo,
        schedRepo,
        timeLimRepo,
        stlRepo,
        deviceRepo,
        trafficRepo,
        extRepo,
        ruRepo,
      )
      state <- tsvc.dayStateLive(now, today, settings, kidsId)
      capMin = state.flatMap(_.perSite.find(_.label == s"app:$appSlug").map(_.usedMinutes))
      // The legacy per-host proportional sum (what the un-bridged series plotted).
      rows <- trafficRepo.listPresenceRows(List(MacAddress.unsafe(testMac)), today)
      perHostPropMins = (wifihaven.api.presence.Presence
        .proportionalHostSeconds(
          rows,
          CrossDeviceOverlapMode.Sum,
          settings.heartbeatFilter,
          settings.presenceContinuationSeconds,
        )
        .values
        .sum / 60L).toInt
      // The per-app graph series via the endpoint.
      rb <- buildRoutes
      (routes, auth) = rb
      token <- auth.login("admin", "changeme").map(_.token.value)
      req = Request
        .get(
          URL
            .decode(s"/api/usage/series?profileId=${kidsId.value}&date=$today&groupBy=app")
            .toOption
            .get,
        )
        .addHeader(Header.Authorization.Bearer(token))
      resp <- routes.runZIO(req)
      body <- resp.body.asString
      out  <- ZIO
        .fromEither(body.fromJson[UsageSeriesResponse])
        .orElseFail(new RuntimeException(body))
      entry    = out.topEntries.find(_.entity.name == appName)
      hourMins = out.bucketsByEntry.map(b =>
        b.perEntity.find(_.entity.name == appName).map(_.mins).getOrElse(0),
      )
    } yield AppReco(
      resp.status,
      entry.map(_.dayMins),
      rollup.get(appId),
      capMin,
      out.presenceTotalMins,
      perHostPropMins,
      hourMins,
    )
  }

  def spec = suite("Usage API")(
    suite("GET /api/usage/series")(
      test("requires mac param") {
        for {
          _  <- cleanDb
          rb <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          req = Request
            .get(URL.decode("/api/usage/series").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
        } yield assertTrue(resp.status == Status.BadRequest)
      },
      test("returns 24 hourly buckets, top-N hosts, and empty long tail for empty day") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          rb          <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          today = TestClock.schoolDayAfternoon.toLocalDate
          req   = Request
            .get(URL.decode(s"/api/usage/series?mac=$testMac&date=$today").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          out  <- ZIO.fromEither(body.fromJson[UsageSeriesResponse])
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(out.buckets.length == 24) &&
          assertTrue(out.buckets.forall(_.totalMins == 0)) &&
          assertTrue(out.topHosts.isEmpty)
      },
      test("buckets activity by UTC hour and proportionally allocates per host") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          // 14:00–14:05 UTC, two hosts in same 5-min bucket → 5 wall-clock mins
          // split 2.5min/2.5min between hosts (floored to 2m each + 1m other).
          _  <- insertRow(routerId, testMac, "youtube.com", today, 14, 0)
          _  <- insertRow(routerId, testMac, "google.com", today, 14, 0)
          // 14:05–14:10 UTC, youtube.com alone → 5m to youtube
          _  <- insertRow(routerId, testMac, "youtube.com", today, 14, 5)
          // 03:00 UTC, drop.com alone → 5m to drop in hour 3
          _  <- insertRow(routerId, testMac, "drop.com", today, 3, 0)
          _  <- ZIO.service[TrafficReportRepo].as(trafficRepo)
          rb <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          req = Request
            .get(URL.decode(s"/api/usage/series?mac=$testMac&date=$today").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          out  <- ZIO.fromEither(body.fromJson[UsageSeriesResponse])
          h14 = out.buckets(14)
          h3  = out.buckets(3)
          h0  = out.buckets(0)
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(out.buckets.length == 24) &&
          assertTrue(h14.totalMins == 10) && // two 5-min buckets in hour 14
          assertTrue(h3.totalMins == 5) &&
          assertTrue(h0.totalMins == 0) &&
          // youtube wins hour 14: 2.5min from shared bucket + 5min solo = 7.5
          assertTrue(h14.perHost.headOption.exists(_.host.value == "youtube.com")) &&
          assertTrue(h14.perHost.exists(p => p.host.value == "google.com"))
      },
      test("topN collapses long-tail into otherMins") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          // 7 distinct hosts each in their own 5-min bucket. topN=2 must
          // keep two named, fold the other 5 into otherMins.
          _  <- ZIO.foreachDiscard(0 until 7) { i =>
            insertRow(routerId, testMac, s"host$i.com", today, 10, i * 5)
          }
          rb <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          req = Request
            .get(URL.decode(s"/api/usage/series?mac=$testMac&date=$today&topN=2").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          out  <- ZIO.fromEither(body.fromJson[UsageSeriesResponse])
          h10 = out.buckets(10)
        } yield assertTrue(out.topHosts.length == 2) &&
          assertTrue(h10.totalMins == 35) &&
          assertTrue(h10.perHost.length == 2) &&
          assertTrue(h10.otherMins == 25) // 5 hosts × 5 min
      },
      test("topN=500 returns the full long-tail unaggregated (#964 drill-in)") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          // 30 distinct hosts — comfortably more than the prior cap of 20 and
          // well below the new cap of 500. With topN=500 every host should be
          // surfaced in topHosts (the prior 20-cap would have truncated to 20).
          _  <- ZIO.foreachDiscard(0 until 30) { i =>
            insertRow(routerId, testMac, s"host$i.com", today, 10, (i % 12) * 5)
          }
          rb <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          req = Request
            .get(URL.decode(s"/api/usage/series?mac=$testMac&date=$today&topN=500").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          out  <- ZIO.fromEither(body.fromJson[UsageSeriesResponse])
        } yield assertTrue(resp.status == Status.Ok) &&
          // Prior cap (.min(20)) would have capped this at 20 — the bump to 500
          // is what enables the per-device 'other' drill-in to see the full tail.
          assertTrue(out.topHosts.length == 30)
      },
      test("tz parameter buckets by local-hour") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          // 06:00 UTC on a Jan day == 22:00 previous day in PST (UTC-8). When
          // requested with tz=America/Los_Angeles for `today`, this row
          // belongs to the *previous* local day and should not appear.
          _  <- insertRow(routerId, testMac, "youtube.com", today, 6, 0)
          // 20:00 UTC == 12:00 PST on `today` → hour 12 in PST bucket layout.
          _  <- insertRow(routerId, testMac, "google.com", today, 20, 0)
          rb <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          req = Request
            .get(
              URL
                .decode(
                  s"/api/usage/series?mac=$testMac&date=$today&tz=America/Los_Angeles",
                )
                .toOption
                .get,
            )
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          out  <- ZIO.fromEither(body.fromJson[UsageSeriesResponse])
        } yield assertTrue(out.tz == "America/Los_Angeles") &&
          // The 06:00 UTC sample falls into the previous local day → excluded.
          assertTrue(out.buckets.iterator.map(_.totalMins).sum == 5) &&
          assertTrue(out.buckets(12).totalMins == 5)
      },
      test("rejects unknown mac with 404") {
        for {
          _  <- cleanDb
          rb <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          req = Request
            .get(URL.decode("/api/usage/series?mac=aa:bb:cc:dd:ee:ff").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
        } yield assertTrue(resp.status == Status.NotFound)
      },
      test("rejects requests with both mac and profileId") {
        for {
          _  <- cleanDb
          rb <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          req = Request
            .get(URL.decode("/api/usage/series?mac=aa:bb:cc:dd:ee:01&profileId=1").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
        } yield assertTrue(resp.status == Status.BadRequest)
      },
      test(
        "profileId mode: aggregates across the profile's devices, both stacks sum to totalMins",
      ) {
        val macA = "aa:bb:cc:dd:ee:0a"
        val macB = "aa:bb:cc:dd:ee:0b"
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, macA, "iPad-A", kidsId)
          _           <- TestLayers.seedDevice(deviceRepo, macB, "iPad-B", kidsId)
          routerId    <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          // Hour 14: macA sole on youtube + macA two-host bucket (5+5min=10),
          // and macB on google.com for one 5-min bucket. Profile total = 15m.
          _  <- insertRow(routerId, macA, "youtube.com", today, 14, 0)
          _  <- insertRow(routerId, macA, "youtube.com", today, 14, 5)
          _  <- insertRow(routerId, macA, "google.com", today, 14, 5)
          _  <- insertRow(routerId, macB, "google.com", today, 14, 10)
          rb <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          req = Request
            .get(
              URL.decode(s"/api/usage/series?profileId=${kidsId.value}&date=$today").toOption.get,
            )
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          out  <- ZIO.fromEither(body.fromJson[UsageSeriesResponse])
          h14H = out.buckets(14)
          h14D = out.bucketsByDevice(14)
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(out.profileId.contains(kidsId)) &&
          assertTrue(out.profileName.contains("Kids")) &&
          assertTrue(out.deviceMac.isEmpty) &&
          assertTrue(out.buckets.length == 24 && out.bucketsByDevice.length == 24) &&
          assertTrue(h14H.totalMins == 15 && h14D.totalMins == 15) &&
          // Per-device stack: macA 10m + macB 5m, no Other. In Sum mode the per-device
          // session unions partition the profile total cleanly, so they still reconcile.
          assertTrue(h14D.perDevice.length == 2 && h14D.otherMins == 0) &&
          assertTrue(h14D.perDevice.iterator.map(_.mins).sum + h14D.otherMins == 15) &&
          // #1492: per-host presence is the per-host session span, NOT a within-bucket
          // partition — google runs on both devices (10m) and youtube on one (10m), so the
          // per-host stack (20m) legitimately exceeds the 15m device-union total (design §4.3:
          // the total is the device union, not the sum of per-app spans). otherMins clamps the
          // rendered residual non-negative.
          assertTrue(h14H.perHost.iterator.map(_.mins).sum >= h14H.totalMins) &&
          assertTrue(h14H.otherMins == 0) &&
          // Day totals reconcile with the headline (sum of per-device session unions).
          assertTrue(out.topDevices.iterator.map(_.dayMins).sum == 15) &&
          assertTrue(out.presenceTotalMins == 15)
      },
      test("profileId mode: rejects unknown profile with 404") {
        for {
          _  <- cleanDb
          rb <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          req = Request
            .get(URL.decode("/api/usage/series?profileId=99999").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
        } yield assertTrue(resp.status == Status.NotFound)
      },
      test("profileId mode: empty profile (no devices) returns 24 zero buckets") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          rb          <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          req = Request
            .get(URL.decode(s"/api/usage/series?profileId=${kidsId.value}").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          out  <- ZIO.fromEither(body.fromJson[UsageSeriesResponse])
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(out.buckets.length == 24 && out.bucketsByDevice.length == 24) &&
          assertTrue(out.buckets.forall(_.totalMins == 0)) &&
          assertTrue(out.topHosts.isEmpty && out.topDevices.isEmpty)
      },
      // #1079 — unified app + non-app-host axis. Apps roll up; non-app hosts
      // surface individually; 'Other' is strictly the long tail past topN.
      test("#1079: groupBy=app emits per-app + per-non-app-host rows; Other = long tail only") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          appRepo     <- ZIO.service[AppRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          // Two apps each owning one host.
          ytId        <- appRepo.create("YouTube", "youtube", None, Some("📺"))
          _           <- appRepo.setHosts(ytId, List(Hostname.unsafe("youtube.com")))
          ggId        <- appRepo.create("Google", "google", None, None)
          _           <- appRepo.setHosts(ggId, List(Hostname.unsafe("google.com")))
          today = TestClock.schoolDayAfternoon.toLocalDate
          // Distinct minute counts so top-N ranking is deterministic.
          // YouTube: 5 buckets = 25min. Hour 10.
          _  <- ZIO.foreachDiscard(0 until 5)(i =>
            insertRow(routerId, testMac, "youtube.com", today, 10, i * 5),
          )
          // Google: 4 buckets = 20min. Hour 11.
          _  <- ZIO.foreachDiscard(0 until 4)(i =>
            insertRow(routerId, testMac, "google.com", today, 11, i * 5),
          )
          // drop.com (non-app): 3 buckets = 15min. Hour 12.
          _  <- ZIO.foreachDiscard(0 until 3)(i =>
            insertRow(routerId, testMac, "drop.com", today, 12, i * 5),
          )
          // extra.com (non-app): 2 buckets = 10min. Hour 13.
          _  <- ZIO.foreachDiscard(0 until 2)(i =>
            insertRow(routerId, testMac, "extra.com", today, 13, i * 5),
          )
          // Long tail of 6 single-bucket non-app hosts at hour 14.
          _  <- ZIO.foreachDiscard(0 until 6)(i =>
            insertRow(routerId, testMac, s"tail$i.com", today, 14, i * 5),
          )
          rb <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          req = Request
            .get(
              URL
                .decode(s"/api/usage/series?mac=$testMac&date=$today&topN=4&groupBy=app")
                .toOption
                .get,
            )
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          out  <- ZIO.fromEither(body.fromJson[UsageSeriesResponse])
          tops = out.topEntries
        } yield assertTrue(resp.status == Status.Ok) &&
          // 4 top entries: two apps + two non-app hosts (NOT 2 apps + 1 "Other").
          assertTrue(tops.length == 4) &&
          assertTrue(
            tops(0).entity.kind == "app" && tops(0).entity.name == "YouTube" && tops(
              0,
            ).dayMins == 25,
          ) &&
          assertTrue(
            tops(1).entity.kind == "app" && tops(1).entity.name == "Google" && tops(1).dayMins == 20,
          ) &&
          assertTrue(
            tops(2).entity.kind == "host" && tops(2).entity.id == "drop.com" && tops(
              2,
            ).dayMins == 15,
          ) &&
          assertTrue(
            tops(3).entity.kind == "host" && tops(3).entity.id == "extra.com" && tops(
              3,
            ).dayMins == 10,
          ) &&
          // 'Other' = long tail = 6 × 5min, present only in buckets where the tail lives.
          assertTrue(out.bucketsByEntry.length == 24) &&
          assertTrue(out.bucketsByEntry(14).otherMins == 30) &&
          // Hours without tail traffic shouldn't have an Other bar.
          assertTrue(out.bucketsByEntry(10).otherMins == 0) &&
          assertTrue(out.bucketsByEntry(11).otherMins == 0) &&
          // Day total sums.
          assertTrue(out.bucketsByEntry.iterator.map(_.totalMins).sum == 100)
      },
      // #1079 — when no host or app exceeds topN, there's no Other bucket.
      test("#1079: groupBy=app with everything inside topN → no Other row") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          today = TestClock.schoolDayAfternoon.toLocalDate
          // Two unmapped hosts only, topN=5 (default).
          _  <- insertRow(routerId, testMac, "drop.com", today, 10, 0)
          _  <- insertRow(routerId, testMac, "extra.com", today, 10, 5)
          rb <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          req = Request
            .get(URL.decode(s"/api/usage/series?mac=$testMac&date=$today&groupBy=app").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          out  <- ZIO.fromEither(body.fromJson[UsageSeriesResponse])
        } yield assertTrue(resp.status == Status.Ok) &&
          // Both unmapped hosts are individual entries — NOT lumped into Other.
          assertTrue(out.topEntries.length == 2) &&
          assertTrue(out.topEntries.forall(_.entity.kind == "host")) &&
          assertTrue(out.bucketsByEntry.iterator.map(_.otherMins).sum == 0)
      },
      // #1492 — the per-profile usage graph is presence-based: its headline
      // (`presenceTotalMins`) reconciles with the session-stitch time-used total
      // that drives the daily cap, and the per-app contributions match the
      // canonical per-app presence surface. The fixture reproduces the prod
      // undercount (each 5-min window only sampled 10s of bytes) so the legacy
      // bucket-max graph would have read ~0 while the kid was present 25 minutes.
      test("#1492: profile series total reconciles with time-used; per-app contributions match") {
        val today = TestClock.schoolDayAfternoon.toLocalDate
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          hsRepo      <- ZIO.service[wifihaven.api.db.HouseholdSettingsRepo]
          appRepo     <- ZIO.service[AppRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          ytId        <- appRepo.create("YouTube", "youtube", None, Some("📺"))
          _           <- appRepo.setHosts(ytId, List(Hostname.unsafe("youtube.com")))
          base  = today.atStartOfDay(ZoneOffset.UTC).toInstant.plusSeconds(14 * 3600L)
          // Each window: full 5-min span, but only 10s sampled active (design §2a
          // sparse-app undercount). Three contiguous youtube windows + two
          // khanacademy windows → one bridged 25-min device session.
          mkRow = (host: String, idx: Int) =>
            TrafficReportInsert(
              routerId,
              MacAddress.unsafe(testMac),
              None,
              HostId.Fqdn(Hostname.unsafe(host)),
              today,
              base.plusSeconds(idx * 300L),
              base.plusSeconds(idx * 300L + 300L),
              10,
              60_000L,
              60_000L,
            )
          _        <- trafficRepo.insertBatch(
            List(
              mkRow("youtube.com", 0),
              mkRow("youtube.com", 1),
              mkRow("youtube.com", 2),
              mkRow("khanacademy.org", 3),
              mkRow("khanacademy.org", 4),
            ),
          )
          settings <- hsRepo.get
          rows     <- trafficRepo.listPresenceRows(List(MacAddress.unsafe(testMac)), today)
          // The canonical time-used total (the number on the profile card / the cap).
          expectedMins       = wifihaven.api.presence.Presence
            .totalMinutesByMac(
              rows,
              Nil,
              settings.heartbeatFilter,
              settings.presenceContinuationSeconds,
            )
            .values
            .sum
          // What the legacy bucket-max graph would have plotted: Σ max(active_seconds).
          naiveBucketMaxMins = (rows
            .groupBy(r => (r.mac, r.periodStart))
            .values
            .map(_.map(_.activeSeconds).max.toLong)
            .sum / 60).toInt
          rb <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          req = Request
            .get(
              URL
                .decode(s"/api/usage/series?profileId=${kidsId.value}&date=$today&groupBy=app")
                .toOption
                .get,
            )
            .addHeader(Header.Authorization.Bearer(token))
          resp    <- routes.runZIO(req)
          body    <- resp.body.asString
          out     <- ZIO.fromEither(body.fromJson[UsageSeriesResponse])
          // Cross-check the per-app contributions against the canonical per-app surface.
          ubaResp <- routes.runZIO(
            Request
              .get(
                URL
                  .decode(s"/api/profiles/${kidsId.value}/usage-by-app?from=$today&to=$today")
                  .toOption
                  .get,
              )
              .addHeader(Header.Authorization.Bearer(token)),
          )
          ubaBody <- ubaResp.body.asString
          uba     <- ZIO.fromEither(ubaBody.fromJson[ProfileUsageByApp])
          ytApp   = uba.apps.find(_.appName == "YouTube").get
          ytEntry = out.topEntries.find(_.entity.name == "YouTube").get
        } yield assertTrue(resp.status == Status.Ok) &&
          // The graph headline equals the session-stitch time-used total, exactly.
          assertTrue(out.presenceTotalMins == expectedMins) &&
          assertTrue(expectedMins == 25) &&
          // ...and it is genuinely presence-based — the legacy bucket-max read ~0.
          assertTrue(naiveBucketMaxMins == 0 && out.presenceTotalMins > naiveBucketMaxMins) &&
          // The hourly bars sum to the same number (within per-hour flooring; here exact).
          assertTrue(out.buckets.iterator.map(_.totalMins).sum == expectedMins) &&
          // Per-app contributions are visible and reconcile with the per-app presence surface.
          assertTrue(ytEntry.dayMins == (ytApp.proportionalSeconds / 60).toInt) &&
          assertTrue(ytEntry.dayMins == 15) &&
          // The two contributions explain the total (no within-device overlap in this fixture).
          assertTrue(out.topEntries.map(_.dayMins).sum == expectedMins)
      },
      // #1517 — the per-app graph series must derive from the SINGLE per-app presence primitive
      // (`Presence.appSpansForProfile`, #1514/#1532): an app's host-set is gap-bridged + overlap-
      // deduped across its WHOLE host-set, NOT the concatenation of its per-host stitched spans.
      //
      // Cross-host idle-gap bridge: app "Social" owns social.com + cdn.social.net. social.com is
      // active 08:50–08:55, cdn.social.net 09:00–09:05. The 300 s cross-host gap is < the effective
      // gap (2×R = 600 s for 300 s buckets), so the apex going quiet while the off-domain CDN host
      // (#1505) carries the next request bridges into one [08:50, 09:05] engaged span = 15 min — NOT
      // 5 + 5. The legacy per-host concat plotted the un-bridged 10 min and disagreed with the per-app
      // cap aggregate and the #1510 rollup. The per-app series now reads the bridged 15 the cap and
      // rollup read, and hour-clipping that span sums back to the per-app daily total (10 in h8, 5 in
      // h9). NOTE: the profile *headline* total is 10 here, not 15 — it stitches per-(mac,host) and so
      // does NOT bridge the cross-host gap; per-app exceeding the headline under cross-host bridging
      // is the documented restricted-equality case (AppUsedRollupService). The overlap fixture below
      // is where the full profile-total ⇄ per-app chain holds exactly.
      test(
        "#1517: multi-host app series is gap-bridged + hour-clipped, reconciling with rollup + cap",
      ) {
        for {
          r <- appReco(
            "Social",
            "social",
            List("social.com", "cdn.social.net"),
            List(("social.com", 8, 50), ("cdn.social.net", 9, 0)),
          )
        } yield assertTrue(r.status == Status.Ok) &&
          // Series day total is the gap-bridged 15 — NOT the per-host concat 10.
          assertTrue(r.seriesDayMins.contains(15)) &&
          assertTrue(r.perHostPropMins == 10 && !r.seriesDayMins.contains(r.perHostPropMins)) &&
          // Reconciliation: per-app series ⇄ #1510 rollup ⇄ per-app cap aggregate.
          assertTrue(r.rollupMins.contains(15)) &&
          assertTrue(r.capMins.contains(15)) &&
          // Hour-clipping the bridged span sums back to the per-app daily total: 10 (h8) + 5 (h9).
          assertTrue(r.appHourMins(8) == 10 && r.appHourMins(9) == 5) &&
          assertTrue(r.appHourMins.sum == 15)
      },
      // #1517 — within-app cross-host OVERLAP: both hosts of app "Social" are active in the SAME three
      // contiguous windows 08:00–08:15. The per-host concat double-counts the overlap (15 + 15 = 30
      // min), but the per-app union counts it once = 15 min. Here the full reconciliation chain holds
      // exactly: profile headline total ⇄ #1510 per-app rollup ⇄ per-app cap aggregate ⇄ per-app
      // series, all 15 — the #1492 "graphs reconcile with the headline total" invariant at the app
      // dimension — while the legacy per-host sum (30) is what the un-deduped series plotted.
      test(
        "#1517: within-app cross-host overlap — full chain (profile total ⇄ rollup ⇄ series) holds",
      ) {
        for {
          r <- appReco(
            "Social",
            "social",
            List("social.com", "cdn.social.net"),
            (0 until 3).toList.flatMap(i =>
              List(("social.com", 8, i * 5), ("cdn.social.net", 8, i * 5)),
            ),
          )
        } yield assertTrue(r.status == Status.Ok) &&
          // Series day total is the overlap-deduped 15 — NOT the per-host concat 30.
          assertTrue(r.seriesDayMins.contains(15)) &&
          assertTrue(r.perHostPropMins == 30 && !r.seriesDayMins.contains(r.perHostPropMins)) &&
          // Full chain reconciles exactly at 15.
          assertTrue(r.rollupMins.contains(15)) &&
          assertTrue(r.capMins.contains(15)) &&
          assertTrue(r.presenceTotalMins == 15) &&
          // All 15 engaged minutes land in hour 8 and the per-hour series sums to the daily total.
          assertTrue(r.appHourMins(8) == 15 && r.appHourMins.sum == 15)
      },
    ) @@ TestAspect.sequential,
    // ── #846 Traffic Usage page ───────────────────────────────────────────
    suite("GET /api/usage/traffic")(
      test("raw view returns one row per traffic_reports row") {
        val today = TestClock.schoolDayAfternoon.toLocalDate
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          _           <- insertRow(routerId, testMac, "youtube.com", today, 14, 0)
          _           <- insertRow(routerId, testMac, "google.com", today, 14, 5)
          rb          <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          from = today.atStartOfDay(ZoneOffset.UTC).toInstant.toString
          to   = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant.toString
          req  = Request
            .get(
              URL
                .decode(s"/api/usage/traffic?mac=$testMac&from=$from&to=$to&bucket=raw")
                .toOption
                .get,
            )
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          out  <- ZIO.fromEither(body.fromJson[TrafficUsageResponse])
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(out.bucket == "raw") &&
          assertTrue(out.rawRows.length == 2) &&
          assertTrue(out.aggregateRows.isEmpty) &&
          assertTrue(out.rawRows.forall(_.deviceName.contains("iPad"))) &&
          assertTrue(out.rawRows.forall(_.profileName.contains("Kids"))) &&
          assertTrue(out.rawRows.map(_.host.value).toSet == Set("youtube.com", "google.com"))
      },
      test(
        "1h aggregated view with groupBy=domain groups by domain and sums bytes/seconds; matches raw sums",
      ) {
        val today = TestClock.schoolDayAfternoon.toLocalDate
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          // Two youtube rows in hour 14 (5m + 5m = 10m, 600s active), 1 google row.
          // insertRow has bytes_in/out = 0 so we hand-build with bytes via insertBatch.
          start1 = today.atStartOfDay(ZoneOffset.UTC).toInstant.plusSeconds(14 * 3600L)
          start2 = start1.plusSeconds(300)
          start3 = start1.plusSeconds(600)
          _  <- trafficRepo.insertBatch(
            List(
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(testMac),
                None,
                HostId.Fqdn(Hostname.unsafe("youtube.com")),
                today,
                start1,
                start1.plusSeconds(300),
                300,
                1000L,
                2000L,
              ),
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(testMac),
                None,
                HostId.Fqdn(Hostname.unsafe("youtube.com")),
                today,
                start2,
                start2.plusSeconds(300),
                300,
                500L,
                1500L,
              ),
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(testMac),
                None,
                HostId.Fqdn(Hostname.unsafe("google.com")),
                today,
                start3,
                start3.plusSeconds(300),
                300,
                100L,
                100L,
              ),
            ),
          )
          rb <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          from   = today.atStartOfDay(ZoneOffset.UTC).toInstant.toString
          to     = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant.toString
          aggReq = Request
            .get(
              URL
                .decode(
                  s"/api/usage/traffic?mac=$testMac&from=$from&to=$to&bucket=1h&groupBy=domain",
                )
                .toOption
                .get,
            )
            .addHeader(Header.Authorization.Bearer(token))
          aggResp <- routes.runZIO(aggReq)
          aggBody <- aggResp.body.asString
          aggOut  <- ZIO.fromEither(aggBody.fromJson[TrafficUsageResponse])
          rawReq = Request
            .get(
              URL
                .decode(s"/api/usage/traffic?mac=$testMac&from=$from&to=$to&bucket=raw")
                .toOption
                .get,
            )
            .addHeader(Header.Authorization.Bearer(token))
          rawResp <- routes.runZIO(rawReq)
          rawBody <- rawResp.body.asString
          rawOut  <- ZIO.fromEither(rawBody.fromJson[TrafficUsageResponse])
        } yield assertTrue(aggResp.status == Status.Ok) &&
          assertTrue(aggOut.bucket == "1h") &&
          assertTrue(aggOut.groupBy == List("domain")) &&
          assertTrue(aggOut.aggregateRows.length == 2) &&
          // Sum invariant: aggregated bytes == raw bytes for the same window.
          assertTrue(
            aggOut.aggregateRows.map(_.totalBytesIn).sum == rawOut.rawRows.map(_.bytesIn).sum,
          ) &&
          assertTrue(
            aggOut.aggregateRows.map(_.totalBytesOut).sum == rawOut.rawRows.map(_.bytesOut).sum,
          ) &&
          assertTrue(
            aggOut.aggregateRows.map(_.totalSeconds).sum ==
              rawOut.rawRows.map(_.activeSeconds.toLong).sum,
          ) &&
          // distinctDevices is at-least-1 per group (single test device).
          assertTrue(aggOut.aggregateRows.forall(_.distinctDevices == 1)) &&
          // Each row carries its domain in the `groups` map.
          assertTrue(aggOut.aggregateRows.forall(_.groups.contains("domain"))) &&
          assertTrue(
            aggOut.aggregateRows.map(_.groups("domain")).toSet ==
              Set("youtube.com", "google.com"),
          )
      },
      // #917: strictly additive aggregation. Default = one row per window.
      test("#917: 1h aggregated view with no groupBy returns one row per window (full roll-up)") {
        val today = TestClock.schoolDayAfternoon.toLocalDate
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          start = today.atStartOfDay(ZoneOffset.UTC).toInstant.plusSeconds(14 * 3600L)
          // Three rows in the same hour bucket, two distinct domains.
          _  <- trafficRepo.insertBatch(
            List(
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(testMac),
                None,
                HostId.Fqdn(Hostname.unsafe("youtube.com")),
                today,
                start,
                start.plusSeconds(300),
                300,
                1000L,
                2000L,
              ),
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(testMac),
                None,
                HostId.Fqdn(Hostname.unsafe("youtube.com")),
                today,
                start.plusSeconds(300),
                start.plusSeconds(600),
                300,
                500L,
                1500L,
              ),
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(testMac),
                None,
                HostId.Fqdn(Hostname.unsafe("google.com")),
                today,
                start.plusSeconds(600),
                start.plusSeconds(900),
                300,
                100L,
                100L,
              ),
            ),
          )
          rb <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          from = today.atStartOfDay(ZoneOffset.UTC).toInstant.toString
          to   = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant.toString
          req  = Request
            .get(
              URL
                .decode(s"/api/usage/traffic?mac=$testMac&from=$from&to=$to&bucket=1h")
                .toOption
                .get,
            )
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          out  <- ZIO.fromEither(body.fromJson[TrafficUsageResponse])
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(out.groupBy.isEmpty) &&
          // Three input rows in a single hour, all aggregated into one row.
          assertTrue(out.aggregateRows.length == 1) &&
          assertTrue(out.aggregateRows.head.groups.isEmpty) &&
          assertTrue(out.aggregateRows.head.totalBytesIn == 1600L) &&
          assertTrue(out.aggregateRows.head.totalBytesOut == 3600L) &&
          assertTrue(out.aggregateRows.head.totalSeconds == 900L) &&
          assertTrue(out.aggregateRows.head.distinctDomains == 2)
      },
      test(
        "#917: groupBy as repeated params (?groupBy=device&groupBy=domain) equivalent to comma form",
      ) {
        val today = TestClock.schoolDayAfternoon.toLocalDate
        val macA  = "aa:bb:cc:dd:ee:1a"
        val macB  = "aa:bb:cc:dd:ee:1b"
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, macA, "iPad-A", kidsId)
          _           <- TestLayers.seedDevice(deviceRepo, macB, "iPad-B", kidsId)
          routerId    <- seedRouter
          start = today.atStartOfDay(ZoneOffset.UTC).toInstant.plusSeconds(14 * 3600L)
          _  <- trafficRepo.insertBatch(
            List(
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(macA),
                None,
                HostId.Fqdn(Hostname.unsafe("youtube.com")),
                today,
                start,
                start.plusSeconds(300),
                300,
                100L,
                0L,
              ),
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(macB),
                None,
                HostId.Fqdn(Hostname.unsafe("google.com")),
                today,
                start,
                start.plusSeconds(300),
                300,
                200L,
                0L,
              ),
            ),
          )
          rb <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          from = today.atStartOfDay(ZoneOffset.UTC).toInstant.toString
          to   = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant.toString
          req  = Request
            .get(
              URL
                .decode(
                  s"/api/usage/traffic?profileId=${kidsId.value}&from=$from&to=$to&bucket=1h&groupBy=device&groupBy=domain",
                )
                .toOption
                .get,
            )
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          out  <- ZIO.fromEither(body.fromJson[TrafficUsageResponse])
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(out.groupBy.toSet == Set("device", "domain")) &&
          assertTrue(out.aggregateRows.length == 2)
      },
      test("#917: unknown groupBy value returns 400 unknown_groupBy") {
        for {
          _  <- cleanDb
          rb <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          req = Request
            .get(
              URL.decode(s"/api/usage/traffic?mac=$testMac&bucket=1h&groupBy=bogus").toOption.get,
            )
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
        } yield assertTrue(resp.status == Status.BadRequest) &&
          assertTrue(body.contains("unknown_groupBy")) &&
          assertTrue(body.contains("bogus"))
      },
      test("multi-column groupBy=device,domain produces one row per (window, device, domain)") {
        val today = TestClock.schoolDayAfternoon.toLocalDate
        val macA  = "aa:bb:cc:dd:ee:0a"
        val macB  = "aa:bb:cc:dd:ee:0b"
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, macA, "iPad-A", kidsId)
          _           <- TestLayers.seedDevice(deviceRepo, macB, "iPad-B", kidsId)
          routerId    <- seedRouter
          start = today.atStartOfDay(ZoneOffset.UTC).toInstant.plusSeconds(14 * 3600L)
          _  <- trafficRepo.insertBatch(
            List(
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(macA),
                None,
                HostId.Fqdn(Hostname.unsafe("youtube.com")),
                today,
                start,
                start.plusSeconds(300),
                300,
                100L,
                0L,
              ),
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(macB),
                None,
                HostId.Fqdn(Hostname.unsafe("youtube.com")),
                today,
                start,
                start.plusSeconds(300),
                300,
                200L,
                0L,
              ),
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(macA),
                None,
                HostId.Fqdn(Hostname.unsafe("google.com")),
                today,
                start,
                start.plusSeconds(300),
                300,
                50L,
                0L,
              ),
            ),
          )
          rb <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          from = today.atStartOfDay(ZoneOffset.UTC).toInstant.toString
          to   = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant.toString
          req  = Request
            .get(
              URL
                .decode(
                  s"/api/usage/traffic?profileId=${kidsId.value}&from=$from&to=$to&bucket=1h&groupBy=device,domain",
                )
                .toOption
                .get,
            )
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          out  <- ZIO.fromEither(body.fromJson[TrafficUsageResponse])
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(out.groupBy.toSet == Set("device", "domain")) &&
          // 3 distinct (device, domain) tuples in the same hour window.
          assertTrue(out.aggregateRows.length == 3) &&
          assertTrue(
            out.aggregateRows.forall(r =>
              r.groups.contains("device") && r.groups.contains("domain"),
            ),
          )
      },
      test("#865 mac= and profileId= accept comma-separated multi-value") {
        val today = TestClock.schoolDayAfternoon.toLocalDate
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          adultsId    <- profileRepo.create("Adults", List.empty)
          macAStr = "aa:bb:cc:dd:ee:01"
          macBStr = "aa:bb:cc:dd:ee:02"
          macCStr = "aa:bb:cc:dd:ee:03"
          _        <- TestLayers.seedDevice(deviceRepo, macAStr, "Kid iPad", kidsId)
          _        <- TestLayers.seedDevice(deviceRepo, macBStr, "Kid Phone", kidsId)
          _        <- TestLayers.seedDevice(deviceRepo, macCStr, "Adult Phone", adultsId)
          routerId <- seedRouter
          _        <- insertRow(routerId, macAStr, "youtube.com", today, 14, 0)
          _        <- insertRow(routerId, macBStr, "tiktok.com", today, 14, 0)
          _        <- insertRow(routerId, macCStr, "nyt.com", today, 14, 0)
          rb       <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          from = today.atStartOfDay(ZoneOffset.UTC).toInstant.toString
          to   = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant.toString
          // Two macs selected — only those two rows come back.
          reqM = Request
            .get(
              URL
                .decode(s"/api/usage/traffic?mac=$macAStr,$macBStr&from=$from&to=$to&bucket=raw")
                .toOption
                .get,
            )
            .addHeader(Header.Authorization.Bearer(token))
          respM <- routes.runZIO(reqM)
          bodyM <- respM.body.asString
          outM  <- ZIO.fromEither(bodyM.fromJson[TrafficUsageResponse])
          // Single-mac form still parses (backwards compat).
          reqOne = Request
            .get(
              URL
                .decode(s"/api/usage/traffic?mac=$macAStr&from=$from&to=$to&bucket=raw")
                .toOption
                .get,
            )
            .addHeader(Header.Authorization.Bearer(token))
          respOne <- routes.runZIO(reqOne)
          bodyOne <- respOne.body.asString
          outOne  <- ZIO.fromEither(bodyOne.fromJson[TrafficUsageResponse])
          // Two profiles selected — kids + adults, three rows.
          reqP = Request
            .get(
              URL
                .decode(
                  s"/api/usage/traffic?profileId=${kidsId.value},${adultsId.value}&from=$from&to=$to&bucket=raw",
                )
                .toOption
                .get,
            )
            .addHeader(Header.Authorization.Bearer(token))
          respP <- routes.runZIO(reqP)
          bodyP <- respP.body.asString
          outP  <- ZIO.fromEither(bodyP.fromJson[TrafficUsageResponse])
        } yield assertTrue(respM.status == Status.Ok) &&
          assertTrue(outM.rawRows.map(_.host.value).toSet == Set("youtube.com", "tiktok.com")) &&
          assertTrue(
            outOne.rawRows.length == 1 && outOne.rawRows.head.host.value == "youtube.com",
          ) &&
          assertTrue(
            outP.rawRows.map(_.host.value).toSet == Set("youtube.com", "tiktok.com", "nyt.com"),
          )
      },
      // #1035: 1m bucket is now enabled — per-minute bucketing for fine-grained
      // diagnosis. Three 5-min raw rows in distinct minutes should produce
      // three 1m aggregate rows.
      test("#1035: bucket=1m aggregates per minute") {
        val today = TestClock.schoolDayAfternoon.toLocalDate
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          start = today.atStartOfDay(ZoneOffset.UTC).toInstant.plusSeconds(14 * 3600L)
          // Three rows in three distinct minute-aligned slots (0s, 60s, 120s).
          _  <- trafficRepo.insertBatch(
            List(
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(testMac),
                None,
                HostId.Fqdn(Hostname.unsafe("youtube.com")),
                today,
                start,
                start.plusSeconds(60),
                60,
                100L,
                200L,
              ),
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(testMac),
                None,
                HostId.Fqdn(Hostname.unsafe("youtube.com")),
                today,
                start.plusSeconds(60),
                start.plusSeconds(120),
                60,
                50L,
                150L,
              ),
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(testMac),
                None,
                HostId.Fqdn(Hostname.unsafe("google.com")),
                today,
                start.plusSeconds(120),
                start.plusSeconds(180),
                60,
                10L,
                10L,
              ),
            ),
          )
          rb <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          from = start.toString
          to   = start.plusSeconds(60 * 60).toString
          req  = Request
            .get(
              URL
                .decode(s"/api/usage/traffic?mac=$testMac&from=$from&to=$to&bucket=1m")
                .toOption
                .get,
            )
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          out  <- ZIO.fromEither(body.fromJson[TrafficUsageResponse])
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(out.bucket == "1m") &&
          assertTrue(out.aggregateRows.length == 3) &&
          assertTrue(out.aggregateRows.map(_.totalBytesIn).sum == 160L) &&
          assertTrue(out.aggregateRows.map(_.totalBytesOut).sum == 360L)
      },
      test("#769: 1h aggregated view with groupBy=app joins through app_hosts") {
        val today = TestClock.schoolDayAfternoon.toLocalDate
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          appRepo     <- ZIO.service[AppRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          // App "YouTube" owns youtube.com + ytimg.com; google.com is not in any app.
          ytId        <- appRepo.create("YouTube", "youtube", None, Some("📺"))
          _           <- appRepo.setHosts(
            ytId,
            List(Hostname.unsafe("youtube.com"), Hostname.unsafe("ytimg.com")),
          )
          start1 = today.atStartOfDay(ZoneOffset.UTC).toInstant.plusSeconds(14 * 3600L)
          start2 = start1.plusSeconds(300)
          start3 = start1.plusSeconds(600)
          _  <- trafficRepo.insertBatch(
            List(
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(testMac),
                None,
                HostId.Fqdn(Hostname.unsafe("youtube.com")),
                today,
                start1,
                start1.plusSeconds(300),
                300,
                1000L,
                2000L,
              ),
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(testMac),
                None,
                HostId.Fqdn(Hostname.unsafe("ytimg.com")),
                today,
                start2,
                start2.plusSeconds(300),
                300,
                500L,
                1500L,
              ),
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(testMac),
                None,
                HostId.Fqdn(Hostname.unsafe("google.com")),
                today,
                start3,
                start3.plusSeconds(300),
                300,
                100L,
                100L,
              ),
            ),
          )
          rb <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          from = today.atStartOfDay(ZoneOffset.UTC).toInstant.toString
          to   = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant.toString
          req  = Request
            .get(
              URL
                .decode(s"/api/usage/traffic?mac=$testMac&from=$from&to=$to&bucket=1h&groupBy=app")
                .toOption
                .get,
            )
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          out  <- ZIO.fromEither(body.fromJson[TrafficUsageResponse])
          yt = out.aggregateRows.find(_.groups.getOrElse("app", "") == "youtube").get
          ot = out.aggregateRows.find(_.groups.getOrElse("app", "") == "__other__").get
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(out.groupBy == List("app")) &&
          assertTrue(out.aggregateRows.length == 2) &&
          // Bytes invariant: app + __other__ together cover everything that was inserted.
          assertTrue(
            out.aggregateRows.map(_.totalBytesIn).sum == 1600L,
          ) &&
          assertTrue(
            out.aggregateRows.map(_.totalBytesOut).sum == 3600L,
          ) &&
          assertTrue(yt.totalBytesIn == 1500L) &&
          assertTrue(yt.totalBytesOut == 3500L) &&
          assertTrue(yt.appName.contains("YouTube")) &&
          assertTrue(yt.appIcon.contains("📺")) &&
          assertTrue(yt.appId.isDefined) &&
          assertTrue(ot.totalBytesIn == 100L) &&
          assertTrue(ot.appName.contains("Other")) &&
          assertTrue(ot.appId.isEmpty)
      },
      test("#769: groupBy=app fans a multi-app host into one row per app") {
        val today = TestClock.schoolDayAfternoon.toLocalDate
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          appRepo     <- ZIO.service[AppRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          ytId        <- appRepo.create("YouTube", "youtube", None, None)
          muId        <- appRepo.create("Music", "music", None, None)
          _           <- appRepo.setHosts(ytId, List(Hostname.unsafe("youtube.com")))
          _           <- appRepo.setHosts(muId, List(Hostname.unsafe("youtube.com")))
          start1 = today.atStartOfDay(ZoneOffset.UTC).toInstant.plusSeconds(14 * 3600L)
          _  <- trafficRepo.insertBatch(
            List(
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(testMac),
                None,
                HostId.Fqdn(Hostname.unsafe("youtube.com")),
                today,
                start1,
                start1.plusSeconds(300),
                300,
                1000L,
                2000L,
              ),
            ),
          )
          rb <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          from = today.atStartOfDay(ZoneOffset.UTC).toInstant.toString
          to   = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant.toString
          req  = Request
            .get(
              URL
                .decode(s"/api/usage/traffic?mac=$testMac&from=$from&to=$to&bucket=1h&groupBy=app")
                .toOption
                .get,
            )
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          out  <- ZIO.fromEither(body.fromJson[TrafficUsageResponse])
          slugs = out.aggregateRows.map(_.groups.getOrElse("app", "")).toSet
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(slugs == Set("youtube", "music")) &&
          // Both apps see the full bytes — operator intent at the app boundary
          // is "how much did this app account for", not a strict partition.
          assertTrue(out.aggregateRows.forall(_.totalBytesIn == 1000L)) &&
          assertTrue(out.aggregateRows.forall(_.totalBytesOut == 2000L))
      },
      test("#1085: groupBy=app suffix-matches FQDNs against apex app_hosts") {
        val today = TestClock.schoolDayAfternoon.toLocalDate
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          appRepo     <- ZIO.service[AppRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          // App "YouTube" owns apex youtube.com + ytimg.com; traffic arrives
          // on FQDN subdomains (www.youtube.com, i.ytimg.com) — must still
          // attribute to YouTube, not __other__.
          ytId        <- appRepo.create("YouTube", "youtube", None, Some("📺"))
          _           <- appRepo.setHosts(
            ytId,
            List(Hostname.unsafe("youtube.com"), Hostname.unsafe("ytimg.com")),
          )
          start1 = today.atStartOfDay(ZoneOffset.UTC).toInstant.plusSeconds(14 * 3600L)
          start2 = start1.plusSeconds(300)
          start3 = start1.plusSeconds(600)
          _  <- trafficRepo.insertBatch(
            List(
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(testMac),
                None,
                HostId.Fqdn(Hostname.unsafe("www.youtube.com")),
                today,
                start1,
                start1.plusSeconds(300),
                300,
                1000L,
                2000L,
              ),
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(testMac),
                None,
                HostId.Fqdn(Hostname.unsafe("i.ytimg.com")),
                today,
                start2,
                start2.plusSeconds(300),
                300,
                500L,
                1500L,
              ),
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(testMac),
                None,
                HostId.Fqdn(Hostname.unsafe("api.mcsrvstat.us")),
                today,
                start3,
                start3.plusSeconds(300),
                300,
                100L,
                100L,
              ),
            ),
          )
          rb <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          from = today.atStartOfDay(ZoneOffset.UTC).toInstant.toString
          to   = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant.toString
          req  = Request
            .get(
              URL
                .decode(s"/api/usage/traffic?mac=$testMac&from=$from&to=$to&bucket=1h&groupBy=app")
                .toOption
                .get,
            )
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          out  <- ZIO.fromEither(body.fromJson[TrafficUsageResponse])
          yt = out.aggregateRows.find(_.groups.getOrElse("app", "") == "youtube").get
          ot = out.aggregateRows.find(_.groups.getOrElse("app", "") == "__other__").get
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(out.aggregateRows.length == 2) &&
          // www.youtube.com + i.ytimg.com both roll up to YouTube.
          assertTrue(yt.totalBytesIn == 1500L) &&
          assertTrue(yt.totalBytesOut == 3500L) &&
          assertTrue(yt.appName.contains("YouTube")) &&
          // Only api.mcsrvstat.us (no apex match) lands in Other.
          assertTrue(ot.totalBytesIn == 100L) &&
          assertTrue(ot.totalBytesOut == 100L) &&
          assertTrue(ot.appId.isEmpty)
      },
      // #769: groupBy=app is now implemented; the apex case still rejects.
      test("rejects groupBy=apex with groupBy_not_implemented") {
        for {
          _  <- cleanDb
          rb <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          apexReq = Request
            .get(URL.decode(s"/api/usage/traffic?mac=$testMac&bucket=1h&groupBy=apex").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          apexResp <- routes.runZIO(apexReq)
          apexBody <- apexResp.body.asString
        } yield assertTrue(apexResp.status == Status.BadRequest) &&
          assertTrue(apexBody.contains("groupBy_not_implemented")) &&
          assertTrue(apexBody.contains("apex"))
      },
      // #809/#813: the prior 31-day on-the-fly cap is gone. Wide windows now
      // route to traffic_hourly / traffic_daily instead of 503ing. The test
      // below pins the new contract: a 40-day request succeeds (200) reading
      // the daily rollup tier. Empty rollup table = empty aggregateRows but
      // not an error.
      test("wide windows route to the daily rollup (#809 #813)") {
        val today = TestClock.schoolDayAfternoon.toLocalDate
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          _           <- insertRow(routerId, testMac, "youtube.com", today, 14, 0)
          rb          <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          from = today.minusDays(40).atStartOfDay(ZoneOffset.UTC).toInstant.toString
          to   = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant.toString
          req  = Request
            .get(
              URL
                .decode(s"/api/usage/traffic?mac=$testMac&from=$from&to=$to&bucket=1d")
                .toOption
                .get,
            )
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
        } yield assertTrue(resp.status == Status.Ok)
      },
      test("raw cursor pagination walks the full set without dups (#862)") {
        val today = TestClock.schoolDayAfternoon.toLocalDate
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          // 7 rows in distinct 5-min slots, single domain to make ordering deterministic.
          _           <- ZIO.foreachDiscard(0 until 7)(i =>
            insertRow(routerId, testMac, s"site$i.com", today, 14, i * 5),
          )
          rb          <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          from  = today.atStartOfDay(ZoneOffset.UTC).toInstant.toString
          to    = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant.toString
          pager = (cursor: Option[String]) => {
            val base = s"/api/usage/traffic?mac=$testMac&from=$from&to=$to&bucket=raw&limit=3"
            val q    = cursor.fold(base)(c => s"$base&cursor=$c")
            val req  = Request
              .get(URL.decode(q).toOption.get)
              .addHeader(Header.Authorization.Bearer(token))
            routes
              .runZIO(req)
              .flatMap(_.body.asString)
              .flatMap(b => ZIO.fromEither(b.fromJson[TrafficUsageResponse]))
          }
          p1 <- pager(None)
          p2 <- p1.nextCursor match {
            case Some(c) => pager(Some(c))
            case None    => ZIO.succeed(p1.copy(rawRows = Nil, nextCursor = None))
          }
          p3 <- p2.nextCursor match {
            case Some(c) => pager(Some(c))
            case None    => ZIO.succeed(p2.copy(rawRows = Nil, nextCursor = None))
          }
          all = p1.rawRows ++ p2.rawRows ++ p3.rawRows
          hosts = all.map(_.host.value)
        } yield assertTrue(p1.rawRows.length == 3) &&
          assertTrue(p2.rawRows.length == 3) &&
          assertTrue(p3.rawRows.length == 1) &&
          assertTrue(p3.nextCursor.isEmpty) &&
          assertTrue(hosts.distinct.length == 7) &&
          // newest first: site6 → site0
          assertTrue(
            hosts == List(
              "site6.com",
              "site5.com",
              "site4.com",
              "site3.com",
              "site2.com",
              "site1.com",
              "site0.com",
            ),
          )
      },
      test("raw cursor: bad cursor returns 400 (#862)") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          _           <- seedRouter
          rb          <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          req = Request
            .get(
              URL
                .decode(s"/api/usage/traffic?mac=$testMac&bucket=raw&cursor=not-valid")
                .toOption
                .get,
            )
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
        } yield assertTrue(resp.status == Status.BadRequest)
      },
      test("aggregated cursor pagination across multi-window dataset (#862)") {
        val today = TestClock.schoolDayAfternoon.toLocalDate
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          // 5 rows in 5 different hours so 1h bucket yields 5 windows.
          _           <- ZIO.foreachDiscard(0 until 5)(h =>
            insertRow(routerId, testMac, "alpha.com", today, 10 + h, 0),
          )
          rb          <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          from  = today.atStartOfDay(ZoneOffset.UTC).toInstant.toString
          to    = today.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant.toString
          pager = (cursor: Option[String]) => {
            val base = s"/api/usage/traffic?mac=$testMac&from=$from&to=$to&bucket=1h&limit=2"
            val q    = cursor.fold(base)(c => s"$base&cursor=$c")
            val req  = Request
              .get(URL.decode(q).toOption.get)
              .addHeader(Header.Authorization.Bearer(token))
            routes
              .runZIO(req)
              .flatMap(_.body.asString)
              .flatMap(b => ZIO.fromEither(b.fromJson[TrafficUsageResponse]))
          }
          p1 <- pager(None)
          p2 <- p1.nextCursor match {
            case Some(c) => pager(Some(c))
            case None    => ZIO.succeed(p1.copy(aggregateRows = Nil, nextCursor = None))
          }
          p3 <- p2.nextCursor match {
            case Some(c) => pager(Some(c))
            case None    => ZIO.succeed(p2.copy(aggregateRows = Nil, nextCursor = None))
          }
          all = p1.aggregateRows ++ p2.aggregateRows ++ p3.aggregateRows
          windows = all.map(_.windowStart)
        } yield assertTrue(p1.aggregateRows.length == 2) &&
          assertTrue(p2.aggregateRows.length == 2) &&
          assertTrue(p3.aggregateRows.length == 1) &&
          assertTrue(p3.nextCursor.isEmpty) &&
          assertTrue(windows.distinct.length == 5) &&
          assertTrue(windows == windows.sortBy(s => -java.time.Instant.parse(s).toEpochMilli))
      },
    ) @@ TestAspect.sequential,
    // #1061 — per-app time-used breakdown for one profile.
    suite("GET /api/profiles/:id/usage-by-app")(
      test("#1519: two apps + one non-app host → 2 app rows, 1 orphan row, NO 'Other' app row") {
        val today = TestClock.schoolDayAfternoon.toLocalDate
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          appRepo     <- ZIO.service[AppRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          ytId        <- appRepo.create("YouTube", "youtube", None, Some("📺"))
          muId        <- appRepo.create("Music", "music", None, Some("🎵"))
          _           <- appRepo.setHosts(ytId, List(Hostname.unsafe("youtube.com")))
          _           <- appRepo.setHosts(muId, List(Hostname.unsafe("spotify.com")))
          // 1 bucket on youtube.com (5m), 1 bucket on spotify.com (5m),
          // 1 bucket on google.com (not in any app — falls into Other).
          // Real-traffic byte volume so the default heartbeat filter (10KB floor,
          // now applied to per-app surfaces too — #1465) keeps these rows.
          start = today.atStartOfDay(ZoneOffset.UTC).toInstant.plusSeconds(14 * 3600L)
          _  <- trafficRepo.insertBatch(
            List(
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(testMac),
                None,
                HostId.Fqdn(Hostname.unsafe("youtube.com")),
                today,
                start,
                start.plusSeconds(300),
                300,
                500_000L,
                500_000L,
              ),
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(testMac),
                None,
                HostId.Fqdn(Hostname.unsafe("spotify.com")),
                today,
                start.plusSeconds(300),
                start.plusSeconds(600),
                300,
                500_000L,
                500_000L,
              ),
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(testMac),
                None,
                HostId.Fqdn(Hostname.unsafe("google.com")),
                today,
                start.plusSeconds(600),
                start.plusSeconds(900),
                300,
                500_000L,
                500_000L,
              ),
            ),
          )
          rb <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          req = Request
            .get(
              URL
                .decode(s"/api/profiles/${kidsId.value}/usage-by-app?from=$today&to=$today")
                .toOption
                .get,
            )
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          out  <- ZIO.fromEither(body.fromJson[ProfileUsageByApp])
          yt     = out.apps.find(_.appName == "YouTube").get
          mu     = out.apps.find(_.appName == "Music").get
          orphan = out.orphanHosts.find(_.host.value == "google.com").get
        } yield assertTrue(resp.status == Status.Ok) &&
          // Only the two real apps: no synthetic "Other" row anywhere in `apps`.
          assertTrue(out.apps.length == 2) &&
          assertTrue(!out.apps.exists(_.appName == "Other")) &&
          assertTrue(!out.apps.exists(_.appId.isEmpty)) &&
          // Each app saw one 5-min bucket → 300 presence seconds, ~300 proportional seconds.
          assertTrue(yt.presenceSeconds == 300L) &&
          assertTrue(mu.presenceSeconds == 300L) &&
          assertTrue(yt.proportionalSeconds == 300L) &&
          assertTrue(mu.proportionalSeconds == 300L) &&
          assertTrue(yt.appIcon.contains("📺")) &&
          // The non-app host surfaces as a per-host orphan row, not as an "Other" bucket.
          assertTrue(out.orphanHosts.length == 1) &&
          assertTrue(orphan.proportionalSeconds == 300L) &&
          assertTrue(orphan.presenceSeconds == 300L)
      },
      test("#1433: an app with usage but no time limit still returns its time-used") {
        // The profile/app page surfaces today's time-used for every app, not
        // just time-limited ones. An app assigned with mode=allowed (no daily
        // cap) that saw traffic must come back with its proportional seconds so
        // the row can render "Xm today" without a cap.
        val today = TestClock.schoolDayAfternoon.toLocalDate
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          appRepo     <- ZIO.service[AppRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          ytId        <- appRepo.create("YouTube", "youtube", None, Some("📺"))
          _           <- appRepo.setHosts(ytId, List(Hostname.unsafe("youtube.com")))
          // Allowed, NOT time-limited: no daily cap configured for this app.
          _           <- appRepo.upsertAssignment(ytId, kidsId, AppMode.Allowed, None, true)
          start = today.atStartOfDay(ZoneOffset.UTC).toInstant.plusSeconds(14 * 3600L)
          _  <- trafficRepo.insertBatch(
            List(
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(testMac),
                None,
                HostId.Fqdn(Hostname.unsafe("youtube.com")),
                today,
                start,
                start.plusSeconds(300),
                300,
                500_000L,
                500_000L,
              ),
            ),
          )
          rb <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          req = Request
            .get(
              URL
                .decode(s"/api/profiles/${kidsId.value}/usage-by-app?from=$today&to=$today")
                .toOption
                .get,
            )
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          out  <- ZIO.fromEither(body.fromJson[ProfileUsageByApp])
          yt = out.apps.find(_.appName == "YouTube").get
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(out.apps.exists(_.appName == "YouTube")) &&
          assertTrue(yt.proportionalSeconds == 300L) &&
          assertTrue(yt.presenceSeconds == 300L)
      },
      test("#1161: traffic on FQDN subdomain attributes to apex-form app_hosts entry") {
        // App is registered with apex `khanacademy.org`, but the device actually
        // contacts `m.khanacademy.org`. Pre-fix the row falls into the synthetic
        // "Other" bucket because the lookup is strict equality; post-fix it
        // attributes to the Khan Academy app (same suffix rule as #1085 on the
        // groupBy=app path).
        val today = TestClock.schoolDayAfternoon.toLocalDate
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          appRepo     <- ZIO.service[AppRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          kaId        <- appRepo.create("KhanAcademy", "khan", None, Some("🎓"))
          _           <- appRepo.setHosts(kaId, List(Hostname.unsafe("khanacademy.org")))
          start = today.atStartOfDay(ZoneOffset.UTC).toInstant.plusSeconds(14 * 3600L)
          _  <- trafficRepo.insertBatch(
            List(
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(testMac),
                None,
                HostId.Fqdn(Hostname.unsafe("m.khanacademy.org")),
                today,
                start,
                start.plusSeconds(300),
                300,
                500_000L,
                500_000L,
              ),
            ),
          )
          rb <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          req = Request
            .get(
              URL
                .decode(s"/api/profiles/${kidsId.value}/usage-by-app?from=$today&to=$today")
                .toOption
                .get,
            )
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          out  <- ZIO.fromEither(body.fromJson[ProfileUsageByApp])
          ka = out.apps.find(_.appName == "KhanAcademy")
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(ka.isDefined) &&
          assertTrue(ka.get.proportionalSeconds == 300L) &&
          assertTrue(ka.get.presenceSeconds == 300L) &&
          assertTrue(ka.get.hosts.map(_.host.value).contains("m.khanacademy.org")) &&
          assertTrue(!out.apps.exists(_.appId.isEmpty)) &&
          // #1519: an apex-matched host MUST attribute to its app, not surface as an orphan row.
          assertTrue(out.orphanHosts.isEmpty)
      },
      test("sorted by proportionalSeconds desc") {
        val today = TestClock.schoolDayAfternoon.toLocalDate
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          appRepo     <- ZIO.service[AppRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          ytId        <- appRepo.create("YouTube", "youtube", None, None)
          muId        <- appRepo.create("Music", "music", None, None)
          _           <- appRepo.setHosts(ytId, List(Hostname.unsafe("youtube.com")))
          _           <- appRepo.setHosts(muId, List(Hostname.unsafe("spotify.com")))
          start = today.atStartOfDay(ZoneOffset.UTC).toInstant.plusSeconds(14 * 3600L)
          // YouTube: 2 buckets (10m). Music: 1 bucket (5m). Real-traffic bytes so
          // the default heartbeat filter (now on per-app surfaces, #1465) keeps them.
          _  <- trafficRepo.insertBatch(
            List(
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(testMac),
                None,
                HostId.Fqdn(Hostname.unsafe("youtube.com")),
                today,
                start,
                start.plusSeconds(300),
                300,
                500_000L,
                500_000L,
              ),
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(testMac),
                None,
                HostId.Fqdn(Hostname.unsafe("youtube.com")),
                today,
                start.plusSeconds(300),
                start.plusSeconds(600),
                300,
                500_000L,
                500_000L,
              ),
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(testMac),
                None,
                HostId.Fqdn(Hostname.unsafe("spotify.com")),
                today,
                start.plusSeconds(600),
                start.plusSeconds(900),
                300,
                500_000L,
                500_000L,
              ),
            ),
          )
          rb <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          req = Request
            .get(
              URL.decode(s"/api/profiles/${kidsId.value}/usage-by-app").toOption.get,
            )
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          out  <- ZIO.fromEither(body.fromJson[ProfileUsageByApp])
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(out.apps.map(_.appName) == List("YouTube", "Music")) &&
          assertTrue(out.apps.head.proportionalSeconds == 600L) &&
          assertTrue(out.apps(1).proportionalSeconds == 300L)
      },
      test(
        "#1519/#726: one app with 3 hosts + 2 orphan hosts → 1 app row (summed) + 2 orphan rows",
      ) {
        val today = TestClock.schoolDayAfternoon.toLocalDate
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          deviceRepo  <- ZIO.service[DeviceRepo]
          trafficRepo <- ZIO.service[TrafficReportRepo]
          appRepo     <- ZIO.service[AppRepo]
          kidsId      <- TestLayers.seedKidsProfile(profileRepo, schedRepo)
          _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
          routerId    <- seedRouter
          ytId        <- appRepo.create("YouTube", "youtube", None, Some("📺"))
          _           <- appRepo.setHosts(
            ytId,
            List(
              Hostname.unsafe("youtube.com"),
              Hostname.unsafe("ytimg.com"),
              Hostname.unsafe("googlevideo.com"),
            ),
          )
          start = today.atStartOfDay(ZoneOffset.UTC).toInstant.plusSeconds(14 * 3600L)
          // 3 buckets for the app (across its three hosts) + 2 buckets for two orphan hosts.
          _  <- trafficRepo.insertBatch(
            List(
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(testMac),
                None,
                HostId.Fqdn(Hostname.unsafe("youtube.com")),
                today,
                start,
                start.plusSeconds(300),
                300,
                500_000L,
                500_000L,
              ),
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(testMac),
                None,
                HostId.Fqdn(Hostname.unsafe("ytimg.com")),
                today,
                start.plusSeconds(300),
                start.plusSeconds(600),
                300,
                500_000L,
                500_000L,
              ),
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(testMac),
                None,
                HostId.Fqdn(Hostname.unsafe("googlevideo.com")),
                today,
                start.plusSeconds(600),
                start.plusSeconds(900),
                300,
                500_000L,
                500_000L,
              ),
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(testMac),
                None,
                HostId.Fqdn(Hostname.unsafe("wikipedia.org")),
                today,
                start.plusSeconds(900),
                start.plusSeconds(1200),
                300,
                500_000L,
                500_000L,
              ),
              TrafficReportInsert(
                routerId,
                MacAddress.unsafe(testMac),
                None,
                HostId.Fqdn(Hostname.unsafe("example.com")),
                today,
                start.plusSeconds(1200),
                start.plusSeconds(1500),
                300,
                500_000L,
                500_000L,
              ),
            ),
          )
          rb <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          req = Request
            .get(URL.decode(s"/api/profiles/${kidsId.value}/usage-by-app").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
          out  <- ZIO.fromEither(body.fromJson[ProfileUsageByApp])
          yt          = out.apps.find(_.appName == "YouTube").get
          orphanHosts = out.orphanHosts.map(_.host.value).toSet
        } yield assertTrue(resp.status == Status.Ok) &&
          // Exactly 1 app row (the configured YouTube app-set, aggregated across 3 hosts).
          assertTrue(out.apps.length == 1) &&
          assertTrue(yt.proportionalSeconds == 900L) &&
          assertTrue(
            yt.hosts.map(_.host.value).toSet ==
              Set("youtube.com", "ytimg.com", "googlevideo.com"),
          ) &&
          // 2 per-orphan rows, NO "Other" bucket.
          assertTrue(out.orphanHosts.length == 2) &&
          assertTrue(orphanHosts == Set("wikipedia.org", "example.com")) &&
          assertTrue(out.orphanHosts.forall(_.proportionalSeconds == 300L)) &&
          assertTrue(!out.apps.exists(_.appName == "Other"))
      },
      test("404 on unknown profile id") {
        for {
          _  <- cleanDb
          rb <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          req = Request
            .get(URL.decode("/api/profiles/9999/usage-by-app").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
        } yield assertTrue(resp.status == Status.NotFound)
      },
      test("401 without token") {
        for {
          rb <- buildRoutes
          (routes, _) = rb
          req         = Request.get(URL.decode("/api/profiles/1/usage-by-app").toOption.get)
          resp <- routes.runZIO(req)
        } yield assertTrue(resp.status == Status.Unauthorized)
      },
    ) @@ TestAspect.sequential,
  ) @@ TestAspect.sequential
}
