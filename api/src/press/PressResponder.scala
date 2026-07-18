package wifihaven.api.press

import wifihaven.api.PressConfig
import wifihaven.api.auth.RateLimiter
import wifihaven.api.metrics.AppMetrics
import wifihaven.api.notify.{EmailOutcome, EmailSender}
import wifihaven.shared.Clock
import zio.*

/**
 * #2203 (press intake, epic #2197) — the Claude PRESS/PR responder for the PUBLIC, unauthenticated
 * press inbox. Reuses the #2200 cloud-agent dispatch + token-callback shape and the shared
 * [[wifihaven.api.support.ManagedAgents]] transport, with three deliberate differences that follow
 * from the trust model:
 *
 *   1. **No household gate.** Press arrives at `press@wifihaven.net` via a Cloudflare Email Worker
 *      (deploy/press-worker/) that HMAC-signs and POSTs the message to `/api/press/inbound`, not
 *      the #2199 identified widget. There is no household to resolve: EVERY signature-valid press
 *      message is dispatched (cost/abuse is bounded by rate limits, not an auth gate). 2. **No
 *      household data access.** The press agent's [[PressToken]] is reply-target-bound only — no
 *      household, no data scope — and there is no household-read endpoint. The no-customer-data
 *      guarantee is STRUCTURAL, not prompt-level. 3. **Autonomous email reply,
 *      destination-locked.** [[agentReply]] EMAILS the agent's copy to the original sender via the
 *      #578 [[EmailSender]] (operator decision 2026-07-17: reply directly to the journalist, no
 *      approval step). The recipient comes FROM the verified token, so a hijacked agent cannot
 *      redirect the reply — the load-bearing control that makes autonomous send safe.
 *
 * Both halves are behind the EXPLICIT `press.responderEnabled` flag (#2265 — no dark-by-default:
 * enabling without the full config chain, including outbound email, refuses to boot; disabling is a
 * named, logged, health-visible state). External I/O (Anthropic, Resend) is behind swappable traits
 * stubbed in specs; the Clock is injected.
 */
final case class PressResponder(
    cfg: PressConfig,
    email: EmailSender,
    dispatcher: PressAgentDispatcher,
    clock: Clock,
    // Cost guardrails: press is public + unauthenticated, so there is no auth gate ahead of the
    // token-billing dispatch — the per-thread (per-sender) + global rate caps ARE the abuse control.
    dispatchSenderLimiter: RateLimiter,
    dispatchGlobalLimiter: RateLimiter,
) {
  import PressResponder.*

  // ── Inbound: the signed press webhook (from the Cloudflare Email Worker) ─────

  /**
   * Handle one press inbound delivery. Never fails — every path resolves to a metered
   * [[WebhookOutcome]] the route maps to a status. Signature verification (against the press
   * webhook secret) runs FIRST over the raw body; nothing is parsed or acted on for an
   * unsigned/forged payload.
   */
  def handleInbound(rawBody: String, sigHeader: Option[String]): UIO[WebhookOutcome] =
    if !cfg.responderEnabled then meter(WebhookOutcome.Disabled)
    else
      PressInbound.verifyAndParse(rawBody, sigHeader, cfg.webhookSecretTrimmed) match {
        case Left(PressInbound.VerifyError.MissingSignature) | Left(
              PressInbound.VerifyError.BadSignature,
            ) =>
          meter(WebhookOutcome.InvalidSignature)
        case Left(PressInbound.VerifyError.MalformedPayload) =>
          meter(WebhookOutcome.Malformed)
        case Right(event)                                    =>
          dispatchFor(event).flatMap(meter)
      }

  /**
   * Dispatch a press cloud-agent session. No household gate (press is public); the only
   * pre-dispatch controls are the token-cost rate caps (per-sender + global). The message text is
   * UNTRUSTED and rides to the agent as delimited data only.
   */
  private def dispatchFor(event: PressInboundEvent): UIO[WebhookOutcome] =
    // Short-circuit (mirrors #2261): draw the global bucket only when the per-sender cap allowed, so
    // one spamming sender can't drain the shared budget and lock everyone else out.
    dispatchSenderLimiter.tryAcquire(s"from:${event.from}").flatMap { senderOk =>
      if !senderOk then ZIO.succeed(WebhookOutcome.RateLimited)
      else
        dispatchGlobalLimiter.tryAcquire("global").flatMap { globalOk =>
          if !globalOk then ZIO.succeed(WebhookOutcome.RateLimited)
          else dispatch(event)
        }
    }

  private def dispatch(event: PressInboundEvent): UIO[WebhookOutcome] =
    for {
      now <- clock.instant
      // The reply DESTINATION + subject are baked into the token here — the agent never chooses
      // them. `from` is the sender's address the Worker extracted from the inbound email.
      token = PressToken.mint(
        replyTo = event.from,
        subject = event.subject,
        now = now,
        ttl = cfg.agentTokenTtl,
        secret = cfg.agentTokenSecretTrimmed,
      )
      // Audit trail for every mint — the reply target, never the token.
      _       <- ZIO.logInfo(s"press: minted agent token for reply-to=${event.from}")
      outcome <- dispatcher.dispatch(
        PressDispatch(
          from = event.from,
          subject = event.subject,
          agentToken = token,
          pressMessage = event.messageText,
        ),
      )
    } yield outcome match {
      case wifihaven.api.support.DispatchOutcome.Dispatched => WebhookOutcome.Dispatched
      case wifihaven.api.support.DispatchOutcome.Disabled   => WebhookOutcome.Disabled
      case wifihaven.api.support.DispatchOutcome.Error      => WebhookOutcome.Error
    }

  private def meter(o: WebhookOutcome): UIO[WebhookOutcome] =
    AppMetrics.pressAiDraft(WebhookOutcome.label(o)).as(o)

  // ── Agent-facing: the token-authenticated email-reply callback ──────────────

  /**
   * Email the agent's reply to the token-bound sender (autonomous send, 2026-07-17). The recipient
   * + subject come FROM the verified token, so a hijacked agent cannot aim the reply at another
   * address; the request body carries only the reply text.
   */
  def agentReply(bearer: Option[String], markdown: String): UIO[AgentActionResult] =
    if !cfg.agentEndpointsEnabled then
      AppMetrics.pressAgentAction("reply", "disabled").as(AgentActionResult.Disabled)
    else
      clock.instant.flatMap { now =>
        bearer.map(_.trim).filter(_.nonEmpty) match {
          case None        =>
            AppMetrics.pressAgentAction("reply", "denied").as(AgentActionResult.Denied)
          case Some(token) =>
            PressToken.verify(token, now, cfg.agentTokenSecretTrimmed) match {
              case Left(_)       =>
                AppMetrics.pressAgentAction("reply", "denied").as(AgentActionResult.Denied)
              case Right(claims) =>
                val subject = replySubject(claims.subject)
                email.send(claims.replyTo, subject, htmlBody(markdown)).flatMap {
                  case EmailOutcome.Sent     =>
                    AppMetrics.pressAgentAction("reply", "ok").as(AgentActionResult.Ok)
                  case EmailOutcome.Disabled =>
                    AppMetrics.pressAgentAction("reply", "disabled").as(AgentActionResult.Disabled)
                  case EmailOutcome.Failed   =>
                    AppMetrics.pressAgentAction("reply", "error").as(AgentActionResult.Error)
                }
            }
        }
      }
}

