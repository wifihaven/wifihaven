package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
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

import java.time.{LocalDate, ZoneOffset}

object UsageApiSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

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
            0L,
            0L,
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
      clock           <- ZIO.service[Clock]
      auth            <- makeAuth
    } yield (
      UsageRoutes.routes(auth, deviceRepo, trafficRepo, userProfileRepo, profileRepo, clock),
      auth,
    )

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
          // Per-device stack: macA 10m + macB 5m, no Other.
          assertTrue(h14D.perDevice.length == 2 && h14D.otherMins == 0) &&
          assertTrue(h14D.perDevice.iterator.map(_.mins).sum + h14D.otherMins == 15) &&
          // Per-host stack invariant: sum(perHost.mins) + otherMins == totalMins.
          assertTrue(h14H.perHost.iterator.map(_.mins).sum + h14H.otherMins == 15) &&
          // Day totals reconcile.
          assertTrue(out.topDevices.iterator.map(_.dayMins).sum == 15)
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
      test("rejects bucket=1m with bucket_not_implemented") {
        for {
          _  <- cleanDb
          rb <- buildRoutes
          (routes, auth) = rb
          token <- auth.login("admin", "changeme").map(_.token.value)
          req = Request
            .get(URL.decode(s"/api/usage/traffic?mac=$testMac&bucket=1m").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
          body <- resp.body.asString
        } yield assertTrue(resp.status == Status.BadRequest) &&
          assertTrue(body.contains("bucket_not_implemented"))
      },
      test("rejects groupBy=apex and groupBy=app with groupBy_not_implemented") {
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
          appReq = Request
            .get(URL.decode(s"/api/usage/traffic?mac=$testMac&bucket=1h&groupBy=app").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          appResp <- routes.runZIO(appReq)
          appBody <- appResp.body.asString
        } yield assertTrue(apexResp.status == Status.BadRequest) &&
          assertTrue(apexBody.contains("groupBy_not_implemented")) &&
          assertTrue(apexBody.contains("apex")) &&
          assertTrue(appResp.status == Status.BadRequest) &&
          assertTrue(appBody.contains("app"))
      },
      test("rejects window beyond on-the-fly cap with 503") {
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
          body <- resp.body.asString
        } yield assertTrue(resp.status == Status.ServiceUnavailable) &&
          assertTrue(body.contains("window_too_large"))
      },
    ) @@ TestAspect.sequential,
  ) @@ TestAspect.sequential
}
