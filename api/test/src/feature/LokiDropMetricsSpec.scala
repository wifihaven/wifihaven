package wifihaven.api.feature

import wifihaven.api.MetricsConfig
import wifihaven.api.metrics.MetricsRuntime
import wifihaven.api.observability.LokiDropMetrics
import wifihaven.api.routes.MetricsRoutes
import zio.*
import zio.http.*
import zio.metrics.connectors.prometheus.PrometheusPublisher
import zio.test.*

/**
 * #1885: the poller side of the loki-drop metric. [[LokiDropMetrics.emitDelta]] reads the
 * appender's monotonic cumulative drop counter each tick and feeds the positive delta to
 * `loki_logs_dropped_total` via `MetricGuard`. This spec drives that diff logic against a
 * Ref-backed reading (no live appender needed) and scrapes the real Prometheus publisher behind
 * `GET /metrics` to prove:
 *
 *   - successive deltas accumulate into the counter (10 then +15 → 25),
 *   - a flat reading emits nothing (no double-count),
 *   - a non-increasing reading is ignored (no negative/garbage emission).
 *
 * Pairs with `LokiAppenderFailOpenSpec`, which proves the appender actually produces those drops.
 */
object LokiDropMetricsSpec extends ZIOSpec[PrometheusPublisher] {

  override val bootstrap = MetricsRuntime.prometheus(100.millis)

  private def scrape: ZIO[PrometheusPublisher, Nothing, String] =
    for {
      pub <- ZIO.service[PrometheusPublisher]
      routes = MetricsRoutes.routes(MetricsConfig(enabled = true), pub)
      resp <- routes(Request.get("/metrics")).merge
      body <- resp.body.asString.orDie
    } yield body

  /**
   * Parse the unlabelled `loki_logs_dropped_total` value out of the exposition (0.0 if absent). The
   * zio-metrics prometheus line is `name value [timestamp]`, so the value is the SECOND token — not
   * the last (that's the scrape timestamp). Matching the exact first token also skips any
   * `loki_logs_dropped_total_created` helper line.
   */
  private def droppedValue(body: String): Double =
    body.linesIterator
      .filterNot(_.startsWith("#"))
      .map(_.trim.split("\\s+"))
      .collectFirst {
        case parts if parts.length >= 2 && parts(0) == "loki_logs_dropped_total" =>
          parts(1).toDoubleOption.getOrElse(0.0)
      }
      .getOrElse(0.0)

  /**
   * Poll the publisher until the scraped value reaches `target` (the snapshot listener is async).
   */
  private def awaitValue(target: Double): ZIO[PrometheusPublisher, Nothing, Double] =
    scrape
      .map(droppedValue)
      .repeat(Schedule.spaced(50.millis) && Schedule.recurUntil[Double](_ >= target))
      .map(_._2)

  def spec = suite("loki-drop metric poller (#1885)")(
    test("accumulates positive deltas, ignores flat and non-increasing readings") {
      for {
        reading  <- Ref.make(0L)
        lastSeen <- Ref.make(0L)
        // First tick: appender at 10 drops → emit 10.
        _        <- reading.set(10L)
        _        <- LokiDropMetrics.emitDelta(reading.get, lastSeen)
        // Flat reading: still 10 → emit nothing.
        _        <- LokiDropMetrics.emitDelta(reading.get, lastSeen)
        // Climbs to 25 → emit +15.
        _        <- reading.set(25L)
        _        <- LokiDropMetrics.emitDelta(reading.get, lastSeen)
        // Non-increasing (defensive; counter is monotonic in reality) → emit nothing, no crash.
        _        <- reading.set(20L)
        _        <- LokiDropMetrics.emitDelta(reading.get, lastSeen)
        value    <- awaitValue(25.0)
      } yield assertTrue(value == 25.0)
    } @@ TestAspect.withLiveClock @@ TestAspect.timeout(30.seconds),
    test("warns loudly when the Loki URL is set but no appender is attached (#1972)") {
      // findAppender returns None in-test (no LOKI appender on the test logger), so this exercises
      // the misconfiguration branch: a deployed env that has GRAFANA_CLOUD_LOKI_URL but, due to a
      // logback wiring regression, never attached the appender — the silent 0-ingest mode #1972
      // hit. The branch must surface a WARN so the next recurrence is diagnosable, not invisible.
      (for {
        _    <- LokiDropMetrics.loop(lokiUrlConfigured = true)
        logs <- ZTestLogger.logOutput
      } yield assertTrue(
        logs.exists(e =>
          e.logLevel == LogLevel.Warning && e.message().contains("shipping to Grafana Cloud Loki"),
        ),
      )).provide(ZTestLogger.default)
    },
  )
}
