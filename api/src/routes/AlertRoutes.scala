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
 *
 * #1570: handlers fail with a typed [[ApiError]] mapped centrally by
 * [[ErrorMapper.errorToResponse]]; the [[wifihaven.api.ErrorBoundary]] logs (4xx WARN / 5xx ERROR)
 * + meters each error. Each case reproduces the EXACT status + body the hand-rolled code produced —
 * the pending-state `409` keeps its empty body via [[ApiError.Wrapped]], DB failures stay 503 via
 * [[ApiError.Db]]. Success-channel responses (the debounce `Some(a)` hit) are unchanged; the
 * boundary still observes their status.
 */
object AlertRoutes {

  /**
   * Debounce window: a fresh POST /api/access-requests from the same (mac, host) within this many
   * seconds returns the existing pending row instead of inserting a duplicate.
   */
  val AccessRequestDebounceSeconds: Long = 5 * 60

  /** Default extension duration when the admin doesn't override on approve. */
  val DefaultExtensionMinutes: Int = 30

  /**
   * #2081: `note` is attacker-controlled free text on an unauthenticated route (React escapes it on
   * render, so no XSS, but an unbounded value is unnecessary DB bloat / content-injection surface).
   * Truncated, not rejected — a genuine over-length note from a kid shouldn't 400.
   */
  val MaxNoteLength: Int = 500

