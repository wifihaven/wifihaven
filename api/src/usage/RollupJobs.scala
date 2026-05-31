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

  // How many hours back the hourly fiber re-rolls every tick. Two hours covers
  // the closed-hour boundary plus router clock skew (#9), and — now that the
  // fiber ticks hourly (#1230) — gives one tick of self-heal slack: a missed
  // tick's hour is still inside the next tick's 2h window. Must stay
  // >= HourlyInterval or a missed tick drops a bucket (see RollupCadenceSpec).
  val HourlyLookback: Duration = Duration.ofHours(2)

  // The daily fiber re-rolls the trailing two days every tick: yesterday +
  // today (late-arriving router posts extend the previous day past midnight),
  // plus one extra day of slack so a missed daily tick (#1230 widened the
  // cadence to 24h) still self-heals on the next one. Must stay >= 2 days
  // while DailyInterval is 24h (see RollupCadenceSpec).
  val DailyLookback: java.time.Period = java.time.Period.ofDays(2)

  // #1230: re-roll cadences match the tier each table is read at, not the
  // bucket name. `pickTier` (UsageRoutes) reads ≤24h windows from raw
  // traffic_reports, 24h–14d from traffic_hourly, >14d from traffic_daily — so
  // a rolled bucket isn't read until it crosses its threshold. Rolling far more
  // often just burns DB work on the 256 MB prod instance (#1228). Hourly cadence
  // gives every hour ~24 re-rolls of slack before it's first read; daily cadence
  // gives every day ~14 days. The trailing-window lookbacks above keep a single
  // missed tick self-healing.
  val HourlyInterval: Duration = Duration.ofHours(1)
  val DailyInterval: Duration  = Duration.ofHours(24)

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

  // `private[api]` so the shutdown-handling spec can drive a single tick with an
  // injected body (a failing/never-completing effect) without going through the
  // forever-looping fiber. The production `body` is always one of the tick
  // functions above.
  private[api] def runOnce(
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
