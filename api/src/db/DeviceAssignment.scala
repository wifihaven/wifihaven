package wifihaven.api.db

import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.api.metrics.{AppMetrics, DbMetrics}
import wifihaven.shared.types.*
import zio.*
import zio.interop.catz.*

import java.time.Instant

/** `device_profile_assignments.kind` (V90 `dpa_kind_check`). */
enum AssignmentKind(val db: String) {
  case Assigned extends AssignmentKind("assigned")
  case CheckIn  extends AssignmentKind("check_in")
}

/** `device_profile_assignments.end_cause` (V90 `dpa_end_cause_check`): why a row was closed. */
enum AssignmentEndCause(val db: String) {
  case Reassigned extends AssignmentEndCause("reassigned")
  case Unassigned extends AssignmentEndCause("unassigned")
  case CheckOut   extends AssignmentEndCause("check_out")
  case Forced     extends AssignmentEndCause("forced")
  case TimeLimit  extends AssignmentEndCause("time_limit")
  case Schedule   extends AssignmentEndCause("schedule")
  case Paused     extends AssignmentEndCause("paused")
  case Idle       extends AssignmentEndCause("idle")
  case DayReset   extends AssignmentEndCause("day_reset")
  case MadeShared extends AssignmentEndCause("made_shared")
  case Unshared   extends AssignmentEndCause("unshared")
}

/** #2848: the result of [[DeviceAssignment.checkIn]]. */
enum CheckInOutcome {
  case CheckedIn

  /** The device is not shared, so it is assigned, never checked in. */
  case NotShared

  /** Someone already holds the device (design §15 Q2). */
  case Held
}

/** #2848: the result of [[DeviceAssignment.checkOut]]. */
enum CheckOutOutcome {
  case CheckedOut
  case NotShared

  /** No open check-in by the expected holder: already released, or released and re-taken. */
  case NotHeld
}

/**
 * Why [[DeviceAssignment.assign]] refused a write. Sealed so the routes that surface these (#2848:
 * 409 `device_shared`, 403 / 404 for scope) can map them exhaustively.
 */
sealed abstract class DeviceAssignmentError(message: String) extends RuntimeException(message)

object DeviceAssignmentError {

  /**
   * An `assigned` row on a shared device (design §9): a shared device is only ever held through a
   * `check_in`, because an `assigned` row would enforce like a check-in that no auto-checkout
   * releases.
   */
  final case class SharedDeviceRefused(deviceId: DeviceId)
      extends DeviceAssignmentError(
        s"device ${deviceId.value} is shared: it can be checked in, not assigned",
      )

  /** The device is not in the household the caller named. */
  final case class DeviceNotInHousehold(deviceId: DeviceId, household: HouseholdId)
      extends DeviceAssignmentError(
        s"device ${deviceId.value} is not in household ${household.value}",
      )

  /** #1771: no device can be held by the global sentinel profile. */
  final case class GlobalProfile(profileId: ProfileId)
      extends DeviceAssignmentError(
        s"devices cannot be assigned to the global profile (id=${profileId.value})",
      )

  /** The profile is in another household than the device, or does not exist. */
  final case class ProfileNotInHousehold(profileId: ProfileId, household: HouseholdId)
      extends DeviceAssignmentError(
        s"profile ${profileId.value} is not in household ${household.value}",
      )
}

/**
 * #2843 (epic #2841, design `docs/design/shared-devices.md` §5.3): the ONLY code that writes
 * `devices.profile_id`. Each write also maintains `device_profile_assignments` (V90) in the same
 * transaction, so `devices.profile_id` (the "current" read every now-shaped path uses, §5.2) always
 * equals the open history row's profile, or is NULL when there is no open row.
 *
 * Every other path that changes a device's profile composes [[assign]] into its own transaction
 * (`DeviceRepoLive.upsert`). A CI guard (`.github/scripts/check-device-profile-writers.sh`) rejects
 * any other `devices.profile_id` write under `api/src`, and `AssignmentInvariant` pins the
 * invariant after every feature test.
 *
 * Writing the profile on a device row from anywhere else would re-attribute usage history without a
 * record of when the device moved, which is the bug this table exists to fix (V90 header).
 */
