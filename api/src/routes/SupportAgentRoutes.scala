package wifihaven.api.routes

import wifihaven.api.support.{PlainWebhook, SupportResponder}
import wifihaven.api.support.SupportResponder.{AgentActionResult, WebhookOutcome}
import zio.*
import zio.http.*
import zio.json.*

/**
 * #2200 (support intake C, epic #2197) — the responder's HTTP surface, all config-gated dark:
 *
 *   - PUBLIC `POST /api/support/webhook` — Plain's signed new-message webhook. Unauthenticated by
 *     design (Plain has no bearer token); the `Plain-Request-Signature` HMAC verified inside
 *     [[SupportResponder.handleWebhook]] IS the authentication, exactly like the Stripe webhook
 *     (#2135). Size-capped before anything is read. A bad signature is the one security-relevant
 *     rejection (400); every other outcome — dispatched, skipped-unauthenticated (cold email, the
 *     UI-origin gate), disabled, malformed, downstream error — returns 200 so Plain does not
 *     retry-storm and the response never leaks WHY (the metric carries the outcome).
 *
 *   - AGENT `POST /api/support/agent/reply`, `POST /api/support/agent/issues`, `GET
 *     /api/support/agent/household` — the cloud agent's ONLY side-effect channels, authenticated
 *     solely by the per-session [[wifihaven.api.support.ConsentToken]] (thread- + household-bound,
 *     consent-scoped, short-TTL) as `Authorization: Bearer`. No JWT, no admin session — and
 *     conversely no other route in the API accepts this token. Denials are deliberately uniform
 *     (401 "unauthorized") so a probing caller learns nothing about why.
 */
object SupportAgentRoutes {

  /** Cap the webhook body so a hostile unauthenticated caller can't stream us out of memory. */
  val MaxWebhookBytes: Long = 256 * 1024

  /** Cap agent-endpoint bodies — a reply or issue never legitimately approaches this. */
  val MaxAgentBodyBytes: Long = 64 * 1024

  private final case class ReplyPost(markdown: String)
  private object ReplyPost { given JsonCodec[ReplyPost] = DeriveJsonCodec.gen[ReplyPost] }

  private final case class IssuePost(title: String, body: String)
  private object IssuePost { given JsonCodec[IssuePost] = DeriveJsonCodec.gen[IssuePost] }

  def routes(responder: SupportResponder): Routes[Any, Response] =
    Routes(
      // ── Public: signature-verified Plain webhook → gate → cloud-agent dispatch ──
      Method.POST / "api" / "support" / "webhook" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            // The RAW body is what the signature was computed over — read it verbatim, never
            // re-serialize (a re-encode would change the bytes and break the MAC).
            body <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            _    <- ZIO
              .fail(ApiError.BadRequest("payload too large"))
              .when(body.length.toLong > MaxWebhookBytes)
            sig = req.headers.get(PlainWebhook.SignatureHeader)
            outcome <- responder.handleWebhook(body, sig)
            resp    <- outcome match {
              case WebhookOutcome.InvalidSignature =>
                ZIO.fail(ApiError.BadRequest("invalid signature"))
              // Everything else is a 200 so Plain stops retrying — a cold thread (the UI-origin
              // gate), a dark install, or a downstream hiccup is not the webhook's error.
              case _                               => ZIO.succeed(Response.ok)
            }
          } yield resp
          handle.mapError(ErrorMapper.errorToResponse)
        },

      // ── Agent: post the AI-attributed reply into the token-bound thread (sent) ──
      Method.POST / "api" / "support" / "agent" / "reply" ->
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

      // ── Agent: file a scrubbed, rate-limited, support-agent-labeled GitHub issue ─
      Method.POST / "api" / "support" / "agent" / "issues" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            body   <- capped(req)
            post   <- ZIO
              .fromEither(body.fromJson[IssuePost])
              .mapError(ApiError.DecodeFailure.apply)
            _      <- ZIO
              .fail(ApiError.BadRequest("empty issue"))
              .when(post.title.trim.isEmpty)
            result <- responder.agentFileIssue(bearerToken(req), post.title, post.body)
            resp   <- toResponse(result)
          } yield resp
          handle.mapError(ErrorMapper.errorToResponse)
        },

      // ── Agent: the consented single-household read ───────────────────────────────
      Method.GET / "api" / "support" / "agent" / "household" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] =
            responder.agentHousehold(bearerToken(req)).flatMap {
              case Right(summary)                    => ZIO.succeed(Response.json(summary.toJson))
              // No-consent reads are the one 403: the token is valid but lacks the data scope.
              // Token failures stay in `toResponse` → uniform 401.
              case Left(AgentActionResult.NoConsent) =>
                ZIO.fail(ApiError.Forbidden("no data consent"))
              case Left(other)                       => toResponse(other)
            }
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
    case AgentActionResult.NoConsent   => ZIO.fail(ApiError.Forbidden("no data consent"))
    case AgentActionResult.RateLimited => ZIO.fail(ApiError.RateLimited("rate limited"))
    // Dark install: the endpoints don't exist as far as a caller can tell.
    case AgentActionResult.Disabled    => ZIO.fail(ApiError.NotFound("not found"))
    case AgentActionResult.Error       => ZIO.fail(ApiError.Internal("upstream error"))
  }
}
