package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.api.policy.*
import wifihaven.api.usage.TimeUsedRollupJob
import wifihaven.shared.*
import wifihaven.shared.Clock.TestClock
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.test.*

import java.time.{Instant, LocalDate, LocalDateTime, ZoneOffset}

/**
 * #1167: per-(profile, today, app) rollup sub-buckets that share the parent profile's
 * `rolled_through` watermark. These tests assert:
 *   - invariant: rollup_per_app(today) + tail == live_per_app(today)
 *   - fiber tick + immediate read reproduces live per-app exactly
 *   - past dates ignore the per-app rollup
 *   - app-assignment mutation invalidates the per-app rollup
 *   - household-settings change clears BOTH the profile-total and per-app tables
 *   - PolicyService.snapshot per-app cap evaluation reads through the cached path
 */
object TimeUsedAppRollupSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres] {

  override val bootstrap = TestDatabase.layer

  private def cleanDb = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

  private def makeService: ZIO[
    ProfileRepo & ScheduleRepo & TimeLimitRepo & SiteTimeLimitRepo & DeviceRepo &
      TrafficReportRepo & TimeExtensionRepo & TimeUsedRollupRepo & AppRepo & TimeUsedAppRollupRepo,
    Nothing,
    TimeStatusService,
  ] =
    for {
      pr   <- ZIO.service[ProfileRepo]
      sr   <- ZIO.service[ScheduleRepo]
      tlr  <- ZIO.service[TimeLimitRepo]
      stlr <- ZIO.service[SiteTimeLimitRepo]
      dr   <- ZIO.service[DeviceRepo]
      trr  <- ZIO.service[TrafficReportRepo]
      er   <- ZIO.service[TimeExtensionRepo]
      ru   <- ZIO.service[TimeUsedRollupRepo]
      ar   <- ZIO.service[AppRepo]
      aru  <- ZIO.service[TimeUsedAppRollupRepo]
    } yield new TimeStatusServiceLive(pr, sr, tlr, stlr, dr, trr, er, ru, Some(ar), aru)

  private def seedRouterRow: ZIO[RouterRepo, Throwable, RouterId] =
    ZIO.serviceWithZIO[RouterRepo](_.create("gw-tar", Sha256Hex.unsafe("a" * 64)))