object DeviceAssignment {

  private[db] final case class DeviceRow(household: HouseholdId, shared: Boolean)
  private[db] final case class OpenRow(id: Long, profile: ProfileId, kind: String)

  // Locks the device row, so concurrent writers for one device serialise here and each sees the
  // open row the previous one left.
  private def lockDevice(household: HouseholdId, device: DeviceId): ConnectionIO[DeviceRow] =
    sql"""SELECT household_id, shared FROM devices
           WHERE id = $device AND household_id = $household FOR UPDATE"""
      .query[(HouseholdId, Boolean)]
      .option
      .flatMap {
        case Some((hh, shared)) => DeviceRow(hh, shared).pure[ConnectionIO]
        case None               =>
          FC.raiseError[DeviceRow](DeviceAssignmentError.DeviceNotInHousehold(device, household))
      }

  private def openRow(device: DeviceId): ConnectionIO[Option[OpenRow]] =
    sql"""SELECT id, profile_id, kind FROM device_profile_assignments
           WHERE device_id = $device AND ended_at IS NULL"""
      .query[(Long, ProfileId, String)]
      .option
      .map(_.map(OpenRow.apply.tupled))

  // Whether this device has ANY assignment history row (open or closed). Distinguishes a genuine
  // first-ever assignment (no rows at all) from a re-assignment after an unassignment gap (closed
  // rows exist) — only the former gets an open-ended start (see [[startFor]]).
  private def hasAnyRow(device: DeviceId): ConnectionIO[Boolean] =
    sql"SELECT EXISTS(SELECT 1 FROM device_profile_assignments WHERE device_id = $device)"
      .query[Boolean]
      .unique

  private def currentProfile(device: DeviceId): ConnectionIO[Option[ProfileId]] =
    sql"SELECT profile_id FROM devices WHERE id = $device".query[Option[ProfileId]].unique

  // The instant a transition is recorded at: `at`, but never earlier than any bound already in this
  // device's history. Two API instances' clocks can disagree slightly, and an injected test clock
  // can sit behind a row another clock wrote; without the clamp either would close a row before it
  // opened (V90 `dpa_interval_order`) or open one that overlaps its predecessor. A clamped
  // transition is a zero-length interval, which readers' half-open match treats as no presence.
  // Served by `idx_dpa_device_started`.
  private def transitionAt(device: DeviceId, at: Instant): ConnectionIO[Instant] =
    sql"""SELECT GREATEST(MAX(started_at), MAX(ended_at)) FROM device_profile_assignments
           WHERE device_id = $device"""
      .query[Option[Instant]]
      .unique
      .map(_.filter(_.isAfter(at)).getOrElse(at))

  private def close(
      row: OpenRow,
      at: Instant,
      by: Option[UserId],
      cause: AssignmentEndCause,
  ): ConnectionIO[Unit] =
    sql"""UPDATE device_profile_assignments
             SET ended_at = $at, ended_by = $by, end_cause = ${cause.db}
           WHERE id = ${row.id}""".update.run.void

  private def open(
      household: HouseholdId,
      device: DeviceId,
      profile: ProfileId,
      start: Option[Instant],
      kind: AssignmentKind,
      by: Option[UserId],
  ): ConnectionIO[Unit] =
    sql"""INSERT INTO device_profile_assignments
            (household_id, device_id, profile_id, started_at, kind, started_by)
          VALUES ($household, $device, $profile, $start, ${kind.db}, $by)""".update.run.void

  // The `started_at` a newly opened row gets. A device's FIRST-EVER `assigned` row is open-ended
  // (NULL), exactly like the V90 backfill row every pre-existing device received: the profile owns
  // the device's history up to any later reassignment. That is the pre-#2844 attribution the
  // migration preserves ("changes no behaviour") and keeps an API-created device attributing the
  // same as a backfilled one; design `docs/design/shared-devices.md` §5.1 / Q7 scopes the
  // "reassigning no longer moves past usage" change to a *reassignment*, not a first assignment.
  // Everything else — a reassignment (an open row is being closed), a re-assignment after an
  // unassignment gap (closed rows exist), and every `check_in` (a point-in-time hold) — is stamped
  // at the transition instant. `check_in` is never open-ended (V90 `dpa_open_start_is_assigned`).
  private def startFor(
      device: DeviceId,
      closing: Option[OpenRow],
      kind: AssignmentKind,
      ts: Instant,
  ): ConnectionIO[Option[Instant]] =
    if (kind != AssignmentKind.Assigned || closing.isDefined) Option(ts).pure[ConnectionIO]
    else hasAnyRow(device).map(if (_) Some(ts) else None)

