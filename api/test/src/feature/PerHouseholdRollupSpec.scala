package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.api.usage.{AmbientLearnJob, TimeUsedRollupJob}
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import doobie.Transactor
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.test.*

import java.time.{Instant, LocalDate, ZoneId, ZoneOffset}

/**
 * #2553: the all-tenant batch jobs (`TimeUsedRollupJob`, `AmbientLearnJob`) used to read the
 * household settings ONCE per tick — household #1's row — and apply it to every household they then
 * iterated. So household N's screen-time was bucketed on the operator household's `daily_reset_tz`
 * and gated by its heartbeat filter and ambient knobs.
 *
 * #2570 (blocked on #2553): `HouseholdSettingsRepoLive.update` deleted EVERY household's
 * `time_used_daily` / `app_used_daily` rows on any household's settings save — deliberately, while
 * the rollup derived every household's rows from household #1's settings. Once each household rolls
 * under its own settings that coupling is gone and the invalidation narrows to the writer.
 *
 * Every pin here runs through the real jobs and the real repos on embedded Postgres (no repo
 * mocks), with the tick instant injected rather than slept for.
 */
object PerHouseholdRollupSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Transactor[Task]] {

  override val bootstrap = TestDatabase.layer

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val macA = MacAddress.unsafe("aa:bb:cc:dd:ee:a1")
  private val macB = MacAddress.unsafe("aa:bb:cc:dd:ee:b1")

