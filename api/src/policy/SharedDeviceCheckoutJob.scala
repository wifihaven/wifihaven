package wifihaven.api.policy

import wifihaven.api.db.*
import wifihaven.api.metrics.AppMetrics
import wifihaven.api.observability.LogContext
import wifihaven.shared.{Clock, HouseholdSettings, MacBlockReason}
import wifihaven.shared.types.*
import zio.{Clock as _, *}

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * #2849 (epic #2841, design `docs/design/shared-devices.md` §7.2 rows 3–7, §7.3, §15 Q3/Q3a):
 * release a checked-in shared device so someone else can check in. Runs at the start of every
 * per-household reevaluate ([[PolicyServiceLive]]), next to the assignment drift check and before
 * the snapshot build, so a release is in the same tick's snapshot. The writes stay out of the build
 * itself.
 *
 * Enforcement never waits for this job: a held device resolves to its holder's rules, so it is
 * blocked the moment the holder is. This only frees the device.
 *
 * Each open check-in is released for the first of, in this order:
 *   1. `day_reset`: it started before the household's most recent daily reset. It ends AT the reset
 *      instant, not at the tick, so no post-reset presence is credited to yesterday's holder. 2.
 *      `paused` / `schedule` / `time_limit`: the holder's [[ProfileDayState.blockReason]], which
 *      already folds the three in `Paused > Schedule > TimeLimit` order. Read from
 *      [[TimeStatusService.dayStateAll]], the day state the snapshot's `blocked` comes from, so
 *      "blocked" has one definition. 3. `idle`: no engaged presence on the device for the
 *      household's `sharedDeviceIdleMinutes`, measured from [[TimeStatusService.lastEngagedAt]] (or
 *      the check-in start if none).
 *
 * The write is [[SharedDeviceRepo.autoCheckOut]], i.e. `DeviceAssignment.checkOut` with the
 * matching end cause: the one assignment writer. Releasing an already-released check-in is a
 * `NotHeld` no-op, so two overlapping ticks cannot double-close.
 */
final class SharedDeviceCheckoutJob(
    sharedDeviceRepo: SharedDeviceRepo,
    householdSettingsRepo: HouseholdSettingsRepo,
    timeStatusService: TimeStatusService,
    clock: Clock,
) {

  // Who hears about a release: the SPA change bus, which is built after the policy layer, so Main
  // installs it once at startup, as it does the snapshot publisher.
  private val listener: AtomicReference[HouseholdId => UIO[Unit]] =
    new AtomicReference(_ => ZIO.unit)

  /** Install what to notify after a household's check-ins were released. */
  def setOnReleased(f: HouseholdId => UIO[Unit]): UIO[Unit] = ZIO.succeed(listener.set(f))

  /**
   * Release every due check-in in `household`; returns how many were released. Metered as
   * `shared_device_checkout_job_total{outcome}` and `shared_device_checkout_job_duration_seconds`,
   * and refreshes the fleet-wide `shared_device_checkins_open` gauge.
   */
  def run(household: HouseholdId): Task[Int] =
    zio.Clock.nanoTime.flatMap { started =>
      releaseDue(household)
        .tap(n => ZIO.when(n > 0)(listener.get()(household)))
        .zipLeft(
          sharedDeviceRepo.countOpenCheckInsFleet.flatMap(AppMetrics.setSharedDeviceCheckinsOpen),
        )
        .onExit {
          case Exit.Failure(cause) if cause.isInterruptedOnly => ZIO.unit
          case exit                                           =>
            zio.Clock.nanoTime.flatMap { ended =>
              AppMetrics.recordSharedDeviceCheckoutJob(
                if (exit.isSuccess) "ok" else "error",
                (ended - started) / 1e9,
              )
            }
        }
    }

  private def releaseDue(household: HouseholdId): Task[Int] =
    sharedDeviceRepo.openCheckIns(household).flatMap {
      case Nil  => ZIO.succeed(0)
      case open =>
        for {
          now      <- clock.instant
          settings <- householdSettingsRepo.getForHousehold(household)
          reset = SharedDeviceCheckoutJob.lastResetAtOrBefore(now, settings)
          // Day states are only needed once a check-in survives the day-reset test.
          states   <-
            if (open.forall(_.startedAt.isBefore(reset))) ZIO.succeed(Map.empty)
            else
              timeStatusService.dayStateAll(
                household,
                now,
                PolicyService.householdLocalDate(now, settings),
                settings,
              )
          released <- ZIO.foreach(open) { ci =>
            dueRelease(household, ci, now, reset, states.get(ci.holder), settings).flatMap {
              case None              => ZIO.succeed(false)
              case Some((cause, at)) => release(household, ci, cause, at)
            }
          }
        } yield released.count(identity)
    }

  private def dueRelease(
      household: HouseholdId,
      ci: OpenCheckIn,
      now: Instant,
      reset: Instant,
      state: Option[ProfileDayState],
      settings: HouseholdSettings,
  ): Task[Option[(AssignmentEndCause, Instant)]] =
    SharedDeviceCheckoutJob.blockRelease(ci, now, reset, state) match {
      case some @ Some(_) => ZIO.succeed(some)
      case None           =>
        timeStatusService
          .lastEngagedAt(household, now, settings, ci.holder, ci.mac, ci.startedAt)
          .map(SharedDeviceCheckoutJob.idleRelease(ci, now, _, settings))
    }

  private def release(
      household: HouseholdId,
      ci: OpenCheckIn,
      cause: AssignmentEndCause,
      at: Instant,
  ): Task[Boolean] =
    sharedDeviceRepo.autoCheckOut(household, ci.deviceId, ci.holder, at, cause).flatMap {
      case CheckOutOutcome.CheckedOut =>
        AppMetrics.recordSharedDeviceCheckin("check_out", cause.db) *>
          LogContext
            .annotate(LogContext.Mac, ci.mac.value) {
              ZIO.logInfo(
                s"shared device auto-checked-out: household=${household.value} " +
                  s"profileId=${ci.holder.value} cause=${cause.db} at=$at",
              )
            }
            .as(true)
      // Released or re-taken since the read above (a user check-out racing the tick).
      case _                          => ZIO.succeed(false)
    }
}

