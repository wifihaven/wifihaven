package wifihaven.api.support

import wifihaven.api.SupportConfig
import wifihaven.api.auth.{JwtClaims, RateLimiter}
import wifihaven.api.db.{
  DeviceRepo,
  Household,
  HouseholdBillingRepo,
  HouseholdRepo,
  ProfileRepo,
  SupportConsentRepo,
  UserRepo,
}
import wifihaven.api.metrics.AppMetrics
import wifihaven.shared.{Clock, UserRole}
import wifihaven.shared.types.HouseholdId
import zio.*
import zio.json.*

import java.time.Instant

/**
 * #2200 (support intake C, epic #2197) — the Claude support responder, wired to Plain per the #2241
 * access model. Two halves, both behind the EXPLICIT `support.responderEnabled` flag (#2265 — no
 * dark-by-default: enabling without the full config chain refuses to boot; disabling is a named,
 * logged, health-visible state):
 *
 * **Inbound ([[handleWebhook]])**: Plain's signed new-message webhook → HMAC verify → the
 * AUTHENTICATED-ORIGIN gate → mint the per-session [[ConsentToken]] (thread- + household-bound,
 * consent-scoped, short-TTL) → dispatch a cloud-agent session ([[CloudAgentDispatcher]]). The
 * inbound message text is UNTRUSTED DATA end to end.
 *
 * Before the origin gate runs, the #2403 LOOP GUARD drops any event that is not an inbound,
 * customer-authored message ([[PlainWebhook.InboundCustomerEventTypes]] + `actorType ==
 * "customer"`) — our own outbound `thread.chat_sent` reply can never re-trigger a dispatch, even if
 * the Plain webhook is subscribed to every event type.
 *
 * The gate admits a thread to the AI responder on EITHER of two authenticated origins; everything
 * else burns no tokens (#2307, refining the 2026-07-14 UI-only constraint):
 *   - **UI-originated**: the #2199 identified widget stamps `household_id` on the Plain customer's
 *     `externalId`, so a `customer.externalId` (`tenantIdentifier`) that resolves to a real
 *     household is a proven authenticated submission.
 *   - **Registered-admin email**: a NEW inbound email (no resolvable tenant) whose From address
 *     matches a registered household ADMIN (`users.email`, globally unique, V67). We resolve THAT
 *     admin's household and dispatch bound to it, exactly as if UI-originated (the #2241 token
 *     binds to the sender's household).
 *
 * A NEW thread from an UNREGISTERED address gets a FIXED static reject via the outbound Plain reply
 * — no Claude call, no dispatch, no token, no persisted support thread — so a flood of cold email
 * cannot burn tokens (the token-burn guard the UI-only rule used to provide, preserved). The reject
 * fires ONLY on the new-thread event, so an ongoing unregistered thread is not re-rejected on every
 * message (backscatter guard); an unregistered continuation with no resolvable origin is silently
 * `skipped_unauthenticated`. Because Plain carries the message body only on the per-message events
 * (`thread.chat_received` / `thread.email_received`), a registered admin whose inbound email
 * resolves no tenant is admitted per message that carries a body — dispatch is rate-capped
 * per-thread + globally, so this is bounded, not open-ended.
 *
 * **Agent-facing ([[agentReply]] / [[agentFileIssue]] / [[agentHousehold]])**: the dispatched
 * agent's ONLY credential is the token; every side effect comes back through these endpoints where
 * the guarantees are enforced structurally, not by prompt:
 *   - replies post into the token-bound thread ONLY, attributed as the AI assistant, and go to the
 *     customer WITHOUT a human approval step (autonomous send — operator decision 2026-07-17; the
 *     customer can always escalate to a human, and the operator monitors every thread in Plain);
 *   - issues are PII-scrubbed at the [[GithubIssueClient]] boundary, `support-agent`-labeled, and
 *     rate-limited per-thread + globally (#2241 compensating control + volume alert feed);
 *   - household reads require the token's consent scope and derive the household FROM the verified
 *     token — cross-tenant reads are impossible by construction (#2107/#2108 isolation substrate).
 *
 * External I/O (Plain, GitHub, Anthropic) is behind swappable traits stubbed in specs; the Clock is
 * injected (docs/process/testing.md).
 */
