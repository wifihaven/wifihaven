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
 * `bootScheduleInit` mirrors `api/src/Main.scala`'s boot-time schedule-init step exactly —
 * post-#1602 it is a no-op. The "past migration" the operator's prod system already lived through
 * is staged via a direct `ScheduleSeeder.seedAndMigrate` call (modelling a deploy from before the
 * fix), then the operator deletes the migrated schedule, then we boot again. The post-fix boot must
 * not resurrect it. (The seeder file is left in place; #1485 deletes it together with the legacy
 * `schedules` table, at which point this test goes too.)
 */
object ScheduleResurrectionSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate
  private val UTC     = ZoneId.of("UTC")

  // Mirrors api/src/Main.scala's boot-time schedule-init step exactly. Update both sides together.
  // Post-#1602: no-op. Pre-#1602 this called ScheduleSeeder.seedAndMigrate, which is the resurrection.
  private def bootScheduleInit: UIO[Unit] = ZIO.unit

  private def kidsProfileId =
    ZIO.serviceWithZIO[ProfileRepo](_.listAll).map(_.find(_.name == "Kids").get.id)

  def spec = suite("#1602: deleted migrated schedule stays deleted across boots")(
    test("a fully-deleted migrated named_schedule does NOT resurrect on the next boot") {
      // The V1 seed gives us Kids + a legacy `schedules` row for Kids — exactly the prod shape.
      for {
        _              <- cleanDb
        pr             <- ZIO.service[ProfileRepo]
        sr             <- ZIO.service[ScheduleRepo]
        nsr            <- ZIO.service[NamedScheduleRepo]
        kid            <- kidsProfileId
        // Stage: simulate a past deploy that migrated the legacy `schedules` row into a named
        // schedule and attached it. This is what every prod household already has.
        _              <- ScheduleSeeder.seedAndMigrate(nsr, sr, pr, UTC)
        attachedBefore <- nsr.blockScheduleIdsForProfile(kid)
        migratedId = attachedBefore.head
        // Operator deletes the migrated schedule from the SPA. The repo `delete` cascades the
        // profile_schedule_rules attachment (the SPA's delete endpoint does the same).
        _             <- nsr.setProfileBlockSchedules(kid, Nil)
        _             <- nsr.delete(migratedId)
        afterDelete   <- nsr.findById(migratedId)
        // Now boot the API again. Post-fix this is a no-op; pre-fix it ran the seeder and the
        // legacy `schedules` row resurrected the deletion.
        _             <- bootScheduleInit
        attachedAfter <- nsr.blockScheduleIdsForProfile(kid)
        all           <- nsr.listAll
        // The Kids-shaped marker must not reappear.
        resurrected = all.exists(_.description.contains("Migrated from Kids's schedule"))
      } yield assertTrue(attachedBefore.length == 1) &&
        assertTrue(afterDelete.isEmpty) &&
        assertTrue(attachedAfter.isEmpty) &&
        assertTrue(!resurrected)
    },
  ) @@ TestAspect.sequential
}