object PressResponder {

  /** `Re:`-prefix the original subject, without double-prefixing a subject that already has one. */
  def replySubject(original: String): String = {
    val s = original.trim
    if s.isEmpty then "Re: your message to WifiHaven"
    else if s.toLowerCase.startsWith("re:") then s
    else s"Re: $s"
  }

  /**
   * Wrap the agent's plain-text/markdown reply in minimal, HTML-escaped body so [[EmailSender]]
   * (which sends `htmlBody`) renders it safely — the reply text is HTML-escaped so nothing the
   * agent wrote can inject markup into the outgoing email. Newlines become paragraph breaks.
   */
  def htmlBody(markdown: String): String = {
    val escaped = markdown
      .replace("&", "&amp;")
      .replace("<", "&lt;")
      .replace(">", "&gt;")
    val paras   = escaped
      .split("\r?\n\r?\n")
      .toList
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(p => s"<p>${p.replace("\n", "<br>")}</p>")
      .mkString("\n")
    if paras.isEmpty then "<p></p>" else paras
  }

  /** Bounded outcome enum for the webhook path — the `press_ai_draft_total{outcome}` label set. */
  enum WebhookOutcome   {
    case Dispatched
    case RateLimited
    case InvalidSignature
    case Malformed
    case Disabled
    case Error
  }
  object WebhookOutcome {
    def label(o: WebhookOutcome): String = o match {
      case Dispatched       => "dispatched"
      case RateLimited      => "rate_limited"
      case InvalidSignature => "invalid_signature"
      case Malformed        => "malformed"
      case Disabled         => "disabled"
      case Error            => "error"
    }
  }

  /**
   * Bounded result enum for the press agent endpoint — the `press_agent_action_total` outcome set.
   * `Denied` is any token failure (missing / tampered / expired — uniform to the caller).
   */
  enum AgentActionResult   {
    case Ok
    case Denied
    case RateLimited
    case Disabled
    case Error
  }
  object AgentActionResult {
    def label(r: AgentActionResult): String = r match {
      case Ok          => "ok"
      case Denied      => "denied"
      case RateLimited => "rate_limited"
      case Disabled    => "disabled"
      case Error       => "error"
    }
  }
}
