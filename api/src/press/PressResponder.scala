package wifihaven.api.press

import wifihaven.api.PressConfig
import wifihaven.api.agent.AgentCredential
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
import wifihaven.api.observability.AgentTokenRejection
import wifihaven.api.support.{AgentPromptVersion, DispatchTracker}
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
    // #2517: the dispatch→completion pairing. An ACCEPTED dispatch is recorded here and closed by the
    // agent's first TERMINAL callback ([[withClaims]]); the sweep fiber Main forks reports the ones
    // nobody ever closed. Without it a cloud session that took the trigger and died is
    // indistinguishable from one that answered the journalist — the #2472 failure, press side. The
    // SAME generalized component support uses, parameterized by the correlation key (press has no
    // Plain thread id) — see [[PressResponder.dispatchKey]].
    dispatchTracker: DispatchTracker,
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
        case Left(PressInbound.VerifyError.MalformedPayload)               =>
          meter(WebhookOutcome.Malformed)
        // #2442: the Worker classified this delivery as machine-generated — break the loop here,
        // ahead of every dispatch control.
        case Right(PressInbound.Verified.AutoSubmitted(marker, messageId)) =>
          loopGuarded(marker, messageId).flatMap(meter)
        case Right(PressInbound.Verified.Message(event))                   =>
          dispatchFor(event).flatMap(meter)
      }

  /**
   * #2442 — the auto-reply / DSN loop breaker. The Cloudflare Worker
   * (deploy/press-worker/src/loop-guard.ts) classified this delivery as machine-generated: an
   * out-of-office, a ticketing acknowledgement, a mailing-list post, or a bounce. Dispatching would
   * email a reply, which draws the next auto-reply — and because prod's press From AND Reply-To are
   * both `press@wifihaven.net` (#2439), the address Cloudflare Email Routing binds to that same
   * Worker, the loop closes on itself. The per-sender rate cap below bounds how FAST it runs; only
   * this breaks it.
   *
   * The skip is deliberately NOT silent (#2265/#2266). A journalist whose mail is misclassified is
   * a real cost, so every skip lands on `press_loop_guard_total{reason}` — a bounded label, never a
   * per-sender one — and on the `press_ai_draft_total{outcome="skipped_auto_submitted"}`
   * disposition series, with a WARN log carrying the marker and the inbound Message-ID. The id is
   * the JOIN KEY: this log deliberately does not name the sender, the Worker's log line does, and
   * reconciling them is how an operator answers "which journalist did we refuse". Both series are
   * on the Press Grafana dashboard.
   */
  private def loopGuarded(marker: LoopGuardMarker, messageId: String): UIO[WebhookOutcome] = {
    val label  = LoopGuardMarker.label(marker)
    // The id is attacker-supplied, so it is control-stripped at parse (no CR/LF can reach the log
    // line) and bounded here — an unbounded one would let a hostile sender flood the log with a
    // single message. Same cap the token path uses, via the existing alias rather than a second
    // number: an id past it could never thread anyway, so nothing downstream would want the tail.
    val idPart =
      if messageId.isEmpty then "none" else messageId.take(MaxMessageIdChars)
    ZIO.logWarning(
      s"press loop guard: inbound skipped as auto-submitted " +
        s"(marker=$label, message-id=$idPart) — no session dispatched",
    ) *>
      AppMetrics.pressLoopGuard(label).as(WebhookOutcome.AutoSubmitted)
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

  /**
   * #2480 — a routine inbound emails NOBODY. #2446 sent an operator FYI from here on the premise
   * that "press has no inbox and no SPA view of `press_messages`". The second half was false since
   * #2296: `/press` (`web/src/pages/PressPage.tsx` over `GET /api/press/messages`,
   * `PressRoutes.scala`) IS the operator's surface for AI-handled press traffic, and an email per
   * message turns a browsable log into inbox noise.
   *
   * The other half of that premise — a silently FAILED dispatch — is deliberately not re-solved
   * here with a different notification. It is a metrics/alerting concern: #2416 made dispatch
   * failures fail-loud and attributed, and a permanent 4xx surfaces as `outcome=error,
   * reason=config` on `press_ai_draft_total` (the `WebhookOutcome.ConfigError` mapping below) plus
   * an ERROR log naming the fix. **Coverage today is partial and worth knowing:** the only armed
   * alert is W7 (`infra/grafana/alerting-rules-warning.tf`), scoped `env="prod"` — and since #2537
   * set `WIFIHAVEN_PRESS_RESPONDER_ENABLED` to `true` for prod (`render.yaml`) that alert does now
   * page on the environment press runs in. The remaining gap is the BUCKET, not the environment: W7
   * fires only on `reason=config`, so a sustained TRANSIENT rate still pages nobody (#2443).
   * Widening that coverage belongs in that issue; mailing the operator about every SUCCESS on the
   * chance one failed does not.
   *
   * The operator mailbox now means exactly one thing: a human must act — see [[escalate]].
   */
  private def dispatch(event: PressInboundEvent): UIO[WebhookOutcome] =
    for {
      now <- clock.instant
      // #2296: record the inbound press email BEFORE dispatch so the reply can pair to it. Fail-open
      // — a recording error yields id 0 ("no inbound row") and never blocks the dispatch.
      // #2467: normalise the inbound `References` ONCE, here, before it is persisted OR minted —
      // so the row, the token, and the header the reply eventually emits are all the same value,
      // and the attacker-controlled header is whitelisted down to msg-ids and bounded exactly once.
      references = EmailSender.normalizeReferences(Some(event.references))
      pressMessageId <- recordInbound(event, references)
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
        // #2467: the journalist's own accumulated chain, so a reply to a FOLLOW-UP references the
        // whole thread rather than just the follow-up (RFC 5322 §3.6.4). Already normalised and
        // bounded above; it rides the SIGNED payload like every other field, so a hijacked agent
        // can neither forge the chain nor graft its reply onto another conversation.
        inboundReferences = references,
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
      // #2517: pair the dispatch with the callback that must follow it. ONLY an ACCEPTED dispatch is
      // tracked — a `Disabled` / `Error` / `ConfigError` outcome never started a cloud session, so
      // nothing is owed and the dispatcher-level series already reports it. The transport is the SAME
      // pure function of config the dispatcher was built from (`transportFor`), so the label cannot
      // drift from the one `press_dispatch_total{transport}` already carries.
      _       <- ZIO
        .foreachDiscard(PressAgentDispatcher.transportLabel(cfg))(
          dispatchTracker.dispatched(
            dispatchKey(pressMessageId, event.from),
            // #2668's per-dispatch agent session id, EMPTY for press and deliberately so. That
            // guard drops a customer-visible write from a superseded session, and it is enforced
            // at support's callback boundary against the id its ConsentToken carries. PressToken
            // has no such field and press has no equivalent guard, so `turnOwner` answers
            // `Unknown` here and refuses nothing — it disables no guard that otherwise exists.
            // Press's own duplicate-reply exposure is bounded differently: the reply is
            // destination-locked to the token's address, and the #2403 loop guard plus the #2442
            // auto-reply/DSN drop sit ahead of dispatch.
            "",
            DispatchTracker.Subject.replyTo(event.from),
            _,
            now,
          ),
        )
        .when(outcome == wifihaven.api.support.DispatchOutcome.Dispatched)
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
   *
   * #2508: that reply text is the ONE thing the agent fully authors, and this is the
   * higher-severity of the two channels — the recipient is an untrusted, public journalist. So it
   * is credential-scrubbed HERE, before the email body OR the `/press` correspondence-log row is
   * built from it, rather than inside the shared [[EmailSender]] (which legitimately carries
   * server-minted secrets on other paths — see [[AgentCredential]] for the full reasoning).
   */
  def agentReply(
      bearer: Option[String],
      markdown: String,
      // #2469: the live agent's echo of the `PROMPT_VERSION:` marker in its own system prompt.
      // Optional — an agent predating the marker reports nothing and is recorded `unknown`.
      promptVersion: Option[String] = None,
  ): UIO[AgentActionResult] =
    withClaims(PressAgentAction.Reply, bearer) { claims =>
      // #2469: recorded HERE, not in the route, because here we are PAST the token
      // check — the caller provably is the dispatched agent. The route is public (the
      // token is verified inside this method), so a route-level observe would let any
      // anonymous POST forge `state="current"` and mask a genuinely stale routine.
      // Ahead of the send, so every AUTHENTICATED outcome records, including a disabled
      // or failed email. Alert-only: it can never cost a journalist their answer.
      AgentPromptVersion.observe(AgentPromptVersion.Channel.Press, promptVersion) *>
        // #2508: scrub the agent-authored text ONCE, before either the outbound email body
        // or the correspondence-log row is derived from it, so both share the safe copy.
        AgentCredential
          .redact(AgentCredential.Channel.Press, PressAgentAction.Reply, markdown)
          .flatMap { safeMarkdown =>
            val subject    = replySubject(claims.subject)
            // #2451: normalize HERE via the same primitive the transport uses, so the log line
            // below reports what will actually go on the wire rather than a second opinion.
            val inReplyTo  =
              EmailSender.threadingId(Some(claims.inboundMessageId).filter(_.nonEmpty))
            // #2467: the journalist's own accumulated chain, from the SIGNED token.
            val parentRefs =
              Some(claims.inboundReferences).filter(_.nonEmpty)
            // #2467: how this reply threaded, from the same producer that renders the
            // headers — four bounded values, no per-thread label.
            val shape      = EmailSender.threadingShape(inReplyTo, parentRefs)
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
                s"(threaded=${inReplyTo.isDefined}, shape=$shape, " +
                s"legacyToken=${claims.legacyPayload})",
            ) *>
              AppMetrics.pressReplyThreading(shape) *>
              email
                .sendAs(
                  from = cfg.fromAddressTrimmed,
                  replyTo = Some(cfg.replyToAddressTrimmed),
                  to = claims.replyTo,
                  subject = subject,
                  htmlBody = htmlBody(safeMarkdown),
                  // #2451: thread the reply under the journalist's original — the transport
                  // renders this into In-Reply-To AND References. Already normalized above; a
                  // missing or malformed Message-ID resolves to None, so the reply still sends,
                  // just unthreaded.
                  inReplyTo = inReplyTo,
                  // #2467: the journalist's own References, from the signed token. The
                  // transport accumulates it with the parent id into the reply's own
                  // References; empty (first contact, or a pre-#2467 token) yields
                  // exactly the #2451 first-level headers.
                  parentReferences = parentRefs,
                )
                .flatMap { sendResult =>
                  // #2296: record the outbound reply as AUDIT (fail-open) AFTER the send, pairing it
                  // to the inbound row via the token's pressMessageId. Only Sent/Failed are real
                  // send attempts worth logging; a Disabled send (dark install) emitted no email.
                  val record = sendResult match {
                    case EmailOutcome.Sent     =>
                      recordOutbound(claims, subject, safeMarkdown, "sent")
                    case EmailOutcome.Failed   =>
                      recordOutbound(claims, subject, safeMarkdown, "failed")
                    case EmailOutcome.Disabled => ZIO.unit
                  }
                  record *> (sendResult match {
                    case EmailOutcome.Sent     =>
                      AppMetrics
                        .pressAgentAction(PressAgentAction.Reply, "ok")
                        .as(AgentActionResult.Ok)
                    case EmailOutcome.Disabled =>
                      AppMetrics
                        .pressAgentAction(PressAgentAction.Reply, "disabled")
                        .as(AgentActionResult.Disabled)
                    case EmailOutcome.Failed   =>
                      AppMetrics
                        .pressAgentAction(PressAgentAction.Reply, "error")
                        .as(AgentActionResult.Error)
                  })
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
    withClaims(PressAgentAction.Escalate, bearer) { claims =>
      escalateLimiter.tryAcquire(s"escalate:${claims.replyTo}").flatMap { ok =>
        if !ok then
          AppMetrics
            .pressAgentAction(PressAgentAction.Escalate, "rate_limited")
            .as(AgentActionResult.RateLimited)
        else escalate(claims, note)
      }
    }

  /**
   * #2517 — the ONE gate every token-authenticated press agent callback passes through: the enabled
   * check, the bearer parse, the [[PressToken]] verification, and the dispatch→completion close, in
   * one place, so the pairing has exactly one closing site (the press twin of
   * `SupportResponder.withClaimsE`, which #2472 built for the same reason).
   *
   * A REJECTED callback never reaches `f`, and never closes anything — a forged or expired token
   * cannot silence the report for a session that really did die. Every press callback is TERMINAL
   * ([[PressAgentAction.Terminal]]), but the membership test is kept rather than assumed so a
   * future non-terminal press endpoint (an issue filing, say) cannot silently start marking
   * journalists served by existing.
   *
   * WHAT CLOSES. This measures "did the session come back", not "did the reply land": an email the
   * agent authored and Resend then refused still closes, because the send WAS attempted and its
   * failure is already loud on `press_agent_action_total{op="reply",outcome="error"}` — judging it
   * a second time here would be two places computing one thing
   * (docs/process/single-source-of-truth.md).
   *
   * A RATE-LIMITED callback is the exception and does NOT close (#2691 review). `escalateLimiter`
   * (3/hour per sender) REFUSES the action outright: no operator was paged, no journalist was
   * answered, and nothing happened that a human reading `press_dispatch_total{outcome="completed"}`
   * — the panel titled "Press inquiries the agent ANSWERED" — would recognise. Counting it served
   * would make that panel say something false AND drop the entry from the sweep, so the inquiry it
   * belongs to would go unreported. So `f` runs first and the close is conditional on its outcome
   * ([[PressResponder.closesDispatch]]).
   */
  private def withClaims(op: String, bearer: Option[String])(
      f: PressToken.Claims => UIO[AgentActionResult],
  ): UIO[AgentActionResult] =
    if !cfg.agentEndpointsEnabled then
      AppMetrics.pressAgentAction(op, "disabled").as(AgentActionResult.Disabled)
    else
      clock.instant.flatMap { now =>
        bearer.map(_.trim).filter(_.nonEmpty) match {
          case None        =>
            // #2473: loud on the shared rejection series — a rejected callback is a reply the
            // journalist never received, not just another `denied` sample.
            denyLoudly(op, AgentTokenRejection.Reason.Missing)
          case Some(token) =>
            PressToken.verify(token, now, cfg.agentTokenSecretTrimmed) match {
              case Left(err)     => denyLoudly(op, AgentTokenRejection.reasonFor(err))
              case Right(claims) =>
                f(claims).tap { result =>
                  ZIO
                    .when(PressAgentAction.Terminal.contains(op) && closesDispatch(result))(
                      dispatchTracker.calledBack(
                        dispatchKey(claims.pressMessageId, claims.replyTo),
                        op,
                        now,
                      ),
                    )
                    .unit
                }
            }
        }
      }

  /**
   * #2473 — the ONE press-side token rejection path (the press twin of SupportResponder's). Log +
   * meter the loud shared series, then return the SAME uniform 401-shaped `Denied` (and the same
   * `press_agent_action_total{denied}` sample) every rejection has always returned: the response is
   * identical for every reason, so a public caller learns nothing about WHY it failed.
   */
  private def denyLoudly(op: String, reason: String): UIO[AgentActionResult] =
    AgentTokenRejection.rejected(AgentTokenRejection.Channel.Press, op, reason) *>
      AppMetrics.pressAgentAction(op, "denied").as(AgentActionResult.Denied)

  private def escalate(
      claims: PressToken.Claims,
      note: Option[String],
  ): UIO[AgentActionResult] =
    for {
      // #2508: the note is agent-authored and lands in the operator's mailbox — a credential in it
      // would leave the process too, so it is scrubbed on the same terms as a reply.
      safeNote <- AgentCredential.redactOpt(
        AgentCredential.Channel.Press,
        PressAgentAction.Escalate,
        note,
      )
      // Audit trail for every escalation — the peer + the log row, never the token.
      _        <- ZIO.logInfo(
        s"press: agent ESCALATED reply-to=${claims.replyTo} pressMessageId=${claims.pressMessageId}",
      )
      // The original inquiry, re-read from the audit log by the id the SIGNED token carries — so the
      // operator sees what the journalist actually wrote, not a copy the agent could have edited.
      // Best-effort: a missing/unreadable row degrades to a pointer, never blocks the notice.
      body     <- inboundBody(claims.pressMessageId)
      _        <- notifier.escalation(
        EscalationNotice(
          channel = EscalationChannel.Press,
          kind = EscalationKind.Escalated,
          sender = claims.replyTo,
          subject = claims.subject,
          body = body,
          agentNote = safeNote.map(_.trim).filter(_.nonEmpty),
          reference =
            if claims.pressMessageId > 0 then s"press_messages id=${claims.pressMessageId}"
            else "press_messages row unavailable",
        ),
      )
      r <- AppMetrics.pressAgentAction(PressAgentAction.Escalate, "ok").as(AgentActionResult.Ok)
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
  private def recordInbound(event: PressInboundEvent, references: String): UIO[Long] =
    pressLog
      .recordInbound(event.from, event.subject, event.messageText, event.messageId, references)
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
   * #2517 — the press dispatch→completion CORRELATION KEY: the one value that identifies the same
   * session at dispatch time and at callback time. This is what the generalized `DispatchTracker`
   * is parameterized over; support uses the Plain `threadId`, and press has no thread.
   *
   * '''Primary: the recorded inbound row id''' (#2296). It is unique per inbound press email, it is
   * known before the token is minted, and it rides the SIGNED token — so the key the callback
   * presents cannot be forged or repointed at another session's dispatch, exactly as the support
   * key cannot.
   *
   * '''Fallback: the reply-target address.''' `pressMessageId` is `0` when the fail-open inbound
   * recording missed (a DB error must never block a dispatch), and a shared literal `0` key would
   * make two such dispatches supersede each other — one session silently untracked, which is the
   * very silence this closes. The reply target is always present and is equally on the signed
   * token, so it keeps those dispatches correlated. Its weaker property is stated rather than
   * papered over: two inquiries from the SAME address while recording is broken collide, and the
   * newer supersedes the older with a logged `press dispatch superseded` line — degraded, bounded,
   * and never silent.
   *
   * The prefixes keep the two spaces disjoint, so a recorded row id can never collide with an
   * address. This value is STATE, never a metric label (docs/process/instrumentation.md §4).
   */
  def dispatchKey(pressMessageId: Long, replyTo: String): String =
    if pressMessageId > 0 then s"pm:$pressMessageId" else s"rt:$replyTo"

  /**
   * #2517 — does this callback outcome CLOSE the dispatch it belongs to?
   *
   * Everything the agent actually did closes it, including a send the transport then refused
   * (`Error`) and a send our own install has switched off (`Disabled`): in all of those the session
   * came back and acted, which is the only thing the tracker claims to measure.
   *
   * `RateLimited` does not (#2691 review). It is the one outcome where the terminal action was
   * REFUSED before doing anything — the #2437 3/hour-per-sender escalation cap — so no human was
   * paged and no journalist was answered. `Denied` and the pre-token `Disabled` never reach here
   * (they short-circuit ahead of `f`), but the match is total so a new `AgentActionResult` case
   * cannot be silently absorbed into "served".
   */
  def closesDispatch(result: AgentActionResult): Boolean = result match {
    case AgentActionResult.Ok | AgentActionResult.Error | AgentActionResult.Disabled => true
    case AgentActionResult.RateLimited | AgentActionResult.Denied                    => false
  }

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

    /**
     * #2442 — the Worker flagged this delivery auto-submitted (out-of-office / list / bounce), so
     * no session was dispatched and no reply was sent. Distinct from [[Malformed]]: that is an
     * envelope we could not read, this is one we read and deliberately declined to answer.
     */
    case AutoSubmitted

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
      case AutoSubmitted       => "skipped_auto_submitted"
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
      case ConfigError                                                                        =>
        wifihaven.api.support.CloudAgentObservability.Reason.Config
      case Error                                                                              =>
        wifihaven.api.support.CloudAgentObservability.Reason.Transient
      // #2442: a loop-guard skip is not a dispatch FAILURE — the `why` it carries is the marker on
      // its own `press_loop_guard_total` series, not this dispatch-failure vocabulary.
      case Dispatched | RateLimited | InvalidSignature | Malformed | Disabled | AutoSubmitted =>
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
