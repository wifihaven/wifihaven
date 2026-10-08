package wifihaven.api.feature

import wifihaven.api.{AppTemplates, JwtConfig}
import wifihaven.api.auth.*
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

import java.time.{LocalDate, ZoneOffset}

/**
 * #2863: inside ONE `GET /api/profiles/{id}/usage-by-app` response, an app's headline
 * (`proportionalSeconds`) and its drill-down (`hosts`) must apply the SAME suppression rule.
 *
 * Prod 2026-10-08, Kids profile: iMessage read 28m with `hosts: []`. The headline ran
 * `Presence.isHeartbeat` with the app's own host-set as the attribution context, so `ess.apple.com`
 * (on `InfraHosts.suppressOnly`) was claimed at equal specificity and counted; the drill-down ran
 * the same predicate with NO app context and suppressed it. Math Academy showed the byte-floor
 * twin: an app-claimed sub-floor row counted in the headline and vanished from the drill-down.
 *
 * The one rule both halves now share is the daily total's: a row counts iff `!isHeartbeat(row,
 * filter, TimeStatusService.appHostPatterns(<the profile's assignments>))`.
 *
 * LIVENESS ANCHORS: every absence asserted here ("iMessage reads 0", "no headline without hosts")
 * is paired with a non-zero reading off the same rig, so a fixture that generates no traffic, or a
 * route that returns an empty body, fails instead of passing for free.
 */
