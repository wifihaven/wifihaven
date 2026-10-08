package wifihaven.testinfra

import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import zio.*
import zio.interop.catz.*

/**
 * #2843 TEST-PIN (design `docs/design/shared-devices.md` §5.3): the invariants that
 * `DeviceAssignment.assign` maintains between `devices.profile_id` and
 * `device_profile_assignments`. V90 enforces only the one-open-row rule (`uq_dpa_device_open`);
 * everything below is held by the single writer alone, so this check is what notices a writer that
 * bypasses it.
 *
 * Run by `TestDatabase.cleanAndMigrate` against the state the PREVIOUS test left behind, so every
 * feature spec that resets between tests is checked after each of its tests, and asserted directly
 * by `DeviceAssignmentSpec`.
 */
object AssignmentInvariant {

  // 1. `devices.profile_id` equals the open row's profile, or is NULL when there is no open row.
  private val currentMismatch =
    sql"""SELECT 'device ' || d.id || ': devices.profile_id=' || COALESCE(d.profile_id::TEXT, 'NULL')
                 || ' but open history row profile=' || COALESCE(o.profile_id::TEXT, 'none')
            FROM devices d
            LEFT JOIN device_profile_assignments o ON o.device_id = d.id AND o.ended_at IS NULL
           WHERE d.profile_id IS DISTINCT FROM o.profile_id"""

  // 2. No two intervals for one device overlap. Half-open `[started_at, ended_at)` with NULL bounds
  //    unbounded, the reading every attribution reader uses (design §5.1). A zero-length row is an
  //    empty range and overlaps nothing.
  private val overlapping =
    sql"""SELECT 'device ' || a.device_id || ': history rows ' || a.id || ' and ' || b.id || ' overlap'
            FROM device_profile_assignments a
            JOIN device_profile_assignments b ON b.device_id = a.device_id AND b.id > a.id
           WHERE tstzrange(a.started_at, a.ended_at, '[)') && tstzrange(b.started_at, b.ended_at, '[)')"""

  // 3. A history row's household is its device's household (always copied from the device row).
  private val householdMismatch =
    sql"""SELECT 'history row ' || h.id || ': household_id=' || h.household_id
                 || ' but device ' || d.id || ' is in household ' || d.household_id
            FROM device_profile_assignments h
            JOIN devices d ON d.id = h.device_id
           WHERE h.household_id <> d.household_id"""

  // 4. A shared device never holds an open `assigned` row (design §9).
  private val sharedAssigned =
    sql"""SELECT 'device ' || d.id || ': shared but holds open assigned row ' || o.id
            FROM devices d
            JOIN device_profile_assignments o ON o.device_id = d.id AND o.ended_at IS NULL
           WHERE d.shared AND o.kind = 'assigned'"""

  def violations(xa: Transactor[Task]): Task[List[String]] =
    List(currentMismatch, overlapping, householdMismatch, sharedAssigned)
      .traverse(_.query[String].to[List])
      .map(_.flatten)
      .transact(xa)

  /** Fails listing every violation, so a broken fixture names the device it broke. */
  def assertHolds(xa: Transactor[Task], context: String): Task[Unit] =
    violations(xa).flatMap { vs =>
      ZIO
        .fail(
          new AssertionError(
            s"#2843 device assignment invariant violated ($context):\n  " + vs.mkString("\n  "),
          ),
        )
        .when(vs.nonEmpty)
        .unit
    }
}
