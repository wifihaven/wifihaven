package wifihaven.api.feature

import wifihaven.api.{EmailConfig, PlainConfig, PressConfig, SupportConfig}
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
import zio.json.*
import zio.metrics.*
import zio.test.*

/**
 * #2508 — the agent must not be able to leak its OWN bearer credential into the text it sends to a
 * customer or a journalist.
 *
 * WHY this suite exists. The #2335 / #2336 hand validations both reported "programmatic scan of
 * every outbound row: zero token-shaped strings". That result was real, but it is an observation
 * about MODEL BEHAVIOUR under one attack — not a control. Every other guarantee in the support /
 * press trust model holds structurally rather than by prompt (the reply destination comes from the
 * signed token, the household comes from the signed token, issue bodies are PII-scrubbed at the
 * [[GithubIssueClient]] boundary). Credential disclosure was the one leg still resting on the
 * prompt, so it could not be pinned by a test until the redactor existed. This is that pin.
 *
 * Full stack on embedded Postgres — real repos, no mocks (docs/process/testing.md); only the
 * external transports (Plain, GitHub, Resend, the cloud dispatchers) are recorders, and the Clock
 * is injected. The tokens the tests try to leak are LIVE — minted by the real [[ConsentToken.mint]]
 * / [[PressToken.mint]] at the injected clock's instant — so the redactor is matched against what
 * the minting code actually produces today, and a change to either grammar that the redactor did
 * not follow fails HERE.
 *
 * The load-bearing pins:
 *   - a SUPPORT reply quoting the agent's own live token reaches Plain without it;
 *   - a PRESS reply quoting the agent's own live token reaches the journalist without it — and the
 *     `/press` correspondence log does not record it either;
 *   - a bare `Bearer …` credential is redacted on BOTH channels;
 *   - a support issue TITLE and BODY (the PUBLIC GitHub surface) are redacted too;
 *   - an escalation NOTE on both channels reaches the operator without the credential — the sink a
 *     transport-level scrub would have missed, since the note is rendered into a notice body before
 *     any `EmailSender` sees it;
 *   - a `g1.` consent-grant link the agent can read out of thread history (#2441) is covered by the
 *     same rule, because it can be filed into a public issue;
 *   - a redaction is LOUD on BOTH channels — `agent_reply_redacted_total{channel,op,reason}`, with
 *     the two rules metering DISTINCT `reason` labels, plus an ERROR log that does NOT itself quote
 *     the credential;
 *   - FALSE POSITIVES: an ordinary reply carrying a UUID, a `v1.2.3` version string, base64-ish
 *     words, `Bearer authentication-based access control`, a `YOUR_API_KEY_HERE` placeholder and a
 *     short digit-bearing id (`Bearer token1234` — the case that pins the LENGTH floor, which the
 *     digit check cannot save) survives byte-identical.
 */
object ReplyRedactionSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  private type Env = TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val SupportTokenSecret = "agent-token-secret-0123456789abcdef"
  private val PressTokenSecret   = "press-agent-token-secret-0123456789abcdef"

  /** What a redacted credential is replaced with — the marker the assertions look for. */
  private val Marker = "[redacted-credential]"

  /**
   * #2437: the operator mailbox an escalation notice is addressed to. The rigs below wire the REAL
   * `Notifier.EmailNotifier` over a recording `EmailSender`, so the escalation-note assertions read
   * the body a live send would carry — the note passes through the notice renderer, which is
   * exactly the sink a transport-level scrub would have missed.
   */
  private val OperatorAddress = "operator@wifihaven.test"

  private val emailCfg = EmailConfig(
    enabled = true,
    resendApiKey = "re_test",
    fromAddress = "alerts@wifihaven.test",
    operatorAddress = OperatorAddress,
    appBaseUrl = "https://app.example.test",
  )

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

  // ── Harnesses ───────────────────────────────────────────────────────────────

  private final case class SupportRig(
      routes: Routes[Any, Response],
      plain: PlainClient.Recorder,
      github: GithubIssueClient.Recorder,
      emails: Ref[List[EmailSender.Sent]],
      clock: Clock,
  )

  private def supportRig: ZIO[TestDatabase.AllRepos & Clock, Nothing, SupportRig] =
    for {
      hhRepo      <- ZIO.service[HouseholdRepo]
      userRepo    <- ZIO.service[UserRepo]
      billRepo    <- ZIO.service[HouseholdBillingRepo]
      devRepo     <- ZIO.service[DeviceRepo]
      profRepo    <- ZIO.service[ProfileRepo]
      hsRepo      <- ZIO.service[HouseholdSettingsRepo]
      timeStatus  <- TestLayers.timeStatusService
      hsRepo      <- ZIO.service[HouseholdSettingsRepo]
      consentRepo <- ZIO.service[SupportConsentRepo]
      clock       <- ZIO.service[Clock]
      plainRec    <- PlainClient.recorder
      ghRec       <- GithubIssueClient.recorder
      dispRec     <- CloudAgentDispatcher.recorder
      emailRef    <- Ref.make(List.empty[EmailSender.Sent])
      tracker     <- DispatchTracker.make(
        DispatchTracker.deadAfterFor(supportCfg),
        DispatchTracker.Channel.Support,
      )
      responder = SupportResponder(
        supportCfg,
        hhRepo,
        userRepo,
        billRepo,
        devRepo,
        profRepo,
        hsRepo,
        timeStatus,
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
        new Notifier.EmailNotifier(hsRepo, EmailSender.recording(emailRef), emailCfg),
        RateLimiter.allowAll,
        tracker,
      )
    } yield SupportRig(SupportAgentRoutes.routes(responder), plainRec, ghRec, emailRef, clock)

  private final case class PressRig(
      routes: Routes[Any, Response],
      emails: Ref[List[EmailSender.Sent]],
      pressLog: PressMessageRepo,
      clock: Clock,
  )

  private def pressRig: ZIO[PressMessageRepo & HouseholdSettingsRepo & Clock, Nothing, PressRig] =
    for {
      pressLog     <- ZIO.service[PressMessageRepo]
      hsRepo       <- ZIO.service[HouseholdSettingsRepo]
      clock        <- ZIO.service[Clock]
      emailRef     <- Ref.make(List.empty[EmailSender.Sent])
      dispRec      <- PressAgentDispatcher.recorder
      pressTracker <- DispatchTracker.make(
        DispatchTracker.deadAfterFor(pressCfg),
        DispatchTracker.Channel.Press,
      )
      transport = EmailSender.recording(emailRef)
      responder = PressResponder(
        pressCfg,
        transport,
        PressAgentDispatcher.recording(dispRec),
        pressLog,
        clock,
        RateLimiter.allowAll,
        RateLimiter.allowAll,
        new Notifier.EmailNotifier(hsRepo, transport, emailCfg),
        RateLimiter.allowAll,
        pressTracker,
      )
    } yield PressRig(PressAgentRoutes.routes(responder), emailRef, pressLog, clock)

  /** A LIVE support token, minted exactly as the responder mints it. */
  private def mintSupport(clock: Clock, thread: String): UIO[String] =
    clock.instant.map(
      ConsentToken.mint(
        HouseholdId(1L),
        thread,
        dataAccess = false,
        _,
        supportCfg.agentTokenTtl,
        SupportTokenSecret,
        ConsentToken.newSessionId(),
      ),
    )

  /** A LIVE press token, minted exactly as the responder mints it. */
  private def mintPress(clock: Clock, replyTo: String): UIO[String] =
    clock.instant.map(now =>
      PressToken.mint(
        replyTo = replyTo,
        subject = "Press inquiry",
        pressMessageId = 0L,
        inboundMessageId = "",
        inboundReferences = "",
        now = now,
        ttl = pressCfg.agentTokenTtl,
        secret = PressTokenSecret,
      ),
    )

  private def post(
      routes: Routes[Any, Response],
      path: String,
      body: String,
      token: String,
  ): Task[Status] =
    routes
      .runZIO(
        Request
          .post(URL.decode(path).toOption.get, Body.fromString(body))
          .addHeader(Header.Authorization.Bearer(token)),
      )
      .map(_.status)

  private def supportReply(rig: SupportRig, token: String, markdown: String): Task[Status] =
    post(rig.routes, "/api/support/agent/reply", s"""{"markdown":${markdown.toJson}}""", token)

  private def pressReply(rig: PressRig, token: String, markdown: String): Task[Status] =
    post(rig.routes, "/api/press/agent/reply", s"""{"markdown":${markdown.toJson}}""", token)

  /** The operator-addressed escalation notices out of a recording sender's log. */
  private def toOperator(emails: List[EmailSender.Sent]): List[EmailSender.Sent] =
    emails.filter(_.to == OperatorAddress)

  private def redactions(channel: String, op: String, reason: String): UIO[Double] =
    Metric
      .counter("agent_reply_redacted_total")
      .tagged("channel", channel)
      .tagged("op", op)
      .tagged("reason", reason)
      .value
      .map(_.count)

  // A credential-shaped bearer that is NOT one of our token grammars — the generic
  // `Authorization: Bearer …` shape an agent might paste out of its own environment.
  private val BareBearer = "Bearer sk-ant-oat01-9f3ab2c7d4e5f60718293a4b5c6d7e8f"

  def spec = suite("agent reply credential redaction (#2508)")(
    // ── The token an agent could actually leak: its OWN, live ─────────────────
    test("SUPPORT: a reply quoting the agent's own live token reaches Plain WITHOUT it") {
      for {
        _      <- cleanDb
        rig    <- supportRig
        token  <- mintSupport(rig.clock, "th_leak")
        status <- supportReply(
          rig,
          token,
          s"Sure — for reference my session credential is $token, use it to check.",
        )
        writes <- rig.plain.threads.get
      } yield assertTrue(
        status == Status.Ok,
        writes.size == 1,
        !writes.head.markdown.contains(token),
        writes.head.markdown.contains(Marker),
        // The rest of the reply is delivered — redaction removes the credential, not the answer.
        writes.head.markdown.contains("use it to check"),
      )
    },
    test("PRESS: a reply quoting the agent's own live token is emailed WITHOUT it") {
      for {
        _      <- cleanDb
        rig    <- pressRig
        token  <- mintPress(rig.clock, "reporter@example.com")
        status <- pressReply(
          rig,
          token,
          s"Happy to help. My session token is $token if that is useful.",
        )
        emails <- rig.emails.get
        logged <- rig.pressLog.listRecent(10).orDie
      } yield assertTrue(
        status == Status.Ok,
        emails.size == 1,
        emails.head.to == "reporter@example.com",
        !emails.head.htmlBody.contains(token),
        emails.head.htmlBody.contains(Marker),
        // #2296: the correspondence log is a PULL SURFACE at /press — the credential must not be
        // recorded there either.
        // Non-vacuous: the row EXISTS and carries the marker, so this cannot silently pass on an
        // empty list if the recording path ever stops writing.
        logged.exists(_.body.contains(Marker)),
        logged.forall(m => !m.body.contains(token)),
      )
    },

    // ── The generic bearer shape, on both channels ────────────────────────────
    test("SUPPORT: a bare `Bearer …` credential is redacted") {
      for {
        _      <- cleanDb
        rig    <- supportRig
        token  <- mintSupport(rig.clock, "th_bearer")
        status <- supportReply(rig, token, s"Try calling the API with `$BareBearer`.")
        writes <- rig.plain.threads.get
      } yield assertTrue(
        status == Status.Ok,
        writes.size == 1,
        !writes.head.markdown.contains("sk-ant-oat01-9f3ab2c7d4e5f60718293a4b5c6d7e8f"),
        writes.head.markdown.contains(Marker),
      )
    },
    test("PRESS: a bare `Bearer …` credential is redacted") {
      for {
        _      <- cleanDb
        rig    <- pressRig
        token  <- mintPress(rig.clock, "reporter@example.com")
        status <- pressReply(rig, token, s"For context: $BareBearer")
        emails <- rig.emails.get
      } yield assertTrue(
        status == Status.Ok,
        emails.size == 1,
        !emails.head.htmlBody.contains("sk-ant-oat01-9f3ab2c7d4e5f60718293a4b5c6d7e8f"),
        emails.head.htmlBody.contains(Marker),
      )
    },

    // ── The PUBLIC GitHub surface gets the same treatment ─────────────────────
    test("SUPPORT: an issue TITLE and BODY quoting the agent's own token are filed WITHOUT it") {
      for {
        _     <- cleanDb
        rig   <- supportRig
        token <- mintSupport(rig.clock, "th_issue")
        // The TITLE is the most public field of the most public surface — it shows in the issue
        // list and in every notification GitHub sends — so it is scrubbed and pinned separately.
        title = s"Blocking fails (session $token)"
        body  = s"Blocking fails. Session credential for repro: $token"
        status <- post(
          rig.routes,
          "/api/support/agent/issues",
          s"""{"title":${title.toJson},"body":${body.toJson}}""",
          token,
        )
        issues <- rig.github.issues.get
      } yield assertTrue(
        status == Status.Ok,
        issues.size == 1,
        !issues.head.body.contains(token),
        issues.head.body.contains(Marker),
        !issues.head.title.contains(token),
        issues.head.title.contains(Marker),
      )
    },

    // ── The escalation note — agent-authored text that reaches the OPERATOR ───
    // The sink a transport-level scrub would have missed: the note is folded into a rendered
    // notice body by Notifier before any EmailSender sees it.
    test(
      "SUPPORT: an escalation note quoting the agent's own token reaches the operator WITHOUT it",
    ) {
      for {
        _     <- cleanDb
        rig   <- supportRig
        token <- mintSupport(rig.clock, "th_esc")
        note = s"Handing over. My session credential is $token if you need it."
        status <- post(
          rig.routes,
          "/api/support/agent/escalate",
          s"""{"note":${note.toJson}}""",
          token,
        )
        emails <- rig.emails.get
      } yield {
        val notices = toOperator(emails)
        assertTrue(
          status == Status.Ok,
          notices.size == 1,
          !notices.head.htmlBody.contains(token),
          notices.head.htmlBody.contains(Marker),
        )
      }
    },
    test(
      "PRESS: an escalation note quoting the agent's own token reaches the operator WITHOUT it",
    ) {
      for {
        _     <- cleanDb
        rig   <- pressRig
        token <- mintPress(rig.clock, "reporter@example.com")
        note = s"Needs a human. Session credential: $token"
        status <- post(
          rig.routes,
          "/api/press/agent/escalate",
          s"""{"note":${note.toJson}}""",
          token,
        )
        emails <- rig.emails.get
      } yield {
        val notices = toOperator(emails)
        assertTrue(
          status == Status.Ok,
          notices.size == 1,
          !notices.head.htmlBody.contains(token),
          notices.head.htmlBody.contains(Marker),
        )
      }
    },

    // ── The consent-grant token the agent can read out of thread history ──────
    test("SUPPORT: a `g1.` consent-grant link quoted into a reply is redacted too") {
      for {
        _      <- cleanDb
        rig    <- supportRig
        token  <- mintSupport(rig.clock, "th_grant")
        // #2441: the agent sees prior thread messages, which include any consent link WE posted.
        grant  <- rig.clock.instant.map(
          ConsentGrant.mint(
            HouseholdId(1L),
            "th_grant",
            _,
            java.time.Duration.ofMinutes(30),
            SupportTokenSecret,
            // #2453: a fresh single-use nonce, minted the same way the server does.
            ConsentGrant.newNonce(),
          ),
        )
        status <- supportReply(rig, token, s"Here is your consent link again: $grant")
        writes <- rig.plain.threads.get
      } yield assertTrue(
        status == Status.Ok,
        writes.size == 1,
        !writes.head.markdown.contains(grant),
        writes.head.markdown.contains(Marker),
      )
    },

    // ── A redaction is LOUD ───────────────────────────────────────────────────
    test("PRESS: a redaction meters the shared series on the press channel and logs an ERROR") {
      (for {
        _      <- cleanDb
        rig    <- pressRig
        token  <- mintPress(rig.clock, "reporter@example.com")
        before <- redactions("press", "reply", "agent_token")
        status <- pressReply(rig, token, s"credential: $token")
        after  <- redactions("press", "reply", "agent_token")
        logs   <- ZTestLogger.logOutput
      } yield {
        val errors = logs.filter(_.logLevel == LogLevel.Error).map(_.message())
        assertTrue(
          status == Status.Ok,
          after == before + 1,
          errors.exists(m => m.contains("redacted") && m.contains("press")),
          logs.forall(e => !e.message().contains(token)),
        )
      }).provideSome[Env](ZTestLogger.default)
    },
    test("the bearer rule meters its OWN reason label, distinct from the token rule") {
      for {
        _      <- cleanDb
        rig    <- supportRig
        token  <- mintSupport(rig.clock, "th_bearer_metric")
        before <- redactions("support", "reply", "bearer")
        status <- supportReply(rig, token, s"see `$BareBearer`")
        after  <- redactions("support", "reply", "bearer")
      } yield assertTrue(status == Status.Ok, after == before + 1)
    },
    test("a redaction meters agent_reply_redacted_total and logs an ERROR that omits the token") {
      (for {
        _      <- cleanDb
        rig    <- supportRig
        token  <- mintSupport(rig.clock, "th_loud")
        before <- redactions("support", "reply", "agent_token")
        status <- supportReply(rig, token, s"credential: $token")
        after  <- redactions("support", "reply", "agent_token")
        logs   <- ZTestLogger.logOutput
      } yield {
        val errors = logs.filter(_.logLevel == LogLevel.Error).map(_.message())
        assertTrue(
          status == Status.Ok,
          after == before + 1,
          errors.exists(m => m.contains("redacted") && m.contains("support")),
          // The alert must not become the leak: no log line quotes the credential.
          logs.forall(e => !e.message().contains(token)),
        )
      }).provideSome[Env](ZTestLogger.default)
    },

    // ── FALSE POSITIVES: ordinary prose must survive byte-identical ───────────
    test("SUPPORT: an ordinary reply with a UUID, a version string and prose is untouched") {
      // DO NOT TRIM THIS STRING. Each clause pins a different half of the bearer rule, and
      // `Bearer token1234` is the ONLY input that pins the length floor — it is short and DOES
      // carry a digit, so the digit check cannot save it. Drop that clause and
      // `MinBearerValueChars` becomes deletable with the suite still green.
      // Every one of these was a real over-match candidate: the hyphenated compound and the
      // `WORD_WORD` placeholder both clear the 16-char floor and survive only because neither
      // carries a digit; `credentials.Rotate` is the missing-space-after-a-period case.
      val ordinary =
        """Your device 550e8400-e29b-41d4-a716-446655440000 is on firmware v1.2.3, and the
          |agent build is v1.4.0-rc2. We use Bearer authentication-based access control; set
          |`Authorization: Bearer YOUR_API_KEY_HERE`. Rotate bearer credentials.Rotate them
          |quarterly. Short ids like `Bearer token1234` are below the length floor, so they
          |survive on their own — that is the half of the rule the digit check does NOT
          |cover. The config value is base64 encoded (for example aGVsbG8gd29ybGQ) —
          |nothing secret.""".stripMargin
      for {
        _      <- cleanDb
        rig    <- supportRig
        token  <- mintSupport(rig.clock, "th_clean")
        status <- supportReply(rig, token, ordinary)
        writes <- rig.plain.threads.get
      } yield assertTrue(
        status == Status.Ok,
        writes.size == 1,
        // Byte-identical after the server-owned attribution header — nothing was rewritten.
        writes.head.markdown.endsWith(ordinary),
        !writes.head.markdown.contains(Marker),
      )
    },
    test("PRESS: an ordinary reply with a UUID, a version string and prose is untouched") {
      val ordinary =
        "We shipped v1.2.3 on 2026-07-01; the beta id is 550e8400-e29b-41d4-a716-446655440000. " +
          "Bearer authentication is used for the API."
      for {
        _      <- cleanDb
        rig    <- pressRig
        token  <- mintPress(rig.clock, "reporter@example.com")
        status <- pressReply(rig, token, ordinary)
        emails <- rig.emails.get
      } yield assertTrue(
        status == Status.Ok,
        emails.size == 1,
        emails.head.htmlBody.contains(ordinary),
        !emails.head.htmlBody.contains(Marker),
      )
    },
  ) @@ TestAspect.sequential
}