  private def seedTraffic(
      routerId: RouterId,
      mac: String,
      hostname: String,
      date: LocalDate,
      minutes: Int,
      startOffsetMin: Int = 0,
  ): ZIO[TrafficReportRepo, Throwable, Unit] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      val buckets = minutes / 5
      val day0    = date.atStartOfDay(ZoneOffset.UTC).toInstant.plusSeconds(startOffsetMin * 60L)
      val inserts = (0 until buckets).map { i =>
        val start = day0.plusSeconds(i * 300L)
        TrafficReportInsert(
          routerId,
          MacAddress.unsafe(mac),
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

  // Create an app with the given hosts and return its id.
  private def seedApp(
      ar: AppRepo,
      name: String,
      slug: String,
      hosts: List[String],
  ): Task[AppId] =
    for {
      id <- ar.create(name, slug, None, None)
      _  <- ar.setHosts(id, hosts.map(Hostname.unsafe))
    } yield id

  def spec = suite("TimeUsedAppRollupSpec (#1167)")(
    test("invariant: rollup_per_app(today) + tail == live_per_app(today) for a multi-app fixture") {
      // Two apps on the kids profile. Watermark mid-day. Manually plant the
      // pre-watermark per-app rollup; the cached read must add the post-watermark
      // tail and yield the same numbers a full live aggregation would.
      val macA = "aa:bb:cc:dd:ee:50"
      val macB = "aa:bb:cc:dd:ee:51"
      for {
        _    <- cleanDb
        hsr  <- ZIO.service[HouseholdSettingsRepo]
        pr   <- ZIO.service[ProfileRepo]
        sr   <- ZIO.service[ScheduleRepo]
        dr   <- ZIO.service[DeviceRepo]
        ar   <- ZIO.service[AppRepo]
        aru  <- ZIO.service[TimeUsedAppRollupRepo]
        trr  <- ZIO.service[TrafficReportRepo]
        kid  <- TestLayers.seedKidsProfile(pr, sr)
        _    <- TestLayers.seedDevice(dr, macA, "kid-a", kid)
        _    <- TestLayers.seedDevice(dr, macB, "kid-b", kid)
        ytId <- seedApp(ar, "YouTube", "youtube", List("youtube.com"))
        ttId <- seedApp(ar, "TikTok", "tiktok", List("tiktok.com"))
        _    <- ar.upsertAssignment(ytId, kid, AppMode.TimeLimited, Some(60), false)
        _    <- ar.upsertAssignment(ttId, kid, AppMode.TimeLimited, Some(60), false)
        rid  <- seedRouterRow
        today = LocalDate.of(2025, 1, 6)
        // Spread of activity across both apps across the day.
        _   <- seedTraffic(rid, macA, "youtube.com", today, 25, 8 * 60)
        _   <- seedTraffic(rid, macB, "tiktok.com", today, 20, 10 * 60)
        _   <- seedTraffic(rid, macA, "youtube.com", today, 15, 13 * 60)
        _   <- seedTraffic(rid, macB, "tiktok.com", today, 10, 14 * 60)
        s   <- hsr.get
        // Get live per-app values across the full day.
        svc <- makeService
        now = LocalDateTime.of(2025, 1, 6, 15, 30).toInstant(ZoneOffset.UTC)
        live <- svc.appUsageAllLive(now, today, s)
        // Plant per-app rollup rows for the prefix up to 12:00.
        watermark = LocalDateTime.of(2025, 1, 6, 12, 0).toInstant(ZoneOffset.UTC)
        _        <- {
          for {
            devices  <- dr.listAll.map(_.filter(_.profileId.contains(kid)))
            mappings <- ar.listAllHostMappings
            appHosts: Map[AppId, List[Hostname]] = mappings
              .groupBy(_.appId)
              .view
              .mapValues(_.map(_.host))
              .toMap
            allPres <- trr.listPresenceRows(devices.map(_.mac), today)
            prefix = allPres.filter(_.periodStart.isBefore(watermark))
            perApp = TimeStatusService.usedSecondsByApp(devices, appHosts, prefix, s)
            _ <- aru.upsertBatch(
              today,
              appHosts.keys
                .map(aid => (kid, aid) -> RolledAppDay(perApp.getOrElse(aid, 0L), watermark))
                .toMap,
            )
          } yield ()
        }
        composed <- svc.appUsageAll(now, today, s)
      } yield assertTrue(composed(kid)(ytId) == live(kid)(ytId)) &&
        assertTrue(composed(kid)(ttId) == live(kid)(ttId)) &&
        assertTrue(live(kid)(ytId) > 0L) &&
        assertTrue(live(kid)(ttId) > 0L)
    },
    test("fiber tick + immediate read reproduces live per-app values exactly") {
      val mac = "aa:bb:cc:dd:ee:52"
      for {
        _    <- cleanDb
        hsr  <- ZIO.service[HouseholdSettingsRepo]
        pr   <- ZIO.service[ProfileRepo]
        sr   <- ZIO.service[ScheduleRepo]
        dr   <- ZIO.service[DeviceRepo]
        ar   <- ZIO.service[AppRepo]
        ru   <- ZIO.service[TimeUsedRollupRepo]
        aru  <- ZIO.service[TimeUsedAppRollupRepo]
        trr  <- ZIO.service[TrafficReportRepo]
        stl  <- ZIO.service[SiteTimeLimitRepo]
        kid  <- TestLayers.seedKidsProfile(pr, sr)
        _    <- TestLayers.seedDevice(dr, mac, "kid", kid)
        ytId <- seedApp(ar, "YouTube", "youtube", List("youtube.com"))
        _    <- ar.upsertAssignment(ytId, kid, AppMode.TimeLimited, Some(60), false)
        rid  <- seedRouterRow
        today = LocalDate.of(2025, 1, 6)
        _ <- seedTraffic(rid, mac, "youtube.com", today, 35, 9 * 60)
        now = LocalDateTime.of(2025, 1, 6, 12, 0).toInstant(ZoneOffset.UTC)
        s      <- hsr.get
        svc    <- makeService
        live   <- svc.appUsageAllLive(now, today, s)
        _      <- TimeUsedRollupJob.oneTickForTest(ru, aru, pr, dr, stl, trr, ar, hsr, now)
        cached <- svc.appUsageAll(now, today, s)
      } yield assertTrue(cached(kid)(ytId) == live(kid)(ytId)) &&
        assertTrue(live(kid)(ytId) > 0L)
    },
    test("past dates ignore the per-app rollup entirely") {
      for {
        _    <- cleanDb
        hsr  <- ZIO.service[HouseholdSettingsRepo]
        pr   <- ZIO.service[ProfileRepo]
        sr   <- ZIO.service[ScheduleRepo]
        ar   <- ZIO.service[AppRepo]
        aru  <- ZIO.service[TimeUsedAppRollupRepo]
        kid  <- TestLayers.seedKidsProfile(pr, sr)
        ytId <- seedApp(ar, "YouTube", "youtube", List("youtube.com"))
        _    <- ar.upsertAssignment(ytId, kid, AppMode.TimeLimited, Some(60), false)
        pastDate = LocalDate.of(2025, 1, 5)
        bogus    = Instant.parse("2025-01-05T23:59:59Z")
        _   <- aru.upsertDay(kid, ytId, pastDate, RolledAppDay(9_999L, bogus))
        s   <- hsr.get
        svc <- makeService
        now = LocalDateTime.of(2025, 1, 6, 12, 0).toInstant(ZoneOffset.UTC)
        out <- svc.appUsageAll(now, pastDate, s)
      } yield assertTrue(out.getOrElse(kid, Map.empty).getOrElse(ytId, 0L) == 0L)
    },
    test("app-assignment mutation invalidates the per-app rollup") {
      for {
        _    <- cleanDb
        hsr  <- ZIO.service[HouseholdSettingsRepo]
        pr   <- ZIO.service[ProfileRepo]
        sr   <- ZIO.service[ScheduleRepo]
        ar   <- ZIO.service[AppRepo]
        aru  <- ZIO.service[TimeUsedAppRollupRepo]
        kid  <- TestLayers.seedKidsProfile(pr, sr)
        ytId <- seedApp(ar, "YouTube", "youtube", List("youtube.com"))
        _    <- ar.upsertAssignment(ytId, kid, AppMode.TimeLimited, Some(60), false)
        today = LocalDate.of(2025, 1, 6)
        wm    = Instant.parse("2025-01-06T09:00:00Z")
        _    <- aru.upsertDay(kid, ytId, today, RolledAppDay(42L * 60L, wm))
        pre  <- aru.getDayMap(today)
        // Mutate an assignment — should clear the per-app rollup.
        _    <- ar.upsertAssignment(ytId, kid, AppMode.TimeLimited, Some(90), false)
        post <- aru.getDayMap(today)
      } yield assertTrue(pre.contains((kid, ytId))) && assertTrue(!post.contains((kid, ytId)))
    },
    test(
      "household-settings change clears BOTH time_used_daily and time_used_app_daily atomically",
    ) {
      for {
        _    <- cleanDb
        hsr  <- ZIO.service[HouseholdSettingsRepo]
        pr   <- ZIO.service[ProfileRepo]
        sr   <- ZIO.service[ScheduleRepo]
        ar   <- ZIO.service[AppRepo]
        ru   <- ZIO.service[TimeUsedRollupRepo]
        aru  <- ZIO.service[TimeUsedAppRollupRepo]
        kid  <- TestLayers.seedKidsProfile(pr, sr)
        ytId <- seedApp(ar, "YouTube", "youtube", List("youtube.com"))
        today = LocalDate.of(2025, 1, 6)
        wm    = Instant.parse("2025-01-06T09:00:00Z")
        _     <- ru.upsertDay(kid, today, RolledDay(50L * 60L, wm))
        _     <- aru.upsertDay(kid, ytId, today, RolledAppDay(30L * 60L, wm))
        preP  <- ru.getDayMap(today)
        preA  <- aru.getDayMap(today)
        cur   <- hsr.get
        _     <- hsr.update(cur.copy(heartbeatFilter = HeartbeatFilter(enabled = true, 999, Nil)))
        postP <- ru.getDayMap(today)
        postA <- aru.getDayMap(today)
      } yield assertTrue(preP.contains(kid)) &&
        assertTrue(preA.contains((kid, ytId))) &&
        assertTrue(!postP.contains(kid)) &&
        assertTrue(!postA.contains((kid, ytId)))
    },
    test("PolicyService.snapshot: per-app cap-exhausted block fires from the cached path") {
      // Set up a fixture where the per-app cache is the ONLY data source:
      // a rolled row at watermark = now (no tail will be read) with
      // used_seconds beyond the cap. No traffic_reports are seeded — the live
      // path would say 0. If the snapshot fires the block, it must have read
      // through the cache.
      val mac = "aa:bb:cc:dd:ee:60"
      for {
        _    <- cleanDb
        hsr  <- ZIO.service[HouseholdSettingsRepo]
        pr   <- ZIO.service[ProfileRepo]
        sr   <- ZIO.service[ScheduleRepo]
        dr   <- ZIO.service[DeviceRepo]
        ar   <- ZIO.service[AppRepo]
        aru  <- ZIO.service[TimeUsedAppRollupRepo]
        kid  <- TestLayers.seedKidsProfile(pr, sr)
        _    <- TestLayers.seedDevice(dr, mac, "kid", kid)
        ytId <- seedApp(ar, "YouTube", "youtube", List("youtube.com"))
        _    <- ar.upsertAssignment(ytId, kid, AppMode.TimeLimited, Some(30), false)
        s    <- hsr.get
        // Wall-clock time mid-day on 2025-01-06. `today` resolves under UTC.
        now   = LocalDateTime.of(2025, 1, 6, 12, 0).toInstant(ZoneOffset.UTC)
        today = PolicyService.householdLocalDate(now, s)
        // Plant per-app cache row for every existing profile so the read-path
        // dispatch stays on the cached branch (any miss falls through to live).
        // Kids id=3 (this test's), plus migration-seeded Kids(1) and Adults(2):
        // give the inactive profiles zero so only the test's kid is over cap.
        allProfiles <- pr.listAll
        _           <- ZIO.foreachDiscard(allProfiles)(p =>
          aru.upsertDay(
            p.id,
            ytId,
            today,
            if p.id == kid then RolledAppDay(35L * 60L, now) else RolledAppDay(0L, now),
          ),
        )
        // Build service with the AppRepo + per-app rollup repo so dispatch
        // routes to the cached path.
        tlr         <- ZIO.service[TimeLimitRepo]
        stl         <- ZIO.service[SiteTimeLimitRepo]
        blr         <- ZIO.service[BlocklistRepo]
        trr         <- ZIO.service[TrafficReportRepo]
        er          <- ZIO.service[TimeExtensionRepo]
        ru          <- ZIO.service[TimeUsedRollupRepo]
        // Profile-total rollup rows for ALL profiles so dayStateAll stays on
        // the cached path; the per-app block is what we're asserting, not the
        // profile-total block, so all-zero is fine.
        _   <- ZIO.foreachDiscard(allProfiles)(p => ru.upsertDay(p.id, today, RolledDay(0L, now)))
        ref <- Ref.make(LocalDateTime.ofInstant(now, ZoneOffset.UTC))
        clk = new Clock.TestClock(ref)
        tss = new TimeStatusServiceLive(pr, sr, tlr, stl, dr, trr, er, ru, Some(ar), aru)
        svc = new PolicyServiceLive(pr, sr, hsr, tlr, stl, dr, blr, trr, er, ar, tss, clk)
        // Sanity: the cache should have the row we planted, and the read path
        // should surface 35 min for (kid, ytId).
        cachedAll <- aru.getDayMap(today)
        usage     <- tss.appUsageAll(now, today, s)
        snap      <- svc.snapshot
        eb = snap.profiles(kid).rules.extraBlocked.map(_.value).toSet
      } yield assertTrue(cachedAll.contains((kid, ytId))) &&
        assertTrue(usage(kid)(ytId) == 35L * 60L) &&
        assertTrue(eb.contains("youtube.com"))
    },
  ) @@ TestAspect.sequential
}
