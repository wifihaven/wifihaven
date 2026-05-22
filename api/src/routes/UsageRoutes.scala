package wifihaven.api.routes

import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.usage.{UsageSeries, UsageTraffic}
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
      clock: Clock,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "usage" / "traffic" ->
        handler { (req: Request) =>
          trafficHandler(req, auth, deviceRepo, trafficRepo, profileRepo, userProfileRepo, clock)
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
              .min(20)
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
        UsageSeries.buildProfile(rows, nameByMac, zone, topN)
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
      groupByOpt <- ZIO
        .foreach(req.url.queryParam("groupBy")) { s =>
          ZIO
            .fromOption(UsageTraffic.GroupBy.parse(s))
            .orElseFail(Response.badRequest(s"""{"error":"unknown_groupBy","groupBy":"$s"}"""))
        }
      _          <- groupByOpt match {
        case Some(UsageTraffic.GroupBy.Apex) =>
          ZIO.fail(
            Response.badRequest(
              """{"error":"groupBy_not_implemented","groupBy":"apex","reason":"PSL not available"}""",
            ),
          )
        case Some(UsageTraffic.GroupBy.App)  =>
          ZIO.fail(
            Response.badRequest(
              """{"error":"groupBy_not_implemented","groupBy":"app","reason":"apps track not implemented"}""",
            ),
          )
        case _                               => ZIO.succeed(())
      }
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
      _     <- ZIO
        .fail(
          Response(
            status = Status.ServiceUnavailable,
            body = Body.fromString(
              """{"error":"window_too_large","reason":"on-the-fly aggregation cap is 31 days until rollup tables (#809) land"}""",
            ),
          ),
        )
        .when(Duration.between(fromI, toI).compareTo(UsageTraffic.maxOnTheFlyDuration) > 0)
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
      // Retention gating per #814 is not yet wired (rollup tables + horizons endpoint
      // are dependencies). We still expose the 409 contract by emitting it when the
      // window straddles a horizon we DO know about — but until #814, the only
      // signal we have is "no data exists for this range", which is normal for
      // empty households. So: don't 409 here. SPA may downgrade the bucket pick
      // when #814 lands.
      // Resolve mac filter from mac / profileId / "all visible to admin".
      allDevices   <- deviceRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
      macs         <- (macOpt, profileIdOpt) match {
        case (Some(mac), _) =>
          // Require profile read access via the device's profile.
          for {
            dev <- ZIO
              .fromOption(allDevices.find(_.mac == mac))
              .orElseFail(Response.notFound("Device not found"))
            _   <- requireProfileReadAccess(claims, dev.profileId, userProfileRepo)
          } yield List(mac)
        case (_, Some(pid)) =>
          for {
            _ <- requireProfileReadAccess(claims, pid, userProfileRepo)
          } yield allDevices.filter(_.profileId.contains(pid)).map(_.mac)
        case _              =>
          // No filter: admin/adult only. Children must scope to their profile.
          if (claims.role == "admin" || claims.role == "adult")
            ZIO.succeed(allDevices.map(_.mac))
          else
            ZIO.fail(
              Response.forbidden("mac or profileId required for non-admin"),
            )
      }
      rows         <-
        if (macs.isEmpty && (macOpt.isDefined || profileIdOpt.isDefined))
          ZIO.succeed(List.empty[wifihaven.api.usage.TrafficUsageDbRow])
        else
          trafficRepo
            .listRawInRange(macs, fromI, toI)
            .mapError(ErrorMapper.dbErrorToResponse)
      profiles     <- profileRepo.listAll.mapError(ErrorMapper.dbErrorToResponse)
      profNames = profiles.iterator.map(p => p.id -> p.name).toMap
      devByMac  = allDevices.iterator.map(d => d.mac -> d).toMap
      resp      = bucket match {
        case UsageTraffic.Bucket.Raw =>
          TrafficUsageResponse(
            bucket = bucket.code,
            groupBy = None,
            from = fromI.toString,
            to = toI.toString,
            tz = zone.getId,
            rawRows = UsageTraffic.buildRaw(rows, devByMac, profNames),
            aggregateRows = Nil,
          )
        case _                       =>
          val gb = groupByOpt.getOrElse(UsageTraffic.GroupBy.Domain)
          TrafficUsageResponse(
            bucket = bucket.code,
            groupBy = Some(gb.code),
            from = fromI.toString,
            to = toI.toString,
            tz = zone.getId,
            rawRows = Nil,
            aggregateRows = UsageTraffic.buildAggregate(rows, bucket, zone),
          )
      }
    } yield Response.json(resp.toJson)
}
