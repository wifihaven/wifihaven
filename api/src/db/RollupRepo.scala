package wifihaven.api.db

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.shared.types.*
import zio.*
import zio.interop.catz.*

import java.time.{Instant, LocalDate}

// ── Rollup tables (#809) ────────────────────────────────────────────────────
//
// Pre-aggregated counterparts of traffic_reports, written by the scheduled
// fibers in RollupJobs. Read by /api/usage/traffic when the requested window
// is wider than what raw 5-min rows can serve in <200 ms (#813).
//
// Hostnames in both tables are post-resolved — bare ipv4/ipv6 host_value rows
// were promoted to their resolved fqdn at rollup-write time via the same
// LATERAL join TrafficReportRepoLive does at read time. So callers read the
// rollups exactly like they'd read traffic_reports (no extra resolve step).

case class RollupRow(
    mac: MacAddress,
    host: HostId,
    bucketStart: Instant,
    bucketEnd: Instant,
    activeSeconds: Int,
    bytesIn: Long,
    bytesOut: Long,
)

case class RollupRun(
    id: Long,
    job: String,
    startedAt: Instant,
    finishedAt: Instant,
    status: String,
    error: Option[String],
    rowsUpserted: Int,
)

trait RollupRepo {

  /**
   * Re-aggregate the trailing N hours of `traffic_reports` into `traffic_hourly`. Idempotent: the
   * UPSERT replaces existing rows with the freshly summed values. Returns the row count touched.
   */
  def rerollHourly(since: Instant): Task[Int]

  /**
   * Re-aggregate the trailing window of `traffic_reports` into `traffic_daily`. `sinceDate` is the
   * earliest date to re-roll, inclusive. Idempotent via UPSERT.
   */
  def rerollDaily(sinceDate: LocalDate): Task[Int]

  /**
   * Record one tick of a rollup fiber in `rollup_runs` for /api/admin/rollup-status. `error` is set
   * only when `status != "ok"`.
   */
  def recordRun(
      job: String,
      startedAt: Instant,
      finishedAt: Instant,
      status: String,
      error: Option[String],
      rowsUpserted: Int,
  ): Task[Unit]

  /** Last N rollup_runs rows, newest first. */
  def recentRuns(limit: Int): Task[List[RollupRun]]

  /**
   * Read hourly rollup rows for the supplied macs in `[from, to)`. `macs = Nil` returns all macs (
   * unfiltered). Output shape mirrors `TrafficReportRepoLive.listRawInRange` so the in-app
   * aggregator can consume the rows unchanged.
   */
  def listHourlyInRange(
      macs: List[MacAddress],
      from: Instant,
      to: Instant,
  ): Task[List[RollupRow]]

  /**
   * Read daily rollup rows for the supplied macs in `[from, to)`. `bucketStart` is midnight UTC of
   * the row's `date` column; `bucketEnd` is the following midnight UTC.
   */
  def listDailyInRange(
      macs: List[MacAddress],
      from: Instant,
      to: Instant,
  ): Task[List[RollupRow]]
}

class RollupRepoLive(xa: Transactor[Task]) extends RollupRepo {

  // The LATERAL FQDN-resolve mirrors TrafficReportRepoLive — keep them in
  // lockstep. If the read-time resolve in traffic_reports queries ever
  // changes, this CTE must change too.
  private val resolvedCte =
    fr"""
      WITH resolved AS (
        SELECT
          tr.router_id,
          tr.mac,
          CASE WHEN tr.host_type IN ('ipv4','ipv6') AND ce.resolved_host_value IS NOT NULL
               THEN ce.resolved_host_value
               ELSE tr.host_value
          END AS hostname,
          tr.period_start,
          tr.date,
          tr.active_seconds,
          tr.bytes_in,
          tr.bytes_out
        FROM traffic_reports tr
        LEFT JOIN LATERAL (
          SELECT resolved_host_value
          FROM connection_events
          WHERE mac                 = tr.mac
            AND dest_ip             = tr.host_value
            AND resolved_host_value IS NOT NULL
            AND ts >= tr.date::TIMESTAMPTZ
            AND ts <  (tr.date + INTERVAL '1 day')::TIMESTAMPTZ
          ORDER BY ts DESC LIMIT 1
        ) ce ON tr.host_type IN ('ipv4','ipv6')
        WHERE (tr.active_seconds > 0 OR tr.bytes_in > 0 OR tr.bytes_out > 0)
      """

  def rerollHourly(since: Instant): Task[Int] = {
    val sql =
      resolvedCte ++
        fr"""
          AND tr.period_start >= $since
        )
        INSERT INTO traffic_hourly
          (router_id, mac, hostname, bucket_start, active_seconds, bytes_in, bytes_out, sample_count, rolled_at)
        SELECT
          router_id, mac, hostname,
          date_trunc('hour', period_start),
          SUM(active_seconds)::INT,
          SUM(bytes_in)::BIGINT,
          SUM(bytes_out)::BIGINT,
          COUNT(*)::INT,
          NOW()
        FROM resolved
        GROUP BY router_id, mac, hostname, date_trunc('hour', period_start)
        ON CONFLICT (router_id, mac, hostname, bucket_start) DO UPDATE SET
          active_seconds = EXCLUDED.active_seconds,
          bytes_in       = EXCLUDED.bytes_in,
          bytes_out      = EXCLUDED.bytes_out,
          sample_count   = EXCLUDED.sample_count,
          rolled_at      = EXCLUDED.rolled_at
        """
    sql.update.run.transact(xa)
  }

