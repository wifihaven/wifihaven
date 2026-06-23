// NB: deliberately in loki4j's own package so the package-private
// `Loki4jAppender.droppedEventsCount()` accessor is reachable. #1873 wired the
// stock `Loki4jAppender`; #1885 swaps logback.xml to this subclass so the
// fail-open drop count (events the async pipeline shed because its bounded send
// queue was full — Loki slow/unreachable) can be surfaced as a Prometheus
// self-metric. See `wifihaven.api.observability.LokiDropMetrics`.
package com.github.loki4j.logback

/**
 * Thin subclass of loki4j's [[Loki4jAppender]] that adds nothing to the logging path — it only
 * re-exports the appender's own cumulative dropped-events counter as a public accessor.
 *
 * Why a subclass rather than reflection or Micrometer:
 *   - The drop count lives in `Loki4jAppender.droppedEventsCount` (an `AtomicLong` bumped by
 *     `reportDroppedEvents()` every time `AsyncBufferPipeline.append` returns `false`, i.e. the
 *     bounded `sendQueueMaxBytes` queue was full and the event was shed instead of blocking the
 *     calling thread). loki4j exposes it via a package-private `long droppedEventsCount()` method —
 *     reachable only from within `com.github.loki4j.logback`.
 *   - loki4j's richer Micrometer metrics (`metricsEnabled=true`, including its own
 *     `droppedEventsCounter`) publish to Micrometer's global registry, which is NOT the
 *     `zio.metrics` Prometheus registry behind `GET /metrics`. Bridging the two would mean pulling
 *     in Micrometer + a registry adapter for a single counter. The appender's own `AtomicLong` is
 *     the same number with none of that machinery.
 *
 * This class adds zero behaviour, so it is a drop-in for the stock appender on the wire/logging
 * side; the only observable difference is the new public accessor.
 */
class MeteredLoki4jAppender extends Loki4jAppender {

  /**
   * Cumulative count of log events this appender has dropped because the async send queue was full
   * (Loki slow or unreachable). Monotonic for the life of the appender; the poller in
   * [[wifihaven.api.observability.LokiDropMetrics]] diffs it into a Prometheus counter.
   */
  def droppedEventsTotal: Long = droppedEventsCount()
}
