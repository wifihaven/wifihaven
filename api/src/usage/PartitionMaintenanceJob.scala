package wifihaven.api.usage

import wifihaven.api.db.PartitionRepo
import wifihaven.api.metrics.AppMetrics
import wifihaven.shared.Clock
import zio.*

import java.time.Instant

/**
 * #808 (design: docs/design/db-partitioning.md §"Auto-create future partitions"): the scheduling +
 * observability half of the partition-maintenance job. It keeps the weekly RANGE-partitioned ingest
 * tables (traffic_reports V41, connection_events V42) provisioned a configurable number of weeks
 * ahead so router writes never hit a missing range — the durable fix for the 2026-06-29 P0 (#2053),
 * where the fixed 5-week runway V41/V42 seeded ran out at ISO week 2026-27 and every `POST
 * /api/router/{usage,events}` insert 503'd.
 *
 * All SQL lives in [[PartitionRepo]] per the "no SQL outside *RepoLive" convention; this object
 * only drives the clock, logs created partitions at INFO, and publishes the runway gauge.
 *
 * Multi-instance-safe via the repo's session advisory lock: a tick that loses the race returns
 * `None` and is a quiet no-op (another instance is provisioning), so this never double-creates or
 * races on the parent's attach lock.
 */
object PartitionMaintenanceJob {

  /**
   * Tick cadence. `Schedule.fixed` runs the effect once immediately (the startup pass — critical,
   * since this IS the runway guard) and then every 24 h, satisfying "runs at startup and every 24h"
   * from #808 without needing a wall-clock hour alignment.
   */
  val Interval: Duration = 24.hours

  /**
   * One maintenance pass. Ensures `[current week, +weeksAhead]` partitions exist on every
   * partitioned table, logs any freshly-created partitions at INFO, and publishes
   * `partition_weeks_ahead{table}` for each table. Never fails — DB errors are logged (a
   * pool-closed-during-shutdown race is benign, logged at DEBUG); the fiber stays alive. `now` is
   * supplied by the caller (the [[Clock]] in `start`, a fixed instant in tests).
   */
  def runOnce(repo: PartitionRepo, weeksAhead: Int, now: Instant): UIO[Unit] =
    repo
      .ensureFuturePartitions(weeksAhead, now)
      .either
      .flatMap {
        case Right(None)                               =>
          ZIO.logDebug(
            "PartitionMaintenanceJob: advisory lock held by another instance — tick skipped",
          )
        case Right(Some(results))                      =>
          ZIO.foreachDiscard(results) { r =>
            val createdLog =
              ZIO
                .logInfo(
                  s"PartitionMaintenanceJob: created partitions for ${r.table}: " +
                    r.created.mkString(", "),
                )
                .when(r.created.nonEmpty)
                .unit
            createdLog *> AppMetrics.setPartitionWeeksAhead(r.table, r.weeksAhead)
          } *> {
            val summary =
              results.map(r => s"${r.table}=${r.weeksAhead}w(+${r.created.size})").mkString(", ")
            ZIO.logInfo(s"PartitionMaintenanceJob: runway [$summary]")
          }
        case Left(e) if RollupShutdown.isPoolClosed(e) =>
          ZIO.logDebug(
            "PartitionMaintenanceJob: tick aborted (pool closed during shutdown)",
          )
        case Left(e)                                   =>
          ZIO.logErrorCause("PartitionMaintenanceJob: tick failed", Cause.fail(e))
      }

  /**
   * #812: one retention-drop pass. DETACHes + DROPs every weekly partition that has aged past its
   * table's retention window (metadata-only — see [[PartitionRepo.dropExpiredPartitions]]), logs
   * each drop at INFO with its row count, and increments `partitions_dropped_total{table}`. Never
   * fails — DB errors are logged (pool-closed-during-shutdown is benign, logged at DEBUG); the
   * fiber stays alive. `now` is supplied by the caller (the [[Clock]] in `start`, a fixed instant
   * in tests).
   */
  def runDropPass(repo: PartitionRepo, now: Instant): UIO[Unit] =
    repo
      .dropExpiredPartitions(now)
      .either
      .flatMap {
        case Right(None)                               =>
          ZIO.logDebug(
            "PartitionMaintenanceJob: retention-drop lock held by another instance — tick skipped",
          )
        case Right(Some(results))                      =>
          ZIO.foreachDiscard(results) { r =>
            r.skippedReason match {
              case Some(reason) =>
                ZIO.logWarning(
                  s"PartitionMaintenanceJob: retention-drop skipped ${r.table}: $reason",
                )
              case None         =>
                val droppedLog =
                  ZIO
                    .logInfo(
                      s"PartitionMaintenanceJob: dropped partitions for ${r.table}: " +
                        r.dropped.map(d => s"${d.name}(${d.rows} rows)").mkString(", "),
                    )
                    .when(r.dropped.nonEmpty)
                    .unit
                droppedLog *> AppMetrics.incrementPartitionsDropped(r.table, r.dropped.size)
            }
          }
        case Left(e) if RollupShutdown.isPoolClosed(e) =>
          ZIO.logDebug(
            "PartitionMaintenanceJob: retention-drop tick aborted (pool closed during shutdown)",
          )
        case Left(e)                                   =>
          ZIO.logErrorCause("PartitionMaintenanceJob: retention-drop tick failed", Cause.fail(e))
      }

  /**
   * Fork a daemon fiber that runs both the create-future pass and the retention-drop pass (#812) at
   * startup and every 24 h — the same scheduled job drives both, per the design. `weeksAhead` comes
   * from config; `clock` supplies each tick's `now` so the schedule honours the shared injected
   * clock.
   */
  def start(repo: PartitionRepo, weeksAhead: Int, clock: Clock): UIO[Fiber.Runtime[Nothing, Long]] =
    for {
      _     <- ZIO.logInfo(
        s"PartitionMaintenanceJob scheduled: startup pass now, then every 24h " +
          s"(keeping $weeksAhead weeks of partitions ahead)",
      )
      fiber <- clock.instant
        .flatMap(now => runOnce(repo, weeksAhead, now) *> runDropPass(repo, now))
        .repeat(Schedule.fixed(Interval))
        .forkDaemon
    } yield fiber
}
