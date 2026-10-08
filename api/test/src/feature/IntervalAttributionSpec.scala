package wifihaven.api.feature

import doobie.*
import doobie.implicits.*
import wifihaven.api.db.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.api.policy.*
import wifihaven.api.usage.{
  AppUsedRollupServiceLive,
  AttributionScope,
  AttributionSpan,
  TimeUsedRollupJob,
}
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.interop.catz.*
import zio.test.*

import java.time.{Instant, LocalDate, LocalDateTime, LocalTime, ZoneOffset}

/**
 * #2844 (epic #2841, design `docs/design/shared-devices.md` §6): usage is attributed to the profile
 * whose assignment interval contains each presence row's `period_start`, not to the device's
 * CURRENT profile.
 *
 * Fixture: profiles A and B, household tz UTC, day 2025-01-08. Device `moved` is on A from the
 * fixture clock (2025-01-06) and is reassigned to B at 14:00. It is active 10:00-10:30 (inside A's
 * interval) and 15:00-15:20 (inside B's). Device `stay` is on A throughout, active 08:00-08:15.
 *
 * Expected on every path: A = 30 + 15 = 45 minutes, B = 20. Attributing by current profile (the
 * pre-#2844 behaviour) gives A = 15, B = 50. Every expected value is non-zero, so a read that
 * returned nothing cannot pass.
 */
object IntervalAttributionSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Transactor[Task]] {

  override val bootstrap = TestDatabase.layer

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val day                    = LocalDate.of(2025, 1, 8)
  private def at(h: Int, m: Int = 0) =
    LocalDateTime.of(day, LocalTime.of(h, m)).toInstant(ZoneOffset.UTC)

  private val movedMac = MacAddress.unsafe("aa:bb:cc:dd:28:44")
  private val stayMac  = MacAddress.unsafe("aa:bb:cc:dd:28:45")

  // `minutes` of consecutive, fully active 5-minute report periods on `mac` starting at `start`.
  private def seedTraffic(
      routerId: RouterId,
      mac: MacAddress,
      start: Instant,
      minutes: Int,
  ): ZIO[TrafficReportRepo, Throwable, Unit] =
    ZIO.serviceWithZIO[TrafficReportRepo] { tr =>
      val inserts = (0 until minutes / 5).map { i =>
        val s = start.plusSeconds(i * 300L)
        TrafficReportInsert(
          routerId,
          mac,
          None,
          HostId.Fqdn(Hostname.unsafe("youtube.com")),
          s.atZone(ZoneOffset.UTC).toLocalDate,
          s,
          s.plusSeconds(300),
          300,
          500_000L,
          500_000L,
        )
      }.toList
      tr.insertBatch(inserts).unit
    }

  final case class Fixture(
      a: ProfileId,
      b: ProfileId,
      moved: DeviceId,
      stay: DeviceId,
      settings: HouseholdSettings,
      router: RouterId,
  )

  private val seed = for {
    _   <- cleanDb
    hsr <- ZIO.service[HouseholdSettingsRepo]
    pr  <- ZIO.service[ProfileRepo]
    dr  <- ZIO.service[DeviceRepo]
    dar <- ZIO.service[DeviceAssignmentRepo]
    ar  <- ZIO.service[AppRepo]
    cur <- hsr.getForHousehold(HouseholdId.Default)
    settings = cur.copy(dailyResetTz = java.time.ZoneId.of("UTC"))
    _     <- hsr.update(HouseholdId.Default, settings)
    a     <- pr.create("A", Nil)
    b     <- pr.create("B", Nil)
    // A per-app limit on both profiles, so the per-app minutes (`app_used_daily`) are checked too.
    _     <- TestLayers.seedAppAssignment(ar, a, "youtube.com", AppMode.TimeLimited, Some(600))
    _     <- TestLayers.seedAppAssignment(ar, b, "youtube.com", AppMode.TimeLimited, Some(600))
    moved <- TestLayers.seedDevice(dr, movedMac.value, "moved", a)
    stay  <- TestLayers.seedDevice(dr, stayMac.value, "stay", a)
    rid   <- ZIO.serviceWithZIO[RouterRepo](_.create("gw-2844", Sha256Hex.unsafe("i" * 64)))
    _     <- seedTraffic(rid, stayMac, at(8), 15)
    _     <- seedTraffic(rid, movedMac, at(10), 30)
    _     <- dar.assign(
      HouseholdId.Default,
      moved,
      Some(b),
      at(14),
      AssignmentKind.Assigned,
      None,
      AssignmentEndCause.Reassigned,
    )
    _     <- seedTraffic(rid, movedMac, at(15), 20)
  } yield Fixture(a, b, moved, stay, settings, rid)

