package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.api.policy.*
import wifihaven.api.usage.{AppUsedRollupServiceLive, TimeUsedRollupJob}
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.test.*

import java.time.{LocalDate, LocalDateTime, ZoneOffset}

/**
 * #1516 (sub-issue of #1510, App-level usage aggregation): the per-(profile, app, date) engaged-
 * minutes rollup builder + read accessor. These feature tests assert the reconciliation invariant
 * the per-app series (#1517) relies on, against embedded Postgres (no repo mocks):
 *
 *   - rollup ⇄ cap (EXACT): a multi-host app's engaged minutes via the reader equal the single
 *     per-app primitive (`Presence.appSecondsForProfile`, surfaced as the per-app cap aggregate
 *     `AppDayState.usedMinutes`), including cross-host idle-gap bridging.
 *   - rolled+tail ⇄ live (EXACT): a planted rollup row + the live tail past the watermark sum to
 *     the all-live computation, with the watermark landing in an idle gap.
 *   - per-app sum ⇄ profile total (restricted equality): for non-exempt, non-overlapping apps with
 *     no non-app traffic, Σ app_used_daily.engaged_seconds == time_used_daily.used_seconds.
 *   - the re-aggregation tick is idempotent.
 */
object AppUsedRollupSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres] {

  override val bootstrap = TestDatabase.layer

  private val cleanDb = TestDatabase.cleanAndMigrate

  private def makeReader: ZIO[
    ProfileRepo & DeviceRepo & AppTimeLimitRepo & AppRepo & TrafficReportRepo & AppUsedRollupRepo,
    Nothing,
    AppUsedRollupServiceLive,
  ] =
    for {
      pr  <- ZIO.service[ProfileRepo]
      dr  <- ZIO.service[DeviceRepo]
      stl <- ZIO.service[AppTimeLimitRepo]
      ar  <- ZIO.service[AppRepo]
      trr <- ZIO.service[TrafficReportRepo]
      aru <- ZIO.service[AppUsedRollupRepo]
    } yield new AppUsedRollupServiceLive(pr, dr, stl, trr, aru)

  private def makeTimeService: ZIO[
    ProfileRepo & ScheduleRepo & TimeLimitRepo & AppTimeLimitRepo & DeviceRepo & TrafficReportRepo &
      TimeExtensionRepo & TimeUsedRollupRepo,
    Nothing,
    TimeStatusService,
  ] =
    for {
      pr   <- ZIO.service[ProfileRepo]
      sr   <- ZIO.service[ScheduleRepo]
      tlr  <- ZIO.service[TimeLimitRepo]
      atlr <- ZIO.service[AppTimeLimitRepo]
      dr   <- ZIO.service[DeviceRepo]
      trr  <- ZIO.service[TrafficReportRepo]
      er   <- ZIO.service[TimeExtensionRepo]
      ru   <- ZIO.service[TimeUsedRollupRepo]
    } yield new TimeStatusServiceLive(pr, sr, tlr, atlr, dr, trr, er, ru)

  private def seedRouterRow: ZIO[RouterRepo, Throwable, RouterId] =
    ZIO.serviceWithZIO[RouterRepo](_.create("gw-app", Sha256Hex.unsafe("a" * 64)))

  // A registered app with `slug` and an explicit host-set, assigned `time_limited` to the profile.
  private def seedApp(
      appRepo: AppRepo,
      profileId: ProfileId,
      slug: String,
      hosts: List[String],
      dailyMinutes: Int,
      exemptFromDaily: Boolean = false,
  ): Task[AppId] =
    for {
      id <- appRepo.create(slug, slug, None, None)
      _  <- appRepo.setHosts(id, hosts.map(Hostname.unsafe))
      _  <- appRepo.upsertAssignment(
        id,
        profileId,
        AppMode.TimeLimited,
        Some(dailyMinutes),
        exemptFromDaily,
      )
    } yield id

