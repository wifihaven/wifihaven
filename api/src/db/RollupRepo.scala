package wifihaven.api.db

import cats.data.NonEmptyList
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.shared.types.*
import zio.*
import zio.interop.catz.*

import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDate, ZoneOffset}

// ── Rollup tables (#809) ────────────────────────────────────────────────────
//
// Pre-aggregated counterparts of traffic_reports, written by the scheduled
// fibers in RollupJobs. Read by /api/usage/traffic when the requested window
// is wider than what raw per-report-period rows (the agent's
// `usage_report_interval`, ~60s) can serve in <200 ms (#813).
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

// Per-app aggregate over a rollup window. `appId = None` is the synthetic
// "Other" bucket (rows whose host belongs to no app). Mirrors the read-time
// shape produced by the in-memory HostMatch.lookupApex paths in UsageRoutes,
// so a caller can swap a SQL `GROUP BY app_id` rollup read for the in-process
// matcher and get identical numbers.
case class AppUsageAgg(
    appId: Option[AppId],
    activeSeconds: Long,
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

// Stable advisory-lock keys (#818). Picked once, never reused. If you add a
// new rollup job, pick a fresh int64 here — overlapping keys would cross-
// serialize unrelated jobs.
//
// Heartbeat filter (#714) note: the filter is applied at *read* time in
// `Presence.totalMinutesByMac` for screen-time totals and consumed only by
// `/api/usage/series` and `PolicyService`. The rollup tables back
// `/api/usage/traffic`, which doesn't filter heartbeats — so the rollup sums
// match what the endpoint would have computed from raw rows. If a future
// change wants heartbeat filtering on `/api/usage/traffic`, the rollups
// can't apply it post-hoc — the per-report-period `active_seconds/period_seconds`
// ratio is summed away. That work would need a parallel `heartbeat_seconds`
// column in the rollups, written at reroll time with the current household
// filter settings.
object RollupLockKeys {
  val Hourly: Long           = 0x1108L << 32 | 0x0001L
  val Daily: Long            = 0x1108L << 32 | 0x0002L
  // #1265: connection-event rollup tier. Distinct keys so a CE reroll never
  // cross-serializes with a traffic reroll (they touch disjoint tables).
  val ConnEventsHourly: Long = 0x1108L << 32 | 0x0003L
  val ConnEventsDaily: Long  = 0x1108L << 32 | 0x0004L
}

trait RollupRepo {

  /**
   * Re-aggregate the trailing N hours of `traffic_reports` into `traffic_hourly`. Idempotent: the
   * UPSERT replaces existing rows with the freshly summed values.
   *
   * Returns `Some(n)` with the rows touched on success, `None` when the advisory lock was held by
   * another API instance (skip-this-tick). The lock is acquired transaction-scoped via
   * `pg_try_advisory_xact_lock`, so it auto-releases when the doobie tx commits or rolls back — no
   * manual release path needed and no risk of a stale lock surviving a crash.
   *
   * `appsByApex` maps each app-host apex (e.g. `youtube.com`) to the app ids that claim it; it is
   * applied to every rolled row's hostname via [[wifihaven.shared.types.HostMatch.lookupApex]] (the
   * canonical matcher) to populate `traffic_hourly_apps`. `version` is the `app_hosts_version` the
   * snapshot was read at and is stamped onto every rolled row so a later read can detect staleness.
   * The default empty snapshot leaves rows unattributed (version stamped, no app rows) — used by
   * the back-compat call sites that don't care about app attribution.
   */
  def rerollHourly(
      since: Instant,
      appsByApex: Map[String, List[AppId]] = Map.empty,
      version: Long = 0L,
  ): Task[Option[Int]]

  /**
   * Re-aggregate the trailing window of `traffic_reports` into `traffic_daily`. `sinceDate` is the
   * earliest date to re-roll, inclusive. Idempotent via UPSERT. Same advisory-lock contract as
   * [[rerollHourly]] — `None` means another instance is rolling concurrently. `appsByApex` /
   * `version` carry the same app-attribution contract as [[rerollHourly]], writing
   * `traffic_daily_apps`.
   */
  def rerollDaily(
      sinceDate: LocalDate,
      appsByApex: Map[String, List[AppId]] = Map.empty,
      version: Long = 0L,
  ): Task[Option[Int]]

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
   * Read hourly rollup rows for the supplied macs in `[from, to)`, scoped to `household`. `macs =
   * Nil` means "all macs IN `household`" — the household predicate is what bounds that "all", so
   * the widening can never reach another tenant's rows (#2708; the raw tier got the same treatment
   * in #2313). Output shape mirrors `TrafficReportRepoLive.listRawInRange` so the in-app aggregator
   * can consume the rows unchanged.
   */
  def listHourlyInRange(
      household: HouseholdId,
      macs: List[MacAddress],
      from: Instant,
      to: Instant,
  ): Task[List[RollupRow]]

  /**
   * Read daily rollup rows for the supplied macs in `[from, to)`, scoped to `household` exactly as
   * [[listHourlyInRange]]. `bucketStart` is midnight UTC of the row's `date` column; `bucketEnd` is
   * the following midnight UTC.
   */
  def listDailyInRange(
      household: HouseholdId,
      macs: List[MacAddress],
      from: Instant,
      to: Instant,
  ): Task[List[RollupRow]]

  /**
   * Per-app usage over the hourly rollup in `[from, to)` (#1091). When every in-window row carries
   * the current `app_hosts_version` (`currentVersion`), aggregates in-database via `GROUP BY
   * app_id` against `traffic_hourly_apps` — no per-row matching. If any row is stale (version <
   * current, or never attributed), falls back to reading the rows and re-matching in process with
   * `appsByApex` via the canonical [[wifihaven.shared.types.HostMatch.lookupApex]] — so an
   * `app_hosts` edit that bumps the version is reflected immediately instead of serving stale
   * attribution.
   *
   * A host in multiple apps is attributed to the lowest app id (matching the read-time dedup in
   * `UsageRoutes`), so a row's seconds are never double-counted across apps.
   *
   * Scoped to `household` on the same terms as [[listHourlyInRange]] — both the SQL path and the
   * in-process fallback (which reads through [[listHourlyInRange]]) see one tenant's rows.
   */
  def aggregateByAppHourly(
      household: HouseholdId,
      macs: List[MacAddress],
      from: Instant,
      to: Instant,
      currentVersion: Long,
      appsByApex: Map[String, List[AppId]],
  ): Task[List[AppUsageAgg]]

  /** Daily-grain counterpart of [[aggregateByAppHourly]]. */
  def aggregateByAppDaily(
      household: HouseholdId,
      macs: List[MacAddress],
      from: Instant,
      to: Instant,
      currentVersion: Long,
      appsByApex: Map[String, List[AppId]],
  ): Task[List[AppUsageAgg]]
}

class RollupRepoLive(xa: Transactor[Task]) extends RollupRepo {

  // The FQDN-resolve LATERAL is centralised in SqlFragments so this CTE and
  // every TrafficReportRepoLive read use the same join shape (#1741).
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
        ${SqlFragments.resolvedHostLateral}
        WHERE (tr.active_seconds > 0 OR tr.bytes_in > 0 OR tr.bytes_out > 0)
      """

  // Run `upsert` inside a transaction guarded by a transaction-scoped advisory
  // lock. If the lock is already held (another instance is mid-roll), commits
  // immediately with None and the upsert never runs. The lock auto-releases
  // on commit / rollback — no manual unlock path to leak.
  private def withLock(key: Long)(upsert: doobie.ConnectionIO[Int]): Task[Option[Int]] = {
    val tx = for {
      got <- sql"SELECT pg_try_advisory_xact_lock($key)".query[Boolean].unique
      n   <- if (got) upsert.map(Option(_)) else doobie.free.connection.pure(Option.empty[Int])
    } yield n
    tx.transact(xa)
  }

  def rerollHourly(
      since: Instant,
      appsByApex: Map[String, List[AppId]],
      version: Long,
  ): Task[Option[Int]] = {
    val truncSince = since.truncatedTo(ChronoUnit.HOURS)
    val sql        =
      resolvedCte ++
        fr"""
          AND tr.period_start >= $since
        )
        INSERT INTO traffic_hourly
          (router_id, mac, hostname, bucket_start, active_seconds, bytes_in, bytes_out, sample_count, rolled_at, app_hosts_version)
        SELECT
          router_id, mac, hostname,
          date_trunc('hour', period_start),
          SUM(active_seconds)::INT,
          SUM(bytes_in)::BIGINT,
          SUM(bytes_out)::BIGINT,
          COUNT(*)::INT,
          NOW(),
          $version
        FROM resolved
        GROUP BY router_id, mac, hostname, date_trunc('hour', period_start)
        ON CONFLICT (router_id, mac, hostname, bucket_start) DO UPDATE SET
          active_seconds    = EXCLUDED.active_seconds,
          bytes_in          = EXCLUDED.bytes_in,
          bytes_out         = EXCLUDED.bytes_out,
          sample_count      = EXCLUDED.sample_count,
          rolled_at         = EXCLUDED.rolled_at,
          app_hosts_version = EXCLUDED.app_hosts_version
        """
    withLock(RollupLockKeys.Hourly)(
      sql.update.run.flatMap(n => attributeHourly(truncSince, appsByApex, version).map(_ => n)),
    )
  }

  def rerollDaily(
      sinceDate: LocalDate,
      appsByApex: Map[String, List[AppId]],
      version: Long,
  ): Task[Option[Int]] = {
    val sql =
      resolvedCte ++
        fr"""
          AND tr.date >= $sinceDate
        )
        INSERT INTO traffic_daily
          (router_id, mac, hostname, date, active_seconds, bytes_in, bytes_out, sample_count, rolled_at, app_hosts_version)
        SELECT
          router_id, mac, hostname, date,
          SUM(active_seconds)::INT,
          SUM(bytes_in)::BIGINT,
          SUM(bytes_out)::BIGINT,
          COUNT(*)::INT,
          NOW(),
          $version
        FROM resolved
        GROUP BY router_id, mac, hostname, date
        ON CONFLICT (router_id, mac, hostname, date) DO UPDATE SET
          active_seconds    = EXCLUDED.active_seconds,
          bytes_in          = EXCLUDED.bytes_in,
          bytes_out         = EXCLUDED.bytes_out,
          sample_count      = EXCLUDED.sample_count,
          rolled_at         = EXCLUDED.rolled_at,
          app_hosts_version = EXCLUDED.app_hosts_version
        """
    withLock(RollupLockKeys.Daily)(
      sql.update.run.flatMap(n => attributeDaily(sinceDate, appsByApex, version).map(_ => n)),
    )
  }

  // ── App attribution (#1091) ───────────────────────────────────────────────
  //
  // After a reroll stamps `app_hosts_version` on every rolled row, recompute
  // the owning app(s) for those rows via the canonical HostMatch.lookupApex and
  // rewrite the side table. A host belonging to N apps fans out to N side rows;
  // read-time aggregation collapses the fan to the lowest app id. Runs in the
  // reroll transaction so attribution is atomic with the rollup write.

  private def attributeHourly(
      truncSince: Instant,
      appsByApex: Map[String, List[AppId]],
      version: Long,
  ): ConnectionIO[Unit] =
    for {
      _    <- sql"DELETE FROM traffic_hourly_apps WHERE bucket_start >= $truncSince".update.run
      rows <-
        sql"""SELECT router_id, mac, hostname, bucket_start
              FROM traffic_hourly WHERE bucket_start >= $truncSince"""
          .query[(RouterId, MacAddress, String, Instant)]
          .to[List]
      tuples = rows.flatMap { case (rid, m, h, bs) =>
        appsFor(h, appsByApex).map(appId => (rid, m, h, bs, appId, version))
      }
      _ <- insertHourlyApps(tuples)
    } yield ()

  private def attributeDaily(
      sinceDate: LocalDate,
      appsByApex: Map[String, List[AppId]],
      version: Long,
  ): ConnectionIO[Unit] =
    for {
      _    <- sql"DELETE FROM traffic_daily_apps WHERE date >= $sinceDate".update.run
      rows <-
        sql"""SELECT router_id, mac, hostname, date
              FROM traffic_daily WHERE date >= $sinceDate"""
          .query[(RouterId, MacAddress, String, LocalDate)]
          .to[List]
      tuples = rows.flatMap { case (rid, m, h, d) =>
        appsFor(h, appsByApex).map(appId => (rid, m, h, d, appId, version))
      }
      _ <- insertDailyApps(tuples)
    } yield ()

  // All apps owning `host`, deduped. lookupApex returns the apex-form entry the
  // FQDN belongs to; that bucket may name several apps (the fan).
  private def appsFor(host: String, appsByApex: Map[String, List[AppId]]): List[AppId] =
    HostMatch.lookupApex(host, appsByApex).getOrElse(Nil).distinct

  private def insertHourlyApps(
      tuples: List[(RouterId, MacAddress, String, Instant, AppId, Long)],
  ): ConnectionIO[Unit] =
    if (tuples.isEmpty) doobie.free.connection.unit
    else
      Update[(RouterId, MacAddress, String, Instant, AppId, Long)](
        """INSERT INTO traffic_hourly_apps
             (router_id, mac, hostname, bucket_start, app_id, app_hosts_version)
           VALUES (?, ?, ?, ?, ?, ?)""",
      ).updateMany(tuples).map(_ => ())

  private def insertDailyApps(
      tuples: List[(RouterId, MacAddress, String, LocalDate, AppId, Long)],
  ): ConnectionIO[Unit] =
    if (tuples.isEmpty) doobie.free.connection.unit
    else
      Update[(RouterId, MacAddress, String, LocalDate, AppId, Long)](
        """INSERT INTO traffic_daily_apps
             (router_id, mac, hostname, date, app_id, app_hosts_version)
           VALUES (?, ?, ?, ?, ?, ?)""",
      ).updateMany(tuples).map(_ => ())

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
      // #1243: publish the time-series alongside the existing rollup_runs row. The /api/admin/
      // rollup-status point-in-time view is unchanged; this just exports to Prometheus.
      .zipLeft(
        wifihaven.api.metrics.AppMetrics.recordRollup(
          job = job,
          status = status,
          durationSeconds = (finishedAt.toEpochMilli - startedAt.toEpochMilli) / 1000.0,
          rows = rowsUpserted,
        ),
      )

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
      household: HouseholdId,
      macs: List[MacAddress],
      from: Instant,
      to: Instant,
  ): Task[List[RollupRow]] = {
    // #2708: the tenancy predicate below. `traffic_hourly` carries no `household_id` of its own, so
    // the scope is TRANSITIVE through `routers.household_id` (NOT NULL, V65) via the same shared
    // fragment the raw `traffic_reports` reads compose (#2313/#2568). The MAC list is NOT scoping —
    // post-V74 the same MAC can be a device in two households, and `macs = Nil` disables the filter
    // outright.
    //
    // Plan, measured 2026-08-14 on prod (`traffic_hourly` 2,211,470 rows / `traffic_daily`
    // 678,361), `EXPLAIN (ANALYZE, BUFFERS)` over the widest read each tier serves (14d hourly /
    // 90d daily, `macs = Nil`): the predicate resolves to a `Materialize`d 1-row `routers`
    // semijoin (5 shared buffers, `idx_routers_household` V65 on the subquery) and the outer scan
    // stays an index scan on `idx_traffic_hourly_bucket_start` / `idx_traffic_daily_date`, the same
    // plan class as before. Wall time went 3.48s → 2.65s (hourly) and 3.86s → 2.86s (daily),
    // because the scoped read returns fewer rows. So no new index is needed, and no `household_id`
    // column on these unbounded-growth tables is justified — see
    // `docs/design/multi-tenant-isolation.md` § "router_id-keyed tables".
    type Row = (MacAddress, String, Instant, Int, Long, Long)
    val base = fr"""SELECT mac, hostname, bucket_start, active_seconds, bytes_in, bytes_out
           FROM traffic_hourly
           WHERE bucket_start >= $from AND bucket_start < $to """ ++
      SqlFragments.householdRouterScope(household, "router_id") ++ fr" "
    (base ++ macFilter(macs) ++ fr"ORDER BY bucket_start DESC, mac, hostname")
      .query[Row]
      .map { case (m, h, bs, secs, bi, bo) =>
        RollupRow(m, HostId.Fqdn(Hostname.unsafe(h)), bs, bs.plusSeconds(3600), secs, bi, bo)
      }
      .to[List]
      .transact(xa)
  }

  def listDailyInRange(
      household: HouseholdId,
      macs: List[MacAddress],
      from: Instant,
      to: Instant,
  ): Task[List[RollupRow]] = {
    type Row = (MacAddress, String, LocalDate, Int, Long, Long)
    // `date` is a calendar day with no zone; widen the band by one day on each
    // edge then filter in Scala so we don't accidentally drop edge rows under
    // non-UTC household zones. The caller's `from`/`to` are UTC instants.
    val fromDate = from.atZone(java.time.ZoneOffset.UTC).toLocalDate.minusDays(1)
    val toDate   = to.atZone(java.time.ZoneOffset.UTC).toLocalDate.plusDays(1)
    val base     = fr"""SELECT mac, hostname, date, active_seconds, bytes_in, bytes_out
           FROM traffic_daily
           WHERE date >= $fromDate AND date <= $toDate """ ++
      // #2708: same transitive tenancy predicate as `listHourlyInRange`.
      SqlFragments.householdRouterScope(household, "router_id") ++ fr" "
    (base ++ macFilter(macs) ++ fr"ORDER BY date DESC, mac, hostname")
      .query[Row]
      .map { case (m, h, d, secs, bi, bo) =>
        val bs = d.atStartOfDay(java.time.ZoneOffset.UTC).toInstant
        RollupRow(m, HostId.Fqdn(Hostname.unsafe(h)), bs, bs.plusSeconds(86400), secs, bi, bo)
      }
      .to[List]
      .transact(xa)
      .map(_.filter(r => !r.bucketStart.isBefore(from) && r.bucketStart.isBefore(to)))
  }

  private def macFilter(macs: List[MacAddress]): Fragment =
    macs match {
      case Nil => fr""
      case ms  =>
        val nel = NonEmptyList.fromListUnsafe(ms.map(_.value))
        fr"AND " ++ Fragments.in(fr"mac", nel)
    }

  def aggregateByAppHourly(
      household: HouseholdId,
      macs: List[MacAddress],
      from: Instant,
      to: Instant,
      currentVersion: Long,
      appsByApex: Map[String, List[AppId]],
  ): Task[List[AppUsageAgg]] = {
    // #2708: both the staleness probe and the aggregate read carry the tenancy predicate — a
    // foreign stale row must not be able to flip this household's read onto the fallback path
    // either.
    val staleSql =
      fr"""SELECT COUNT(*) FROM traffic_hourly
           WHERE bucket_start >= $from AND bucket_start < $to
             AND (app_hosts_version IS NULL OR app_hosts_version < $currentVersion) """ ++
        SqlFragments.householdRouterScope(household, "router_id") ++ fr" " ++
        macFilter(macs)
    val freshSql =
      fr"""SELECT chosen.app_id,
                  SUM(th.active_seconds)::BIGINT,
                  SUM(th.bytes_in)::BIGINT,
                  SUM(th.bytes_out)::BIGINT
           FROM traffic_hourly th
           LEFT JOIN LATERAL (
             SELECT MIN(a.app_id) AS app_id
             FROM traffic_hourly_apps a
             WHERE a.router_id    = th.router_id
               AND a.mac          = th.mac
               AND a.hostname     = th.hostname
               AND a.bucket_start = th.bucket_start
           ) chosen ON TRUE
           WHERE th.bucket_start >= $from AND th.bucket_start < $to """ ++
        SqlFragments.householdRouterScope(household, "th.router_id") ++ fr" " ++
        macFilter(macs) ++
        fr"GROUP BY chosen.app_id"

    val sqlPath =
      freshSql
        .query[(Option[AppId], Long, Long, Long)]
        .map { case (a, secs, bi, bo) => AppUsageAgg(a, secs, bi, bo) }
        .to[List]
        .transact(xa)

    for {
      stale <- staleSql.query[Long].unique.transact(xa)
      aggs  <-
        if (stale == 0L) sqlPath
        else listHourlyInRange(household, macs, from, to).map(aggregateInScala(_, appsByApex))
    } yield aggs
  }

  def aggregateByAppDaily(
      household: HouseholdId,
      macs: List[MacAddress],
      from: Instant,
      to: Instant,
      currentVersion: Long,
      appsByApex: Map[String, List[AppId]],
  ): Task[List[AppUsageAgg]] = {
    val fromDate = from.atZone(ZoneOffset.UTC).toLocalDate
    val toDate   = to.atZone(ZoneOffset.UTC).toLocalDate
    // #2708: scoped on both the staleness probe and the aggregate, as in `aggregateByAppHourly`.
    val staleSql =
      fr"""SELECT COUNT(*) FROM traffic_daily
           WHERE date >= $fromDate AND date < $toDate
             AND (app_hosts_version IS NULL OR app_hosts_version < $currentVersion) """ ++
        SqlFragments.householdRouterScope(household, "router_id") ++ fr" " ++
        macFilter(macs)
    val freshSql =
      fr"""SELECT chosen.app_id,
                  SUM(td.active_seconds)::BIGINT,
                  SUM(td.bytes_in)::BIGINT,
                  SUM(td.bytes_out)::BIGINT
           FROM traffic_daily td
           LEFT JOIN LATERAL (
             SELECT MIN(a.app_id) AS app_id
             FROM traffic_daily_apps a
             WHERE a.router_id = td.router_id
               AND a.mac       = td.mac
               AND a.hostname  = td.hostname
               AND a.date      = td.date
           ) chosen ON TRUE
           WHERE td.date >= $fromDate AND td.date < $toDate """ ++
        SqlFragments.householdRouterScope(household, "td.router_id") ++ fr" " ++
        macFilter(macs) ++
        fr"GROUP BY chosen.app_id"

    val sqlPath =
      freshSql
        .query[(Option[AppId], Long, Long, Long)]
        .map { case (a, secs, bi, bo) => AppUsageAgg(a, secs, bi, bo) }
        .to[List]
        .transact(xa)

    for {
      stale <- staleSql.query[Long].unique.transact(xa)
      aggs  <-
        if (stale == 0L) sqlPath
        else listDailyInRange(household, macs, from, to).map(aggregateInScala(_, appsByApex))
    } yield aggs
  }

  // Read-time fallback when any in-window rollup row is stale: re-match each
  // row's host in process via the canonical matcher, deduping a multi-app host
  // to the lowest app id (matching the SQL path and the UsageRoutes read path).
  private def aggregateInScala(
      rows: List[RollupRow],
      appsByApex: Map[String, List[AppId]],
  ): List[AppUsageAgg] = {
    val byApexMin = appsByApex.view.mapValues(_.minBy(_.value)).toMap
    rows
      .groupBy(r => r.host.asFqdn.flatMap(fqdn => HostMatch.lookupApex(fqdn.value, byApexMin)))
      .map { case (appId, rs) =>
        AppUsageAgg(
          appId,
          rs.iterator.map(_.activeSeconds.toLong).sum,
          rs.iterator.map(_.bytesIn).sum,
          rs.iterator.map(_.bytesOut).sum,
        )
      }
      .toList
  }
}
