package wifihaven.api.routes

import wifihaven.api.support.{PlainWebhook, SupportResponder}
import wifihaven.api.support.SupportResponder.{AgentActionResult, WebhookOutcome}
import zio.*
import zio.http.*
import zio.json.*

/**
 * #2200 (support intake C, epic #2197) — the responder's HTTP surface, live iff the explicit
 * `support.responderEnabled` flag is set (#2265):
 *
 *   - PUBLIC `POST /api/support/webhook` — Plain's signed new-message webhook. Unauthenticated by
 *     design (Plain has no bearer token); the `Plain-Request-Signature` HMAC verified inside
 *     [[SupportResponder.handleWebhook]] IS the authentication, exactly like the Stripe webhook
 *     (#2135). Size-capped before anything is read. A bad signature is the one security-relevant
 *     rejection (400); EVERY other outcome returns 200 so Plain does not retry-storm and the
 *     response never leaks WHY (the metric carries the outcome). That deliberately includes the
 *     FAILURES: a reject Plain refused to send (#2471 `email_reject_send_failed`) still answers
 *     200, because a retry cannot fix a workspace that will not send. The outcome vocabulary lives
 *     on [[SupportResponder.WebhookOutcome]] and is deliberately not restated here.
 *
 *   - AGENT `POST /api/support/agent/reply`, `POST /api/support/agent/issues`, `POST
 *     /api/support/agent/request-consent`, `POST /api/support/agent/escalate` (#2437 — the
 *     STRUCTURAL handoff signal: the server labels the thread + emails the operator), `GET
 *     /api/support/agent/household` — the cloud agent's ONLY side-effect channels, authenticated
 *     solely by the per-session [[wifihaven.api.support.ConsentToken]] (thread- + household-bound,
 *     consent-scoped, expiring) as `Authorization: Bearer`. No JWT, no admin session — and
 *     conversely no other route in the API accepts this token. Denials are deliberately uniform
 *     (401 "unauthorized") so a probing caller learns nothing about why.
 */
object SupportAgentRoutes {

  /** Cap the webhook body so a hostile unauthenticated caller can't stream us out of memory. */
  val MaxWebhookBytes: Long = 256 * 1024

  /** Cap agent-endpoint bodies — a reply or issue never legitimately approaches this. */
  val MaxAgentBodyBytes: Long = 64 * 1024

