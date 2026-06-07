package wifihaven.api.feature

import wifihaven.api.ScheduleSeeder
import wifihaven.api.db.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.test.*

import java.time.ZoneId

/**
 * #1069: the boot-time seed/migrate pass. Migrates each profile's legacy per-profile `schedules`
 * into the named-schedule model (linking via schedule_id), and seeds the default starter set when
 * `named_schedules` is empty. Idempotent.
 *
 * The migrations (V1) seed a `Kids` + `Adults` profile and a `Bedtime` legacy schedule on `Kids`,
 * so a freshly-migrated DB already carries exactly that fixture — these tests build on it.
 */
object ScheduleSeederSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate
  private val UTC     = ZoneId.of("UTC")

  private def seedNow =
    for {
      pr  <- ZIO.service[ProfileRepo]
      sr  <- ZIO.service[ScheduleRepo]
      nsr <- ZIO.service[NamedScheduleRepo]
      out <- ScheduleSeeder.seedAndMigrate(nsr, sr, pr, UTC)
    } yield out

  private def kidsProfileId =
    ZIO.serviceWithZIO[ProfileRepo](_.listAll).map(_.find(_.name == "Kids").get.id)

  def spec = suite("ScheduleSeeder (#1069)")(
    test("migrates the V1-seeded Kids/Bedtime legacy schedule into a linked block schedule") {
      for {
        _        <- cleanDb
        nsr      <- ZIO.service[NamedScheduleRepo]
        kid      <- kidsProfileId
        before   <- nsr.blockScheduleIdsForProfile(kid)
        summary  <- seedNow
        attached <- nsr.blockScheduleIdsForProfile(kid)
        named    <- attached.headOption.fold(ZIO.succeed(Option.empty[NamedSchedule]))(nsr.findById)
      } yield assertTrue(before.isEmpty) &&
        // only Kids has a legacy schedule in the V1 seed → exactly one profile migrated
        assertTrue(summary.migrated == 1) &&
        assertTrue(attached.length == 1) &&
        assertTrue(named.exists(_.windows.length == 1)) &&
        assertTrue(named.exists(_.windows.head.days.contains("mon")))
    },
    test("is idempotent — a second run migrates nothing and adds no duplicate schedule") {
      for {
        _           <- cleanDb
        nsr         <- ZIO.service[NamedScheduleRepo]
        first       <- seedNow
        afterFirst  <- nsr.listAll.map(_.length)
        second      <- seedNow
        afterSecond <- nsr.listAll.map(_.length)
      } yield assertTrue(first.migrated == 1) &&
        assertTrue(second.migrated == 0) &&
        assertTrue(afterFirst == afterSecond)
    },
    test("#1538: a detached migrated schedule is NOT resurrected on the next migrate run") {
      // Regression for the resurrection loop: the old guard skipped migration only when the
      // profile CURRENTLY had a block-mode named schedule attached. Detaching it (deleting the
      // profile_schedule_rules row) while the legacy `schedules` row was still retained for
      // rollback safety made the guard see "not migrated" again, so the next boot re-migrated the
      // retained legacy row and re-attached the schedule the operator had just removed.
      for {
        _        <- cleanDb
        nsr      <- ZIO.service[NamedScheduleRepo]
        kid      <- kidsProfileId
        _        <- seedNow // migrate: creates + attaches Kids' named schedule
        attached <- nsr.blockScheduleIdsForProfile(kid)
        _        <- nsr.setProfileBlockSchedules(
          kid,
          Nil,
        ) // operator detaches; legacy `schedules` row stays
        _        <- seedNow // boot again — must NOT re-attach
        after    <- nsr.blockScheduleIdsForProfile(kid)
      } yield assertTrue(attached.length == 1) &&
        assertTrue(after.isEmpty)
    },
    test("seeds the default starter set when named_schedules is empty") {
      for {
        _       <- cleanDb
        nsr     <- ZIO.service[NamedScheduleRepo]
        summary <- seedNow
        names   <- nsr.listAll.map(_.map(_.name).toSet)
      } yield assertTrue(summary.seededDefaults == 4) &&
        assertTrue(
          Set("Bedtime", "School hours", "Afternoon", "Weekend mornings").subsetOf(names),
        )
    },
    test("does NOT re-seed defaults once named schedules exist") {
      for {
        _      <- cleanDb
        first  <- seedNow
        second <- seedNow
      } yield assertTrue(first.seededDefaults == 4) && assertTrue(second.seededDefaults == 0)
    },
  ) @@ TestAspect.sequential
}
