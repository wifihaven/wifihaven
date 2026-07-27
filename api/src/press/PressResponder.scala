package wifihaven.api.press

import wifihaven.api.PressConfig
import wifihaven.api.auth.RateLimiter
import wifihaven.api.db.PressMessageRepo
import wifihaven.api.metrics.AppMetrics
import wifihaven.api.notify.{
  EmailOutcome,
  EmailSender,
  EscalationChannel,
  EscalationKind,
  EscalationNotice,
  Notifier,
}
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
    // #2296: the press correspondence log. Recording is best-effort AUDIT — every write is fail-open
    // (a DB error is logged + metered and swallowed) so it can never break the inbound webhook or the
    // autonomous reply send; the reply path is authoritative, the log is a side record.
    pressLog: PressMessageRepo,
    clock: Clock,
    // Cost guardrails: press is public + unauthenticated, so there is no auth gate ahead of the
    // token-billing dispatch — the per-thread (per-sender) + global rate caps ARE the abuse control.
    dispatchSenderLimiter: RateLimiter,
    dispatchGlobalLimiter: RateLimiter,
    // #2437: the #578 operator-notification seam, used for ESCALATIONS ONLY (#2480). Press has NO
    // inbox — `press@` goes to a Cloudflare Email Worker — so a handoff would otherwise reach nobody:
    // the #2296 correspondence log at `/press` is a pull surface, and an escalation is precisely the
    // case where nothing would tell the operator to go look. Routine traffic is NOT mailed.
    notifier: Notifier,
    // #2437: bounds how often ONE sender's session can page the operator, so an agent stuck in a loop
    // (or a prompt-injected one) cannot turn our own alert mailbox into a firehose.
    escalateLimiter: RateLimiter,
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
    // one spamming sender can't drain the shared budget and lock everyone else out. The per-sender
    // key is best-effort (the From is attacker-controlled and trivially rotated); the GLOBAL cap is
    // the real ceiling on token spend for this public endpoint.
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
      now            <- clock.instant
      // #2296: record the inbound press email BEFORE dispatch so the reply can pair to it. Fail-open
      // — a recording error yields id 0 ("no inbound row") and never blocks the dispatch.
      pressMessageId <- recordInbound(event)
      // The reply DESTINATION + subject are baked into the token here — the agent never chooses
      // them. `from` is the sender's address the Worker extracted from the inbound email. The
      // recorded inbound row id (#2296) rides the SIGNED token so the reply callback can pair the
      // outbound row to this inquiry without trusting anything the agent sends.
      token = PressToken.mint(
        replyTo = event.from,
        subject = event.subject,
        pressMessageId = pressMessageId,
        // #2451: the journalist's own Message-ID, so the reply can carry In-Reply-To/References and
        // thread under their original. It rides the SIGNED payload like every other field — a
        // hijacked agent can neither forge it nor graft its reply onto another conversation. Empty
        // when the inbound carried no Message-ID; the reply then sends unthreaded. Truncated so an
        // attacker-controlled field cannot inflate the bearer token without bound. Truncation can
        // never re-point a thread: a msg-id ends at its first `>`, so either that `>` survives and
        // the id is intact, or it doesn't and the value fails the shape check at send time and the
        // reply goes out unthreaded.
        inboundMessageId = event.messageId.take(MaxMessageIdChars),
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
      // #2480: a routine inbound emails NOBODY. #2446 sent an operator FYI here on the premise that
      // "press has no inbox and no SPA view of `press_messages`" — false since #2296: `/press`
      // (web/src/pages/PressPage.tsx over `GET /api/press/messages`) IS the operator's surface for
      // AI-handled press traffic, and an email per message turns a browsable log into inbox noise.
      // The other half of that premise — a silent dispatch FAILURE — is not re-solved here either:
      // #2416 made dispatch failures fail-loud and attributed on `press_dispatch_total{outcome,
      // transport}`, so a failure pages through metrics/alerting rather than by mailing the
      // operator about every SUCCESS on the off chance one failed. The operator mailbox now means
      // exactly one thing: a human must act — see `escalate` below.
    } yield outcome match {
      case wifihaven.api.support.DispatchOutcome.Dispatched  => WebhookOutcome.Dispatched
      case wifihaven.api.support.DispatchOutcome.Disabled    => WebhookOutcome.Disabled
      case wifihaven.api.support.DispatchOutcome.Error       => WebhookOutcome.Error
      // #2416: a permanent 4xx at the agent boundary — already logged at ERROR with the fix named by
      // the SHARED CloudAgentObservability classifier. Same `outcome=error`, distinct `reason=config`.
      case wifihaven.api.support.DispatchOutcome.ConfigError => WebhookOutcome.ConfigError
    }

  // #2416: `reason` rides alongside `outcome` on every sample (`none` when there is nothing to
  // attribute), both bounded by WebhookOutcome — no per-sender / per-thread label ever.
  private def meter(o: WebhookOutcome): UIO[WebhookOutcome] =
    AppMetrics.pressAiDraft(WebhookOutcome.label(o), WebhookOutcome.reason(o)).as(o)

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
                val subject   = replySubject(claims.subject)
                // #2451: normalize HERE via the same primitive the transport uses, so the log line
                // below reports what will actually go on the wire rather than a second opinion.
                val inReplyTo =
                  EmailSender.threadingId(Some(claims.inboundMessageId).filter(_.nonEmpty))
                // #2407: send FROM the press identity (not the shared #578 alerts@ notification
                // sender). From and Reply-To are SEPARATE addresses: the From must sit on a
                // Resend-verified sending domain (the apex — staging borrows it as press-staging@),
                // while Reply-To names the press mailbox the Cloudflare Email Worker actually
                // watches, so a journalist's human follow-up threads back into the pipeline. The
                // recipient stays server-locked to the token's `replyTo` — only the From/Reply-To
                // identity changes, never the destination.
                // #2451: the original bug was that 100% of replies went out unthreaded and NOTHING
                // surfaced it, so say so per reply. `threaded=false` is legitimate on its own (the
                // inbound carried no Message-ID, or it wasn't a well-formed msg-id), but a
                // sustained run of it is the signal that this fix has regressed. `legacyToken` is
                // reported SEPARATELY rather than folded into `threaded=false`, because it answers
                // a different question: it is what has to go quiet before #2459 deletes the
                // tolerant pre-#2451 payload arm. The Message-ID itself is not logged — it is
                // attacker-controlled sender content.
                ZIO.logInfo(
                  s"press: sending reply to ${claims.replyTo} " +
                    s"(threaded=${inReplyTo.isDefined}, legacyToken=${claims.legacyPayload})",
                ) *>
                  email
                    .sendAs(
                      from = cfg.fromAddressTrimmed,
                      replyTo = Some(cfg.replyToAddressTrimmed),
                      to = claims.replyTo,
                      subject = subject,
                      htmlBody = htmlBody(markdown),
                      // #2451: thread the reply under the journalist's original — the transport
                      // renders this into In-Reply-To AND References. Already normalized above; a
                      // missing or malformed Message-ID resolves to None, so the reply still sends,
                      // just unthreaded.
                      inReplyTo = inReplyTo,
                    )
                    .flatMap { sendResult =>
                      // #2296: record the outbound reply as AUDIT (fail-open) AFTER the send, pairing it
                      // to the inbound row via the token's pressMessageId. Only Sent/Failed are real
                      // send attempts worth logging; a Disabled send (dark install) emitted no email.
                      val record = sendResult match {
                        case EmailOutcome.Sent => recordOutbound(claims, subject, markdown, "sent")
                        case EmailOutcome.Failed   =>
                          recordOutbound(claims, subject, markdown, "failed")
                        case EmailOutcome.Disabled => ZIO.unit
                      }
                      record *> (sendResult match {
                        case EmailOutcome.Sent     =>
                          AppMetrics.pressAgentAction("reply", "ok").as(AgentActionResult.Ok)
                        case EmailOutcome.Disabled =>
                          AppMetrics
                            .pressAgentAction("reply", "disabled")
                            .as(AgentActionResult.Disabled)
                        case EmailOutcome.Failed   =>
                          AppMetrics.pressAgentAction("reply", "error").as(AgentActionResult.Error)
                      })
                    }
            }
        }
      }

  // ── #2437: escalation — the handoff that actually reaches a human ────────────

  /**
   * The press agent hands this inquiry to a human (#2437) — a journalist asking to schedule a call,
   * anything the agent cannot answer from public information. The agent still emails its courteous
   * holding reply via [[agentReply]]; THIS call is what makes the promise true, notifying the
   * operator out-of-band via the #578 [[Notifier]] with the sender, the subject, the original
   * message (re-read from the correspondence log by the id on the SIGNED token, never from the
   * request), and the agent's one-line reason.
   *
   * Escalation is STRUCTURAL: only a call to this endpoint with a valid token registers one.
   * Nothing text-matches the reply or the inbound email — a journalist who writes "a team member
   * will follow up" in their own message has escalated nothing.
   *
   * The `note` is AGENT-AUTHORED and rides into the operator email HTML-escaped; it selects
   * nothing. The sender identity comes from the token, so a hijacked agent cannot make us report a
   * different peer (the same destination-locking that makes the autonomous reply safe).
   */
  def agentEscalate(bearer: Option[String], note: Option[String]): UIO[AgentActionResult] =
    if !cfg.agentEndpointsEnabled then
      AppMetrics.pressAgentAction("escalate", "disabled").as(AgentActionResult.Disabled)
    else
      clock.instant.flatMap { now =>
        bearer.map(_.trim).filter(_.nonEmpty) match {
          case None        =>
            AppMetrics.pressAgentAction("escalate", "denied").as(AgentActionResult.Denied)
          case Some(token) =>
            PressToken.verify(token, now, cfg.agentTokenSecretTrimmed) match {
              case Left(_)       =>
                AppMetrics.pressAgentAction("escalate", "denied").as(AgentActionResult.Denied)
              case Right(claims) =>
                escalateLimiter.tryAcquire(s"escalate:${claims.replyTo}").flatMap { ok =>
                  if !ok then
                    AppMetrics
                      .pressAgentAction("escalate", "rate_limited")
                      .as(AgentActionResult.RateLimited)
                  else escalate(claims, note)
                }
            }
        }
      }

  private def escalate(
      claims: PressToken.Claims,
      note: Option[String],
  ): UIO[AgentActionResult] =
    for {
      // Audit trail for every escalation — the peer + the log row, never the token.
      _    <- ZIO.logInfo(
        s"press: agent ESCALATED reply-to=${claims.replyTo} pressMessageId=${claims.pressMessageId}",
      )
      // The original inquiry, re-read from the audit log by the id the SIGNED token carries — so the
      // operator sees what the journalist actually wrote, not a copy the agent could have edited.
      // Best-effort: a missing/unreadable row degrades to a pointer, never blocks the notice.
      body <- inboundBody(claims.pressMessageId)
      _    <- notifier.escalation(
        EscalationNotice(
          channel = EscalationChannel.Press,
          kind = EscalationKind.Escalated,
          sender = claims.replyTo,
          subject = claims.subject,
          body = body,
          agentNote = note.map(_.trim).filter(_.nonEmpty),
          reference =
            if claims.pressMessageId > 0 then s"press_messages id=${claims.pressMessageId}"
            else "press_messages row unavailable",
        ),
      )
      r    <- AppMetrics.pressAgentAction("escalate", "ok").as(AgentActionResult.Ok)
    } yield r

  /**
   * The inbound message text for a recorded press row, or a pointer when it cannot be read (id 0
   * because the fail-open inbound recording missed, the row is gone, or a DB blip). Never fails:
   * the operator notice matters more than the quote inside it.
   */
  private def inboundBody(pressMessageId: Long): UIO[String] =
    if pressMessageId <= 0 then ZIO.succeed(PressResponder.MissingInboundBody)
    else
      pressLog
        .findById(pressMessageId)
        .map(_.map(_.body).getOrElse(PressResponder.MissingInboundBody))
        .catchAll(e =>
          ZIO
            .logWarning(s"press: escalation body lookup failed (fail-open): ${e.getMessage}")
            .as(PressResponder.MissingInboundBody),
        )

  // ── #2296: fail-open correspondence-log recording ────────────────────────────

  /**
   * Record the inbound press email; returns its new row id, or 0 on any DB error. Fail-open: a
   * recording miss is logged + metered (`press_message_recorded_total{direction=inbound}`) and
   * swallowed so the dispatch always proceeds (the token then carries id 0 = "no inbound row").
   */
  private def recordInbound(event: PressInboundEvent): UIO[Long] =
    pressLog
      .recordInbound(event.from, event.subject, event.messageText, event.messageId)
      .flatMap(id => AppMetrics.pressMessageRecorded("inbound", "ok").as(id))
      .catchAll(e =>
        ZIO.logWarning(s"press: inbound recording failed (fail-open): ${e.getMessage}") *>
          AppMetrics.pressMessageRecorded("inbound", "error").as(0L),
      )

  /**
   * Record the outbound AI reply as audit, paired to its inbound row. `subject` is the SAME
   * `Re:`-subject that was emailed (threaded in, not re-derived). Fail-open: a recording error is
   * logged + metered and swallowed — the reply has already been emailed and that is authoritative.
   * `inReplyTo` is omitted when the token carries id 0 (the inbound insert had failed), so the
   * outbound row never dangles against a non-existent FK.
   */
  private def recordOutbound(
      claims: PressToken.Claims,
      subject: String,
      markdown: String,
      outcome: String,
  ): UIO[Unit] =
    pressLog
      .recordOutbound(
        peerEmail = claims.replyTo,
        subject = subject,
        body = markdown,
        inReplyTo = Option.when(claims.pressMessageId > 0)(claims.pressMessageId),
        outcome = outcome,
      )
      .flatMap(_ => AppMetrics.pressMessageRecorded("outbound", "ok"))
      .catchAll(e =>
        ZIO.logWarning(s"press: outbound recording failed (fail-open): ${e.getMessage}") *>
          AppMetrics.pressMessageRecorded("outbound", "error"),
      )
}

