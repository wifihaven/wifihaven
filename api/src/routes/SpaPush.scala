package wifihaven.api.routes

import wifihaven.api.db.*
import wifihaven.api.observability.LogContext
import wifihaven.shared.Clock
import zio.{Clock as _, *}
import zio.json.*
import zio.json.ast.Json

import java.time.{Duration, Instant}

/**
 * #1970 (S3, SPA-websocket design `docs/design/spa-websocket.md` §5.2): the single consumer fiber
 * that drains the [[SpaEventBus]] and translates each [[SpaEvent]] into subscription-gated,
 * role-filtered server→SPA frames via [[SpaWsRegistry]]. This is the "translate" half of the change
 * sources — the write sites publish (one-liners), this fiber rebuilds the affected body through its
 * canonical builder and fans it out.
 *
 *   - [[SpaEvent.NowChanged]] → rebuild [[wifihaven.shared.DashboardNow]] ONCE via the shared
 *     [[DashboardNowRoutes.computeNow]] builder (SSOT — the same code `GET /api/dashboard/now`
 *     runs) and push it to `now` subscribers. Built over the FULL profile/device set because `now`
 *     is visible only to admin/adult (`SpaTopic.visibleTo`), the roles the GET shows everything to
 *     (§4.4) — so one body is correct for every recipient and the registry's role gate is the
 *     filter.
 *   - [[SpaEvent.ConnectionEventsIngested]] → re-read the head rows through the same
 *     `connRepo.query` the `/api/logs` GET uses (SSOT), keep the genuinely-new rows (ts at/after
 *     the event's `since`), and let the registry append them to each `connectionEvents` subscriber
 *     whose filter matches.
 *   - [[SpaEvent.Stale]] → fan a contentless nudge to `stale` subscribers.
 *
 * The loop NEVER fails: each event's build is wrapped so a transient repo error is logged and
 * skipped (the push surface is a latency/UX layer, design §6.5 — a missed push self-heals on the
 * next change or the client's reconnect refetch, §6.1), it never crashes the consumer. The clock is
 * always the INJECTED [[Clock]] (never wall-clock).
 */
object SpaPush {

  /**
   * Subscribe to the bus and process events forever in a forked fiber. Scoped: the Hub subscription
   * and the fiber are released when the enclosing scope closes (forkScoped, like the rollup loops
   * in `Main`). Returns once subscribed + forked, so events published after `run` returns are seen.
   */
  def run(
      bus: SpaEventBus,
      registry: SpaWsRegistry,
      trafficRepo: TrafficReportRepo,
      connRepo: ConnectionEventRepo,
      deviceRepo: DeviceRepo,
      profileRepo: ProfileRepo,
      appTimeLimitRepo: AppTimeLimitRepo,
      clock: Clock,
  ): ZIO[Scope, Nothing, Unit] =
    bus.subscribe.flatMap { queue =>
      queue.take
        .flatMap(
          handle(
            _,
            registry,
            trafficRepo,
            connRepo,
            deviceRepo,
            profileRepo,
            appTimeLimitRepo,
            clock,
          ),
        )
        .forever
        .forkScoped
        .unit
    }

  private def handle(
      event: SpaEvent,
      registry: SpaWsRegistry,
      trafficRepo: TrafficReportRepo,
      connRepo: ConnectionEventRepo,
      deviceRepo: DeviceRepo,
      profileRepo: ProfileRepo,
      appTimeLimitRepo: AppTimeLimitRepo,
      clock: Clock,
  ): UIO[Unit] =
    event match {
      case SpaEvent.NowChanged                      =>
        pushNow(registry, trafficRepo, connRepo, deviceRepo, profileRepo, appTimeLimitRepo, clock)
          .catchAllCause(c => ZIO.logErrorCause("spa ws push: now recompute failed", c))
      case SpaEvent.ConnectionEventsIngested(since) =>
        pushConnectionEvents(registry, connRepo, clock, since)
          .catchAllCause(c => ZIO.logErrorCause("spa ws push: connectionEvents fan-out failed", c))
      case SpaEvent.Stale(topic, scope)             =>
        LogContext.annotate(LogContext.Op, "stale") {
          registry.fanOutStale(topic, scope)
        }
    }

  /** Rebuild `DashboardNow` over the full profile/device set and push to `now` subscribers. */
  private def pushNow(
      registry: SpaWsRegistry,
      trafficRepo: TrafficReportRepo,
      connRepo: ConnectionEventRepo,
      deviceRepo: DeviceRepo,
      profileRepo: ProfileRepo,
      appTimeLimitRepo: AppTimeLimitRepo,
      clock: Clock,
  ): Task[Unit] =
    for {
      now      <- clock.instant
      profiles <- profileRepo.listAll
      devices  <- deviceRepo.listAll
      body     <- DashboardNowRoutes.computeNow(
        now,
        profiles,
        devices,
        trafficRepo,
        connRepo,
        appTimeLimitRepo,
      )
      _        <- registry.fanOut(SpaTopic.Now, body.toJsonAST.getOrElse(Json.Obj()))
    } yield ()

  /**
   * Re-read the connection-events head through the `/api/logs` query and append the genuinely-new
   * rows (ts at/after `since`) to matching subscribers. Re-querying via the GET path keeps the
   * pushed `QueryLog` rows byte-identical to what the page loads (joins, FQDN resolution — SSOT);
   * the `since` floor is what makes the push carry only the NEW rows (an ingest that didn't match a
   * subscriber's filter then pushes nothing to it).
   */
  private def pushConnectionEvents(
      registry: SpaWsRegistry,
      connRepo: ConnectionEventRepo,
      clock: Clock,
      since: Instant,
  ): Task[Unit] =
    for {
      now <- clock.instant
      // Window covers [since, now]; the head is then trimmed to ts >= since to drop older rows the
      // window may include. Cap at 24h so a stale `since` can't widen the scan unboundedly.
      hrs = math.min(24, math.max(1, Duration.between(since, now).toHours.toInt + 1))
      rows <- connRepo.query(LogFilter(hours = hrs, limit = ConnEventHeadLimit, until = Some(now)))
      fresh = rows.filter(r => atOrAfter(r.ts, since))
      _ <- ZIO.when(fresh.nonEmpty)(registry.fanOutConnectionEvents(fresh))
    } yield ()

  // True iff the `/api/logs` row timestamp is at/after `since` (the genuinely-new edge). The row ts
  // is always ISO-8601 UTC ("…Z", ConnectionEventRepo `to_char(... 'Z')`), so a parse never fails in
  // practice; on the impossible malformed case KEEP the row rather than silently drop a real new one.
  private def atOrAfter(ts: String, since: Instant): Boolean =
    scala.util.Try(Instant.parse(ts)).map(!_.isBefore(since)).getOrElse(true)

  // Head-row cap per connectionEvents push. Matches the `/api/logs` default page; the client dedups
  // by id and the feed is "recent" anyway, so the newest 200 is plenty for the live edge (§3.1).
  private val ConnEventHeadLimit = 200
}
