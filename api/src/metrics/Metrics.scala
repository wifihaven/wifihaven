package wifihaven.api.metrics

import com.zaxxer.hikari.HikariDataSource
import wifihaven.api.db.RouterRepo
import zio.*
import zio.metrics.*
import zio.metrics.connectors
import zio.metrics.connectors.MetricsConfig as ConnectorsConfig
import zio.metrics.connectors.prometheus.PrometheusPublisher

/**
 * #1204 §4.1: server-side cardinality firewall. Every self-metric emission goes through here, so a
 * metric can only be written under an allowlisted name with an allowlisted (and never-forbidden)
 * set of label keys. Anything else is dropped and counted in `metrics_rejected_total{reason}`
 * rather than polluting the registry with an unbounded series. For the current call sites the
 * names/keys are all static, so the reject path is a defensive backstop — but it's the same gate
 * the future `/api/router/metrics` ingest (router-pushed series) will reuse.
 */
object MetricGuard {

  /**
   * §4.3 — keys whose value-space grows with users/devices/domains/flows. Never allowed anywhere.
   */
  val ForbiddenKeys: Set[String] =
    Set(
      "mac",
      "device_id",
      "domain",
      "host",
      "hostname",
      "ip",
      "dst_ip",
      "user_id",
      "profile_id",
      "path",
      "query",
    )

  /**
   * #1210 — the small, known label-key vocabulary. Every key in any [[Allowed]] entry MUST be
   * listed here, and nothing here may also be a [[ForbiddenKeys]] (both invariants are enforced by
   * `MetricCardinalityGuardSpec`). This is the standing cardinality gate: introducing *any* new
   * label key forces a deliberate edit to this set, which is the review checkpoint #1210 makes
   * permanent. Each key is bounded and known at code-write time (§4.2): `route` (~40 templated
   * paths), `method` (~5), `status` (HTTP codes / bounded ingest enum), `op` (~30 hand-named DB
   * ops), `reason`/`result` (fixed per-metric enums), `version` (slow-moving agent versions),
   * `rollup_job` (handful of rollup job names), and `router_id` / `installation_id` (bounded
   * fleet/install dimensions).
   */
  val KnownLabelKeys: Set[String] =
    Set(
      "route",
      "method",
      "status",
      "op",
      "reason",
      "result",
      "version",
      "rollup_job",
      "router_id",
      "installation_id",
    )

  /**
   * §5.1/§5.2 — metric name → its permitted label keys. The only (name, keys) pairs that may be
   * emitted. `router_id` (and `installation_id`, once that concept lands) are bounded fleet-size
   * dimensions the server attaches to every router-pushed series (§4.2) — they are deliberately NOT
   * in [[ForbiddenKeys]].
   */
  val Allowed: Map[String, Set[String]] = Map(
    // §5.2 API self-metrics.
    "http_requests_total"                       -> Set("route", "method", "status"),
    "http_request_duration_seconds"             -> Set("route", "method"),
    "db_query_duration_seconds"                 -> Set("op"),
    "db_queries_total"                          -> Set("op", "status"),
    "auth_failures_total"                       -> Set("reason"),
    "agent_connected_routers"                   -> Set.empty[String],
    "traffic_reports_filtered_zero_bytes_total" -> Set.empty[String],
    // §5.1 router-sourced, pushed via POST /api/router/metrics (#1205). Every one carries the
    // server-attached `router_id` + `installation_id` plus its own bounded enum label.
    "dnsmasq_restarts_total"                    -> Set("reason", "router_id", "installation_id"),
    "policy_apply_total"                        -> Set("result", "router_id", "installation_id"),
    "policy_apply_duration_seconds"             -> Set("router_id", "installation_id"),
    "snapshot_poll_total"                       -> Set("result", "router_id", "installation_id"),
    "snapshot_poll_duration_seconds"            -> Set("router_id", "installation_id"),
    "agent_uptime_seconds"                      -> Set("router_id", "installation_id"),
    "agent_version"                             -> Set("version", "router_id", "installation_id"),
    "dns_queries_total"                         -> Set("result", "router_id", "installation_id"),
    "blocklist_fetch_failures_total"            -> Set("status", "router_id", "installation_id"),
    "enforcement_drops_total"                   -> Set("reason", "router_id", "installation_id"),
    // Server-side ingest health for POST /api/router/metrics (#1205). Concrete, emitted now.
    "router_metrics_batches_total"              -> Set("status"),
    // #1243 rollup health — `rollup_job` is a handful of hand-named jobs (traffic_hourly,
    // traffic_daily, time_used_daily), `status` ∈ {ok, error}. Bounded; routed through the guard.
    "wifihaven_rollup_runs_total"               -> Set("rollup_job", "status"),
    "wifihaven_rollup_duration_seconds"         -> Set("rollup_job"),
    "wifihaven_rollup_rows_upserted"            -> Set("rollup_job"),
    // #1243/#1221 HikariCP pool gauges — no labels.
    "wifihaven_db_pool_active_connections"      -> Set.empty[String],
    "wifihaven_db_pool_idle_connections"        -> Set.empty[String],
    "wifihaven_db_pool_total_connections"       -> Set.empty[String],
    "wifihaven_db_pool_threads_awaiting_connection" -> Set.empty[String],
    "wifihaven_db_pool_max_size"                    -> Set.empty[String],
  )

