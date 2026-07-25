package wifihaven.api.support

import wifihaven.api.SupportConfig
import wifihaven.api.auth.RateLimiter
import wifihaven.api.db.{
  DeviceRepo,
  Household,
  HouseholdBillingRepo,
  HouseholdRepo,
  ProfileRepo,
  UserRepo,
}
import wifihaven.api.metrics.AppMetrics
import wifihaven.shared.{Clock, UserRole}
import wifihaven.shared.types.HouseholdId
import zio.*
import zio.json.*

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
) {
  import SupportResponder.*

  // ── Inbound: the signed Plain webhook ────────────────────────────────────────

  /**
   * Handle one Plain new-message webhook delivery. Never fails — every path resolves to a metered
   * [[WebhookOutcome]] the route maps to a status. Signature verification runs FIRST, over the raw
   * body; nothing is parsed or acted on for an unsigned/forged payload.
   */
  def handleWebhook(rawBody: String, sigHeader: Option[String]): UIO[WebhookOutcome] =
    if !cfg.responderEnabled then meter(WebhookOutcome.Disabled)
    else
      PlainWebhook.verifyAndParse(rawBody, sigHeader, cfg.plain.webhookSecretTrimmed) match {
        case Left(PlainWebhook.VerifyError.MissingSignature) | Left(
              PlainWebhook.VerifyError.BadSignature,
            ) =>
          meter(WebhookOutcome.InvalidSignature)
        case Left(PlainWebhook.VerifyError.MalformedPayload) =>
          meter(WebhookOutcome.Malformed)
        case Right(event)                                    =>
          dispatchIfAuthorized(event).flatMap(meter)
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
   *   - consent (`event.consent` → the token's `dataAccess`) is never set from a cold email — only
   *     the #2199 identified widget stamps `dataConsent`, so a forged-From dispatch mints a
   *     data-scope-LESS token: the agent can reply but the household-read endpoint refuses it;
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
          // No household — this sender maps to no tenant. #2240 switches writeThread to a
          // reply-INTO-thread mutation against event.threadId (the customer-visible email reply);
          // the trait seam keeps that a go-live provisioning change, not a code change here.
          customerExternalId = event.customerExternalId,
          tenantIdentifier = "",
          title = UnregisteredRejectTitle,
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
      billing <- billingRepo.findByHousehold(hh).catchAll(_ => ZIO.none)
      now     <- clock.instant
      token = ConsentToken.mint(
        household = hh,
        threadId = event.threadId,
        dataAccess = event.consent,
        now = now,
        ttl = cfg.agentTokenTtl,
        secret = cfg.agentTokenSecretTrimmed,
      )
      // Audit trail for every mint (#2241) — household + thread + scope, never the token.
      _       <- ZIO.logInfo(
        s"support: minted agent token for household=${hh.value} thread=${event.threadId} dataAccess=${event.consent}",
      )
      outcome <- dispatcher.dispatch(
        AgentDispatch(
          threadId = event.threadId,
          householdName = household.name,
          plan = billing.map(_.status),
          dataConsent = event.consent,
          agentToken = token,
          customerMessage = event.messageText,
        ),
      )
    } yield outcome match {
      case DispatchOutcome.Dispatched => success
      case DispatchOutcome.Disabled   => WebhookOutcome.Disabled
      case DispatchOutcome.Error      => WebhookOutcome.Error
    }

  private def meter(o: WebhookOutcome): UIO[WebhookOutcome] =
    AppMetrics.supportAiDraft(WebhookOutcome.label(o)).as(o)

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
        customerExternalId = claims.householdId.value.toString,
        tenantIdentifier = claims.householdId.value.toString,
        // TODO(#2240): PlainClient.writeThread's live impl uses createThread; switching to the
        // reply-to-thread mutation against `claims.threadId` (the customer-visible send) is a
        // go-live Plain-provisioning item. The trait seam is deliberate — the thread binding is
        // enforced HERE either way.
        title = s"[AI reply] support thread ${claims.threadId}",
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
  val UnregisteredRejectTitle: String    = "Re: your message to WifiHaven support"
  val UnregisteredRejectTemplate: String =
    "Thanks for reaching out. WifiHaven support is available to registered customers — please " +
      "sign in at https://app.wifihaven.net and use the in-app support chat. If you don't have an " +
      "account yet, you can request beta access at https://app.wifihaven.net/beta."

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
    case Error
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
      case Error                     => "error"
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
