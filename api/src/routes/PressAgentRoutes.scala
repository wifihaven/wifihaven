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
 *   - AGENT `POST /api/press/agent/reply` and `POST /api/press/agent/escalate` (#2437 — the
 *     STRUCTURAL handoff signal that emails the operator, since press has no inbox), authenticated
 *     solely by the per-session reply-target-bound [[wifihaven.api.press.PressToken]] as
 *     `Authorization: Bearer`. Neither takes a recipient: both derive the peer from the token.
 *     Denials are uniform (401) so a prober learns nothing.
 */
object PressAgentRoutes {

  /** Cap the webhook body so a hostile unauthenticated caller can't stream us out of memory. */
  val MaxWebhookBytes: Long = 256 * 1024

  /** Cap agent-endpoint bodies — a reply never legitimately approaches this. */
  val MaxAgentBodyBytes: Long = 64 * 1024

  // #2469: `promptVersion` is the live agent's echo of the `PROMPT_VERSION:` marker in its own
  // system prompt — a DEDICATED field, never scraped out of `markdown`, so untrusted sender text
  // cannot spoof it. Optional on the wire: an older routine (or the Managed Agents path before a
  // re-apply) omits it and is recorded `unknown` rather than rejected.
  private final case class ReplyPost(markdown: String, promptVersion: Option[String] = None)
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
            // #2469: `promptVersion` is passed THROUGH — the drift signal is recorded inside
            // `agentReply`, past the token check. This route is public, so recording it here would
            // let any anonymous POST forge `state="current"` and mask a genuinely stale routine.
            result <- responder.agentReply(bearerToken(req), post.markdown, post.promptVersion)
            resp   <- toResponse(result)
          } yield resp
          handle.mapError(ErrorMapper.errorToResponse)
        },

      // ── Agent: hand this inquiry to a human (#2437) ─────────────────────────────
      // The STRUCTURAL escalation signal for press. Press has no inbox, so this notice IS the handoff:
      // the agent calls it alongside its courteous holding reply, and the server emails the operator
      // the sender, subject, the original message (re-read by the id on the SIGNED token) and the
      // agent's note. The peer identity is token-derived — a hijacked agent cannot report a different
      // journalist any more than it can redirect the reply.
      Method.POST / "api" / "press" / "agent" / "escalate" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            body   <- capped(req)
            note   <- escalationNote("press", body)
            result <- responder.agentEscalate(bearerToken(req), note)
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