  // #2469: `promptVersion` is the live agent's echo of the `PROMPT_VERSION:` marker in its own
  // system prompt — a DEDICATED field, never scraped out of `markdown`, so untrusted customer text
  // cannot spoof it. Optional on the wire: an older routine (or the Managed Agents path before a
  // re-apply) omits it and is recorded `unknown` rather than rejected.
  private final case class ReplyPost(markdown: String, promptVersion: Option[String] = None)
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
              // Everything else is a 200 so Plain stops retrying — a skipped continuation, a #2307
              // static reject, a dark install, or a downstream hiccup is not the webhook's error.
              case _                               => ZIO.succeed(Response.ok)
            }
          } yield resp
          handle.mapError(ErrorMapper.errorToResponse)
        },

      // ── Agent: post the AI-attributed reply into the token-bound thread (sent) ──
      Method.POST / "api" / "support" / "agent" / "reply" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            body <- capped(req)
            post <- ZIO
              .fromEither(body.fromJson[ReplyPost])
              .mapError(ApiError.DecodeFailure.apply)
            // #2456: guard on what will ACTUALLY be posted. `agentReply` drops the agent's
            // redundant leading copies of the server-owned attribution line, so a body that is
            // nothing BUT that line reduces to nothing — and would otherwise pass this check and
            // send the customer a bare header with no answer, reported back to the agent as Ok.
            // Same shared primitive the responder uses, so the two cannot disagree on "empty".
            reply = SupportResponder.stripLeadingAttribution(post.markdown)
            _      <- ZIO.fail(ApiError.BadRequest("empty reply")).when(reply.trim.isEmpty)
            // #2469: `promptVersion` is passed THROUGH — the drift signal is recorded inside
            // `agentReply`, past the token check. This route is public, so recording it here would
            // let any anonymous POST forge `state="current"` and mask a genuinely stale routine.
            result <- responder.agentReply(bearerToken(req), reply, post.promptVersion)
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
            // #2461: on success the response carries the created issue's number + public URL so
            // the agent can quote the link back to the customer.
            resp   <- result.fold(toResponse, filed => ZIO.succeed(Response.json(filed.toJson)))
          } yield resp
          handle.mapError(ErrorMapper.errorToResponse)
        },

      // ── Agent: ASK the customer for data-access consent (#2419) ─────────────────
      // The agent's alternative to dead-ending on an account question. It carries NO body: the
      // agent supplies no text and names no thread — the server posts its OWN fixed prompt into
      // the token-bound thread. Requesting consent is not having it: this route cannot create a
      // consent record (only the customer's JWT-authenticated POST /api/support/consent can), so
      // a hijacked agent cannot widen its own data scope. #2453: the posted link is also stripped
      // out of the thread history the agent is later shown, and is single-use — so the agent can
      // neither author the prompt nor re-post the real link under our attribution.
      Method.POST / "api" / "support" / "agent" / "request-consent" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            result <- responder.agentRequestConsent(bearerToken(req))
            resp   <- toResponse(result)
          } yield resp
          handle.mapError(ErrorMapper.errorToResponse)
        },

      // ── Agent: hand this thread to a human (#2437) ───────────────────────────────
      // The STRUCTURAL escalation signal. The agent calls this instead of only writing "a human will
      // follow up" — the server then labels the token-bound thread (so the inbox is filterable) and
      // emails the operator. The body carries at most a one-line `note`; the thread, the household,
      // and the label all come from the verified token / config, so nothing here is aimable. An
      // absent or empty body is accepted (a hijack-proof escalation must not be blockable on syntax).
      Method.POST / "api" / "support" / "agent" / "escalate" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            body   <- capped(req)
            note   <- escalationNote("support", body)
            result <- responder.agentEscalate(bearerToken(req), note)
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
    // #2461: a metric-only distinction — both success values are the same plain `ok` to the caller.
    // Only the issue-filing route can produce it, and that route answers the richer FiledIssue body
    // directly (its success never reaches here), so this case exists for exhaustivity.
    case AgentActionResult.OkNoLink    => ZIO.succeed(Response.json("""{"ok":true}"""))
    // #2458: same shape — the duplicate-matched success also only arises on the issue-filing route,
    // which answers its own FiledIssue body (carrying the pre-existing issue's number/url).
    case AgentActionResult.OkDuplicate => ZIO.succeed(Response.json("""{"ok":true}"""))
    // Uniform denial: bad token, expired token, missing header — the caller learns nothing more.
    case AgentActionResult.Denied      => ZIO.fail(ApiError.Unauthorized("unauthorized"))
    case AgentActionResult.NoConsent   => ZIO.fail(ApiError.Forbidden("no data consent"))
    // #2454: this session holds the consented-read scope, so it cannot file into the PUBLIC repo.
    // Unlike the token denials this one IS explained — the caller is our own agent, and the message
    // tells it what to do instead (describe the symptom, or escalate) rather than leaving it to
    // retry a call that can never succeed for this session.
    case AgentActionResult.DataSession =>
      ZIO.fail(
        ApiError.Forbidden(
          "issue filing is unavailable in a data-access session — describe the symptom " +
            "without account data in a later session, or escalate to a human",
        ),
      )
    case AgentActionResult.RateLimited => ZIO.fail(ApiError.RateLimited("rate limited"))
    // Dark install: the endpoints don't exist as far as a caller can tell.
    case AgentActionResult.Disabled    => ZIO.fail(ApiError.NotFound("not found"))
    case AgentActionResult.Error       => ZIO.fail(ApiError.Internal("upstream error"))
  }
}
