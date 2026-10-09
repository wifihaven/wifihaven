package wifihaven.api.db

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.api.metrics.DbMetrics
import wifihaven.shared.{SharedDevice, SharedDeviceHolder}
import wifihaven.shared.types.*
import zio.*
import zio.interop.catz.*

import java.sql.SQLException
import java.time.Instant

/**
 * #2848 (epic #2841, design `docs/design/shared-devices.md` §9): the shared-device reads and the
 * check-in / check-out writes behind `/api/shared-devices`. The writes are
 * [[DeviceAssignment.checkIn]] / [[DeviceAssignment.checkOut]], one transaction each, so a check-in
 * is an ordinary assignment-history row like every other change of `devices.profile_id`.
 */
trait SharedDeviceRepo {

  /** Every shared device in `household`, with its open check-in if it has one, by device name. */
  def listForHousehold(household: HouseholdId): Task[List[SharedDevice]]

  /** [[DeviceAssignment.checkIn]] in its own transaction, acting as `byUsername`. */
  def checkIn(
      household: HouseholdId,
      device: DeviceId,
      profile: ProfileId,
      at: Instant,
      byUsername: String,
  ): Task[CheckInOutcome]

  /** [[DeviceAssignment.checkOut]] in its own transaction, acting as `byUsername`. */
  def checkOut(
      household: HouseholdId,
      device: DeviceId,
      holder: ProfileId,
      at: Instant,
      byUsername: String,
      cause: AssignmentEndCause,
  ): Task[CheckOutOutcome]

  /** #2849: every open check-in in `household`, for the auto-checkout job. */
  def openCheckIns(household: HouseholdId): Task[List[OpenCheckIn]]

  /**
   * #2849: [[DeviceAssignment.checkOut]] of exactly `checkIn`'s row, by the system (no `ended_by`),
   * in its own transaction, for the auto-checkout job's `time_limit` / `schedule` / `paused` /
   * `idle` / `day_reset` causes. Closes `checkIn` only if it is still the device's open row.
   */
  def autoCheckOut(
      checkIn: OpenCheckIn,
      at: Instant,
      cause: AssignmentEndCause,
  ): Task[CheckOutOutcome]

  /**
   * #2849: open check-ins across every household, for the `shared_device_checkins_open` gauge. A
   * bare count with no household dimension (design §12), so it returns no tenant's rows.
   */
  def countOpenCheckInsFleet: Task[Long]
}

/** #2849: one open `check_in` row: which profile holds which shared device, since when. */
final case class OpenCheckIn(
    id: Long,
    household: HouseholdId,
    deviceId: DeviceId,
    mac: MacAddress,
    holder: ProfileId,
    startedAt: Instant,
)

class SharedDeviceRepoLive(xa: Transactor[Task]) extends SharedDeviceRepo {

  // `devices` is household-bounded and small; the open-row join is served by V90's
  // `uq_dpa_device_open` partial index (one open row per device).
  def listForHousehold(household: HouseholdId) =
    DbMetrics.timed("sharedDevice.listForHousehold")(
      sql"""SELECT d.mac, d.name, o.profile_id, p.name, o.started_at, u.username
              FROM devices d
              LEFT JOIN device_profile_assignments o
                     ON o.device_id = d.id AND o.ended_at IS NULL AND o.kind = 'check_in'
              LEFT JOIN profiles p ON p.id = o.profile_id
              LEFT JOIN users u ON u.id = o.started_by
             WHERE d.household_id = $household AND d.shared
             ORDER BY d.name, d.mac"""
        .query[
          (
              MacAddress,
              String,
              Option[ProfileId],
              Option[String],
              Option[Instant],
              Option[String],
          ),
        ]
        .map { case (mac, name, pid, pname, since, by) =>
          val holder = for {
            p <- pid
            n <- pname
            s <- since
          } yield SharedDeviceHolder(p, n, s.toString, by)
          SharedDevice(mac, name, holder)
        }
        .to[List]
        .transact(xa),
    )

  def checkIn(
      household: HouseholdId,
      device: DeviceId,
      profile: ProfileId,
      at: Instant,
      byUsername: String,
  ) =
    DbMetrics
      .timed("sharedDevice.checkIn")(
        DeviceAssignment
          .actor(household, Some(byUsername))
          .flatMap(DeviceAssignment.checkIn(household, device, profile, at, _))
          .transact(xa),
      )
      // Design §15 Q2: the one-open-row index is the backstop for the one-holder rule. The device
      // row lock makes this unreachable through `checkIn`; it maps a writer that skipped the lock.
      .catchSome {
        case e: SQLException
            if e.getSQLState == "23505" && Option(e.getMessage).exists(
              _.contains("uq_dpa_device_open"),
            ) =>
          ZIO.succeed(CheckInOutcome.Held)
      }

  def checkOut(
      household: HouseholdId,
      device: DeviceId,
      holder: ProfileId,
      at: Instant,
      byUsername: String,
      cause: AssignmentEndCause,
  ) =
    DbMetrics.timed("sharedDevice.checkOut")(
      DeviceAssignment
        .actor(household, Some(byUsername))
        .flatMap(
          DeviceAssignment.checkOut(household, device, CheckInRef.HeldBy(holder), at, _, cause),
        )
        .transact(xa),
    )

  // Read on every per-household reevaluate tick. Open rows are served by V90's partial
  // `uq_dpa_device_open`; a check-in's `started_at` is never NULL (V90 `dpa_open_start_is_assigned`).
  def openCheckIns(household: HouseholdId) =
    DbMetrics.timed("sharedDevice.openCheckIns")(
      sql"""SELECT o.id, o.household_id, o.device_id, d.mac, o.profile_id, o.started_at
              FROM device_profile_assignments o JOIN devices d ON d.id = o.device_id
             WHERE o.household_id = $household AND o.ended_at IS NULL AND o.kind = 'check_in'
             ORDER BY o.device_id"""
        .query[(Long, HouseholdId, DeviceId, MacAddress, ProfileId, Instant)]
        .map(OpenCheckIn.apply.tupled)
        .to[List]
        .transact(xa),
    )

  def autoCheckOut(checkIn: OpenCheckIn, at: Instant, cause: AssignmentEndCause) =
    DbMetrics.timed("sharedDevice.autoCheckOut")(
      DeviceAssignment
        .checkOut(
          checkIn.household,
          checkIn.deviceId,
          CheckInRef.Row(checkIn.id),
          at,
          None,
          cause,
        )
        .transact(xa),
    )

  def countOpenCheckInsFleet =
    DbMetrics.timed("sharedDevice.countOpenCheckInsFleet")(
      sql"""SELECT count(*) FROM device_profile_assignments
             WHERE ended_at IS NULL AND kind = 'check_in'"""
        .query[Long]
        .unique
        .transact(xa),
    )
}
