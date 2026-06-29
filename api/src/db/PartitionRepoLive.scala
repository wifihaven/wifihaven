package wifihaven.api.db

import cats.implicits.*
import doobie.*
import doobie.free.connection.{pure as cpure, raiseError as craiseError}
import doobie.implicits.*
import doobie.postgres.implicits.*
import zio.*
import zio.interop.catz.*

import java.time.{Instant, LocalDate}

/**
 * #808 (design: docs/design/db-partitioning.md): create-future-partitions data access. See
 * [[PartitionRepo]] for the contract. Mirrors [[wifihaven.api.usage.RetentionSweepJob]]'s
 * advisory-lock shape: the lock acquire, the create pass and the unlock all run on ONE connection
 * (`cio.transact(xa)`), so the session-scoped lock covers exactly the pass and auto-releases if the
 * connection drops.
 */
final case class PartitionRepoLive(xa: Transactor[Task]) extends PartitionRepo {

  import PartitionRepo.*

  def ensureFuturePartitions(
      weeksAhead: Int,
      now: Instant,
  ): Task[Option[List[TableResult]]] = {
    val epochSeconds = now.toEpochMilli.toDouble / 1000.0

    val body: ConnectionIO[List[TableResult]] =
      PartitionedTables.traverse(ensureTable(_, weeksAhead, epochSeconds))

    // Acquire the session advisory lock; if another instance holds it, return None untouched.
    // Otherwise run the pass and release on the SAME connection whether it succeeds or fails,
    // re-raising any error after the unlock (so the lock never leaks).
    val cio: ConnectionIO[Option[List[TableResult]]] =
      sql"SELECT pg_try_advisory_lock($AdvisoryLockKey)".query[Boolean].unique.flatMap {
        case false => cpure(Option.empty[List[TableResult]])
        case true  =>
          body.attempt.flatMap { res =>
            sql"SELECT pg_advisory_unlock($AdvisoryLockKey)".query[Boolean].unique.flatMap { _ =>
              res match {
                case Right(rs) => cpure(Some(rs))
                case Left(e)   => craiseError[Option[List[TableResult]]](e)
              }
            }
          }
      }
    cio.transact(xa)
  }

  // One row of the week plan: the ISO-week suffix, the Monday/next-Monday bounds, and whether the
  // partition already exists. Names + bounds are computed in SQL so they match V41/V42 byte-for-byte
  // (date_trunc('week', …) Monday, to_char(wk,'IYYY_IW'), [Monday, Monday+7) ).
  private final case class WeekRow(iw: String, lo: LocalDate, hi: LocalDate, exists: Boolean)

  private def ensureTable(
      table: String,
      weeksAhead: Int,
      epochSeconds: Double,
  ): ConnectionIO[TableResult] =
    for {
      plan <- weekPlan(table, weeksAhead, epochSeconds)
      missing = plan.filterNot(_.exists)
      _     <- missing.traverse_(createPartition(table, _))
      ahead <- consecutiveWeeksAhead(table, epochSeconds)
    } yield TableResult(table, missing.map(r => s"${table}_${r.iw}"), ahead)

  // weeks 0..weeksAhead from the Monday of `now`, each with its ISO-week name, [lo, hi) bounds, and
  // current existence. The prefix is a constant from PartitionedTables — never user input.
  private def weekPlan(
      table: String,
      weeksAhead: Int,
      epochSeconds: Double,
  ): ConnectionIO[List[WeekRow]] = {
    val prefix = s"${table}_"
    sql"""
      SELECT to_char(d, 'IYYY_IW') AS iw,
             d::date                AS lo,
             (d + 7)::date          AS hi,
             to_regclass($prefix || to_char(d, 'IYYY_IW')) IS NOT NULL AS already
      FROM (
        SELECT (date_trunc('week', to_timestamp($epochSeconds))::date + (gs * 7)) AS d
        FROM generate_series(0, $weeksAhead) AS gs
      ) w
      ORDER BY d
    """.query[(String, LocalDate, LocalDate, Boolean)].to[List].map(_.map(WeekRow.apply.tupled))
  }

  // CREATE TABLE IF NOT EXISTS <table>_<iw> PARTITION OF <table> FOR VALUES FROM (Monday) TO
  // (Monday+7) — the EXACT scheme V41/V42 use. Identifiers + date bounds derive from the table
  // constant + Postgres date arithmetic (never user input), so Fragment.const is safe here. A bare
  // ISO date literal on a TIMESTAMPTZ partition key coerces to session-tz midnight, identical to
  // V41's `wk::timestamptz`.
  private def createPartition(table: String, row: WeekRow): ConnectionIO[Unit] = {
    val name = s"${table}_${row.iw}"
    val ddl  =
      s"CREATE TABLE IF NOT EXISTS $name PARTITION OF $table " +
        s"FOR VALUES FROM ('${row.lo}') TO ('${row.hi}')"
    Fragment.const(ddl).update.run.void
  }

  // Runway = number of consecutive weekly partitions present starting at the current ISO week. The
  // first week index (from 0) with NO partition is exactly that count; 0 means even the current week
  // is missing (the #2053 outage state). Capped probe so a fully-provisioned table returns the cap+1
  // rather than scanning forever.
  private def consecutiveWeeksAhead(table: String, epochSeconds: Double): ConnectionIO[Int] = {
    val prefix = s"${table}_"
    val cap    = ConsecutiveWeeksAheadCap
    sql"""
      SELECT COALESCE(MIN(gs), ${cap + 1})
      FROM generate_series(0, $cap) AS gs
      WHERE to_regclass(
        $prefix || to_char(
          (date_trunc('week', to_timestamp($epochSeconds))::date + (gs * 7)), 'IYYY_IW'
        )
      ) IS NULL
    """.query[Int].unique
  }
}
