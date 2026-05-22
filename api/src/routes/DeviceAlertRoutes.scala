package wifihaven.api.routes

import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.shared.*
import wifihaven.shared.Clock as SharedClock
import wifihaven.shared.types.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*

/**
 * #711: list + dismiss endpoints for the new-device alert feed. Reads are open to any authenticated
 * user (children see the banner too — they just can't dismiss). Dismissal requires admin: any other
 * role would let a child silence the alert about their own newly-jailbroken device.
 */
object DeviceAlertRoutes {

  def routes(
      auth: AuthService,
      repo: DeviceAlertRepo,
      clock: SharedClock,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "device-alerts"                           ->
        handler { (req: Request) =>
          for {
            _ <- requireAuth(req, auth)
            includeDismissed = req.url
              .queryParam("dismissed")
              .map(_.equalsIgnoreCase("true"))
              .getOrElse(false)
            xs <- repo
              .listAll(includeDismissed)
              .mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.json(xs.toJson)
        },
      Method.POST / "api" / "device-alerts" / long("id") / "dismiss" ->
        handler { (id: Long, req: Request) =>
          for {
            _   <- requireAdmin(req, auth)
            now <- clock.instant
            n   <- repo
              .dismiss(DeviceAlertId(id), now)
              .mapError(ErrorMapper.dbErrorToResponse)
            resp =
              if n == 0 then Response.notFound("Alert not found or already dismissed")
              else Response.ok
          } yield resp
        },
    )
}
