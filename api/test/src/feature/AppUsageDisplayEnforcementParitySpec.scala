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

/**
 * #2744: the per-app usage DISPLAY must report the same engaged minutes ENFORCEMENT does.
 *
 * Prod 2026-08-25: Khan Academy on one device-day read 37 engaged minutes through the canonical
 * per-app path (`app_used_daily` / the per-app cap, both fed by `Presence.appSpansForProfile` — the
 * #1514/#1532 "exactly one per-app time computation") and 50 minutes through `GET
 * /api/profiles/{id}/usage-by-app`, which built its per-app headline by SUMMING
 * `Presence.proportionalHostSeconds` over the app's hosts. Khan's apex and its CDN host are one
 * browsing session, so summing them double-counted the overlap.
 *
 * The invariant pinned here: N minutes of traffic attributed to an app yields ~N engaged minutes on
 * BOTH surfaces, however many of the app's hosts carried that traffic concurrently.
 *
 * LIVENESS ANCHOR: a second, single-host app is seeded with a different amount of traffic in a
 * disjoint window and asserted to read its own distinct non-zero value on both surfaces. Without it
 * an equality assertion would pass for free against a rig that drove neither side (0 == 0) or that
 * happened to report one constant everywhere.
 */
object AppUsageDisplayEnforcementParitySpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg   = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)
  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)
  private val cleanDb  = TestDatabase.cleanAndMigrate

  private val testMac = "aa:bb:cc:dd:ee:44"

  // The multi-host app under test: an apex plus an off-domain CDN host, both DISTINCTIVE, both
  // carrying traffic in the SAME wall-clock buckets — the Khan Academy shape.
  private val apexHost = "khanacademy.example"
  private val cdnHost  = "kastatic.example"
  private val appSlug  = "khan-academy"
  // The liveness anchor: a single-host app, different minutes, disjoint window.
  private val soloHost = "solo-app.example"
  private val soloSlug = "solo-app"

  private def seedRouter: ZIO[RouterRepo, Throwable, RouterId] =
    ZIO.serviceWithZIO[RouterRepo] { rr =>
      for {
        id <- rr.create("test-router", Sha256Hex.unsafe("t" * 64))
        _  <- rr.completeEnrollment(id, Sha256Hex.unsafe("u" * 64))
      } yield id
    }

  /**
   * `minutes` of traffic for (mac, host) as consecutive 5-minute buckets starting at
   * `bucketOffset`. Passing the SAME offset for two hosts makes them concurrent — the case the
   * summed-per-host display double-counted. Byte volume is well above the default 10 KB heartbeat
   * floor so every row is real activity.
   */
  private def seedTraffic(
      routerId: RouterId,
      hostname: String,
      date: LocalDate,
      minutes: Int,
      bucketOffset: Int,
  ): ZIO[TrafficReportRepo, Throwable, Unit] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      val today0  = date.atStartOfDay(ZoneOffset.UTC).toInstant
      val inserts = (0 until (minutes / 5)).map { i =>
        val start = today0.plusSeconds((bucketOffset + i) * 300L)
        TrafficReportInsert(
          routerId,
          MacAddress.unsafe(testMac),
          None,
          HostId.Fqdn(Hostname.unsafe(hostname)),
          date,
          start,
          start.plusSeconds(300),
          300,
          500_000L,
          500_000L,
        )
      }.toList
      tr.insertBatch(inserts).unit
    }

  private def usageRoutes =
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
      auth            <- makeAuth
    } yield UsageRoutes.routes(
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
    )

  private def timeRoutes =
    for {
      profileRepo     <- ZIO.service[ProfileRepo]
      tlRepo          <- ZIO.service[TimeLimitRepo]
      atlRepo         <- ZIO.service[AppTimeLimitRepo]
      deviceRepo      <- ZIO.service[DeviceRepo]
      trafficRepo     <- ZIO.service[TrafficReportRepo]
      extRepo         <- ZIO.service[TimeExtensionRepo]
      userProfileRepo <- ZIO.service[UserProfileRepo]
      hsRepo          <- ZIO.service[HouseholdSettingsRepo]
      clock           <- ZIO.service[Clock]
      auth            <- makeAuth
      tss = new wifihaven.api.policy.TimeStatusServiceLive(
        profileRepo,
        tlRepo,
        atlRepo,
        deviceRepo,
        trafficRepo,
        extRepo,
      )
    } yield TimeRoutes.routes(
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

  def spec = suite("Per-app usage display ⇄ enforcement parity (#2744)")(
    test(
      "an app's concurrently-active hosts count ONCE: display minutes equal the enforced per-app minutes",
    ) {
      val today = TestClock.schoolDayAfternoon.toLocalDate
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        tlRepo      <- ZIO.service[TimeLimitRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        appRepo     <- ZIO.service[AppRepo]
        kidsId      <- TestLayers.seedKidsProfile(profileRepo)
        _           <- tlRepo.upsert(kidsId, 240)
        _           <- TestLayers.seedDevice(deviceRepo, testMac, "Kid Laptop", kidsId)
        routerId    <- seedRouter
        khanId      <- appRepo.create("Khan Academy", appSlug, None, None)
        _           <- appRepo.setHosts(
          khanId,
          List(Hostname.unsafe(apexHost), Hostname.unsafe(cdnHost)),
        )
        _      <- appRepo.upsertAssignment(khanId, kidsId, AppMode.TimeLimited, Some(120), false)
        soloId <- appRepo.create("Solo App", soloSlug, None, None)
        _      <- appRepo.setHosts(soloId, List(Hostname.unsafe(soloHost)))
        _      <- appRepo.upsertAssignment(soloId, kidsId, AppMode.TimeLimited, Some(120), false)
        // 20 minutes of Khan Academy, with the apex and the CDN host both active across the SAME
        // four buckets — one browsing session, two hosts.
        _      <- seedTraffic(routerId, apexHost, today, 20, 0)
        _      <- seedTraffic(routerId, cdnHost, today, 20, 0)
        // LIVENESS ANCHOR: 10 minutes on a single-host app, two hours later so nothing bridges.
        _      <- seedTraffic(routerId, soloHost, today, 10, 24)

        uRoutes <- usageRoutes
        tRoutes <- timeRoutes
        auth    <- makeAuth
        token   <- auth.login("admin", "changeme").map(_.token.value)

        byAppResp <- uRoutes.runZIO(
          Request
            .get(
              URL
                .decode(s"/api/profiles/${kidsId.value}/usage-by-app?from=$today&to=$today")
                .toOption
                .get,
            )
            .addHeader(Header.Authorization.Bearer(token)),
        )
        byAppBody <- byAppResp.body.asString
        byApp     <- ZIO.fromEither(byAppBody.fromJson[ProfileUsageByApp])

        statusResp <- tRoutes.runZIO(
          Request
            .get(
              URL
                .decode(s"/api/time/status?profileId=${kidsId.value}&date=$today")
                .toOption
                .get,
            )
            .addHeader(Header.Authorization.Bearer(token)),
        )
        statusBody <- statusResp.body.asString
        statuses   <- ZIO.fromEither(statusBody.fromJson[List[ProfileTimeStatus]])
      } yield {
        val status = statuses.head

        def displayMins(name: String): Int  =
          byApp.apps
            .find(_.appName == name)
            .map(a => (a.proportionalSeconds / 60L).toInt)
            .getOrElse(-1)
        def enforcedMins(slug: String): Int =
          status.appUsage.find(_.label == s"app:$slug").map(_.usedMins).getOrElse(-1)

        val khanDisplay  = displayMins("Khan Academy")
        val khanEnforced = enforcedMins(appSlug)
        val soloDisplay  = displayMins("Solo App")
        val soloEnforced = enforcedMins(soloSlug)

        assertTrue(byAppResp.status == Status.Ok) &&
        assertTrue(statusResp.status == Status.Ok) &&
        // LIVENESS ANCHOR: the rig really drove both surfaces, and they carry DIFFERENT, non-zero,
        // correct values per app — so the parity assertions below cannot pass on an empty fixture.
        assertTrue(soloEnforced == 10) &&
        assertTrue(soloDisplay == 10) &&
        assertTrue(khanEnforced == 20) &&
        // Reconciliation: 20 minutes of attributed traffic ⇒ ~20 engaged minutes, not 40. Summing
        // the two co-present hosts (the #2744 defect) yields 40 here.
        assertTrue(khanDisplay == 20) &&
        // ...and the two surfaces agree by construction, the invariant that must not drift.
        assertTrue(khanDisplay == khanEnforced)
      }
    },
    test(
      "an UNASSIGNED catalog app gets its own headline but is NOT a shared-host co-presence qualifier",
    ) {
      // #2744 guard. Unassigned catalog apps get their engaged seconds from a SEPARATE pass over
      // the same primitive, deliberately not folded into `distinctiveSpansByApp`. If they were
      // folded in they would become #1898 co-presence qualifiers, and a shared row whose only
      // co-present app is unassigned would move OUT of the orphan bucket into that app's host rows.
      // This pins both halves: the unassigned app still reports its own minutes, and the shared row
      // still lands in "Other".
      val today      = TestClock.schoolDayAfternoon.toLocalDate
      val loneHost   = "unassigned-app.example"
      val sharedHost = "shared-backend.example"
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        tlRepo      <- ZIO.service[TimeLimitRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        appRepo     <- ZIO.service[AppRepo]
        kidsId      <- TestLayers.seedKidsProfile(profileRepo)
        _           <- tlRepo.upsert(kidsId, 240)
        _           <- TestLayers.seedDevice(deviceRepo, testMac, "Kid Laptop", kidsId)
        routerId    <- seedRouter
        // NO assignment for this app — catalog entry only.
        loneId      <- appRepo.create("Unassigned App", "unassigned-app", None, None)
        _           <- appRepo.setHostEntries(
          loneId,
          List(
            AppHostEntry(Hostname.unsafe(loneHost), shared = false),
            AppHostEntry(Hostname.unsafe(sharedHost), shared = true),
          ),
        )
        // 15 minutes on the unassigned app's distinctive host, with the shared backend firing
        // inside that window — co-present with the ONLY candidate, which is unassigned.
        _           <- seedTraffic(routerId, loneHost, today, 15, 0)
        _           <- seedTraffic(routerId, sharedHost, today, 5, 1)

        uRoutes <- usageRoutes
        auth    <- makeAuth
        token   <- auth.login("admin", "changeme").map(_.token.value)
        resp    <- uRoutes.runZIO(
          Request
            .get(
              URL
                .decode(s"/api/profiles/${kidsId.value}/usage-by-app?from=$today&to=$today")
                .toOption
                .get,
            )
            .addHeader(Header.Authorization.Bearer(token)),
        )
        body    <- resp.body.asString
        out     <- ZIO.fromEither(body.fromJson[ProfileUsageByApp])
      } yield {
        val lone = out.apps.find(_.appName == "Unassigned App")
        assertTrue(resp.status == Status.Ok) &&
        // LIVENESS ANCHOR: the unassigned app really is reported, with its real distinctive
        // minutes — so the "not a qualifier" assertion below cannot pass on an empty response.
        assertTrue(lone.isDefined) &&
        assertTrue(lone.get.proportionalSeconds == 900L) &&
        // The shared row is NOT credited to it: its seconds stay in the orphan bucket.
        assertTrue(!lone.get.hosts.exists(_.host.value == sharedHost)) &&
        assertTrue(
          out.orphanHosts.exists(o => o.host.value == sharedHost && o.proportionalSeconds == 300L),
        )
      }
    },
    test(
      "a host distinctive on TWO apps counts for both headlines; the drill-down keeps the #1061 tiebreak",
    ) {
      // #2744 freezes a consequence of reading the canonical primitive. The old headline came from
      // the per-host sum, fed by an apex map that resolved a doubly-listed host with
      // `minBy(appId)` (#1061), so only the lowest appId was credited. Both headline paths now use
      // PER-APP distinctive host-sets with no tiebreak — which is what the per-app cap counts — so
      // the host counts for both apps. The `hosts` drill-down still applies the tiebreak, so the
      // losing app renders a headline with no host rows.
      //
      // No shipped app template has a host distinctive on two apps, so nothing regresses today;
      // this pins the intended behaviour before an operator-created app or a catalog pass makes it
      // reachable.
      val today    = TestClock.schoolDayAfternoon.toLocalDate
      val dualHost = "dual-listed.example"
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        tlRepo      <- ZIO.service[TimeLimitRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        appRepo     <- ZIO.service[AppRepo]
        kidsId      <- TestLayers.seedKidsProfile(profileRepo)
        _           <- tlRepo.upsert(kidsId, 240)
        _           <- TestLayers.seedDevice(deviceRepo, testMac, "Kid Laptop", kidsId)
        routerId    <- seedRouter
        lowId       <- appRepo.create("Lower AppId", "lower-appid", None, None)
        highId      <- appRepo.create("Higher AppId", "higher-appid", None, None)
        _           <- appRepo.setHosts(lowId, List(Hostname.unsafe(dualHost)))
        _           <- appRepo.setHosts(highId, List(Hostname.unsafe(dualHost)))
        _ <- appRepo.upsertAssignment(lowId, kidsId, AppMode.TimeLimited, Some(120), false)
        _ <- appRepo.upsertAssignment(highId, kidsId, AppMode.TimeLimited, Some(120), false)
        _ <- seedTraffic(routerId, dualHost, today, 10, 0)

        uRoutes <- usageRoutes
        auth    <- makeAuth
        token   <- auth.login("admin", "changeme").map(_.token.value)
        resp    <- uRoutes.runZIO(
          Request
            .get(
              URL
                .decode(s"/api/profiles/${kidsId.value}/usage-by-app?from=$today&to=$today")
                .toOption
                .get,
            )
            .addHeader(Header.Authorization.Bearer(token)),
        )
        body    <- resp.body.asString
        out     <- ZIO.fromEither(body.fromJson[ProfileUsageByApp])
      } yield {
        val low  = out.apps.find(_.appName == "Lower AppId")
        val high = out.apps.find(_.appName == "Higher AppId")
        assertTrue(resp.status == Status.Ok) &&
        // LIVENESS ANCHOR: both apps are present with the real 10 minutes, so the drill-down
        // assertions below cannot pass against a response that dropped one of them.
        assertTrue(low.isDefined) &&
        assertTrue(high.isDefined) &&
        assertTrue(low.get.proportionalSeconds == 600L) &&
        assertTrue(high.get.proportionalSeconds == 600L) &&
        // #1061 tiebreak survives on the drill-down: the host row goes to the lower appId only.
        assertTrue(low.get.hosts.map(_.host.value) == List(dualHost)) &&
        assertTrue(high.get.hosts.isEmpty)
      }
    },
  ) @@ TestAspect.sequential // DB-backed: clones the migration template into a fixed-named scratch DB.
}
