package wifihaven.api.routes

import wifihaven.api.auth.{AuthService, JwtClaims}
import wifihaven.api.db.*
import wifihaven.api.metrics.AppMetrics
import wifihaven.api.observability.LogContext
import wifihaven.api.policy.TimeStatusService
import wifihaven.shared.{Clock, Device, MacBlockReason, SharedDeviceCheckInRequest}
import wifihaven.shared.types.{HouseholdId, MacAddress, ProfileId}
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*

/**
 * #2848 (epic #2841, design `docs/design/shared-devices.md` §9, §15 Q1–Q5a): list shared devices,
 * check in, check out.
 *
 *   - `GET /api/shared-devices`: any authenticated user; a child sees every shared device.
 *   - `POST /api/shared-devices/{mac}/check-in {profileId}`: a child on a profile they are linked
 *     to, an adult/admin on any non-global profile in the household.
 *   - `POST /api/shared-devices/{mac}/check-out`: `check_out` by any user linked to the holder
 *     profile, `forced` by an adult/admin on someone else's check-in.
 *
 * Errors the SPA branches on carry a JSON code: 403 `not_linked`, 409 `held` / `profile_blocked` /
 * `not_shared` / `not_held`. Every successful mutation calls `onChange(household)`, which
 * production wires to `PolicyService.invalidate` plus the `sharedDevices` and `stale{devices}` ws
 * pushes.
 */
object SharedDeviceRoutes {

  /** The refusal reasons `shared_device_checkin_rejected_total{reason}` is labelled with. */
  private enum Rejection(val code: String, val status: Status) {
    case Held           extends Rejection("held", Status.Conflict)
    case NotLinked      extends Rejection("not_linked", Status.Forbidden)
    case ProfileBlocked extends Rejection("profile_blocked", Status.Conflict)
    case NotShared      extends Rejection("not_shared", Status.Conflict)
    case NotHeld        extends Rejection("not_held", Status.Conflict)
  }

  private def reject(
      r: Rejection,
      extra: Option[(String, String)] = None,
  ): IO[ApiError, Nothing] = {
    val fields = ("error" -> r.code) :: extra.toList
    val body   = fields.map { case (k, v) => s"${k.toJson}:${v.toJson}" }.mkString("{", ",", "}")
    AppMetrics.recordSharedDeviceCheckinRejected(r.code) *>
      ZIO.fail(ApiError.Wrapped(Response.json(body).status(r.status)))
  }

  // Design §15 Q3: check-in is refused while the profile is blocked for now. `DefaultDeny` is a
  // baseline mode, not a block-for-now, so it may check in.
  private val BlockingReasons: Set[MacBlockReason] =
    Set(MacBlockReason.Paused, MacBlockReason.Schedule, MacBlockReason.TimeLimit)

