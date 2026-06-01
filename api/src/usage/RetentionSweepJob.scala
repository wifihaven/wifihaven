package wifihaven.api.usage

import cats.implicits.*
import doobie.*
import doobie.free.connection.{pure as cpure, raiseError as craiseError}
import doobie.implicits.*
import zio.*
import zio.interop.catz.*

import java.time.{Duration as JDuration, ZoneOffset, ZonedDateTime}

/**
 * Daily retention sweep (#811). Per [docs/design/usage-retention.md] §9:
 *
 *   - `traffic_reports` — drop rows older than 30 days
 *   - `connection_events` — drop rows older than 30 days
 *   - `traffic_hourly` — drop rows older than 90 days (gated on #809 table presence)
 *   - `traffic_daily` — drop rows older than 180 days (gated on #809 table presence; `date` col)
 *   - `connection_events_hourly` — drop rows older than 90 days (#1265; gated on V47 table
 *     presence)
 *   - `connection_events_daily` — drop rows older than 180 days (#1265; gated; `date` col)
 *
 * Multi-instance-safe via a session-scoped Postgres advisory lock. Losing the race makes the tick a
 * no-op. The lock auto-releases if the connection drops, so a crashing instance can't wedge it.
 *
 * v1 uses a plain `DELETE` per the design doc's "if #793 doesn't land" fallback; the partition-drop
 * variant is a follow-up gated on #793 landing.
 */
object RetentionSweepJob {

  /**
   * Reserved per docs/design/usage-retention.md §4 — `0x726c7570_73770001` ("rlupsw"). Do not reuse
   * for any other advisory lock.
   */
  val AdvisoryLockKey: Long = 0x726c757073770001L

  // Retention horizons (days). Hardcoded for v1; operator-tunable config
  // (`usage.rawRetentionDays` etc.) is a follow-up.
  val RawRetentionDays: Int              = 30
  val EventsRetentionDays: Int           = 30
  val HourlyRetentionDays: Int           = 90
  val DailyRetentionDays: Int            = 180
  // #1265: connection-event rollups share the traffic-rollup horizons (raw 30d
  // already covered by EventsRetentionDays; hourly 3mo / daily 6mo here).
  val ConnEventsHourlyRetentionDays: Int = 90
  val ConnEventsDailyRetentionDays: Int  = 180

  // Daily run hour (UTC). Design doc prefers router-local, but the API is
  // multi-household so picking *a* local zone is ill-defined. v1 = UTC; revisit
  // when per-household sweep windows become a real ask.
  private val DailyRunHourUtc: Int = 3

  final case class SweepResult(table: String, rowsDeleted: Int)

  /**
   * One sweep pass. Acquires the advisory lock; if another instance holds it, returns `None`
   * without touching any tables. Otherwise deletes expired rows from each table in a single
   * connection (so the lock is released on the same connection it was acquired on) and returns the
   * per-table row counts.
   *
   * Rollup tables from #809 are gated on `to_regclass(...)` — until that migration lands the sweep
   * silently skips them.
   */
  def sweepOnce(xa: Transactor[Task]): Task[Option[List[SweepResult]]] = {
    val cio: ConnectionIO[Option[List[SweepResult]]] =
      sql"SELECT pg_try_advisory_lock($AdvisoryLockKey)".query[Boolean].unique.flatMap {
        case false => cpure(Option.empty[List[SweepResult]])
        case true  =>
          // Run the sweep; release the lock on the same connection whether the
          // sweep succeeds or fails. Re-raise any error after releasing.
          sweepAllTables.attempt.flatMap { res =>
            sql"SELECT pg_advisory_unlock($AdvisoryLockKey)".query[Boolean].unique.flatMap { _ =>
              res match {
                case Right(rs) => cpure(Some(rs))
                case Left(e)   => craiseError[Option[List[SweepResult]]](e)
              }
            }
          }
      }
    cio.transact(xa)
  }

