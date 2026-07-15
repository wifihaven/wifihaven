package wifihaven.api.routes

import wifihaven.api.auth.AuthService
import wifihaven.api.support.SupportService
import zio.*
import zio.http.*
import zio.json.*

/**
 * #2199 (support intake B, epic #2197) — the ONE support surface in this PR: the server-signed
 * widget identity for the authenticated admin SPA (design/umbrella #2206 §2).
 *
 *   - ADMIN `GET /api/support/identity` — returns the Plain identified-chat identity for the
 *     CALLER'S OWN household: the chat-auth HMAC over the caller's email + `tenantIdentifier =
 *     household_id`. Admin-gated (`requireAdmin`) so it acts on `claims.hh` only — household A can
 *     never obtain household B's identity (the household-gating boundary). When the widget is
 *     unconfigured, or the caller has no email, it returns `{configured:false}` and the SPA renders
 *     no widget (feature dark).
 *
 * There is deliberately no thread/support-table surface here — Plain owns threads (#2199 scope);
 * the Claude responder + its webhook are #2200.
 */
object SupportRoutes {

  def routes(
      auth: AuthService,
      support: SupportService,
  ): Routes[Any, Response] =
    Routes(
      Method.GET / "api" / "support" / "identity" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims   <- requireAdmin(req, auth)
            // identity never fails; it derives everything from the caller's OWN JWT-resolved
            // household, so there is no cross-household read even when dark.
            identity <- support.identity(claims)
          } yield Response.json(identity.toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },
    )
}
