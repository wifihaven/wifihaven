package wifihaven.api.routes

import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.notify.Notifier
import wifihaven.shared.*
import wifihaven.shared.Clock as SharedClock
import wifihaven.shared.types.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*

/**
 * Generic admin-action feed. Two kinds — `new_device` (#711) and `access_request` (#960) — share
 * the same `pending → approved | denied` state machine; the side-effect on approve differs.
 *
 * POST /api/access-requests — public, no auth. Block page posts {mac, host, kind, note?}; debounced
 * per-(mac, host). GET /api/alerts — auth required. `?all=true` includes decided rows. POST
 * /api/alerts/{id}/approve — writer-auth. For access_request, applies the per- kind grant via
 * existing primitives. For new_device, just records the decision. POST /api/alerts/{id}/deny —
 * writer-auth. Records the decision; no side-effect.
 */
object AlertRoutes {

  /**
   * Debounce window: a fresh POST /api/access-requests from the same (mac, host) within this many
   * seconds returns the existing pending row instead of inserting a duplicate.
   */
  val AccessRequestDebounceSeconds: Long = 5 * 60

  /** Default extension duration when the admin doesn't override on approve. */
  val DefaultExtensionMinutes: Int = 30

  def routes(
      auth: AuthService,
      alertRepo: AlertRepo,
      deviceRepo: DeviceRepo,
      profileRepo: ProfileRepo,
      extRepo: TimeExtensionRepo,
      appRepo: AppRepo,
      hsRepo: HouseholdSettingsRepo,
      notifier: Notifier,
      clock: SharedClock,
  ): Routes[Any, Response] =
    Routes(
      // ── Public: kid posts an access-request from the block page ─────────
      Method.POST / "api" / "access-requests" ->
        handler { (req: Request) =>
          for {
            body <- req.body.asString.orElseFail(Response.badRequest(""))
            cr   <- ZIO
              .fromEither(body.fromJson[CreateAccessRequest])
              .mapError(e => Response.badRequest(e))
            now  <- clock.instant
            since = now.minusSeconds(AccessRequestDebounceSeconds)
            existing <- alertRepo
              .findRecentAccessRequest(cr.mac, cr.host, since)
              .mapError(ErrorMapper.dbErrorToResponse)
            resp     <- existing match {
              case Some(a) => ZIO.succeed(Response.json(a.toJson))
              case None    =>
                for {
                  device <- deviceRepo
                    .findByMac(cr.mac)
                    .mapError(ErrorMapper.dbErrorToResponse)
                  pid = device.flatMap(_.profileId)
                  id    <- alertRepo
                    .createAccessRequest(cr.mac, pid, cr.host, cr.kind, cr.note, now)
                    .mapError(ErrorMapper.dbErrorToResponse)
                  full  <- alertRepo
                    .findById(id)
                    .mapError(ErrorMapper.dbErrorToResponse)
                  alert <- ZIO
                    .fromOption(full)
                    .orElseFail(Response.internalServerError("vanished"))
                  _     <- notifier.alertCreated(alert).forkDaemon
                } yield Response.json(alert.toJson).status(Status.Created)
            }
          } yield resp
        },

      // ── Admin list ──────────────────────────────────────────────────────
      Method.GET / "api" / "alerts" ->
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

      // ── Admin approve ───────────────────────────────────────────────────
      Method.POST / "api" / "alerts" / long("id") / "approve" ->
        handler { (id: Long, req: Request) =>
          val aid = AlertId(id)
          for {
            claims  <- requireWriter(req, auth)
            body    <- req.body.asString.orElse(ZIO.succeed(""))
            apr     <-
              if (body.isEmpty) ZIO.succeed(ApproveAlertRequest())
              else
                ZIO
                  .fromEither(body.fromJson[ApproveAlertRequest])
                  .mapError(e => Response.badRequest(e))
            alert   <- alertRepo
              .findById(aid)
              .mapError(ErrorMapper.dbErrorToResponse)
              .flatMap(ZIO.fromOption(_).orElseFail(Response.notFound("Alert not found")))
            _       <- ZIO
              .fail(Response.status(Status.Conflict))
              .when(alert.status != AlertStatus.Pending)
            now     <- clock.instant
            // Side-effect lands before the status transition so we never
            // leave an "approved" row pointing at a grant that failed.
            granted <- applyApproveSideEffect(
              alert,
              apr.minutes.getOrElse(DefaultExtensionMinutes),
              claims.sub,
              profileRepo,
              extRepo,
              appRepo,
              hsRepo,
              clock,
            )
            n       <- alertRepo
              .decide(aid, AlertStatus.Approved, now, claims.sub, granted)
              .mapError(ErrorMapper.dbErrorToResponse)
            resp    <- alertRepo
              .findById(aid)
              .mapError(ErrorMapper.dbErrorToResponse)
              .map {
                case None          => Response.notFound("Alert not found")
                case Some(updated) =>
                  if n == 0 then Response.status(Status.Conflict)
                  else Response.json(updated.toJson)
              }
          } yield resp
        },

      // ── Admin deny ──────────────────────────────────────────────────────
      Method.POST / "api" / "alerts" / long("id") / "deny" ->
        handler { (id: Long, req: Request) =>
          val aid = AlertId(id)
          for {
            claims <- requireWriter(req, auth)
            now    <- clock.instant
            n      <- alertRepo
              .decide(aid, AlertStatus.Denied, now, claims.sub, None)
              .mapError(ErrorMapper.dbErrorToResponse)
            resp   <- alertRepo
              .findById(aid)
              .mapError(ErrorMapper.dbErrorToResponse)
              .map {
                case None          => Response.notFound("Alert not found")
                case Some(updated) =>
                  if n == 0 then Response.status(Status.Conflict)
                  else Response.json(updated.toJson)
              }
          } yield resp
        },
    )