  private def setCurrent(device: DeviceId, profile: Option[ProfileId]): ConnectionIO[Unit] =
    sql"UPDATE devices SET profile_id = $profile WHERE id = $device".update.run.void

  // #1771: a device cannot be held by the global sentinel profile, and (#2843) a profile can only
  // hold a device in its own household. Both are checked here because this is the one writer;
  // routes reject the same cases earlier with a 400 / 403.
  private def checkProfile(household: HouseholdId, profile: ProfileId): ConnectionIO[Unit] =
    sql"SELECT is_global, household_id FROM profiles WHERE id = $profile"
      .query[(Boolean, HouseholdId)]
      .option
      .flatMap {
        case Some((true, _))                  =>
          FC.raiseError(DeviceAssignmentError.GlobalProfile(profile))
        case Some((_, hh)) if hh == household => FC.unit
        case _                                =>
          FC.raiseError(DeviceAssignmentError.ProfileNotInHousehold(profile, household))
      }

  /**
   * Make `newProfile` the device's current profile as of `at`: close the open history row (with
   * `cause`, by `by`), open a `kind` row for `newProfile` (none when `newProfile` is None), and set
   * `devices.profile_id` — in the caller's transaction.
   *
   * A no-op returning false when the open row already holds `newProfile` (whatever its kind), so a
   * rename or a re-sent PUT does not churn history; `devices.profile_id` is still re-synced. The
   * new row's `household_id` is the device row's, never the caller's: `household` only scopes the
   * lookup, and a device outside it fails with [[DeviceAssignmentError.DeviceNotInHousehold]].
   *
   * On a shared device an `assigned` write never moves the holder (design §9: the existing writers
   * must not bypass check-in). A profile is refused with
   * [[DeviceAssignmentError.SharedDeviceRefused]]; no profile is a no-op returning false, so a `PUT
   * /api/devices` that omits `profileId` leaves the check-in open. Check-in, check-out and the
   * sharing toggle are the transitions below, each with its own end cause.
   */
  def assign(
      household: HouseholdId,
      device: DeviceId,
      newProfile: Option[ProfileId],
      at: Instant,
      kind: AssignmentKind,
      by: Option[UserId],
      cause: AssignmentEndCause,
  ): ConnectionIO[Boolean] =
    lockDevice(household, device).flatMap { dev =>
      if (dev.shared && kind == AssignmentKind.Assigned)
        openRow(device).flatMap { current =>
          newProfile match {
            case None                                          => false.pure[ConnectionIO]
            case Some(p) if current.map(_.profile).contains(p) =>
              setCurrent(device, newProfile).as(false)
            case Some(_)                                       =>
              FC.raiseError[Boolean](DeviceAssignmentError.SharedDeviceRefused(device))
          }
        }
      else transition(dev, device, newProfile, at, kind, by, cause)
    }

  // The body of every write: the caller holds the device row lock (`dev`) and has applied any
  // shared-device rule. A no-op when the open row already holds `newProfile`.
  private def transition(
      dev: DeviceRow,
      device: DeviceId,
      newProfile: Option[ProfileId],
      at: Instant,
      kind: AssignmentKind,
      by: Option[UserId],
      cause: AssignmentEndCause,
  ): ConnectionIO[Boolean] =
    openRow(device).flatMap { current =>
      if (current.map(_.profile) == newProfile) setCurrent(device, newProfile).as(false)
      else
        for {
          _     <- newProfile.traverse_(checkProfile(dev.household, _))
          ts    <- transitionAt(device, at)
          start <- startFor(device, current, kind, ts)
          _     <- current.traverse_(close(_, ts, by, cause))
          _     <- newProfile.traverse_(open(dev.household, device, _, start, kind, by))
          _     <- setCurrent(device, newProfile)
        } yield true
    }

