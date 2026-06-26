package wifihaven.api.observability

import com.github.loki4j.logback.MeteredLoki4jAppender
import wifihaven.api.metrics.AppMetrics
import org.slf4j.LoggerFactory
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.core.status.{Status, StatusListener}
import zio.*

import java.util.concurrent.atomic.AtomicLong
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
 *
 * #1972 extends this with TWO more observability hooks for the *other* ways shipping can fail
 * silently — both invisible to the drop counter, which only sees queue-full backpressure:
 *   - When the secret IS set but the appender never attached (a logback wiring regression like the
 *     `<if>`-nested-in-`<root>` bug #1972 fixed), [[loop]] logs a loud WARN instead of a silent
 *     debug, so the 0-ingest cause is named in the API's own logs.
 *   - A [[SendErrorCountingListener]] on the logback StatusManager counts loki4j SEND errors
 *     (401/403 wrong-scope token, 4xx/5xx, timeouts) into `loki_logs_send_errors_total`. loki4j is
 *     fail-open and reports these only to the StatusManager, so a misscoped access-policy token
 *     would otherwise reject every line with no metric moving at all.
 */
object LokiDropMetrics {

  /** Poll cadence. Drops accrue slowly relative to scrape interval; 30s keeps the series cheap. */
  val DefaultInterval: Duration = 30.seconds

  /**
   * #1972: classify a logback status as a loki4j send-side error. loki4j routes its HTTP failures
   * (401/403 wrong-scope token, 4xx/5xx, connection/timeout) to the logback StatusManager via
   * `InternalLogger.error(...)`, with the originating loki4j object as the status origin. We count
   * ERROR-level statuses whose origin class lives under `com.github.loki4j` — capturing the silent
   * auth failure that left `loki_logs_dropped_total` flat at 0 while every line was rejected at the
   * send boundary. Pure (takes the origin's class name) so it is unit-testable without loki4j.
   */
  def isLokiSendError(level: Int, originClassName: Option[String]): Boolean =
    level == Status.ERROR && originClassName.exists(_.startsWith("com.github.loki4j"))

  /**
   * Logback [[StatusListener]] holding a monotonic count of loki4j send errors (see
   * [[isLokiSendError]]). Reset-resistant so it survives a logback reconfigure. The poll loop reads
   * [[errorsTotal]] and diffs it into `loki_logs_send_errors_total`, exactly as the drop counter is
   * polled — keeping the zio-metrics bridge on the ZIO side, off the logback callback thread.
   */
  final class SendErrorCountingListener extends StatusListener {
    private val errors                                = new AtomicLong(0L)
    override def isResetResistant: Boolean            = true
    override def addStatusEvent(status: Status): Unit =
      if (isLokiSendError(status.getLevel, Option(status.getOrigin).map(_.getClass.getName))) {
        val _ = errors.incrementAndGet()
      }
    def errorsTotal: Long                             = errors.get()
  }

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

  /** Same monotonic-delta shape as [[emitDelta]], for the send-error counter (#1972). */
  def emitSendErrorDelta(read: UIO[Long], lastSeen: Ref[Long]): UIO[Unit] =
    for {
      cur  <- read
      prev <- lastSeen.getAndSet(cur)
      _    <- AppMetrics.recordLokiSendErrors(cur - prev)
    } yield ()

  /**
   * Register a [[SendErrorCountingListener]] on the logback root context's StatusManager so loki4j
   * send errors are counted, returning it for polling. `None` when logback isn't a
   * [[LoggerContext]] (defensive). Installed only when the LOKI appender is present (the caller
   * gates on that).
   */
  def installSendErrorListener: UIO[Option[SendErrorCountingListener]] =
    ZIO.succeed {
      LoggerFactory.getILoggerFactory match {
        case ctx: LoggerContext =>
          val listener = new SendErrorCountingListener
          ctx.getStatusManager.add(listener)
          Some(listener)
        case _                  => None
      }
    }

  /**
   * Fiber loop: if the LOKI appender is present, poll its drop count every `interval` and emit the
   * delta; otherwise log once and return (local/test). `lastSeen` is seeded with the count at start
   * so a pre-existing total isn't replayed as one large spike. Never fails; fork as a daemon.
   */
  def loop(
      interval: Duration = DefaultInterval,
      lokiUrlConfigured: => Boolean = sys.env.contains("GRAFANA_CLOUD_LOKI_URL"),
  ): UIO[Unit] =
    findAppender.flatMap {
      case None      =>
        // No appender attached. Two cases: (a) local/test, where the logback `<if>` gate is false
        // because the secret is unset — expected, debug-only; (b) a DEPLOYED env where the secret
        // IS set but the appender failed to attach (a logback wiring regression like #1972). Case
        // (b) is the silent 0-ingest failure mode, so surface it as a loud WARN — otherwise it is
        // invisible (loki4j is fail-open and the drop metric never even registers).
        if (lokiUrlConfigured)
          ZIO.logWarning(
            "GRAFANA_CLOUD_LOKI_URL is set but no LOKI appender is attached to the root logger — " +
              "API logs are NOT shipping to Grafana Cloud Loki. Check logback.xml appender wiring (#1972).",
          )
        else
          ZIO.logDebug("LOKI appender not present; loki-drop metrics disabled (local/test).")
      case Some(app) =>
        for {
          start        <- ZIO.succeed(app.droppedEventsTotal)
          lastSeen     <- Ref.make(start)
          // #1972: also poll loki4j send errors (401/4xx/5xx) so an auth/scope failure that sheds
          // every line at the send boundary is visible — it never touches the queue-full drop count.
          sendListener <- installSendErrorListener
          errStart = sendListener.fold(0L)(_.errorsTotal)
          lastErrSeen <- Ref.make(errStart)
          _           <- ZIO.logInfo("loki-drop metrics fiber polling MeteredLoki4jAppender")
          _           <- (emitDelta(ZIO.succeed(app.droppedEventsTotal), lastSeen) *>
            emitSendErrorDelta(ZIO.succeed(sendListener.fold(0L)(_.errorsTotal)), lastErrSeen))
            .repeat(Schedule.fixed(interval))
            .unit
        } yield ()
    }
}
