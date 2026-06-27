package wifihaven.api.routes

import wifihaven.api.db.*
import wifihaven.api.observability.LogContext
import wifihaven.api.usage.{AppMembership, UsageTraffic, UsageTrafficQuery}
import wifihaven.shared.{Clock, Device, TrafficUsageResponse}
import wifihaven.shared.types.{MacAddress, ProfileId}
import zio.{Clock as _, *}
import zio.json.*
import zio.json.ast.Json

import java.time.{Duration, Instant, ZoneId}

/**
 * #1970 (S3) / #1971 (S4), SPA-websocket design `docs/design/spa-websocket.md` §5.2/§5.3: the
 * single consumer fiber that drains the [[SpaEventBus]] and translates each [[SpaEvent]] into
 * subscription-gated, role-filtered server→SPA frames via [[SpaWsRegistry]]. This is the
 * "translate" half of the change sources — the write sites publish (one-liners), this fiber
 * rebuilds the affected body through its canonical builder and fans it out.
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
 *   - [[SpaEvent.UsageIngested]] → #1971 (S4): for each DISTINCT subscribed `trafficUsage`
 *     `(groupBy, bucket, filter)` param-set, recompute ONLY the bucket the just-ingested rows
 *     landed in — the one containing the event's `periodStart` (#2004) — via the shared
 *     [[UsageTrafficQuery.aggregate]] (the exact query the `GET /api/usage/traffic` aggregated path
 *     runs — SSOT) scoped to that single bucket, and push it as a `TrafficUsageResponse` live edge
 *     (design §5.3). No subscriber for a param-set → no query.
 *
 * Cadence / backpressure (design §6.3): the drain loop COALESCES — it takes the first buffered
 * event then drains everything else currently queued and de-duplicates the triggers
 * ([[SpaEvent.NowChanged]] to one, [[SpaEvent.UsageIngested]] to the freshest `periodStart`), so a
 * burst of usage ingests collapses to a single freshest head-bucket recompute per param-set
 * (latest-wins), never a backlog. A slow consumer thus serves the freshest state, not a queue of
 * stale snapshots.
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
      rollupRepo: RollupRepo,
      connRepo: ConnectionEventRepo,
      deviceRepo: DeviceRepo,
      profileRepo: ProfileRepo,
      appRepo: AppRepo,
      appTimeLimitRepo: AppTimeLimitRepo,
      clock: Clock,
  ): ZIO[Scope, Nothing, Unit] =
    bus.subscribe.flatMap { queue =>
      drainBatch(
        queue,
        registry,
        trafficRepo,
        rollupRepo,
        connRepo,
        deviceRepo,
        profileRepo,
        appRepo,
        appTimeLimitRepo,
        clock,
      ).forever.forkScoped.unit
    }

  /**
   * Drain one coalesced batch: block for the first event, then sweep up everything else already
   * buffered and coalesce (design §6.3) so a burst of ingests does ONE recompute per topic, not N.
   * The `ConnectionEventsIngested(since)` events keep their distinct `since` (their head re-read is
   * cheap and the `since` floor matters); `NowChanged` collapses to one and `UsageIngested` to the
   * freshest `periodStart` (latest-wins).
   */
  private def drainBatch(
      queue: Dequeue[SpaEvent],
      registry: SpaWsRegistry,
      trafficRepo: TrafficReportRepo,
      rollupRepo: RollupRepo,
      connRepo: ConnectionEventRepo,
      deviceRepo: DeviceRepo,
      profileRepo: ProfileRepo,
      appRepo: AppRepo,
      appTimeLimitRepo: AppTimeLimitRepo,
      clock: Clock,
  ): UIO[Unit] =
    for {
      first <- queue.take
      more  <- queue.takeAll
      batch = coalesce(first +: more)
      _ <- ZIO.foreachDiscard(batch)(
        handle(
          _,
          registry,
          trafficRepo,
          rollupRepo,
          connRepo,
          deviceRepo,
          profileRepo,
          appRepo,
          appTimeLimitRepo,
          clock,
        ),
      )
    } yield ()

  /**
   * Coalesce a drained batch (design §6.3 latest-wins): de-dup so a burst of triggers fires ONE
   * recompute per topic regardless of burst size, while distinctly-`since`d
   * [[SpaEvent.ConnectionEventsIngested]] are kept (each names a different new-row floor).
   *   - [[SpaEvent.NowChanged]] → collapses to one (contentless).
   *   - [[SpaEvent.UsageIngested]] → collapses to the one with the LATEST `periodStart` (#2004):
   *     the head bucket is anchored on the ingested period, so a burst must converge on the
   *     freshest period (latest-wins) — a plain `distinct` would keep N distinct-period events and
   *     recompute each, and an older period must never overwrite the live edge with a stale bucket.
   *   - [[SpaEvent.ConnectionEventsIngested]] → distinct `since`s kept (their head re-read
   *     differs).
   * `distinct` preserves first-occurrence order. Exposed for unit coverage of the collapse logic.
   */
  private[api] def coalesce(events: Chunk[SpaEvent]): Chunk[SpaEvent] = {
    val latestUsage: Option[Instant] =
      events
        .collect { case SpaEvent.UsageIngested(p) => p }
        .foldLeft(Option.empty[Instant])((acc, p) =>
          Some(acc.fold(p)(a => if (p.isAfter(a)) p else a)),
        )
    val withoutUsage                 = events.filter {
      case SpaEvent.UsageIngested(_) => false
      case _                         => true
    }.distinct
    latestUsage.fold(withoutUsage)(p => withoutUsage :+ SpaEvent.UsageIngested(p))
  }

  private def handle(
      event: SpaEvent,
      registry: SpaWsRegistry,
      trafficRepo: TrafficReportRepo,
      rollupRepo: RollupRepo,
      connRepo: ConnectionEventRepo,
      deviceRepo: DeviceRepo,
      profileRepo: ProfileRepo,
      appRepo: AppRepo,
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
      case SpaEvent.UsageIngested(periodStart)      =>
        LogContext.annotate(LogContext.Op, "trafficUsage") {
          pushTrafficUsage(
            registry,
            trafficRepo,
            rollupRepo,
            deviceRepo,
            profileRepo,
            appRepo,
            periodStart,
          )
            .catchAllCause(c => ZIO.logErrorCause("spa ws push: trafficUsage recompute failed", c))
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

  /**
   * #1971 (S4): the `trafficUsage` live-edge aggregator (design §5.3). For each DISTINCT subscribed
   * param-set, recompute ONLY the bucket the just-ingested rows landed in via the shared
   * [[UsageTrafficQuery.aggregate]] (the same query the `GET` runs) scoped to that single bucket,
   * and push it as a `TrafficUsageResponse`. The device/profile maps are loaded ONCE and shared
   * across param-sets; app memberships are loaded only when some param-set groups by app. No
   * subscriber → empty param-set → nothing computed.
   *
   * #2004: `anchor` is the ingested batch's `periodStart` (traffic rows are bucketed by
   * `period_start`, `UsageTrafficQuery.listRawInRange`), NOT wall-clock `now`. A ~60s router report
   * covers the *prior* minute, so its `period_start` floors to the previous bucket; anchoring the
   * head on `now` left `[floor(now), now)` perpetually empty (the fresh row sits one bucket back)
   * and the dashboard LIVE BANDWIDTH panel stuck at 0 B/s. Anchoring on the period pushes the
   * bucket the data is actually in.
   */
  private def pushTrafficUsage(
      registry: SpaWsRegistry,
      trafficRepo: TrafficReportRepo,
      rollupRepo: RollupRepo,
      deviceRepo: DeviceRepo,
      profileRepo: ProfileRepo,
      appRepo: AppRepo,
      anchor: Instant,
  ): Task[Unit] =
    registry.trafficUsageParamSets.flatMap { paramSets =>
      ZIO
        .when(paramSets.nonEmpty) {
          for {
            devices  <- deviceRepo.listAll
            profiles <- profileRepo.listAll
            devByMac  = devices.iterator.map(d => d.mac -> d).toMap
            profNames = profiles.iterator.map(p => p.id -> p.name).toMap
            // App memberships are only needed if some subscribed param-set groups by app; load once.
            wantsApp  = paramSets.exists(p =>
              decodeParams(p).exists(_.groupBySet.contains(UsageTraffic.GroupBy.App)),
            )
            appsByHost <-
              if (wantsApp) loadAppsByHost(appRepo)
              else ZIO.succeed(Map.empty[String, List[AppMembership]])
            _          <- ZIO.foreachDiscard(paramSets.toList)(p =>
              pushOneParamSet(
                p,
                registry,
                trafficRepo,
                rollupRepo,
                devices,
                devByMac,
                profNames,
                appsByHost,
                anchor,
              ),
            )
          } yield ()
        }
        .unit
    }

  /**
   * Compute + push the head bucket for ONE param-set. A param-set whose `groupBy`/`bucket` can't be
   * parsed (or names `apex`, which the read model can't render yet) is skipped — a real subscriber
   * never sends one. A non-empty mac/profile filter that resolves to zero visible devices pushes an
   * empty head (never widens to the whole household — `macs = Nil` would mean "all" at the repo).
   */
  private def pushOneParamSet(
      params: Json,
      registry: SpaWsRegistry,
      trafficRepo: TrafficReportRepo,
      rollupRepo: RollupRepo,
      devices: List[Device],
      devByMac: Map[MacAddress, Device],
      profNames: Map[ProfileId, String],
      appsByHost: Map[String, List[AppMembership]],
      anchor: Instant,
  ): Task[Unit] =
    decodeParams(params) match {
      case None         =>
        ZIO.logDebug(s"spa ws push: skipping unrenderable trafficUsage params ${params.toJson}")
      case Some(parsed) =>
        // #2004: anchor the head bucket on the ingested `periodStart`, then scope to exactly that ONE
        // bucket `[headStart, headEnd)` where `headEnd = headStart + bucketWidth`. Rows are bucketed
        // by `period_start`, so this captures precisely the bucket the fresh rows landed in (one
        // `windowStart`) — the client's single-bucket head-merge (`wsCache.mergeHeadBucket`) requires
        // a single window. Anchoring on wall-clock `now` instead left this window empty in steady
        // state (a ~60s report's period sits one bucket back), the prod defect this fixes.
        val headStart    = UsageTraffic.floorTo(anchor, parsed.bucket, parsed.zone)
        val headEnd      = headStart.plus(UsageTraffic.stepOf(parsed.bucket))
        val resolvedMacs = UsageTrafficQuery.resolveMacs(parsed.macs, parsed.profileIds, devices)
        val filterEmpty  = parsed.filterRequested && resolvedMacs.isEmpty
        val aggregate    =
          if (filterEmpty) ZIO.succeed(List.empty)
          else
            UsageTrafficQuery.aggregate(
              trafficRepo,
              rollupRepo,
              resolvedMacs,
              headStart,
              headEnd,
              parsed.bucket,
              parsed.groupBySet,
              parsed.zone,
              devByMac,
              profNames,
              appsByHost,
            )
        aggregate.flatMap { rows =>
          val body = TrafficUsageResponse(
            bucket = parsed.bucket.code,
            groupBy = parsed.groupBySet.toList.map(_.code).sorted,
            from = headStart.toString,
            to = headEnd.toString,
            tz = parsed.zone.getId,
            rawRows = Nil,
            aggregateRows = rows,
            nextCursor = None,
          )
          registry.fanOutTrafficUsage(params, body.toJsonAST.getOrElse(Json.Obj()))
        }
    }

  /** Load the (host → app memberships) map for app grouping, exactly as `UsageRoutes` does. */
  private def loadAppsByHost(appRepo: AppRepo): Task[Map[String, List[AppMembership]]] =
    for {
      apps <- appRepo.listAll
      byId = apps.iterator.map(a => a.id -> a).toMap
      mappings <- appRepo.listAllHostMappings
    } yield mappings
      .flatMap(m =>
        byId
          .get(m.appId)
          .map(a => m.host.value -> AppMembership(a.slug, a.name, a.icon, Some(a.id))),
      )
      .groupMap(_._1)(_._2)

  // True iff the `/api/logs` row timestamp is at/after `since` (the genuinely-new edge). The row ts
  // is always ISO-8601 UTC ("…Z", ConnectionEventRepo `to_char(... 'Z')`), so a parse never fails in
  // practice; on the impossible malformed case KEEP the row rather than silently drop a real new one.
  private def atOrAfter(ts: String, since: Instant): Boolean =
    scala.util.Try(Instant.parse(ts)).map(!_.isBefore(since)).getOrElse(true)

  // Head-row cap per connectionEvents push. Matches the `/api/logs` default page; the client dedups
  // by id and the feed is "recent" anyway, so the newest 200 is plenty for the live edge (§3.1).
  private val ConnEventHeadLimit = 200

  /**
   * #1971 (S4): the decoded `trafficUsage` subscription params — the verbatim `GET
   * /api/usage/traffic` query params (§1.4 / §0.3). All optional; unknown fields ignored
   * (forward-compat). `groupBy` is a list of breakdown columns (empty = overall household);
   * `bucket` is the averaging window (defaults to `1m`, the aggregated floor, design §1.3); `tz`
   * defaults to UTC like the GET.
   */
  private final case class TrafficSubParams(
      groupBy: List[String] = Nil,
      bucket: Option[String] = None,
      macs: Option[List[String]] = None,
      profileIds: Option[List[ProfileId]] = None,
      tz: Option[String] = None,
  ) derives JsonDecoder

  /** A fully-resolved, renderable param-set (parse failures are dropped upstream). */
  private final case class TrafficSubParamsResolved(
      bucket: UsageTraffic.Bucket,
      groupBySet: Set[UsageTraffic.GroupBy],
      macs: List[MacAddress],
      profileIds: List[ProfileId],
      zone: ZoneId,
      filterRequested: Boolean,
  )

  /**
   * Decode + validate a `trafficUsage` param blob into a renderable shape, or `None` if it names a
   * bucket/groupBy the read model can't render (`apex`, or any unparseable value) — those never
   * come from a real subscriber, so skipping is correct (the `GET` would 400 them).
   */
  private def decodeParams(params: Json): Option[TrafficSubParamsResolved] =
    params.toJson.fromJson[TrafficSubParams].toOption.flatMap { p =>
      val groupByParsed = p.groupBy.map(UsageTraffic.GroupBy.parse)
      for {
        bucket  <- UsageTraffic.Bucket.parse(p.bucket.getOrElse("1m"))
        groupBy <- if (groupByParsed.contains(None)) None else Some(groupByParsed.flatten.toSet)
        if !groupBy.contains(UsageTraffic.GroupBy.Apex)
        zone    <- p.tz.fold(Option(ZoneId.of("UTC")))(s => scala.util.Try(ZoneId.of(s)).toOption)
      } yield {
        val macAddrs = p.macs.getOrElse(Nil).map(s => MacAddress.unsafe(normalizeMac(s)))
        val pids     = p.profileIds.getOrElse(Nil)
        TrafficSubParamsResolved(
          bucket = bucket,
          groupBySet = groupBy,
          macs = macAddrs,
          profileIds = pids,
          zone = zone,
          filterRequested = macAddrs.nonEmpty || pids.nonEmpty,
        )
      }
    }
}
