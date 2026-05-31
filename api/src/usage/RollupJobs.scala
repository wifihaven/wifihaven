package wifihaven.api.usage

import wifihaven.api.db.{AppRepo, RollupRepo}
import wifihaven.shared.Clock
import wifihaven.shared.types.AppId
import zio.*

import java.time.{Duration, LocalDate, ZoneId}

// #809: scheduled re-aggregation fibers. Both are designed so a missed tick
// (process restart, transient DB failure) self-heals on the next tick — the
// window we re-roll always extends N hours / days back from "now", and the
// UPSERT shape means re-rolling already-rolled buckets is a no-op semantically.
//
// Multi-instance safety (#818): each tick is gated by a transaction-scoped
// `pg_try_advisory_xact_lock` keyed off `RollupLockKeys` — only one instance
// rolls per tick, the rest log a skip and move on. The UPSERT is still the
// correctness floor (re-rolling the same bucket twice can't corrupt anything)
// but the lock keeps redundant DB work from doubling as the household scales
// to multiple API processes.
//
// Failure handling: catch all errors, record them in rollup_runs, never let
// the fiber die. The admin /api/admin/rollup-status endpoint shows the last N
// runs so an operator can tell at a glance whether the rollups are alive.
object RollupJobs {

  // How many hours back the hourly fiber re-rolls every tick. Two hours is
  // enough cushion for the closed-hour boundary plus any router clock skew
  // (#9) without re-aggregating a meaningful slice of history each pass.
  val HourlyLookback: Duration = Duration.ofHours(2)

  // The daily fiber re-rolls today + yesterday every tick — yesterday because
  // late-arriving router posts can extend the previous day's traffic_reports
  // rows past midnight, and today because the rollup should be no more than
  // an hour stale.
  val DailyLookback: java.time.Period = java.time.Period.ofDays(1)

  val HourlyInterval: Duration = Duration.ofMinutes(5)
  val DailyInterval: Duration  = Duration.ofMinutes(60)

  /**
   * Hourly fiber loop. Re-aggregates the trailing [[HourlyLookback]] window into `traffic_hourly`
   * every [[HourlyInterval]]. Errors are caught and recorded; the fiber never dies.
   */
  def hourlyLoop(repo: RollupRepo, appRepo: AppRepo, clock: Clock): UIO[Unit] =
    runOnce("hourly", repo, clock, oneHourlyTick(repo, appRepo, _))
      .repeat(Schedule.fixed(HourlyInterval))
      .unit

  /**
   * Daily fiber loop. Re-aggregates the trailing [[DailyLookback]] window into `traffic_daily`
   * every [[DailyInterval]]. Same error semantics as the hourly fiber.
   */
  def dailyLoop(repo: RollupRepo, appRepo: AppRepo, clock: Clock, zone: ZoneId): UIO[Unit] =
    runOnce("daily", repo, clock, oneDailyTick(repo, appRepo, _, zone))
      .repeat(Schedule.fixed(DailyInterval))
      .unit

  // ── tick bodies ────────────────────────────────────────────────────────────

  // The app-hosts snapshot is read once per tick and stamped onto every rolled
  // row (#1091). Reading it here (rather than per-row) keeps the version the
  // rows are stamped at consistent for the whole reroll.
  private def appSnapshot(appRepo: AppRepo): Task[(Map[String, List[AppId]], Long)] =
    for {
      mappings <- appRepo.listAllHostMappings
      version  <- appRepo.currentHostsVersion
    } yield (mappings.groupBy(_.host.value).view.mapValues(_.map(_.appId)).toMap, version)

  private def oneHourlyTick(repo: RollupRepo, appRepo: AppRepo, clock: Clock): Task[Option[Int]] =
    for {
      now <- clock.instant
      since = now.minus(HourlyLookback).truncatedTo(java.time.temporal.ChronoUnit.HOURS)
      snap <- appSnapshot(appRepo)
      (appsByApex, version) = snap
      n <- repo.rerollHourly(since, appsByApex, version)
    } yield n

  private def oneDailyTick(
      repo: RollupRepo,
      appRepo: AppRepo,
      clock: Clock,
      zone: ZoneId,
  ): Task[Option[Int]] =
    for {
      now <- clock.instant
      sinceDate = LocalDate.ofInstant(now, zone).minus(DailyLookback)
      snap <- appSnapshot(appRepo)
      (appsByApex, version) = snap
      n <- repo.rerollDaily(sinceDate, appsByApex, version)
    } yield n

  // ── shared run wrapper ─────────────────────────────────────────────────────

  private def runOnce(
      job: String,
      repo: RollupRepo,
      clock: Clock,
      body: Clock => Task[Option[Int]],
  ): UIO[Unit] =
    for {
      started  <- clock.instant
      result   <- body(clock).either
      finished <- clock.instant
      _        <- result match {
        case Right(Some(n)) =>
          ZIO.logInfo(s"rollup $job tick ok rows=$n") *>
            repo.recordRun(job, started, finished, "ok", None, n).ignore
        case Right(None)    =>
          // Another instance held the advisory lock — expected under
          // multi-instance deploys, log at debug and don't write a
          // rollup_runs row (would noise up the admin view).
          ZIO.logDebug(s"rollup $job tick skipped (advisory lock held)")
        case Left(e)        =>
          ZIO.logErrorCause(s"rollup $job tick failed", Cause.fail(e)) *>
            repo
              .recordRun(job, started, finished, "error", Some(e.getMessage.take(500)), 0)
              .ignore
      }
    } yield ()
}