final case class SupportResponder(
    cfg: SupportConfig,
    householdRepo: HouseholdRepo,
    // #2307: the email-intake gate resolves an inbound From address to a registered household admin
    // (findByEmail, globally unique V67) and dispatches bound to THAT admin's household.
    userRepo: UserRepo,
    billingRepo: HouseholdBillingRepo,
    deviceRepo: DeviceRepo,
    profileRepo: ProfileRepo,
    // #2419: the server-side per-(household, thread) data-access consent record (V84). The ONLY
    // thing that widens a minted token's `dataAccess` scope beyond the widget-stamped flag — and
    // the only writer is the CUSTOMER's own JWT-authenticated grant ([[recordConsent]]).
    consentRepo: SupportConsentRepo,
    plain: PlainClient,
    github: GithubIssueClient,
    dispatcher: CloudAgentDispatcher,
    clock: Clock,
    issueThreadLimiter: RateLimiter,
    issueGlobalLimiter: RateLimiter,
    // Cost guardrails (operator concern 2026-07-16): agent sessions bill real tokens, so dispatch
    // itself is rate-capped per-thread and globally — a household spamming the widget (or a retry
    // storm) hits a hard ceiling instead of an open-ended bill. Unregistered cold email never
    // reaches this point (the origin gate is the first cost control).
    dispatchThreadLimiter: RateLimiter,
    dispatchGlobalLimiter: RateLimiter,
    // #2307: bounds the cheap static-reject outbound path so a spammer forging many cold-email
    // threads cannot turn us into a reply-amplification (backscatter) source. Global bucket — the
    // reject is a fixed string, not a per-thread cost.
    rejectLimiter: RateLimiter,
    // #2419: bounds how often the agent can make us post a consent prompt into ONE thread — an
    // agent stuck in a loop (or a prompt-injected one) must not be able to spam the customer.
    consentThreadLimiter: RateLimiter,
    // #2419: the SPA origin the consent link points at (`<appBaseUrl>/support/consent?g=…`).
    // Sourced from the ONE per-env SPA origin the API already carries (`wifihaven.email.appBaseUrl`,
    // #2250) rather than a new `support.*` key — SupportConfig sits at zio-config-magnolia's
    // 16-field nested-derivation ceiling (see Config.scala), and "where the dashboard lives" is one
    // fact, not a per-feature one (single-source-of-truth).
    appBaseUrl: String,
) {
  import SupportResponder.*

  // ── Inbound: the signed Plain webhook ────────────────────────────────────────

  /**
   * Handle one Plain new-message webhook delivery. Never fails — every path resolves to a metered
   * [[WebhookOutcome]] the route maps to a status. Signature verification runs FIRST, over the raw
   * body; nothing is parsed or acted on for an unsigned/forged payload.
   */
  def handleWebhook(rawBody: String, sigHeader: Option[String]): UIO[WebhookOutcome] =
    if !cfg.responderEnabled then finish(WebhookOutcome.Disabled, None)
    else
      PlainWebhook.verifyAndParse(rawBody, sigHeader, cfg.plain.webhookSecretTrimmed) match {
        case Left(PlainWebhook.VerifyError.MissingSignature) | Left(
              PlainWebhook.VerifyError.BadSignature,
            ) =>
          finish(WebhookOutcome.InvalidSignature, None)
        case Left(PlainWebhook.VerifyError.MalformedPayload) =>
          finish(WebhookOutcome.Malformed, None)
        case Right(event)                                    =>
          dispatchIfAuthorized(event).flatMap(finish(_, Some(event)))
      }

  /**
   * The loop guard + AUTHENTICATED-ORIGIN gate + dispatch (#2403 / #2307).
   *
   * FIRST, the #2403 loop guard: only an INBOUND, CUSTOMER-authored event is actionable
   * ([[PlainWebhook.InboundCustomerEventTypes]] + `actorType == "customer"`). Our own outbound
   * `thread.chat_sent` reply — or any non-customer actor — is `SkippedNotInbound`, so the
   * assistant's reply can never re-trigger a dispatch (a reply loop), even if the Plain webhook is
   * subscribed to every event type.
   *
   * THEN the origin gate — a thread reaches the AI responder on EITHER origin, else it burns no
   * tokens: (1) a `customer.externalId` (`tenantIdentifier`) that resolves to a real household (the
   * #2199 UI-origin path); (2) failing that, an inbound email whose From matches a registered
   * household admin. An unregistered NEW thread gets a static reject (no dispatch); anything else
   * with no resolvable origin is `skipped_unauthenticated`. A dispatch also requires a non-empty
   * message — a `thread.thread_created` carries no body (the text arrives on the following
   * `thread.chat_received` / `thread.email_received`), so it never dispatches an empty session.
   */
  private def dispatchIfAuthorized(event: PlainNewMessageEvent): UIO[WebhookOutcome] =
    if !isActionableInbound(event) then ZIO.succeed(WebhookOutcome.SkippedNotInbound)
    else
      resolveTenantHousehold(event).flatMap {
        case Some((hh, household)) if event.messageText.nonEmpty =>
          rateLimitedDispatch(event, hh, household, WebhookOutcome.Dispatched)
        case Some(_)                                             =>
          // Identified, but a bodyless metadata event (e.g. thread_created) — nothing to answer;
          // the real message rides the following chat_received/email_received.
          ZIO.succeed(WebhookOutcome.SkippedNotInbound)
        case None                                                =>
          // No provable UI origin. An inbound email is gated on the sender being a registered admin.
          event.customerEmail match {
            case Some(_) => emailIntakeGate(event)
            case None    => ZIO.succeed(WebhookOutcome.SkippedUnauthenticated)
          }
      }

  /**
   * #2403 loop guard: the event is an inbound CUSTOMER message we may act on. The event-type
   * allowlist excludes our own outbound `thread.chat_sent` (the reply-loop source) and every non-
   * message event; the actor check is the second guard — if an author is present it must be the
   * `customer`, so an agent/system-authored event on an allowlisted type is still skipped. An
   * absent actor is non-blocking (the allowlist already bounds the surface).
   */
  private def isActionableInbound(event: PlainNewMessageEvent): Boolean =
    PlainWebhook.InboundCustomerEventTypes.contains(event.eventType) &&
      event.actorType.forall(_ == "customer")

  /**
   * Resolve the UI-origin tenant to a household. The tenant must parse AND resolve to an existing
   * row (presence of the attribute alone is not enough) and there must be a thread to bind to.
   */
  private def resolveTenantHousehold(
      event: PlainNewMessageEvent,
  ): UIO[Option[(HouseholdId, Household)]] =
    event.tenantIdentifier.flatMap(_.toLongOption) match {
      case Some(raw) if event.threadId.nonEmpty =>
        val hh = HouseholdId(raw)
        householdRepo.findById(hh).catchAll(_ => ZIO.none).map(_.map(hh -> _))
      case _                                    => ZIO.none
    }

  /**
   * #2307 email-intake gate: an inbound email with no resolvable tenant. Resolve the From address
   * to a registered household ADMIN and dispatch bound to THAT admin's household (authenticated, as
   * if UI-originated); otherwise emit the fixed static reject — but only on a NEW thread, so an
   * ongoing unregistered thread is not re-rejected on every message (the backscatter guard). No AI
   * call ever runs on the reject path (the token-burn guard the UI-only rule used to provide). A
   * registered admin's bodyless `thread.thread_created` is skipped (the answerable message rides
   * the following `thread.email_received`, which carries the body).
   *
   * TRUST BOUNDARY: SMTP `From` is spoofable, so this gate trusts Plain's upstream MX (#2198) to
   * have accepted the message under its own SPF/DKIM/spam handling before signing + firing the
   * webhook — we treat `customerEmail` as authenticated by Plain, not by us. The blast radius of a
   * forged-From admin email is deliberately bounded and does NOT include data exfiltration:
   *   - data-access scope is never set from the inbound payload at all (#2419): it comes only from
   *     a server-side consent record the CUSTOMER wrote from their authenticated session, so a
   *     forged-From dispatch mints a data-scope-LESS token — the agent can reply, but the
   *     household-read endpoint refuses it;
   *   - the reply is delivered by Plain to the spoofed address (the REAL admin), not the forger;
   *   - dispatch is rate-capped (per-thread + global), so the worst case is bounded dispatch-budget
   *     burn, not an open-ended bill or a leak.
   * Do NOT "fix" the consent default to true here, and do NOT assume the From is verified locally.
   */
  private def emailIntakeGate(event: PlainNewMessageEvent): UIO[WebhookOutcome] =
    event.customerEmail match {
      case None        => ZIO.succeed(WebhookOutcome.SkippedUnauthenticated) // no From to gate on
      case Some(email) =>
        resolveAdminHousehold(email).flatMap {
          case Some((hh, household)) if event.threadId.nonEmpty && event.messageText.nonEmpty =>
            rateLimitedDispatch(event, hh, household, WebhookOutcome.EmailRegisteredDispatched)
          case Some(_)                                                                        =>
            // Registered, but no thread to bind or a bodyless new-thread event — the answerable
            // message rides the following body event (chat_received/email_received).
            ZIO.succeed(WebhookOutcome.SkippedUnauthenticated)
          case None                                                                           =>
            // Unregistered: reject a NEW thread once; skip continuations (no re-reject backscatter).
            if event.isNewThread then staticReject(event)
            else ZIO.succeed(WebhookOutcome.SkippedUnauthenticated)
        }
    }

  /**
   * Resolve a sender From address to the household of the ADMIN who owns it. Match is exact
   * (aligned with the case-sensitive `uq_users_email` constraint used by email login). A non-admin
   * registered email is NOT admitted (the gate is admins only, #2307).
   */
  private def resolveAdminHousehold(email: String): UIO[Option[(HouseholdId, Household)]] =
    userRepo.findByEmail(email).catchAll(_ => ZIO.none).flatMap {
      case Some(user) if user.role == UserRole.Admin =>
        householdRepo
          .findById(user.householdId)
          .catchAll(_ => ZIO.none)
          .map(_.map(user.householdId -> _))
      case _                                         => ZIO.none
    }

  /**
   * The dispatch cost caps (#2261 short-circuit: the global bucket is drawn only when the
   * per-thread cap allowed, else one capped thread would drain the shared daily budget and lock
   * every other household out of the AI-reply path). `success` is the metered outcome (UI vs email
   * origin).
   */
  private def rateLimitedDispatch(
      event: PlainNewMessageEvent,
      hh: HouseholdId,
      household: Household,
      success: WebhookOutcome,
  ): UIO[WebhookOutcome] =
    dispatchThreadLimiter.tryAcquire(s"thread:${event.threadId}").flatMap { threadOk =>
      if !threadOk then ZIO.succeed(WebhookOutcome.RateLimited)
      else
        dispatchGlobalLimiter.tryAcquire("global").flatMap { globalOk =>
          if !globalOk then ZIO.succeed(WebhookOutcome.RateLimited)
          else dispatchFor(event, hh, household, success)
        }
    }

  /**
   * The fixed, NON-AI reject for a new email from an unregistered sender (#2307). A static string,
   * never a Claude call, so cold-email volume cannot burn tokens; rate-capped globally so we cannot
   * be turned into a reply-amplification source. No token minted, no dispatch, no persisted support
   * thread. The wording is deliberately generic (names no accounts) — it reveals only whether the
   * sender's OWN address is registered, their own info.
   */
  private def staticReject(event: PlainNewMessageEvent): UIO[WebhookOutcome] =
    rejectLimiter.tryAcquire("global").flatMap { ok =>
      if !ok then ZIO.succeed(WebhookOutcome.RateLimited)
      else {
        val write = PlainThreadWrite(
          // No household — this sender maps to no tenant. #2408: the reject replies INTO the
          // cold-email thread (event.threadId, the customer-visible email reply), not a new thread.
          threadId = event.threadId,
          markdown = UnregisteredRejectTemplate,
        )
        plain.writeThread(write).as(WebhookOutcome.EmailUnregisteredRejected)
      }
    }

  private def dispatchFor(
      event: PlainNewMessageEvent,
      hh: HouseholdId,
      household: Household,
      success: WebhookOutcome,
  ): UIO[WebhookOutcome] =
    for {
      billing    <- billingRepo.findByHousehold(hh).catchAll(_ => ZIO.none)
      // #2430: the conversation SO FAR on this thread. The responder is stateless — every inbound
      // message fires a FRESH cloud session — so without this the agent answers each message in
      // isolation. Scoped to the bound thread; fail-open (the read never fails, it yields Nil), so
      // a Plain hiccup or a missing `thread:read` grant costs context, never the webhook.
      prior      <- plain.threadHistory(event.threadId, PlainClient.HistoryFetchLimit)
      now        <- clock.instant
      // #2419: the token's data scope comes from ONE place — a LIVE server-side consent record for
      // THIS (household, thread), written only by the customer's own authenticated grant. Both key
      // columns are bound to state we already resolved, so a grant on another thread/household can
      // never widen this token; a DB blip degrades to NO access (fail-closed — never grant on
      // error). Nothing on the inbound payload can set this (the parser no longer reads a
      // `dataConsent` flag at all).
      dataAccess <- consentGranted(hh, event.threadId, now)
      token = ConsentToken.mint(
        household = hh,
        threadId = event.threadId,
        dataAccess = dataAccess,
        now = now,
        ttl = cfg.agentTokenTtl,
        secret = cfg.agentTokenSecretTrimmed,
      )
      // Audit trail for every mint (#2241) — household + thread + scope, never the token.
      _       <- ZIO.logInfo(
        s"support: minted agent token for household=${hh.value} thread=${event.threadId} dataAccess=$dataAccess",
      )
      outcome <- dispatcher.dispatch(
        AgentDispatch(
          threadId = event.threadId,
          householdName = household.name,
          plan = billing.map(_.status),
          dataConsent = dataAccess,
          agentToken = token,
          customerMessage = event.messageText,
          history = priorTurns(prior, event.messageText),
        ),
      )
    } yield outcome match {
      case DispatchOutcome.Dispatched  => success
      case DispatchOutcome.Disabled    => WebhookOutcome.Disabled
      case DispatchOutcome.Error       => WebhookOutcome.Error
      // #2416: a permanent 4xx at the agent boundary — already logged at ERROR with the fix named by
      // CloudAgentDispatch. Same `outcome=error`, distinct `reason=config`.
      case DispatchOutcome.ConfigError => WebhookOutcome.ConfigError
    }

  /**
   * The single webhook choke point: LOG the resolved outcome (per-thread correlation) AND meter it.
   * Every branch of [[handleWebhook]] routes through here, so a SKIP / REJECT / RATE-LIMIT /
   * MALFORMED is never silent — the aggregate `supportAiDraft{outcome}` counter can't answer "why
   * wasn't THIS thread answered?" on its own (#2431: a loop-guard skip of our own
   * `thread.chat_sent` echoes used to require a manual investigation).
   */
  private def finish(o: WebhookOutcome, event: Option[PlainNewMessageEvent]): UIO[WebhookOutcome] =
    logWebhookOutcome(o, event) *> meter(o)

  /**
   * One INFO line per webhook delivery: the bounded [[WebhookOutcome]] label plus, when a parsed
   * event is available, the Plain `threadId` and `eventType` — both bounded, non-PII (the mint log
   * already establishes `thread=<id>` as loggable). Pre-parse outcomes (InvalidSignature /
   * Malformed / Disabled) carry no event, so both correlation fields log as `-`. NEVER the message
   * text, customer email, or any customer content (UNTRUSTED PII — see the class comment).
   */
  private def logWebhookOutcome(o: WebhookOutcome, event: Option[PlainNewMessageEvent]): UIO[Unit] =
    ZIO.logInfo(
      s"support webhook outcome=${WebhookOutcome.label(o)} " +
        s"thread=${event.map(_.threadId).filter(_.nonEmpty).getOrElse("-")} " +
        s"eventType=${event.map(_.eventType).getOrElse("-")}",
    )

  // #2416: `reason` rides alongside `outcome` on every sample (`none` when there is nothing to
  // attribute), both bounded by WebhookOutcome — no per-thread / per-household label ever.
  private def meter(o: WebhookOutcome): UIO[WebhookOutcome] =
    AppMetrics.supportAiDraft(WebhookOutcome.label(o), WebhookOutcome.reason(o)).as(o)

  // ── Agent-facing: the token-authenticated callback endpoints ────────────────

  /**
   * Post the agent's reply into the token-bound Plain thread — SENT to the customer, attributed as
   * the AI assistant (autonomous send, operator decision 2026-07-17: no human approval step; the
   * customer can escalate to a human at any time per the agent's standing instructions, and the
   * operator monitors every thread in the Plain inbox). The thread and household come FROM the
   * verified token — the request body carries only the reply text, so a hijacked agent cannot aim a
   * reply at another thread or household.
   */
  def agentReply(bearer: Option[String], markdown: String): UIO[AgentActionResult] =
    withClaims("reply", bearer) { claims =>
      val write = PlainThreadWrite(
        // #2408: the reply posts INTO the customer's existing thread (`claims.threadId`, the
        // customer-visible send via Plain's replyToThread), NOT a new createThread. The thread
        // binding comes from the verified token — the request body carries only the reply text.
        threadId = claims.threadId,
        markdown = s"$AiReplyAttribution\n\n$markdown",
      )
      plain.writeThread(write).flatMap {
        case PlainOutcome.Ok       => done("reply", AgentActionResult.Ok)
        case PlainOutcome.Disabled => done("reply", AgentActionResult.Disabled)
        case PlainOutcome.Error    => done("reply", AgentActionResult.Error)
      }
    }

  /**
   * File a GitHub issue on the support bot's behalf. Rate-limited per-thread and globally (the
   * volume metric feeds the operator alert), auto-labeled `support-agent` and PII-scrubbed inside
   * [[GithubIssueClient]] — the #2241 compensating control: the body that leaves this process never
   * embeds raw household-data output.
   */
  def agentFileIssue(bearer: Option[String], title: String, body: String): UIO[AgentActionResult] =
    withClaims("issue", bearer) { claims =>
      // Same short-circuit as dispatch: a thread-capped caller must not drain the global budget.
      issueThreadLimiter.tryAcquire(s"thread:${claims.threadId}").flatMap { threadOk =>
        if !threadOk then done("issue", AgentActionResult.RateLimited)
        else
          issueGlobalLimiter.tryAcquire("global").flatMap { globalOk =>
            if !globalOk then done("issue", AgentActionResult.RateLimited)
            else
              github.fileIssue(IssueFileRequest(title, body, claims.threadId)).flatMap {
                case IssueOutcome.Filed    => done("issue", AgentActionResult.Ok)
                case IssueOutcome.Disabled => done("issue", AgentActionResult.Disabled)
                case IssueOutcome.Error    => done("issue", AgentActionResult.Error)
              }
          }
      }
    }

  /**
   * The consented household read (#2241): a bounded summary of the ONE household the token is bound
   * to. Requires the token's `dataAccess` scope (minted only when the customer opted in at the UI
   * submission); the household id comes from the token, so there is no parameter through which
   * another household could be requested — single-household is enforced by construction.
   */
  def agentHousehold(bearer: Option[String]): UIO[Either[AgentActionResult, HouseholdSummary]] =
    withClaimsE("household_read", bearer) { claims =>
      if !claims.dataAccess then
        // Valid token, but minted WITHOUT the consent scope — the one 403-shaped denial (the route
        // distinguishes it from a bad/expired token, which stays a uniform 401).
        AppMetrics
          .supportAgentAction("household_read", "denied")
          .as(Left(AgentActionResult.NoConsent))
      else {
        val hh = claims.householdId
        for {
          household <- householdRepo.findById(hh).catchAll(_ => ZIO.none)
          billing   <- billingRepo.findByHousehold(hh).catchAll(_ => ZIO.none)
          devices   <- deviceRepo.listAllForHousehold(hh).catchAll(_ => ZIO.succeed(Nil))
          profiles  <- profileRepo.listAllForHousehold(hh).catchAll(_ => ZIO.succeed(Nil))
          // Audit every consented read (#2241) — household + thread, never the data.
          _         <- ZIO.logInfo(
            s"support: agent household read household=${hh.value} thread=${claims.threadId}",
          )
          _         <- AppMetrics.supportAgentAction("household_read", "ok")
        } yield Right(
          HouseholdSummary(
            name = household.map(_.name).getOrElse(""),
            plan = billing.map(_.status),
            founding = billing.map(_.founding),
            deviceCount = devices.size,
            profileCount = profiles.size,
            profiles = profiles.map(p => ProfileSummary(p.name, p.paused)),
          ),
        )
      }
    }

  // ── #2419: the in-conversation data-access consent flow ─────────────────────

  /**
   * AGENT-side half: "ask the customer for permission" (#2419). The agent — which cannot read
   * household data without the consent scope — calls this instead of dead-ending, and the SERVER
   * posts a FIXED, server-authored consent prompt into the token-bound thread carrying a signed
   * [[ConsentGrant]] link. It is deliberately not the agent's own words: the agent supplies no text
   * here, so a prompt-injected agent cannot craft a phishing message under our attribution.
   *
   * This REQUESTS consent; it does not grant it. There is no path from this endpoint to a
   * `support_thread_consent` row — only [[recordConsent]], authenticated by the CUSTOMER's session
   * JWT, writes one. A thread that already has a live grant is a no-op Ok (nothing to ask), so a
   * confused agent can't re-prompt a customer who already said yes; everything else is capped by
   * `consentThreadLimiter`.
   */
  def agentRequestConsent(bearer: Option[String]): UIO[AgentActionResult] =
    withClaims("consent_request", bearer) { claims =>
      clock.instant.flatMap { now =>
        consentGranted(claims.householdId, claims.threadId, now).flatMap {
          case true  =>
            // Already consented — no prompt, no spam. The next dispatch already carries the scope.
            AppMetrics.supportConsent("request_already_granted") *>
              done("consent_request", AgentActionResult.Ok)
          case false =>
            consentThreadLimiter.tryAcquire(s"consent:${claims.threadId}").flatMap { ok =>
              if !ok then
                AppMetrics.supportConsent("request_rate_limited") *>
                  done("consent_request", AgentActionResult.RateLimited)
              else postConsentPrompt(claims, now)
            }
        }
      }
    }

  private def postConsentPrompt(
      claims: ConsentToken.Claims,
      now: Instant,
  ): UIO[AgentActionResult] = {
    val grant = ConsentGrant.mint(
      household = claims.householdId,
      threadId = claims.threadId,
      now = now,
      ttl = SupportResponder.ConsentLinkTtl,
      secret = cfg.agentTokenSecretTrimmed,
    )
    val write = PlainThreadWrite(
      threadId = claims.threadId,
      markdown = SupportResponder.consentPromptTemplate(consentUrl(grant)),
    )
    // Audit every request (#2241 discipline) — household + thread, never the link/token.
    ZIO.logInfo(
      s"support: consent requested for household=${claims.householdId.value} thread=${claims.threadId}",
    ) *>
      plain.writeThread(write).flatMap {
        case PlainOutcome.Ok       =>
          AppMetrics.supportConsent("requested") *> done("consent_request", AgentActionResult.Ok)
        case PlainOutcome.Disabled =>
          AppMetrics.supportConsent("request_disabled") *>
            done("consent_request", AgentActionResult.Disabled)
        case PlainOutcome.Error    =>
          AppMetrics.supportConsent("request_error") *>
            done("consent_request", AgentActionResult.Error)
      }
  }

  /** `<appBaseUrl>/support/consent?g=<grant token>` — the customer-facing consent link. */
  private def consentUrl(grant: String): String =
    s"${appBaseUrl.trim.stripSuffix("/")}/support/consent?g=$grant"

  /**
   * CUSTOMER-side half: record (or withdraw) the grant (#2419). Called from the JWT-authenticated
   * `POST /api/support/consent`, so the acting household is `claims.hh` — resolved from the
   * customer's OWN session, never from a request field and never from message text.
   *
   * The `grant` token proves WHICH thread was asked; the JWT proves WHO is answering. Both are
   * required and they must agree: a consent link for household A redeemed by a household-B session
   * is refused ([[ConsentResult.Mismatch]]) and writes nothing. The recorded scope is exactly
   * `(claims.hh, grantClaims.threadId)` — the household comes from the SESSION, so even a forged
   * household in a (necessarily well-signed) link could not aim a grant at another tenant.
   */
  def recordConsent(claims: JwtClaims, grant: String, allow: Boolean): UIO[ConsentResult] =
    if !cfg.agentEndpointsEnabled then
      AppMetrics.supportConsent("disabled").as(ConsentResult.Disabled)
    else
      clock.instant.flatMap { now =>
        ConsentGrant.verify(grant.trim, now, cfg.agentTokenSecretTrimmed) match {
          case Left(ConsentGrant.Err.Expired)         =>
            AppMetrics.supportConsent("expired").as(ConsentResult.Invalid)
          case Left(_)                                =>
            AppMetrics.supportConsent("invalid").as(ConsentResult.Invalid)
          case Right(g) if g.householdId != claims.hh =>
            // The one cross-tenant shape this endpoint can see: a link minted for another
            // household. Loud, metered, and it writes NOTHING.
            ZIO.logWarning(
              s"support: consent household mismatch — link household=${g.householdId.value} " +
                s"session household=${claims.hh.value} thread=${g.threadId}",
            ) *> AppMetrics.supportConsent("household_mismatch").as(ConsentResult.Mismatch)
          case Right(g)                               =>
            if allow then applyGrant(claims, g, now) else applyRevoke(claims, g, now)
        }
      }

  private def applyGrant(claims: JwtClaims, g: ConsentGrant.Claims, now: Instant) =
    for {
      // Audit pointer: WHICH admin granted. Best-effort — a DB blip on the lookup must not lose
      // the customer's consent, so the grant is still recorded (with a null actor).
      user <- userRepo.findByUsername(claims.hh, claims.sub).catchAll(_ => ZIO.none)
      res  <- consentRepo
        .grant(claims.hh, g.threadId, user.map(_.id), now, now.plus(SupportResponder.ConsentTtl))
        .foldZIO(
          e =>
            ZIO.logWarning(s"support: consent grant failed: ${e.getMessage}") *>
              AppMetrics.supportConsent("error").as(ConsentResult.Error),
          _ =>
            ZIO.logInfo(
              s"support: data-access consent GRANTED household=${claims.hh.value} " +
                s"thread=${g.threadId} by=${claims.sub} ttlHours=${SupportResponder.ConsentTtl.toHours}",
            ) *> AppMetrics.supportConsent("granted").as(ConsentResult.Granted),
        )
    } yield res

  /**
   * Withdraw. The repo's Boolean says whether a LIVE grant was actually revoked; a withdrawal of an
   * already-expired / already-revoked / never-granted thread is idempotent for the customer (still
   * 200 — the end state they asked for holds) but meters as `revoke_noop`, so the consent panel
   * counts real withdrawals rather than inflating on repeat clicks.
   */
  private def applyRevoke(claims: JwtClaims, g: ConsentGrant.Claims, now: Instant) =
    consentRepo
      .revoke(claims.hh, g.threadId, now)
      .foldZIO(
        e =>
          ZIO.logWarning(s"support: consent revoke failed: ${e.getMessage}") *>
            AppMetrics.supportConsent("error").as(ConsentResult.Error),
        revokedLive =>
          ZIO.logInfo(
            s"support: data-access consent REVOKED household=${claims.hh.value} " +
              s"thread=${g.threadId} by=${claims.sub} wasLive=$revokedLive",
          ) *> AppMetrics
            .supportConsent(if revokedLive then "revoked" else "revoke_noop")
            .as(ConsentResult.Revoked),
      )

  /**
   * Is there a LIVE customer grant for this (household, thread)? Fail-CLOSED: a DB error is logged
   * and read as "no consent" — the responder must never widen a token's scope because a lookup
   * failed. The ONE reader of the consent record (single-source-of-truth).
   */
  private def consentGranted(hh: HouseholdId, threadId: String, now: Instant): UIO[Boolean] =
    consentRepo
      .isGranted(hh, threadId, now)
      .catchAll(e =>
        ZIO
          .logWarning(s"support: consent lookup failed (treating as no consent): ${e.getMessage}")
          .as(false),
      )

  // ── Token plumbing ───────────────────────────────────────────────────────────

  private def withClaims(action: String, bearer: Option[String])(
      f: ConsentToken.Claims => UIO[AgentActionResult],
  ): UIO[AgentActionResult] =
    withClaimsE(action, bearer)(claims => f(claims).map(Left(_))).map(_.merge)

  private def withClaimsE[A](action: String, bearer: Option[String])(
      f: ConsentToken.Claims => UIO[Either[AgentActionResult, A]],
  ): UIO[Either[AgentActionResult, A]] =
    if !cfg.agentEndpointsEnabled then
      AppMetrics.supportAgentAction(action, "disabled").as(Left(AgentActionResult.Disabled))
    else
      clock.instant.flatMap { now =>
        bearer.map(_.trim).filter(_.nonEmpty) match {
          case None        =>
            AppMetrics.supportAgentAction(action, "denied").as(Left(AgentActionResult.Denied))
          case Some(token) =>
            ConsentToken.verify(token, now, cfg.agentTokenSecretTrimmed) match {
              case Left(_)       =>
                AppMetrics.supportAgentAction(action, "denied").as(Left(AgentActionResult.Denied))
              case Right(claims) => f(claims)
            }
        }
      }

  private def done(action: String, r: AgentActionResult): UIO[AgentActionResult] =
    AppMetrics.supportAgentAction(action, AgentActionResult.label(r)).as(r)
}