  /**
   * The user `username` names in `household`, recorded as a history row's started_by / ended_by.
   */
  def actor(household: HouseholdId, username: Option[String]): ConnectionIO[Option[UserId]] =
    username.flatTraverse(u =>
      sql"SELECT id FROM users WHERE household_id=$household AND username=$u"
        .query[UserId]
        .option,
    )

  /**
   * #2848 (design §9, §13): turn sharing on or off for a device, in the caller's transaction.
   * Turning it on closes the open `assigned` row as `made_shared` and leaves the device checked
   * out; turning it off closes any open check-in as `unshared` and leaves the device unassigned. A
   * no-op when the device already has that value. Returns true when an open check-in was closed.
   */
  def setShared(
      household: HouseholdId,
      device: DeviceId,
      shared: Boolean,
      at: Instant,
      by: Option[UserId],
  ): ConnectionIO[Boolean] =
    lockDevice(household, device).flatMap { dev =>
      if (dev.shared == shared) false.pure[ConnectionIO]
      else
        for {
          current <- openRow(device)
          cause = if (shared) AssignmentEndCause.MadeShared else AssignmentEndCause.Unshared
          _ <- transition(dev, device, None, at, AssignmentKind.Assigned, by, cause)
          _ <- sql"UPDATE devices SET shared = $shared WHERE id = $device".update.run
        } yield current.exists(_.kind == AssignmentKind.CheckIn.db)
    }

  /**
   * #2848 (design §9): open a `check_in` row for `profile` on a shared device that nobody holds, in
   * the caller's transaction. The device row lock serialises this against any other check-in, so
   * the open-row test below is the one-holder rule; `uq_dpa_device_open` is the backstop.
   */
  def checkIn(
      household: HouseholdId,
      device: DeviceId,
      profile: ProfileId,
      at: Instant,
      by: Option[UserId],
  ): ConnectionIO[CheckInOutcome] =
    lockDevice(household, device).flatMap { dev =>
      if (!dev.shared) CheckInOutcome.NotShared.pure[ConnectionIO]
      else
        openRow(device).flatMap {
          case Some(_) => CheckInOutcome.Held.pure[ConnectionIO]
          // Nothing is open, so there is no row for the end cause to close.
          case None    =>
            transition(
              dev,
              device,
              Some(profile),
              at,
              AssignmentKind.CheckIn,
              by,
              AssignmentEndCause.CheckOut,
            )
              .as(CheckInOutcome.CheckedIn)
        }
    }

  /**
   * #2848 (design §9): close the open check-in on a shared device with `cause` (`check_out` or
   * `forced`), in the caller's transaction. `holder` is the profile the caller authorised against;
   * if the open check-in is no longer that profile's, nothing is written.
   */
  def checkOut(
      household: HouseholdId,
      device: DeviceId,
      holder: ProfileId,
      at: Instant,
      by: Option[UserId],
      cause: AssignmentEndCause,
  ): ConnectionIO[CheckOutOutcome] =
    lockDevice(household, device).flatMap { dev =>
      if (!dev.shared) CheckOutOutcome.NotShared.pure[ConnectionIO]
      else
        openRow(device).flatMap {
          case Some(o) if o.kind == AssignmentKind.CheckIn.db && o.profile == holder =>
            transition(dev, device, None, at, AssignmentKind.CheckIn, by, cause)
              .as(CheckOutOutcome.CheckedOut)
          case _                                                                     =>
            CheckOutOutcome.NotHeld.pure[ConnectionIO]
        }
    }

  // Devices in `household` whose two stores disagree, or that are shared and hold an `assigned`
  // row. Only a candidate list: each one is re-read under its row lock before it is repaired.
  private def driftCandidates(household: HouseholdId): ConnectionIO[List[DeviceId]] =
    sql"""SELECT d.id FROM devices d
            LEFT JOIN device_profile_assignments o ON o.device_id = d.id AND o.ended_at IS NULL
           WHERE d.household_id = $household
             AND (d.profile_id IS DISTINCT FROM o.profile_id OR (d.shared AND o.kind = 'assigned'))
           ORDER BY d.id"""
      .query[DeviceId]
      .to[List]

