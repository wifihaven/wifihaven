package wifihaven.api.feature

import wifihaven.api.ScheduleSeeder
import wifihaven.api.db.*
import wifihaven.shared.Clock
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.test.*

import java.time.ZoneId

/**
 * #1602: an operator-deleted migrated named_schedule must stay deleted across API restarts.
 *
 * Bug shape: `Main.scala` invoked `ScheduleSeeder.seedAndMigrate` on every boot. #1538 keyed
 * idempotency off "a migrated `named_schedules` row exists with the deterministic 'Migrated from …'
 * description" — correct for the never-deleted case, but the moment the operator legitimately
 * deletes that row from the SPA the marker is gone, and the next boot re-imports the still-present
 * legacy `schedules` row, resurrecting the deletion. Fix: stop calling the seeder from boot.
 *
 * The test mirrors the production boot's schedule-init step in `bootScheduleInit` and runs it twice
 * with the operator's delete in between. While Main still calls the seeder, `bootScheduleInit` does
 * too; when Main is changed, `bootScheduleInit` is changed in lock-step. (The seeder file stays in
 * place — #1485 deletes it together with the legacy `schedules` table.)
 */
object ScheduleResurrectionSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate
  private val UTC     = ZoneId.of("UTC")

  // Mirrors api/src/Main.scala's boot-time schedule-init step exactly. Update both sides together.
  private def bootScheduleInit =
    for {
      pr  <- ZIO.service[ProfileRepo]
      sr  <- ZIO.service[ScheduleRepo]
      nsr <- ZIO.service[NamedScheduleRepo]
      _   <- ScheduleSeeder.seedAndMigrate(nsr, sr, pr, UTC)
    } yield ()

  private def kidsProfileId =
    ZIO.serviceWithZIO[ProfileRepo](_.listAll).map(_.find(_.name == "Kids").get.id)

  def spec = suite("#1602: deleted migrated schedule stays deleted across boots")(
    test("a fully-deleted migrated named_schedule does NOT resurrect on the next boot") {
      // The V1 seed gives us Kids + a legacy `schedules` row for Kids — exactly the prod shape.
      for {
        _              <- cleanDb
        nsr            <- ZIO.service[NamedScheduleRepo]
        kid            <- kidsProfileId
        // First boot: legacy → named migration creates the Kids named schedule and attaches it.
        _              <- bootScheduleInit
        attachedAfter1 <- nsr.blockScheduleIdsForProfile(kid)
        migratedId = attachedAfter1.head
        // Operator deletes the migrated schedule from the SPA. The repo `delete` cascades the
        // profile_schedule_rules attachment (the SPA's delete endpoint does the same).
        _              <- nsr.setProfileBlockSchedules(kid, Nil)
        _              <- nsr.delete(migratedId)
        afterDelete    <- nsr.findById(migratedId)
        // Second boot: must not re-import the still-present legacy `schedules` row.
        _              <- bootScheduleInit
        attachedAfter2 <- nsr.blockScheduleIdsForProfile(kid)
        all            <- nsr.listAll
        // The Kids-shaped marker must not reappear.
        resurrected = all.exists(_.description.contains("Migrated from Kids's schedule"))
      } yield assertTrue(attachedAfter1.length == 1) &&
        assertTrue(afterDelete.isEmpty) &&
        assertTrue(attachedAfter2.isEmpty) &&
        assertTrue(!resurrected)
    },
  ) @@ TestAspect.sequential
}