object SupportResponder {

  /**
   * #2430 — the PRIOR turns of a thread: the fetched timeline minus the message we are dispatching
   * on. Plain fires the webhook once the inbound message is already ON the timeline, so the fetched
   * history normally ENDS with an echo of it; leaving that in would render the same words twice
   * (once as history, once as `<customer_message>`) and read as if the customer said it twice.
   *
   * Only a TRAILING customer echo is dropped, and only on an exact (trimmed) text match — an
   * identical message the customer genuinely sent earlier in the thread is a real prior turn and
   * stays. Pure, so the rule is unit-pinnable.
   */
  def priorTurns(
      fetched: List[PlainThreadMessage],
      latest: String,
  ): List[PlainThreadMessage] =
    fetched.lastOption match {
      case Some(m) if m.role == ThreadMessageRole.Customer && m.text.trim == latest.trim =>
        fetched.init
      case _                                                                             => fetched
    }

  /**
   * The attribution on every agent-authored reply — the customer sees the answer came from the AI
   * assistant and knows a human is one ask away (autonomous send, 2026-07-17).
   */
  val AiReplyAttribution: String =
    "🤖 *WifiHaven support assistant — reply \"talk to a human\" any time and a teammate will follow up.*"

  /**
   * The FIXED reject a new email from an unregistered sender receives (#2307). Static — NEVER
   * AI-generated (no token burn) — and generic: it names no accounts and reveals only whether the
   * sender's own address is registered (their own info). Points the sender at the two authenticated
   * intake paths (in-app chat / beta access).
   */
  val UnregisteredRejectTemplate: String =
    "Thanks for reaching out. WifiHaven support is available to registered customers — please " +
      "sign in at https://app.wifihaven.net and use the in-app support chat. If you don't have an " +
      "account yet, you can request beta access at https://app.wifihaven.net/beta."