  /**
   * Apply the per-kind side effect for approve. Returns the granted-minutes value to record on the
   * row (only meaningful for extension grants); the row itself is updated by the caller.
   *
   *   - new_device: no side effect. The device row is managed separately on the Devices page.
   *   - access_request extension: grant minutes against today's bucket.
   *   - access_request exemption: upsert a single-host App + Allowed assignment for the kid's
   *     profile (post-#1054 the legacy `profile.extraAllowed` column is gone).
   *   - access_request unpause: flip profile.paused=false.
   *
   * A 400 bubbles when a grant can't apply (e.g. extension on a device with no profile) so the
   * admin can fix the prerequisite instead of accumulating half-applied approvals.
   */
  private def applyApproveSideEffect(
      alert: Alert,
      requestedMinutes: Int,
      grantedBy: String,
      profileRepo: ProfileRepo,
      extRepo: TimeExtensionRepo,
      appRepo: AppRepo,
      hsRepo: HouseholdSettingsRepo,
      clock: SharedClock,
  ): ZIO[Any, Response, Option[Int]] =
    alert.kind match {
      case AlertKind.NewDevice =>
        ZIO.succeed(None)

      case AlertKind.AccessRequest =>
        (alert.requestKind, alert.host) match {
          case (None, _) | (_, None) =>
            ZIO.fail(Response.internalServerError("access-request row missing requestKind/host"))

          case (Some(AccessRequestKind.Extension), _) =>
            alert.profileId match {
              case None      =>
                ZIO.fail(
                  Response.badRequest(
                    "Cannot extend time: device is not assigned to a profile",
                  ),
                )
              case Some(pid) =>
                for {
                  settings <- hsRepo.get.mapError(ErrorMapper.dbErrorToResponse)
                  now      <- clock.instant
                  today =
                    wifihaven.api.policy.PolicyService.householdLocalDate(now, settings)
                  _ <- extRepo
                    .grantForProfile(
                      pid,
                      today,
                      requestedMinutes,
                      grantedBy,
                      alert.note.orElse(Some(s"approved alert #${alert.id.value}")),
                    )
                    .mapError(ErrorMapper.dbErrorToResponse)
                } yield Some(requestedMinutes)
            }

          case (Some(AccessRequestKind.Exemption), Some(host)) =>
            // Post-#1054 the legacy `profile.extraAllowed` column is gone;
            // exemptions are expressed as a single-host App with an
            // assignment of (app, profile, mode=Allowed). We find or create
            // the App keyed by slug = host so repeated exemptions for the
            // same host reuse the row.
            alert.profileId match {
              case None      =>
                ZIO.fail(
                  Response.badRequest(
                    "Cannot grant exemption: device is not assigned to a profile",
                  ),
                )
              case Some(pid) =>
                for {
                  existing <- appRepo
                    .findBySlug(host.value)
                    .mapError(ErrorMapper.dbErrorToResponse)
                  appId    <- existing match {
                    case Some(app) => ZIO.succeed(app.id)
                    case None      =>
                      for {
                        id <- appRepo
                          .create(host.value, host.value, None, None)
                          .mapError(ErrorMapper.dbErrorToResponse)
                        _  <- appRepo
                          .setHosts(id, List(host))
                          .mapError(ErrorMapper.dbErrorToResponse)
                      } yield id
                  }
                  _        <- appRepo
                    .upsertAssignment(appId, pid, AppMode.Allowed, None, true)
                    .mapError(ErrorMapper.dbErrorToResponse)
                } yield None
            }

          case (Some(AccessRequestKind.Unpause), _) =>
            alert.profileId match {
              case None      =>
                ZIO.fail(
                  Response.badRequest(
                    "Cannot unpause: device is not assigned to a profile",
                  ),
                )
              case Some(pid) =>
                profileRepo
                  .setPaused(pid, false)
                  .mapError(ErrorMapper.dbErrorToResponse)
                  .as(None)
            }
        }
    }
}