  // `minutes/5` consecutive 5-min fully-active buckets starting `startOffsetMin` after midnight UTC.
  private def seedTraffic(
      routerId: RouterId,
      mac: String,
      hostname: String,
      date: LocalDate,
      minutes: Int,
      startOffsetMin: Int,
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

  private def setTz(hsr: HouseholdSettingsRepo, tz: String): Task[HouseholdSettings] =
    for {
      cur <- hsr.get
      upd = cur.copy(dailyResetTz = java.time.ZoneId.of(tz))
      _ <- hsr.update(upd)
    } yield upd

  def spec = suite("AppUsedRollupSpec (#1516)")(
    test(
      "multi-host app: reader engaged-minutes == per-app cap aggregate, with cross-host bridge",
    ) {
      // social.com @08:00 (5 min) then cdn.social.net @08:10 (5 min). The 300 s cross-host idle gap
      // is < the effective gap (2×R = 600 s for 300 s buckets), so the two different hosts of the
      // SAME app bridge into one [08:00, 08:15] engaged span = 15 min — NOT 5+5. The reader and the
      // per-app cap aggregate must both read that bridged 15.
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        sr  <- ZIO.service[ScheduleRepo]
        dr  <- ZIO.service[DeviceRepo]
        ar  <- ZIO.service[AppRepo]
        ru  <- ZIO.service[TimeUsedRollupRepo]
        aru <- ZIO.service[AppUsedRollupRepo]
        stl <- ZIO.service[AppTimeLimitRepo]
        trr <- ZIO.service[TrafficReportRepo]
        s   <- setTz(hsr, "UTC")
        kid <- TestLayers.seedKidsProfile(pr, sr)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:dd:ee:60", "kid", kid)
        app <- seedApp(ar, kid, "social", List("social.com", "cdn.social.net"), 60)
        rid <- seedRouterRow
        today = LocalDate.of(2025, 1, 6)
        _ <- seedTraffic(rid, "aa:bb:cc:dd:ee:60", "social.com", today, 5, 8 * 60)
        _ <- seedTraffic(rid, "aa:bb:cc:dd:ee:60", "cdn.social.net", today, 5, 8 * 60 + 10)
        now = LocalDateTime.of(2025, 1, 6, 12, 0).toInstant(ZoneOffset.UTC)
        _      <- TimeUsedRollupJob.oneTickForTest(ru, aru, pr, dr, stl, trr, hsr, now)
        reader <- makeReader
        perApp <- reader.appEngagedMinutes(now, today, s, kid)
        // The per-app cap aggregate (AppDayState.usedMinutes) for the same app.
        tsvc   <- makeTimeService
        state  <- tsvc.dayStateLive(now, today, s, kid)
        capMin = state.flatMap(_.perApp.find(_.label == "app:social").map(_.usedMinutes))
      } yield assertTrue(perApp.get(app).contains(15)) &&
        assertTrue(capMin.contains(15))
    },
    test("rolled + live-tail across the watermark == all-live engaged minutes") {
      // youtube.com active 08:00–08:25 then 10:00–10:30. Watermark 09:00 lands in the idle gap, so
      // no session straddles it: rolled (25 min) + tail (30 min) == all-live (55 min).
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        sr  <- ZIO.service[ScheduleRepo]
        dr  <- ZIO.service[DeviceRepo]
        ar  <- ZIO.service[AppRepo]
        aru <- ZIO.service[AppUsedRollupRepo]
        trr <- ZIO.service[TrafficReportRepo]
        s   <- setTz(hsr, "UTC")
        kid <- TestLayers.seedKidsProfile(pr, sr)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:dd:ee:61", "kid", kid)
        app <- seedApp(ar, kid, "yt", List("youtube.com"), 240)
        rid <- seedRouterRow
        today = LocalDate.of(2025, 1, 6)
        _ <- seedTraffic(rid, "aa:bb:cc:dd:ee:61", "youtube.com", today, 25, 8 * 60)
        _ <- seedTraffic(rid, "aa:bb:cc:dd:ee:61", "youtube.com", today, 30, 10 * 60)
        now       = LocalDateTime.of(2025, 1, 6, 12, 0).toInstant(ZoneOffset.UTC)
        watermark = LocalDateTime.of(2025, 1, 6, 9, 0).toInstant(ZoneOffset.UTC)
        reader   <- makeReader
        // Rollup empty → all-live path.
        live     <- reader.appEngagedMinutes(now, today, s, kid)
        // Plant the rolled prefix (the 25-min first window) and read via rolled+tail.
        _        <- aru.upsertDay(kid, app, today, RolledAppDay(25L * 60L, watermark))
        composed <- reader.appEngagedMinutes(now, today, s, kid)
      } yield assertTrue(live.get(app).contains(55)) &&
        assertTrue(composed.get(app).contains(55))
    },
    test("per-app rollup sum == profile total for non-exempt, non-overlapping apps") {
      // Two non-exempt apps, single device, Sum mode, disjoint windows and no non-app traffic — the
      // restricted case where the per-app series partitions the profile total exactly. Both rollups
      // are written by the same tick from the same presence batch.
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        sr  <- ZIO.service[ScheduleRepo]
        dr  <- ZIO.service[DeviceRepo]
        ar  <- ZIO.service[AppRepo]
        ru  <- ZIO.service[TimeUsedRollupRepo]
        aru <- ZIO.service[AppUsedRollupRepo]
        stl <- ZIO.service[AppTimeLimitRepo]
        trr <- ZIO.service[TrafficReportRepo]
        _   <- setTz(hsr, "UTC")
        kid <- TestLayers.seedKidsProfile(pr, sr)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:dd:ee:62", "kid", kid)
        _   <- seedApp(ar, kid, "a", List("a.com"), 60)
        _   <- seedApp(ar, kid, "b", List("b.com"), 60)
        rid <- seedRouterRow
        today = LocalDate.of(2025, 1, 6)
        _ <- seedTraffic(rid, "aa:bb:cc:dd:ee:62", "a.com", today, 10, 8 * 60)
        _ <- seedTraffic(rid, "aa:bb:cc:dd:ee:62", "b.com", today, 15, 9 * 60)
        now = LocalDateTime.of(2025, 1, 6, 12, 0).toInstant(ZoneOffset.UTC)
        _       <- TimeUsedRollupJob.oneTickForTest(ru, aru, pr, dr, stl, trr, hsr, now)
        timeRow <- ru.getDayForProfile(kid, today)
        appRows <- aru.getDayForProfile(kid, today)
        appSum = appRows.values.map(_.engagedSeconds).sum
      } yield assertTrue(timeRow.exists(_.usedSeconds == 1500L)) &&
        assertTrue(appSum == 1500L) &&
        assertTrue(appRows.size == 2)
    },
    test(
      "#1564: AppTimeLimit + AppDayState carry the FK appId, not just the `app:<slug>` label",
    ) {
      // The cap surface keys on `apps.id` end-to-end so consumers no longer have to parse
      // `label.stripPrefix(\"app:\")` and re-resolve a slug. The repo carries `a.id` straight
      // through from the join (it was already in scope), and the per-app DayState exposes it
      // alongside the slug-derived label (the label stays for SPA back-compat).
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        sr  <- ZIO.service[ScheduleRepo]
        dr  <- ZIO.service[DeviceRepo]
        ar  <- ZIO.service[AppRepo]
        stl <- ZIO.service[AppTimeLimitRepo]
        trr <- ZIO.service[TrafficReportRepo]
        s   <- setTz(hsr, "UTC")
        kid <- TestLayers.seedKidsProfile(pr, sr)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:dd:ee:64", "kid", kid)
        app <- seedApp(ar, kid, "fkapp", List("fkapp.com"), 60)
        rid <- seedRouterRow
        today = LocalDate.of(2025, 1, 6)
        _    <- seedTraffic(rid, "aa:bb:cc:dd:ee:64", "fkapp.com", today, 10, 8 * 60)
        atls <- stl.listForProfile(kid)
        // Every emitted AppTimeLimit row carries the registered apps.id directly.
        _   = atls
        now = LocalDateTime.of(2025, 1, 6, 12, 0).toInstant(ZoneOffset.UTC)
        tsvc  <- makeTimeService
        state <- tsvc.dayStateLive(now, today, s, kid)
        perApp = state.map(_.perApp).getOrElse(Nil)
      } yield assertTrue(atls.nonEmpty) &&
        assertTrue(atls.forall(_.appId == app)) &&
        assertTrue(perApp.exists(_.appId == app)) &&
        assertTrue(perApp.find(_.appId == app).map(_.usedMinutes).contains(10))
    },
    test("re-running the aggregation tick is idempotent") {
      for {
        _   <- cleanDb
        hsr <- ZIO.service[HouseholdSettingsRepo]
        pr  <- ZIO.service[ProfileRepo]
        sr  <- ZIO.service[ScheduleRepo]
        dr  <- ZIO.service[DeviceRepo]
        ar  <- ZIO.service[AppRepo]
        ru  <- ZIO.service[TimeUsedRollupRepo]
        aru <- ZIO.service[AppUsedRollupRepo]
        stl <- ZIO.service[AppTimeLimitRepo]
        trr <- ZIO.service[TrafficReportRepo]
        _   <- setTz(hsr, "UTC")
        kid <- TestLayers.seedKidsProfile(pr, sr)
        _   <- TestLayers.seedDevice(dr, "aa:bb:cc:dd:ee:63", "kid", kid)
        _   <- seedApp(ar, kid, "yt", List("youtube.com"), 240)
        rid <- seedRouterRow
        today = LocalDate.of(2025, 1, 6)
        _ <- seedTraffic(rid, "aa:bb:cc:dd:ee:63", "youtube.com", today, 20, 8 * 60)
        now = LocalDateTime.of(2025, 1, 6, 12, 0).toInstant(ZoneOffset.UTC)
        _      <- TimeUsedRollupJob.oneTickForTest(ru, aru, pr, dr, stl, trr, hsr, now)
        first  <- aru.getDayForProfile(kid, today)
        _      <- TimeUsedRollupJob.oneTickForTest(ru, aru, pr, dr, stl, trr, hsr, now)
        second <- aru.getDayForProfile(kid, today)
      } yield assertTrue(first.nonEmpty) && assertTrue(first == second)
    },
  ) @@ TestAspect.sequential
}