object PressResponder {

  /**
   * #2451 — cap on the inbound `Message-ID` carried on the token, so an attacker-controlled field
   * cannot inflate the bearer token without bound. Reuses
   * [[wifihaven.api.notify.EmailSender.MaxThreadingIdChars]] rather than picking a second number:
   * that is the longest id that can be rendered into an RFC 5322 header line at all, so anything
   * beyond it could never thread regardless.
   */
  val MaxMessageIdChars: Int = EmailSender.MaxThreadingIdChars

  /**
   * #2437 — what the escalation notice quotes when the inbound row cannot be read (the fail-open
   * recording missed, or the row is gone). Says so plainly rather than sending an empty quote block
   * that reads like the journalist wrote nothing.
   */
  val MissingInboundBody: String =
    "(the original message could not be read back from the press correspondence log — check the " +
      "press@ mailbox / press_messages)"

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

    /** A TRANSIENT cloud-agent dispatch failure (transport / timeout / 5xx) — may self-heal. */
    case Error

    /**
     * #2416 — a PERMANENT cloud-agent dispatch failure: a 4xx from the Anthropic boundary (revoked
     * key, wrong agent-or-routine id, stale beta header). Labels as `outcome=error` exactly like
     * [[Error]] (so existing panels and alerts are unchanged) but carries `reason=config`, and the
     * shared dispatcher logged it at ERROR with the likely fix named inline.
     */
    case ConfigError
  }
  object WebhookOutcome {
    def label(o: WebhookOutcome): String = o match {
      case Dispatched          => "dispatched"
      case RateLimited         => "rate_limited"
      case InvalidSignature    => "invalid_signature"
      case Malformed           => "malformed"
      case Disabled            => "disabled"
      // #2416: both dispatch-failure cases keep the SAME `outcome` value — the aggregate
      // `outcome=error` series is unchanged; they differ only on `reason` below.
      case Error | ConfigError => "error"
    }

    /**
     * #2416 — the bounded `reason` companion label on `press_ai_draft_total`: WHY a dispatch
     * failed, so a permanently-dead press responder (`config`) is distinguishable from a blip
     * (`transient`). Every other outcome is `none` (nothing to attribute), so no sample is missing
     * the label. Bounded by this match — never a per-sender / per-thread value (the §4 cardinality
     * firewall). The vocabulary is [[wifihaven.api.support.CloudAgentObservability.Reason]], shared
     * with support so the two audiences read identically on their (deliberately separate) series.
     *
     * EXHAUSTIVE on purpose — no `case _`, for the same reason as the support twin: a future
     * dispatch-failure outcome must fail to COMPILE here rather than silently label itself `none`.
     */
    def reason(o: WebhookOutcome): String = o match {
      case ConfigError                                                        =>
        wifihaven.api.support.CloudAgentObservability.Reason.Config
      case Error                                                              =>
        wifihaven.api.support.CloudAgentObservability.Reason.Transient
      case Dispatched | RateLimited | InvalidSignature | Malformed | Disabled =>
        wifihaven.api.support.CloudAgentObservability.Reason.None
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
