package wifihaven.api.feature

import wifihaven.api.ScheduleSeeder
import wifihaven.api.db.*
import wifihaven.api.policy.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, Schedule as _, *}
import zio.test.*

import java.time.{Instant, LocalDate, LocalDateTime, LocalTime, ZoneId, ZoneOffset}

/**
 * #1482: the legacy per-profile `schedules` table is migrated into the named-schedule model
 * (`named_schedules` / `profile_schedule_rules`) by the boot-time `ScheduleSeeder`, and enforcement
 * now reads schedule downtime EXCLUSIVELY from that model — the legacy table is no longer an
 * enforcement source.
 *
 * The migration is a representation change, NOT a behaviour change, so this spec pins the critical
 * invariant: for a profile whose schedule was authored as a legacy row and then migrated, the
 * named-only snapshot blocks at EXACTLY the instants the original legacy schedule would have — the
 * same `PolicyService.scheduleActiveAt` math the pre-migration read path used. We probe the awkward
 * boundaries the issue calls out: exact on/off edges, the overnight wrap + its tail day, a non-UTC
 * timezone, and day-of-week membership.
 *
 * The equivalence is asserted directly: at each probed instant the named-only `snapshot.blocked`
 * for the device equals `scheduleActiveAt(<the original legacy row>, instant)`. Equality with that
 * function — the very function the legacy read path evaluated — IS the byte-for-byte preservation
 * proof. (The migration's structural correctness — one named schedule + window + profile rule per
 * legacy row — is pinned separately by ScheduleSeederSpec.)
 */
object ScheduleMigrationBehaviorSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate
  private val UTC     = ZoneId.of("UTC")
  private val NY      = ZoneId.of("America/New_York")

  // A profile carrying an overnight, all-days window in UTC (the classic bedtime shape).
  private val overnightMac = "aa:bb:cc:00:00:01"
  private val overnight    = ScheduleRequest(
    "Overnight",
    List("mon", "tue", "wed", "thu", "fri", "sat", "sun"),
    LocalTime.of(21, 0),
    LocalTime.of(7, 0),
    UTC,
  )

  // A profile carrying a weekday-only daytime window in a non-UTC zone (DST-correct via the row tz).
  private val schoolMac = "aa:bb:cc:00:00:02"
  private val school    = ScheduleRequest(
    "School",
    List("mon", "tue", "wed", "thu", "fri"),
    LocalTime.of(8, 0),
    LocalTime.of(15, 0),
    NY,
  )

  private def utc(d: LocalDate, h: Int, m: Int): Instant =
    LocalDateTime.of(d, LocalTime.of(h, m)).toInstant(ZoneOffset.UTC)

  private val mon = LocalDate.of(2025, 1, 6) // Monday
  private val tue = LocalDate.of(2025, 1, 7)
  private val sat = LocalDate.of(2025, 1, 11)

  // Instants chosen to straddle every boundary the issue calls out. NY is UTC-5 in January, so
  // 08:00 NY = 13:00 UTC and 15:00 NY = 20:00 UTC.
  private val probes: List[Instant] = List(
    utc(mon, 20, 59), // overnight: 1 min before the start edge
    utc(mon, 21, 0),  // overnight: exact start (inclusive)
    utc(mon, 23, 30), // overnight: mid-window
    utc(tue, 6, 59),  // overnight: tail day, 1 min before the end edge
    utc(tue, 7, 0),   // overnight: exact end (exclusive)
    utc(tue, 12, 0),  // overnight: well outside
    utc(mon, 12, 59), // school: 07:59 NY — before start
    utc(mon, 13, 0),  // school: 08:00 NY — exact start
    utc(mon, 19, 59), // school: 14:59 NY — mid/just-before end
    utc(mon, 20, 0),  // school: 15:00 NY — exact end (exclusive)
    utc(sat, 13, 0),  // school: 08:00 NY on Saturday — DOW excludes it
  )

  // Build a named-only PolicyService at `at`, with the REAL NamedScheduleRepo wired (so schedule
  // downtime is read from the migrated named schedules, never the legacy table).
  private def makePsAt(at: Instant) =
    for {
      pr     <- ZIO.service[ProfileRepo]
      sr     <- ZIO.service[ScheduleRepo]
      nsr    <- ZIO.service[NamedScheduleRepo]
      hsr    <- ZIO.service[HouseholdSettingsRepo]
      tlr    <- ZIO.service[TimeLimitRepo]
      stlr   <- ZIO.service[SiteTimeLimitRepo]
      dr     <- ZIO.service[DeviceRepo]
      blr    <- ZIO.service[BlocklistRepo]
      trRepo <- ZIO.service[TrafficReportRepo]
      er     <- ZIO.service[TimeExtensionRepo]
      ar     <- ZIO.service[AppRepo]
      ref    <- Ref.make(LocalDateTime.ofInstant(at, UTC))
      clk = new Clock.TestClock(ref)
    } yield PolicyServiceLive(
      pr,
      sr,
      hsr,
      tlr,
      stlr,
      dr,
      blr,
      trRepo,
      er,
      ar,
      clk,
      namedScheduleRepo = nsr,
    ): PolicyService

  private def isScheduleBlocked(snap: PolicySnapshot, mac: String): Boolean =
    snap.devices
      .collectFirst { case (m, dev) if m.value.equalsIgnoreCase(mac) => dev }
      .flatMap(dev => dev.rules.orElse(dev.profileId.flatMap(snap.profiles.get).map(_.rules)))
      .exists(r => r.blocked && r.blockReason.contains(MacBlockReason.Schedule))

  private def seedLegacyProfile(name: String, mac: String, sched: ScheduleRequest) =
    for {
      pr  <- ZIO.service[ProfileRepo]
      sr  <- ZIO.service[ScheduleRepo]
      dr  <- ZIO.service[DeviceRepo]
      pid <- pr.create(name, Nil)
      _   <- sr.replaceForProfile(pid, List(sched))
      _   <- TestLayers.seedDevice(dr, mac, s"$name-dev", pid)
    } yield pid

  private def active(leg: List[Schedule], t: Instant): Boolean =
    leg.exists(s => PolicyService.scheduleActiveAt(s, t))

  def spec = suite("schedule migration — behaviour preservation (#1482)")(
    test("migrated profiles block at exactly the instants the original legacy schedule did") {
      for {
        _     <- cleanDb
        sr    <- ZIO.service[ScheduleRepo]
        nsr   <- ZIO.service[NamedScheduleRepo]
        pr    <- ZIO.service[ProfileRepo]
        p1    <- seedLegacyProfile("Overnight", overnightMac, overnight)
        p2    <- seedLegacyProfile("School", schoolMac, school)
        // Run the real boot-time migration: legacy rows → named schedules + profile rules.
        _     <- ScheduleSeeder.seedAndMigrate(nsr, sr, pr, UTC)
        // The original legacy rows are read back ONLY to compute the pre-migration expectation —
        // they are no longer an enforcement source.
        leg1  <- sr.listForProfile(p1)
        leg2  <- sr.listForProfile(p2)
        // For every probe instant, the named-only snapshot must agree with scheduleActiveAt over
        // the original legacy schedule — i.e. enforcement is unchanged by the migration.
        agree <- ZIO.foreach(probes) { t =>
          makePsAt(t).flatMap(_.snapshot).map { snap =>
            (isScheduleBlocked(snap, overnightMac) == active(leg1, t)) &&
            (isScheduleBlocked(snap, schoolMac) == active(leg2, t))
          }
        }
      } yield assertTrue(agree.forall(identity)) &&
        // Guard against a vacuous pass: the probes exercise BOTH blocked and unblocked outcomes for
        // each profile, so the equivalence above is meaningful, not "always false == always false".
        assertTrue(
          probes.exists(active(leg1, _)),
          probes.exists(t => !active(leg1, t)),
          probes.exists(active(leg2, _)),
          probes.exists(t => !active(leg2, t)),
        )
    },
  ) @@ TestAspect.sequential
}
