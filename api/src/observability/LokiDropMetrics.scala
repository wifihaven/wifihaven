package wifihaven.api.observability

import com.github.loki4j.logback.MeteredLoki4jAppender
import wifihaven.api.metrics.AppMetrics
import org.slf4j.LoggerFactory
import ch.qos.logback.classic.LoggerContext
import zio.*

import scala.jdk.CollectionConverters.*

/**
 * #1885 (epic #1831): surface the loki4j appender's fail-open drop count as a Prometheus
 * self-metric so a Loki outage that is silently shedding log lines becomes alertable.
 *
 * The appender ([[MeteredLoki4jAppender]]) is asynchronous by construction: the request thread only
 * enqueues; a background thread batches and ships to Loki. When Loki is slow/unreachable the
 * bounded `sendQueueMaxBytes` queue fills and further events are DROPPED (never blocked) — that is
 * the hard fail-open guarantee (proved by `LokiAppenderFailOpenSpec`). The drop is invisible from
 * the request path by design, so without this poller a sustained outage loses logs with no signal.
 *
 * Shape mirrors [[wifihaven.api.metrics.DbPoolMetrics]] / `RouterPresenceMetrics`: a daemon fiber
 * that polls a live source and emits a metric. The appender keeps a monotonic cumulative drop
 * counter; each tick we read it, diff against the previous reading, and feed the positive delta to
 * the `loki_logs_dropped_total` counter through `MetricGuard` (unlabelled — the appender exposes a
 * single appender-wide number, and any per-mac/route label would breach the §4 cardinality
 * firewall).
 *
 * Deployed-env-only with no extra config switch: the LOKI appender is itself gated behind
 * `isDefined("GRAFANA_CLOUD_LOKI_URL")` in `logback.xml`, so on local dev / `mill __.test` it is
 * never instantiated. [[findAppender]] returns `None` there and [[loop]] no-ops — same presence
 * gate as the appender, no duplicated env check.
 */
object LokiDropMetrics {

  /** Poll cadence. Drops accrue slowly relative to scrape interval; 30s keeps the series cheap. */
  val DefaultInterval: Duration = 30.seconds

  /**
   * Locate the [[MeteredLoki4jAppender]] attached to the logback root logger, if any. `None` when
   * logback isn't backed by a [[LoggerContext]] (defensive) or the appender is absent (local/test,
   * where the `<if>` gate in logback.xml skipped it).
   */
  def findAppender: UIO[Option[MeteredLoki4jAppender]] =
    ZIO.succeed {
      LoggerFactory.getILoggerFactory match {
        case ctx: LoggerContext =>
          ctx
            .getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME)
            .iteratorForAppenders()
            .asScala
            .collectFirst { case a: MeteredLoki4jAppender => a }
        case _                  => None
      }
    }

  /**
   * Read the appender's cumulative drop count, emit the positive delta since `lastSeen`, and store
   * the new reading. Split out (taking a `read` thunk) so the diff logic is unit-testable without a
   * live appender — see `LokiDropMetricsSpec`.
   */
  def emitDelta(read: UIO[Long], lastSeen: Ref[Long]): UIO[Unit] =
    for {
      cur  <- read
      prev <- lastSeen.getAndSet(cur)
      // recordLokiDropped is a no-op for a non-positive delta (flat or — defensively — a
      // non-increasing reading), so the single guard lives there.
      _    <- AppMetrics.recordLokiDropped(cur - prev)
    } yield ()

  /**
   * Fiber loop: if the LOKI appender is present, poll its drop count every `interval` and emit the
   * delta; otherwise log once and return (local/test). `lastSeen` is seeded with the count at start
   * so a pre-existing total isn't replayed as one large spike. Never fails; fork as a daemon.
   */
  def loop(interval: Duration = DefaultInterval): UIO[Unit] =
    findAppender.flatMap {
      case None      =>
        ZIO.logDebug("LOKI appender not present; loki-drop metrics disabled (local/test).")
      case Some(app) =>
        for {
          start    <- ZIO.succeed(app.droppedEventsTotal)
          lastSeen <- Ref.make(start)
          _        <- ZIO.logInfo("loki-drop metrics fiber polling MeteredLoki4jAppender")
          _        <- emitDelta(ZIO.succeed(app.droppedEventsTotal), lastSeen)
            .repeat(Schedule.fixed(interval))
            .unit
        } yield ()
    }
}
