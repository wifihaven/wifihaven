package wifihaven.api.db

import zio.*

import java.time.Instant

/**
 * #808 (design: docs/design/db-partitioning.md §"Auto-create future partitions"): the data-access
 * half of the in-process job that keeps the weekly RANGE-partitioned ingest tables provisioned a
 * few weeks ahead so router writes never hit a missing range. This is the durable fix for the
 * 2026-06-29 P0 (#2053): V41/V42 created only a fixed 5-week runway at migration time and deferred
 * ongoing creation to this issue, which never shipped — the runway exhausted at ISO week 2026-27
 * and every `POST /api/router/{usage,events}` insert 503'd.
 *
 * SQL lives here per the "no SQL outside *RepoLive" convention; the scheduling, logging and metric
 * emission live in [[wifihaven.api.usage.PartitionMaintenanceJob]].
 */
trait PartitionRepo {

  /**
   * Ensure weekly partitions exist for `[current ISO week, +weeksAhead]` on every partitioned
   * table, then report how many consecutive weeks of runway each table now has.
   *
   * Idempotent (`CREATE TABLE IF NOT EXISTS … PARTITION OF`) and multi-instance-safe: the whole
   * pass runs under a session-scoped Postgres advisory lock on one connection, so two API instances
   * can't race on the `AccessExclusiveLock` the partition-attach takes on the parent (design
   * §"Horizontal-scaling safety"). If another instance holds the lock this returns `None` without
   * touching the catalog.
   *
   * `now` is injected (not `CURRENT_DATE`) so the schedule is driven by the shared
   * [[wifihaven.shared.Clock]] and tests are deterministic; the Monday/ISO-week derivation matches
   * V41/V42 exactly (`date_trunc('week', …)` Monday boundaries, `to_char(wk,'IYYY_IW')` names).
   */
  def ensureFuturePartitions(
      weeksAhead: Int,
      now: Instant,
  ): Task[Option[List[PartitionRepo.TableResult]]]

  /**
   * #812 (design: docs/design/db-partitioning.md §"Retention via partition-drop"): for each
   * partitioned table, `ALTER TABLE ... DETACH PARTITION` + `DROP TABLE` every weekly partition
   * whose upper bound falls at or before `now - RetentionDays(table)` — metadata-only, no row scan
   * or vacuum debt, unlike [[wifihaven.api.usage.RetentionSweepJob]]'s row-`DELETE`.
   *
   * The `_pre_partition` historical omnibus partition is never a candidate — only partitions named
   * `<table>_<IYYY_IW>` are considered, and the omnibus carries a different name entirely, so it is
   * structurally excluded rather than filtered.
   *
   * Multi-instance-safe via its own session-scoped advisory lock
   * ([[PartitionRepo.RetentionDropAdvisoryLockKey]]), independent of both the create-future job's
   * lock and [[wifihaven.api.usage.RetentionSweepJob]]'s lock, so all three can interleave across
   * instances without racing on the same lock. If another instance holds this lock, returns `None`
   * without touching the catalog.
   *
   * A table whose configured retention is `< 1` day is refused (logged, zero drops for that table)
   * rather than executed — the guard against a misconfigured `0`-or-negative window wiping
   * everything. This can never drop the partition containing `now` itself: a candidate partition's
   * upper bound is always `<= now - RetentionDays(table)` by construction, which is strictly before
   * the partition holding the current instant.
   *
   * `retentionDaysByTable` defaults to [[PartitionRepo.RetentionDaysByTable]] (the single-sourced
   * production horizons) — the parameter exists so tests can pin a short window / an out-of-bounds
   * value deterministically without touching the production constants.
   */
  def dropExpiredPartitions(
      now: Instant,
      retentionDaysByTable: Map[String, Int] = PartitionRepo.RetentionDaysByTable,
  ): Task[Option[List[PartitionRepo.DropResult]]]
}

object PartitionRepo {

  /**
   * The weekly RANGE-partitioned tables — the SINGLE source of truth for which tables the
   * maintenance job provisions. `traffic_reports` (V41, RANGE on `period_start`) and
   * `connection_events` (V42, RANGE on `ts`). Adding a third partitioned table is a one-line edit
   * here; nothing else hardcodes the pair (`AGENTS.md#single-source-of-truth`).
   */
  val PartitionedTables: List[String] = List("traffic_reports", "connection_events")

  /**
   * Reserved session advisory-lock key for the partition-create pass. Distinct from
   * [[wifihaven.api.usage.RetentionSweepJob.AdvisoryLockKey]] (`0x726c757073770001`) so the
   * create-future and retention-drop jobs hold independent locks and can interleave across
   * instances (design §"Horizontal-scaling safety"). Do not reuse for any other advisory lock.
   */
  val AdvisoryLockKey: Long = 0x70617274_6e770001L

  /**
   * #812: reserved session advisory-lock key for the partition-drop (retention) pass. Distinct from
   * both [[AdvisoryLockKey]] (create-future) and
   * [[wifihaven.api.usage.RetentionSweepJob.AdvisoryLockKey]] (row-`DELETE` sweep) so all three
   * passes hold independent locks and can interleave across instances (design §"Horizontal-scaling
   * safety"). Do not reuse for any other advisory lock.
   */
  val RetentionDropAdvisoryLockKey: Long = 0x70617274_64770001L

  /**
   * Retention window (days) per partitioned table, reused from the single-sourced constants
   * [[wifihaven.api.usage.RetentionSweepJob.RawRetentionDays]] /
   * [[wifihaven.api.usage.RetentionSweepJob.EventsRetentionDays]] rather than a second literal —
   * partition-drop and the row-`DELETE` sweep must agree on the same horizon per table
   * (`AGENTS.md#single-source-of-truth`).
   */
  val RetentionDaysByTable: Map[String, Int] = Map(
    "traffic_reports"   -> wifihaven.api.usage.RetentionSweepJob.RawRetentionDays,
    "connection_events" -> wifihaven.api.usage.RetentionSweepJob.EventsRetentionDays,
  )

  /** How far ahead [[consecutiveWeeksAheadCap]] probes when measuring runway. */
  private[db] val ConsecutiveWeeksAheadCap: Int = 60

  /**
   * #812: how far back the retention-drop pass probes for existing-but-expired weekly partitions,
   * starting from the retention cutoff. 520 weeks (10 years) comfortably covers a job that hasn't
   * run in years without an unbounded scan; each probed week is a single cheap `to_regclass`
   * catalog lookup, not a table scan.
   */
  private[db] val RetentionDropProbeCapWeeks: Int = 520

  /**
   * Per-table outcome of one pass: which partitions were freshly created (for the INFO log) and the
   * resulting runway — the count of consecutive weekly partitions present starting from the current
   * ISO week. `weeksAhead = 0` means the current week itself has no partition (the #2053 outage
   * state); the maintenance job emits this as the `partition_weeks_ahead{table}` gauge the runway
   * alert watches.
   */
  final case class TableResult(table: String, created: List[String], weeksAhead: Int)

  /**
   * One partition DETACHed+DROPped by the retention pass, with its row count at drop time (audit).
   */
  final case class DroppedPartition(name: String, rows: Long)

  /**
   * Per-table outcome of one retention-drop pass. `skippedReason` is set (and `dropped` is empty)
   * when the table's configured retention was out of bounds (`< 1` day) and the pass refused to run
   * for that table rather than risk wiping everything.
   */
  final case class DropResult(
      table: String,
      dropped: List[DroppedPartition],
      skippedReason: Option[String],
  )
}
