package wifihaven.api.usage

import wifihaven.api.db.RollupRepo
import wifihaven.shared.Clock
import zio.*

import java.time.{Duration, LocalDate, ZoneId}

// #809: scheduled re-aggregation fibers. Both are designed so a missed tick
// (process restart, transient DB failure) self-heals on the next tick — the
// window we re-roll always extends N hours / days back from "now", and the
// UPSERT shape means re-rolling already-rolled buckets is a no-op semantically.
//
// Multi-instance safety: we don't currently run more than one API process per
// household, but #818 calls out that this should not catch fire if it ever
// does. The UPSERT is the safety net — two instances racing on the same
// bucket produce the same row. A pg_try_advisory_lock would still be cheaper
// (one instance does the work) but adds Flyway-version-skew risk; defer
// until #818 explicitly wires the lock pattern in.
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
  def hourlyLoop(repo: RollupRepo, clock: Clock): UIO[Unit] =
    runOnce("hourly", repo, clock, oneHourlyTick(repo, _))
      .repeat(Schedule.fixed(HourlyInterval))
      .unit

  /**
   * Daily fiber loop. Re-aggregates the trailing [[DailyLookback]] window into `traffic_daily`
   * every [[DailyInterval]]. Same error semantics as the hourly fiber.
   */
  def dailyLoop(repo: RollupRepo, clock: Clock, zone: ZoneId): UIO[Unit] =
    runOnce("daily", repo, clock, oneDailyTick(repo, _, zone))
      .repeat(Schedule.fixed(DailyInterval))
      .unit

  // ── tick bodies ────────────────────────────────────────────────────────────

  private def oneHourlyTick(repo: RollupRepo, clock: Clock): Task[Int] =
    for {
      now <- clock.instant
      since = now.minus(HourlyLookback).truncatedTo(java.time.temporal.ChronoUnit.HOURS)
      n <- repo.rerollHourly(since)
    } yield n

  private def oneDailyTick(repo: RollupRepo, clock: Clock, zone: ZoneId): Task[Int] =
    for {
      now <- clock.instant
      sinceDate = LocalDate.ofInstant(now, zone).minus(DailyLookback)
      n <- repo.rerollDaily(sinceDate)
    } yield n

  // ── shared run wrapper ─────────────────────────────────────────────────────

  private def runOnce(
      job: String,
      repo: RollupRepo,
      clock: Clock,
      body: Clock => Task[Int],
  ): UIO[Unit] =
    for {
      started  <- clock.instant
      result   <- body(clock).either
      finished <- clock.instant
      _        <- result match {
        case Right(n) =>
          ZIO.logInfo(s"rollup $job tick ok rows=$n") *>
            repo.recordRun(job, started, finished, "ok", None, n).ignore
        case Left(e)  =>
          ZIO.logErrorCause(s"rollup $job tick failed", Cause.fail(e)) *>
            repo
              .recordRun(job, started, finished, "error", Some(e.getMessage.take(500)), 0)
              .ignore
      }
    } yield ()
}