  private val rejected = Metric.counter("metrics_rejected_total")

  private def reject(reason: String): UIO[Unit] =
    rejected.tagged("reason", reason).update(1L)

  /** None ⇒ rejected (already counted); Some(labels) ⇒ cleared to emit. */
  private def check(name: String, labels: Map[String, String]): UIO[Option[Map[String, String]]] =
    Allowed.get(name) match {
      case None          => reject("unknown_name").as(None)
      case Some(allowed) =>
        val keys = labels.keySet
        if keys.exists(ForbiddenKeys.contains) || !keys.subsetOf(allowed) then
          reject("forbidden_label").as(None)
        else ZIO.some(labels)
    }

  def counter(name: String, labels: Map[String, String], by: Long = 1L): UIO[Unit] =
    check(name, labels).flatMap {
      case None     => ZIO.unit
      case Some(ls) =>
        ls.foldLeft(Metric.counter(name))((m, kv) => m.tagged(kv._1, kv._2)).update(by)
    }

  def histogram(
      name: String,
      labels: Map[String, String],
      value: Double,
      boundaries: MetricKeyType.Histogram.Boundaries,
  ): UIO[Unit] =
    check(name, labels).flatMap {
      case None     => ZIO.unit
      case Some(ls) =>
        ls.foldLeft(Metric.histogram(name, boundaries))((m, kv) => m.tagged(kv._1, kv._2))
          .update(value)
    }

  def gauge(name: String, labels: Map[String, String], value: Double): UIO[Unit] =
    check(name, labels).flatMap {
      case None     => ZIO.unit
      case Some(ls) =>
        ls.foldLeft(Metric.gauge(name))((m, kv) => m.tagged(kv._1, kv._2)).update(value)
    }
}

/**
 * #1242 / #1243: all WifiHaven application metrics, published through the Prometheus registry
 * exposed at `GET /metrics`. Every series is a plain `zio.metrics.Metric`, so the connector's
 * periodic snapshot picks them up alongside the JVM/runtime metrics.
 */
object AppMetrics {

  // ── HTTP server (#1204) ─────────────────────────────────────────────────────
  // Emitted from HttpMetrics.instrument, which wraps every real route. `route` is
  // the *templated* path (e.g. /api/devices/:mac), never a concrete id — see §4.

  /** §5.2 latency SLO buckets: 5ms → 5s. */
  val HttpDurationBoundaries: MetricKeyType.Histogram.Boundaries =
    MetricKeyType.Histogram.Boundaries.fromChunk(
      Chunk(0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0),
    )

  def recordHttp(route: String, method: String, status: Int, durationSeconds: Double): UIO[Unit] =
    MetricGuard.counter(
      "http_requests_total",
      Map("route" -> route, "method" -> method, "status" -> status.toString),
    ) *>
      MetricGuard.histogram(
        "http_request_duration_seconds",
        Map("route" -> route, "method" -> method),
        math.max(0.0, durationSeconds),
        HttpDurationBoundaries,
      )

