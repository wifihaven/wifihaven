package wifihaven.api.routes

import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.metrics.AppMetrics
import wifihaven.api.usage.{
  AppMembership,
  RawTrafficCursorKey,
  RetentionSweepJob,
  UsageSeries,
  UsageTraffic,
  UsageTrafficQuery,
}
import wifihaven.shared.*
import wifihaven.shared.types.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*

import java.time.{Duration, Instant, LocalDate, ZoneId}

// ── Usage routes (#716) ─────────────────────────────────────────────────────
//
// Per-device timeline (#721) and per-profile timeline (#722) share one endpoint.
// Exactly one of `mac=` or `profileId=` must be set.
//
// #1570: handlers (and their helper builders) fail with a typed [[ApiError]] mapped centrally by
// [[ErrorMapper.errorToResponse]]; the [[wifihaven.api.ErrorBoundary]] logs (4xx WARN / 5xx ERROR) +
// meters each error. Every case reproduces the EXACT status + body the hand-rolled code produced —
// the structured `unknown_bucket` / `unknown_groupBy` / `groupBy_not_implemented` 400 JSON bodies
// map through [[ApiError.BadRequest]] to the identical `Response.badRequest(...)`, DB failures stay
// 503 via [[ApiError.Db]].
object UsageRoutes {
  def routes(
      auth: AuthService,
      deviceRepo: DeviceRepo,
      trafficRepo: TrafficReportRepo,
      userProfileRepo: UserProfileRepo,
      profileRepo: ProfileRepo,
      appRepo: AppRepo,
      rollupRepo: RollupRepo,
      hsRepo: HouseholdSettingsRepo,
      appTimeLimitRepo: AppTimeLimitRepo,
      appUsedRollupRepo: AppUsedRollupRepo,
      clock: Clock,
      // #2077: the ambient anchor gate input. Noop (gate Off) keeps test call sites inert.
      ambientRepo: AmbientHostsRepo = NoopAmbientHostsRepo,
  ): Routes[Any, Response] =
    Routes(
      // #1743 + #1740: client-facing usage config the SPA reads at boot. Carries
      // the bucket → grain mapping (sourced from `BucketPolicy.bucketTiers`, so the
      // SPA no longer hand-mirrors `grainForBucket` in retentionGating.ts) AND the
      // retention horizons (sourced from `RetentionSweepJob`, so the SPA no longer
      // hand-mirrors its day counts either). One endpoint covers both SSOT folds.
      //
      // No bespoke metric: the HTTP middleware already meters `route`, `status`,
      // and duration for this path, and the response is compile-time constants
      // with no failure modes beyond auth.
      Method.GET / "api" / "usage" / "config"                                   ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            _ <- requireAuth(req, auth)
            cfg = UsageConfig(
              bucketTiers = BucketPolicy.bucketTiers.view.mapValues(_.wire).toMap,
              horizons = RetentionHorizons(
                rawDays = RetentionSweepJob.RawRetentionDays,
                hourlyDays = RetentionSweepJob.HourlyRetentionDays,
                dailyDays = RetentionSweepJob.DailyRetentionDays,
              ),
            )
          } yield Response.json(cfg.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.GET / "api" / "usage" / "traffic"                                  ->
        handler { (req: Request) =>
          trafficHandler(
            req,
            auth,
            deviceRepo,
            trafficRepo,
            rollupRepo,
            profileRepo,
            userProfileRepo,
            appRepo,
            clock,
          ).mapError(ErrorMapper.errorToResponse)
        },
      // #766: recently-visited FQDN apexes for one device, used by the
      // apps create/edit "Pick from recent activity" picker.
      Method.GET / "api" / "devices" / string("mac") / "recent-apexes"          ->
        handler { (macRaw: String, req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireAuth(req, auth)
            mac = MacAddress.unsafe(normalizeMac(macRaw))
            // #2108: household-scoped device lookup — a cross-household MAC 404s (incl. unmanaged
            // devices, which the role guard alone would not catch).
            device <- deviceRepo
              .findByMacInHousehold(mac, claims.hh)
              .mapError(ApiError.Db(_))
              .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("Device not found")))
            _ <- requireProfileReadAccess(claims, device.profileId, userProfileRepo, profileRepo)
            windowDays = req.url
              .queryParam("windowDays")
              .flatMap(_.toIntOption)
              .getOrElse(7)
              .max(1)
              .min(30)
            limit      = req.url
              .queryParam("limit")
              .flatMap(_.toIntOption)
              .getOrElse(50)
              .max(1)
              .min(500)
            now <- clock.instant
            from = now.minus(Duration.ofDays(windowDays.toLong))
            rows <- trafficRepo
              .listFqdnHostAggregatesForDevice(claims.hh, mac, from, now)
              .mapError(ApiError.Db(_))
            grouped = rows
              .groupBy { case (h, _, _) => Apex.orSelf(h) }
              .iterator
              .map { case (apex, members) =>
                val bytes = members.iterator.map(_._2).sum
                val hits  = members.iterator.map(_._3).sum
                val subs  = members
                  .map(_._1)
                  .filter(_ != apex)
                  .distinct
                  .sortBy(_.value)
                RecentApex(apex, bytes, hits, subs)
              }
              .toList
              .sortBy(r => (-r.bytes, r.apex.value))
              .take(limit)
            resp    = RecentApexesResponse(
              deviceMac = mac,
              deviceName = device.name,
              windowDays = windowDays,
              items = grouped,
            )
          } yield Response.json(resp.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      // #1061 — per-app time-used breakdown for one profile over [from,to].
      // Read-only companion to #767's rules editor; powers the per-app subsection
      // on the expanded /profiles card. Joins through `app_hosts` and returns one
      // row per app. #1519: hosts NOT in any configured app surface individually
      // in `orphanHosts` (single-host pseudo-apps, per the App-Centric Model) —
      // there is no synthetic "Other" entry on this endpoint.
      Method.GET / "api" / "profiles" / long("id") / "usage-by-app"             ->
        handler { (id: Long, req: Request) =>
          val pid                                  = ProfileId(id)
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireAuth(req, auth)
            _      <- requireProfileReadAccess(claims, pid, userProfileRepo, profileRepo)
            today  <- clock.today
            fromS = req.url.queryParam("from").getOrElse(today.toString)
            toS   = req.url.queryParam("to").getOrElse(fromS)
            from     <- ZIO
              .attempt(LocalDate.parse(fromS))
              .orElseFail(ApiError.BadRequest(s"invalid from: $fromS"))
            to       <- ZIO
              .attempt(LocalDate.parse(toS))
              .orElseFail(ApiError.BadRequest(s"invalid to: $toS"))
            _        <- ZIO
              .fail(ApiError.BadRequest("from must be <= to"))
              .when(from.isAfter(to))
            settings <- hsRepo.getForHousehold(claims.hh).mapError(ApiError.Db(_))
            resp     <- buildUsageByApp(
              pid,
              from,
              to,
              claims.hh,
              profileRepo,
              deviceRepo,
              trafficRepo,
              appRepo,
              appTimeLimitRepo,
              settings,
              ambientRepo,
            )
          } yield Response.json(resp.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      // #1089 — per-app engaged-minutes summed over a 7-day window. Aggregates FROM the
      // `app_used_daily` rollup (no parallel pipeline), so by construction the weekly figure is the
      // sum of the same daily numbers the per-app cap reads and the heartbeat filter applied at
      // rollup-write time flows through unchanged. `to` defaults to household-local today; `from`
      // is always `to - 6` (trailing 7-day window, matching the #777 `/time/status/summary/week`
      // convention). Today's row may lag the per-app cap by one rollup tick — the live tail past
      // the watermark is intentionally NOT folded in here; the screen-time weekly view tolerates
      // sub-tick staleness.
      Method.GET / "api" / "profiles" / long("id") / "usage" / "app" / "weekly" ->
        handler { (id: Long, req: Request) =>
          val pid                                  = ProfileId(id)
          val handle: ZIO[Any, ApiError, Response] = for {
            claims   <- requireAuth(req, auth)
            _        <- requireProfileReadAccess(claims, pid, userProfileRepo, profileRepo)
            settings <- hsRepo.getForHousehold(claims.hh).mapError(ApiError.Db(_))
            now      <- clock.instant
            todayLocal = wifihaven.api.policy.PolicyService.householdLocalDate(now, settings)
            toStr      = req.url.queryParam("to").getOrElse(todayLocal.toString)
            to <- ZIO
              .attempt(LocalDate.parse(toStr))
              .orElseFail(ApiError.BadRequest(s"invalid to: $toStr"))
            from = to.minusDays(6)
            profile <- profileRepo
              .findById(pid)
              .mapError(ApiError.Db(_))
              .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("Profile not found")))
            rows    <- appUsedRollupRepo
              .getRangeForProfile(pid, from, to)
              .mapError(ApiError.Db(_))
            appList <- appRepo.listAll.mapError(ApiError.Db(_))
          } yield {
            val byId       = appList.iterator.map(a => a.id -> a).toMap
            val sumByApp   = rows.groupMapReduce(_._2)(_._3)(_ + _)
            val appRows    = sumByApp.iterator.flatMap { case (aid, secs) =>
              byId.get(aid).map { a =>
                ProfileAppWeeklyUsageRow(
                  appId = aid,
                  appName = a.name,
                  appIcon = a.icon,
                  appIconType = Some(a.iconType),
                  engagedMinutes = (secs / 60L).toInt,
                )
              }
            }.toList
            val sortedRows = appRows
              .filter(_.engagedMinutes > 0)
              .sortBy(r => (-r.engagedMinutes, r.appName))
            val resp       = ProfileAppWeeklyUsage(
              profileId = pid,
              profileName = profile.name,
              from = from.toString,
              to = to.toString,
              apps = sortedRows,
            )
            Response.json(resp.toJson)
          }
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.GET / "api" / "usage" / "series"                                   ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireAuth(req, auth)
            today  <- clock.today
            macOpt = req.url.queryParam("mac").map(s => MacAddress.unsafe(normalizeMac(s)))
            profileIdOpt <- ZIO
              .fromEither(
                req.url.queryParam("profileId") match {
                  case None    => Right(None)
                  case Some(s) =>
                    s.toLongOption
                      .map(l => Some(ProfileId(l)))
                      .toRight(s"invalid profileId: $s")
                },
              )
              .mapError(ApiError.BadRequest(_))
            _            <- ZIO
              .fail(ApiError.BadRequest("exactly one of mac or profileId is required"))
              .when(macOpt.isDefined == profileIdOpt.isDefined)
            dateStr = req.url.queryParam("date").getOrElse(today.toString)
            date <- ZIO
              .attempt(LocalDate.parse(dateStr))
              .orElseFail(ApiError.BadRequest(s"invalid date: $dateStr"))
            tzStr = req.url.queryParam("tz").getOrElse("UTC")
            zone <- ZIO
              .attempt(ZoneId.of(tzStr))
              .orElseFail(ApiError.BadRequest(s"invalid tz: $tzStr"))
            topN       = req.url
              .queryParam("topN")
              .flatMap(_.toIntOption)
              .getOrElse(5)
              .max(1)
              // #964: cap bumped from 20 → 500 so the per-device 'other'
              // drill-in can request the full long-tail of hosts in one shot.
              .min(500)
            // #1079: groupBy=app surfaces the unified per-app + per-non-app-host
            // axis on the response (`topEntries` / `bucketsByEntry`). Optional;
            // older Host/Device-only callers leave it off and the new fields
            // stay empty.
            groupByApp = req.url.queryParam("groupBy").exists(_.split(',').contains("app"))
            appLookup <-
              if (groupByApp) loadAppLookup(appRepo)
              else ZIO.succeed(UsageSeries.AppAxis.empty)
            settings  <- hsRepo.getForHousehold(claims.hh).mapError(ApiError.Db(_))
            resp      <- (macOpt, profileIdOpt) match {
              case (Some(mac), _) =>
                buildForDevice(
                  mac,
                  date,
                  zone,
                  topN,
                  claims,
                  deviceRepo,
                  trafficRepo,
                  userProfileRepo,
                  profileRepo,
                  groupByApp,
                  appLookup,
                  settings,
                  appTimeLimitRepo,
                  ambientRepo,
                )
              case (_, Some(pid)) =>
                buildForProfile(
                  pid,
                  date,
                  zone,
                  topN,
                  claims,
                  profileRepo,
                  deviceRepo,
                  trafficRepo,
                  userProfileRepo,
                  appTimeLimitRepo,
                  groupByApp,
                  appLookup,
                  settings,
                  ambientRepo,
                )
              case _              => ZIO.fail(ApiError.BadRequest("unreachable"))
            }
          } yield Response.json(resp.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      // #1099: batched per-profile series. The /profiles page resolves the
      // whole visible profile set in one partition-pruned scan instead of N
      // parallel single-profile requests.
      Method.GET / "api" / "usage" / "series" / "batch"                         ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireAuth(req, auth)
            today  <- clock.today
            pids   <- parseMultiProfileIdParam(req).map(_.distinct)
            dateStr = req.url.queryParam("date").getOrElse(today.toString)
            date <- ZIO
              .attempt(LocalDate.parse(dateStr))
              .orElseFail(ApiError.BadRequest(s"invalid date: $dateStr"))
            tzStr = req.url.queryParam("tz").getOrElse("UTC")
            zone <- ZIO
              .attempt(ZoneId.of(tzStr))
              .orElseFail(ApiError.BadRequest(s"invalid tz: $tzStr"))
            topN       = req.url
              .queryParam("topN")
              .flatMap(_.toIntOption)
              .getOrElse(5)
              .max(1)
              .min(500)
            groupByApp = req.url.queryParam("groupBy").exists(_.split(',').contains("app"))
            appLookup <-
              if (groupByApp) loadAppLookup(appRepo)
              else ZIO.succeed(UsageSeries.AppAxis.empty)
            settings  <- hsRepo.getForHousehold(claims.hh).mapError(ApiError.Db(_))
            resp      <- buildBatch(
              pids,
              date,
              zone,
              topN,
              claims,
              profileRepo,
              deviceRepo,
              trafficRepo,
              userProfileRepo,
              appTimeLimitRepo,
              groupByApp,
              appLookup,
              settings,
              ambientRepo,
            )
          } yield Response.json(resp.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
    )

