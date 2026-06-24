package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.routes.*
import wifihaven.api.usage.{AppMembership, TrafficUsageDbRow, UsageTraffic}
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

/**
 * #1505: an app's curated host-set beyond the apex. Off-domain asset/CDN hosts listed under an app
 * must (a) attribute to that app in the usage views — not the synthetic "Other" bucket — and (b)
 * count toward that app's *single* per-site time limit, aggregated across the whole host-set rather
 * than each host getting its own independent budget.
 *
 * The motivating case is Math Academy reading ~0: its apex `mathacademy.com` ticks the 30 m limit
 * but assets served from an off-domain CDN miss both the app attribution and the limit pattern,
 * landing in "Other" and never counting.
 */
object AppHostSetAttributionSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg   = JwtConfig(secret = "test-secret-at-least-32-chars!!", expiryHours = 1)
  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)
  private val cleanDb  = TestDatabase.cleanAndMigrate

  private val testMac = "aa:bb:cc:dd:ee:05"

  // The app's apex and an off-domain (different registrable domain) asset host.
  private val apexHost  = "mathacademy.com"
  private val assetHost = "mathacademy-cdn.example"
  private val appSlug   = "math-academy"

  private def seedRouter: ZIO[RouterRepo, Throwable, RouterId] =
    ZIO.serviceWithZIO[RouterRepo] { rr =>
      for {
        id <- rr.create("test-router", Sha256Hex.unsafe("t" * 64))
        _  <- rr.completeEnrollment(id, Sha256Hex.unsafe("u" * 64))
      } yield id
    }

  // Seed `minutes` of traffic for (mac, host, date) as non-overlapping 5-min buckets, returning the
  // next free bucket index so consecutive seeds for the same mac don't collide.
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

  def spec = suite("App host-set attribution (#1505)")(
    test(
      "off-domain asset host counts toward the app's single site limit (aggregated across the host-set)",
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
        // Math Academy with apex + an off-domain CDN host, time-limited to 30 m, exempt from daily.
        appId       <- appRepo.create("Math Academy", appSlug, None, None)
        _           <- appRepo.setHosts(
          appId,
          List(Hostname.unsafe(apexHost), Hostname.unsafe(assetHost)),
        )
        _           <- appRepo.upsertAssignment(appId, kidsId, AppMode.TimeLimited, Some(30), true)
        _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
        routerId    <- seedRouter
        today = TestClock.schoolDayAfternoon.toLocalDate
        // 60 m general browsing + 10 m on the apex + 10 m on the off-domain asset host.
        off1            <- seedTraffic(routerId, testMac, "google.com", today, 60)
        off2            <- seedTraffic(routerId, testMac, apexHost, today, 10, off1)
        _               <- seedTraffic(routerId, testMac, assetHost, today, 10, off2)
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
      } yield {
        val mathBars = status.appUsage.filter(_.label == s"app:$appSlug")
        // The whole app is ONE limit: a single bar, both hosts aggregated to 20 of 30 minutes.
        assertTrue(mathBars.size == 1) &&
        assertTrue(mathBars.head.usedMins == 20) &&
        assertTrue(mathBars.head.remainingMins.contains(10)) &&
        // Both app hosts are exempt from the daily cap, so only the 60 m of general browsing counts.
        assertTrue(status.usedMins == 60)
      }
    },
    test(
      "single-device per-app view counts DISTINCTIVE hosts only — a shared host firing without distinctive activity does not inflate the app's minutes (#1897)",
    ) {
      // #1897 shared-hosts S2: the canonical per-app engaged-minutes stitch reads each app's
      // distinctive (`shared = false`) host-set only — a shared backend (e.g. elevenlabs.io behind
      // multiple apps) overlapping a distinctive span is already counted there, and a shared firing
      // WITHOUT any distinctive activity must NOT create/extend the app's minutes. The single-device
      // view (`GET /api/time/status/{mac}`) re-derives per-app minutes against this device's presence
      // rows, so it must read the SAME distinctive-only source as the profile view, not the full
      // host-set.
      val distinctiveHost = "elevenlabs-app.example"
      val sharedHost      = "elevenlabs.io"
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
        appId       <- appRepo.create("ElevenLabs", "elevenlabs", None, None)
        // Distinctive app host + a SHARED backend host (e.g. shared across multiple AI apps).
        _           <- appRepo.setHostEntries(
          appId,
          List(
            AppHostEntry(Hostname.unsafe(distinctiveHost), shared = false),
            AppHostEntry(Hostname.unsafe(sharedHost), shared = true),
          ),
        )
        _           <- appRepo.upsertAssignment(appId, kidsId, AppMode.TimeLimited, Some(30), false)
        _           <- TestLayers.seedDevice(deviceRepo, testMac, "iPad", kidsId)
        routerId    <- seedRouter
        today = TestClock.schoolDayAfternoon.toLocalDate
        // 10 m on the distinctive host (buckets 0–1), then 10 m on the SHARED host an hour later
        // (buckets 12–13) — a separate, non-overlapping span with no distinctive activity around it.
        _               <- seedTraffic(routerId, testMac, distinctiveHost, today, 10)
        _               <- seedTraffic(routerId, testMac, sharedHost, today, 10, 12)
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
      } yield {
        val bars = status.appUsage.filter(_.label == "app:elevenlabs")
        assertTrue(bars.size == 1) &&
        // Distinctive-only: the 10 m of shared-host traffic must NOT be added on top of the 10 m of
        // distinctive activity. Reading the full host-set (the bug) yields 20.
        assertTrue(bars.head.usedMins == 10)
      }
    },
    test("off-domain asset host attributes to the app, not the 'Other' bucket") {
      // Pure attribution guardrail (Fix C): once the off-domain host is in the app's host-set, the
      // group-by-app aggregation buckets its traffic under the app rather than __other__.
      val today      = LocalDate.of(2025, 1, 6)
      val start      = today.atTime(14, 0).toInstant(ZoneOffset.UTC)
      val mac        = MacAddress.unsafe(testMac)
      val rows       = List(
        TrafficUsageDbRow(
          mac,
          HostId.Fqdn(Hostname.unsafe(assetHost)),
          start,
          start.plusSeconds(300),
          300,
          500_000L,
          500_000L,
        ),
      )
      val membership = AppMembership(appSlug, "Math Academy", None, Some(AppId(1L)))
      val byHost     = Map(apexHost -> List(membership), assetHost -> List(membership))
      val agg        = UsageTraffic.buildAggregate(
        rows,
        UsageTraffic.Bucket.OneHour,
        ZoneOffset.UTC,
        Set(UsageTraffic.GroupBy.App),
        Map.empty,
        Map.empty,
        byHost,
      )
      ZIO.succeed(
        assertTrue(agg.exists(r => r.soleApp.isEmpty && r.appName.contains("Math Academy"))) &&
          // #1526: no "__other__" sentinel — assert both hosts attribute to the
          // registered app, not a synthetic bucket. (Unmatched hosts would each
          // get their own host-keyed single-host app, but here both hosts ARE
          // matched, so they roll up into "Math Academy" only.)
          assertTrue(agg.flatMap(_.groups.get("app")).toSet == Set(appSlug)),
      )
    },
    // The DB-backed tests each clone the migration template into a fixed-named scratch DB, so they
    // must not run concurrently within this suite.
  ) @@ TestAspect.sequential
}
