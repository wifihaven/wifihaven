package wifihaven.api.feature

import wifihaven.api.{PlainConfig, PressConfig, SupportConfig}
import wifihaven.api.auth.RateLimiter
import wifihaven.api.db.*
import wifihaven.api.notify.{EmailSender, Notifier}
import wifihaven.api.press.{PressAgentDispatcher, PressResponder, PressToken}
import wifihaven.api.routes.{PressAgentRoutes, SupportAgentRoutes}
import wifihaven.api.support.*
import wifihaven.shared.Clock
import wifihaven.shared.Clock.TestClock
import wifihaven.shared.types.HouseholdId
import wifihaven.testinfra.*
import doobie.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.metrics.*
import zio.test.*

/**
 * #2473 — the agent-token lifetime, and what happens when a callback is refused.
 *
 * THE OBSERVED FAILURE this pins. On 2026-07-27 a support responder run was PAUSED by Claude
 * subscription usage limits and resumed ~2.5h later. The per-session token had a 30-minute TTL — it
 * was sized for a synchronous dispatch, and is shorter than the `claude-code-cloud` transport's own
 * retry latency — so the resumed run's reply POST got 401, the customer's answer was thrown away,
 * and NOTHING surfaced the loss. Both halves of that are pinned here: the token now outlives the
 * pause, and a refused callback is loud whether or not the TTL is right.
 *
 * Full stack on embedded Postgres — the real repos, no mocks (docs/process/testing.md); only the
 * external transports (Plain / GitHub / cloud dispatch / Resend) are recorders, and the Clock is a
 * per-test [[TestClock]] this suite ADVANCES to model the pause. No wall-clock sleeps: "2.5 hours
 * later" is a `clock.advance`.
 *
 * The load-bearing pins:
 *   - the resumed-after-usage-limit scenario: a token minted at T is still accepted 3h later (past
 *     the OLD 30m TTL) and the reply is really delivered — support AND press;
 *   - expiry is EXTENDED, not disabled: past the NEW TTL the same token is still refused and
 *     nothing is delivered;
 *   - a refused callback is LOUD — `agent_token_rejected_total{channel,op,reason}` with the right
 *     reason (`token_expired` / `invalid` / `missing`), plus an ERROR log for the expired case (our
 *     bug, a real lost answer) and a WARN for the internet-reachable ones;
 *   - the TTL default is shared by both channels and comfortably exceeds a usage-limit pause, while
 *     the ≥1-minute clamp still rejects a misconfigured 0/negative.
 */
object AgentTokenTtlSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  // The suite's own environment — named so the ZTestLogger-carrying tests can `provideSome` it
  // (the log assertions need ZTestLogger, the repo work needs everything else).
  private type Env = TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val SupportTokenSecret = "agent-token-secret-0123456789abcdef"
  private val PressTokenSecret   = "press-agent-token-secret-0123456789abcdef"

  /**
   * The observed #2473 pause: the run resumed ~2.5h after mint. 3h is that with margin —
   * comfortably past the OLD 30-minute TTL and comfortably inside the new one, so this test fails
   * the moment the TTL is shortened back below a usage-limit window.
   */
  private val UsageLimitPause: java.time.Duration = java.time.Duration.ofHours(3)

  private val supportCfg = SupportConfig(
    responderEnabled = true,
    issueFilingEnabled = true,
    plain = PlainConfig(
      apiKey = "plain-api-key-test",
      webhookSecret = "plain-webhook-signing-secret-xyz",
      escalationLabelTypeId = "lt_escalated_test",
    ),
    anthropicApiKey = "sk-ant-test",
    claudeAgentId = "agent_test",
    claudeEnvironmentId = "env_test",
    agentTokenSecret = SupportTokenSecret,
    deploymentEnv = "staging",
    githubSupportBotToken = "github_pat_test",
  )

  private val pressCfg = PressConfig(
    responderEnabled = true,
    webhookSecret = "press-webhook-signing-secret-xyz",
    anthropicApiKey = "sk-ant-test",
    claudeAgentId = "agent_press_test",
    claudeEnvironmentId = "env_press_test",
    agentTokenSecret = PressTokenSecret,
    deploymentEnv = "staging",
    fromAddress = "WifiHaven Press <press-staging@wifihaven.net>",
    replyToAddress = "press@staging.wifihaven.net",
  )

  // ── Harnesses (a PER-TEST TestClock, so advancing it can't leak across tests) ──

  private final case class SupportRig(
      routes: Routes[Any, Response],
      plain: PlainClient.Recorder,
      clock: TestClock,
  )

  private def supportRig: ZIO[TestDatabase.AllRepos, Nothing, SupportRig] =
    for {
      hhRepo             <- ZIO.service[HouseholdRepo]
      userRepo           <- ZIO.service[UserRepo]
      billRepo           <- ZIO.service[HouseholdBillingRepo]
      devRepo            <- ZIO.service[DeviceRepo]
      profRepo           <- ZIO.service[ProfileRepo]
      consentRepo        <- ZIO.service[SupportConsentRepo]
      (clock, testClock) <- TestClock.makeWithControl(TestClock.schoolDayAfternoon)
      plainRec           <- PlainClient.recorder
      ghRec              <- GithubIssueClient.recorder
      dispRec            <- CloudAgentDispatcher.recorder
      responder = SupportResponder(
        supportCfg,
        hhRepo,
        userRepo,
        billRepo,
        devRepo,
        profRepo,
        consentRepo,
        PlainClient.recording(plainRec),
        GithubIssueClient.recording(ghRec),
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
      )
    } yield SupportRig(SupportAgentRoutes.routes(responder), plainRec, testClock)

  private final case class PressRig(
      routes: Routes[Any, Response],
      emails: Ref[List[EmailSender.Sent]],
      clock: TestClock,
  )

  private def pressRig: ZIO[PressMessageRepo, Nothing, PressRig] =
    for {
      pressLog           <- ZIO.service[PressMessageRepo]
      (clock, testClock) <- TestClock.makeWithControl(TestClock.schoolDayAfternoon)
      emailRef           <- Ref.make(List.empty[EmailSender.Sent])
      dispRec            <- PressAgentDispatcher.recorder
      responder = PressResponder(
        pressCfg,
        EmailSender.recording(emailRef),
        PressAgentDispatcher.recording(dispRec),
        pressLog,
        clock,
        RateLimiter.allowAll,
        RateLimiter.allowAll,
        Notifier.logOnly,
        RateLimiter.allowAll,
      )
    } yield PressRig(PressAgentRoutes.routes(responder), emailRef, testClock)

  /**
   * Mint a support token AT THE CLOCK'S CURRENT TIME with the config's real TTL — as the responder
   * does.
   */
  private def mintSupport(clock: TestClock, thread: String): UIO[String] =
    clock.instant.map(
      ConsentToken.mint(
        HouseholdId(1L),
        thread,
        dataAccess = false,
        _,
        supportCfg.agentTokenTtl,
        SupportTokenSecret,
      ),
    )

  private def mintPress(clock: TestClock, replyTo: String): UIO[String] =
    clock.instant.map(now =>
      PressToken.mint(
        replyTo = replyTo,
        subject = "Press inquiry",
        pressMessageId = 0L,
        // #2451: no inbound Message-ID — this suite exercises the TTL, not reply threading.
        inboundMessageId = "",
        now = now,
        ttl = pressCfg.agentTokenTtl,
        secret = PressTokenSecret,
      ),
    )

  private def post(
      routes: Routes[Any, Response],
      path: String,
      body: String,
      token: Option[String],
  ): Task[Status] = {
    val base = Request.post(URL.decode(path).toOption.get, Body.fromString(body))
    val req  = token.fold(base)(t => base.addHeader(Header.Authorization.Bearer(t)))
    routes.runZIO(req).map(_.status)
  }

  private def supportReply(rig: SupportRig, token: Option[String]): Task[Status] =
    post(rig.routes, "/api/support/agent/reply", """{"markdown":"here is your answer"}""", token)

  private def pressReply(rig: PressRig, token: Option[String]): Task[Status] =
    post(rig.routes, "/api/press/agent/reply", """{"markdown":"a public reply"}""", token)

  /**
   * Read the rejection counter straight off the global registry (as
   * CloudAgentDispatchObservabilitySpec does).
   */
  private def rejections(channel: String, op: String, reason: String): UIO[Double] =
    Metric
      .counter("agent_token_rejected_total")
      .tagged("channel", channel)
      .tagged("op", op)
      .tagged("reason", reason)
      .value
      .map(_.count)

  def spec = suite("agent-token TTL + loud rejection (#2473)")(
    // ── The regression pin: the resumed-after-usage-limit run ──────────────────
    test("#2473 SUPPORT: a token minted at T still delivers the reply after a 3h pause") {
      for {
        _      <- cleanDb
        rig    <- supportRig
        token  <- mintSupport(rig.clock, "th_paused")
        // The run is suspended on usage limits and resumes hours later. Under the old 30m TTL this
        // is exactly where the customer's answer was lost.
        _      <- rig.clock.advance(UsageLimitPause)
        status <- supportReply(rig, Some(token))
        writes <- rig.plain.threads.get
      } yield assertTrue(
        status == Status.Ok,
        // Not just accepted — actually delivered, into the token-bound thread.
        writes.size == 1,
        writes.head.threadId == "th_paused",
      )
    },
    test("#2473 PRESS: a token minted at T still emails the reply after a 3h pause") {
      for {
        _      <- cleanDb
        rig    <- pressRig
        token  <- mintPress(rig.clock, "reporter@example.com")
        _      <- rig.clock.advance(UsageLimitPause)
        status <- pressReply(rig, Some(token))
        emails <- rig.emails.get
      } yield assertTrue(
        status == Status.Ok,
        emails.size == 1,
        emails.head.to == "reporter@example.com",
      )
    },

    // ── Expiry is EXTENDED, not disabled ──────────────────────────────────────
    test("SUPPORT: past the TTL the token is still refused and nothing is delivered") {
      for {
        _      <- cleanDb
        rig    <- supportRig
        token  <- mintSupport(rig.clock, "th_stale")
        _      <- rig.clock.advance(supportCfg.agentTokenTtl.plusMinutes(1))
        status <- supportReply(rig, Some(token))
        writes <- rig.plain.threads.get
      } yield assertTrue(status == Status.Unauthorized, writes.isEmpty)
    },
    test("PRESS: past the TTL the token is still refused and no email is sent") {
      for {
        _      <- cleanDb
        rig    <- pressRig
        token  <- mintPress(rig.clock, "reporter@example.com")
        _      <- rig.clock.advance(pressCfg.agentTokenTtl.plusMinutes(1))
        status <- pressReply(rig, Some(token))
        emails <- rig.emails.get
      } yield assertTrue(status == Status.Unauthorized, emails.isEmpty)
    },

    // ── The refusal is LOUD ───────────────────────────────────────────────────
    test("SUPPORT: an EXPIRED callback meters reason=token_expired and logs an ERROR") {
      ZIO
        .scoped {
          for {
            _      <- cleanDb
            rig    <- supportRig
            token  <- mintSupport(rig.clock, "th_loud")
            _      <- rig.clock.advance(supportCfg.agentTokenTtl.plusMinutes(1))
            before <- rejections("support", "reply", "token_expired")
            status <- supportReply(rig, Some(token))
            after  <- rejections("support", "reply", "token_expired")
            logs   <- ZTestLogger.logOutput
          } yield {
            val errors = logs.filter(_.logLevel == LogLevel.Error).map(_.message())
            assertTrue(
              status == Status.Unauthorized,
              after - before >= 1.0,
              errors.exists(m =>
                m.contains("support: agent callback REJECTED") &&
                  m.contains("op=reply") &&
                  m.contains("reason=token_expired"),
              ),
              // PII firewall: the token itself never reaches a log line.
              logs.forall(e => !e.message().contains(token)),
            )
          }
        }
        .provideSome[Env](ZTestLogger.default)
    },
    test("PRESS: an EXPIRED callback meters reason=token_expired on the SAME series") {
      ZIO
        .scoped {
          for {
            _      <- cleanDb
            rig    <- pressRig
            token  <- mintPress(rig.clock, "reporter@example.com")
            _      <- rig.clock.advance(pressCfg.agentTokenTtl.plusMinutes(1))
            before <- rejections("press", "reply", "token_expired")
            status <- pressReply(rig, Some(token))
            after  <- rejections("press", "reply", "token_expired")
            logs   <- ZTestLogger.logOutput
          } yield {
            val errors = logs.filter(_.logLevel == LogLevel.Error).map(_.message())
            assertTrue(
              status == Status.Unauthorized,
              after - before >= 1.0,
              errors.exists(m =>
                m.contains("press: agent callback REJECTED") && m.contains("reason=token_expired"),
              ),
              // No sender address in any log line — the rejection log carries bounded labels only.
              logs.forall(e => !e.message().contains("reporter@example.com")),
            )
          }
        }
        .provideSome[Env](ZTestLogger.default)
    },
    test("a forged or absent bearer is metered as invalid / missing, and never logged loud") {
      ZIO
        .scoped {
          for {
            _             <- cleanDb
            rig           <- supportRig
            beforeInvalid <- rejections("support", "reply", "invalid")
            beforeMissing <- rejections("support", "reply", "missing")
            sForged       <- supportReply(rig, Some("v1.deadbeef.cafe"))
            sNone         <- supportReply(rig, None)
            afterInvalid  <- rejections("support", "reply", "invalid")
            afterMissing  <- rejections("support", "reply", "missing")
            logs          <- ZTestLogger.logOutput
            writes        <- rig.plain.threads.get
          } yield {
            val debugs = logs.filter(_.logLevel == LogLevel.Debug).map(_.message())
            val loud   =
              logs.filter(e => e.logLevel == LogLevel.Error || e.logLevel == LogLevel.Warning)
            assertTrue(
              sForged == Status.Unauthorized,
              sNone == Status.Unauthorized,
              writes.isEmpty,
              // The COUNTER is the signal for these two — it is what the dashboard reads.
              afterInvalid - beforeInvalid >= 1.0,
              afterMissing - beforeMissing >= 1.0,
              debugs.exists(_.contains("reason=invalid")),
              debugs.exists(_.contains("reason=missing")),
              // Both are reachable by anyone on the internet, and the agent endpoints' rate limiters
              // are acquired AFTER token verification — so an unauthenticated caller must not be able
              // to write a line into the operator's log at all, at ERROR or WARN.
              loud.isEmpty,
            )
          }
        }
        .provideSome[Env](ZTestLogger.default)
    },

    // ── The default itself ────────────────────────────────────────────────────
    test("the TTL default is shared by both channels and outlives a usage-limit pause") {
      val support = SupportConfig().agentTokenTtl
      val press   = PressConfig().agentTokenTtl
      assertTrue(
        // ONE value for both channels — the sizing constraint is a property of the shared cloud
        // transport, not of either audience, so they must not drift apart.
        support == press,
        support == java.time.Duration.ofHours(24),
        // The floor that actually matters: the rolling usage-limit window is ~5h, so anything at or
        // under ~6h reproduces #2473.
        support.compareTo(java.time.Duration.ofHours(6)) > 0,
      )
    },
    test("the ≥1-minute clamp still rejects a misconfigured 0 / negative TTL") {
      assertTrue(
        SupportConfig(agentTokenTtlMinutes = 0).agentTokenTtl == java.time.Duration.ofMinutes(1),
        PressConfig(agentTokenTtlMinutes = -5).agentTokenTtl == java.time.Duration.ofMinutes(1),
      )
    },
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential
}