  // #1099: resolve the whole visible profile set in ONE partition-pruned scan.
  // Loads devices once, unions every requested profile's macs, runs a single
  // fetchPresenceDayWindow, then partitions the rows by mac and assembles each
  // profile's response exactly as buildForProfile would. Per-profile read-access
  // checks are preserved so a caller can't widen its reach via the batch route.
  private def buildBatch(
      pids: List[ProfileId],
      date: LocalDate,
      zone: ZoneId,
      topN: Int,
      claims: JwtClaims,
      profileRepo: ProfileRepo,
      deviceRepo: DeviceRepo,
      trafficRepo: TrafficReportRepo,
      userProfileRepo: UserProfileRepo,
      appTimeLimitRepo: AppTimeLimitRepo,
      groupByApp: Boolean,
      appLookup: UsageSeries.AppAxis,
      settings: HouseholdSettings,
      ambientRepo: AmbientHostsRepo,
  ): IO[ApiError, UsageSeriesBatchResponse] =
    for {
      _            <- ZIO.foreachDiscard(pids)(pid =>
        requireProfileReadAccess(claims, pid, userProfileRepo, profileRepo),
      )
      profiles     <- ZIO.foreach(pids) { pid =>
        profileRepo
          .findById(pid)
          .mapError(ApiError.Db(_))
          .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("Profile not found")))
      }
      allDevices   <- deviceRepo.listAllForHousehold(claims.hh).mapError(ApiError.Db(_))
      // #1492: exempt-from-daily site patterns must match the headline daily total exactly, so
      // load them per profile (same input `usedSecondsForProfile` uses) and carve them out.
      appLimsByPid <- ZIO
        .foreach(pids)(pid => appTimeLimitRepo.listForProfile(pid).map(pid -> _))
        .map(_.toMap)
        .mapError(ApiError.Db(_))
      exemptByPid = appLimsByPid.view
        .mapValues(sl => sl.filter(_.exemptFromDaily).map(_.domainPattern))
        .toMap
      ambient      <- ambientRepo.gateFor(settings, date).mapError(ApiError.Db(_))
      devicesByPid = pids.iterator
        .map(pid => pid -> allDevices.filter(_.profileId.contains(pid)))
        .toMap
      allMacs      = devicesByPid.valuesIterator.flatten.map(_.mac).toList.distinct
      rows <- fetchPresenceDayWindow(claims.hh, trafficRepo, allMacs, date, zone)
    } yield {
      val series = profiles.map { profile =>
        val devices   = devicesByPid.getOrElse(profile.id, Nil)
        val macSet    = devices.iterator.map(_.mac).toSet
        val nameByMac = devices.iterator.map(d => d.mac -> d.name).toMap
        // #2077: gate each profile's slice with its own app-attribution context.
        val pRows     = wifihaven.api.policy.TimeStatusService.gatedPresence(
          appLimsByPid.getOrElse(profile.id, Nil),
          rows.filter(r => macSet.contains(r.mac)),
          settings,
          ambient,
        )
        val exempt    = exemptByPid.getOrElse(profile.id, Nil)
        val (topHosts, bucketsByHost, topDevices, bucketsByDevice, presenceTotalMins) =
          UsageSeries.buildProfile(
            pRows,
            nameByMac,
            date,
            zone,
            topN,
            profile.crossDeviceOverlapMode,
            exempt,
            settings.heartbeatFilter,
            settings.presenceContinuationSeconds,
          )
        val (topEntries, bucketsByEntry)                                              =
          if (groupByApp)
            UsageSeries.buildEntries(
              pRows,
              date,
              zone,
              topN,
              profile.crossDeviceOverlapMode,
              exempt,
              settings.heartbeatFilter,
              settings.presenceContinuationSeconds,
              appLookup,
            )
          else (List.empty[UsageEntityTotal], List.empty[UsageEntityBucket])
        UsageSeriesResponse(
          profileId = Some(profile.id),
          profileName = Some(profile.name),
          date = date.toString,
          tz = zone.getId,
          topHosts = topHosts,
          buckets = bucketsByHost,
          topDevices = topDevices,
          bucketsByDevice = bucketsByDevice,
          topEntries = topEntries,
          bucketsByEntry = bucketsByEntry,
          presenceTotalMins = presenceTotalMins,
        )
      }
      UsageSeriesBatchResponse(series)
    }

  // #1061 — per-app rollup. Joins `app_hosts` to map each host → owning app (a
  // host in two apps deterministically picks the lowest appId so a bucket isn't
  // counted twice for the same minute). Aggregates two parallel numbers per app:
  //   - proportionalSeconds (#715): sum of host byte-share-weighted bucket-seconds
  //   - presenceSeconds: each (mac, period_start) bucket contributes once per
  //     distinct app touched in that bucket. Not additive across apps (this is
  //     intentional — surfaces "was this app touched at all" volume).
  //
  // #1519 — hosts NOT in any configured app are NOT lumped into a synthetic
  // "Other" app. Per the App-Centric Model, a non-app host is its own
  // single-host app. They surface individually in `orphanHosts`, each with the
  // same proportionalSeconds/presenceSeconds units as an app row, so the SPA
  // can render them side-by-side with real apps. "Other" only ever appears as
  // a top-N display rollup at the SPA layer.
  // #1974 (SPA-ws S6a): `private[routes]` (was object-private) so the `appUsage` ws push
  // ([[SpaPush]], same package) rebuilds the per-app body through the EXACT same repo-load + compute
  // path the GET runs — the streamed body is byte-identical to `GET /api/profiles/{id}/usage-by-app`
  // (AGENTS.md §single-source-of-truth). The push calls it with `from == to == clock.today` (the
  // GET's today default).
  private[routes] def buildUsageByApp(
      pid: ProfileId,
      from: LocalDate,
      to: LocalDate,
      // #2257: the caller's household — the GET passes `claims.hh`, the `appUsage` ws push passes the
      // recipient's household. The device read is scoped to it so this never scans another household's
      // rows (the #2251/#2120 leak class). `pid` is already entitled to the caller's household by the
      // GET's `requireProfileReadAccess` / the push's per-household profile-id gate.
      household: HouseholdId,
      profileRepo: ProfileRepo,
      deviceRepo: DeviceRepo,
      trafficRepo: TrafficReportRepo,
      appRepo: AppRepo,
      appTimeLimitRepo: AppTimeLimitRepo,
      settings: HouseholdSettings,
      ambientRepo: AmbientHostsRepo,
  ): IO[ApiError, ProfileUsageByApp] = {
    val filter              = settings.heartbeatFilter
    val continuationSeconds = settings.presenceContinuationSeconds
    for {
      profile <- profileRepo
        .findById(pid)
        .mapError(ApiError.Db(_))
        .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("Profile not found")))
      allDevs <- deviceRepo.listAllForHousehold(household).mapError(ApiError.Db(_))
      macs = allDevs.collect { case d if d.profileId.contains(pid) => d.mac }
      raw       <- (if (macs.isEmpty) ZIO.succeed(Nil)
              else trafficRepo.listPresenceRows(household, macs, from, to))
        .mapError(ApiError.Db(_))
      appList   <- appRepo.listAll.mapError(ApiError.Db(_))
      mappings  <- appRepo.listAllHostMappings.mapError(ApiError.Db(_))
      // #1898: the per-(app, host) `shared` flag rides the per-profile assignment join so
      // `distinctiveSpansByApp` can partition each app's host-set; classification of which traffic
      // hosts are shared comes from the GLOBAL `mappings` above (a catalog-wide property).
      appLimits <- appTimeLimitRepo.listForProfile(pid).mapError(ApiError.Db(_))
      // #2077: gate with the profile's app-attribution context (window anchored on `to`, the
      // GET's today default) so the per-app/orphan breakdown reconciles with the daily headline.
      ambient   <- ambientRepo.gateFor(settings, to).mapError(ApiError.Db(_))
      presence       = wifihaven.api.policy.TimeStatusService
        .gatedPresence(appLimits, raw, settings, ambient)
      built          = computeUsageByApp(
        pid,
        from,
        to,
        profile,
        presence,
        appList,
        mappings,
        appLimits,
        settings,
        filter,
        continuationSeconds,
      )
      (resp, counts) = built
      // #1898: bounded-label outcome counter for shared-row attribution (attributed/split/other).
      _ <- AppMetrics.recordSharedHostAttribution("attributed", counts.attributed)
      _ <- AppMetrics.recordSharedHostAttribution("split", counts.split)
      _ <- AppMetrics.recordSharedHostAttribution("other", counts.other)
    } yield resp
  }

  // #1898: pure core of `buildUsageByApp`. Distinctive hosts keep the unconditional `minBy(appId)`
  // mapping; SHARED hosts (multi-tenant backends listed on several apps) are routed through the
  // co-presence allocation — their per-row proportional seconds attribute to the app(s) whose
  // DISTINCTIVE session overlaps the row, split EQUALLY among co-present qualifiers, else "Other".
  // This is NOT the rejected #842/#715 argmax-foreground heuristic: allocation is gated purely on an
  // app's own distinctive presence, never on byte magnitude. Returns the response plus the per-span
  // attribution tally for the metric.
  private def computeUsageByApp(
      pid: ProfileId,
      from: LocalDate,
      to: LocalDate,
      profile: Profile,
      presence: List[wifihaven.api.presence.PresenceRow],
      appList: List[App],
      mappings: List[AppHost],
      appLimits: List[AppTimeLimit],
      settings: HouseholdSettings,
      filter: HeartbeatFilter,
      continuationSeconds: Int,
  ): (ProfileUsageByApp, wifihaven.api.presence.Presence.SharedHostAttributionCounts) = {
    import wifihaven.api.presence.Presence
    val appById = appList.iterator.map(a => a.id -> a).toMap
    val overlap = profile.crossDeviceOverlapMode

    // Distinctive host → owning-app: lowest appId wins when a host is in multiple apps (#1061),
    // apex-aware so subdomain traffic attributes to the apex-form entry (#1161). #1898: SHARED rows
    // are EXCLUDED from this map — a shared host is never distinctively credited to one app.
    val distinctiveByApex = mappings
      .filterNot(_.shared)
      .groupBy(_.host.value)
      .view
      .mapValues(ms => ms.iterator.map(_.appId).minBy(_.value))
      .toMap
    // #1898: apps that LIST a host as shared (the co-presence candidates), apex-keyed. Global
    // consistency (validation S1b) guarantees a host shared on any app is shared on every app
    // listing it, so a host is never simultaneously distinctive and shared.
    val sharedAppsByApex  = mappings
      .filter(_.shared)
      .groupBy(_.host.value)
      .view
      .mapValues(ms => ms.iterator.map(_.appId).toSet)
      .toMap

    def distinctiveAppOf(h: HostId): Option[AppId] =
      h.asFqdn.flatMap(fqdn => HostMatch.lookupApex(fqdn.value, distinctiveByApex))
    def sharedAppsOf(h: HostId): Set[AppId]        =
      h.asFqdn
        .flatMap(fqdn => HostMatch.lookupApex(fqdn.value, sharedAppsByApex))
        .getOrElse(Set.empty)

    // #1898: the S2 single-source-of-truth seam — each app's DISTINCTIVE-host engaged spans. S3 reads
    // these for the co-presence overlap test rather than re-deriving the distinctive partition.
    //
    // #2744: these spans are ALSO the per-app headline (`proportionalSeconds`) below. They are the
    // gap-bridged stitch over the app's distinctive host-set — `Presence.appSpansForProfile`, the
    // #1514/#1532 "exactly one per-app time computation" — so for a TimeLimited app the headline is
    // literally the spans `TimeStatusService.appSecondsByApp` sums for the per-app cap and the
    // `app_used_daily` rollup, and display cannot drift from enforcement.
    //
    // Previously the headline was rebuilt by SUMMING `Presence.proportionalHostSeconds` over the
    // app's hosts. That treats an app's hosts as additive, so an app whose apex and CDN carry one
    // browsing session reported ~2x its engaged time (prod 2026-08-25: Khan Academy 50 min here vs
    // 37 min on the cap/rollup, off a 28.2-minute wall-clock envelope). The per-host `hosts`
    // drill-down below still carries the per-host allocation — that is host-level detail, and it
    // deliberately does NOT sum to the app headline, precisely because an app's hosts overlap in
    // wall clock.
    //
    // `catalogOnly` carries the apps this endpoint reports on that the profile has NO assignment
    // for (#1519: a non-app host is its own single-host app, and an unassigned catalog app still
    // gets a row). They ride the SAME seam rather than a second derivation beside it, so every app
    // in this response shares one distinctive host-set derivation, one `appHostPatterns` scope and
    // one `effectiveGap`. Membership is tested with `HostMatch.matchesAny` — the same matcher the
    // primitive itself uses — over the batch's DISTINCT hosts, so an app the primitive would match
    // is never gated out by a different matcher.
    val assignedAppIds: Set[AppId]                      = appLimits.map(_.appId).toSet
    val distinctiveHostsByApp: Map[AppId, List[String]] =
      mappings.filterNot(_.shared).groupBy(_.appId).view.mapValues(_.map(_.host.value)).toMap
    val presenceHosts: List[HostId]                     = presence.map(_.host).distinct
    val catalogOnly: List[(AppId, List[String])]        =
      distinctiveHostsByApp.iterator
        .filterNot { case (id, _) => assignedAppIds.contains(id) }
        .filter { case (_, hosts) => presenceHosts.exists(h => HostMatch.matchesAny(h, hosts)) }
        .toList
    val distinctiveSpans                                =
      wifihaven.api.policy.TimeStatusService.distinctiveSpansByApp(
        profile,
        appLimits,
        presence,
        settings,
        catalogOnly,
      )
    val canonicalSecondsByApp: Map[AppId, Long]         =
      distinctiveSpans.view.mapValues(_.map(_.seconds).sum).filter(_._2 > 0L).toMap

    // #1465: per-host presence is the session-stitch span (heartbeat-filtered), combined across the
    // profile's devices by its `crossDeviceOverlapMode`.
    val propByHost    =
      Presence.proportionalHostSeconds(presence, overlap, filter, continuationSeconds)
    val seenByHost    = Presence.hostMinutes(presence, filter)
    val propMinByHost =
      Presence.proportionalHostMinutes(presence, overlap, filter, continuationSeconds)

    // Per host, the proportional-seconds allocation across `Some(appId)` / `None` ("Other").
    var counts = Presence.SharedHostAttributionCounts.zero
    val allocByHost: Map[HostId, Map[Option[AppId], Long]] =
      propByHost.map { case (h, secs) =>
        val candidates = sharedAppsOf(h)
        if (candidates.nonEmpty) {
          val candidateSpans = distinctiveSpans.view.filterKeys(candidates.contains).toMap
          val (alloc, c)     = Presence.allocateSharedHostSeconds(
            presence,
            h,
            candidateSpans,
            overlap,
            filter,
            continuationSeconds,
          )
          counts = counts + c
          h -> alloc
        } else h -> Map(distinctiveAppOf(h) -> secs)
      }
    // The set of `Option[AppId]` keys each host's activity touches (for the non-additive
    // presence-seconds surface). A shared host split across apps + "Other" touches all of them.
    val touchedByHost: Map[HostId, Set[Option[AppId]]]     =
      allocByHost.view.mapValues(_.keySet).toMap

    val hostsByApp = scala.collection.mutable.Map.empty[Option[AppId], List[HostUsage]]
    for ((h, alloc) <- allocByHost) {
      val totalSecs = alloc.values.sum
      val single    = alloc.sizeIs == 1
      for ((key, secs) <- alloc if key.isDefined) {
        // Distinctive (single-key) host carries its display minutes verbatim; a split shared host's
        // proportional minutes follow its allocated seconds, and its `usedMins` (bucket presence) is
        // scaled by the same fraction so the per-app host row reflects the share.
        val usedM =
          if (single) seenByHost.getOrElse(h, 0)
          else if (totalSecs <= 0L) 0
          else
            math
              .round(seenByHost.getOrElse(h, 0).toDouble * (secs.toDouble / totalSecs.toDouble))
              .toInt
        val propM = if (single) propMinByHost.getOrElse(h, 0) else (secs / 60L).toInt
        val hu    = HostUsage(h, usedM, propM)
        hostsByApp.updateWith(key)(prev => Some(hu :: prev.getOrElse(Nil)))
      }
    }

    // Per-app bucket-dedup for presence-seconds (heartbeat rows stripped, #1465). Not additive across
    // apps by design — a bucket's seconds count once per distinct app key it touches.
    val appPresence = scala.collection.mutable.Map.empty[Option[AppId], Long]
    val activeRows  = presence.filterNot(r => Presence.isHeartbeat(r, filter))
    for ((_, bucket) <- activeRows.groupBy(r => (r.mac, r.periodStart))) {
      val secs = bucket.iterator.map(_.activeSeconds.toLong).maxOption.getOrElse(0L)
      val keys = bucket.iterator.flatMap(r => touchedByHost.getOrElse(r.host, Set.empty)).toSet
      for (a <- keys if a.isDefined)
        appPresence.updateWith(a)(prev => Some(prev.getOrElse(0L) + secs))
    }

    // #1519: real-app rows ONLY. The `None` key surfaces as individual orphanHosts entries below.
    // #2744: the engaged-seconds side of the key set is the canonical map, not a per-host sum.
    val appKeys =
      (canonicalSecondsByApp.keySet ++ appPresence.keySet.flatten).toList.distinct
    val rows    = appKeys.map { aid =>
      val key   = Some(aid)
      val name  = appById.get(aid).map(_.name).getOrElse(aid.value.toString)
      val icon  = appById.get(aid).flatMap(_.icon)
      val it    = appById.get(aid).map(_.iconType)
      val hosts = hostsByApp
        .getOrElse(key, Nil)
        .sortBy(hu => (-hu.proportionalMins, -hu.usedMins, hu.host.value))
      ProfileAppUsage(
        appId = key,
        appName = name,
        appIcon = icon,
        appIconType = it,
        proportionalSeconds = canonicalSecondsByApp.getOrElse(aid, 0L),
        presenceSeconds = appPresence.getOrElse(key, 0L),
        hosts = hosts,
      )
    }

    // #1519: orphan rows = the unattributed portion of each host, each rendered as its own
    // single-host pseudo-app. #1898: a SHARED host with a "no qualifier" span contributes its
    // unattributed seconds here (the "Other" fallback) — possibly alongside app rows for its
    // attributed spans. proportional = the `None`-keyed allocation; presence = dedupe (mac,
    // periodStart) buckets PER HOST.
    val orphanHostSet  = allocByHost.iterator.collect {
      case (h, alloc) if alloc.get(None).exists(_ > 0L) => h
    }.toSet
    val orphanPresence = scala.collection.mutable.Map.empty[HostId, Long]
    for ((_, bucket) <- activeRows.groupBy(r => (r.mac, r.periodStart))) {
      val secs      = bucket.iterator.map(_.activeSeconds.toLong).maxOption.getOrElse(0L)
      val hostsHere = bucket.iterator.map(_.host).filter(orphanHostSet.contains).toSet
      for (h <- hostsHere)
        orphanPresence.updateWith(h)(prev => Some(prev.getOrElse(0L) + secs))
    }
    val orphanRows = orphanHostSet.toList.map { h =>
      OrphanHostUsage(
        host = h,
        proportionalSeconds = allocByHost.get(h).flatMap(_.get(None)).getOrElse(0L),
        presenceSeconds = orphanPresence.getOrElse(h, 0L),
      )
    }

    val resp = ProfileUsageByApp(
      profileId = pid,
      profileName = profile.name,
      from = from.toString,
      to = to.toString,
      apps = rows.sortBy(r => (-r.proportionalSeconds, -r.presenceSeconds, r.appName)),
      orphanHosts = orphanRows
        .sortBy(o => (-o.proportionalSeconds, -o.presenceSeconds, o.host.value)),
    )
    (resp, counts)
  }

  // #1079 — build the by-app axis used by the unified-axis builder. `appOf` mirrors the
  // deterministic "lowest appId wins" tiebreak from #1061 and the apex-aware match from #1085 so
  // subdomain traffic attributes to the apex-form app entry. #1517: also carry each app's full
  // host-set (`patternsBySlug`) so the per-app series spans are gap-bridged over the SAME host-set
  // the per-app cap aggregate and the #1510 rollup use (via Presence.appSpansForProfile), keyed by
  // slug. Both are built from one `app_hosts` snapshot so the attribution and the span host-set
  // can't drift.
  //
  // #1898: SHARED hosts are excluded from BOTH `appOf` and `patternsBySlug` here. The per-app series
  // span is the gap-bridged union of an app's host-set, and a shared backend folded in would extend
  // the app's span past its distinctive activity — exactly the inflation S2 removed from the cap
  // aggregate (TimeStatusService reads `capGroupLabelDistinctiveHosts`). Keeping this axis
  // distinctive-only keeps the per-app series reconciling with the cap and the #1510 rollup by
  // construction; a shared host instead surfaces as a standalone host entry (the time-series analog
  // of the usage-by-app "Other"/orphan bucket). Per-bucket co-presence allocation is not applied to
  // the time-series; the worked-example allocation lives on the `buildUsageByApp` totals surface.
  private def loadAppLookup(
      appRepo: AppRepo,
  ): IO[ApiError, UsageSeries.AppAxis] =
    for {
      apps    <- appRepo.listAll.mapError(ApiError.Db(_))
      allMaps <- appRepo.listAllHostMappings.mapError(ApiError.Db(_))
    } yield {
      val mappings       = allMaps.filterNot(_.shared)
      val appById        = apps.iterator.map(a => a.id -> a).toMap
      val byApex         = mappings
        .groupBy(_.host.value)
        .view
        .mapValues(ms => ms.iterator.map(_.appId).minBy(_.value))
        .toMap
      val appOf          = (h: HostId) =>
        h.asFqdn
          .flatMap(fqdn => HostMatch.lookupApex(fqdn.value, byApex))
          .flatMap(appById.get)
          .map(a => UsageSeries.AppInfo(a.id, a.slug, a.name, a.icon))
      val patternsBySlug = mappings
        .groupBy(_.appId)
        .iterator
        .flatMap { case (aid, ms) =>
          appById.get(aid).map(a => a.slug -> ms.map(_.host.value).distinct)
        }
        .toMap
      UsageSeries.AppAxis(appOf, patternsBySlug)
    }

  private def buildForDevice(
      mac: MacAddress,
      date: LocalDate,
      zone: ZoneId,
      topN: Int,
      claims: JwtClaims,
      deviceRepo: DeviceRepo,
      trafficRepo: TrafficReportRepo,
      userProfileRepo: UserProfileRepo,
      profileRepo: ProfileRepo,
      groupByApp: Boolean,
      appLookup: UsageSeries.AppAxis,
      settings: HouseholdSettings,
      appTimeLimitRepo: AppTimeLimitRepo,
      ambientRepo: AmbientHostsRepo,
  ): IO[ApiError, UsageSeriesResponse] =
    for {
      // #2108: household-scoped device lookup — a cross-household MAC 404s (design §7 pin 1/2).
      device    <- deviceRepo
        .findByMacInHousehold(mac, claims.hh)
        .mapError(ApiError.Db(_))
        .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("Device not found")))
      _         <- requireProfileReadAccess(claims, device.profileId, userProfileRepo, profileRepo)
      raw       <- fetchPresenceDayWindow(claims.hh, trafficRepo, List(mac), date, zone)
      appLimits <- device.profileId
        .fold(ZIO.succeed(List.empty[wifihaven.shared.AppTimeLimit]))(pid =>
          appTimeLimitRepo.listForProfile(pid),
        )
        .mapError(ApiError.Db(_))
      // #2077: gate the presence-derived series with the same definition as the daily headline.
      ambient   <- ambientRepo.gateFor(settings, date).mapError(ApiError.Db(_))
      rows                                   = wifihaven.api.policy.TimeStatusService
        .gatedPresence(appLimits, raw, settings, ambient)
      (topHosts, buckets, presenceTotalMins) =
        UsageSeries.build(
          rows,
          date,
          zone,
          topN,
          settings.heartbeatFilter,
          settings.presenceContinuationSeconds,
        )
      // #1507: surface what the engaged-time calculation excluded so the drill-in can show a
      // collapsed "background / infra" group. App-aware via [[appLookup]] — a host attributed to
      // any app's host-set is NOT reported here, matching the post-#1506 attribution-beats-
      // suppression semantics. #1560 will centralize this with the per-host-span call site.
      suppressedHostRows                     =
        wifihaven.api.presence.Presence.suppressedHostUsage(
          rows,
          settings.heartbeatFilter,
          appLookup.patternsBySlug.valuesIterator.flatten.toList.distinct,
        )
      // Single-device view → per-device union is the total; no cross-device overlap mode applies.
      (topEntries, bucketsByEntry)           =
        if (groupByApp)
          UsageSeries.buildEntries(
            rows,
            date,
            zone,
            topN,
            CrossDeviceOverlapMode.Sum,
            Nil,
            settings.heartbeatFilter,
            settings.presenceContinuationSeconds,
            appLookup,
          )
        else (List.empty[UsageEntityTotal], List.empty[UsageEntityBucket])
    } yield UsageSeriesResponse(
      deviceMac = Some(mac),
      deviceName = Some(device.name),
      date = date.toString,
      tz = zone.getId,
      topHosts = topHosts,
      buckets = buckets,
      topEntries = topEntries,
      bucketsByEntry = bucketsByEntry,
      presenceTotalMins = presenceTotalMins,
      suppressedHosts =
        suppressedHostRows.map(r => SuppressedHostUsage(r.host, r.bytes, r.buckets, r.reason)),
    )

  private def buildForProfile(
      pid: ProfileId,
      date: LocalDate,
      zone: ZoneId,
      topN: Int,
      claims: JwtClaims,
      profileRepo: ProfileRepo,
      deviceRepo: DeviceRepo,
      trafficRepo: TrafficReportRepo,
      userProfileRepo: UserProfileRepo,
      appTimeLimitRepo: AppTimeLimitRepo,
      groupByApp: Boolean,
      appLookup: UsageSeries.AppAxis,
      settings: HouseholdSettings,
      ambientRepo: AmbientHostsRepo,
  ): IO[ApiError, UsageSeriesResponse] =
    for {
      _         <- requireProfileReadAccess(claims, pid, userProfileRepo, profileRepo)
      profile   <- profileRepo
        .findById(pid)
        .mapError(ApiError.Db(_))
        .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("Profile not found")))
      all       <- deviceRepo.listAllForHousehold(claims.hh).mapError(ApiError.Db(_))
      appLimits <- appTimeLimitRepo.listForProfile(pid).mapError(ApiError.Db(_))
      // #1630: read the canonical exempt-pattern primitive (`ProfileAppDispositions.exemptPatterns`)
      // instead of re-deriving the filter inline — the same §single-source-of-truth shape the
      // collapse exists to enforce. Both sides agree on the current input (the repo returns rows
      // for every mode), so this is a structural cleanup rather than a behavior change.
      exempt    = wifihaven.api.policy.ProfileAppDispositions.from(appLimits).exemptPatterns
      devices   = all.filter(_.profileId.contains(pid))
      macs      = devices.map(_.mac)
      nameByMac = devices.iterator.map(d => d.mac -> d.name).toMap
      raw     <- fetchPresenceDayWindow(claims.hh, trafficRepo, macs, date, zone)
      // #2077: gate the presence-derived series with the same definition as the daily headline.
      ambient <- ambientRepo.gateFor(settings, date).mapError(ApiError.Db(_))
      rows = wifihaven.api.policy.TimeStatusService
        .gatedPresence(appLimits, raw, settings, ambient)
      (topHosts, bucketsByHost, topDevices, bucketsByDevice, presenceTotalMins) =
        UsageSeries.buildProfile(
          rows,
          nameByMac,
          date,
          zone,
          topN,
          profile.crossDeviceOverlapMode,
          exempt,
          settings.heartbeatFilter,
          settings.presenceContinuationSeconds,
        )
      (topEntries, bucketsByEntry)                                              =
        if (groupByApp)
          UsageSeries.buildEntries(
            rows,
            date,
            zone,
            topN,
            profile.crossDeviceOverlapMode,
            exempt,
            settings.heartbeatFilter,
            settings.presenceContinuationSeconds,
            appLookup,
          )
        else (List.empty[UsageEntityTotal], List.empty[UsageEntityBucket])
    } yield UsageSeriesResponse(
      profileId = Some(pid),
      profileName = Some(profile.name),
      date = date.toString,
      tz = zone.getId,
      topHosts = topHosts,
      buckets = bucketsByHost,
      topDevices = topDevices,
      bucketsByDevice = bucketsByDevice,
      topEntries = topEntries,
      bucketsByEntry = bucketsByEntry,
      presenceTotalMins = presenceTotalMins,
    )

  // Pull the requested local-day window as a single partition-pruned query
  // (#1099). The local day [00:00, 24:00) in `zone` maps to a contiguous UTC
  // instant window, and listPresenceRowsInWindow filters on period_start (the
  // partition key) so Postgres prunes to just the touched day-partition(s).
  // This replaces the old three-call (date-1/date/date+1) + zone post-filter,
  // whose `tr.date` predicate defeated pruning and let one profile scan the
  // whole table for 90s. The row set is identical: both select exactly the
  // rows whose period_start falls in the local day.
  private def fetchPresenceDayWindow(
      household: HouseholdId,
      trafficRepo: TrafficReportRepo,
      macs: List[MacAddress],
      date: LocalDate,
      zone: ZoneId,
  ): IO[ApiError, List[wifihaven.api.presence.PresenceRow]] =
    if (macs.isEmpty) ZIO.succeed(Nil)
    else {
      val from = date.atStartOfDay(zone).toInstant
      val to   = date.plusDays(1).atStartOfDay(zone).toInstant
      trafficRepo
        .listPresenceRowsInWindow(household, macs, from, to)
        .mapError(ApiError.Db(_))
    }

  // ── #846 Traffic Usage page ───────────────────────────────────────────────
  //
  // GET /api/usage/traffic?from=&to=&bucket=&groupBy=&mac=&profileId=&tz=
  // Returns either raw rows (bucket=raw) or aggregated rows (bucket=10m..1w).
  // 1m/apex/app are rejected with typed errors per #846.
  // ── #813 source-tier routing ──────────────────────────────────────────────
  //
  // #1262: bucket and range are independent client choices. The requested
  // bucket is ALWAYS honored at its width — the range never coarsens it. The
  // range only influences which physical table backs the read, and only ever
  // toward a *finer* source (raw), never a coarser one. Concretely the source
  // is the finer of:
  //   - the bucket's correctness cap: the coarsest table that can still render
  //     the requested width (raw/1m/10m → raw, 1h/12h → hourly, 1d/1w → daily).
  //     Reading anything coarser would lose resolution — that was the old
  //     promotion bug. Shared with the connection-events endpoint via
  //     wifihaven.shared.BucketPolicy so the two can't drift.
  //   - the window's cost preference: a wide window justifies a coarser rollup
  //     so the read touches ~50 rows per host instead of millions.
  // Examples: bucket=10m over 30d → raw (fine, paginated); bucket=1h over a 2h
  // window → raw (fresh); bucket=1d over 90d → daily rollup (cheap).
  // #1971: the tier picker + fetch + aggregate now live in
  // `wifihaven.api.usage.UsageTrafficQuery.aggregate`, shared verbatim with the
  // S4 `trafficUsage` live-edge stream so the two can't drift (SSOT).

  private def trafficHandler(
      req: Request,
      auth: AuthService,
      deviceRepo: DeviceRepo,
      trafficRepo: TrafficReportRepo,
      rollupRepo: RollupRepo,
      profileRepo: ProfileRepo,
      userProfileRepo: UserProfileRepo,
      appRepo: AppRepo,
      clock: Clock,
  ): ZIO[Any, ApiError, Response] =
    for {
      claims <- requireAuth(req, auth)
      bucketS = req.url.queryParam("bucket").getOrElse("raw")
      bucket     <- ZIO
        .fromOption(UsageTraffic.Bucket.parse(bucketS))
        .orElseFail(
          ApiError.BadRequest(s"""{"error":"unknown_bucket","bucket":"$bucketS"}"""),
        )
      // #917: groupBy accepts repeated params (?groupBy=host&groupBy=device).
      // For backwards-compat each value is also comma-split, so the older
      // single-param ?groupBy=host,device form keeps working. Empty/absent =
      // strictly aggregate (one row per window). Apex deferred to #856, App
      // to #857 — both still rejected with typed errors.
      groupBySet <- {
        val raw = req.url.queryParams
          .getAll("groupBy")
          .toList
          .flatMap(_.split(',').toList)
          .map(_.trim)
          .filter(_.nonEmpty)
        ZIO
          .foreach(raw) { s =>
            ZIO
              .fromOption(UsageTraffic.GroupBy.parse(s))
              .orElseFail(
                ApiError.BadRequest(s"""{"error":"unknown_groupBy","groupBy":"$s"}"""),
              )
          }
          .map(_.toSet)
      }
      _          <- ZIO
        .fail(
          ApiError.BadRequest(
            """{"error":"groupBy_not_implemented","groupBy":"apex","reason":"PSL not available — see #856"}""",
          ),
        )
        .when(groupBySet.contains(UsageTraffic.GroupBy.Apex))
      // #769: groupBy=app is now implemented (joins through app_hosts).
      tzStr = req.url.queryParam("tz").getOrElse("UTC")
      zone <- ZIO.attempt(ZoneId.of(tzStr)).orElseFail(ApiError.BadRequest(s"invalid tz: $tzStr"))
      now  <- clock.now
      // Default range: last 24h if not supplied.
      defaultFrom = now.minus(Duration.ofHours(24))
      fromS       = req.url.queryParam("from").getOrElse(defaultFrom.toString)
      toS         = req.url.queryParam("to").getOrElse(now.toString)
      fromI <- ZIO
        .attempt(Instant.parse(fromS))
        .orElseFail(ApiError.BadRequest(s"invalid from: $fromS"))
      toI   <- ZIO
        .attempt(Instant.parse(toS))
        .orElseFail(ApiError.BadRequest(s"invalid to: $toS"))
      _     <- ZIO
        .fail(ApiError.BadRequest("from must be < to"))
        .when(!fromI.isBefore(toI))
      // #862/#809: the prior 31-day window cap is gone. The aggregated path
      // routes coarse buckets to traffic_hourly / traffic_daily (see
      // `tierForBucket` below) so a 90-day, 1d query reads ~50 daily rows per
      // host instead of millions of per-report-period rows. A fine bucket reads raw at any
      // range, with keyset paging keeping its wide-window cost bounded by the
      // page cap.
      // #865: mac and profileId are comma-separated multi-value lists. A
      // single value still works ("mac=aa:bb:cc:dd:ee:01"). Empty/absent =
      // no filter on that column.
      macsRaw = parseMultiValueParam(req, "mac").map(s => MacAddress.unsafe(normalizeMac(s)))
      profileIds <- parseMultiProfileIdParam(req)
      // Retention gating per #814 is not yet wired (rollup tables + horizons endpoint
      // are dependencies). We still expose the 409 contract by emitting it when the
      // window straddles a horizon we DO know about — but until #814, the only
      // signal we have is "no data exists for this range", which is normal for
      // empty households. So: don't 409 here. SPA may downgrade the bucket pick
      // when #814 lands.
      // Resolve mac filter from macs / profileIds / "all visible to admin".
      // When both lists are non-empty, intersect: devices that match any
      // selected mac AND belong to any selected profile.
      allDevices <- deviceRepo.listAllForHousehold(claims.hh).mapError(ApiError.Db(_))
      // #1971: the device-set RESULT is computed by the shared
      // `UsageTrafficQuery.resolveMacs` (one source, also used by the S4 live-edge stream so the two
      // can't drift on filter semantics). This handler keeps the HTTP-only guards around it: a
      // requested mac that doesn't exist is a 404, the selected profiles must pass
      // `requireProfileReadAccess`, and the no-filter ("all devices") set is admin/adult-only.
      // #2708: a `MacScope`, not a `List` — "the filter selected nothing" and "no filter was
      // supplied" are distinct constructors, so the hand-rolled `macs.isEmpty && (macsRaw.nonEmpty
      // || profileIds.nonEmpty)` short-circuits below are gone. A household with ZERO devices is
      // the case those guards missed.
      macScope   <- (macsRaw, profileIds) match {
        case (ms, _) if ms.nonEmpty     =>
          for {
            devs <- ZIO.foreach(ms) { mac =>
              ZIO
                .fromOption(allDevices.find(_.mac == mac))
                .orElseFail(ApiError.NotFound(s"Device not found: ${mac.value}"))
            }
            _    <- ZIO.foreach(devs.flatMap(_.profileId).distinct) { pid =>
              requireProfileReadAccess(claims, Some(pid), userProfileRepo, profileRepo)
            }
          } yield UsageTrafficQuery.resolveMacs(ms, profileIds, allDevices)
        case (_, pids) if pids.nonEmpty =>
          ZIO
            .foreach(pids)(pid =>
              requireProfileReadAccess(claims, Some(pid), userProfileRepo, profileRepo),
            )
            .as(UsageTrafficQuery.resolveMacs(Nil, pids, allDevices))
        case _                          =>
          // No filter: admin/adult only. Children must scope to their profile.
          if (claims.role == "admin" || claims.role == "adult")
            ZIO.succeed(UsageTrafficQuery.resolveMacs(Nil, Nil, allDevices))
          else
            ZIO.fail(
              ApiError.Forbidden("mac or profileId required for non-admin"),
            )
      }
      // #858: zero-bytes-zero-seconds rows are filtered at SQL level in
      // listRawInRange so the application never sees them. #864: they're counted
      // at ingest into traffic_reports_filtered_zero_bytes_total (see
      // RouterIngestRoutes.handleUsage), so a return of the #858 regression is
      // now a metric rather than a per-request log.
      profiles   <- profileRepo.listAllForHousehold(claims.hh).mapError(ApiError.Db(_))
      profNames = profiles.iterator.map(p => p.id -> p.name).toMap
      devByMac  = allDevices.iterator.map(d => d.mac -> d).toMap
      // #769: load (host → app memberships) for app grouping. Only fetched
      // when the request actually drills on app — apps + their host inventory
      // are small (~tens of rows in the typical household), but skipping the
      // round-trip keeps the un-grouped path identical to pre-#769.
      appsByHost <-
        if (groupBySet.contains(UsageTraffic.GroupBy.App))
          (for {
            apps <- appRepo.listAll
            byId = apps.iterator.map(a => a.id -> a).toMap
            mappings <- appRepo.listAllHostMappings
          } yield mappings
            .flatMap { m =>
              byId
                .get(m.appId)
                .map(a => m.host.value -> AppMembership(a.slug, a.name, a.icon, Some(a.id)))
            }
            .groupMap(_._1)(_._2)).mapError(ApiError.Db(_))
        else ZIO.succeed(Map.empty[String, List[AppMembership]])
      // #862: cursor + nextCursor replace the old "single fixed window with
      // rawRowsTruncated flag" UX. `limit` is the per-page cap (200 default,
      // 500 max). For aggregated views the cap applies to GROUP BY output rows.
      rawLimit = req.url
        .queryParam("limit")
        .flatMap(_.toIntOption)
        .getOrElse(if (bucket == UsageTraffic.Bucket.Raw) 200 else 500)
        .max(1)
        .min(500)
      // #917: empty groupBy is intentional — strictly aggregate ("one row per
      // time bucket"). No implicit default.
      effectiveGroupBy = groupBySet
      resp <- bucket match {
        // #2040: `raw` WITHOUT a groupBy is the page's per-host raw inspector → `rawRows`. `raw`
        // WITH a groupBy (e.g. the dashboard gauge's `groupBy=profile`) falls through to the
        // aggregate path below, which rolls each ingest period up per the groupBy at the row's REAL
        // `[periodStart, periodEnd)` window (`buildAggregate` floors `raw` to the row itself, #2018)
        // — one `aggregateRows` point per (period, group), per design §1.3. Without this guard a
        // `raw&groupBy=profile` read returned per-host `rawRows` and empty `aggregateRows`, leaving
        // the gauge (which reads `aggregateRows`) blank.
        case UsageTraffic.Bucket.Raw if effectiveGroupBy.isEmpty =>
          for {
            rawCursor <- req.url.queryParam("cursor") match {
              case None    => ZIO.succeed(Option.empty[RawTrafficCursorKey])
              case Some(s) =>
                ZIO
                  .fromEither(
                    wifihaven.api.db.Cursor.decode[wifihaven.api.db.Cursor.RawTrafficCursor](s),
                  )
                  .mapBoth(
                    ApiError.BadRequest(_),
                    c => Some(RawTrafficCursorKey(c.ts, c.mac, c.host)),
                  )
            }
            pagedRows <- macScope.fold(
              ZIO.succeed(List.empty[wifihaven.api.usage.TrafficUsageDbRow]),
            )(macs =>
              trafficRepo
                .listRawInRange(claims.hh, macs, fromI, toI, rawCursor, Some(rawLimit))
                .mapError(ApiError.Db(_)),
            )
            built   = UsageTraffic.buildRaw(pagedRows, devByMac, profNames)
            nextCur =
              if (pagedRows.size < rawLimit) None
              else
                pagedRows.lastOption.map { r =>
                  wifihaven.api.db.Cursor.encode(
                    wifihaven.api.db.Cursor.RawTrafficCursor(
                      r.periodStart,
                      r.mac.value,
                      r.host.value,
                    ),
                  )
                }
          } yield TrafficUsageResponse(
            bucket = bucket.code,
            groupBy = Nil,
            from = fromI.toString,
            to = toI.toString,
            tz = zone.getId,
            rawRows = built,
            aggregateRows = Nil,
            rawRowLimit = Some(rawLimit),
            rawRowsTruncated = false,
            nextCursor = nextCur,
          )
        case _                                                   =>
          // Aggregated path: source table = finer of (bucket cap, window cost
          // preference) (#1262). The requested bucket is always honored; the
          // range can only steer the read toward finer/fresher data, never
          // coarsen the bucket. #1971: the fetch+tier+aggregate core is
          // `UsageTrafficQuery.aggregate`, shared verbatim with the S4
          // `trafficUsage` live-edge stream (SSOT — the stream and this GET
          // can't disagree). The keyset cursor paging stays here on top.
          for {
            allAgg    <- UsageTrafficQuery
              .aggregate(
                claims.hh,
                trafficRepo,
                rollupRepo,
                macScope,
                fromI,
                toI,
                bucket,
                effectiveGroupBy,
                zone,
                devByMac,
                profNames,
                appsByHost,
              )
              .mapError(ApiError.Db(_))
            cursorOpt <- req.url.queryParam("cursor") match {
              case None    => ZIO.succeed(Option.empty[wifihaven.api.db.Cursor.AggCursor])
              case Some(s) =>
                ZIO
                  .fromEither(
                    wifihaven.api.db.Cursor.decode[wifihaven.api.db.Cursor.AggCursor](s),
                  )
                  .mapBoth(ApiError.BadRequest(_), Some(_))
            }
            keyOf = (r: TrafficUsageAggregateRow) => UsageTraffic.aggGroupKey(r, effectiveGroupBy)
            sorted   = allAgg.sortBy(r =>
              (-java.time.Instant.parse(r.windowStart).toEpochMilli, keyOf(r)),
            )
            filtered = cursorOpt match {
              case None    => sorted
              case Some(c) =>
                sorted.filter { r =>
                  val cmp = r.windowStart.compare(c.ws)
                  cmp < 0 || (cmp == 0 && keyOf(r).compare(c.key) > 0)
                }
            }
            page     = filtered.take(rawLimit)
            nextCur  =
              if (page.size < rawLimit) None
              else
                page.lastOption.map { r =>
                  wifihaven.api.db.Cursor.encode(
                    wifihaven.api.db.Cursor.AggCursor(r.windowStart, keyOf(r)),
                  )
                }
          } yield TrafficUsageResponse(
            bucket = bucket.code,
            groupBy = effectiveGroupBy.toList.map(_.code).sorted,
            from = fromI.toString,
            to = toI.toString,
            tz = zone.getId,
            rawRows = Nil,
            aggregateRows = page,
            nextCursor = nextCur,
          )
      }
    } yield Response.json(resp.toJson)
}
