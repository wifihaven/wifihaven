package wifihaven.api.routes

import wifihaven.api.press.{PressInbound, PressResponder}
import wifihaven.api.press.PressResponder.{AgentActionResult, WebhookOutcome}
import zio.*
import zio.http.*
import zio.json.*

/**
 * #2203 (press intake, epic #2197) — the press responder's HTTP surface, live iff the explicit
 * `press.responderEnabled` flag is set (#2265). Deliberately NARROWER than #2200's
 * [[SupportAgentRoutes]]: press is public + untrusted, so there is NO household-read endpoint and
 * NO issue-filing endpoint — the press agent can do exactly one thing, email a reply to the sender
 * the token is bound to.
 *
 *   - PUBLIC `POST /api/press/inbound` — the Cloudflare Email Worker's signed inbound POST (a press
 *     email routed through Cloudflare Email Routing). Unauthenticated by design; the
 *     `X-WifiHaven-Signature` HMAC verified inside [[PressResponder.handleInbound]] IS the
 *     authentication (same shape as the Stripe / support webhooks). Size-capped before anything is
 *     read. A bad signature is the one security-relevant rejection (400); every other outcome
 *     returns 200 so the Worker does not retry and the response never leaks WHY (the metric carries
 *     the outcome).
 *
 *   - AGENT `POST /api/press/agent/reply` — the press agent's ONLY side-effect channel,
 *     authenticated solely by the per-session reply-target-bound [[wifihaven.api.press.PressToken]]
 *     as `Authorization: Bearer`. Denials are uniform (401) so a prober learns nothing.
 */
object PressAgentRoutes {

  /** Cap the webhook body so a hostile unauthenticated caller can't stream us out of memory. */
  val MaxWebhookBytes: Long = 256 * 1024

  /** Cap agent-endpoint bodies — a reply never legitimately approaches this. */
  val MaxAgentBodyBytes: Long = 64 * 1024

  private final case class ReplyPost(markdown: String)
  private object ReplyPost { given JsonCodec[ReplyPost] = DeriveJsonCodec.gen[ReplyPost] }

  def routes(responder: PressResponder): Routes[Any, Response] =
    Routes(
      // ── Public: signature-verified press inbound → rate-cap → cloud-agent dispatch ──
      Method.POST / "api" / "press" / "inbound" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            // The RAW body is what the signature was computed over — read it verbatim, never
            // re-serialize (a re-encode would change the bytes and break the MAC).
            body <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            _    <- ZIO
              .fail(ApiError.BadRequest("payload too large"))
              .when(body.length.toLong > MaxWebhookBytes)
            sig = req.headers.get(PressInbound.SignatureHeader)
            outcome <- responder.handleInbound(body, sig)
            resp    <- outcome match {
              case WebhookOutcome.InvalidSignature =>
                ZIO.fail(ApiError.BadRequest("invalid signature"))
              // Everything else is a 200 so the Worker stops retrying — a dark install, a rate-cap,
              // a malformed body, or a downstream hiccup is not the webhook's error.
              case _                               => ZIO.succeed(Response.ok)
            }
          } yield resp
          handle.mapError(ErrorMapper.errorToResponse)
        },

      // ── Agent: email the reply to the token-bound sender (autonomous, destination-locked) ──
      Method.POST / "api" / "press" / "agent" / "reply" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            body   <- capped(req)
            post   <- ZIO
              .fromEither(body.fromJson[ReplyPost])
              .mapError(ApiError.DecodeFailure.apply)
            _      <- ZIO
              .fail(ApiError.BadRequest("empty reply"))
              .when(post.markdown.trim.isEmpty)
            result <- responder.agentReply(bearerToken(req), post.markdown)
            resp   <- toResponse(result)
          } yield resp
          handle.mapError(ErrorMapper.errorToResponse)
        },
    )

  private def capped(req: Request): ZIO[Any, ApiError, String] =
    req.body.asString
      .orElseFail(ApiError.BadRequest(""))
      .filterOrFail(
        _.length.toLong <= MaxAgentBodyBytes,
      )(ApiError.BadRequest("payload too large"))

  private def toResponse(r: AgentActionResult): ZIO[Any, ApiError, Response] = r match {
    case AgentActionResult.Ok          => ZIO.succeed(Response.json("""{"ok":true}"""))
    // Uniform denial: bad token, expired token, missing header — the caller learns nothing more.
    case AgentActionResult.Denied      => ZIO.fail(ApiError.Unauthorized("unauthorized"))
    case AgentActionResult.RateLimited => ZIO.fail(ApiError.RateLimited("rate limited"))
    // Dark install: the endpoint doesn't exist as far as a caller can tell.
    case AgentActionResult.Disabled    => ZIO.fail(ApiError.NotFound("not found"))
    case AgentActionResult.Error       => ZIO.fail(ApiError.Internal("upstream error"))
  }
}
