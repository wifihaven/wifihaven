package wifihaven.api.feature

import wifihaven.api.db.*
import wifihaven.api.policy.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import doobie.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.test.*

import java.time.{LocalTime, ZoneId}

/**
 * #2264 (follow-up to #2257, epic #2085/#622) — the residual all-tenant reads INSIDE the
 * household-scoped `TimeStatusService.dayStateAll` batch.
 *
 * After #2257 scoped `dayStateAll` to a household, three reads inside it still returned EVERY
 * household's rows:
 *
 *   - `namedScheduleRepo.windowsForAllProfiles` (`WHERE psr.mode = 'blocked_during'`, no household)
 *   - `extRepo.snapshotAllByProfile(date)` (`WHERE date = ?`, no household)
 *   - `rollupRepo.getDayMap(date)` (`WHERE date = ?`, no household) — a THIRD read the issue does
 *     not name; found by the #2563 audit, same class, in `dayStateAllFromRollup`.
 *
 * All three are `ProfileId`-keyed and only the scoped household's profiles are iterated, so there
 * was no cross-household leak — this pins the SCOPING itself (each method returns only the scoped
 * household's profile ids) rather than the absence of a leak in the response, plus a regression pin
 * that `dayStateAll` for household A is byte-identical with and without household B's rows.
 *
 * Full stack against embedded Postgres — no repo mocks, Clock injected.
 */
object DayStateAllScopeSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val macA = MacAddress.unsafe("aa:bb:cc:00:00:2a")
  private val macB = MacAddress.unsafe("aa:bb:cc:00:00:2b")

  private val window =
    ScheduleWindow(
      List("mon", "tue", "wed", "thu", "fri"),
      LocalTime.of(21, 0),
      LocalTime.of(7, 0),
      ZoneId.of("UTC"),
    )

  /** Attach a fresh block-mode named schedule (one window) to `pid`, stamped to `household`. */
  private def attachSchedule(name: String, pid: ProfileId, household: HouseholdId) =
    for {
      nsr <- ZIO.service[NamedScheduleRepo]
      id  <- nsr.create(name, None, List(window), household)
      _   <- nsr.setProfileBlockSchedules(pid, List(id))
    } yield id

  private def timeStatusService =
    for {
      pr  <- ZIO.service[ProfileRepo]
      tlr <- ZIO.service[TimeLimitRepo]
      atl <- ZIO.service[AppTimeLimitRepo]
      dr  <- ZIO.service[DeviceRepo]
      trr <- ZIO.service[TrafficReportRepo]
      er  <- ZIO.service[TimeExtensionRepo]
      ru  <- ZIO.service[TimeUsedRollupRepo]
      nsr <- ZIO.service[NamedScheduleRepo]
    } yield new TimeStatusServiceLive(pr, tlr, atl, dr, trr, er, ru, nsr)

  def spec = suite("dayStateAll's inner reads are household-scoped (#2264)")(
    // ── Read 1: named-schedule windows ──────────────────────────────────────────
    test("windowsForHouseholdProfiles returns only the scoped household's profile ids") {
      for {
        _   <- cleanDb
        two <- TestLayers.seedTwoHouseholds(macA, macB)
        nsr <- ZIO.service[NamedScheduleRepo]
        _   <- attachSchedule("A-Bedtime", two.profileA, two.hhA)
        _   <- attachSchedule("B-Bedtime", two.profileB, two.hhB)
        wA  <- nsr.windowsForHouseholdProfiles(two.hhA)
        wB  <- nsr.windowsForHouseholdProfiles(two.hhB)
      } yield assertTrue(wA.keySet == Set(two.profileA)) &&
        assertTrue(wB.keySet == Set(two.profileB)) &&
        // Non-vacuous: the window rows themselves survive the join.
        assertTrue(wA(two.profileA) == List(window))
    },
    // ── Read 2: time-extension snapshot ─────────────────────────────────────────
    test("snapshotByProfileForHousehold returns only the scoped household's profile ids") {
      for {
        _     <- cleanDb
        two   <- TestLayers.seedTwoHouseholds(macA, macB)
        er    <- ZIO.service[TimeExtensionRepo]
        clock <- ZIO.service[Clock]
        now   <- clock.instant
        hsr   <- ZIO.service[HouseholdSettingsRepo]
        stg   <- hsr.getForHousehold(two.hhA)
        date = PolicyService.householdLocalDate(now, stg)
        _  <- er.grantForProfile(two.profileA, date, 10, "test", None, two.hhA)
        _  <- er.grantForProfile(two.profileB, date, 99, "test", None, two.hhB)
        sA <- er.snapshotByProfileForHousehold(two.hhA, date)
        sB <- er.snapshotByProfileForHousehold(two.hhB, date)
      } yield assertTrue(sA == Map(two.profileA -> 10)) &&
        assertTrue(sB == Map(two.profileB -> 99))
    },
    // ── Read 3: the rollup day map (NOT named in #2264; found by the #2563 audit) ─
    test("getDayMapForHousehold returns only the scoped household's profile ids") {
      for {
        _     <- cleanDb
        two   <- TestLayers.seedTwoHouseholds(macA, macB)
        ru    <- ZIO.service[TimeUsedRollupRepo]
        clock <- ZIO.service[Clock]
        now   <- clock.instant
        hsr   <- ZIO.service[HouseholdSettingsRepo]
        stg   <- hsr.getForHousehold(two.hhA)
        date = PolicyService.householdLocalDate(now, stg)
        _  <- ru.upsertDay(two.profileA, date, RolledDay(600L, now))
        _  <- ru.upsertDay(two.profileB, date, RolledDay(1800L, now))
        mA <- ru.getDayMapForHousehold(two.hhA, date)
        mB <- ru.getDayMapForHousehold(two.hhB, date)
      } yield assertTrue(mA.keySet == Set(two.profileA)) &&
        assertTrue(mB.keySet == Set(two.profileB)) &&
        assertTrue(mA(two.profileA).usedSeconds == 600L)
    },
    // ── Regression pin: A's day-states are unchanged by B's rows ────────────────
    test("dayStateAll for household A is identical with and without household B's rows") {
      for {
        _     <- cleanDb
        two   <- TestLayers.seedTwoHouseholds(macA, macB)
        tss   <- timeStatusService
        er    <- ZIO.service[TimeExtensionRepo]
        ru    <- ZIO.service[TimeUsedRollupRepo]
        clock <- ZIO.service[Clock]
        now   <- clock.instant
        hsr   <- ZIO.service[HouseholdSettingsRepo]
        stg   <- hsr.getForHousehold(two.hhA)
        date = PolicyService.householdLocalDate(now, stg)
        // Household A's own rows: schedule window, extension, rollup row.
        _      <- attachSchedule("A-Bedtime", two.profileA, two.hhA)
        _      <- er.grantForProfile(two.profileA, date, 10, "test", None, two.hhA)
        _      <- ru.upsertDay(two.profileA, date, RolledDay(600L, now))
        before <- tss.dayStateAll(two.hhA, now, date, stg)
        // Now add household B's rows for the SAME date — A must not move.
        _      <- attachSchedule("B-Bedtime", two.profileB, two.hhB)
        _      <- er.grantForProfile(two.profileB, date, 99, "test", None, two.hhB)
        _      <- ru.upsertDay(two.profileB, date, RolledDay(1800L, now))
        after  <- tss.dayStateAll(two.hhA, now, date, stg)
      } yield assertTrue(before.keySet == Set(two.profileA)) &&
        assertTrue(after == before) &&
        // Non-vacuous: A's own extension is present (so the extension read really ran).
        assertTrue(after(two.profileA).extensionMinutes == 10)
    },
  ) @@ TestAspect.sequential
}