object UsageByAppSuppressionConsistencySpec
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

  private val testMac = "aa:bb:cc:dd:ee:63"

  // A real iMessage edge subdomain observed on prod (#1529), suffix-matched by the template's
  // `ess.apple.com` entry and by `InfraHosts.suppressOnly`'s `ess.apple.com`.
  private val essHost    = "query.ess.apple.com"
  private val anchorHost = "anchor-app.example"

  private def seedRouter: ZIO[RouterRepo, Throwable, RouterId] =
    ZIO.serviceWithZIO[RouterRepo] { rr =>
      for {
        id <- rr.create("test-router", Sha256Hex.unsafe("t" * 64))
        _  <- rr.completeEnrollment(id, Sha256Hex.unsafe("u" * 64))
      } yield id
    }

  /**
   * `minutes` of traffic for (mac, host) as consecutive 5-minute buckets starting at
   * `bucketOffset`, each bucket carrying `bytesPerBucket` (split in/out). 1 MB per bucket is far
   * above the 10 KB heartbeat floor; a few KB is below it but above the 256 B session anchor.
   */
  private def seedTraffic(
      routerId: RouterId,
      hostname: String,
      date: LocalDate,
      minutes: Int,
      bucketOffset: Int,
      bytesPerBucket: Long = 1_000_000L,
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
          bytesPerBucket / 2,
          bytesPerBucket / 2,
        )
      }.toList
      tr.insertBatch(inserts).unit
    }

  /** The shipped iMessage template's host-set, so the test tracks the real catalog entry. */
  private def seedIMessage(appRepo: AppRepo): ZIO[Any, Throwable, AppId] =
    for {
      templates <- AppTemplates.loadAll()
      imessage = templates.find(_.slug == AppTemplateId.unsafe("imessage")).get
      id <- appRepo.create("iMessage", "imessage", None, None)
      _  <- appRepo.setHosts(id, imessage.hosts)
    } yield id

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
      tss = new TimeStatusServiceLive(
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

  private def makePs =
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
      clock  <- ZIO.service[Clock]
    } yield PolicyServiceLive(pr, hsr, tlr, atlr, dr, blr, trRepo, er, ar, clock): PolicyService

  private def fetchByApp(pid: ProfileId, today: LocalDate) =
    for {
      uRoutes <- usageRoutes
      auth    <- makeAuth
      token   <- auth.login("admin", "changeme").map(_.token.value)
      resp    <- uRoutes.runZIO(
        Request
          .get(
            URL
              .decode(s"/api/profiles/${pid.value}/usage-by-app?from=$today&to=$today")
              .toOption
              .get,
          )
          .addHeader(Header.Authorization.Bearer(token)),
      )
      body    <- resp.body.asString
      out     <- ZIO.fromEither(body.fromJson[ProfileUsageByApp])
    } yield (resp.status, out)

  private def fetchUsedMins(pid: ProfileId, today: LocalDate) =
    for {
      tRoutes  <- timeRoutes
      auth     <- makeAuth
      token    <- auth.login("admin", "changeme").map(_.token.value)
      resp     <- tRoutes.runZIO(
        Request
          .get(URL.decode(s"/api/time/status?profileId=${pid.value}&date=$today").toOption.get)
          .addHeader(Header.Authorization.Bearer(token)),
      )
      body     <- resp.body.asString
      statuses <- ZIO.fromEither(body.fromJson[List[ProfileTimeStatus]])
    } yield statuses.head.usedMins

  /** The #2863 invariant: no app in the response has a headline with nothing behind it. */
  private def noHeadlineWithoutHosts(out: ProfileUsageByApp): Boolean =
    out.apps.forall(a => a.proportionalSeconds == 0L || a.hosts.nonEmpty)

  def spec = suite("usage-by-app: headline and drill-down share one suppression rule (#2863)")(
    test(
      "an UNASSIGNED app whose only host is suppressOnly reads 0 — and the daily total never counted it",
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
        // Catalog entries only — the prod shape: neither app is assigned to the profile.
        _           <- seedIMessage(appRepo)
        anchorId    <- appRepo.create("Anchor App", "anchor-app", None, None)
        _           <- appRepo.setHosts(anchorId, List(Hostname.unsafe(anchorHost)))
        // 30 minutes of substantial iMessage-edge traffic (the boot-burst shape), and 10 minutes
        // on the anchor app two hours later so nothing bridges.
        _           <- seedTraffic(routerId, essHost, today, 30, 0)
        _           <- seedTraffic(routerId, anchorHost, today, 10, 24)
        fetched     <- fetchByApp(kidsId, today)
        usedMins    <- fetchUsedMins(kidsId, today)
      } yield {
        val (status, out) = fetched
        val imessage      = out.apps.find(_.appName == "iMessage")
        val anchor        = out.apps.find(_.appName == "Anchor App")
        assertTrue(status == Status.Ok) &&
        // LIVENESS ANCHOR: the rig drove both surfaces with real, attributed traffic.
        assertTrue(anchor.exists(_.proportionalSeconds == 600L)) &&
        assertTrue(anchor.exists(_.hosts.map(_.host.value) == List(anchorHost))) &&
        assertTrue(usedMins == 10) &&
        // iMessage's only host is device background: no headline, no drill-down.
        assertTrue(imessage.forall(_.proportionalSeconds == 0L)) &&
        assertTrue(imessage.forall(_.hosts.isEmpty)) &&
        assertTrue(noHeadlineWithoutHosts(out))
      }
    },
    test(
      "an ASSIGNED app's sub-floor row that its headline counts appears in its drill-down (Math Academy shape)",
    ) {
      val today     = TestClock.schoolDayAfternoon.toLocalDate
      val quietHost = "quiet-app.example"
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
        quietId     <- appRepo.create("Quiet App", "quiet-app", None, None)
        _           <- appRepo.setHosts(quietId, List(Hostname.unsafe(quietHost)))
        _           <- appRepo.upsertAssignment(quietId, kidsId, AppMode.Allowed, None, true)
        // 10 minutes at 4 KB per bucket: below the 10 KB heartbeat floor, above the 256 B anchor.
        // The app claims the host, so the byte floor does not apply to it (#1506).
        _           <- seedTraffic(routerId, quietHost, today, 10, 0, bytesPerBucket = 4_000L)
        fetched     <- fetchByApp(kidsId, today)
      } yield {
        val (status, out) = fetched
        val quiet         = out.apps.find(_.appName == "Quiet App")
        assertTrue(status == Status.Ok) &&
        // LIVENESS ANCHOR: the headline really counts the sub-floor traffic.
        assertTrue(quiet.exists(_.proportionalSeconds == 600L)) &&
        // ...and the drill-down accounts for it under the same rule.
        assertTrue(
          quiet.exists(
            _.hosts.map(h => (h.host.value, h.proportionalMins)) == List(quietHost -> 10),
          ),
        ) &&
        assertTrue(noHeadlineWithoutHosts(out))
      }
    },
    test(
      "an ASSIGNED iMessage keeps counting ess.apple.com (#2815 equal specificity) — and now shows it",
    ) {
      // #2815 deliberately lets an app that names an infra host EXACTLY keep attributing it, and
      // names iMessage as the live instance. This change does not revisit that; it only makes the
      // drill-down agree with the headline instead of hiding the host.
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
        imId        <- seedIMessage(appRepo)
        _           <- appRepo.upsertAssignment(imId, kidsId, AppMode.Allowed, None, true)
        _           <- seedTraffic(routerId, essHost, today, 15, 0)
        fetched     <- fetchByApp(kidsId, today)
      } yield {
        val (status, out) = fetched
        val imessage      = out.apps.find(_.appName == "iMessage")
        assertTrue(status == Status.Ok) &&
        assertTrue(imessage.exists(_.proportionalSeconds == 900L)) &&
        assertTrue(imessage.exists(_.hosts.map(_.host.value) == List(essHost))) &&
        assertTrue(noHeadlineWithoutHosts(out))
      }
    },
    test(
      "iMessage BLOCKING is unchanged: a Blocked assignment still puts ess.apple.com in extraBlocked",
    ) {
      // Counting and blocking are separate: `ess.apple.com` is suppressed as background for
      // counting and is still the iMessage block handle (README's collateral-aware example).
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        appRepo     <- ZIO.service[AppRepo]
        kidsId      <- TestLayers.seedKidsProfile(profileRepo)
        imId        <- seedIMessage(appRepo)
        _           <- appRepo.upsertAssignment(imId, kidsId, AppMode.Blocked, None, true)
        svc         <- makePs
        snap        <- svc.snapshot
      } yield {
        val eb = snap.profiles(kidsId).rules.extraBlocked.map(_.value).toSet
        assertTrue(eb.contains("ess.apple.com")) &&
        assertTrue(!eb.exists(_.contains("push.apple.com")))
      }
    },
  ) @@ TestAspect.sequential // DB-backed: clones the migration template into a fixed-named scratch DB.
}