  /**
   * #2419 — how long a customer's data-access grant stays live. Per-THREAD and time-boxed: a
   * support conversation's active window, long enough for an async back-and-forth, short enough
   * that a forgotten grant lapses on its own. Re-granting is one click (the agent just asks again).
   * A constant, not config: it is a security property of the flow, not a per-deployment knob.
   */
  val ConsentTtl: java.time.Duration = java.time.Duration.ofHours(24)

  /**
   * #2419 — how long a posted consent LINK can be redeemed. Derived from [[ConsentTtl]] (not a
   * second literal, so the two cannot drift) so a customer who reads the thread the next morning
   * can still act on it; the link is a capability to be ASKED, not a grant — redeeming it still
   * needs their authenticated session.
   */
  val ConsentLinkTtl: java.time.Duration = ConsentTtl

  /**
   * #2419 — the FIXED, server-authored consent prompt posted into the thread when the agent asks
   * for data access. Never agent-authored: the agent supplies no text on that path, so it cannot
   * craft a phishing message under our attribution. Names exactly what is shared, that it is
   * read-only, that it is scoped to this conversation, and how to say no (do nothing).
   */
  def consentPromptTemplate(consentUrl: String): String =
    s"""$AiReplyAttribution
       |
       |To answer that I need to look at your account — your plan, your profiles, and how many
       |devices you have. I can't see any of that without your permission.
       |
       |**[Allow me to read your account summary]($consentUrl)**
       |
       |You'll be asked to confirm in your WifiHaven dashboard (you'll need to be signed in). It's
       |read-only, it covers this conversation only, it expires after ${ConsentTtl.toHours} hours,
       |and you can withdraw it from the same page at any time. If you'd rather not, just ignore
       |this and tell me what you're seeing — I'll help without it, or hand you to a human
       |teammate.""".stripMargin