  def rerollDaily(sinceDate: LocalDate): Task[Int] = {
    val sql =
      resolvedCte ++
        fr"""
          AND tr.date >= $sinceDate
        )
        INSERT INTO traffic_daily
          (router_id, mac, hostname, date, active_seconds, bytes_in, bytes_out, sample_count, rolled_at)
        SELECT
          router_id, mac, hostname, date,
          SUM(active_seconds)::INT,
          SUM(bytes_in)::BIGINT,
          SUM(bytes_out)::BIGINT,
          COUNT(*)::INT,
          NOW()
        FROM resolved
        GROUP BY router_id, mac, hostname, date
        ON CONFLICT (router_id, mac, hostname, date) DO UPDATE SET
          active_seconds = EXCLUDED.active_seconds,
          bytes_in       = EXCLUDED.bytes_in,
          bytes_out      = EXCLUDED.bytes_out,
          sample_count   = EXCLUDED.sample_count,
          rolled_at      = EXCLUDED.rolled_at
        """
    sql.update.run.transact(xa)
  }

  def recordRun(
      job: String,
      startedAt: Instant,
      finishedAt: Instant,
      status: String,
      error: Option[String],
      rowsUpserted: Int,
  ): Task[Unit] =
    sql"""INSERT INTO rollup_runs (job, started_at, finished_at, status, error, rows_upserted)
          VALUES ($job, $startedAt, $finishedAt, $status, $error, $rowsUpserted)""".update.run
      .transact(xa)
      .unit

  def recentRuns(limit: Int): Task[List[RollupRun]] =
    sql"""SELECT id, job, started_at, finished_at, status, error, rows_upserted
          FROM rollup_runs ORDER BY started_at DESC LIMIT $limit"""
      .query[(Long, String, Instant, Instant, String, Option[String], Int)]
      .map { case (id, j, s, f, st, e, r) => RollupRun(id, j, s, f, st, e, r) }
      .to[List]
      .transact(xa)

  // Hostnames in the rollup are stored as a single text column (post-resolved
  // so the original ipv4/ipv6 kind is no longer meaningful at read time). We
  // wrap them as HostId.Fqdn so the downstream aggregator can keep treating
  // host as a HostId — `.value` round-trips the same string either way.
  def listHourlyInRange(
      macs: List[MacAddress],
      from: Instant,
      to: Instant,
  ): Task[List[RollupRow]] = {
    type Row = (MacAddress, String, Instant, Int, Long, Long)
    val base      =
      fr"""SELECT mac, hostname, bucket_start, active_seconds, bytes_in, bytes_out
           FROM traffic_hourly
           WHERE bucket_start >= $from AND bucket_start < $to """
    val macFilter = macs match {
      case Nil => fr""
      case ms  =>
        val nel = cats.data.NonEmptyList.fromListUnsafe(ms.map(_.value))
        fr"AND " ++ Fragments.in(fr"mac", nel)
    }
    (base ++ macFilter ++ fr"ORDER BY bucket_start DESC, mac, hostname")
      .query[Row]
      .map { case (m, h, bs, secs, bi, bo) =>
        RollupRow(m, HostId.Fqdn(Hostname.unsafe(h)), bs, bs.plusSeconds(3600), secs, bi, bo)
      }
      .to[List]
      .transact(xa)
  }

  def listDailyInRange(
      macs: List[MacAddress],
      from: Instant,
      to: Instant,
  ): Task[List[RollupRow]] = {
    type Row = (MacAddress, String, LocalDate, Int, Long, Long)
    // `date` is a calendar day with no zone; widen the band by one day on each
    // edge then filter in Scala so we don't accidentally drop edge rows under
    // non-UTC household zones. The caller's `from`/`to` are UTC instants.
    val fromDate  = from.atZone(java.time.ZoneOffset.UTC).toLocalDate.minusDays(1)
    val toDate    = to.atZone(java.time.ZoneOffset.UTC).toLocalDate.plusDays(1)
    val base      =
      fr"""SELECT mac, hostname, date, active_seconds, bytes_in, bytes_out
           FROM traffic_daily
           WHERE date >= $fromDate AND date <= $toDate """
    val macFilter = macs match {
      case Nil => fr""
      case ms  =>
        val nel = cats.data.NonEmptyList.fromListUnsafe(ms.map(_.value))
        fr"AND " ++ Fragments.in(fr"mac", nel)
    }
    (base ++ macFilter ++ fr"ORDER BY date DESC, mac, hostname")
      .query[Row]
      .map { case (m, h, d, secs, bi, bo) =>
        val bs = d.atStartOfDay(java.time.ZoneOffset.UTC).toInstant
        RollupRow(m, HostId.Fqdn(Hostname.unsafe(h)), bs, bs.plusSeconds(86400), secs, bi, bo)
      }
      .to[List]
      .transact(xa)
      .map(_.filter(r => !r.bucketStart.isBefore(from) && r.bucketStart.isBefore(to)))
  }
}
