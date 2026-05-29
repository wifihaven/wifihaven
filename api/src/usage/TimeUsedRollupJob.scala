package wifihaven.api.usage

import wifihaven.api.db.{HouseholdSettingsRepo, RollupRepo, TimeUsedRollupRepo}
import wifihaven.api.policy.{PolicyService, TimeStatusService}
import wifihaven.shared.Clock
import zio.*

import java.time.{Duration, Instant, LocalDate}

/**
 * #1160: scheduled re-aggregation of `TimeStatusService.dayStateAll(...).usedMinutes` into
 * `time_used_daily`. Mirrors the byte-rollup fiber pattern in [[RollupJobs]] — fiber loop, per-tick
 * advisory lock, runs recorded in `rollup_runs` so the existing /api/admin/rollup-status surface
 * picks the new job up by name.
 *
 * Scope (v1): caches `usedMinutes` for past dates only — today stays live (and so does enforcement,
 * since `PolicyService.snapshot` always evaluates today). The fiber re-rolls a trailing window so
 * yesterday lands in the cache shortly after the midnight rollover. Per-site / per-app rollups and
 * a today-tier are tracked separately.
 *
 * Invalidation is wholesale via [[TimeUsedRollupRepo.deleteAll]] (called from the
 * `household_settings` UPDATE — covers `daily_reset_tz`, `daily_reset_time`, and any heartbeat-
 * filter knob). After invalidation the fiber refills on its next tick.
 */
object TimeUsedRollupJob {

  /** Trailing window the fiber re-rolls every tick. Bounded by the raw retention horizon (#811). */
  val LookbackDays: Int = 30

  /** Refresh cadence — keeps the cache reasonably fresh for the UI's perceived-latency budget. */
  val Interval: Duration = Duration.ofMinutes(5)

  /**
   * Fiber loop entry point. Mirrors [[RollupJobs.hourlyLoop]]: tick body either succeeds (records a
   * row), skips on advisory-lock contention (debug log), or fails (records error). Never dies.
   */
  def loop(
      rollup: TimeUsedRollupRepo,
      runs: RollupRepo,
      svc: TimeStatusService,
      hs: HouseholdSettingsRepo,
      clock: Clock,
  ): UIO[Unit] =
    runOnce(rollup, runs, svc, hs, clock)
      .repeat(Schedule.fixed(Interval))
      .unit

  /**
   * Single tick body, exposed for the test that exercises one date end-to-end without going through
   * the fiber loop. The contract is the same as the production tick: compute live, persist via
   * upsertBatch, return the count of profiles rolled.
   */
  def oneTickForTest(
      rollup: TimeUsedRollupRepo,
      svc: TimeStatusService,
      hs: HouseholdSettingsRepo,
      now: Instant,
      date: LocalDate,
  ): Task[Int] =
    for {
      settings <- hs.get
      states   <- svc.dayStateAllLive(now, date, settings)
      n        <- rollup.upsertBatch(date, states.view.mapValues(_.usedMinutes).toMap)
    } yield n

  // ── internals ──────────────────────────────────────────────────────────────

  private def runOnce(
      rollup: TimeUsedRollupRepo,
      runs: RollupRepo,
      svc: TimeStatusService,
      hs: HouseholdSettingsRepo,
      clock: Clock,
  ): UIO[Unit] =
    for {
      started  <- clock.instant
      result   <- doTick(rollup, svc, hs, clock).either
      finished <- clock.instant
      _        <- result match {
        case Right(n) =>
          ZIO.logInfo(s"rollup time_used_daily tick ok rows=$n") *>
            runs.recordRun("time_used_daily", started, finished, "ok", None, n).ignore
        case Left(e)  =>
          ZIO.logErrorCause("rollup time_used_daily tick failed", Cause.fail(e)) *>
            runs
              .recordRun(
                "time_used_daily",
                started,
                finished,
                "error",
                Some(e.getMessage.take(500)),
                0,
              )
              .ignore
      }
    } yield ()

  // Re-roll the trailing window of past dates (today is excluded — today reads
  // live so enforcement and /summary agree, and writing today on every tick
  // would just be noise the UI never reads). Caps at LookbackDays.
  private def doTick(
      rollup: TimeUsedRollupRepo,
      svc: TimeStatusService,
      hs: HouseholdSettingsRepo,
      clock: Clock,
  ): Task[Int] =
    for {
      now      <- clock.instant
      settings <- hs.get
      today = PolicyService.householdLocalDate(now, settings)
      // Iterate yesterday → today − LookbackDays, oldest first so a partial
      // failure leaves the most-stale days re-rolled.
      dates = (1 to LookbackDays).map(d => today.minusDays(d.toLong)).toList.reverse
      count <- ZIO.foldLeft(dates)(0) { (acc, d) =>
        for {
          states <- svc.dayStateAllLive(now, d, settings)
          n      <- rollup.upsertBatch(d, states.view.mapValues(_.usedMinutes).toMap)
        } yield acc + n
      }
    } yield count
}
