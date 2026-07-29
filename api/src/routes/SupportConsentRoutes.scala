package wifihaven.api.routes

import wifihaven.api.auth.AuthService
import wifihaven.api.support.SupportResponder
import wifihaven.api.support.SupportResponder.ConsentResult
import zio.*
import zio.http.*
import zio.json.*

/**
 * #2419 (epic #2197) — the CUSTOMER's half of the support data-access consent flow, and the ONE
 * writer of a consent record.
 *
 *   - ADMIN `POST /api/support/consent` — redeem the signed consent link the SERVER posted into a
 *     support thread (`{"grant":"g1.…"}`), or withdraw a live grant
 *     (`{"grant":"…","allow":false}`).
 *
 * This route is the boundary that makes agent self-escalation impossible (docs/ops/support-data-
 * consent.md). The support agent can ASK for consent with its thread-bound #2241 token
 * ([[SupportAgentRoutes]] `POST /api/support/agent/request-consent`), which only makes the server
 * post a prompt; recording the grant requires the customer's own session JWT, and the household
 * written is `claims.hh` — taken from that session, never from the request body and never inferred
 * from the untrusted message text. A consent link minted for another household is refused (403) and
 * writes nothing.
 *
 * Deliberately separate from [[SupportRoutes]] (the widget identity) so the consent writer has one
 * obvious home, and from [[SupportAgentRoutes]] because the credential is different in kind: JWT
 * here, agent token there. That difference IS the security property.
 */
object SupportConsentRoutes {

  /** Cap the body — it carries one signed link token and a boolean. */
  val MaxConsentBodyBytes: Long = 8 * 1024

  /**
   * The consent action: the signed grant token from the link, and whether the customer is allowing
   * or withdrawing. `allow` defaults to true so a bare `{"grant":"…"}` is an approval; withdrawal
   * is the explicit `{"grant":"…","allow":false}`.
   */
  private final case class ConsentPost(grant: String, allow: Boolean = true)
  private object ConsentPost { given JsonCodec[ConsentPost] = DeriveJsonCodec.gen[ConsentPost] }

  private final case class ConsentResponse(status: String)
  private object ConsentResponse {
    given JsonCodec[ConsentResponse] = DeriveJsonCodec.gen[ConsentResponse]
  }

  def routes(auth: AuthService, responder: SupportResponder): Routes[Any, Response] =
    Routes(
      // Admin-gated, like the widget identity: the person who can see the account is the person
      // who decides whether the assistant may read its summary.
      Method.POST / "api" / "support" / "consent" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireAdmin(req, auth)
            body   <- req.body.asString
              .orElseFail(ApiError.BadRequest(""))
              .filterOrFail(_.length.toLong <= MaxConsentBodyBytes)(
                ApiError.BadRequest("payload too large"),
              )
            post   <- ZIO
              .fromEither(body.fromJson[ConsentPost])
              .mapError(ApiError.DecodeFailure.apply)
            _      <- ZIO
              .fail(ApiError.BadRequest("missing consent link"))
              .when(post.grant.trim.isEmpty)
            result <- responder.recordConsent(claims, post.grant, post.allow)
            resp   <- result match {
              case ConsentResult.Granted   =>
                ZIO.succeed(Response.json(ConsentResponse("granted").toJson))
              case ConsentResult.Revoked   =>
                ZIO.succeed(Response.json(ConsentResponse("revoked").toJson))
              // Bad / tampered / expired link — actionable for the customer (ask for a new one).
              case ConsentResult.Invalid   =>
                ZIO.fail(ApiError.BadRequest("consent link is invalid or expired"))
              // #2453: a single-use link presented again after its grant ended, or one minted
              // before the customer withdrew. Same actionable shape, and deliberately the same
              // amount of detail for both — the metric, not the response, tells the two apart.
              case ConsentResult.LinkSpent =>
                ZIO.fail(
                  ApiError.BadRequest(
                    "this consent link has already been used — ask the assistant for a new one",
                  ),
                )
              // The link belongs to another household. Nothing was written.
              case ConsentResult.Mismatch  =>
                ZIO.fail(ApiError.Forbidden("consent link is not for this account"))
              // Dark install: the responder — and so this endpoint — doesn't exist to a caller.
              case ConsentResult.Disabled  => ZIO.fail(ApiError.NotFound("not found"))
              case ConsentResult.Error => ZIO.fail(ApiError.Internal("could not record consent"))
            }
          } yield resp
          handle.mapError(ErrorMapper.errorToResponse)
        },
    )
}