  /**
   * #2419 — the outcome of a CUSTOMER consent action (`POST /api/support/consent`). Bounded enum;
   * the route maps it to a status and it labels `support_consent_total{outcome}`.
   */
  enum ConsentResult {
    case Granted
    case Revoked
    // Bad, tampered, or expired consent link — the customer can ask the assistant for a new one.
    case Invalid
    // The link belongs to another household than the authenticated session. Writes nothing.
    case Mismatch
    case Disabled
    case Error
  }

  /**
   * Bounded outcome enum for the webhook path — the `support_ai_draft_total{outcome}` label set.
   */
  enum WebhookOutcome   {
    case Dispatched
    // #2307: a NEW inbound email whose From matched a registered household admin → dispatched
    // (authenticated), kept distinct from the UI-origin `Dispatched` for cost/attribution.
    case EmailRegisteredDispatched
    // #2307: a NEW inbound email from an UNREGISTERED address → the fixed static reject (no AI).
    case EmailUnregisteredRejected
    case SkippedUnauthenticated
    // #2403 loop guard: a non-inbound / non-customer event (our own `thread.chat_sent` reply, a
    // non-customer actor, or a bodyless identified metadata event) — deliberately never dispatched.
    case SkippedNotInbound
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
     * dispatcher logged it at ERROR with the likely fix named inline.
     */
    case ConfigError
  }
  object WebhookOutcome {
    def label(o: WebhookOutcome): String = o match {
      case Dispatched                => "dispatched"
      case EmailRegisteredDispatched => "email_registered_dispatched"
      case EmailUnregisteredRejected => "email_unregistered_rejected"
      case SkippedUnauthenticated    => "skipped_unauthenticated"
      case SkippedNotInbound         => "skipped_not_inbound"
      case RateLimited               => "rate_limited"
      case InvalidSignature          => "invalid_signature"
      case Malformed                 => "malformed"
      case Disabled                  => "disabled"
      // #2416: both dispatch-failure cases keep the SAME `outcome` value — the aggregate
      // `outcome=error` series is unchanged; they differ only on `reason` below.
      case Error | ConfigError       => "error"
    }