  /**
   * `minutes` worth of contiguous 5-minute buckets on `mac`/`host`, labelled with `date` (the
   * household-local day `RouterIngestService` stamps) and starting at `startUtc`.
   */
  private def seedTraffic(
      rid: RouterId,
      mac: MacAddress,
      host: String,
      date: LocalDate,
      startUtc: Instant,
      minutes: Int,
      bytesPerBucket: Long = 1_000_000L,
  ): ZIO[TrafficReportRepo, Throwable, Unit] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      val inserts = (0 until (minutes / 5)).map { i =>
        val start = startUtc.plusSeconds(i * 300L)
        TrafficReportInsert(
          rid,
          mac,
          None,
          HostId.Fqdn(Hostname.unsafe(host)),
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

  /** One 60-second bucket — the isolated-span shape the ambient learner reads. */
  private def seedBucket(
      rid: RouterId,
      mac: MacAddress,
      host: String,
      date: LocalDate,
      startUtc: Instant,
  ): ZIO[TrafficReportRepo, Throwable, Unit] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      tr.insertBatch(
        List(
          TrafficReportInsert(
            rid,
            mac,
            None,
            HostId.Fqdn(Hostname.unsafe(host)),
            date,
            startUtc,
            startUtc.plusSeconds(60),
            10,
            500_000L,
            500_000L,
          ),
        ),
      ).unit
    }

  private def setSettings(hh: HouseholdId)(
      f: HouseholdSettings => HouseholdSettings,
  ): ZIO[HouseholdSettingsRepo, Throwable, HouseholdSettings] =
    ZIO.serviceWithZIO[HouseholdSettingsRepo] { hsr =>
      for {
        cur <- hsr.getForHousehold(hh)
        upd = f(cur)
        _ <- hsr.update(hh, upd)
      } yield upd
    }

  private def rollupTick(now: Instant): ZIO[
    TimeUsedRollupRepo & AppUsedRollupRepo & ProfileRepo & DeviceRepo & AppTimeLimitRepo &
      TrafficReportRepo & HouseholdSettingsRepo,
    Throwable,
    Int,
  ] =
    for {
      ru  <- ZIO.service[TimeUsedRollupRepo]
      aru <- ZIO.service[AppUsedRollupRepo]
      pr  <- ZIO.service[ProfileRepo]
      dr  <- ZIO.service[DeviceRepo]
      atl <- ZIO.service[AppTimeLimitRepo]
      trr <- ZIO.service[TrafficReportRepo]
      hsr <- ZIO.service[HouseholdSettingsRepo]
      n   <- TimeUsedRollupJob.oneTickForTest(ru, aru, pr, dr, atl, trr, hsr, now)
    } yield n

  private def learnTick(now: Instant): ZIO[
    AmbientHostsRepo & ProfileRepo & DeviceRepo & AppTimeLimitRepo & TrafficReportRepo &
      HouseholdSettingsRepo,
    Throwable,
    Int,
  ] =
    for {
      ahr <- ZIO.service[AmbientHostsRepo]
      pr  <- ZIO.service[ProfileRepo]
      dr  <- ZIO.service[DeviceRepo]
      atl <- ZIO.service[AppTimeLimitRepo]
      trr <- ZIO.service[TrafficReportRepo]
      hsr <- ZIO.service[HouseholdSettingsRepo]
      n   <- AmbientLearnJob.oneTickForTest(ahr, pr, dr, atl, trr, hsr, now)
    } yield n

  def spec = suite("PerHouseholdRollupSpec (#2553 / #2570)")(
    test("#2553: each household's rollup lands on ITS OWN daily-reset tz's local date") {
      // now = 2026-07-01T23:30Z. Household A is UTC → its local day is Jul 1. Household B is
      // Asia/Tokyo (+09) → its local day is already Jul 2. Each household's traffic is labelled with
      // the day ITS OWN router-ingest path would have stamped, so a correct rollup writes A's row on
      // Jul 1 and B's on Jul 2. With one global settings read, B's presence is looked up on A's Jul 1
      // and B's row lands on the wrong date carrying zero seconds.
      for {
        _   <- cleanDb
        two <- TestLayers.seedTwoHouseholds(macA, macB)
        ru  <- ZIO.service[TimeUsedRollupRepo]
        _   <- setSettings(two.hhA)(_.copy(dailyResetTz = ZoneOffset.UTC))
        _   <- setSettings(two.hhB)(_.copy(dailyResetTz = ZoneId.of("Asia/Tokyo")))
        dayA = LocalDate.of(2026, 7, 1)
        dayB = LocalDate.of(2026, 7, 2)
        now  = Instant.parse("2026-07-01T23:30:00Z")
        _     <- seedTraffic(
          two.routerIdA,
          macA,
          "youtube.com",
          dayA,
          Instant.parse("2026-07-01T09:00:00Z"),
          30,
        )
        _     <- seedTraffic(
          two.routerIdB,
          macB,
          "youtube.com",
          dayB,
          Instant.parse("2026-07-01T23:00:00Z"),
          20,
        )
        _     <- rollupTick(now)
        aOwn  <- ru.getDayForProfile(two.profileA, dayA)
        bOwn  <- ru.getDayForProfile(two.profileB, dayB)
        bOnAs <- ru.getDayForProfile(two.profileB, dayA)
      } yield assertTrue(
        aOwn.exists(_.usedSeconds == 30L * 60L),
        // B rolls on ITS OWN local date, with its own minutes…
        bOwn.exists(_.usedSeconds == 20L * 60L),
        // …and nothing lands on the operator household's day key for B.
        bOnAs.isEmpty,
      )
    },
    test("#2553: each household's heartbeat filter gates ITS OWN rollup") {
      // Same tz for both, so the only variable is the filter. A suppresses everything below 5 MB
      // (its 1 MB buckets all qualify); B has the filter off and keeps its minutes. Under one global
      // settings read, A's filter ate B's traffic too.
      for {
        _   <- cleanDb
        two <- TestLayers.seedTwoHouseholds(macA, macB)
        ru  <- ZIO.service[TimeUsedRollupRepo]
        _   <- setSettings(two.hhA)(
          _.copy(
            dailyResetTz = ZoneOffset.UTC,
            heartbeatFilter = HeartbeatFilter(enabled = true, 5_000_000, Nil),
          ),
        )
        _   <- setSettings(two.hhB)(
          _.copy(dailyResetTz = ZoneOffset.UTC, heartbeatFilter = HeartbeatFilter.Off),
        )
        day = LocalDate.of(2026, 7, 1)
        now = Instant.parse("2026-07-01T20:00:00Z")
        _ <- seedTraffic(
          two.routerIdA,
          macA,
          "youtube.com",
          day,
          Instant.parse("2026-07-01T09:00:00Z"),
          30,
        )
        _ <- seedTraffic(
          two.routerIdB,
          macB,
          "youtube.com",
          day,
          Instant.parse("2026-07-01T09:00:00Z"),
          30,
        )
        _ <- rollupTick(now)
        a <- ru.getDayForProfile(two.profileA, day)
        b <- ru.getDayForProfile(two.profileB, day)
      } yield assertTrue(
        // A's own filter suppresses every one of its sub-threshold buckets…
        a.exists(_.usedSeconds == 0L),
        // …and B, which has no filter, keeps its full 30 minutes.
        b.exists(_.usedSeconds == 30L * 60L),
      )
    },
    test("#2553: each household's ambient isolation knob gates ITS OWN learning") {
      // Both households see one two-host isolated span. A's `ambient_isolation_max_hosts = 1` is too
      // tight to call it isolated; B's = 2 learns both hosts. Under one global settings read, A's
      // knob decided B's learning too.
      for {
        _   <- cleanDb
        two <- TestLayers.seedTwoHouseholds(macA, macB)
        ahr <- ZIO.service[AmbientHostsRepo]
        _   <- setSettings(two.hhA)(
          _.copy(dailyResetTz = ZoneOffset.UTC, ambientIsolationMaxHosts = 1),
        )
        sB  <- setSettings(two.hhB)(
          _.copy(dailyResetTz = ZoneOffset.UTC, ambientIsolationMaxHosts = 2),
        )
        day   = LocalDate.of(2026, 7, 1)
        start = Instant.parse("2026-07-01T03:00:00Z")
        _       <- seedBucket(two.routerIdA, macA, "a-one.example.com", day, start)
        _       <- seedBucket(two.routerIdA, macA, "a-two.example.com", day, start)
        _       <- seedBucket(two.routerIdB, macB, "b-one.example.com", day, start)
        _       <- seedBucket(two.routerIdB, macB, "b-two.example.com", day, start)
        learned <- learnTick(Instant.parse("2026-07-02T12:00:00Z"))
        window  <- ahr.listWindow(sB, LocalDate.of(2026, 7, 2))
        hosts = window.map(_.host).toSet
      } yield assertTrue(
        // exactly B's two hosts reached the shared baseline — A's span contributed nothing
        learned == 2,
        // B's knob admits its own two-host span…
        hosts.contains("b-one.example.com"),
        hosts.contains("b-two.example.com"),
        // …while A's tighter knob keeps A's span out, and does not reach across into B's.
        !hosts.contains("a-one.example.com"),
        !hosts.contains("a-two.example.com"),
      )
    },
    test("#2553: the ambient learn day is a shared label, not household #1's local yesterday") {
      // The baseline is one global (host, day) table, so the day LABEL is derived in UTC and shared
      // by every household — it is no longer whatever household #1's timezone made "yesterday".
      // now = 2026-07-01T23:00Z with household A (the operator household) in Asia/Tokyo: A's local
      // date is already Jul 2, so the old code would have learned Jul 1. The shared label is Jun 30.
      // Household B's device has an isolated span on each of the two days, so the two behaviours are
      // distinguishable by which host gets learned.
      for {
        _   <- cleanDb
        two <- TestLayers.seedTwoHouseholds(macA, macB)
        ahr <- ZIO.service[AmbientHostsRepo]
        _   <- setSettings(two.hhA)(_.copy(dailyResetTz = ZoneId.of("Asia/Tokyo")))
        sB  <- setSettings(two.hhB)(_.copy(dailyResetTz = ZoneOffset.UTC))
        jun30 = LocalDate.of(2026, 6, 30)
        jul1  = LocalDate.of(2026, 7, 1)
        _      <- seedBucket(
          two.routerIdB,
          macB,
          "b-jun30.example.com",
          jun30,
          Instant.parse("2026-06-30T03:00:00Z"),
        )
        _      <- seedBucket(
          two.routerIdB,
          macB,
          "b-jul1.example.com",
          jul1,
          Instant.parse("2026-07-01T03:00:00Z"),
        )
        _      <- learnTick(Instant.parse("2026-07-01T23:00:00Z"))
        window <- ahr.listWindow(sB, LocalDate.of(2026, 7, 2))
        hosts = window.map(_.host).toSet
      } yield assertTrue(
        // the shared UTC-derived label — Jun 30 — is what was learned…
        hosts.contains("b-jun30.example.com"),
        window.exists(r => r.host == "b-jun30.example.com" && r.lastIsolatedDay == jun30),
        // …not household #1's local yesterday, which was already Jul 1 in Tokyo.
        !hosts.contains("b-jul1.example.com"),
      )
    },
    test("#2570: a household's settings save invalidates ONLY its own rollup rows") {
      for {
        _   <- cleanDb
        two <- TestLayers.seedTwoHouseholds(macA, macB)
        ru  <- ZIO.service[TimeUsedRollupRepo]
        aru <- ZIO.service[AppUsedRollupRepo]
        ar  <- ZIO.service[AppRepo]
        hsr <- ZIO.service[HouseholdSettingsRepo]
        app <- ar.create("YouTube", "youtube", None, None)
        day       = LocalDate.of(2026, 7, 1)
        watermark = Instant.parse("2026-07-01T12:00:00Z")
        _     <- ru.upsertDay(two.profileA, day, RolledDay(600L, watermark))
        _     <- ru.upsertDay(two.profileB, day, RolledDay(900L, watermark))
        _     <- aru.upsertDay(two.profileA, app, day, RolledAppDay(300L, watermark))
        _     <- aru.upsertDay(two.profileB, app, day, RolledAppDay(450L, watermark))
        // Household B saves its settings — the write that used to wipe the whole table.
        sB    <- hsr.getForHousehold(two.hhB)
        _     <- hsr.update(two.hhB, sB.copy(notifyEmail = Some("b@example.com")))
        aRow  <- ru.getDayForProfile(two.profileA, day)
        bRow  <- ru.getDayForProfile(two.profileB, day)
        aApps <- aru.getDayForProfile(two.profileA, day)
        bApps <- aru.getDayForProfile(two.profileB, day)
      } yield assertTrue(
        // A's cache is untouched by B's write…
        aRow.exists(_.usedSeconds == 600L),
        aApps.get(app).exists(_.engagedSeconds == 300L),
        // …and B's own rows are still evicted, so its next tick refills under the new settings.
        bRow.isEmpty,
        bApps.isEmpty,
      )
    },
  ) @@ TestAspect.sequential
}
