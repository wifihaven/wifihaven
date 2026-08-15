package wifihaven.api.support

import wifihaven.api.SupportConfig
import wifihaven.api.agent.AgentCredential
import wifihaven.api.auth.{JwtClaims, RateLimiter}
import wifihaven.api.db.{
  DeviceRepo,
  GrantOutcome,
  Household,
  HouseholdBillingRepo,
  HouseholdRepo,
  HouseholdSettingsRepo,
  ProfileRepo,
  SupportConsentRepo,
  UserRepo,
}
import wifihaven.api.metrics.AppMetrics
import wifihaven.api.notify.{EscalationChannel, EscalationKind, EscalationNotice, Notifier}
import wifihaven.api.observability.AgentTokenRejection
import wifihaven.api.policy.{PolicyService, ProfileDayState, TimeStatusService}
import wifihaven.shared.{Clock, MacBlockReason, UserRole}
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
 * consent-scoped, expiring) → dispatch a cloud-agent session ([[CloudAgentDispatcher]]). The
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
 *     binds to the sender's household). Resolving that admin ALSO stamps the household→Plain
 *     customer mapping (#2505, [[mapCustomerToHousehold]]) — otherwise a customer who only ever
 *     emails is never mapped, because #2435's reconcile runs on the SPA identity path alone.
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
 *     Since #2665 that read also carries TODAY's per-profile screen time (used / limit / remaining
 *     / blocked-right-now) and the device→profile map, because the consent it costs the customer
 *     was buying them nothing on the question this desk is asked most.
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
    // #2665: the two inputs the consented read needs to answer "how much screen time did X use
    // today?" — the household's daily-reset settings (which fix what "today" MEANS for this
    // tenant) and the canonical day-state primitive. NOT defaulted to a noop: a silently-zero
    // usage read is exactly the confidently-wrong answer #2462 exists to prevent, and
    // no-dark-by-default forbids a dependency whose absence degrades a feature to a plausible lie.
    hsRepo: HouseholdSettingsRepo,
    // The SAME `TimeStatusService` the policy snapshot's TimeLimit decision and `GET /api/blocked`
    // read. There is no second usage computation here and there must never be one — screen-time
    // accounting has drifted between readers three times already (#2016 over-count, #2068
    // under-count, #2274 phantom usage), and a support answer that disagrees with the dashboard the
    // customer is looking at is worse than no answer (docs/process/single-source-of-truth.md).
    timeStatus: TimeStatusService,
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
    // #2437: the #578 operator-notification seam. The support agent's escalation reaches a human two
    // independent ways — the in-Plain thread mark (filterable inbox) and this out-of-band notice — so
    // one channel failing does not silently drop the handoff.
    notifier: Notifier,
    // #2437: bounds how often ONE thread can page the operator. An agent stuck in a loop (or a
    // prompt-injected one) must not be able to turn our own alert mailbox into a firehose.
    escalateThreadLimiter: RateLimiter,
    // #2472: the dispatch→completion pairing. A dispatch the transport ACCEPTED is recorded here and
    // closed by the first terminal agent callback on the same thread; a periodic sweep reports the
    // ones nobody ever closed. Without it a cloud session that accepted the trigger and then died
    // was indistinguishable from one that answered — every log line and metric said success while
    // the customer got nothing.
    dispatchTracker: DispatchTracker,
    // HOW a best-effort FOLLOW-UP is run — the one thing about those that differs between production
    // and a spec. Production forks each as a DAEMON fiber so the request/webhook fiber returns at
    // once; specs pass `identity` to run it inline. The seam controls only WHERE the effect runs,
    // never what it does, and it must be `forkDaemon` (not `timeout`/`disconnect`): a fork lets the
    // follow-up RUN ON to completion, so every branch still meters its own outcome and no multi-write
    // chain can be abandoned half-done.
    //
    // Two callers, both one-shot follow-ups whose latency the caller must not pay:
    //   - #2460, the post-grant consent resume. Its two legs (the Plain timeline read at
    //     `PlainClient.HistoryTimeout`, then the cloud-agent dispatch at the transport's
    //     `RequestTimeout`) together exceed the SPA's own `REQUEST_TIMEOUT_MS`
    //     (web/src/api/client.ts), so on the request fiber a SUCCESSFUL grant could abort
    //     client-side and be reported to the customer as a broken link.
    //   - #2505, the household→Plain customer mapping written from the webhook path
    //     ([[mapCustomerToHousehold]]). `upsertCustomer` chains the tenant write, the tenant fields,
    //     the customer upsert and — on an email collision — the two-leg reconcile, each bounded only
    //     by the HTTP client's `RequestTimeout`; awaited, that could push our webhook ack past
    //     Plain's delivery timeout and earn a redelivery.
    //
    // `forkDaemon`, NOT the `forkScoped` that #1247 moved the Main.scala background loops to: those
    // are app-lifetime loops that must be interrupted before the Hikari pool closes, whereas these
    // are one-shot follow-ups with no Scope in reach at the route layer. The accepted cost is a
    // deploy-time window (bounded by the transport timeouts) in which an in-flight follow-up can be
    // interrupted or see a closing pool. Both callers survive it: the consent grant is already
    // committed, so the customer keeps their consent and at worst re-asks; the mapping is idempotent
    // and re-attempted on that household's next message. Either way it is one dropped sample.
    //
    // Deliberately UNCAPPED and un-deduped: one fork per triggering event, so a new inbound email
    // stamps the mapping about twice — on the bodyless `thread.thread_created` and again on the
    // `email_received` that carries the body (and the earlier of the two is the better moment, not
    // waste). That is fine at the rate it fires: forks track inbound CUSTOMER email, both callers
    // are idempotent, and every leg is bounded by the transport's own `RequestTimeout`. A cap here
    // would buy nothing the origin gate and the dispatch limiters do not already buy.
    //
    // This is the ONLY parameter with a default, so it must stay LAST — every construction site
    // (HttpRoutes, the specs) passes the ones above positionally.
    runDetached: UIO[Unit] => UIO[Unit] = _.forkDaemon.unit,
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
        case OriginLookup.Resolved(hh, household) if event.messageText.nonEmpty =>
          rateLimitedDispatch(event, hh, household, WebhookOutcome.Dispatched)
        case OriginLookup.Resolved(_, _)                                        =>
          // Identified, but a bodyless metadata event (e.g. thread_created) — nothing to answer;
          // the real message rides the following chat_received/email_received.
          ZIO.succeed(WebhookOutcome.SkippedNotInbound)
        // #2462: the lookup FAILED — we do not know whether this tenant resolves. Falling through
        // to the email gate would let a DB blip re-decide an identified thread as cold.
        case OriginLookup.Failed                                                =>
          ZIO.succeed(WebhookOutcome.OriginLookupFailed)
        case OriginLookup.NoMatch                                               =>
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
   *
   * #2462: a read that THREW is [[OriginLookup.Failed]], never `NoMatch` — see
   * [[originLookupFailed]].
   */
  private def resolveTenantHousehold(event: PlainNewMessageEvent): UIO[OriginLookup] =
    event.tenantIdentifier.flatMap(_.toLongOption) match {
      case Some(raw) if event.threadId.nonEmpty =>
        val hh = HouseholdId(raw)
        householdRepo
          .findById(hh)
          .foldZIO(
            originLookupFailed("tenant_household", _),
            {
              case Some(household) => ZIO.succeed(OriginLookup.Resolved(hh, household))
              case None            => ZIO.succeed(OriginLookup.NoMatch)
            },
          )
      case _                                    => ZIO.succeed(OriginLookup.NoMatch)
    }

  /**
   * #2462 — a DB failure during origin resolution is LOUD and TERMINAL for this delivery, never
   * collapsed into "no such origin".
   *
   * The two are opposite facts: `NoMatch` asserts we looked and this sender has no registered
   * origin, which on a NEW email thread makes us tell them so ([[staticReject]] — the #2307 "you're
   * not a registered customer" message). Reading a failed lookup as `NoMatch` therefore sends a
   * real, paying, registered admin that reject because `users` was briefly unreadable — and meters
   * it as a routine reject, so nothing anywhere records that it was wrong. `Failed` resolves to
   * [[WebhookOutcome.OriginLookupFailed]] instead: nothing is said to the customer, nothing is
   * dispatched, and the outcome is separately visible on `support_ai_draft_total{outcome}`.
   */
  private def originLookupFailed(stage: String, e: Throwable): UIO[OriginLookup] =
    ZIO
      .logError(
        s"support: origin lookup FAILED at $stage — refusing to treat an unreadable database as " +
          s"an unregistered sender: ${e.getMessage}",
      )
      .as(OriginLookup.Failed)

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
          case OriginLookup.Resolved(hh, household) =>
            val outcome =
              if event.threadId.nonEmpty && event.messageText.nonEmpty then
                rateLimitedDispatch(event, hh, household, WebhookOutcome.EmailRegisteredDispatched)
              else
                // Registered, but no thread to bind or a bodyless new-thread event — the answerable
                // message rides the following body event (chat_received/email_received).
                ZIO.succeed(WebhookOutcome.SkippedUnauthenticated)
            // #2505: the mapping runs DETACHED (`forkDaemon` in production), so neither the agent
            // session nor the webhook ack waits on a slow Plain (see [[mapCustomerToHousehold]]).
            outcome <* runDetached(mapCustomerToHousehold(hh, household, email))
          // #2462: we could not READ the registry, so we have not established this sender is
          // unregistered — say nothing rather than reject a customer on a failed lookup. No #2505
          // mapping either: there is no household to map them to.
          case OriginLookup.Failed  => ZIO.succeed(WebhookOutcome.OriginLookupFailed)
          case OriginLookup.NoMatch =>
            // Unregistered: reject a NEW thread once; skip continuations (no re-reject backscatter).
            if event.isNewThread then staticReject(event)
            else ZIO.succeed(WebhookOutcome.SkippedUnauthenticated)
        }
    }

  /**
   * #2505 — stamp the household→Plain-customer mapping from the EMAIL path.
   *
   * #2435's reconcile fires from [[SupportService.identity]], i.e. only when an admin loads the
   * SPA. A household whose admin emails support and never opens the dashboard was therefore never
   * mapped: its Plain customer kept `externalId: null`, so the operator triaging the thread saw no
   * tenant and no plan/entitlement context (the whole point of #2240), and every later message fell
   * through to [[resolveAdminHousehold]] — a strictly narrower key (exact, case-sensitive
   * `users.email` AND `role == Admin`), which treats a non-admin member or an
   * aliased/differently-cased admin address as unregistered. Nothing retried it. The gate has just
   * proven this sender IS a household admin and holds both halves the mapping needs, so it writes
   * the same payload the identity path writes — [[PlainCustomerUpsert.forHousehold]] is the one
   * builder, so the two producers cannot drift.
   *
   * Best-effort and NOT on the critical path, but also NOT silent
   * (docs/process/no-dark-by-default.md):
   *   - it is decided AFTER the dispatch, so nothing the customer waits on is behind it, and it is
   *     handed to [[runDetached]] (`forkDaemon` in production) so the webhook fiber does not wait
   *     on it either. `upsertCustomer` is a CHAIN of Plain writes — tenant, tenant fields,
   *     customer, and on an email collision the two-leg reconcile — each bounded only by the HTTP
   *     client's `RequestTimeout`, so awaiting it could push our ack past Plain's delivery timeout
   *     and earn a redelivery. A `timeout` would be the wrong instrument for the same reason a fork
   *     is the right one: interrupting mid-chain can leave the reconcile's `externalId` patch
   *     written with its tenant join missing — a state no later attempt repairs, because the
   *     primary path carries `tenantIdentifiers` on `onCreate` only (#2253) — and would drop the
   *     sample that says so;
   *   - it is idempotent and `PlainClient.upsertCustomer` never fails, so it cannot break the
   *     dispatch or the reply however it goes;
   *   - and it meters `support_customer_upsert_total{outcome,reason}` from inside `PlainClient` —
   *     the SAME series and reason vocabulary #2435 settled on, so a broken mapping is attributable
   *     on the existing panel regardless of which producer attempted it. Metering here as well
   *     would double-count and could only carry the outcome, never the Plain-side reason. Running
   *     on a forked fiber is what keeps that attribution: the chain completes and meters even
   *     though nobody is waiting for it.
   *
   * A degraded billing read is warned and degraded to "no plan attributes" rather than skipping the
   * write: the externalId mapping — the load-bearing half — is worth stamping even when the
   * entitlement context is momentarily unreadable, and the next call repairs the attributes.
   *
   * Keying the Plain customer on the HOUSEHOLD id is SOUND, not an aliasing hazard: #2512 made "a
   * household has exactly one admin" structurally true (V86's partial unique index
   * `uq_users_household_single_admin`, plus the 409 guard on `POST /api/users` and `PATCH
   * /api/users/{id}`), so there is no second admin whose address could rewrite the household's one
   * externalId-keyed customer into Plain's workspace-wide email uniqueness — the non-self-healing
   * `email_collision` bucket that motivated #2512.
   */
  private def mapCustomerToHousehold(
      hh: HouseholdId,
      household: Household,
      email: String,
  ): UIO[Unit] =
    billingRepo
      .findByHousehold(hh)
      .catchAll(e =>
        ZIO
          .logWarning(
            s"support: could not read billing for household ${hh.value} while mapping its Plain " +
              s"customer; mapping without plan attributes: ${e.getMessage}",
          )
          .as(None),
      )
      .flatMap { billing =>
        plain
          .upsertCustomer(
            PlainCustomerUpsert.forHousehold(
              householdId = hh,
              email = email,
              householdName = household.name,
              plan = billing.map(_.status),
              founding = billing.map(_.founding),
            ),
          )
          .unit
      }

  /**
   * Resolve a sender From address to the household of the ADMIN who owns it. Match is exact
   * (aligned with the case-sensitive `uq_users_email` constraint used by email login). A non-admin
   * registered email is NOT admitted (the gate is admins only, #2307).
   */
  private def resolveAdminHousehold(email: String): UIO[OriginLookup] =
    userRepo
      .findByEmail(email)
      .foldZIO(
        originLookupFailed("admin_email", _),
        {
          case Some(user) if user.role == UserRole.Admin =>
            householdRepo
              .findById(user.householdId)
              .foldZIO(
                originLookupFailed("admin_household", _),
                {
                  case Some(household) =>
                    ZIO.succeed(OriginLookup.Resolved(user.householdId, household))
                  case None            => ZIO.succeed(OriginLookup.NoMatch)
                },
              )
          case _                                         => ZIO.succeed(OriginLookup.NoMatch)
        },
      )

  /**
   * The dispatch cost caps — the ONE place they are drawn (#2261 short-circuit: the global bucket
   * is drawn only when the per-thread cap allowed, else one capped thread would drain the shared
   * daily budget and lock every other household out of the AI-reply path). Both callers that can
   * start an agent session go through here — the inbound webhook and the #2460 consent resume — so
   * the ordering and the short-circuit cannot drift between them.
   */
  private def withDispatchCaps[A](threadId: String)(onCapped: UIO[A])(dispatch: UIO[A]): UIO[A] =
    dispatchThreadLimiter.tryAcquire(s"thread:$threadId").flatMap { threadOk =>
      if !threadOk then onCapped
      else
        dispatchGlobalLimiter.tryAcquire("global").flatMap { globalOk =>
          if !globalOk then onCapped else dispatch
        }
    }

  /** `success` is the metered outcome (UI vs email origin). */
  private def rateLimitedDispatch(
      event: PlainNewMessageEvent,
      hh: HouseholdId,
      household: Household,
      success: WebhookOutcome,
  ): UIO[WebhookOutcome] =
    withDispatchCaps(event.threadId)(ZIO.succeed(WebhookOutcome.RateLimited))(
      dispatchFor(event, hh, household, success),
    )

  /**
   * The ONE agent-session assembly: bounded account context → mint the #2241 token → audit the mint
   * → dispatch. Every path that starts a cloud session calls this (the inbound webhook via
   * [[dispatchFor]], the #2460 consent resume via [[redispatchAfterGrant]]), so the token shape,
   * the audit line, and the dispatch payload have exactly one implementation. Caps are the CALLER's
   * ([[withDispatchCaps]]) so a capped request never pays for the account reads.
   */
  private def dispatchAgentSession(
      hh: HouseholdId,
      householdName: String,
      threadId: String,
      customerMessage: String,
      history: List[PlainThreadMessage],
      dataAccess: Boolean,
      now: Instant,
      // #2481: the inbound email's subject, when the message came in as email. `None` on chat, and
      // on the #2460 resume — that re-dispatches a turn read back off the Plain timeline, and
      // `PlainThreadMessage` carries no subject (the timeline query selects none). So a resume of a
      // thread whose question lived in the SUBJECT re-asks with the body alone; tracked by #2495.
      subject: Option[String] = None,
  ): UIO[DispatchOutcome] =
    for {
      // TODO(#2520): this swallow is the #2462 bug's surviving sibling — a DB blip hands the agent
      // `plan = None` on EVERY dispatch, with no log and no metric, indistinguishable from a
      // household that genuinely has no billing row. Deliberately out of scope for #2462 (which
      // named only `agentHousehold` and the two origin resolvers); unlike those, this one must not
      // fail the dispatch — a customer message still has to get answered when we cannot read a plan.
      billing <- billingRepo.findByHousehold(hh).catchAll(_ => ZIO.none)
      // #2668: identity for THIS dispatch, so a callback can be matched against the session that
      // owns the thread's current turn. Minted here rather than derived from the token bytes —
      // two dispatches on one thread in the same second produce an identical payload otherwise.
      sessionId = ConsentToken.newSessionId()
      token     = ConsentToken.mint(
        household = hh,
        threadId = threadId,
        dataAccess = dataAccess,
        now = now,
        ttl = cfg.agentTokenTtl,
        secret = cfg.agentTokenSecretTrimmed,
        sessionId = sessionId,
      )
      // Audit trail for every mint (#2241) — household + thread + scope, never the token. ONE line
      // shape, so an operator grep finds resume mints and webhook mints alike.
      _       <- ZIO.logInfo(
        s"support: minted agent token for household=${hh.value} thread=$threadId dataAccess=$dataAccess",
      )
      outcome <- dispatcher.dispatch(
        AgentDispatch(
          threadId = threadId,
          householdName = householdName,
          plan = billing.map(_.status),
          dataConsent = dataAccess,
          agentToken = token,
          customerMessage = customerMessage,
          subject = subject,
          history = history,
        ),
      )
      // #2472: pair the dispatch with the callback that must follow it. ONLY an ACCEPTED dispatch is
      // tracked — a `Disabled` / `Error` / `ConfigError` outcome never started a cloud session, so
      // nothing is owed and the existing dispatcher-level series already reports it. The transport is
      // the SAME pure function of config the dispatcher was built from (`transportFor`), so the label
      // cannot drift from the one `support_dispatch_total{transport}` already carries.
      _       <- ZIO
        .foreachDiscard(CloudAgentDispatcher.transportLabel(cfg))(
          dispatchTracker.dispatched(
            threadId,
            sessionId,
            DispatchTracker.Subject.household(hh),
            _,
            now,
          ),
        )
        .when(outcome == DispatchOutcome.Dispatched)
    } yield outcome

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
        // #2471: the send outcome IS the outcome. Discarding it here reported a reject Plain had
        // refused as a completed one — and `support_ai_draft_total{outcome}` is built on this
        // label, so the dashboard showed a healthy reject path while zero rejects were delivered.
        // Per docs/process/no-dark-by-default.md a config-recoverable failure must FAIL LOUD; a
        // metric alone makes it visible but does not make it acceptable, which is why the refusal
        // branch below logs at ERROR with the fix named rather than only metering.
        plain.writeThread(write).flatMap {
          case PlainOutcome.Ok       =>
            ZIO.succeed(WebhookOutcome.EmailUnregisteredRejected)
          // `Disabled` is NOT a failure: the write half is switched off by the EXPLICIT named flag
          // `wifihaven.support.plain.writeEnabled` (`PlainClient.layer`), which is reported at
          // startup — a deliberate off-state, exactly what `WebhookOutcome.Disabled` means. It is
          // reachable on its own: `PlainConfig.validate` requires `plain.apiKey` when
          // `writeEnabled=true`, and `SupportConfig.missingRequiredKeys` requires it when
          // `responderEnabled=true`, but NOTHING requires `writeEnabled` itself, so
          // `responderEnabled=true` + `writeEnabled=false` boots. Routing it here would light the
          // "Plain REFUSED to send" tile and send an operator to Plain's email settings over our
          // own flag.
          case PlainOutcome.Disabled => ZIO.succeed(WebhookOutcome.Disabled)
          // A genuine refusal: Plain accepted the call and would not send. Config-recoverable and
          // permanent, so it FAILS LOUD with the fix named inline
          // (docs/process/no-dark-by-default.md — a metric makes it visible but does not make it
          // acceptable), matching how `PlainClient.addLabels` treats the sibling provisioning gap.
          // The generic `post` path only logs this at WARNING, which is right for best-effort
          // context writes but not for the reject, which IS the customer-facing action.
          case PlainOutcome.Error    =>
            ZIO
              .logError(
                "plain reject send FAILED — PROVISIONING GAP: the unregistered-sender reject was " +
                  "never delivered. Check that the Plain workspace has email SENDING enabled " +
                  "(Settings → Channels → Email §3 \"Sending emails\" verified and §4 \"Enable " +
                  "email\" on) — see docs/ops/plain-setup.md §3.1; the preceding " +
                  "`plain replyToThread failed` line carries Plain's own message",
              )
              .as(WebhookOutcome.EmailRejectSendFailed)
        }
      }
    }

  private def dispatchFor(
      event: PlainNewMessageEvent,
      hh: HouseholdId,
      household: Household,
      success: WebhookOutcome,
  ): UIO[WebhookOutcome] =
    for {
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
      outcome    <- dispatchAgentSession(
        hh = hh,
        householdName = household.name,
        threadId = event.threadId,
        customerMessage = event.messageText,
        history = priorTurns(prior, event.messageText),
        dataAccess = dataAccess,
        now = now,
        // #2481: the email subject is part of the customer's message — a question in the subject
        // with a signature-only body is ordinary email, and dropping it made those unanswerable.
        subject = event.subject,
      )
    } yield outcome match {
      case DispatchOutcome.Dispatched  => success
      case DispatchOutcome.Disabled    => WebhookOutcome.Disabled
      case DispatchOutcome.Error       => WebhookOutcome.Error
      // #2416: a permanent 4xx at the agent boundary — already logged at ERROR with the fix named by
      // CloudAgentObservability. Same `outcome=error`, distinct `reason=config`.
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
   * Malformed, and `Disabled` when it comes from the responder-dark short-circuit) carry no event,
   * so both correlation fields log as `-`. `Disabled` is NOT always pre-parse: since #2471 a dark
   * Plain write half also resolves to it from `staticReject`, which runs post-parse, so a
   * `disabled` line CAN carry a populated `thread=` / `eventType=`. NEVER the message text,
   * customer email, or any customer content (UNTRUSTED PII — see the class comment).
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
   *
   * #2508: the reply text is the ONE thing the agent fully authors, so it is credential-scrubbed
   * HERE — before anything can read it — rather than at the [[PlainClient]] transport. See
   * [[AgentCredential]] for why the seam is the callback surface and not the transport.
   */
  def agentReply(
      bearer: Option[String],
      markdown: String,
      // #2469: the live agent's echo of the `PROMPT_VERSION:` marker in its own system prompt.
      // Optional — an agent predating the marker reports nothing and is recorded `unknown`.
      promptVersion: Option[String] = None,
  ): UIO[AgentActionResult] =
    withClaims(AgentAction.Reply, bearer) { claims =>
      // #2469: recorded HERE, not in the route, because here we are PAST the token check — the
      // caller provably is the dispatched agent. The route is public (the token is verified inside
      // `withClaims`), so a route-level observe would let any anonymous POST forge `state="current"`
      // and mask a genuinely stale routine. Ahead of the send, so every AUTHENTICATED outcome
      // records — including a `Disabled` (write half dark) or failed write, which is exactly the
      // case we most want on the dashboard. Alert-only: it can never cost the customer their answer.
      AgentPromptVersion.observe(AgentPromptVersion.Channel.Support, promptVersion) *>
        // #2667: refused OUTRIGHT if this session already made us post a consent prompt — the
        // customer's decision moment is server-authored text ONLY. Outside the redaction, so a
        // suppressed reply does no work at all.
        exclusiveThreadWrite(AgentAction.Reply, claims) {
          AgentCredential
            .redact(AgentCredential.Channel.Support, AgentAction.Reply, markdown)
            .flatMap { safeMarkdown =>
              val write = PlainThreadWrite(
                // #2408: the reply posts INTO the customer's existing thread (`claims.threadId`,
                // the customer-visible send via Plain's replyToThread), NOT a new createThread. The
                // thread binding comes from the verified token — the request body carries only the
                // reply text.
                threadId = claims.threadId,
                // #2456: strip the agent's own leading copy first — the server owns this line, and
                // the agent intermittently copies it out of the thread history it now sees (#2441),
                // which showed the customer the header twice.
                markdown = s"$AiReplyAttribution\n\n${stripLeadingAttribution(safeMarkdown)}",
              )
              plain.writeThread(write).flatMap {
                case PlainOutcome.Ok       => done(AgentAction.Reply, AgentActionResult.Ok)
                case PlainOutcome.Disabled => done(AgentAction.Reply, AgentActionResult.Disabled)
                case PlainOutcome.Error    => done(AgentAction.Reply, AgentActionResult.Error)
              }
            }
        }
    }

  /**
   * File a GitHub issue on the support bot's behalf. Rate-limited per-thread and globally (the
   * volume metric feeds the operator alert), auto-labeled `support-agent` and PII-scrubbed inside
   * [[GithubIssueClient]] — the #2241 compensating control: the body that leaves this process never
   * embeds raw household-data output.
   *
   * #2508 adds the credential scrub on top of that PII scrub — different rule sets, different
   * reasons. `scrubForIssue` protects the CUSTOMER's data from a public repo;
   * [[AgentCredential.redact]] protects OUR credential from the same repo, and applies on every
   * agent-authored surface (an issue is the most public of them).
   *
   * On success it answers the created [[FiledIssue]] (#2461) — the number + public URL the agent
   * may quote to the customer; every failure stays the bounded [[AgentActionResult]] the route maps
   * to a status.
   *
   * Both limiters are consumed BEFORE the #2458 duplicate check runs, so an ask that ends up
   * creating nothing still spends thread and global budget. That is deliberate: what the limiters
   * bound is the COST an agent can impose — and a deduped ask still costs the GitHub list call —
   * not the count of issues that reach the repo. Deferring them past the scan would let a looping
   * agent hammer the GitHub API for free.
   *
   * #2454 — a session whose token carries `dataAccess=true` is REFUSED outright, before the rate
   * limiters and before the client. The scrubber alone could never be the control here: the
   * consented read returns the household NAME and the PROFILE names, which by product design are
   * typically children's given names — ordinary words that match no PII pattern and never will. So
   * the two capabilities are refused as a PAIR rather than the payload chased with regexes. Nothing
   * legitimate is lost: an agent that needed account data to understand a problem can describe the
   * symptom without republishing the account, and #2437 escalation reaches a human either way. This
   * is a property of the SESSION's scope, not of the body — a body-shaped check would be exactly
   * the regex-shaped guarantee this replaces.
   */
  def agentFileIssue(
      bearer: Option[String],
      title: String,
      body: String,
  ): UIO[Either[AgentActionResult, FiledIssue]] =
    withClaimsE(AgentAction.Issue, bearer) { (claims, _) =>
      // #2454: the consented-read scope and public-issue filing do not compose. Checked FIRST so a
      // refused session spends neither rate-limit budget nor a GitHub call — including the #2458
      // duplicate-scan list call, which the limiters otherwise deliberately charge for.
      if claims.dataAccess then
        AppMetrics.supportConsent("issue_refused_data_session") *>
          doneE(AgentAction.Issue, AgentActionResult.DataSession)
      // Same short-circuit as dispatch: a thread-capped caller must not drain the global budget.
      else
        issueThreadLimiter.tryAcquire(s"thread:${claims.threadId}").flatMap { threadOk =>
          if !threadOk then doneE(AgentAction.Issue, AgentActionResult.RateLimited)
          else
            issueGlobalLimiter.tryAcquire("global").flatMap { globalOk =>
              if !globalOk then doneE(AgentAction.Issue, AgentActionResult.RateLimited)
              else
                // #2508: title AND body — the agent authors both, and both go public.
                redactIssueText(title, body).flatMap { (safeTitle, safeBody) =>
                  github.fileIssue(IssueFileRequest(safeTitle, safeBody, claims.threadId)).flatMap {
                    // #2461: the created issue's identity rides back out so the agent can offer the
                    // customer a link. The metric label stays the bounded `ok` — never the number.
                    case IssueOutcome.Filed(ref)     =>
                      // Same `done` metering choke point as every other branch — only the RESULT
                      // differs, so there is still exactly one place the label is derived.
                      done(AgentAction.Issue, issueFiledOutcome(ref))
                        .as(Right(FiledIssue(ref.map(_.number), ref.map(_.url))))
                    // #2458: the topic was already tracked, so nothing was created. Carried on the
                    // SAME `number`/`url` fields #2461 added — the customer is pointed at the
                    // canonical issue, not told about a duplicate we declined to file — plus the
                    // `duplicate` marker so the agent's wording matches what happened.
                    case IssueOutcome.Duplicate(ref) =>
                      done(AgentAction.Issue, AgentActionResult.OkDuplicate)
                        .as(
                          Right(
                            FiledIssue(Some(ref.number), Some(ref.url), duplicate = Some(true)),
                          ),
                        )
                    case IssueOutcome.Disabled       =>
                      doneE(AgentAction.Issue, AgentActionResult.Disabled)
                    case IssueOutcome.Error          =>
                      doneE(AgentAction.Issue, AgentActionResult.Error)
                  }
                }
            }
        }
    }

  /**
   * #2508 — scrub an agent-authored issue title + body of credentials. Both fields go through the
   * SAME call, so the `reason` labels and the ERROR log are emitted once per field that actually
   * carried one. The TITLE matters as much as the body: it is what shows in the public issue list
   * and in every notification GitHub sends.
   */
  private def redactIssueText(title: String, body: String): UIO[(String, String)] =
    for {
      t <- AgentCredential.redact(AgentCredential.Channel.Support, AgentAction.Issue, title)
      b <- AgentCredential.redact(AgentCredential.Channel.Support, AgentAction.Issue, body)
    } yield (t, b)

  /**
   * The consented household read (#2241): a bounded summary of the ONE household the token is bound
   * to. The household id comes from the token, so there is no parameter through which another
   * household could be requested — single-household is enforced by construction.
   *
   * Consent is checked TWICE, and both must hold:
   *   - `claims.dataAccess` — the scope stamped into the token at mint;
   *   - a LIVE grant for this `(household, thread)` RIGHT NOW — re-read here, not trusted from the
   *     token (#2476).
   *
   * The live re-read is what makes "stop allowing" take effect immediately. Before it, `dataAccess`
   * was evaluated once at dispatch and the read trusted that stamp, so a withdrawal only bit once
   * the token expired — a residual window bounded by the agent-token TTL. #2473 raised that TTL
   * from 30 minutes to 24 hours (a cloud-agent run can be paused on usage limits and resume hours
   * later), which would have stretched the residual window to a full day. Re-reading closes it
   * instead of trading a customer's withdrawal for the reply fix. It costs one indexed lookup, and
   * only on a token that CLAIMS data access — a scope-less token is refused before the lookup, and
   * a scoped one is already on a path that does four repo queries.
   *
   * Fail-CLOSED both ways: [[consentGranted]] reads a DB error as "no consent", and the token scope
   * is still required, so this only ever narrows access.
   */
  def agentHousehold(bearer: Option[String]): UIO[Either[AgentActionResult, HouseholdSummary]] =
    withClaimsE(AgentAction.HouseholdRead, bearer) { (claims, now) =>
      // The token scope is free to check and refuses the COMMON case (most threads never grant), so
      // it short-circuits ahead of the grant lookup — the DB round trip is only spent on a token
      // that actually claims data access.
      if !claims.dataAccess then householdRead(claims, liveGrant = false)
      else
        consentGranted(claims.householdId, claims.threadId, now)
          .flatMap(live => householdRead(claims, live))
    }

  private def householdRead(
      claims: ConsentToken.Claims,
      liveGrant: Boolean,
  ): UIO[Either[AgentActionResult, HouseholdSummary]] =
    if !claims.dataAccess || !liveGrant then
      // The one 403-shaped denial (the route distinguishes it from a bad/expired token, which stays
      // a uniform 401). `read_withdrawn` (#2476) is the security-interesting half: a token that WAS
      // minted with data scope, presented after the customer withdrew — it should be rare, and a
      // rising rate means grants are being withdrawn mid-conversation.
      AppMetrics
        .supportConsent(if claims.dataAccess then "read_withdrawn" else "read_no_scope") *>
        AppMetrics
          .supportAgentAction(AgentAction.HouseholdRead, "denied")
          .as(Left(AgentActionResult.NoConsent))
    else {
      val hh = claims.householdId
      // #2462: the four reads FAIL the request rather than degrading to a plausible value. They
      // used to be individually `catchAll`-ed to none/Nil, which made a total Postgres outage and a
      // brand-new empty household byte-identical to the agent — `name=""`, `deviceCount=0`,
      // `profileCount=0` — and metered BOTH `ok`. The agent then stated those zeros to the customer
      // as account fact and reasoned onward from them ("no devices have been paired yet, so make
      // sure the router finished enrollment"), which is confidently wrong troubleshooting for a
      // household that actually has twelve devices. No-dark-by-default: a failed read is an error,
      // not a value.
      //
      // The distinction is FAILURE-vs-EMPTY, not zero-vs-nonzero. A household that genuinely has
      // nothing still succeeds here with real zeros — that answer is TRUE and the agent should give
      // it. Only a read that threw is refused.
      //
      // #2665 adds three reads to the four, on the SAME fail-loud footing: the household's settings
      // (what "today" means for this tenant), and `dayStateAll`, the canonical per-profile day
      // state. `dayStateAll` is the very function the policy snapshot's TimeLimit decision and
      // `GET /api/blocked` call — no query is added and no second usage computation exists.
      (for {
        household <- householdRepo.findById(hh)
        billing   <- billingRepo.findByHousehold(hh)
        devices   <- deviceRepo.listAllForHousehold(hh)
        profiles  <- profileRepo.listAllForHousehold(hh)
        settings  <- hsRepo.getForHousehold(hh)
        now       <- clock.instant
        today = PolicyService.householdLocalDate(now, settings)
        // Every read here is `hh`-parameterised — including this one, whose INNER reads were
        // scoped by #2257/#2264 — and `hh` is the verified token's household, the only one in
        // reach. The join below then keys day-states by THIS household's own profile ids, so a
        // foreign row is unreachable even if one were ever returned.
        //
        // Deliberately NOT wrapped in `HouseholdScoped` (#2630). That type exists to make a
        // FAN-OUT safe: it stops a loop over many recipients handing household A's value to
        // household B, because the payload cannot be read without naming the recipient. Here there
        // is exactly one household in scope and it is the recipient — wrapping and immediately
        // unwrapping with the same `claims.householdId` is a tautology, and it buys an unreachable
        // failure branch that would meter `household_read{outcome="error"}` (the #2462 "a repo
        // query threw" signal) for a state that cannot occur. It becomes the right tool the moment
        // this read serves a household it did not derive from the token.
        states <- timeStatus.dayStateAll(hh, now, today, settings)
      } yield HouseholdSummary(
        name = household.map(_.name).getOrElse(""),
        plan = billing.map(_.status),
        founding = billing.map(_.founding),
        deviceCount = devices.size,
        profileCount = profiles.size,
        profiles = profiles.map(p => profileSummary(p.name, p.paused, states.get(p.id))),
        devices = devices.map(d => DeviceSummary(d.name, d.profileName)),
        date = Some(today.toString),
      )).foldZIO(
        e =>
          ZIO.logError(
            s"support: agent household read FAILED household=${hh.value} " +
              s"thread=${claims.threadId}: ${e.getMessage}",
          ) *>
            // `error` on THIS op means exactly one thing — the account read did not complete —
            // because no other producer of `support_agent_action_total{op=household_read}` can
            // mint it. The route maps it to a 5xx, so the agent is TOLD it has no data rather than
            // handed a plausible-looking empty one. What it then SAYS to the customer currently
            // rests on the prompt's general "never claim account data you did not read from the
            // endpoint" rule — `agent.yaml` has no 5xx-specific instruction yet (#2524).
            doneE(AgentAction.HouseholdRead, AgentActionResult.Error),
        summary =>
          // Audit every consented read (#2241) — household + thread, never the data.
          ZIO.logInfo(
            s"support: agent household read household=${hh.value} thread=${claims.threadId}",
          ) *> AppMetrics.supportAgentAction(AgentAction.HouseholdRead, "ok").as(Right(summary)),
      )
    }

  /**
   * #2665 — project ONE profile's canonical [[ProfileDayState]] onto the wire shape. A pure
   * projection: every number is carried through, none is recomputed, so this cannot become the
   * second usage computation that #2016 / #2068 / #2274 each were.
   *
   * `state` is `None` only for a profile `dayStateAll` returned no entry for. That is the same "no
   * state yet" the `/api/time/status/summary` route already renders as a zeroed day, and it is an
   * EMPTY, not a failure — a failed batch never reaches here, it fails the whole read.
   */
  private def profileSummary(
      name: String,
      paused: Boolean,
      state: Option[ProfileDayState],
  ): ProfileSummary =
    state.fold(ProfileSummary(name = name, paused = paused))(st =>
      ProfileSummary(
        name = name,
        paused = paused,
        usedMinutes = st.usedMinutes,
        dailyLimitMinutes = st.dailyLimitMinutes,
        extensionMinutes = st.extensionMinutes,
        remainingMinutes = st.remainingMinutes,
        blockedNow = st.blocked,
        // `MacBlockReason.asString` — the enum's own case name (`TimeLimit`), which is exactly
        // what `agent.yaml` tells the agent to expect, so the prompt and this field cannot drift
        // into two vocabularies. It is NOT either wire tag: `MacBlockReason` separately carries
        // `jsonKind` (`timeLimit` — the snapshot's JSON kind tag) and `wireKind` (`time_limit`,
        // `unmanaged_mac` — `asWire`/`fromWire`, and the block page's `reasonClass` in
        // `BlockedRoutes`). Do not read this field as matching either of those.
        blockReason = st.blockReason.map(MacBlockReason.asString),
      ),
    )

  // ── #2437: escalation — the handoff that actually reaches a human ────────────

  /**
   * The agent hands this thread to a human (#2437). Two server-side effects, neither of which the
   * agent can aim or fake:
   *
   *   1. MARK the token-bound Plain thread with the configured escalation label, so the operator
   *      can FILTER the inbox for "waiting on a human" instead of reading every thread (before
   *      this, an escalated thread was indistinguishable from an AI-resolved one). 2. NOTIFY the
   *      operator out-of-band via the #578 [[Notifier]], carrying the household, the thread, and
   *      the agent's one-line reason.
   *
   * Escalation is STRUCTURAL: it is registered ONLY by a call to this endpoint with a valid
   * thread-bound token. Nothing ever text-matches the reply or the customer's message for phrases
   * like "a team member will follow up" — a customer who types that sentence has not escalated
   * anything, and an agent that writes it without calling here has not either (the prompt requires
   * both; the metric shows the gap if a session ever skips it).
   *
   * `note` is AGENT-AUTHORED text. It rides into the operator email HTML-escaped and is never used
   * for a decision — it cannot select a thread, a household, or a recipient (all three come from
   * the verified token / config).
   *
   * Fail-open on the notify half (the [[Notifier]] never fails); the thread-mark outcome is metered
   * and logged LOUD on failure, but does NOT fail the agent's call — the escalation happened, and
   * making the agent retry would only re-page the operator.
   */
  def agentEscalate(bearer: Option[String], note: Option[String]): UIO[AgentActionResult] =
    withClaims(AgentAction.Escalate, bearer) { claims =>
      escalateThreadLimiter.tryAcquire(s"escalate:${claims.threadId}").flatMap { ok =>
        if !ok then done(AgentAction.Escalate, AgentActionResult.RateLimited)
        else escalate(claims, note)
      }
    }

  private def escalate(
      claims: ConsentToken.Claims,
      note: Option[String],
  ): UIO[AgentActionResult] = {
    val hh = claims.householdId
    for {
      // #2508: the note is agent-authored and lands in the operator's mailbox — a credential in it
      // would leave the process too, so it is scrubbed on the same terms as a reply.
      safeNote  <- AgentCredential.redactOpt(
        AgentCredential.Channel.Support,
        AgentAction.Escalate,
        note,
      )
      // Audit trail (#2241 discipline) — household + thread, never the agent's prose.
      _         <- ZIO.logInfo(
        s"support: agent ESCALATED household=${hh.value} thread=${claims.threadId}",
      )
      // 1) The inbox-visible mark. The thread comes from the token and the label from config, so the
      // agent chooses neither. A failure is loud inside PlainClient and metered here.
      mark      <- plain.markThread(
        PlainThreadMark(
          threadId = claims.threadId,
          labelTypeIds = List(cfg.plain.escalationLabelTypeIdTrimmed),
        ),
      )
      _         <- AppMetrics.supportAgentAction("escalate_mark", PlainOutcome.label(mark))
      // 2) The out-of-band operator notice. The household NAME (not an address) identifies the
      // customer — the conversation itself lives in Plain, so nothing else needs copying out.
      household <- householdRepo.findById(hh).catchAll(_ => ZIO.none)
      _         <- notifier.escalation(
        EscalationNotice(
          channel = EscalationChannel.Support,
          kind = EscalationKind.Escalated,
          sender = household.map(_.name).getOrElse(s"household ${hh.value}"),
          subject = s"Plain thread ${claims.threadId}",
          body = SupportResponder.EscalationBodyHint,
          agentNote = safeNote.map(_.trim).filter(_.nonEmpty),
          reference = claims.threadId,
        ),
      )
      r         <- done(AgentAction.Escalate, AgentActionResult.Ok)
    } yield r
  }

  // ── #2419: the in-conversation data-access consent flow ─────────────────────

  /**
   * AGENT-side half: "ask the customer for permission" (#2419). The agent — which cannot read
   * household data without the consent scope — calls this instead of dead-ending, and the SERVER
   * posts a FIXED, server-authored consent prompt into the token-bound thread carrying a signed
   * [[ConsentGrant]] link. It is deliberately not the agent's own words: the agent supplies no text
   * in the consent MESSAGE.
   *
   * #2667 — that sentence is narrower than the guarantee it reads as, and the wider one is what
   * protects customers: IN THE TURN THAT POSTS THE PROMPT, THE CUSTOMER SEES ONLY SERVER-AUTHORED
   * TEXT. It did not hold at all until #2667, because nothing stopped the agent calling
   * [[agentReply]] in the same turn, immediately beside the real signed link and under the same
   * attribution — and a genuine, correctly-signed link with a hostile framing next to it is a
   * phishing primitive with every technical control intact. It is now enforced by
   * [[exclusiveThreadWrite]]: the two [[AgentAction.ThreadWrites]] are mutually exclusive within a
   * session, in either order. Adjacent agent text is NOT a separate concern to be left to the
   * prompt — the threat model is an agent that ignores its prompt.
   *
   * SCOPE, stated so it is not read as more than it is. The exclusion is per SESSION, and the link
   * stays redeemable for [[SupportResponder.ConsentLinkTtl]]. A LATER turn — the customer asking
   * "what is this link?" dispatches a fresh session with nothing claimed — can still post agent
   * text beneath a live prompt and frame the link the customer can already see. That costs the
   * attacker a customer round trip and is strictly narrower than what shipped before, but it is
   * open, and closing it needs durable per-thread state (so, a migration) plus a product call about
   * answering a customer who asks about the link. Tracked in
   * https://github.com/wifihaven/wifihaven/issues/2709. Do NOT relax this wording back to an
   * unqualified "at the consent moment" — the unqualified version is what let #2667 ship.
   *
   * #2453 — that guarantee needs a second half, because the prompt is posted through the SAME
   * machine-user write path as every AI reply and therefore comes back on the timeline as an
   * `ai_assistant` turn. Closing the wording channel is worthless if the agent can read the LIVE
   * link back out of its own thread history and re-post it wrapped in a pretext of its own
   * choosing. So [[CloudAgentDispatcher.renderHistory]] strips consent links out of the rendered
   * transcript (via [[SupportPrivacy.redactConsentLinks]], on every role), and the link itself is
   * single-use and cannot outlive a withdrawal (see [[wifihaven.api.db.SupportConsentRepo.grant]]).
   * Do not "simplify" either half away: together they are what makes the sentence above true.
   *
   * This REQUESTS consent; it does not grant it. There is no path from this endpoint to a
   * `support_thread_consent` row — only [[recordConsent]], authenticated by the CUSTOMER's session
   * JWT, writes one. A thread that already has a live grant is a no-op Ok (nothing to ask), so a
   * confused agent can't re-prompt a customer who already said yes; everything else is capped by
   * `consentThreadLimiter`.
   *
   * That cap is consumed BEFORE #2667's exclusion check, so an ask that is then suppressed as
   * `consent_prompt_after_reply` still spends thread budget. Same reasoning as the issue limiters:
   * what the cap bounds is the COST an agent can impose, and a suppressed ask still cost a token
   * verification and a tracker round trip. Moving the exclusion earlier is not the alternative —
   * the claim belongs at the write ([[exclusiveThreadWrite]]), because the already-granted branch
   * above posts nothing and must not spend the turn.
   */
  def agentRequestConsent(bearer: Option[String]): UIO[AgentActionResult] =
    // The OTHER callback that needs the current time: takes the verified `now` from the token check
    // rather than reading the clock again, so the grant check, the link's expiry, and the token
    // verification all sit on one instant.
    withClaimsAt(AgentAction.ConsentRequest, bearer) { (claims, now) =>
      consentGranted(claims.householdId, claims.threadId, now)
        .flatMap {
          case true  =>
            // Already consented — no prompt, no spam. The next dispatch already carries the scope.
            AppMetrics.supportConsent("request_already_granted") *>
              done(AgentAction.ConsentRequest, AgentActionResult.Ok)
          case false =>
            consentThreadLimiter.tryAcquire(s"consent:${claims.threadId}").flatMap { ok =>
              if !ok then
                AppMetrics.supportConsent("request_rate_limited") *>
                  done(AgentAction.ConsentRequest, AgentActionResult.RateLimited)
              else postConsentPrompt(claims, now)
            }
        }
    }

  private def postConsentPrompt(
      claims: ConsentToken.Claims,
      now: Instant,
  ): UIO[AgentActionResult] =
    // #2667: if this session has already spoken to the customer this turn, we do NOT post a prompt
    // underneath its words — and we mint no grant, so no live link exists for a turn we refused.
    exclusiveThreadWrite(AgentAction.ConsentRequest, claims)(mintAndPostConsentPrompt(claims, now))

  private def mintAndPostConsentPrompt(
      claims: ConsentToken.Claims,
      now: Instant,
  ): UIO[AgentActionResult] = {
    val grant = ConsentGrant.mint(
      household = claims.householdId,
      threadId = claims.threadId,
      now = now,
      ttl = SupportResponder.ConsentLinkTtl,
      secret = cfg.agentTokenSecretTrimmed,
      // #2453: a fresh nonce per link — redemption consumes it, so a captured link cannot be
      // replayed to re-grant access the customer has since withdrawn.
      nonce = ConsentGrant.newNonce(),
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
          AppMetrics
            .supportConsent("requested") *> done(AgentAction.ConsentRequest, AgentActionResult.Ok)
        case PlainOutcome.Disabled =>
          AppMetrics.supportConsent("request_disabled") *>
            done(AgentAction.ConsentRequest, AgentActionResult.Disabled)
        case PlainOutcome.Error    =>
          AppMetrics.supportConsent("request_error") *>
            done(AgentAction.ConsentRequest, AgentActionResult.Error)
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
      user  <- userRepo.findByUsername(claims.hh, claims.sub).catchAll(_ => ZIO.none)
      // #2460: `grant` reports what it DID, decided by the same transaction that writes (a separate
      // read-then-write would let a second Allow on an already-live grant resume again and
      // double-answer). #2453 folds the link's single-use check into the same transaction, so a
      // replayed or pre-revocation link is refused there rather than by a check we could race.
      // A failed write transitions nothing.
      write <- consentRepo
        .grant(
          claims.hh,
          g.threadId,
          g.nonce,
          g.issuedAt,
          // The LINK's own expiry, not the grant's: V85 defines `link_expires_at` as when the spent
          // link would have lapsed anyway. The grant window below starts at REDEMPTION instead, so
          // the two coincide only when a link is redeemed the instant it is minted.
          g.expiresAt,
          user.map(_.id),
          now,
          now.plus(SupportResponder.ConsentTtl),
        )
        .foldZIO(
          e =>
            ZIO.logWarning(s"support: consent grant failed: ${e.getMessage}") *>
              AppMetrics
                .supportConsent("error")
                .as(GrantWrite(ConsentResult.Error, transitioned = false)),
          {
            // #2453 — the two refusals. Both are security-relevant: a link is being presented that
            // cannot legitimately grant, which on the `link_spent` side is the replay-after-
            // withdrawal shape the single-use nonce exists to stop. Loud + metered, writes nothing.
            case out @ (GrantOutcome.LinkSpent | GrantOutcome.LinkStale) =>
              val reason =
                if out == GrantOutcome.LinkSpent then "link_spent" else "link_stale"
              ZIO.logWarning(
                s"support: consent link REFUSED ($reason) household=${claims.hh.value} " +
                  s"thread=${g.threadId} by=${claims.sub}",
              ) *> AppMetrics
                .supportConsent(reason)
                .as(GrantWrite(ConsentResult.LinkSpent, transitioned = false))
            case out                                                     =>
              ZIO.logInfo(
                s"support: data-access consent GRANTED household=${claims.hh.value} " +
                  s"thread=${g.threadId} by=${claims.sub} ttlHours=${SupportResponder.ConsentTtl.toHours}",
              ) *> AppMetrics
                .supportConsent("granted")
                .as(GrantWrite(ConsentResult.Granted, out == GrantOutcome.Transitioned))
          },
        )
      // The grant row is COMMITTED before the resume, so the token the resume mints is guaranteed to
      // carry the scope the customer just granted. Re-confirming a still-live grant resumes nothing
      // (the idempotency guard) — the answer to that question is already on its way.
      _     <- ZIO.when(write.result == ConsentResult.Granted) {
        if write.transitioned then runDetached(resumeAfterGrant(claims.hh, g, now))
        else meterResume(ResumeOutcome.Skipped)
      }
    } yield write.result

  /**
   * #2460 — CLOSE THE LOOP. Consent used to be consumed only by the NEXT inbound webhook, so a
   * customer who clicked Allow got nothing: the consent link had navigated them out of the page
   * hosting the chat widget, and the assistant never learned the grant happened. The conversation
   * dead-ended exactly the way #2419 was created to stop it dead-ending.
   *
   * So the SERVER finishes the turn: read the thread, take the customer's last message — the
   * question that made the agent ask for permission in the first place — and re-dispatch it with a
   * `dataAccess=true` token. The customer does nothing; they come back (whenever they come back) to
   * an answer.
   *
   * Bounded and non-bypassing by construction:
   *   - IDEMPOTENT per grant — the caller only runs this on a transition from no-live-grant to
   *     granted, so a repeat Allow re-dispatches nothing and cannot double-answer;
   *   - the ORDINARY dispatch caps are drawn through the shared [[withDispatchCaps]], but ONLY
   *     around the branch that actually starts a session ([[redispatchAfterGrant]]) — a resume is a
   *     real agent session and costs real tokens, while the fail-open nudge is a fixed string. The
   *     caps are a DRAW, not a check, so gating the nudge on them would spend the shared daily AI
   *     budget on threads that dispatch nothing (and, on a capped thread, silently swallow the
   *     nudge). This is why the read comes before the caps here and after them on the webhook path:
   *     there the read is on the request fiber and a capped thread must not pay for it, here the
   *     whole resume is already off the request fiber, so a wasted Plain read costs nobody's
   *     latency. The accepted price is that one Plain call and one fiber ride on every grant
   *     TRANSITION, capped or not (a revoke→grant cycle is a transition each time) — cheap next to
   *     an agent session, and the sessions themselves are what the caps protect;
   *   - it does NOT trip the #2403/#2404 loop guard: that guard lives on the inbound webhook path
   *     and drops our own outbound writes, which is untouched here. The agent's eventual reply
   *     still arrives as a `thread.chat_sent` the guard drops, so the loop terminates;
   *   - it runs OFF the request fiber (`runDetached`, `forkDaemon` in production). Both legs are
   *     bounded only by their own transport timeouts — the timeline read at
   *     [[PlainClient.HistoryTimeout]], the dispatch at the transport's `RequestTimeout`
   *     (`ManagedAgents` / `ClaudeCodeRoutines`) — which together exceed the SPA's own request
   *     timeout (`web/src/api/client.ts`), so running it inline would let a SUCCESSFUL grant abort
   *     client-side and render as "that link is no longer valid". Forking also keeps the metric
   *     honest: a ZIO timeout would INTERRUPT the dispatch the customer is waiting on and drop its
   *     `resume_*` sample (see the `disconnect` note on [[PlainClient.threadHistory]]), whereas a
   *     daemon fiber runs every branch below to completion. The grant is committed BEFORE any of
   *     this, so nothing here can cost the customer's consent.
   */
  private def resumeAfterGrant(
      hh: HouseholdId,
      g: ConsentGrant.Claims,
      now: Instant,
  ): UIO[Unit] =
    plain.threadHistory(g.threadId, PlainClient.HistoryFetchLimit).flatMap { history =>
      // The customer's LAST turn is what we re-ask. Usually that is the unanswered question that
      // made the agent request permission; if the customer also typed something after the prompt
      // ("ok, approved"), that is what re-dispatches — the earlier turns ride along as history, so
      // the agent still has the question. Everything AFTER the last customer turn is dropped,
      // which also keeps the server's consent prompt (and its link) out of the agent's context.
      history.lastIndexWhere(_.role == ThreadMessageRole.Customer) match {
        case -1  => nudgeAfterGrant(g.threadId)
        case idx =>
          val latest = history(idx).text
          // The prior-turns rule has ONE implementation (#2430): `priorTurns` drops a trailing
          // customer echo of the message being dispatched, which is exactly `history.take(idx)`.
          redispatchAfterGrant(
            hh,
            g.threadId,
            latest,
            priorTurns(history.take(idx + 1), latest),
            now,
          )
      }
    }

  /**
   * The re-dispatch itself: the customer's own last message, answered under the scope they just
   * granted, assembled by the shared [[dispatchAgentSession]] and capped by the shared
   * [[withDispatchCaps]] — a thread that has exhausted its per-thread cap still GRANTS (the
   * customer's consent is never lost to a cost cap) but gets no free follow-up session. The caps
   * wrap THIS branch only: they are a draw, so the fixed-string nudge must not spend one. Every
   * branch meters, so `granted` and `resume_*` always pair up.
   */
  private def redispatchAfterGrant(
      hh: HouseholdId,
      threadId: String,
      customerMessage: String,
      history: List[PlainThreadMessage],
      now: Instant,
  ): UIO[Unit] =
    withDispatchCaps(threadId)(meterResume(ResumeOutcome.RateLimited)) {
      householdRepo.findById(hh).catchAll(_ => ZIO.none).flatMap {
        case None            =>
          // The household row we resolved the session from is unreadable. Dispatching anyway would
          // ship an empty household name into the kickoff; fail the resume visibly instead.
          ZIO.logWarning(
            s"support: consent resume skipped — household=${hh.value} unreadable thread=$threadId",
          ) *> meterResume(ResumeOutcome.Error)
        case Some(household) =>
          ZIO.logInfo(
            s"support: consent granted — resuming thread=$threadId household=${hh.value} " +
              "with a dataAccess=true agent session",
          ) *>
            dispatchAgentSession(
              hh = hh,
              householdName = household.name,
              threadId = threadId,
              customerMessage = customerMessage,
              history = history,
              dataAccess = true,
              now = now,
            ).flatMap(outcome =>
              meterResume(outcome match {
                case DispatchOutcome.Dispatched  => ResumeOutcome.Resumed
                case DispatchOutcome.Disabled    => ResumeOutcome.Disabled
                case DispatchOutcome.Error       => ResumeOutcome.Error
                // #2416: a permanent 4xx at the agent boundary keeps its own bucket here too — a
                // dead responder must not hide inside the transient one (the dispatcher already
                // logged it at ERROR with the fix named).
                case DispatchOutcome.ConfigError => ResumeOutcome.ConfigError
              }),
            )
      }
    }

  /**
   * The fail-open fallback: we could not read the thread (Plain hiccup, or the `timeline:read`
   * permission gap of #2452), so we do not know what to re-ask. Rather than leaving the customer on
   * a terminal page with nothing happening, post a SERVER-AUTHORED nudge telling them the
   * permission landed and one more message will get their answer.
   *
   * Server-authored is load-bearing (#2419 anti-phishing): the agent supplies no text in this
   * message, so a prompt-injected agent cannot craft one under our attribution. It carries NO
   * consent URL (#2453).
   *
   * This nudge is NOT covered by #2667's turn exclusion, and does not need to be: it is written by
   * the SERVER on the customer's own grant, not by an agent callback, and it is posted only when
   * the grant has already been given — there is no decision left for adjacent text to bias.
   */
  private def nudgeAfterGrant(threadId: String): UIO[Unit] =
    plain
      .writeThread(PlainThreadWrite(threadId, SupportResponder.consentGrantedNudge))
      .flatMap {
        case PlainOutcome.Ok       => meterResume(ResumeOutcome.NoMessage)
        case PlainOutcome.Disabled => meterResume(ResumeOutcome.Disabled)
        case PlainOutcome.Error    => meterResume(ResumeOutcome.Error)
      }

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
   * #2460 — meter one resume outcome. The ONE place a [[ResumeOutcome]] becomes a metric label, so
   * the value the dashboard panel matches on cannot be spelled differently at any emit site.
   */
  private def meterResume(o: ResumeOutcome): UIO[Unit] =
    AppMetrics.supportConsent(ResumeOutcome.label(o))

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
    withClaimsAt(action, bearer)((claims, _) => f(claims))

  /** [[withClaims]] for a callback that also needs the instant the token was verified against. */
  private def withClaimsAt(action: String, bearer: Option[String])(
      f: (ConsentToken.Claims, Instant) => UIO[AgentActionResult],
  ): UIO[AgentActionResult] =
    withClaimsE(action, bearer)((claims, now) => f(claims, now).map(Left(_))).map(_.merge)

  /**
   * `f` receives the SAME `now` the token was verified against, so a caller that needs the current
   * time (the #2476 consent re-read) evaluates it on one consistent instant rather than reading the
   * clock a second time.
   */
  private def withClaimsE[A](action: String, bearer: Option[String])(
      f: (ConsentToken.Claims, Instant) => UIO[Either[AgentActionResult, A]],
  ): UIO[Either[AgentActionResult, A]] =
    if !cfg.agentEndpointsEnabled then
      AppMetrics.supportAgentAction(action, "disabled").as(Left(AgentActionResult.Disabled))
    else
      clock.instant.flatMap { now =>
        bearer.map(_.trim).filter(_.nonEmpty) match {
          case None        =>
            // #2473: a rejected callback is a customer answer that never arrived — it is LOUD on the
            // shared `agent_token_rejected_total` series, not just another `denied` sample.
            denyLoudly(action, AgentTokenRejection.Reason.Missing)
          case Some(token) =>
            ConsentToken.verify(token, now, cfg.agentTokenSecretTrimmed) match {
              case Left(err)     => denyLoudly(action, AgentTokenRejection.reasonFor(err))
              case Right(claims) =>
                // #2668: does this session still own the thread's turn? Checked BEFORE the #2472
                // close, because a superseded session must not close the turn that the session
                // which superseded it now owes an answer for.
                supersededGuard(action, claims, now).flatMap {
                  case Some(refusal) => ZIO.succeed(refusal)
                  case None          =>
                    // #2472: the ONE place every token-authenticated agent callback passes through,
                    // so the dispatch→completion pairing has exactly one closing site. Terminal
                    // actions only (AgentAction.Terminal) — a household read or an issue filing
                    // proves the session is alive but leaves the customer with nothing, which is the
                    // very failure being tracked. A REJECTED callback never reaches here, so a
                    // forged token cannot close someone else's dispatch.
                    ZIO
                      .when(AgentAction.Terminal.contains(action))(
                        dispatchTracker.calledBack(claims.threadId, action, now),
                      )
                      .unit *> f(claims, now)
                }
            }
        }
      }

  /**
   * #2668 — `Some(refusal)` iff this callback would put a SECOND answer to one customer turn in
   * front of the customer: a session whose thread has since been re-dispatched, calling one of the
   * [[AgentAction.ThreadWrites]].
   *
   * WHY THE LATER SESSION WINS. Its kickoff is a superset — the same Plain timeline the earlier
   * session was given, plus whatever arrived after it — so nothing the superseded session was going
   * to say is lost, while the reverse is not true. That also makes the rule the same one whether
   * the two dispatches were a #2460 consent resume plus an inbound message (the #2668 prod shape)
   * or just two customer messages in quick succession, which is why the guard lives here at the
   * shared callback boundary rather than in the consent flow.
   *
   * SCOPED TO THREAD WRITES, not all of [[AgentAction.Terminal]]. `escalate` is deliberately NOT
   * guarded: it is the human-handoff safety valve (#2437), and a duplicate label + operator email
   * is noise, whereas a dropped one is a customer who asked for a person and did not get one. The
   * two non-terminal callbacks (`issue`, `household_read`) are not customer-visible at all.
   *
   * The refusal is reported to the agent as SUCCESS (`AgentActionResult.Superseded` → 200) on
   * purpose: the turn IS handled, just not by this session, and a 4xx would invite the run to retry
   * a write it can never legitimately land.
   */
  private def supersededGuard[A](
      action: String,
      claims: ConsentToken.Claims,
      now: Instant,
  ): UIO[Option[Either[AgentActionResult, A]]] =
    if !AgentAction.ThreadWrites.contains(action) then ZIO.none
    else
      dispatchTracker.turnOwner(claims.threadId, claims.sessionId, now).flatMap {
        case DispatchTracker.Turn.Superseded                             =>
          AppMetrics
            .supportAgentAction(action, AgentActionResult.label(AgentActionResult.Superseded))
            .as(Some(Left(AgentActionResult.Superseded)))
        case DispatchTracker.Turn.Current | DispatchTracker.Turn.Unknown => ZIO.none
      }

  /**
   * #2667 — run `post` only if this session has not already put the OTHER KIND of customer-visible
   * message in front of the customer this turn.
   *
   * KIND, not count: a session replying twice is unchanged (#2668 owns "one turn, one reply" ACROSS
   * sessions). What is refused is the two kinds COMBINING.
   *
   * THE GUARANTEE IT MAKES STRUCTURAL. #2419's consent prompt is server-authored so that a
   * prompt-injected agent cannot craft a phishing message under our attribution. Read as it was
   * meant — *at the consent moment the customer sees only server-authored text* — that was FALSE
   * until this: nothing stopped the agent posting its own words in the same turn, immediately
   * beside a perfectly genuine, correctly-signed link, under the same `🤖 WifiHaven support
   * assistant` banner. The link being real is what makes the framing around it work.
   *
   * WHY IT IS HERE AND NOT IN `deploy/support-agent/agent.yaml`. The threat is an agent that
   * ignores its prompt, so a prompt rule cannot be the control — the same reasoning that put
   * #2454's data-session issue refusal in code. The prompt IS updated to match (an agent that stops
   * after asking never sees this refusal), but it is downstream of the guarantee, not the source of
   * it.
   *
   * The two call sites are the two places anything reaches the customer's thread — [[agentReply]]
   * and [[postConsentPrompt]] — and the claim is taken IMMEDIATELY around the write rather than at
   * the shared callback boundary, on purpose: `request-consent` on an already-consented thread is a
   * no-op that posts nothing, and a boundary claim would let that silently eat the answer the agent
   * then owes the customer.
   *
   * Every claim is SETTLED with what its write actually did, and the recovery is deliberately
   * narrow — see [[settleThreadWrite]] for why only a dark Plain client proves non-delivery, and
   * [[DispatchTracker.settleThreadWrite]] for the concurrent case recovery cannot reach. Both fail
   * towards refusing, which is the right direction for a control keeping attacker-influenced text
   * away from a live consent link.
   *
   * The refusal is reported to the agent as SUCCESS (→ 200), like #2668's: the turn IS handled, and
   * a 4xx would invite the run to retry a write it can never legitimately land.
   */
  private def exclusiveThreadWrite(action: String, claims: ConsentToken.Claims)(
      post: UIO[AgentActionResult],
  ): UIO[AgentActionResult] =
    // The mask makes taking a claim and installing its `onExit` ONE uninterruptible step. Without
    // it, an interrupt landing between the two leaves the kind in flight for good, silently
    // blocking the other one for the rest of the session. `restore` puts the write itself — the
    // only slow part — back under normal interruption.
    ZIO.uninterruptibleMask { restore =>
      dispatchTracker.claimThreadWrite(claims.threadId, claims.sessionId, action).flatMap {
        case DispatchTracker.ThreadWriteClaim.Excluded(by) =>
          // PII firewall (#2438): the thread id and the two bounded action names, never the
          // suppressed text and never anything about the customer.
          ZIO.logWarning(
            s"support: consent turn is server-authored — dropping op=$action on " +
              s"thread=${claims.threadId} because this session already posted op=$by (or is " +
              "posting it). The customer's data-access decision must rest on the server's own " +
              "message alone (#2667)",
          ) *>
            AppMetrics.supportConsent(SupportResponder.exclusionOutcome(action)) *>
            done(action, AgentActionResult.ConsentExclusive)
        case DispatchTracker.ThreadWriteClaim.Claimed      =>
          // `onExit`, not a flatMap: an interrupted or defective write must still settle.
          restore(post).onExit(e => settleThreadWrite(action, claims, e))
        case DispatchTracker.ThreadWriteClaim.Untracked    => restore(post)
      }
    }

  /**
   * #2667 — did the write reach the customer? Only [[AgentActionResult.Disabled]] answers NO.
   *
   * ONLY `Disabled` PROVES NON-DELIVERY. It is our own flag: the Plain write half is off, so
   * nothing was attempted and giving the turn back is unambiguously safe. Everything else is
   * settled LANDED, including [[AgentActionResult.Error]] — `PlainClient` collapses a non-2xx, a
   * GraphQL error, a transport failure and a `RequestTimeout` into that one value, and a write that
   * timed out may very well have been applied. Reading a timeout as "nothing was posted" would free
   * the claim and let a consent prompt land underneath a reply the customer can already see, which
   * is the whole thing this guards. An interrupt or a defect is unknowable and settles the same
   * way.
   *
   * So the recovery is narrow on purpose: it covers the dark-install case and nothing else. The
   * cost of erring this way is a silent turn — loud on
   * `support_agent_action_total{op,outcome="error"}`, and on
   * `support_consent_total{outcome="request_error"}` when it is the prompt that failed. NOT on the
   * #2472 dispatch pairing: [[withClaimsE]] closes that at token-verify time, before the write, so
   * do not send an operator there to find one. A silent turn is the direction a control keeping
   * attacker-influenced text away from a live consent link should fail in.
   */
  private def settleThreadWrite(
      action: String,
      claims: ConsentToken.Claims,
      exit: Exit[Nothing, AgentActionResult],
  ): UIO[Unit] =
    dispatchTracker.settleThreadWrite(
      claims.threadId,
      claims.sessionId,
      action,
      landed = exit.foldExit(_ => true, _ != AgentActionResult.Disabled),
    )

  /**
   * #2473 — the ONE support-side token rejection path: log + meter the loud shared series, then
   * return the SAME uniform 401-shaped `Denied` (and the same `…_agent_action_total{denied}`
   * sample) every rejection has always returned. The response is deliberately identical for every
   * reason, so the caller learns nothing about WHY it failed — only our logs do.
   */
  private def denyLoudly[A](action: String, reason: String): UIO[Either[AgentActionResult, A]] =
    AgentTokenRejection.rejected(AgentTokenRejection.Channel.Support, action, reason) *>
      AppMetrics.supportAgentAction(action, "denied").as(Left(AgentActionResult.Denied))

  private def done(action: String, r: AgentActionResult): UIO[AgentActionResult] =
    AppMetrics.supportAgentAction(action, AgentActionResult.label(r)).as(r)

  /**
   * #2461 — the issue-filing success result. Both values are a SUCCESS to the agent (the issue
   * exists either way), but "filed but GitHub's create response was unreadable" is a real
   * degradation the operator must be able to see: the agent has no link to offer. Returns the
   * bounded [[AgentActionResult]] rather than a bare string so the label stays type-enforced;
   * `outcome` is an already-allowed label key for `support_agent_action_total`, so this adds a
   * VALUE, not a new key. Any "how many issues did we file" query must match
   * [[AgentActionResult.SuccessLabels]], not just `ok`.
   */
  private def issueFiledOutcome(ref: Option[IssueRef]): AgentActionResult =
    if ref.isDefined then AgentActionResult.Ok else AgentActionResult.OkNoLink

  /** [[done]] for the `Either`-shaped endpoints — same single metric derivation, left-biased. */
  private def doneE[A](action: String, r: AgentActionResult): UIO[Either[AgentActionResult, A]] =
    done(action, r).map(Left(_))
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
   * #2456 — drop any LEADING copies of [[AiReplyAttribution]] from agent-authored markdown. The
   * server is the single owner of that line; a copy at the head of the agent's own text is always
   * redundant, and left in place the customer sees the header twice.
   *
   * Why the agent emits it at all: since #2441 the kickoff carries prior AI turns back as history,
   * and those stored turns begin with the line, so the agent intermittently reproduces the shape it
   * sees (measured ~1 in 2 history-carrying replies; it does NOT compound). Because that is
   * non-deterministic model formatting rather than an instruction being followed —
   * `deploy/support-agent/agent.yaml` never asks for the line — the fix has to be structural here
   * rather than a prompt rule, which would re-open on any prompt edit or model change.
   *
   * LEADING only: the line is a header, so a mid-body occurrence is the agent QUOTING an earlier
   * turn back to the customer, which is legitimate content and stays.
   *
   * EXACT match only, deliberately. The constant carries an emoji, markdown emphasis, an em dash
   * and typographic quotes, and a near-miss reproduction (no 🤖, `-` for `—`, straight quotes) is
   * NOT stripped — "exactly once" is a guarantee about VERBATIM copies, which is what the observed
   * duplication is. A fuzzy match would risk eating customer- or agent-authored text that merely
   * resembles the line, the worse failure.
   */
  private[api] def stripLeadingAttribution(markdown: String): String = {
    @annotation.tailrec
    def go(s: String): String = {
      val t = s.stripLeading()
      if t.startsWith(AiReplyAttribution) then go(t.drop(AiReplyAttribution.length)) else t
    }
    go(markdown)
  }

  /**
   * #2437 — what the support escalation notice puts in the "message" slot. Unlike press (where the
   * inbound email body IS the whole context and lives in `press_messages`), a support conversation
   * lives in Plain and can be many messages long: copying a snapshot of it into an email would be a
   * second, stale copy of customer data outside the helpdesk. So the notice points AT the thread
   * instead — the reference field carries the thread id.
   */
  val EscalationBodyHint: String =
    "Open this thread in Plain to read the conversation and reply. It is labelled for escalation, " +
      "so it also shows up under the needs-a-human filter in the inbox."

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
   * for data access. Never agent-authored: the agent supplies no text in it, so it cannot craft a
   * phishing message under our attribution. Names exactly what is shared, that it is read-only,
   * that it is scoped to this conversation, and how to say no (do nothing).
   *
   * #2667 — and it is now the WHOLE message for that turn, which is what makes the sentence above
   * mean what it appears to mean. Its terms (the 24 hours, the read-only scope) are the only ones
   * the customer is shown; nothing agent-authored may sit beside them (the #2667 turn exclusion).
   * Keep the copy self-sufficient for that reason — the agent is not there to add the missing half.
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
   * #2460 — the fail-open nudge posted when the grant lands but the thread is unreadable, so we
   * cannot tell what to re-ask (a Plain hiccup or the #2452 `timeline:read` gap). FIXED and
   * SERVER-AUTHORED — the agent supplies no text in it (#2419 anti-phishing) — and deliberately
   * carries NO consent URL (#2453).
   */
  val consentGrantedNudge: String =
    s"""$AiReplyAttribution
       |
       |Thanks — I can see your account summary for this conversation now. Ask me your question
       |again and I'll take a look.""".stripMargin

  /**
   * #2667 — the `support_consent_total{outcome}` values a SUPPRESSED consent-adjacent write emits,
   * one per direction so the operator can tell which way round it happened: the agent talking after
   * the prompt (the observed prod shape, a prompt the routine has drifted from), or a prompt asked
   * for underneath the agent's own words (which never posts a link at all). Two bounded constants,
   * never a per-thread or per-household label.
   *
   * Named here, in ONE place, because a Grafana panel selects them by string — the #2461/#2482
   * lesson: `SupportMetricsContractSpec` pins the panel's matcher against [[ExclusionOutcomes]], so
   * a renamed label is a failing test rather than a panel that quietly reads zero forever.
   */
  private[api] def exclusionOutcome(action: String): String = action match {
    case AgentAction.ConsentRequest => "consent_prompt_after_reply"
    case AgentAction.Reply          => "reply_after_consent_prompt"
    // Unreachable: only AgentAction.ThreadWrites members are ever claimed, and the contract spec
    // pins ExclusionOutcomes (derived from that set) against the two values above — so a third
    // thread-writing action fails there rather than silently metering as one of these.
    case _                          => "suppressed_other"
  }

  /** Both values of [[exclusionOutcome]] — what the #2667 panel must match on. */
  val ExclusionOutcomes: Set[String] =
    AgentAction.ThreadWrites.map(exclusionOutcome)

  /**
   * #2460 — the `support_consent_total{outcome}` values the post-grant RESUME emits. Named, in ONE
   * place, because a dashboard panel selects a SUBSET of them by string, and #2461/#2482 is the
   * worked example of that drifting silently: a split success label left a volume panel
   * under-counting and nothing failed, because the panel is JSON and the label is a string.
   * [[ResumeOutcome.DeadEnd]] is the subset the "Consent grants that dead-ended" panel counts, and
   * `SupportMetricsContractSpec` pins the panel's regex against it — so adding an outcome here
   * without widening the panel is a failing test rather than a months-later under-count.
   */
  enum ResumeOutcome {

    /** The customer's question was re-dispatched under the scope they just granted. */
    case Resumed

    /** No CUSTOMER turn was readable on the thread, so the server-authored nudge posted instead. */
    case NoMessage

    /** A re-confirmed LIVE grant — the idempotency guard; the answer is already on its way. */
    case Skipped

    case RateLimited
    case Disabled

    /** A transient dispatch / write failure. */
    case Error

    /** #2416 — a PERMANENT 4xx at the agent boundary, kept out of the transient bucket. */
    case ConfigError
  }

  object ResumeOutcome {

    /** The bounded `support_consent_total{outcome}` value. EXHAUSTIVE — no `case _`. */
    def label(o: ResumeOutcome): String = o match {
      case Resumed     => "resumed"
      case NoMessage   => "resume_no_message"
      case Skipped     => "resume_skipped"
      case RateLimited => "resume_rate_limited"
      case Disabled    => "resume_disabled"
      case Error       => "resume_error"
      case ConfigError => "resume_config_error"
    }

    /**
     * Did the customer grant permission and get NOTHING back? EXHAUSTIVE on purpose — a new case
     * must be classified deliberately here rather than defaulting into (or out of) the dashboard's
     * dead-end count. [[Skipped]] and [[NoMessage]] are false: the first means the answer is
     * already on its way, the second means we told them what to do next.
     */
    def deadEnd(o: ResumeOutcome): Boolean = o match {
      case RateLimited | Disabled | Error | ConfigError => true
      case Resumed | NoMessage | Skipped                => false
    }

    /**
     * DERIVED, not hand-listed (the [[AgentActionResult.SuccessLabels]] pattern): a new dead-end
     * case widens this automatically, and `SupportMetricsContractSpec` then fails on the dashboard
     * panel that did not widen with it.
     */
    val DeadEnd: Set[String] = values.filter(deadEnd).map(label).toSet
  }

  /**
   * #2460 — what the consent WRITE did: the customer-facing result, plus whether it moved the
   * `(household, thread)` pair from no-live-grant to live. Only a transition resumes the
   * conversation; a re-confirmation of a still-live grant is the idempotency no-op.
   */
  private final case class GrantWrite(result: ConsentResult, transitioned: Boolean)

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

    /**
     * #2453 — the link is well-signed and unexpired, but cannot grant: its nonce is already spent
     * and the grant is no longer live (the replay-after-withdrawal shape), or it was minted before
     * the customer's withdrawal. ONE customer-facing case for both — the reply says only "ask the
     * assistant for a new link", so a caller probing with captured links learns nothing about which
     * rule bit. The `support_consent_total{outcome}` label keeps them apart for the operator
     * (`link_spent` / `link_stale`). Writes no grant.
     */
    case LinkSpent
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
    // #2307: a NEW inbound email from an UNREGISTERED address → the fixed static reject (no AI),
    // and Plain ACCEPTED the send. Success-shaped: the customer got the reject.
    case EmailUnregisteredRejected

    /**
     * #2471 — the reject was decided correctly and Plain REFUSED to send it, so the customer got
     * NOTHING. Terminal and distinct from [[EmailUnregisteredRejected]] on purpose: the two used to
     * share that success label, which let a workspace with email sending disabled look like a
     * healthy reject path on the Grafana support panel while every reject was dropped (the live
     * staging failure, 2026-07-26). Expect a flat zero; the refusal also logs at ERROR.
     *
     * A REFUSAL only — an explicitly-disabled write half (`plain.writeEnabled=false`) is
     * [[Disabled]], not this, so a deliberate off-state never reads as a provisioning gap.
     */
    case EmailRejectSendFailed
    case SkippedUnauthenticated

    /**
     * #2462 — origin resolution could not be COMPLETED: a `households` / `users` read threw, so we
     * never established whether this sender has a registered origin. Terminal for the delivery
     * (nothing dispatched, nothing said to the customer) and deliberately distinct from
     * [[SkippedUnauthenticated]] / [[EmailUnregisteredRejected]], which both ASSERT the sender has
     * no origin — an assertion a failed lookup does not support. Reading a DB blip as "not a
     * customer" is what sent a registered admin the #2307 static reject, metered as routine. Expect
     * a flat zero; the failure also logs at ERROR with the failing stage named.
     */
    case OriginLookupFailed
    // #2403 loop guard: a non-inbound / non-customer event (our own `thread.chat_sent` reply, a
    // non-customer actor, or a bodyless identified metadata event) — deliberately never dispatched.
    case SkippedNotInbound
    case RateLimited
    case InvalidSignature
    case Malformed

    /**
     * An EXPLICIT named flag is off, so nothing was attempted. TWO deliberate CAUSES:
     * `responderEnabled=false` (the whole responder is dark) and `plain.writeEnabled=false` (the
     * responder runs but the Plain write half is dark, so a static reject is decided and not sent).
     * Not a list of code sites — the first cause reaches this from `handleWebhook`'s short-circuit
     * AND, defensively, from the dispatcher's own `Disabled` outcome, which
     * `CloudAgentDispatcher.transportFor` gates on that same flag. Never a failure — a REFUSED send
     * is [[EmailRejectSendFailed]].
     */
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
      // #2471: a SEPARATE series, deliberately not folded into the reject or the generic error
      // bucket — an undelivered reject is its own failure and should read as zero on the panel.
      case EmailRejectSendFailed     => "email_reject_send_failed"
      case SkippedUnauthenticated    => "skipped_unauthenticated"
      // #2462: a SEPARATE series, never folded into the skip or the reject — an origin we could not
      // READ is not an origin we established does not exist.
      case OriginLookupFailed        => "origin_lookup_failed"
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
     * [[CloudAgentObservability.Reason]], shared with the press path.
     *
     * EXHAUSTIVE on purpose — no `case _`. A future dispatch-failure outcome added to the enum must
     * fail to COMPILE here rather than silently label itself `none`, which would be invisible
     * (`outcome=error` is unchanged, so only the `reason` slice would be wrong).
     */
    def reason(o: WebhookOutcome): String = o match {
      case ConfigError => CloudAgentObservability.Reason.Config
      case Error       => CloudAgentObservability.Reason.Transient
      // #2471: `EmailRejectSendFailed` is `none` on PURPOSE, not by omission. `reason` attributes a
      // CLOUD-AGENT dispatch failure, and `PlainClient` collapses every send failure — non-2xx,
      // GraphQL error, transport — into a single `PlainOutcome.Error` with no cause attached. There
      // is nothing to attribute here, and splitting it config/transient would be a guess: the live
      // failure ("Emails are not enabled for this workspace") is permanent config, but a Plain 5xx
      // is transient, and the two are indistinguishable at this seam. The outcome label carries the
      // signal. Attribution needs a cause on `PlainOutcome` first — a separate change.
      // #2462: `OriginLookupFailed` is `none` for the same reason as `EmailRejectSendFailed` —
      // `reason` attributes a CLOUD-AGENT dispatch failure, and this one never reached a dispatch.
      case Dispatched | EmailRegisteredDispatched | EmailUnregisteredRejected |
          EmailRejectSendFailed | SkippedUnauthenticated | OriginLookupFailed | SkippedNotInbound |
          RateLimited | InvalidSignature | Malformed | Disabled =>
        CloudAgentObservability.Reason.None
    }
  }

  /**
   * #2462 — the three-state result of resolving a webhook delivery's ORIGIN to a household. Exists
   * to make the failure case unrepresentable-as-a-non-match: the previous `Option` shape had no
   * room for "the lookup threw", so both resolvers collapsed a DB error into `None` and every
   * downstream branch read that as a proven-unidentified sender.
   *
   *   - `Resolved` — this delivery belongs to that household;
   *   - `NoMatch` — we looked, and there is no such origin (the only state that may reject);
   *   - `Failed` — we could not look. Terminal, loud, and says nothing to the customer.
   */
  enum OriginLookup {
    case Resolved(householdId: HouseholdId, household: Household)
    case NoMatch
    case Failed
  }

  /**
   * Bounded result enum for the agent endpoints — the outcome vocabulary for the `withClaims`-gated
   * ops (`reply`, `issue`, `household_read`, `consent_request`, `escalate`). `Denied` is any token
   * failure (missing / tampered / expired — uniform to the caller); `NoConsent` is a VALID token
   * without the data scope (the household read's 403). Both meter as "denied" so the label space
   * stays bounded.
   *
   * NOT the only minter of `support_agent_action_total{outcome}`: the #2437 `escalate_mark` op
   * labels the SERVER's Plain `addLabels` write through `PlainClient.PlainOutcome.label` (`ok |
   * disabled | error`), and a few call sites pass a literal. The vocabularies coincide today;
   * [[SuccessLabels]] below is therefore scoped to what THIS enum mints, and the contract spec that
   * consumes it checks the issue-filing panel only. Widening a success query to other ops means
   * reconciling with `PlainOutcome` first.
   *
   * `OkNoLink` (#2461) is a SUCCESS — the issue was created — that we could not read a link back
   * for. It is a distinct label because the operator needs to see it, but every "did the filing
   * succeed" query must count it alongside `Ok`: see [[SuccessLabels]], which the Grafana volume
   * panel's `outcome=~` matcher mirrors.
   */
  enum AgentActionResult   {
    case Ok
    case OkNoLink

    /**
     * #2458 — the agent asked to file, an already-open `support-agent` issue covered the topic, and
     * we handed that issue back instead of creating a duplicate. A SUCCESS: the customer gets a
     * real, canonical link. Distinct from `Ok` because the operator wants to see how much of the
     * ask rate the dedup is absorbing — but it counts in [[SuccessLabels]], so the #2241 volume
     * panel does not read the fix as a collapse in agent activity.
     */
    case OkDuplicate
    case Denied
    case NoConsent

    /**
     * #2454 — the action is refused because THIS SESSION holds the consented-read scope. Only issue
     * filing produces it: a session that can read the household must not also publish into the
     * public repo. Distinct from [[NoConsent]] (which is the opposite complaint — too little scope)
     * and given its own metric label so an operator can see the pair being attempted.
     */
    case DataSession

    /**
     * #2668 — a LATER dispatch owns this thread's turn, so this session's customer-visible write
     * was dropped. Not a failure and not a success: the customer IS being answered, by the session
     * that superseded this one. Its own label so the rate is readable — expect it to be small and
     * non-zero (it tracks how often a customer's follow-up races a session already in flight), and
     * a spike means dispatches are overlapping far more than the conversation warrants.
     */
    case Superseded

    /**
     * #2667 — this session already put the OTHER KIND of customer-visible message in front of the
     * customer (or has one in flight), and the two [[AgentAction.ThreadWrites]] do not combine: an
     * agent reply beside a consent prompt is attacker-influenced text next to a genuine signed
     * link, under our own attribution. Its own label because the rate is the thing an operator
     * watches — a well-behaved agent that ends its turn after asking produces ZERO of these, so a
     * sustained non-zero rate means either the deployed routine has drifted from the repo prompt
     * (#2469) or something is trying.
     */
    case ConsentExclusive
    case RateLimited
    case Disabled
    case Error
  }
  object AgentActionResult {
    def label(r: AgentActionResult): String = r match {
      case Ok               => "ok"
      case OkNoLink         => "ok_no_link"
      case OkDuplicate      => "ok_duplicate"
      case Denied           => "denied"
      case NoConsent        => "denied"
      case DataSession      => "denied_data_session"
      case Superseded       => "superseded"
      // #2667 — kept distinct from `superseded`: that one is a benign race between two live
      // sessions, this one is an agent talking over the customer's consent decision.
      case ConsentExclusive => "consent_exclusive"
      case RateLimited      => "rate_limited"
      case Disabled         => "disabled"
      case Error            => "error"
    }

    /** The cases that mean "the action succeeded" — the ONE place success is defined. */
    private def isSuccess(r: AgentActionResult): Boolean = r match {
      case Ok | OkNoLink | OkDuplicate => true
      // #2668 `Superseded` is FALSE here even though the caller is told 200: the volume panels this
      // set feeds ask "did the action happen", and a suppressed duplicate did not.
      // #2667 `ConsentExclusive` is likewise FALSE: the suppressed write did not happen.
      case Denied | NoConsent | DataSession | Superseded | ConsentExclusive | RateLimited |
          Disabled | Error =>
        false
    }

    /**
     * The labels a volume/success query must match, DERIVED from the enum (not a hand-written
     * mirror) by filtering `values`. Adding a success case — e.g. #2458's "matched an existing
     * issue" — automatically widens this, and `SupportMetricsContractSpec` asserts the #2241
     * Grafana panel's `outcome=~` matcher against it, so the dashboard cannot silently drift back
     * to under-counting the way it did when `ok` was first split.
     */
    val SuccessLabels: Set[String] = values.filter(isSuccess).map(label).toSet
  }

  /**
   * #2461 — the issue-filing response. `number`/`url` point at the issue in the PUBLIC target repo
   * (`GithubIssueClient.Repo`), so the agent may quote the link to a customer. Both are optional:
   * an issue that GitHub created but whose response we could not read back is still a success, and
   * the agent then simply has no link to offer rather than an invented one.
   */
  final case class FiledIssue(
      number: Option[Int],
      url: Option[String],
      ok: Boolean = true,
      // #2458 — `Some(true)` iff the topic was ALREADY tracked, so `number`/`url` point at a
      // pre-existing issue rather than one we just created. Optional (not a defaulted `Boolean`) so
      // it is ABSENT on an ordinary filing: the agent's prompt contract is that a field it must not
      // quote simply is not there, and the no-link body stays exactly `{"ok":true}`.
      duplicate: Option[Boolean] = None,
  )
  object FiledIssue {
    given JsonCodec[FiledIssue] = DeriveJsonCodec.gen[FiledIssue]
  }

  /**
   * The consented household read response — a BOUNDED account summary. Everything here is chosen
   * because a parental-controls support answer needs it, not because it was available:
   *
   *   - the account frame (name, plan, founding, counts) — who we are talking to;
   *   - per PROFILE: the authored `paused` flag plus TODAY's screen-time state (`usedMinutes`,
   *     `dailyLimitMinutes`, `extensionMinutes`, `remainingMinutes`) and whether anything is
   *     blocking it right now (`blockedNow` + `blockReason`). #2665: "how much screen time did X
   *     use today?" is what the FIRST real prod support conversation asked (#2527 §B), and the
   *     consented read could not answer it at all — the customer granted 24h of account access for
   *     nothing;
   *   - per DEVICE: the device's NAME and the name of the profile it belongs to. Customers ask by
   *     DEVICE ("macbook-pro") and minutes are accounted per PROFILE, so without this join the
   *     agent holds the number and cannot connect it to what was asked.
   *
   * What is deliberately still ABSENT, and should stay absent: MACs, IPs, hostnames, per-host or
   * per-app traffic, query logs, block events, user emails, any history beyond today. Those are the
   * fields that would turn this from "grounds a support answer" into an exfiltration payload if a
   * session were ever hijacked, and none of them is needed for the questions this desk actually
   * receives. Widen only against a real, recurring question — and remember a `dataAccess=true`
   * session is refused issue filing precisely because everything here is unscrubbable by regex
   * (#2454).
   */
  final case class ProfileSummary(
      name: String,
      paused: Boolean,
      // #2665 — today's state, straight off `TimeStatusService.dayStateAll`. Non-optional (with
      // defaults only so existing constructions keep compiling): a read that could not compute
      // these FAILS the whole request rather than answering 0 (#2462).
      usedMinutes: Int = 0,
      // `None` = no daily limit configured for this profile — distinct from a 0-minute cap, and the
      // agent must say "no limit set", not "0 minutes left".
      dailyLimitMinutes: Option[Int] = None,
      extensionMinutes: Int = 0,
      // `None` iff `dailyLimitMinutes` is None — there is nothing to remain against.
      remainingMinutes: Option[Int] = None,
      // The EFFECTIVE right-now state, which is not the same fact as `paused`: a profile can be
      // blocked by schedule or by an exhausted daily limit while `paused` is false.
      blockedNow: Boolean = false,
      // Why, when `blockedNow` — `Paused` | `Schedule` | `TimeLimit` | `Manual` | `Unmanaged` |
      // `DefaultDeny` — `MacBlockReason.asString`, the enum's own case names. `agent.yaml` names
      // exactly these six, so the prompt and this field stay one vocabulary. Distinct from the two
      // wire tags (`jsonKind` / `wireKind`); see the note at the `blockReason` assignment.
      blockReason: Option[String] = None,
  )

  /**
   * #2665 — a device, as the agent needs to see it: the name the customer will use for it and the
   * profile whose minutes govern it. No MAC, no IP, no last-seen — identifying hardware is not what
   * answering a screen-time question requires.
   */
  final case class DeviceSummary(name: String, profileName: Option[String])

  final case class HouseholdSummary(
      name: String,
      plan: Option[String],
      founding: Option[Boolean],
      deviceCount: Int,
      profileCount: Int,
      profiles: List[ProfileSummary],
      devices: List[DeviceSummary] = Nil,
      // #2665 — the household-LOCAL date the minutes above are for (`daily_reset_tz`), so the
      // agent states which day it is quoting instead of assuming the customer's "today" is UTC's.
      // Always `Some` on a successful read; `Option` with a `None` default is the additive-wire
      // shape (docs/process/wire-contract.md), not a state this endpoint produces.
      date: Option[String] = None,
  )
  object ProfileSummary   {
    given JsonCodec[ProfileSummary] = DeriveJsonCodec.gen[ProfileSummary]
  }
  object DeviceSummary    {
    given JsonCodec[DeviceSummary] = DeriveJsonCodec.gen[DeviceSummary]
  }
  object HouseholdSummary {
    given JsonCodec[HouseholdSummary] = DeriveJsonCodec.gen[HouseholdSummary]
  }
}
