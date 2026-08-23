package wifihaven.api.metrics

import wifihaven.api.db.RouterRepo
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
 *
 * #2736 — WHY THIS SERVICE ALSO WRITES `routers.agent_version`. That column used to have exactly
 * one writer: the `X-WifiHaven-Agent-Version` header on the REST policy poll
 * ([[wifihaven.api.routes.RouterRoutes]]). #2736 made the OpenWrt agent websocket-only, so nothing
 * sends that header any more and the column would freeze at whatever version a router happened to
 * be running at its last poll — still rendered on the SPA's Routers page, with no error and no way
 * to tell it had gone stale. Silence reading as health is the #2546 shape, and it is the exact
 * failure class #2736's own precondition argument rejects, so it cannot ship that way.
 *
 * The value was already on the wire and already arriving: every batch carries `agentVersion`, on
 * the agent's 60 s metrics push. It is persisted HERE rather than in the two carriers because both
 * of them funnel through `ingest` — one writer, no parallel touch to drift (the sibling-path
 * drift-by-omission rule in `docs/pr-review-checklist.md` §1). The write is a COALESCE-guarded
 * touch, so an empty version from an agent that never had one read cannot blank the column.
 */
trait RouterMetricsService {
  def ingest(batch: RouterMetricsBatch): UIO[Unit]
}

object RouterMetricsService {
  def make(routerRepo: RouterRepo): UIO[RouterMetricsService] =
    Ref
      .make(Map.empty[RouterId, RouterMetricsServiceLive.RouterState])
      .map(new RouterMetricsServiceLive(_, routerRepo))
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
    routerRepo: RouterRepo,
) extends RouterMetricsService {
  import RouterMetricsServiceLive.*

  def ingest(batch: RouterMetricsBatch): UIO[Unit] =
    state.modify(fold(batch, _)).flatten *> recordAgentVersion(batch)

  /**
   * #2736: keep `routers.agent_version` fresh from the metrics push, now that the REST policy poll
   * that used to carry the `X-WifiHaven-Agent-Version` header is gone (see the class doc). An empty
   * string is skipped rather than written: `RouterRepo.touch` COALESCEs a `None`, so skipping
   * preserves a previously-known version instead of overwriting it with nothing.
   *
   * Best-effort on FAILURE ONLY — a DB blip must not reject a metrics batch that otherwise landed —
   * but never silently: a failure is logged with the router id, the same way the ws transport's own
   * `last_seen` touch handles it. This is enrichment on an already-successful ingest, not a
   * credential or permission problem, so it is on the right side of the no-dark-by-default line.
   */
  private def recordAgentVersion(batch: RouterMetricsBatch): UIO[Unit] =
    ZIO
      .when(batch.agentVersion.nonEmpty)(
        routerRepo.touch(batch.routerId, None, Some(batch.agentVersion)),
      )
      .catchAll(e =>
        ZIO.logWarning(
          s"router metrics: agent_version touch failed for router=${batch.routerId}: $e",
        ),
      )
      .unit

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