    /**
     * #2416 — the bounded `reason` companion label on `support_ai_draft_total`: WHY a dispatch
     * failed, so a permanently-dead responder (`config`) is distinguishable from a blip
     * (`transient`). Every other outcome is `none` (nothing to attribute), so no sample is missing
     * the label and a PromQL `sum by (reason)` never silently drops one. Bounded by this match —
     * never a per-thread / per-household value (the §4 cardinality firewall). The vocabulary is
     * [[CloudAgentDispatch.Reason]], shared with the press path.
     */
    def reason(o: WebhookOutcome): String = o match {
      case ConfigError => CloudAgentDispatch.Reason.Config
      case Error       => CloudAgentDispatch.Reason.Transient
      case _           => CloudAgentDispatch.Reason.None
    }
  }

  /**
   * Bounded result enum for the agent endpoints — the `support_agent_action_total` outcome set.
   * `Denied` is any token failure (missing / tampered / expired — uniform to the caller);
   * `NoConsent` is a VALID token without the data scope (the household read's 403). Both meter as
   * "denied" so the label space stays bounded.
   */
  enum AgentActionResult   {
    case Ok
    case Denied
    case NoConsent
    case RateLimited
    case Disabled
    case Error
  }
  object AgentActionResult {
    def label(r: AgentActionResult): String = r match {
      case Ok          => "ok"
      case Denied      => "denied"
      case NoConsent   => "denied"
      case RateLimited => "rate_limited"
      case Disabled    => "disabled"
      case Error       => "error"
    }
  }

  /**
   * The consented household read response — a BOUNDED account summary (name, plan, counts, profile
   * names + pause state). Deliberately no MACs, no hostnames, no traffic, no per-device rows: it
   * grounds a support answer without becoming an exfiltration payload if it leaks into a draft.
   */
  final case class ProfileSummary(name: String, paused: Boolean)
  final case class HouseholdSummary(
      name: String,
      plan: Option[String],
      founding: Option[Boolean],
      deviceCount: Int,
      profileCount: Int,
      profiles: List[ProfileSummary],
  )
  object ProfileSummary   {
    given JsonCodec[ProfileSummary] = DeriveJsonCodec.gen[ProfileSummary]
  }
  object HouseholdSummary {
    given JsonCodec[HouseholdSummary] = DeriveJsonCodec.gen[HouseholdSummary]
  }
}