  // ── DB query timing (#1204) ──────────────────────────────────────────────────
  // Emitted from DbMetrics.timed around the Doobie transact of hot repo methods.
  // `op` is a hand-named constant per method, never the SQL text. Two series per
  // op: the duration histogram (rate via _count, latency via the buckets) and a
  // db_queries_total{op,status} counter that splits ok vs error so the dashboard
  // can show a per-op success rate — a slow query and a *failing* query are
  // different incidents and an operator needs to tell them apart.

  /** Sub-millisecond → multi-second DB latency. */
  val DbDurationBoundaries: MetricKeyType.Histogram.Boundaries =
    MetricKeyType.Histogram.Boundaries.fromChunk(
      Chunk(0.001, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1.0, 2.5, 5.0),
    )

  def recordDbQuery(op: String, durationSeconds: Double, status: String): UIO[Unit] =
    MetricGuard.histogram(
      "db_query_duration_seconds",
      Map("op" -> op),
      math.max(0.0, durationSeconds),
      DbDurationBoundaries,
    ) *>
      MetricGuard.counter("db_queries_total", Map("op" -> op, "status" -> status))

  // ── Auth failures (#1204) ────────────────────────────────────────────────────
  // `reason` ∈ {bad_password, expired_token, bad_router_token, forbidden_role}.

  def recordAuthFailure(reason: String): UIO[Unit] =
    MetricGuard.counter("auth_failures_total", Map("reason" -> reason))

  // ── #864: traffic_reports rows dropped as zero-bytes-zero-seconds ────────────
  // Replaces the per-request warn-log + TODO marker. A rising rate means the
  // #858 agent regression (emitting empty rows) has returned.

  def recordZeroByteFiltered(rows: Int): UIO[Unit] =
    ZIO
      .when(rows > 0)(
        MetricGuard.counter(
          "traffic_reports_filtered_zero_bytes_total",
          Map.empty,
          rows.toLong,
        ),
      )
      .unit

  // ── Fleet liveness (#1204) ───────────────────────────────────────────────────
  // Set by RouterPresenceMetrics: routers seen (last_seen_at) within the window.

  def setConnectedRouters(count: Int): UIO[Unit] =
    MetricGuard.gauge("agent_connected_routers", Map.empty, count.toDouble)

  // ── Router metrics ingest (#1205) ────────────────────────────────────────────
  // One increment per POST /api/router/metrics. `status` ∈ {ok, malformed,
  // router_mismatch}. The concrete server-side health signal for the push path.

  def recordRouterMetricsBatch(status: String): UIO[Unit] =
    MetricGuard.counter("router_metrics_batches_total", Map("status" -> status))

  // §5.1 — server-side histogram boundaries for the router-pushed duration histograms. The agent
  // (#1206) reports cumulative bucket counts on these same boundaries; RouterMetricsService folds
  // the per-batch bucket-count deltas back into these registry histograms.
  val PolicyApplyDurationBoundaries: MetricKeyType.Histogram.Boundaries =
    MetricKeyType.Histogram.Boundaries.fromChunk(Chunk(0.01, 0.05, 0.1, 0.5, 1.0, 5.0))

  val SnapshotPollDurationBoundaries: MetricKeyType.Histogram.Boundaries =
    MetricKeyType.Histogram.Boundaries.fromChunk(Chunk(0.01, 0.05, 0.1, 0.5, 1.0, 5.0))

  /**
   * Boundaries keyed by router-pushed histogram name; the fold falls back to this when unmatched.
   */
  val RouterHistogramBoundaries: Map[String, MetricKeyType.Histogram.Boundaries] = Map(
    "policy_apply_duration_seconds"  -> PolicyApplyDurationBoundaries,
    "snapshot_poll_duration_seconds" -> SnapshotPollDurationBoundaries,
  )

  // ── Rollup health (#1243) ──────────────────────────────────────────────────
  // Emitted from RollupRepoLive.recordRun — the single completion point both the
  // hourly/daily byte-rollup fibers and the time_used_daily fiber funnel through.
  // Routed through MetricGuard (#1210) so the cardinality firewall covers these
  // series too, not just the HTTP/router-pushed ones.

