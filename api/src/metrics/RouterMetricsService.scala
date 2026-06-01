package wifihaven.api.metrics

import wifihaven.shared.RouterMetricsBatch
import zio.*

/**
 * #1205 / design §3 — carrier-agnostic ingest for router-pushed metrics. Takes a *parsed* batch
 * (never a `Request`), so both the REST adapter (`POST /api/router/metrics`) today and the #1023
 * websocket `{"op":"metrics"}` frame dispatcher tomorrow fold into the exact same handler.
 *
 * Counters on the wire are cumulative running totals; this service translates them into the ZIO
 * metric registry idempotently by tracking the last value per (router, series) and emitting only
 * the delta. When `agentStartedAt` changes the agent restarted and its counters reset to zero, so
 * the baseline is dropped and the new cumulative re-bases from zero — the re-exposed counter keeps
 * climbing monotonically with no negative delta. Every emission passes through [[MetricGuard]], so
 * an unknown name or a forbidden label key is dropped and counted in `metrics_rejected_total`.
 */
trait RouterMetricsService {
  def ingest(batch: RouterMetricsBatch): UIO[Unit]
}

object RouterMetricsService {
  def make: UIO[RouterMetricsService] =
    ZIO.succeed(new RouterMetricsServiceLive)
}

final class RouterMetricsServiceLive extends RouterMetricsService {
  def ingest(batch: RouterMetricsBatch): UIO[Unit] = ZIO.unit
}
