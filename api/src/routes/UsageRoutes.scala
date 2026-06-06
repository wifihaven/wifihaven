package wifihaven.api.routes

import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.db.{RollupRepo, RollupRow}
import wifihaven.api.usage.{AppMembership, RawTrafficCursorKey, UsageSeries, UsageTraffic}
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
      siteTimeLimitRepo: SiteTimeLimitRepo,
      clock: Clock,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "usage" / "traffic"                         ->
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
          )
        },
      // #766: recently-visited FQDN apexes for one device, used by the
      // apps create/edit "Pick from recent activity" picker.
      Method.GET / "api" / "devices" / string("mac") / "recent-apexes" ->
        handler { (macRaw: String, req: Request) =>
          for {
            claims <- requireAuth(req, auth)
            mac = MacAddress.unsafe(normalizeMac(macRaw))
            device <- deviceRepo
              .findByMac(mac)
              .mapError(ErrorMapper.dbErrorToResponse)
              .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("Device not found")))
            _      <- requireProfileReadAccess(claims, device.profileId, userProfileRepo)
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
              .listFqdnHostAggregatesForDevice(mac, from, now)
              .mapError(ErrorMapper.dbErrorToResponse)
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
        },
      // #1061 — per-app time-used breakdown for one profile over [from,to].
      // Read-only companion to #767's rules editor; powers the per-app subsection
      // on the expanded /profiles card. Joins through `app_hosts` and folds rows
      // whose host isn't in any app into a synthetic `appId=null` ("Other")
      // bucket. Sorted by proportional seconds desc.
      Method.GET / "api" / "profiles" / long("id") / "usage-by-app"    ->
        handler { (id: Long, req: Request) =>
          val pid = ProfileId(id)
          for {
            claims <- requireAuth(req, auth)
            _      <- requireProfileReadAccess(claims, pid, userProfileRepo)
            today  <- clock.today
            fromS = req.url.queryParam("from").getOrElse(today.toString)
            toS   = req.url.queryParam("to").getOrElse(fromS)
            from     <- ZIO
              .attempt(LocalDate.parse(fromS))
              .orElseFail(Response.badRequest(s"invalid from: $fromS"))
            to       <- ZIO
              .attempt(LocalDate.parse(toS))
              .orElseFail(Response.badRequest(s"invalid to: $toS"))
            _        <- ZIO
              .fail(Response.badRequest("from must be <= to"))
              .when(from.isAfter(to))
            settings <- hsRepo.get.mapError(ErrorMapper.dbErrorToResponse)
            resp     <- buildUsageByApp(
              pid,
              from,
              to,
              profileRepo,
              deviceRepo,
              trafficRepo,
              appRepo,
              settings.heartbeatFilter,
              settings.presenceContinuationSeconds,
            )
          } yield Response.json(resp.toJson)
        },
      Method.GET / "api" / "usage" / "series"                          ->
        handler { (req: Request) =>
          for {
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
              .mapError(Response.badRequest)
            _            <- ZIO
              .fail(Response.badRequest("exactly one of mac or profileId is required"))
              .when(macOpt.isDefined == profileIdOpt.isDefined)
            dateStr = req.url.queryParam("date").getOrElse(today.toString)
            date <- ZIO
              .attempt(LocalDate.parse(dateStr))
              .orElseFail(Response.badRequest(s"invalid date: $dateStr"))
            tzStr = req.url.queryParam("tz").getOrElse("UTC")
            zone <- ZIO
              .attempt(ZoneId.of(tzStr))
              .orElseFail(Response.badRequest(s"invalid tz: $tzStr"))
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
              else ZIO.succeed((_: HostId) => Option.empty[UsageSeries.AppInfo])
            settings  <- hsRepo.get.mapError(ErrorMapper.dbErrorToResponse)
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
                  groupByApp,
                  appLookup,
                  settings,
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
                  siteTimeLimitRepo,
                  groupByApp,
                  appLookup,
                  settings,
                )
              case _              => ZIO.fail(Response.badRequest("unreachable"))
            }
          } yield Response.json(resp.toJson)
        },
      // #1099: batched per-profile series. The /profiles page resolves the
      // whole visible profile set in one partition-pruned scan instead of N
      // parallel single-profile requests.
      Method.GET / "api" / "usage" / "series" / "batch"                ->
        handler { (req: Request) =>
          for {
            claims <- requireAuth(req, auth)
            today  <- clock.today
            pids   <- parseMultiProfileIdParam(req).map(_.distinct)
            dateStr = req.url.queryParam("date").getOrElse(today.toString)
            date <- ZIO
              .attempt(LocalDate.parse(dateStr))
              .orElseFail(Response.badRequest(s"invalid date: $dateStr"))
            tzStr = req.url.queryParam("tz").getOrElse("UTC")
            zone <- ZIO
              .attempt(ZoneId.of(tzStr))
              .orElseFail(Response.badRequest(s"invalid tz: $tzStr"))
            topN       = req.url
              .queryParam("topN")
              .flatMap(_.toIntOption)
              .getOrElse(5)
              .max(1)
              .min(500)
            groupByApp = req.url.queryParam("groupBy").exists(_.split(',').contains("app"))
            appLookup <-
              if (groupByApp) loadAppLookup(appRepo)
              else ZIO.succeed((_: HostId) => Option.empty[UsageSeries.AppInfo])
            settings  <- hsRepo.get.mapError(ErrorMapper.dbErrorToResponse)
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
              siteTimeLimitRepo,
              groupByApp,
              appLookup,
              settings,
            )
          } yield Response.json(resp.toJson)
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
      siteTimeLimitRepo: SiteTimeLimitRepo,
      groupByApp: Boolean,
      appLookup: HostId => Option[UsageSeries.AppInfo],
      settings: HouseholdSettings,
  ): IO[Response, UsageSeriesBatchResponse] =
    for {
      _ <- ZIO.foreachDiscard(pids)(pid => requireProfileReadAccess(claims, pid, userProfileRepo))
      profiles    <- ZIO.foreach(pids) { pid =>
        profileRepo
          .findById(pid)
          .mapError(ErrorMapper.dbErrorToResponse)
          .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("Profile not found")))
      }
      allDevices  <- deviceRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
      // #1492: exempt-from-daily site patterns must match the headline daily total exactly, so
      // load them per profile (same input `usedSecondsForProfile` uses) and carve them out.
      exemptByPid <- ZIO
        .foreach(pids) { pid =>
          siteTimeLimitRepo
            .listForProfile(pid)
            .map(sl => pid -> sl.filter(_.exemptFromDaily).map(_.domainPattern))
        }
        .map(_.toMap)
        .mapError(ErrorMapper.dbErrorToResponse)
      devicesByPid = pids.iterator
        .map(pid => pid -> allDevices.filter(_.profileId.contains(pid)))
        .toMap
      allMacs = devicesByPid.valuesIterator.flatten.map(_.mac).toList.distinct
      rows        <- fetchPresenceDayWindow(trafficRepo, allMacs, date, zone)
    } yield {
      val series = profiles.map { profile =>
        val devices   = devicesByPid.getOrElse(profile.id, Nil)
        val macSet    = devices.iterator.map(_.mac).toSet
        val nameByMac = devices.iterator.map(d => d.mac -> d.name).toMap
        val pRows     = rows.filter(r => macSet.contains(r.mac))
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
  // Hosts not in any app land in the synthetic `appId=null` "Other" bucket.
  private def buildUsageByApp(
      pid: ProfileId,
      from: LocalDate,
      to: LocalDate,
      profileRepo: ProfileRepo,
      deviceRepo: DeviceRepo,
      trafficRepo: TrafficReportRepo,
      appRepo: AppRepo,
      filter: HeartbeatFilter,
      continuationSeconds: Int,
  ): IO[Response, ProfileUsageByApp] =
    for {
      profile <- profileRepo
        .findById(pid)
        .mapError(ErrorMapper.dbErrorToResponse)
        .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("Profile not found")))
      allDevs <- deviceRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
      macs = allDevs.collect { case d if d.profileId.contains(pid) => d.mac }
      presence <- (if (macs.isEmpty) ZIO.succeed(Nil)
                   else trafficRepo.listPresenceRows(macs, from, to))
        .mapError(ErrorMapper.dbErrorToResponse)
      appList  <- appRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
      mappings <- appRepo.listAllHostMappings.mapError(ErrorMapper.dbErrorToResponse)
    } yield {
      val appById                            = appList.iterator.map(a => a.id -> a).toMap
      // Deterministic host → owning-app: lowest appId wins when a host is in
      // multiple apps. Avoids double-counting one bucket of activity into two
      // apps for the per-profile screen-time view (matches #1061 acceptance).
      //
      // #1161: app_hosts rows are stored apex-form (`youtube.com`) but traffic
      // rows carry FQDNs (`m.youtube.com`). Use suffix-aware lookup — same
      // matcher as #1085's groupBy=app fix and PolicyService — so subdomain
      // traffic attributes to the apex-form app entry instead of falling into
      // the synthetic "Other" bucket.
      val appOfHost: HostId => Option[AppId] = {
        val byApex = mappings
          .groupBy(_.host.value)
          .view
          .mapValues(ms => ms.iterator.map(_.appId).minBy(_.value))
          .toMap
        h => h.asFqdn.flatMap(fqdn => HostMatch.lookupApex(fqdn.value, byApex))
      }

      val overlap       = profile.crossDeviceOverlapMode
      // #1465: per-host presence is now the session-stitch span (heartbeat-filtered),
      // combined across the profile's devices by its `crossDeviceOverlapMode`.
      val propByHost    =
        wifihaven.api.presence.Presence
          .proportionalHostSeconds(presence, overlap, filter, continuationSeconds)
      val seenByHost    = wifihaven.api.presence.Presence.hostMinutes(presence, filter)
      val propMinByHost =
        wifihaven.api.presence.Presence
          .proportionalHostMinutes(presence, overlap, filter, continuationSeconds)

      val appProp    = scala.collection.mutable.Map.empty[Option[AppId], Long]
      val hostsByApp =
        scala.collection.mutable.Map.empty[Option[AppId], List[HostUsage]]
      for ((h, secs) <- propByHost) {
        val key = appOfHost(h)
        appProp.updateWith(key)(prev => Some(prev.getOrElse(0L) + secs))
        val hu  = HostUsage(h, seenByHost.getOrElse(h, 0), propMinByHost.getOrElse(h, 0))
        hostsByApp.updateWith(key)(prev => Some(hu :: prev.getOrElse(Nil)))
      }
      // Per-app bucket-dedup for presence-seconds (heartbeat rows stripped, #1465).
      val appPresence = scala.collection.mutable.Map.empty[Option[AppId], Long]
      val activeRows =
        presence.filterNot(r => wifihaven.api.presence.Presence.isHeartbeat(r, filter))
      for ((_, bucket) <- activeRows.groupBy(r => (r.mac, r.periodStart))) {
        val secs = bucket.iterator.map(_.activeSeconds.toLong).maxOption.getOrElse(0L)
        val keys = bucket.iterator.map(r => appOfHost(r.host)).toSet
        for (a <- keys)
          appPresence.updateWith(a)(prev => Some(prev.getOrElse(0L) + secs))
      }

      val allKeys = (appProp.keySet ++ appPresence.keySet).toList
      val rows    = allKeys.map { key =>
        val name  = key.flatMap(appById.get).map(_.name).getOrElse("Other")
        val icon  = key.flatMap(appById.get).flatMap(_.icon)
        val it    = key.flatMap(appById.get).map(_.iconType)
        val hosts = hostsByApp
          .getOrElse(key, Nil)
          .sortBy(hu => (-hu.proportionalMins, -hu.usedMins, hu.host.value))
        ProfileAppUsage(
          appId = key,
          appName = name,
          appIcon = icon,
          appIconType = it,
          proportionalSeconds = appProp.getOrElse(key, 0L),
          presenceSeconds = appPresence.getOrElse(key, 0L),
          hosts = hosts,
        )
      }
      ProfileUsageByApp(
        profileId = pid,
        profileName = profile.name,
        from = from.toString,
        to = to.toString,
        apps = rows.sortBy(r => (-r.proportionalSeconds, -r.presenceSeconds, r.appName)),
      )
    }

  // #1079 — build the host → owning-app lookup used by the unified-axis
  // builder. Mirrors the deterministic "lowest appId wins" tiebreak from
  // #1061 and the apex-aware match from #1085 so subdomain traffic attributes
  // to the apex-form app entry.
  private def loadAppLookup(
      appRepo: AppRepo,
  ): IO[Response, HostId => Option[UsageSeries.AppInfo]] =
    for {
      apps     <- appRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
      mappings <- appRepo.listAllHostMappings.mapError(ErrorMapper.dbErrorToResponse)
    } yield {
      val appById = apps.iterator.map(a => a.id -> a).toMap
      val byApex  = mappings
        .groupBy(_.host.value)
        .view
        .mapValues(ms => ms.iterator.map(_.appId).minBy(_.value))
        .toMap
      (h: HostId) =>
        h.asFqdn
          .flatMap(fqdn => HostMatch.lookupApex(fqdn.value, byApex))
          .flatMap(appById.get)
          .map(a => UsageSeries.AppInfo(a.id, a.slug, a.name, a.icon))
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
      groupByApp: Boolean,
      appLookup: HostId => Option[UsageSeries.AppInfo],
      settings: HouseholdSettings,
  ): IO[Response, UsageSeriesResponse] =
    for {
      device <- deviceRepo
        .findByMac(mac)
        .mapError(ErrorMapper.dbErrorToResponse)
        .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("Device not found")))
      _      <- requireProfileReadAccess(claims, device.profileId, userProfileRepo)
      rows   <- fetchPresenceDayWindow(trafficRepo, List(mac), date, zone)
      (topHosts, buckets, presenceTotalMins) =
        UsageSeries.build(
          rows,
          date,
          zone,
          topN,
          settings.heartbeatFilter,
          settings.presenceContinuationSeconds,
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
      siteTimeLimitRepo: SiteTimeLimitRepo,
      groupByApp: Boolean,
      appLookup: HostId => Option[UsageSeries.AppInfo],
      settings: HouseholdSettings,
  ): IO[Response, UsageSeriesResponse] =
    for {
      _          <- requireProfileReadAccess(claims, pid, userProfileRepo)
      profile    <- profileRepo
        .findById(pid)
        .mapError(ErrorMapper.dbErrorToResponse)
        .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("Profile not found")))
      all        <- deviceRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
      siteLimits <- siteTimeLimitRepo.listForProfile(pid).mapError(ErrorMapper.dbErrorToResponse)
      exempt    = siteLimits.filter(_.exemptFromDaily).map(_.domainPattern)
      devices   = all.filter(_.profileId.contains(pid))
      macs      = devices.map(_.mac)
      nameByMac = devices.iterator.map(d => d.mac -> d.name).toMap
      rows <- fetchPresenceDayWindow(trafficRepo, macs, date, zone)
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
      trafficRepo: TrafficReportRepo,
      macs: List[MacAddress],
      date: LocalDate,
      zone: ZoneId,
  ): IO[Response, List[wifihaven.api.presence.PresenceRow]] =
    if (macs.isEmpty) ZIO.succeed(Nil)
    else {
      val from = date.atStartOfDay(zone).toInstant
      val to   = date.plusDays(1).atStartOfDay(zone).toInstant
      trafficRepo
        .listPresenceRowsInWindow(macs, from, to)
        .mapError(ErrorMapper.dbErrorToResponse)
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
  private enum SourceTier {
    case Raw, Hourly, Daily
  }

  private def coarseness(t: SourceTier): Int = t match {
    case SourceTier.Raw    => 0
    case SourceTier.Hourly => 1
    case SourceTier.Daily  => 2
  }

  // Coarsest table that can render `b` without losing resolution (the cap).
  private def bucketTier(b: UsageTraffic.Bucket): SourceTier =
    BucketPolicy.grainForBucket(b.code) match {
      case BucketGrain.Raw    => SourceTier.Raw
      case BucketGrain.Hourly => SourceTier.Hourly
      case BucketGrain.Daily  => SourceTier.Daily
    }

  // Coarseness the window justifies on cost grounds — only the *cost* input to
  // `pickTier`; it never coarsens the bucket itself.
  private def windowTier(window: java.time.Duration): SourceTier = {
    val hours = window.toHours
    if (hours <= 24) SourceTier.Raw
    else if (hours <= 14 * 24) SourceTier.Hourly
    else SourceTier.Daily
  }

  private def pickTier(b: UsageTraffic.Bucket, window: java.time.Duration): SourceTier = {
    val cap  = bucketTier(b)
    val pref = windowTier(window)
    if (coarseness(pref) <= coarseness(cap)) pref else cap
  }

  // Convert rollup rows back into the shape buildAggregate consumes. The
  // hostname is already post-resolved by the rollup writer, so no extra
  // LATERAL join is needed at read time — this is the whole point of the
  // rollup tables.
  private def asDbRows(rows: List[RollupRow]): List[wifihaven.api.usage.TrafficUsageDbRow] =
    rows.map { r =>
      wifihaven.api.usage.TrafficUsageDbRow(
        mac = r.mac,
        host = r.host,
        periodStart = r.bucketStart,
        periodEnd = r.bucketEnd,
        activeSeconds = r.activeSeconds,
        bytesIn = r.bytesIn,
        bytesOut = r.bytesOut,
      )
    }

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
  ): ZIO[Any, Response, Response] =
    for {
      claims <- requireAuth(req, auth)
      bucketS = req.url.queryParam("bucket").getOrElse("raw")
      bucket     <- ZIO
        .fromOption(UsageTraffic.Bucket.parse(bucketS))
        .orElseFail(
          Response.badRequest(s"""{"error":"unknown_bucket","bucket":"$bucketS"}"""),
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
                Response.badRequest(s"""{"error":"unknown_groupBy","groupBy":"$s"}"""),
              )
          }
          .map(_.toSet)
      }
      _          <- ZIO
        .fail(
          Response.badRequest(
            """{"error":"groupBy_not_implemented","groupBy":"apex","reason":"PSL not available — see #856"}""",
          ),
        )
        .when(groupBySet.contains(UsageTraffic.GroupBy.Apex))
      // #769: groupBy=app is now implemented (joins through app_hosts).
      tzStr = req.url.queryParam("tz").getOrElse("UTC")
      zone <- ZIO.attempt(ZoneId.of(tzStr)).orElseFail(Response.badRequest(s"invalid tz: $tzStr"))
      now  <- clock.now
      // Default range: last 24h if not supplied.
      defaultFrom = now.minus(Duration.ofHours(24))
      fromS       = req.url.queryParam("from").getOrElse(defaultFrom.toString)
      toS         = req.url.queryParam("to").getOrElse(now.toString)
      fromI <- ZIO
        .attempt(Instant.parse(fromS))
        .orElseFail(Response.badRequest(s"invalid from: $fromS"))
      toI   <- ZIO
        .attempt(Instant.parse(toS))
        .orElseFail(Response.badRequest(s"invalid to: $toS"))
      _     <- ZIO
        .fail(Response.badRequest("from must be < to"))
        .when(!fromI.isBefore(toI))
      // #862/#809: the prior 31-day window cap is gone. The aggregated path
      // routes coarse buckets to traffic_hourly / traffic_daily (see
      // `tierForBucket` below) so a 90-day, 1d query reads ~50 daily rows per
      // host instead of millions of 5-min rows. A fine bucket reads raw at any
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
      allDevices <- deviceRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
      macs       <- (macsRaw, profileIds) match {
        case (ms, _) if ms.nonEmpty     =>
          for {
            devs <- ZIO.foreach(ms) { mac =>
              ZIO
                .fromOption(allDevices.find(_.mac == mac))
                .orElseFail(Response.notFound(s"Device not found: ${mac.value}"))
            }
            _    <- ZIO.foreach(devs.flatMap(_.profileId).distinct) { pid =>
              requireProfileReadAccess(claims, Some(pid), userProfileRepo)
            }
          } yield
            if (profileIds.isEmpty) devs.map(_.mac)
            else devs.filter(d => d.profileId.exists(profileIds.contains)).map(_.mac)
        case (_, pids) if pids.nonEmpty =>
          for {
            _ <- ZIO.foreach(pids)(pid =>
              requireProfileReadAccess(claims, Some(pid), userProfileRepo),
            )
          } yield allDevices.filter(d => d.profileId.exists(pids.contains)).map(_.mac)
        case _                          =>
          // No filter: admin/adult only. Children must scope to their profile.
          if (claims.role == "admin" || claims.role == "adult")
            ZIO.succeed(allDevices.map(_.mac))
          else
            ZIO.fail(
              Response.forbidden("mac or profileId required for non-admin"),
            )
      }
      // #858: zero-bytes-zero-seconds rows are filtered at SQL level in
      // listRawInRange so the application never sees them. #864: they're counted
      // at ingest into traffic_reports_filtered_zero_bytes_total (see
      // RouterIngestRoutes.handleUsage), so a return of the #858 regression is
      // now a metric rather than a per-request log.
      profiles   <- profileRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
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
            .groupMap(_._1)(_._2)).mapError(ErrorMapper.dbErrorToResponse)
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
        case UsageTraffic.Bucket.Raw =>
          for {
            rawCursor <- req.url.queryParam("cursor") match {
              case None    => ZIO.succeed(Option.empty[RawTrafficCursorKey])
              case Some(s) =>
                ZIO
                  .fromEither(
                    wifihaven.api.db.Cursor.decode[wifihaven.api.db.Cursor.RawTrafficCursor](s),
                  )
                  .mapBoth(
                    Response.badRequest,
                    c => Some(RawTrafficCursorKey(c.ts, c.mac, c.host)),
                  )
            }
            pagedRows <-
              if (macs.isEmpty && (macsRaw.nonEmpty || profileIds.nonEmpty))
                ZIO.succeed(List.empty[wifihaven.api.usage.TrafficUsageDbRow])
              else
                trafficRepo
                  .listRawInRange(macs, fromI, toI, rawCursor, Some(rawLimit))
                  .mapError(ErrorMapper.dbErrorToResponse)
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
        case _                       =>
          // Aggregated path: source table = finer of (bucket cap, window cost
          // preference) (#1262). The requested bucket is always honored; the
          // range can only steer the read toward finer/fresher data, never
          // coarsen the bucket.
          val tier = pickTier(bucket, Duration.between(fromI, toI))
          for {
            rows      <-
              if (macs.isEmpty && (macsRaw.nonEmpty || profileIds.nonEmpty))
                ZIO.succeed(List.empty[wifihaven.api.usage.TrafficUsageDbRow])
              else
                tier match {
                  case SourceTier.Raw    =>
                    trafficRepo
                      .listRawInRange(macs, fromI, toI)
                      .mapError(ErrorMapper.dbErrorToResponse)
                  case SourceTier.Hourly =>
                    rollupRepo
                      .listHourlyInRange(macs, fromI, toI)
                      .map(asDbRows)
                      .mapError(ErrorMapper.dbErrorToResponse)
                  case SourceTier.Daily  =>
                    rollupRepo
                      .listDailyInRange(macs, fromI, toI)
                      .map(asDbRows)
                      .mapError(ErrorMapper.dbErrorToResponse)
                }
            cursorOpt <- req.url.queryParam("cursor") match {
              case None    => ZIO.succeed(Option.empty[wifihaven.api.db.Cursor.AggCursor])
              case Some(s) =>
                ZIO
                  .fromEither(
                    wifihaven.api.db.Cursor.decode[wifihaven.api.db.Cursor.AggCursor](s),
                  )
                  .mapBoth(Response.badRequest, Some(_))
            }
            allAgg = UsageTraffic
              .buildAggregate(
                rows,
                bucket,
                zone,
                effectiveGroupBy,
                devByMac,
                profNames,
                appsByHost,
              )
            keyOf  = (r: TrafficUsageAggregateRow) => UsageTraffic.aggGroupKey(r, effectiveGroupBy)
            sorted = allAgg.sortBy(r =>
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
