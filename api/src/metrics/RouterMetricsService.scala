package wifihaven.api.metrics

import wifihaven.shared.{MetricHistogram, RouterMetricsBatch}
import wifihaven.shared.types.RouterId
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
    Ref
      .make(Map.empty[RouterId, RouterMetricsServiceLive.RouterState])
      .map(new RouterMetricsServiceLive(_))
}

object RouterMetricsServiceLive {

  /**
   * Per-router re-basing state. `generation` is the agent's `agentStartedAt`; on change the agent
   * restarted and all of its cumulative values reset to zero, so the baselines drop. `counters`
   * maps a series key to its last reported cumulative value; `histograms` maps a series key to its
   * last reported per-`le` cumulative bucket counts.
   */
  final case class RouterState(
      generation: String,
      counters: Map[String, Double],
      histograms: Map[String, Map[String, Double]],
  )

  /** Stable identity for a series: name + its agent-sent labels (sorted). */
  private[metrics] def seriesKey(name: String, labels: Map[String, String]): String =
    name + labels.toList.sortBy(_._1).map((k, v) => s"$k=$v").mkString("{", ",", "}")

  /** `"+Inf"` → +∞; otherwise parse the numeric upper bound. */
  private def leValue(le: String): Double =
    if le == "+Inf" || le == "Inf" then Double.PositiveInfinity
    else le.toDoubleOption.getOrElse(Double.PositiveInfinity)
}

final class RouterMetricsServiceLive(
    state: Ref[Map[RouterId, RouterMetricsServiceLive.RouterState]],
) extends RouterMetricsService {
  import RouterMetricsServiceLive.*

  def ingest(batch: RouterMetricsBatch): UIO[Unit] =
    state.modify(fold(batch, _)).flatten

  /**
   * Pure fold: compute the registry emissions (already cardinality-gated by [[MetricGuard]]) and
   * the updated per-router state for `batch`. Returns the combined effect to run plus the new map;
   * the caller runs the effect *after* the atomic state swap so a concurrent batch from another
   * router can't interleave a stale baseline.
   */
  private def fold(
      batch: RouterMetricsBatch,
      all: Map[RouterId, RouterState],
  ): (UIO[Unit], Map[RouterId, RouterState]) = {
    val rid     = batch.routerId.value.toString
    val prev    = all.get(batch.routerId)
    // A restart (or a never-seen router) means there is no valid baseline → re-base from zero.
    val sameGen = prev.exists(_.generation == batch.agentStartedAt)
    val baseCtr = if sameGen then prev.get.counters else Map.empty[String, Double]
    val baseHst = if sameGen then prev.get.histograms else Map.empty[String, Map[String, Double]]

    val (newCtr, ctrEffects) =
      batch.counters.foldLeft((baseCtr, Chunk.empty[UIO[Unit]])) { case ((acc, effs), c) =>
        val key   = seriesKey(c.name, c.labels)
        val last  = acc.getOrElse(key, 0.0)
        val delta = c.value - last
        // A negative delta within a generation shouldn't happen (cumulative), but if it does treat
        // it like a reset and re-base to the current value rather than decrementing the counter.
        val by    = if delta < 0 then c.value else delta
        val emit  = MetricGuard.counter(c.name, c.labels + ("router_id" -> rid), math.round(by))
        (acc.updated(key, c.value), effs :+ emit)
      }

    val gaugeEffects =
      Chunk
        .fromIterable(batch.gauges)
        .map(g => MetricGuard.gauge(g.name, g.labels + ("router_id" -> rid), g.value))

    val (newHst, hstEffects) =
      batch.histograms.foldLeft((baseHst, Chunk.empty[UIO[Unit]])) { case ((acc, effs), h) =>
        val key                   = seriesKey(h.name, h.labels)
        val (emit, nowCumulative) = foldHistogram(h, acc.getOrElse(key, Map.empty), rid)
        (acc.updated(key, nowCumulative), effs :+ emit)
      }

    val effect = ZIO.collectAllDiscard(ctrEffects ++ gaugeEffects ++ hstEffects)
    (effect, all.updated(batch.routerId, RouterState(batch.agentStartedAt, newCtr, newHst)))
  }

  /**
   * Fold one cumulative-bucket histogram into the registry by re-emitting, per bucket, the *new*
   * observations since the last batch — `(count_le − count_prev_le)` deltas — at the bucket's upper
   * bound so they land in the matching registry bucket. Returns the emission plus this batch's
   * per-`le` cumulative counts to store as the next baseline. Note: `_sum` is reconstructed from
   * the representative bucket values, so it approximates (not reproduces) the agent's reported
   * `sum` — acceptable for best-effort fleet observability; exact per-observation sums are not a
   * goal (§4).
   */
  private def foldHistogram(
      h: MetricHistogram,
      prevBuckets: Map[String, Double],
      rid: String,
  ): (UIO[Unit], Map[String, Double]) = {
    val boundaries =
      AppMetrics.RouterHistogramBoundaries.getOrElse(
        h.name,
        AppMetrics.PolicyApplyDurationBoundaries,
      )
    val sorted     = h.buckets.sortBy(b => leValue(b.le))
    val maxFinite  = sorted.map(b => leValue(b.le)).filter(_.isFinite).maxOption.getOrElse(1.0)
    val labels     = h.labels + ("router_id" -> rid)

    // Walk buckets in ascending `le`, tracking the running cumulative-below for both this batch and
    // the previous one, so each step yields the half-open bucket's new-observation delta.
    val (_, _, effs) =
      sorted.foldLeft((0.0, 0.0, Chunk.empty[UIO[Unit]])) { case ((nowLower, prevLower, effs), b) =>
        val nowCum   = b.count
        val prevCum  = prevBuckets.getOrElse(b.le, 0.0)
        val deltaObs = math.max(0L, math.round((nowCum - nowLower) - (prevCum - prevLower)))
        val repr     = if leValue(b.le).isInfinite then maxFinite + 1.0 else leValue(b.le)
        val emit     =
          MetricGuard
            .histogram(h.name, labels, repr, boundaries)
            .replicateZIODiscard(deltaObs.toInt)
        (nowCum, prevCum, effs :+ emit)
      }

    (ZIO.collectAllDiscard(effs), sorted.map(b => b.le -> b.count).toMap)
  }
}