  // Sub-second to multi-minute coverage: a prod rollup over a growth table can
  // run for minutes (#1197), so the boundaries span 0.05s → ~200s.
  val RollupDurationBoundaries: MetricKeyType.Histogram.Boundaries =
    MetricKeyType.Histogram.Boundaries.exponential(0.05, 2.0, 13)

  /** Record a completed rollup run. `status` is "ok" or "error". */
  def recordRollup(job: String, status: String, durationSeconds: Double, rows: Int): UIO[Unit] =
    MetricGuard.counter(
      "wifihaven_rollup_runs_total",
      Map("rollup_job" -> job, "status" -> status),
    ) *>
      MetricGuard.histogram(
        "wifihaven_rollup_duration_seconds",
        Map("rollup_job" -> job),
        math.max(0.0, durationSeconds),
        RollupDurationBoundaries,
      ) *>
      MetricGuard.gauge("wifihaven_rollup_rows_upserted", Map("rollup_job" -> job), rows.toDouble)

  // ── DB connection pool (#1243, #1221) ───────────────────────────────────────
  // Set from the polling fiber in DbPoolMetrics. threads_awaiting was the
  // leading indicator of the 2026-05-31 pool-exhaustion crash loop. Routed
  // through MetricGuard (#1210) — unlabelled, but the firewall still gates the name.

  def setDbPool(stats: DbPoolStats): UIO[Unit] =
    MetricGuard.gauge("wifihaven_db_pool_active_connections", Map.empty, stats.active.toDouble) *>
      MetricGuard.gauge("wifihaven_db_pool_idle_connections", Map.empty, stats.idle.toDouble) *>
      MetricGuard.gauge("wifihaven_db_pool_total_connections", Map.empty, stats.total.toDouble) *>
      MetricGuard.gauge(
        "wifihaven_db_pool_threads_awaiting_connection",
        Map.empty,
        stats.threadsAwaiting.toDouble,
      ) *>
      MetricGuard.gauge("wifihaven_db_pool_max_size", Map.empty, stats.maxSize.toDouble)
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

/**
 * #1204: time the Doobie transact of a repo method into `db_query_duration_seconds{op}` and count
 * it in `db_queries_total{op,status}`. `op` is a hand-named constant supplied at the call site —
 * never derived from the SQL — so the label space stays a small, known enum. Records on every exit
 * so a slow failing query is still visible; `status` is `ok` on success and `error` otherwise (a
 * failure or interruption), which is what drives the per-op success-rate panel.
 */
object DbMetrics {
  def timed[A](op: String)(query: Task[A]): Task[A] =
    Clock.nanoTime.flatMap { start =>
      query.onExit { exit =>
        Clock.nanoTime.flatMap(end =>
          AppMetrics.recordDbQuery(
            op,
            (end - start) / 1e9d,
            if exit.isSuccess then "ok" else "error",
          ),
        )
      }
    }
}

/**
 * #1204: publish `agent_connected_routers` — the single "is the fleet alive?" gauge. Counts routers
 * whose `last_seen_at` is within `window` (touched on every policy poll + usage/event push). Pure
 * read path: a periodic `SELECT count(*)`, no migration needed.
 */
object RouterPresenceMetrics {

  /** A router that hasn't been seen within this window is treated as disconnected. */
  val DefaultWindow: Duration = 10.minutes

  /** Poll cadence; well below the window so the gauge tracks fleet state promptly. */
  val DefaultInterval: Duration = 30.seconds

  def pollOnce(repo: RouterRepo, window: Duration): UIO[Unit] =
    (for {
      now   <- Clock.instant
      count <- repo.countSeenSince(now.minus(window))
      _     <- AppMetrics.setConnectedRouters(count)
    } yield ()).catchAll(e =>
      ZIO.logWarning(s"agent_connected_routers poll failed: ${e.getMessage}"),
    )

  /** Fiber loop; never fails. Intended to be forked as a daemon. */
  def loop(
      repo: RouterRepo,
      window: Duration = DefaultWindow,
      interval: Duration = DefaultInterval,
  ): UIO[Unit] =
    pollOnce(repo, window).repeat(Schedule.fixed(interval)).unit
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
