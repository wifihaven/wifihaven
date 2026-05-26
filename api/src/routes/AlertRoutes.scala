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
 * Generic admin-action feed. Replaces the per-table `/api/device-alerts` endpoints (#711). The
 * unified feed today carries only `kind=new_device` rows; #960 adds the `access_request` kind on
 * top — same lifecycle, same endpoints, different side-effect on approve.
 *
 * GET /api/alerts — auth required. `?all=true` includes decided rows. POST /api/alerts/{id}/approve
 * — writer-auth. For new_device, just records the decision (the device row / profile assignment
 * lives on the Devices page). #960 adds the access-request side-effects. POST /api/alerts/{id}/deny
 * — writer-auth. Records the decision; no side-effect.
 */
object AlertRoutes {

  def routes(
      auth: AuthService,
      alertRepo: AlertRepo,
      clock: SharedClock,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "alerts"                           ->
        handler { (req: Request) =>
          for {
            _ <- requireAuth(req, auth)
            includeAll = req.url
              .queryParam("all")
              .map(_.equalsIgnoreCase("true"))
              .getOrElse(false)
            xs <- alertRepo
              .list(includeAll)
              .mapError(ErrorMapper.dbErrorToResponse)
          } yield Response.json(xs.toJson)
        },
      Method.POST / "api" / "alerts" / long("id") / "approve" ->
        handler { (id: Long, req: Request) =>
          decideAlert(AlertId(id), AlertStatus.Approved, req, auth, alertRepo, clock)
        },
      Method.POST / "api" / "alerts" / long("id") / "deny"    ->
        handler { (id: Long, req: Request) =>
          decideAlert(AlertId(id), AlertStatus.Denied, req, auth, alertRepo, clock)
        },
    )

  private def decideAlert(
      id: AlertId,
      newStatus: AlertStatus,
      req: Request,
      auth: AuthService,
      alertRepo: AlertRepo,
      clock: SharedClock,
  ): ZIO[Any, Response, Response] =
    for {
      claims <- requireWriter(req, auth)
      now    <- clock.instant
      n      <- alertRepo
        .decide(id, newStatus, now, claims.sub, None)
        .mapError(ErrorMapper.dbErrorToResponse)
      resp   <- alertRepo
        .findById(id)
        .mapError(ErrorMapper.dbErrorToResponse)
        .map {
          case None          => Response.notFound("Alert not found")
          case Some(updated) =>
            if n == 0 then Response.status(Status.Conflict)
            else Response.json(updated.toJson)
        }
    } yield resp
}
