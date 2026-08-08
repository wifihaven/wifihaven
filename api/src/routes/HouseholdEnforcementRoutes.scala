package wifihaven.api.routes

import wifihaven.api.auth.*
import wifihaven.api.db.HouseholdSettingsRepo
import wifihaven.shared.*
import wifihaven.shared.types.HouseholdId
import zio.*
import zio.http.*
import zio.json.*

/**
 * #2382 (epic #622): the server-level per-household "disable enforcement" escape hatch — the easy
 * dashboard equivalent of the router-level offline hatch (#2381). Two surfaces, BOTH strictly
 * scoped to the caller's own household (`claims.hh`) so one household can never read or flip
 * another's flag (multi-tenant isolation, epic #2085):
 *
 *   - `GET /api/household/enforcement` — any authenticated member reads the current flag (so the
 *     SPA can render the toggle + its "blocking is OFF" banner for everyone).
 *   - `PUT /api/household/enforcement` — ADMIN-only write.
 *
 * When the flag is set, [[wifihaven.api.policy.PolicyService]] serves a fully permissive
 * (allow-all) snapshot for the household — the SAME path a `lapsed` household takes (#2137). The
 * router stays a dumb applier: it just receives `blocked=false` for every MAC, learns nothing about
 * this flag, and needs no change. Because the decision is SERVER-driven, it does NOT help during an
 * API outage — the SPA points the user at the router-level offline hatch (#2381) for that.
 *
 * A write invalidates the computed-snapshot cache (`invalidateSnapshot`, wired to
 * `policy.invalidate` in Main) so the change fans out to connected ws routers and the next REST
 * poll rebuilds fresh.
 */
object HouseholdEnforcementRoutes {
  def routes(
      auth: AuthService,
      settingsRepo: HouseholdSettingsRepo,
      // Defaulted to a no-op for test call sites; production passes `policy.invalidate` so a toggle
      // takes effect on the fleet immediately.
      invalidateSnapshot: HouseholdId => UIO[Unit] = _ => ZIO.unit,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "household" / "enforcement" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims   <- requireAuth(req, auth)
            disabled <- settingsRepo.enforcementDisabled(claims.hh).mapError(ApiError.Db(_))
          } yield Response.json(EnforcementStatusResponse(disabled).toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
      Method.PUT / "api" / "household" / "enforcement" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireAdmin(req, auth)
            body   <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            upd    <- ZIO
              .fromEither(body.fromJson[SetEnforcementRequest])
              .mapError(ApiError.DecodeFailure(_))
            // Scoped write (`WHERE household_id=claims.hh`); false = the household has no settings
            // row (unknown household — every real household owns one post-#2386).
            ok     <- settingsRepo
              .setEnforcementDisabled(claims.hh, upd.enforcementDisabled)
              .mapError(ApiError.Db(_))
            _      <- ZIO
              .fail(ApiError.NotFound("household not found"))
              .when(!ok)
            _      <- invalidateSnapshot(claims.hh)
          } yield Response.json(EnforcementStatusResponse(upd.enforcementDisabled).toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
    )
}