  def routes(
      auth: AuthService,
      deviceRepo: DeviceRepo,
      sharedDeviceRepo: SharedDeviceRepo,
      userProfileRepo: UserProfileRepo,
      profileRepo: ProfileRepo,
      hsRepo: HouseholdSettingsRepo,
      timeStatus: TimeStatusService,
      clock: Clock,
      onChange: HouseholdId => UIO[Unit] = _ => ZIO.unit,
  ): Routes[Any, Response] = {

    // The device the caller names must be in the caller's household: each handler looks it up with
    // `findByMacInHousehold`, so a MAC in another household is a 404, indistinguishable from one
    // that does not exist. This then requires it to be shared.
    def requireShared(found: Option[Device]): IO[ApiError, Device] =
      ZIO
        .fromOption(found)
        .orElseFail(ApiError.NotFound("Device not found"))
        .tap(d => ZIO.unless(d.shared)(reject(Rejection.NotShared)))

    def macOf(raw: String): MacAddress = MacAddress.unsafe(normalizeMac(raw))

    def linkedProfiles(claims: JwtClaims): IO[ApiError, List[ProfileId]] =
      userProfileRepo.listProfilesForUsername(claims.hh, claims.sub).mapError(ApiError.Db(_))

    Routes(
      Method.GET / "api" / "shared-devices"                                ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireAuth(req, auth)
            list   <- sharedDeviceRepo.listForHousehold(claims.hh).mapError(ApiError.Db(_))
          } yield Response.json(list.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.POST / "api" / "shared-devices" / string("mac") / "check-in"  ->
        handler { (mac: String, req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireAuth(req, auth)
            device <- deviceRepo
              .findByMacInHousehold(macOf(mac), claims.hh)
              .mapError(ApiError.Db(_))
              .flatMap(requireShared)
            body   <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            cir    <- ZIO
              .fromEither(body.fromJson[SharedDeviceCheckInRequest])
              .mapError(ApiError.DecodeFailure(_))
            pid = cir.profileId
            // A profile in another household is a 404, before any role check (#2108).
            _        <- requireProfileInHousehold(claims, pid, profileRepo)
            _        <- requireNotGlobalProfile(profileRepo, pid, "profileId")
            // Design §15 Q5a / Q9: a child only on a linked profile; an adult or admin on any.
            _        <- ZIO.unless(isWriterRole(claims))(
              linkedProfiles(claims).flatMap(ls =>
                ZIO.unless(ls.contains(pid))(reject(Rejection.NotLinked)),
              ),
            )
            now      <- clock.instant
            settings <- hsRepo.getForHousehold(claims.hh).mapError(ApiError.Db(_))
            // The profile's block state comes from the same day-state primitive the snapshot uses.
            state    <- timeStatus
              .todaysState(claims.hh, now, settings, pid)
              .mapError(ApiError.Db(_))
            _        <- state.flatMap(_.blockReason).filter(BlockingReasons.contains) match {
              case Some(r) =>
                reject(Rejection.ProfileBlocked, Some("reason" -> MacBlockReason.asString(r)))
              case None    => ZIO.unit
            }
            outcome  <- sharedDeviceRepo
              .checkIn(claims.hh, device.id, pid, now, claims.sub)
              .mapError(ApiError.Db(_))
            _        <- outcome match {
              case CheckInOutcome.CheckedIn => ZIO.unit
              case CheckInOutcome.Held      => reject(Rejection.Held)
              case CheckInOutcome.NotShared => reject(Rejection.NotShared)
            }
            _        <- AppMetrics.recordSharedDeviceCheckin("check_in", "user")
            _        <- LogContext.annotate(LogContext.Mac, device.mac.value) {
              ZIO.logInfo(s"shared device checked in: profileId=${pid.value} by=${claims.sub}")
            }
            _        <- onChange(claims.hh)
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.POST / "api" / "shared-devices" / string("mac") / "check-out" ->
        handler { (mac: String, req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims  <- requireAuth(req, auth)
            device  <- deviceRepo
              .findByMacInHousehold(macOf(mac), claims.hh)
              .mapError(ApiError.Db(_))
              .flatMap(requireShared)
            // A checked-in shared device's current profile is its holder (design §5.2).
            holder  <- ZIO.fromOption(device.profileId).orElse(reject(Rejection.NotHeld))
            linked  <- linkedProfiles(claims)
            cause   <-
              if (linked.contains(holder)) ZIO.succeed(AssignmentEndCause.CheckOut)
              else if (isWriterRole(claims)) ZIO.succeed(AssignmentEndCause.Forced)
              else reject(Rejection.NotLinked)
            now     <- clock.instant
            outcome <- sharedDeviceRepo
              .checkOut(claims.hh, device.id, holder, now, claims.sub, cause)
              .mapError(ApiError.Db(_))
            _       <- outcome match {
              case CheckOutOutcome.CheckedOut => ZIO.unit
              case CheckOutOutcome.NotHeld    => reject(Rejection.NotHeld)
              case CheckOutOutcome.NotShared  => reject(Rejection.NotShared)
            }
            _       <- AppMetrics.recordSharedDeviceCheckin("check_out", cause.db)
            _       <- LogContext.annotate(LogContext.Mac, device.mac.value) {
              ZIO.logInfo(
                s"shared device checked out: profileId=${holder.value} cause=${cause.db} by=${claims.sub}",
              )
            }
            _       <- onChange(claims.hh)
          } yield Response.ok
          handle.mapError(ErrorMapper.errorToResponse)
        },
    )
  }
}
