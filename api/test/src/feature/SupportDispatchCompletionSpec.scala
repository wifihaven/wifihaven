package wifihaven.api.feature

import wifihaven.api.{PlainConfig, SupportConfig}
import wifihaven.api.auth.RateLimiter
import wifihaven.api.db.*
import wifihaven.api.notify.Notifier
import wifihaven.api.routes.SupportAgentRoutes
import wifihaven.api.support.*
import wifihaven.shared.Clock
import wifihaven.shared.Clock.TestClock
import wifihaven.shared.types.HouseholdId
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import doobie.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.metrics.*
import zio.test.*

/**
 * #2472 — dispatch→completion tracking for the #2200 support responder.
 *
 * WHAT WENT WRONG (observed live 2026-07-26, #2335 validation). A registered admin emailed support.
 * The dispatch half was textbook — token minted, `support agent dispatched
 * transport=claude-code-cloud`, webhook `outcome=email_registered_dispatched` — and then NOTHING:
 * no `/api/support/agent/…` call ever arrived, then or three hours later. The customer got no
 * reply, while `support_dispatch_total{outcome="dispatched"}` counted the interaction a success.
 * #2444 instruments the dispatch CALL and #2416 makes a 4xx on it fail loud; neither observes
 * whether the agent ever came back, so "answered the customer" and "silently died" were the same
 * signal.
 *
 * The load-bearing pins, driven through the REAL responder + routes (embedded Postgres, no repo
 * mocks — only the Plain / GitHub / cloud-agent transports are recorders, per
 * docs/process/testing.md):
 *   - a webhook that dispatches REGISTERS the dispatch, and the agent's token-authenticated
 *     `/reply` CLOSES it — `support_dispatch_total{outcome="completed",transport}`, and a sweep
 *     long past the dead threshold then reports NOTHING;
 *   - a dispatch that is never called back is reported at the WARN tier past `SlowAfter` —
 *     `{outcome="callback_slow"}` + a WARNING — and exactly ONCE, no matter how often the sweep
 *     runs (a suspended-then-resumed run must not spam);
 *   - past `DeadAfter` it is an attributable ERROR + `{outcome="no_callback"}` naming the thread
 *     and household, emitted exactly once (the entry is dropped);
 *   - `/escalate` and `/request-consent` close a dispatch too — they are the other two terminal
 *     agent actions — while a household READ does NOT: a session that read the household and then
 *     died is precisely the failure being tracked;
 *   - a REJECTED callback (no token) closes nothing, so a forged caller cannot silence the report;
 *   - PII firewall: no report line carries the customer's message text.
 */
object SupportDispatchCompletionSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val WebhookSecret = "plain-webhook-signing-secret-xyz"
  private val TokenSecret   = "agent-token-secret-0123456789abcdef"

  /** A distinctive untrusted payload that must never appear in a tracker report. */
  private val SecretMsg = "SECRET-CUSTOMER-PAYLOAD-2472-do-not-log"

  // The Claude Code Cloud transport — the one the live failure was observed on, and the one whose
  // usage-limit suspends motivate the two-tier threshold.
  private val liveCfg = SupportConfig(
    responderEnabled = true,
    dispatcher = "claude-code-cloud",
    plain = PlainConfig(
      apiKey = "plain-api-key-test",
      webhookSecret = WebhookSecret,
      escalationLabelTypeId = "lt_escalated_test",
    ),
    claudeCodeRoutineId = "routine_test",
    claudeCodeRoutineToken = "sk-ant-oat01-test",
    agentTokenSecret = TokenSecret,
    deploymentEnv = "staging",
  )

  private val Transport = CloudAgentObservability.ClaudeCodeCloud

  private final case class Rig(
      routes: Routes[Any, Response],
      tracker: DispatchTracker,
      // #2668: the agent token a dispatch minted is now SESSION-SCOPED, so a callback has to
      // present the token of the session it belongs to. Reading it back off the recorder is also
      // more faithful than minting a lookalike — it is exactly what the cloud run is handed.
      dispatches: CloudAgentDispatcher.Recorder,
  )

  private def makeRig =
    for {
      hhRepo      <- ZIO.service[HouseholdRepo]
      userRepo    <- ZIO.service[UserRepo]
      billRepo    <- ZIO.service[HouseholdBillingRepo]
      devRepo     <- ZIO.service[DeviceRepo]
      profRepo    <- ZIO.service[ProfileRepo]
      hsRepo      <- ZIO.service[HouseholdSettingsRepo]
      timeStatus  <- TestLayers.timeStatusService
      consentRepo <- ZIO.service[SupportConsentRepo]
      clock       <- ZIO.service[Clock]
      plainRec    <- PlainClient.recorder
      dispRec     <- CloudAgentDispatcher.recorder
      tracker     <- DispatchTracker.make(
        DispatchTracker.deadAfterFor(liveCfg),
        wifihaven.api.observability.AgentTokenRejection.Channel.Support,
      )
      responder = SupportResponder(
        liveCfg,
        hhRepo,
        userRepo,
        billRepo,
        devRepo,
        profRepo,
        hsRepo,
        timeStatus,
        consentRepo,
        PlainClient.recording(plainRec),
        GithubIssueClient.noop,
        CloudAgentDispatcher.recording(dispRec),
        clock,
        RateLimiter.allowAll,
        RateLimiter.allowAll,
        RateLimiter.allowAll,
        RateLimiter.allowAll,
        RateLimiter.allowAll,
        RateLimiter.allowAll,
        "https://app.example.test",
        Notifier.logOnly,
        RateLimiter.allowAll,
        tracker,
      )
    } yield Rig(SupportAgentRoutes.routes(responder), tracker, dispRec)

  private def payload(tenant: Long, threadId: String, text: String): String =
    s"""{"workspaceId":"w_1","id":"pEv_chat","payload":{"eventType":"thread.chat_received",""" +
      s""""chat":{"text":${text.toJson},"createdBy":{"actorType":"customer"}},""" +
      s""""thread":{"id":"$threadId",""" +
      s""""customer":{"id":"c_1","externalId":"$tenant"}}}}"""

  private def dispatchOne(rig: Rig, hh: HouseholdId, threadId: String): Task[Status] = {
    val body = payload(hh.value, threadId, SecretMsg)
    val req  = Request
      .post(URL.decode("/api/support/webhook").toOption.get, Body.fromString(body))
      .addHeader(
        PlainWebhook.SignatureHeader,
        SupportService.hmacSha256Hex(WebhookSecret, body),
      )
    rig.routes.runZIO(req).map(_.status)
  }

  /** The token of the session the LAST dispatch started — what its callbacks must present. */
  private def sessionToken(rig: Rig): Task[String] =
    rig.dispatches.dispatches.get.flatMap(all =>
      ZIO
        .fromOption(all.lastOption.map(_._1.agentToken))
        .orElseFail(new RuntimeException("nothing dispatched")),
    )

  private def agentPost(
      rig: Rig,
      path: String,
      body: String,
      token: Option[String],
  ): Task[Status] = {
    val base = Request.post(URL.decode(path).toOption.get, Body.fromString(body))
    val req  = token.fold(base)(t => base.addHeader(Header.Authorization.Bearer(t)))
    rig.routes.runZIO(req).map(_.status)
  }

  private def agentGetHousehold(rig: Rig, token: String): Task[Status] =
    rig.routes
      .runZIO(
        Request
          .get(URL.decode("/api/support/agent/household").toOption.get)
          .addHeader(Header.Authorization.Bearer(token)),
      )
      .map(_.status)

  private def mintToken(hh: HouseholdId, threadId: String, dataAccess: Boolean = false) =
    ZIO.serviceWithZIO[Clock](_.instant).map { now =>
      // The rig's TTL comes from the SAME config the tracker's dead threshold does, so retuning
      // `agentTokenTtlMinutes` can never put `PastDead` beyond the test tokens' own expiry.
      ConsentToken.mint(
        hh,
        threadId,
        dataAccess,
        now,
        liveCfg.agentTokenTtl,
        TokenSecret,
        ConsentToken.newSessionId(),
      )
    }

  private def counter(outcome: String): UIO[Double] =
    Metric
      .counter("support_dispatch_total")
      .tagged("outcome", outcome)
      .tagged("transport", Transport)
      .value
      .map(_.count)

  /** A household with an admin, plus the rig. */
  private def seeded(threadTag: String) =
    for {
      _      <- cleanDb
      hhRepo <- ZIO.service[HouseholdRepo]
      hh     <- hhRepo.create(s"Family $threadTag", s"family-$threadTag")
      rig    <- makeRig
    } yield (hh, rig)

  /** `now` shifted by `d` from the injected clock — the sweep takes its instant as a parameter. */
  private def at(d: java.time.Duration) =
    ZIO.serviceWithZIO[Clock](_.instant).map(_.plus(d))

  private val PastSlow = DispatchTracker.SlowAfter.plusMinutes(1)

  // The ERROR tier is the CONFIGURED agent-token TTL (#2472 review): past it a resumed session's
  // callback would 401, so the dispatch is unconditionally dead. Read from the same config the rig
  // builds the tracker from — never a literal, which would drift the moment the TTL is retuned.
  private val PastDead = DispatchTracker.deadAfterFor(liveCfg).plusMinutes(1)

  def spec = suite("support dispatch→completion tracking (#2472)")(
    test("an agent reply CLOSES the dispatch: completed is metered and no sweep ever reports it") {
      for {
        (hh, rig)  <- seeded("closed")
        before     <- counter(DispatchTracker.Outcome.Completed)
        status     <- dispatchOne(rig, hh, "th_closed")
        token      <- sessionToken(rig)
        replied    <- agentPost(
          rig,
          "/api/support/agent/reply",
          """{"markdown":"here you go"}""",
          Some(token),
        )
        after      <- counter(DispatchTracker.Outcome.Completed)
        deadBefore <- counter(DispatchTracker.Outcome.NoCallback)
        now        <- at(PastDead)
        _          <- rig.tracker.sweep(now)
        deadAfter  <- counter(DispatchTracker.Outcome.NoCallback)
      } yield assertTrue(
        status == Status.Ok,
        replied == Status.Ok,
        after == before + 1,
        deadAfter == deadBefore,
      )
    },
    test("a dispatch nobody calls back is WARNed past SlowAfter — and exactly once") {
      {
        for {
          (hh, rig) <- seeded("slow")
          before    <- counter(DispatchTracker.Outcome.CallbackSlow)
          _         <- dispatchOne(rig, hh, "th_slow")
          now       <- at(PastSlow)
          _         <- rig.tracker.sweep(now)
          once      <- counter(DispatchTracker.Outcome.CallbackSlow)
          // A second sweep, still inside DeadAfter: the entry survives (a suspended run can still
          // answer) but must NOT re-report.
          _         <- rig.tracker.sweep(now.plusSeconds(120))
          twice     <- counter(DispatchTracker.Outcome.CallbackSlow)
          logs      <- ZTestLogger.logOutput
        } yield {
          val warns = logs.filter(_.logLevel == LogLevel.Warning).map(_.message())
          assertTrue(
            once == before + 1,
            twice == once,
            warns.exists(m =>
              m.contains("support dispatch still unanswered") && m.contains("thread=th_slow"),
            ),
            logs.forall(e => !e.message().contains(SecretMsg)),
          )
        }
      }.provideSomeLayer[
        TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task],
      ](ZTestLogger.default)
    },
    test("past DeadAfter it is an attributable ERROR + no_callback, emitted exactly once") {
      {
        for {
          (hh, rig) <- seeded("dead")
          before    <- counter(DispatchTracker.Outcome.NoCallback)
          _         <- dispatchOne(rig, hh, "th_dead")
          now       <- at(PastDead)
          _         <- rig.tracker.sweep(now)
          once      <- counter(DispatchTracker.Outcome.NoCallback)
          // The entry is dropped, so a later sweep reports nothing more.
          _         <- rig.tracker.sweep(now.plusSeconds(600))
          twice     <- counter(DispatchTracker.Outcome.NoCallback)
          logs      <- ZTestLogger.logOutput
        } yield {
          val errors = logs.filter(_.logLevel == LogLevel.Error).map(_.message())
          assertTrue(
            once == before + 1,
            twice == once,
            errors.exists(m =>
              m.contains("support dispatch NEVER CALLED BACK") &&
                m.contains("thread=th_dead") &&
                m.contains(s"household=${hh.value}") &&
                m.contains(s"transport=$Transport"),
            ),
            logs.forall(e => !e.message().contains(SecretMsg)),
          )
        }
      }.provideSomeLayer[
        TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task],
      ](ZTestLogger.default)
    },
    test("escalate closes a dispatch (it is a terminal agent action)") {
      for {
        (hh, rig)  <- seeded("esc")
        _          <- dispatchOne(rig, hh, "th_esc")
        token      <- sessionToken(rig)
        before     <- counter(DispatchTracker.Outcome.Completed)
        status     <- agentPost(
          rig,
          "/api/support/agent/escalate",
          """{"note":"needs a human"}""",
          Some(token),
        )
        after      <- counter(DispatchTracker.Outcome.Completed)
        deadBefore <- counter(DispatchTracker.Outcome.NoCallback)
        now        <- at(PastDead)
        _          <- rig.tracker.sweep(now)
        deadAfter  <- counter(DispatchTracker.Outcome.NoCallback)
      } yield assertTrue(
        status == Status.Ok,
        after == before + 1,
        deadAfter == deadBefore,
      )
    },
    test("request-consent closes a dispatch (the server posts a prompt the customer sees)") {
      for {
        (hh, rig) <- seeded("cons")
        _         <- dispatchOne(rig, hh, "th_cons")
        token     <- sessionToken(rig)
        before    <- counter(DispatchTracker.Outcome.Completed)
        status    <- agentPost(rig, "/api/support/agent/request-consent", "", Some(token))
        after     <- counter(DispatchTracker.Outcome.Completed)
      } yield assertTrue(status == Status.Ok, after == before + 1)
    },
    test(
      "a household READ does NOT close a dispatch — a session that read and then died is the bug",
    ) {
      for {
        (hh, rig) <- seeded("read")
        _         <- dispatchOne(rig, hh, "th_read")
        token     <- mintToken(hh, "th_read", dataAccess = true)
        // The status is ASSERTED, not discarded: the pin is "the read reached the token check and
        // still did not close", so a request that 404'd or was rejected outright would satisfy the
        // no_callback assertion vacuously. Forbidden (not Ok) because no consent row is seeded —
        // #2476 re-reads the grant at read time, and that refusal happens AFTER withClaimsE, which
        // is exactly where a TERMINAL action would have closed the dispatch.
        read      <- agentGetHousehold(rig, token)
        before    <- counter(DispatchTracker.Outcome.NoCallback)
        now       <- at(PastDead)
        _         <- rig.tracker.sweep(now)
        after     <- counter(DispatchTracker.Outcome.NoCallback)
      } yield assertTrue(read == Status.Forbidden, after == before + 1)
    },
    test("a callback REJECTED at the token check closes nothing — the report still fires") {
      for {
        (hh, rig) <- seeded("forged")
        _         <- dispatchOne(rig, hh, "th_forged")
        status    <- agentPost(rig, "/api/support/agent/reply", """{"markdown":"hi"}""", None)
        before    <- counter(DispatchTracker.Outcome.NoCallback)
        now       <- at(PastDead)
        _         <- rig.tracker.sweep(now)
        after     <- counter(DispatchTracker.Outcome.NoCallback)
      } yield assertTrue(status == Status.Unauthorized, after == before + 1)
    },
  ) @@ TestAspect.sequential
}