object SharedDeviceCheckoutJob {

  val layer: ZLayer[
    SharedDeviceRepo & HouseholdSettingsRepo & TimeStatusService & Clock,
    Nothing,
    SharedDeviceCheckoutJob,
  ] = ZLayer.fromFunction(new SharedDeviceCheckoutJob(_, _, _, _))

  /**
   * The household's most recent daily reset at or before `now`: the start of the household-local
   * day containing `now` (`PolicyService.householdLocalDate`), i.e. the reset
   * `PolicyService.nextDailyResetAfter` named one day earlier.
   */
  def lastResetAtOrBefore(now: Instant, settings: HouseholdSettings): Instant =
    PolicyService
      .householdLocalDate(now, settings)
      .atTime(settings.dailyResetTime)
      .atZone(settings.dailyResetTz)
      .toInstant

  /**
   * Design §7.2 rows 7, 3–5: `day_reset` at the reset instant, else the holder's whole-MAC block
   * reason at `now`. `DefaultDeny` is a baseline mode, not a block-for-now (§15 Q3), so it never
   * releases.
   */
  def blockRelease(
      ci: OpenCheckIn,
      now: Instant,
      lastReset: Instant,
      holderState: Option[ProfileDayState],
  ): Option[(AssignmentEndCause, Instant)] =
    if (ci.startedAt.isBefore(lastReset)) Some(AssignmentEndCause.DayReset -> lastReset)
    else
      holderState.flatMap(_.blockReason).collect {
        case MacBlockReason.Paused    => AssignmentEndCause.Paused    -> now
        case MacBlockReason.Schedule  => AssignmentEndCause.Schedule  -> now
        case MacBlockReason.TimeLimit => AssignmentEndCause.TimeLimit -> now
      }

  /**
   * Design §7.2 row 6: `idle` once `sharedDeviceIdleMinutes` have passed since the last engaged
   * presence on the device, or since the check-in if there has been none.
   */
  def idleRelease(
      ci: OpenCheckIn,
      now: Instant,
      lastEngaged: Option[Instant],
      settings: HouseholdSettings,
  ): Option[(AssignmentEndCause, Instant)] = {
    val lastActive = lastEngaged.filter(_.isAfter(ci.startedAt)).getOrElse(ci.startedAt)
    val idleSince  = lastActive.plusSeconds(settings.sharedDeviceIdleMinutes.toLong * 60L)
    Option.when(!now.isBefore(idleSince))(AssignmentEndCause.Idle -> now)
  }
}