  // The REAL service with the REAL per-app rollup reader, so the today path is rollup + tail for
  // both the profile total and the per-app minutes.
  private val fullService = for {
    pr  <- ZIO.service[ProfileRepo]
    tlr <- ZIO.service[TimeLimitRepo]
    atl <- ZIO.service[AppTimeLimitRepo]
    dr  <- ZIO.service[DeviceRepo]
    trr <- ZIO.service[TrafficReportRepo]
    er  <- ZIO.service[TimeExtensionRepo]
    ru  <- ZIO.service[TimeUsedRollupRepo]
    nsr <- ZIO.service[NamedScheduleRepo]
    aru <- ZIO.service[AppUsedRollupRepo]
  } yield new TimeStatusServiceLive(
    pr,
    tlr,
    atl,
    dr,
    trr,
    er,
    ru,
    nsr,
    new AppUsedRollupServiceLive(pr, dr, atl, trr, aru),
  )

  private def appMins(s: ProfileDayState): Int = s.perApp.map(_.usedMinutes).sum

  def spec = suite("IntervalAttributionSpec (#2844)")(
    test("the scope read returns each profile's spans, bounded by the window it overlaps") {
      for {
        f  <- seed
        dr <- ZIO.service[DeviceRepo]
        window = AttributionScope.dayWindow(day, f.settings)
        scope <- dr.attributionScope(HouseholdId.Default, window._1, window._2)
        prev  <- dr.attributionScope(HouseholdId.Default, window._1.minusSeconds(86400), window._1)
        next  <- dr.attributionScope(HouseholdId.Default, window._2, window._2.plusSeconds(86400))
        fixtureStart = Some(LocalDateTime.of(2025, 1, 6, 0, 0).toInstant(ZoneOffset.UTC))
      } yield assertTrue(
        window == (at(0), at(0).plusSeconds(86400)),
        scope.byProfile(f.a).toSet == Set(
          AttributionSpan(movedMac, f.moved, fixtureStart, Some(at(14))),
          AttributionSpan(stayMac, f.stay, fixtureStart, None),
        ),
        scope.byProfile(f.b) == List(AttributionSpan(movedMac, f.moved, Some(at(14)), None)),
        // The day before the move only A held `moved`; the day after, only B.
        prev.byProfile.get(f.b).isEmpty,
        prev.byProfile(f.a).map(_.deviceId).toSet == Set(f.moved, f.stay),
        next.byProfile(f.a).map(_.deviceId) == List(f.stay),
        next.byProfile(f.b).map(_.deviceId) == List(f.moved),
      )
    },
    test("live path: pre-move usage stays with A, post-move usage goes to B") {
      for {
        f   <- seed
        svc <- fullService
        now = at(16)
        all  <- svc.dayStateAllLive(HouseholdId.Default, now, day, f.settings)
        oneA <- svc.dayStateLive(HouseholdId.Default, now, day, f.settings, f.a).someOrFailException
        oneB <- svc.dayStateLive(HouseholdId.Default, now, day, f.settings, f.b).someOrFailException
      } yield assertTrue(
        all(f.a).usedMinutes == 45,
        all(f.b).usedMinutes == 20,
        oneA.usedMinutes == 45,
        oneB.usedMinutes == 20,
        appMins(oneA) == 45,
        appMins(oneB) == 20,
      )
    },
    test("rollup + tail path: a tick after the move, then more usage, keeps the split") {
      for {
        f      <- seed
        ru     <- ZIO.service[TimeUsedRollupRepo]
        aru    <- ZIO.service[AppUsedRollupRepo]
        pr     <- ZIO.service[ProfileRepo]
        dr     <- ZIO.service[DeviceRepo]
        atl    <- ZIO.service[AppTimeLimitRepo]
        trr    <- ZIO.service[TrafficReportRepo]
        hsr    <- ZIO.service[HouseholdSettingsRepo]
        // Tick at 15:30 (after the move, after B's first session), then 10 more minutes land on B
        // after the watermark, so the read is a rolled prefix plus a live tail on both profiles.
        _      <- TimeUsedRollupJob.oneTickForTest(ru, aru, pr, dr, atl, trr, hsr, at(15, 30))
        rolled <- ru.getDayMapForHousehold(HouseholdId.Default, day)
        _      <- seedTraffic(f.router, movedMac, at(15, 40), 10)
        svc    <- fullService
        now = at(16)
        all  <- svc.dayStateAll(HouseholdId.Default, now, day, f.settings)
        oneA <- svc.dayState(HouseholdId.Default, now, day, f.settings, f.a).someOrFailException
        oneB <- svc.dayState(HouseholdId.Default, now, day, f.settings, f.b).someOrFailException
        live <- svc.dayStateAllLive(HouseholdId.Default, now, day, f.settings)
      } yield assertTrue(
        // The tick wrote the split itself, not just the read.
        rolled(f.a).usedSeconds == 45L * 60,
        rolled(f.b).usedSeconds == 20L * 60,
        all(f.a).usedMinutes == 45,
        all(f.b).usedMinutes == 30,
        oneA.usedMinutes == 45,
        oneB.usedMinutes == 30,
        appMins(oneA) == 45,
        appMins(oneB) == 30,
        live(f.a).usedMinutes == 45,
        live(f.b).usedMinutes == 30,
      )
    },
    test("past day: reading the move day from the next day keeps the split") {
      for {
        f   <- seed
        svc <- fullService
        now = at(16).plusSeconds(86400)
        all  <- svc.dayStateAll(HouseholdId.Default, now, day, f.settings)
        oneA <- svc.dayState(HouseholdId.Default, now, day, f.settings, f.a).someOrFailException
        oneB <- svc.dayState(HouseholdId.Default, now, day, f.settings, f.b).someOrFailException
      } yield assertTrue(
        all(f.a).usedMinutes == 45,
        all(f.b).usedMinutes == 20,
        oneA.usedMinutes == 45,
        oneB.usedMinutes == 20,
      )
    },
    test("a day before the move stays with the profile that held the device then") {
      for {
        f    <- seed
        _    <- seedTraffic(f.router, movedMac, at(9).minusSeconds(86400), 25)
        svc  <- fullService
        prev <- svc.dayStateAll(HouseholdId.Default, at(16), day.minusDays(1), f.settings)
      } yield assertTrue(prev(f.a).usedMinutes == 25, prev(f.b).usedMinutes == 0)
    },
    test("a backfilled open-ended row (NULL started_at) still attributes the whole day") {
      for {
        f   <- seed
        xa  <- ZIO.service[Transactor[Task]]
        // The V90 backfill shape: a never-reassigned device's open row has no start.
        n   <- sql"""UPDATE device_profile_assignments SET started_at = NULL
                     WHERE device_id = ${f.stay} AND ended_at IS NULL""".update.run.transact(xa)
        svc <- fullService
        all <- svc.dayStateAllLive(HouseholdId.Default, at(16), day, f.settings)
      } yield assertTrue(n == 1, all(f.a).usedMinutes == 45, all(f.b).usedMinutes == 20)
    },
  ) @@ TestAspect.sequential
}