  private def sweepAllTables: ConnectionIO[List[SweepResult]] =
    for {
      raw      <- deleteOlderThan("traffic_reports", "period_start", RawRetentionDays)
      events   <- deleteOlderThan("connection_events", "ts", EventsRetentionDays)
      hourly   <- ifTableExists("traffic_hourly") {
        deleteOlderThan("traffic_hourly", "bucket_start", HourlyRetentionDays)
      }
      daily    <- ifTableExists("traffic_daily") {
        // V38's traffic_daily uses `date` (DATE), not `bucket_start` — see
        // docs/design/usage-retention.md §3.
        deleteOlderThan("traffic_daily", "date", DailyRetentionDays)
      }
      ceHourly <- ifTableExists("connection_events_hourly") {
        deleteOlderThan("connection_events_hourly", "bucket_start", ConnEventsHourlyRetentionDays)
      }
      ceDaily  <- ifTableExists("connection_events_daily") {
        // V47's connection_events_daily uses `date` (DATE), not `bucket_start`.
        deleteOlderThan("connection_events_daily", "date", ConnEventsDailyRetentionDays)
      }
    } yield {
      val base = List(
        SweepResult("traffic_reports", raw),
        SweepResult("connection_events", events),
      )
      val h    = hourly.map(SweepResult("traffic_hourly", _)).toList
      val d    = daily.map(SweepResult("traffic_daily", _)).toList
      val ceH  = ceHourly.map(SweepResult("connection_events_hourly", _)).toList
      val ceD  = ceDaily.map(SweepResult("connection_events_daily", _)).toList
      base ++ h ++ d ++ ceH ++ ceD
    }

  private def ifTableExists[A](table: String)(body: => ConnectionIO[A]): ConnectionIO[Option[A]] =
    sql"SELECT to_regclass($table) IS NOT NULL".query[Boolean].unique.flatMap {
      case true  => body.map(Some(_))
      case false => cpure(Option.empty[A])
    }

  // table/col/days come exclusively from compile-time constants above — no
  // user-supplied input ever reaches Fragment.const.
  private def deleteOlderThan(table: String, col: String, days: Int): ConnectionIO[Int] =
    Fragment.const(s"DELETE FROM $table WHERE $col < NOW() - INTERVAL '$days days'").update.run

  /**
   * Fork a daemon fiber that runs the sweep daily at 03:00 UTC. Subsequent ticks fire every 24 h.
   */
  def start(xa: Transactor[Task]): UIO[Fiber.Runtime[Throwable, Long]] = {
    val tick =
      sweepOnce(xa).either.flatMap {
        case Right(None)     =>
          ZIO.logInfo("RetentionSweepJob: advisory lock held by another instance — tick skipped")
        case Right(Some(rs)) =>
          val summary = rs.map(r => s"${r.table}=${r.rowsDeleted}").mkString(", ")
          ZIO.logInfo(s"RetentionSweepJob: swept [$summary]")
        case Left(e)         =>
          ZIO.logErrorCause("RetentionSweepJob: tick failed", Cause.fail(e))
      }
    for {
      delay <- ZIO
        .attempt(initialDelay(ZonedDateTime.now(ZoneOffset.UTC)))
        .orElseSucceed(24.hours)
      _     <- ZIO.logInfo(
        s"RetentionSweepJob scheduled: first tick in ${delay.render}, then every 24h at " +
          s"${"%02d".format(DailyRunHourUtc)}:00 UTC",
      )
      fiber <- (ZIO.sleep(delay) *> tick.repeat(Schedule.fixed(24.hours))).forkDaemon
    } yield fiber
  }

  def initialDelay(now: ZonedDateTime): Duration = {
    val todayRun = now.withHour(DailyRunHourUtc).withMinute(0).withSecond(0).withNano(0)
    val next     = if (todayRun.isAfter(now)) todayRun else todayRun.plusDays(1)
    Duration.fromMillis(JDuration.between(now, next).toMillis)
  }
}
