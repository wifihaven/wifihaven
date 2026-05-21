package wifihaven.api.routes

import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.usage.UsageSeries
import wifihaven.shared.*
import wifihaven.shared.types.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*

import java.time.{LocalDate, ZoneId}

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
      Method.GET / "api" / "usage" / "series" ->
        handler { (req: Request) =>
          for {
            claims <- requireAuth(req, auth)
            today  <- clock.today
            macOpt        = req.url.queryParam("mac").map(s => MacAddress.unsafe(normalizeMac(s)))
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
            _      <- ZIO
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
              case (Some(mac), _) => buildForDevice(mac, date, zone, topN, claims, deviceRepo, trafficRepo, userProfileRepo)
              case (_, Some(pid)) => buildForProfile(pid, date, zone, topN, claims, profileRepo, deviceRepo, trafficRepo, userProfileRepo)
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
      device  <- deviceRepo
        .findByMac(mac)
        .mapError(ErrorMapper.dbErrorToResponse)
        .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("Device not found")))
      _       <- requireProfileReadAccess(claims, device.profileId, userProfileRepo)
      rows    <- fetchPresenceDayWindow(trafficRepo, List(mac), date, zone)
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
      devices  = all.filter(_.profileId.contains(pid))
      macs     = devices.map(_.mac)
      nameByMac = devices.iterator.map(d => d.mac -> d.name).toMap
      rows    <- fetchPresenceDayWindow(trafficRepo, macs, date, zone)
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
        nxt <- trafficRepo.listPresenceRows(macs, date.plusDays(1)).mapError(ErrorMapper.dbErrorToResponse)
        prv <- trafficRepo.listPresenceRows(macs, date.minusDays(1)).mapError(ErrorMapper.dbErrorToResponse)
      } yield (prv ++ d ++ nxt).filter { r =>
        r.periodStart.atZone(zone).toLocalDate == date
      }
}
