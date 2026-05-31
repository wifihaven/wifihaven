package wifihaven.api.metrics

import com.zaxxer.hikari.HikariDataSource
import zio.*
import zio.metrics.*
import zio.metrics.connectors
import zio.metrics.connectors.MetricsConfig as ConnectorsConfig
import zio.metrics.connectors.prometheus.PrometheusPublisher

/**
 * #1242 / #1243: all WifiHaven application metrics, published through the Prometheus registry
 * exposed at `GET /metrics`. Every series is a plain `zio.metrics.Metric`, so the connector's
 * periodic snapshot picks them up alongside the JVM/runtime metrics.
 */
object AppMetrics {

  // ── Rollup health (#1243) ──────────────────────────────────────────────────
  // Emitted from RollupRepoLive.recordRun — the single completion point both the
  // hourly/daily byte-rollup fibers and the time_used_daily fiber funnel through.

  private val rollupRuns = Metric.counter("wifihaven_rollup_runs_total")

  // Sub-second to multi-minute coverage: a prod rollup over a growth table can
  // run for minutes (#1197), so the boundaries span 0.05s → ~200s.
  private val rollupDuration = Metric.histogram(
    "wifihaven_rollup_duration_seconds",
    MetricKeyType.Histogram.Boundaries.exponential(0.05, 2.0, 13),
  )

  private val rollupRows = Metric.gauge("wifihaven_rollup_rows_upserted")

  /** Record a completed rollup run. `status` is "ok" or "error". */
  def recordRollup(job: String, status: String, durationSeconds: Double, rows: Int): UIO[Unit] =
    rollupRuns.tagged("job", job).tagged("status", status).update(1L) *>
      rollupDuration.tagged("job", job).update(math.max(0.0, durationSeconds)) *>
      rollupRows.tagged("job", job).update(rows.toDouble)

  // ── DB connection pool (#1243, #1221) ───────────────────────────────────────
  // Set from the polling fiber in DbPoolMetrics. threads_awaiting was the
  // leading indicator of the 2026-05-31 pool-exhaustion crash loop.

  private val dbActive  = Metric.gauge("wifihaven_db_pool_active_connections")
  private val dbIdle    = Metric.gauge("wifihaven_db_pool_idle_connections")
  private val dbTotal   = Metric.gauge("wifihaven_db_pool_total_connections")
  private val dbWaiting = Metric.gauge("wifihaven_db_pool_threads_awaiting_connection")
  private val dbMax     = Metric.gauge("wifihaven_db_pool_max_size")

  def setDbPool(stats: DbPoolStats): UIO[Unit] =
    dbActive.update(stats.active.toDouble) *>
      dbIdle.update(stats.idle.toDouble) *>
      dbTotal.update(stats.total.toDouble) *>
      dbWaiting.update(stats.threadsAwaiting.toDouble) *>
      dbMax.update(stats.maxSize.toDouble)
}

/** Point-in-time HikariCP pool snapshot. */
final case class DbPoolStats(
    active: Int,
    idle: Int,
    total: Int,
    threadsAwaiting: Int,
    maxSize: Int,
)

/** #1243: poll the HikariCP MXBean and publish the pool gauges. */
object DbPoolMetrics {

  /** Default cadence; chosen short enough to catch a saturation spike before the 30s timeout. */
  val DefaultInterval: Duration = 10.seconds

  /**
   * Read the live pool stats. The MXBean is null until the pool is initialised (first connection),
   * so we fall back to zeros for the dynamic counters while still reporting the configured max.
   */
  def read(ds: HikariDataSource, maxSize: Int): UIO[DbPoolStats] =
    ZIO.succeed {
      Option(ds.getHikariPoolMXBean) match {
        case Some(mx) =>
          DbPoolStats(
            active = mx.getActiveConnections,
            idle = mx.getIdleConnections,
            total = mx.getTotalConnections,
            threadsAwaiting = mx.getThreadsAwaitingConnection,
            maxSize = maxSize,
          )
        case None     =>
          DbPoolStats(0, 0, 0, 0, maxSize)
      }
    }

  def pollOnce(ds: HikariDataSource, maxSize: Int): UIO[Unit] =
    read(ds, maxSize).flatMap(AppMetrics.setDbPool)

  /** Fiber loop: poll every `interval`. Never fails; intended to be forked as a daemon. */
  def loop(ds: HikariDataSource, maxSize: Int, interval: Duration = DefaultInterval): UIO[Unit] =
    pollOnce(ds, maxSize).repeat(Schedule.fixed(interval)).unit
}

/** #1242: Prometheus connector wiring — publisher + the periodic snapshot listener. */
object MetricsRuntime {

  /** Snapshot cadence for the Prometheus listener; Prometheus scrapes typically every 15–30s. */
  val DefaultInterval: Duration = 5.seconds

  /**
   * Provides the [[PrometheusPublisher]] (whose `get` renders the exposition text) plus the
   * background listener fiber that snapshots the metric registry every `interval`. The `Unit`
   * output of `prometheusLayer` and the `ConnectorsConfig` are folded in via `>+>`; the wider
   * intersection is a subtype of the declared `PrometheusPublisher` output.
   */
  def prometheus(
      interval: Duration = DefaultInterval,
  ): ZLayer[Any, Nothing, PrometheusPublisher] = {
    val cfgAndPublisher =
      ZLayer.succeed(ConnectorsConfig(interval)) ++ connectors.prometheus.publisherLayer
    cfgAndPublisher >+> connectors.prometheus.prometheusLayer
  }
}
