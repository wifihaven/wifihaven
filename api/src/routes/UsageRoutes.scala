package wifihaven.api.routes

import wifihaven.api.auth.*
import wifihaven.api.db.*
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
      clock: Clock,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "usage" / "traffic" ->
        handler { (req: Request) =>
          trafficHandler(
            req,
            auth,
            deviceRepo,
            trafficRepo,
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
            mac     = MacAddress.unsafe(normalizeMac(macRaw))
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
            now  <- clock.instant
            from  = now.minus(Duration.ofDays(windowDays.toLong))
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
            resp = RecentApexesResponse(
              deviceMac = mac,
              deviceName = device.name,
              windowDays = windowDays,
              items = grouped,
            )
          } yield Response.json(resp.toJson)
        },
      Method.GET / "api" / "usage" / "series"  ->
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
            topN = req.url
              .queryParam("topN")
              .flatMap(_.toIntOption)
              .getOrElse(5)
              .max(1)
              // #964: cap bumped from 20 → 500 so the per-device 'other'
              // drill-in can request the full long-tail of hosts in one shot.
              .min(500)
            resp <- (macOpt, profileIdOpt) match {
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
                )
              case _              => ZIO.fail(Response.badRequest("unreachable"))
            }
          } yield Response.json(resp.toJson)
        },
    )

  private def buildForDevice(
      mac: MacAddress,
      date: LocalDate,
      zone: ZoneId,
      topN: Int,
      claims: JwtClaims,
      deviceRepo: DeviceRepo,
      trafficRepo: TrafficReportRepo,
      userProfileRepo: UserProfileRepo,
  ): IO[Response, UsageSeriesResponse] =
    for {
      device <- deviceRepo
        .findByMac(mac)
        .mapError(ErrorMapper.dbErrorToResponse)
        .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("Device not found")))
      _      <- requireProfileReadAccess(claims, device.profileId, userProfileRepo)
      rows   <- fetchPresenceDayWindow(trafficRepo, List(mac), date, zone)
      (topHosts, buckets) = UsageSeries.build(rows, zone, topN)
    } yield UsageSeriesResponse(
      deviceMac = Some(mac),
      deviceName = Some(device.name),
      date = date.toString,
      tz = zone.getId,
      topHosts = topHosts,
      buckets = buckets,
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
  ): IO[Response, UsageSeriesResponse] =
    for {
      _       <- requireProfileReadAccess(claims, pid, userProfileRepo)
      profile <- profileRepo
        .findById(pid)
        .mapError(ErrorMapper.dbErrorToResponse)
        .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("Profile not found")))
      all     <- deviceRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
      devices   = all.filter(_.profileId.contains(pid))
      macs      = devices.map(_.mac)
      nameByMac = devices.iterator.map(d => d.mac -> d.name).toMap
      rows <- fetchPresenceDayWindow(trafficRepo, macs, date, zone)
      (topHosts, bucketsByHost, topDevices, bucketsByDevice) =
        UsageSeries.buildProfile(rows, nameByMac, zone, topN, profile.crossDeviceOverlapMode)
    } yield UsageSeriesResponse(
      profileId = Some(pid),
      profileName = Some(profile.name),
      date = date.toString,
      tz = zone.getId,
      topHosts = topHosts,
      buckets = bucketsByHost,
      topDevices = topDevices,
      bucketsByDevice = bucketsByDevice,
    )

  // Pull the requested local-day window. Two adjacent UTC days bracket every
  // calendar day in every IANA zone, then filter by `periodStart` in `zone`.
  private def fetchPresenceDayWindow(
      trafficRepo: TrafficReportRepo,
      macs: List[MacAddress],
      date: LocalDate,
      zone: ZoneId,
  ): IO[Response, List[wifihaven.api.presence.PresenceRow]] =
    if (macs.isEmpty) ZIO.succeed(Nil)
    else
      for {
        d   <- trafficRepo.listPresenceRows(macs, date).mapError(ErrorMapper.dbErrorToResponse)
        nxt <- trafficRepo
          .listPresenceRows(macs, date.plusDays(1))
          .mapError(ErrorMapper.dbErrorToResponse)
        prv <- trafficRepo
          .listPresenceRows(macs, date.minusDays(1))
          .mapError(ErrorMapper.dbErrorToResponse)
      } yield (prv ++ d ++ nxt).filter { r => r.periodStart.atZone(zone).toLocalDate == date }

  // ── #846 Traffic Usage page ───────────────────────────────────────────────
  //
  // GET /api/usage/traffic?from=&to=&bucket=&groupBy=&mac=&profileId=&tz=
  // Returns either raw rows (bucket=raw) or aggregated rows (bucket=10m..1w).
  // 1m/apex/app are rejected with typed errors per #846.
  private def trafficHandler(
      req: Request,
      auth: AuthService,
      deviceRepo: DeviceRepo,
      trafficRepo: TrafficReportRepo,
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
      _          <- ZIO
        .fail(
          Response
            .badRequest(
              """{"error":"bucket_not_implemented","bucket":"1m","reason":"requires faster router upload cadence"}""",
            ),
        )
        .when(bucket == UsageTraffic.Bucket.OneMin)
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
      // #862: the 31-day cap only applies to aggregated views — those still
      // read the whole [from, to) band into memory for in-app bucketing. The
      // raw view pushes (period_start, mac, host) keyset paging + LIMIT into
      // SQL, so a wide band is bounded by the per-page row cap.
      bucketStrEarly = req.url.queryParam("bucket").getOrElse("raw")
      _ <- ZIO
        .fail(
          Response(
            status = Status.ServiceUnavailable,
            body = Body.fromString(
              """{"error":"window_too_large","reason":"on-the-fly aggregation cap is 31 days until rollup tables (#809) land"}""",
            ),
          ),
        )
        .when(
          bucketStrEarly != "raw" &&
            Duration.between(fromI, toI).compareTo(UsageTraffic.maxOnTheFlyDuration) > 0,
        )
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
      // listRawInRange so the application never sees them. TODO(#864) wire
      // a metric for "rows filtered" once observability lands.
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
          // Aggregated path: still reads the whole raw band into memory,
          // buckets in-app, then slices by cursor. Cheap because window*group
          // cardinality is small. Rollup tables (#809) will replace this with
          // a paged SQL fetch.
          for {
            rows      <-
              if (macs.isEmpty && (macsRaw.nonEmpty || profileIds.nonEmpty))
                ZIO.succeed(List.empty[wifihaven.api.usage.TrafficUsageDbRow])
              else
                trafficRepo
                  .listRawInRange(macs, fromI, toI)
                  .mapError(ErrorMapper.dbErrorToResponse)
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
