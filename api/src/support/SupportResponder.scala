package wifihaven.api.support

import wifihaven.api.SupportConfig
import wifihaven.api.auth.RateLimiter
import wifihaven.api.db.{DeviceRepo, HouseholdBillingRepo, HouseholdRepo, ProfileRepo}
import wifihaven.api.metrics.AppMetrics
import wifihaven.shared.Clock
import wifihaven.shared.types.HouseholdId
import zio.*
import zio.json.*

/**
 * #2200 (support intake C, epic #2197) — the Claude support responder, wired to Plain per the #2241
 * access model. Two halves, both config-gated (dark by default):
 *
 * **Inbound ([[handleWebhook]])**: Plain's signed new-message webhook → HMAC verify → the
 * UI-ORIGINATED gate (operator constraint 2026-07-14: the responder acts ONLY on threads that
 * originated from an authenticated UI submission — the #2199 identified widget stamps
 * `tenantIdentifier = household_id` on the Plain customer, so a thread whose tenant does not
 * resolve to a real household is cold inbound email and MUST NOT trigger the agent; this is the
 * cost/abuse control preventing unauthenticated token burn) → mint the per-session [[ConsentToken]]
 * (thread- + household-bound, consent-scoped, short-TTL) → dispatch a cloud-agent session
 * ([[CloudAgentDispatcher]]). The inbound message text is UNTRUSTED DATA end to end.
 *
 * **Agent-facing ([[agentDraft]] / [[agentFileIssue]] / [[agentHousehold]])**: the dispatched
 * agent's ONLY credential is the token; every side effect comes back through these endpoints where
 * the guarantees are enforced structurally, not by prompt:
 *   - drafts post into the token-bound thread ONLY (v1 draft→approve→send: the note is labeled as
 *     an AI draft; a human sends in Plain — no autonomous send path exists);
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
    // storm) hits a hard ceiling instead of an open-ended bill. Cold email never reaches this point
    // (the UI-origin gate is the first cost control).
    dispatchThreadLimiter: RateLimiter,
    dispatchGlobalLimiter: RateLimiter,
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
      PlainWebhook.verifyAndParse(rawBody, sigHeader, cfg.webhookSecretTrimmed) match {
        case Left(PlainWebhook.VerifyError.MissingSignature) | Left(
              PlainWebhook.VerifyError.BadSignature,
            ) =>
          meter(WebhookOutcome.InvalidSignature)
        case Left(PlainWebhook.VerifyError.MalformedPayload) =>
          meter(WebhookOutcome.Malformed)
        case Right(event)                                    =>
          dispatchIfUiOriginated(event).flatMap(meter)
      }

  /**
   * The UI-ORIGINATED gate + dispatch. The tenant identifier must parse AND resolve to an existing
   * household row — presence of the attribute alone is not enough (anyone can email support; only
   * the #2199 identified-widget path stamps a resolvable household). Threads without a provable
   * authenticated origin are skipped WITHOUT any Claude/agent call (`skipped_unauthenticated`).
   */
  private def dispatchIfUiOriginated(event: PlainNewMessageEvent): UIO[WebhookOutcome] =
    event.tenantIdentifier.flatMap(_.toLongOption) match {
      case None                              => ZIO.succeed(WebhookOutcome.SkippedUnauthenticated)
      case Some(_) if event.threadId.isEmpty => ZIO.succeed(WebhookOutcome.SkippedUnauthenticated)
      case Some(raw)                         =>
        val hh = HouseholdId(raw)
        householdRepo.findById(hh).catchAll(_ => ZIO.none).flatMap {
          case None            => ZIO.succeed(WebhookOutcome.SkippedUnauthenticated)
          case Some(household) =>
            dispatchThreadLimiter.tryAcquire(s"thread:${event.threadId}").flatMap { threadOk =>
              dispatchGlobalLimiter.tryAcquire("global").flatMap { globalOk =>
                if !threadOk || !globalOk then ZIO.succeed(WebhookOutcome.RateLimited)
                else dispatchFor(event, hh, household)
              }
            }
        }
    }

  private def dispatchFor(
      event: PlainNewMessageEvent,
      hh: HouseholdId,
      household: wifihaven.api.db.Household,
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
      case DispatchOutcome.Dispatched => WebhookOutcome.Dispatched
      case DispatchOutcome.Disabled   => WebhookOutcome.Disabled
      case DispatchOutcome.Error      => WebhookOutcome.Error
    }

  private def meter(o: WebhookOutcome): UIO[WebhookOutcome] =
    AppMetrics.supportAiDraft(WebhookOutcome.label(o)).as(o)

  // ── Agent-facing: the token-authenticated callback endpoints ────────────────

  /**
   * Post the agent's draft into the token-bound Plain thread as an AI-labeled note. The thread and
   * household come FROM the verified token — the request body carries only the draft text, so a
   * hijacked agent cannot aim a draft at another thread or household. v1 is draft→approve→send:
   * this writes a labeled draft the operator reviews in the Plain inbox; nothing here sends to the
   * customer.
   */
  def agentDraft(bearer: Option[String], markdown: String): UIO[AgentActionResult] =
    withClaims("draft", bearer) { claims =>
      val write = PlainThreadWrite(
        customerExternalId = claims.householdId.value.toString,
        tenantIdentifier = claims.householdId.value.toString,
        // TODO(#2240): PlainClient.writeThread's live impl uses createThread; switching to the
        // reply-to-thread mutation against `claims.threadId` is a go-live Plain-provisioning item.
        // The trait seam is deliberate — the thread binding is enforced HERE either way.
        title = s"[AI draft] support thread ${claims.threadId}",
        markdown = s"$AiDraftLabel\n\n$markdown",
      )
      plain.writeThread(write).flatMap {
        case PlainOutcome.Ok       => done("draft", AgentActionResult.Ok)
        case PlainOutcome.Disabled => done("draft", AgentActionResult.Disabled)
        case PlainOutcome.Error    => done("draft", AgentActionResult.Error)
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
      issueThreadLimiter.tryAcquire(s"thread:${claims.threadId}").flatMap { threadOk =>
        issueGlobalLimiter.tryAcquire("global").flatMap { globalOk =>
          if !threadOk || !globalOk then done("issue", AgentActionResult.RateLimited)
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

  /** The visible marker on every agent-authored Plain note — the operator's cue to review. */
  val AiDraftLabel: String = "🤖 **AI draft — review before sending.**"

  /**
   * Bounded outcome enum for the webhook path — the `support_ai_draft_total{outcome}` label set.
   */
  enum WebhookOutcome   {
    case Dispatched
    case SkippedUnauthenticated
    case RateLimited
    case InvalidSignature
    case Malformed
    case Disabled
    case Error
  }
  object WebhookOutcome {
    def label(o: WebhookOutcome): String = o match {
      case Dispatched             => "dispatched"
      case SkippedUnauthenticated => "skipped_unauthenticated"
      case RateLimited            => "rate_limited"
      case InvalidSignature       => "invalid_signature"
      case Malformed              => "malformed"
      case Disabled               => "disabled"
      case Error                  => "error"
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