  def routes(
      auth: AuthService,
      alertRepo: AlertRepo,
      deviceRepo: DeviceRepo,
      profileRepo: ProfileRepo,
      extRepo: TimeExtensionRepo,
      appRepo: AppRepo,
      hsRepo: HouseholdSettingsRepo,
      // #2564: approve's side effect writes a PROFILE, so the profile-access guard needs the
      // user↔profile links to evaluate the caller's link for a non-admin writer.
      userProfileRepo: UserProfileRepo,
      notifier: Notifier,
      clock: SharedClock,
      rateLimiter: RateLimiter,
  ): Routes[Any, Response] =
    Routes(
      // ── Public: kid posts an access-request from the block page ─────────
      Method.POST / "api" / "access-requests" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            // #2081: per-source-IP rate limit — the existing per-(mac,host) debounce is
            // bypassed by varying host/note, so an unauthenticated caller could otherwise
            // flood alerts + notifications by cycling those fields.
            allowed <- rateLimiter.tryAcquire(s"access-requests:${clientIp(req)}")
            _       <- ZIO
              .fail(ApiError.RateLimited("Too many requests; try again later"))
              .unless(allowed)
            body    <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            cr0     <- ZIO
              .fromEither(body.fromJson[CreateAccessRequest])
              .mapError(ApiError.DecodeFailure(_))
            cr = cr0.copy(note = cr0.note.map(_.take(MaxNoteLength)))
            now <- clock.instant
            since = now.minusSeconds(AccessRequestDebounceSeconds)
            existing <- alertRepo
              .findRecentAccessRequest(cr.mac, cr.host, since)
              .mapError(ApiError.Db(_))
            resp     <- existing match {
              case Some(a) => ZIO.succeed(Response.json(a.toJson))
              case None    =>
                for {
                  // #2312: household-scoped lookup — the same MAC can exist in two households
                  // (V74/V75), and the old global findByMac threw on the 2-row match. This is the
                  // unauthenticated block-page access-request path (no household in scope), so it
                  // resolves against HouseholdId.Default until block-page household derivation lands
                  // (#2322 — matches the insert-side TODO in Repos.scala).
                  device <- deviceRepo
                    .findByMac(cr.mac, HouseholdId.Default)
                    .mapError(ApiError.Db(_))
                  pid = device.flatMap(_.profileId)
                  id    <- alertRepo
                    .createAccessRequest(cr.mac, pid, cr.host, cr.kind, cr.note, now)
                    .mapError(ApiError.Db(_))
                  full  <- alertRepo
                    .findById(id)
                    .mapError(ApiError.Db(_))
                  alert <- ZIO
                    .fromOption(full)
                    .orElseFail(ApiError.Internal("vanished"))
                  _     <- notifier.alertCreated(alert).forkDaemon
                } yield Response.json(alert.toJson).status(Status.Created)
            }
          } yield resp
          handle.mapError(ErrorMapper.errorToResponse)
        },

      // ── Admin list ──────────────────────────────────────────────────────
      Method.GET / "api" / "alerts" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireAuth(req, auth)
            includeAll = req.url
              .queryParam("all")
              .map(_.equalsIgnoreCase("true"))
              .getOrElse(false)
            // #2108/#2283: alerts scoped to the caller's household, on the alert's own
            // `household_id` — see `AlertRepo.listForHousehold`.
            xs <- alertRepo
              .listForHousehold(includeAll, claims.hh)
              .mapError(ApiError.Db(_))
          } yield Response.json(xs.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },

      // ── Admin approve ───────────────────────────────────────────────────
      Method.POST / "api" / "alerts" / long("id") / "approve" ->
        handler { (id: Long, req: Request) =>
          val aid                                  = AlertId(id)
          val handle: ZIO[Any, ApiError, Response] = for {
            claims  <- requireWriter(req, auth)
            // #2564: scope the TARGET to the caller's household BEFORE any read of the row —
            // `findById` below is unscoped, so this is the only thing standing between an hh-A
            // writer and hh-B's alert body (and, via approve's side effect, hh-B's profile).
            _       <- requireAlertInHousehold(claims, aid, alertRepo)
            body    <- req.body.asString.orElse(ZIO.succeed(""))
            apr     <-
              if (body.isEmpty) ZIO.succeed(ApproveAlertRequest())
              else
                ZIO
                  .fromEither(body.fromJson[ApproveAlertRequest])
                  .mapError(ApiError.DecodeFailure(_))
            alert   <- alertRepo
              .findById(aid)
              .mapError(ApiError.Db(_))
              .flatMap(ZIO.fromOption(_).orElseFail(ApiError.NotFound("Alert not found")))
            _       <- ZIO
              .fail(ApiError.Wrapped(Response.status(Status.Conflict)))
              .when(alert.status != AlertStatus.Pending)
            now     <- clock.instant
            // Side-effect lands before the status transition so we never
            // leave an "approved" row pointing at a grant that failed.
            granted <- applyApproveSideEffect(
              alert,
              apr.minutes.getOrElse(DefaultExtensionMinutes),
              claims,
              profileRepo,
              userProfileRepo,
              extRepo,
              appRepo,
              hsRepo,
              clock,
            )
            n       <- alertRepo
              .decide(aid, AlertStatus.Approved, now, claims.sub, granted)
              .mapError(ApiError.Db(_))
            resp    <- alertRepo
              .findById(aid)
              .mapError(ApiError.Db(_))
              .flatMap {
                case None          => ZIO.fail(ApiError.NotFound("Alert not found"))
                case Some(updated) =>
                  if n == 0 then ZIO.fail(ApiError.Wrapped(Response.status(Status.Conflict)))
                  else ZIO.succeed(Response.json(updated.toJson))
              }
          } yield resp
          handle.mapError(ErrorMapper.errorToResponse)
        },

      // ── Admin deny ──────────────────────────────────────────────────────
      Method.POST / "api" / "alerts" / long("id") / "deny" ->
        handler { (id: Long, req: Request) =>
          val aid                                  = AlertId(id)
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireWriter(req, auth)
            // #2564: same choke point as approve — `decide` is `WHERE id = ?` with no household
            // predicate, and the post-decide `findById` would echo hh-B's alert body back.
            _      <- requireAlertInHousehold(claims, aid, alertRepo)
            now    <- clock.instant
            n      <- alertRepo
              .decide(aid, AlertStatus.Denied, now, claims.sub, None)
              .mapError(ApiError.Db(_))
            resp   <- alertRepo
              .findById(aid)
              .mapError(ApiError.Db(_))
              .flatMap {
                case None          => ZIO.fail(ApiError.NotFound("Alert not found"))
                case Some(updated) =>
                  if n == 0 then ZIO.fail(ApiError.Wrapped(Response.status(Status.Conflict)))
                  else ZIO.succeed(Response.json(updated.toJson))
              }
          } yield resp
          handle.mapError(ErrorMapper.errorToResponse)
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
   *
   * #2564: every branch that WRITES a profile composes [[requireProfileAccess]] on that profile
   * first. `requireAlertInHousehold` at the top of the handler proved the ALERT is the caller's;
   * this proves the same of the profile the approval actually mutates. Two distinct things it
   * establishes, only one of which is live today:
   *
   *   - The caller's LINK to the profile — live, and the reason this guard is not optional. A
   *     non-admin writer's link was never checked anywhere on this path, so an `adult` in the
   *     household who is not linked to the profile could approve a grant they cannot make directly
   *     via `POST /api/time/extend`, which gates on this same primitive.
   *   - The profile's HOUSEHOLD (via the composed [[requireProfileInHousehold]]) — defense in
   *     depth. `alerts.household_id` and `alerts.profile_id` are stamped independently at insert,
   *     but they cannot currently disagree: `createAccessRequest` takes `profileId` from
   *     `findByMac(mac, HouseholdId.Default)` and stamps `household_id` with the LOWEST matching
   *     household, so either both resolve to household 1 or `profileId` is NULL; and device→profile
   *     reassignment is itself gated on `requireProfileAccess`, so a device never holds an
   *     out-of-household profile. `TODO(#2322)` could let them diverge — if it derives the
   *     block-page household for the STAMP without also rescoping the `findByMac` lookup that
   *     supplies `profileId`, a shared MAC lands an hh-B alert holding an hh-1 profile. Threading
   *     one derived household into both keeps them in agreement. Pinned now so the mismatch stays
   *     refused either way.
   */
  private def applyApproveSideEffect(
      alert: Alert,
      requestedMinutes: Int,
      // #2130: the approving admin — `claims.sub` stamps `granted_by`, `claims.hh` buckets the
      // extension grant into the approver's household. #2564: also the subject of the profile guard.
      claims: JwtClaims,
      profileRepo: ProfileRepo,
      userProfileRepo: UserProfileRepo,
      extRepo: TimeExtensionRepo,
      appRepo: AppRepo,
      hsRepo: HouseholdSettingsRepo,
      clock: SharedClock,
  ): ZIO[Any, ApiError, Option[Int]] = {
    val grantedBy = claims.sub
    val household = claims.hh

    // #2564: gate every profile-mutating branch on the caller's access to THAT profile.
    def onProfile[A](pid: ProfileId)(f: ZIO[Any, ApiError, A]): ZIO[Any, ApiError, A] =
      requireProfileAccess(claims, pid, userProfileRepo, profileRepo) *> f

    alert.kind match {
      case AlertKind.NewDevice =>
        ZIO.succeed(None)

      case AlertKind.AccessRequest =>
        (alert.requestKind, alert.host) match {
          case (None, _) | (_, None) =>
            ZIO.fail(ApiError.Internal("access-request row missing requestKind/host"))

          case (Some(AccessRequestKind.Extension), _) =>
            alert.profileId match {
              case None      =>
                ZIO.fail(
                  ApiError.BadRequest(
                    "Cannot extend time: device is not assigned to a profile",
                  ),
                )
              case Some(pid) =>
                onProfile(pid) {
                  for {
                    // #2130: household-scoped settings + grant stamp, so an
                    // approval by a hh-B admin lands in hh-B (bucketed by hh-B's
                    // local "today"), never V65's DEFAULT 1.
                    settings <- hsRepo.getForHousehold(household).mapError(ApiError.Db(_))
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
                        household,
                      )
                      .mapError(ApiError.Db(_))
                  } yield Some(requestedMinutes)
                }
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
                  ApiError.BadRequest(
                    "Cannot grant exemption: device is not assigned to a profile",
                  ),
                )
              case Some(pid) =>
                onProfile(pid) {
                  for {
                    existing <- appRepo
                      .findBySlug(host.value)
                      .mapError(ApiError.Db(_))
                    appId    <- existing match {
                      case Some(app) => ZIO.succeed(app.id)
                      case None      =>
                        for {
                          id <- appRepo
                            .create(host.value, host.value, None, None)
                            .mapError(ApiError.Db(_))
                          _  <- appRepo
                            .setHosts(id, List(host))
                            .mapError(ApiError.Db(_))
                        } yield id
                    }
                    _        <- appRepo
                      .upsertAssignment(appId, pid, AppMode.Allowed, None, true)
                      .mapError(ApiError.Db(_))
                  } yield None
                }
            }

          case (Some(AccessRequestKind.Unpause), _) =>
            alert.profileId match {
              case None      =>
                ZIO.fail(
                  ApiError.BadRequest(
                    "Cannot unpause: device is not assigned to a profile",
                  ),
                )
              case Some(pid) =>
                onProfile(pid) {
                  profileRepo
                    .setPaused(pid, false)
                    .mapError(ApiError.Db(_))
                    .as(None)
                }
            }
        }
    }
  }
}
