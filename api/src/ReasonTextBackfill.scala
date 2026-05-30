package wifihaven.api

import doobie.*
import doobie.implicits.*
import zio.*
import zio.interop.catz.*

/**
 * #1176 / #1179: one-shot backfill that populates `reason_text` (pre-V40 wire format) on rows
 * inserted between V40 (TEXT → JSONB conversion) and V44 (the dual-write).
 *
 * Idempotent: `WHERE reason_text IS NULL`. Capped throughput — runs in id-range batches with a
 * short sleep between batches so a slow Render PG doesn't see a burst on cold start. The kind →
 * wire conversion is done in SQL with a CASE on `reason->>'kind'` that mirrors exactly what
 * [[wifihaven.shared.BlockReason.asWire]] produces; the dual-write spec and the backfill spec pin
 * both sides to the same canonical strings.
 *
 * Reads still come from `reason` (JSONB) so a partial backfill never affects the SPA — V45 (out of
 * scope here) will cut reads to `reason_text` and drop the JSONB column once this has settled.
 */
object ReasonTextBackfill {

  // Tuned for Render's small managed PG. Each batch updates up to BatchSize rows in a single
  // UPDATE; SleepBetween throttles so we never monopolize the connection pool on cold start.
  private val BatchSize: Int     = 5000
  private val SleepBetween       = Duration.fromMillis(100)
  // Hard cap so a runaway backfill (e.g. a stuck row) can't loop forever and block startup logs.
  // Even at 5k/batch this covers 50M rows — well above prod's foreseeable size.
  private val MaxBatchesPerTable = 10_000

  /**
   * SQL fragment that converts a `reason` JSONB into its pre-V40 wire-format TEXT. Must agree with
   * `BlockReason.asWire` for every kind. `unknown` rows emit their `raw` field verbatim so a second
   * `fromWire` reparse yields the same `Unknown(raw)`.
   */
  private val asWireSql: Fragment =
    fr"""CASE reason->>'kind'
           WHEN 'allow'         THEN 'allow'
           WHEN 'blocked'       THEN 'blocked'
           WHEN 'extraAllowed'  THEN 'extra_allowed'
           WHEN 'extraBlocked'  THEN 'extra_blocked'
           WHEN 'noProfile'     THEN 'no_profile'
           WHEN 'paused'        THEN 'paused'
           WHEN 'schedule'      THEN 'schedule'
           WHEN 'timeLimit'     THEN 'time_limit'
           WHEN 'manual'        THEN 'manual'
           WHEN 'unmanaged'     THEN 'unmanaged_mac'
           WHEN 'category'      THEN 'category:'        || (reason->>'slug')
           WHEN 'siteTimeLimit' THEN 'site_time_limit:' || (reason->>'label')
           WHEN 'appBlocked'    THEN 'app:'             || (reason->>'appId')
           WHEN 'unknown'       THEN reason->>'raw'
         END"""

  /** Backfill both event tables. Safe to invoke at every startup. */
  def run(xa: Transactor[Task]): Task[Unit] =
    for {
      _  <- ZIO.logInfo("reason_text backfill: starting")
      n1 <- backfillTable(xa, "connection_events")
      n2 <- backfillTable(xa, "block_events")
      _  <- ZIO.logInfo(
        s"reason_text backfill: complete (connection_events=$n1, block_events=$n2 rows updated)",
      )
    } yield ()

  private def backfillTable(xa: Transactor[Task], table: String): Task[Long] = {
    // Repeatedly UPDATE up to BatchSize NULL rows until none remain. `id` is unique across both
    // tables (sequence-backed, retained as a surrogate even after V42 dropped the PK from the
    // partitioned connection_events) so an id-based chunk avoids long lock holds and replays
    // cleanly if the API restarts mid-backfill.
    val tbl                   = Fragment.const(table)
    val updateOnce: Task[Int] =
      (fr"""UPDATE """ ++ tbl ++ fr"""
            SET reason_text = """ ++ asWireSql ++ fr"""
            WHERE id IN (
              SELECT id FROM """ ++ tbl ++ fr"""
              WHERE reason_text IS NULL
              ORDER BY id
              LIMIT $BatchSize
            )""").update.run.transact(xa)

    def loop(batch: Int, total: Long): Task[Long] =
      if batch >= MaxBatchesPerTable then
        ZIO
          .logWarning(
            s"reason_text backfill: hit MaxBatchesPerTable=$MaxBatchesPerTable on $table at $total rows; giving up — re-run on next startup",
          )
          .as(total)
      else
        updateOnce.flatMap { updated =>
          if updated == 0 then ZIO.succeed(total)
          else {
            val newTotal = total + updated.toLong
            ZIO.logDebug(
              s"reason_text backfill: $table batch=$batch updated=$updated total=$newTotal",
            ) *>
              ZIO.sleep(SleepBetween) *>
              loop(batch + 1, newTotal)
          }
        }

    loop(0, 0L)
  }
}
