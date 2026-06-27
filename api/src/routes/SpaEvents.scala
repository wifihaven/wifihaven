package wifihaven.api.routes

import zio.*

import java.time.Instant

/**
 * #1970 (S3, SPA-websocket design `docs/design/spa-websocket.md` §5.2.2): the change-event
 * vocabulary the SPA push path translates into subscription-gated, role-filtered server→SPA frames.
 *
 * Each case is published from an EXISTING write site as a one-line `bus.publish(...)` — there is no
 * new polling/diffing loop (design §5.2: "consume existing write sites, don't rebuild"). The events
 * carry only what the [[SpaPush]] consumer needs to recompute the body it pushes; the bodies
 * themselves are always an existing REST read model rebuilt through its canonical builder (§0.3 —
 * zero new read-model shapes), so the stream and the matching `GET` can never disagree.
 *
 *   - [[SpaEvent.NowChanged]] — "who's online / what they're watching" may have moved (a
 *     connection- events or usage ingest, or a policy reevaluate #1849). The consumer rebuilds
 *     [[DashboardNow]] once via the shared [[DashboardNowRoutes.computeNow]] builder and pushes it
 *     to `now` subscribers (class-(2) thick push). Contentless trigger — latest-wins (§6.3).
 *   - [[SpaEvent.ConnectionEventsIngested]] — new connection_events rows landed at/after `since`.
 *     The consumer re-reads the head via the same `connRepo.query` the `/api/logs` GET uses (SSOT),
 *     keeps the rows at/after `since` (the genuinely-new ones), and appends them to each
 *     `connectionEvents` subscriber whose filter params match (class-(1) live edge).
 *   - [[SpaEvent.Stale]] — a class-(3) occasionally-changing resource mutated; the consumer fans a
 *     contentless `{topic, scope?}` nudge to `stale` subscribers so they invalidate the mapped
 *     query (§3.2). Coalescing-friendly (idempotent).
 *   - [[SpaEvent.UsageIngested]] — #1971 (S4): new `traffic_reports` rows landed. The consumer
 *     re-runs the EXISTING `GET /api/usage/traffic` query (via `UsageTrafficQuery.aggregate`)
 *     scoped to the current/most-recent bucket for each distinct subscribed `(groupBy, bucket,
 *     filter)` param-set and pushes that one bucket as a `TrafficUsageResponse` live edge (design
 *     §5.3). Contentless trigger — the head is recomputed from the DB, latest-wins (§6.3), and a
 *     param-set with no subscriber is never queried.
 *   - [[SpaEvent.TimeStatusChanged]] — #1974 (S6a): a profile's used/remaining minutes moved. The
 *     consumer rebuilds the `/api/time/status` `ProfileTimeStatus[]` body (via
 *     `TimeStatusService.dayStateAllLive`, role-filtered like the GET) and pushes it to
 *     `timeStatus` subscribers, and rebuilds the per-app `/api/profiles/{id}/usage-by-app` body
 *     (via the shared `UsageRoutes.buildUsageByApp`) for each subscribed `appUsage{profileId}`
 *     (design §1.2/§3.1). Published from THREE write sites (§5.2): usage credit (new minutes), the
 *     #1849 time-boundary ticker (schedule boundary / cap exhaustion change remaining without new
 *     usage), and the `POST /api/time/extend` grant. Contentless trigger — bodies are recomputed
 *     from the DB, latest-wins (§6.3), and a topic with no subscriber is never queried.
 */
enum SpaEvent {
  case NowChanged
  case ConnectionEventsIngested(since: Instant)
  case Stale(topic: StaleTopic, scope: Option[String] = None)
  case UsageIngested
  case TimeStatusChanged
}

/**
 * The bounded `topic` enum a `stale{topic, scope?}` nudge carries (design §1.2 / §3.2). One value
 * per class-(3) resource the SPA caches; kept a fixed small enum so it can ride a metric label
 * (`spa_ws_push_total{op="stale"}`) and the topic→query-key map on the client stays closed.
 */
enum StaleTopic {
  case Alerts, Profiles, Devices, Schedules
}

object StaleTopic {
  def wire(t: StaleTopic): String = t match {
    case Alerts    => "alerts"
    case Profiles  => "profiles"
    case Devices   => "devices"
    case Schedules => "schedules"
  }
}

/**
 * #1970: the in-memory `Hub[SpaEvent]` (design §5.2.2 "SpaEventHub") fed by the existing write
 * sites and drained by the single [[SpaPush]] consumer fiber. A tiny seam (like
 * [[wifihaven.api.policy.PolicySnapshotPublisher]]) so write sites — [[RouterIngestService]], the
 * route mutation callbacks wired in `Main` — depend only on `publish`, never on the Hub or the push
 * machinery, and tests can substitute [[SpaEventBus.noop]].
 *
 * Backed by a SLIDING hub (design §6.3): a wedged/slow consumer must NEVER block a write site — an
 * overflowing publish drops the oldest buffered event rather than back-pressuring ingest. The push
 * surface is a latency/UX layer (§6.5); losing a buffered change degrades to the next push (or the
 * client's reconnect refetch, §6.1), it never blocks or fails the write.
 *
 * Single-process, single-instance — the right shape for the one-API-process household; cross-
 * instance fan-out is out of scope (#1952).
 */
trait SpaEventBus {

  /** Publish a change event. Never blocks, never fails (sliding-drop on overflow). */
  def publish(event: SpaEvent): UIO[Unit]

  /**
   * Subscribe a fresh consumer. Only events published AFTER the returned [[Dequeue]] is acquired
   * are delivered; the subscription is released when the enclosing scope closes.
   */
  def subscribe: ZIO[Scope, Nothing, Dequeue[SpaEvent]]
}

object SpaEventBus {

  /** The no-publish bus used before wiring and in tests that don't exercise the push path. */
  val noop: SpaEventBus = new SpaEventBus {
    def publish(event: SpaEvent): UIO[Unit]               = ZIO.unit
    def subscribe: ZIO[Scope, Nothing, Dequeue[SpaEvent]] =
      ZIO.acquireRelease(Queue.unbounded[SpaEvent])(_.shutdown)
  }

  /**
   * The production bus: a sliding hub so an overflowing publish drops oldest, never blocks ingest.
   */
  def make: UIO[SpaEventBus] =
    Hub.sliding[SpaEvent](BufferSize).map { hub =>
      new SpaEventBus {
        def publish(event: SpaEvent): UIO[Unit]               = hub.publish(event).unit
        def subscribe: ZIO[Scope, Nothing, Dequeue[SpaEvent]] = hub.subscribe
      }
    }

  // Buffer of recent unconsumed events. Generous — the single consumer drains continuously and each
  // event is tiny; this only matters if the consumer stalls, in which case sliding-drop keeps the
  // freshest BufferSize events and discards older ones (a missed `now`/`stale` self-heals next push).
  private val BufferSize = 256
}