  // Repair one device; true if it needed it. Design §5.3 as amended on #2843:
  //   - not shared: `devices.profile_id` is the truth (it is what the old writer set); the open row
  //     is closed and an `assigned` row for it reopened at the tick instant.
  //   - shared with an open `check_in`: the check-in is the truth (only this primitive writes one),
  //     so `devices.profile_id` is restored from it and the row stays open.
  //   - shared otherwise: never given an `assigned` row; any open row is closed as `unassigned` and
  //     `devices.profile_id` cleared, leaving the device checked out.
  private def repairOne(
      household: HouseholdId,
      device: DeviceId,
      at: Instant,
  ): ConnectionIO[Boolean] =
    for {
      dev      <- lockDevice(household, device)
      current  <- openRow(device)
      cached   <- currentProfile(device)
      repaired <- current match {
        case Some(o) if dev.shared && o.kind == AssignmentKind.CheckIn.db =>
          if (cached.contains(o.profile)) false.pure[ConnectionIO]
          else setCurrent(device, Some(o.profile)).as(true)
        case _ if dev.shared                                              =>
          if (current.isEmpty && cached.isEmpty) false.pure[ConnectionIO]
          else
            for {
              ts <- transitionAt(device, at)
              _  <- current.traverse_(close(_, ts, None, AssignmentEndCause.Unassigned))
              _  <- setCurrent(device, None)
            } yield true
        case _                                                            =>
          if (current.map(_.profile) == cached) false.pure[ConnectionIO]
          else
            for {
              ts    <- transitionAt(device, at)
              start <- startFor(device, current, AssignmentKind.Assigned, ts)
              cause =
                if (cached.isDefined) AssignmentEndCause.Reassigned
                else AssignmentEndCause.Unassigned
              _ <- current.traverse_(close(_, ts, None, cause))
              _ <- cached.traverse_(
                open(dev.household, device, _, start, AssignmentKind.Assigned, None),
              )
            } yield true
      }
    } yield repaired

  /**
   * The standing drift check (design §5.3, run on every per-household reevaluate tick): repair
   * every device in `household` whose `devices.profile_id` disagrees with its open history row, at
   * `at`. Returns how many devices were repaired. Drift means something wrote `devices.profile_id`
   * without this primitive: an older image overlapping a deploy, or a bypassing writer.
   */
  def repairDrift(household: HouseholdId, at: Instant): ConnectionIO[Int] =
    driftCandidates(household)
      .flatMap(_.traverse(repairOne(household, _, at)))
      .map(_.count(identity))
}

/** Task-level access to [[DeviceAssignment]], one transaction per call. */
trait DeviceAssignmentRepo {

  /** [[DeviceAssignment.assign]] in its own transaction. */
  def assign(
      household: HouseholdId,
      device: DeviceId,
      newProfile: Option[ProfileId],
      at: Instant,
      kind: AssignmentKind,
      by: Option[UserId],
      cause: AssignmentEndCause,
  ): Task[Boolean]

  /**
   * [[DeviceAssignment.repairDrift]] in its own transaction; each repaired device increments
   * `device_assignment_drift_repaired_total`.
   */
  def repairDrift(household: HouseholdId, at: Instant): Task[Int]
}

class DeviceAssignmentRepoLive(xa: Transactor[Task]) extends DeviceAssignmentRepo {
  def assign(
      household: HouseholdId,
      device: DeviceId,
      newProfile: Option[ProfileId],
      at: Instant,
      kind: AssignmentKind,
      by: Option[UserId],
      cause: AssignmentEndCause,
  ) =
    DeviceAssignment.assign(household, device, newProfile, at, kind, by, cause).transact(xa)

  def repairDrift(household: HouseholdId, at: Instant) =
    DbMetrics
      .timed("deviceAssignment.repairDrift")(
        DeviceAssignment.repairDrift(household, at).transact(xa),
      )
      .tap { n =>
        AppMetrics.recordDeviceAssignmentDriftRepaired(n) *> ZIO.when(n > 0)(
          ZIO.logWarning(
            s"device assignment drift repaired: household=${household.value} devices=$n " +
              "(something wrote devices.profile_id outside DeviceAssignment.assign)",
          ),
        )
      }
}
