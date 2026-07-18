package wifihaven.api.routes

import wifihaven.api.auth.AuthService
import wifihaven.api.db.PressMessageRepo
import wifihaven.shared.types.HouseholdId
import zio.*
import zio.http.*
import zio.json.*

/**
 * #2296 (press correspondence log, epic #2197/#2203) — the ONE admin surface over the recorded
 * press channel: the household-1 operator's read-only view of every inbound press email and every
 * autonomous AI reply.
 *
 *   - ADMIN `GET /api/press/messages` — the recorded correspondence, newest-first. Press is a
 *     single **company-global** channel (not tenant-owned; `press_messages` has no `household_id`),
 *     so the gate is: authenticated + `admin` role + household 1 (`HouseholdId.Default`). Any OTHER
 *     household — even an admin — gets **404**, not 403: the log's existence is not disclosed
 *     across the tenant boundary. A non-admin gets 403 (from `requireAdmin`); unauthenticated gets
 *     401.
 *
 * This is the READ side only. The public inbound webhook + the press agent's reply callback live in
 * [[PressAgentRoutes]]; the rows this reads are written (fail-open) by
 * [[wifihaven.api.press.PressResponder]].
 */
object PressRoutes {

  /** Bound the read — the correspondence view is a recent-history list, not a full export. */
  val MaxMessages: Int = 500

  def routes(auth: AuthService, pressLog: PressMessageRepo): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "press" / "messages" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims   <- requireAdmin(req, auth)
            // Company-global data, gated to the operator household. A non-default household (even an
            // admin) is 404 — indistinguishable from "no such endpoint", so the log is not revealed.
            _        <- ZIO
              .fail(ApiError.NotFound("not found"))
              .when(claims.hh != HouseholdId.Default)
            messages <- pressLog
              .listRecent(MaxMessages)
              .orElseFail(ApiError.Internal("could not load press messages"))
          } yield Response.json(messages.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
    )
}
