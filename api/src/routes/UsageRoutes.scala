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
// First consumer: #721 per-device daily timeline.
object UsageRoutes {
  def routes(
      auth: AuthService,
      deviceRepo: DeviceRepo,
      trafficRepo: TrafficReportRepo,
      userProfileRepo: UserProfileRepo,
      clock: Clock,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "usage" / "series" ->
        handler { (req: Request) =>
          for {
            claims <- requireAuth(req, auth)
            today  <- clock.today
            macRaw <- ZIO
              .fromOption(req.url.queryParam("mac"))
              .orElseFail(Response.badRequest("mac is required"))
            mac      = MacAddress.unsafe(normalizeMac(macRaw))
            dateStr  = req.url.queryParam("date").getOrElse(today.toString)
            date    <- ZIO
              .attempt(LocalDate.parse(dateStr))
              .orElseFail(Response.badRequest(s"invalid date: $dateStr"))
            tzStr    = req.url.queryParam("tz").getOrElse("UTC")
            zone    <- ZIO
              .attempt(ZoneId.of(tzStr))
              .orElseFail(Response.badRequest(s"invalid tz: $tzStr"))
            topN     = req.url
                         .queryParam("topN")
                         .flatMap(_.toIntOption)
                         .getOrElse(5)
                         .max(1)
                         .min(20)
            device  <- deviceRepo
              .findByMac(mac)
              .mapError(ErrorMapper.dbErrorToResponse)
              .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("Device not found")))
            _       <- requireProfileReadAccess(claims, device.profileId, userProfileRepo)
            // Pull two adjacent UTC days so a tz like America/Los_Angeles can
            // assemble its calendar day from buckets split across midnight UTC.
            // Cheap on a per-mac filter via idx_traffic_reports_mac_date.
            rowsD   <- trafficRepo
              .listPresenceRows(List(mac), date)
              .mapError(ErrorMapper.dbErrorToResponse)
            rowsNxt <- trafficRepo
              .listPresenceRows(List(mac), date.plusDays(1))
              .mapError(ErrorMapper.dbErrorToResponse)
            rowsPrv <- trafficRepo
              .listPresenceRows(List(mac), date.minusDays(1))
              .mapError(ErrorMapper.dbErrorToResponse)
            // Keep only the rows whose period_start falls on `date` in `zone`.
            inDay   = (rowsPrv ++ rowsD ++ rowsNxt).filter { r =>
              r.periodStart.atZone(zone).toLocalDate == date
            }
            (topHosts, buckets) = UsageSeries.build(inDay, zone, topN)
            resp = UsageSeriesResponse(
              deviceMac = mac,
              deviceName = device.name,
              date = date.toString,
              tz = zone.getId,
              topHosts = topHosts,
              buckets = buckets,
            )
          } yield Response.json(resp.toJson)
        },
    )
}
