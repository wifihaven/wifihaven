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

  /** How far ahead [[consecutiveWeeksAheadCap]] probes when measuring runway. */
  private[db] val ConsecutiveWeeksAheadCap: Int = 60

  /**
   * Per-table outcome of one pass: which partitions were freshly created (for the INFO log) and the
   * resulting runway — the count of consecutive weekly partitions present starting from the current
   * ISO week. `weeksAhead = 0` means the current week itself has no partition (the #2053 outage
   * state); the maintenance job emits this as the `partition_weeks_ahead{table}` gauge the runway
   * alert watches.
   */
  final case class TableResult(table: String, created: List[String], weeksAhead: Int)
}
