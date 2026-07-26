package wifihaven.api.feature

import wifihaven.api.{MetricsConfig, PlainConfig, PressConfig, SupportConfig}
import wifihaven.api.auth.RateLimiter
import wifihaven.api.db.*
import wifihaven.api.metrics.MetricsRuntime
import wifihaven.api.notify.EmailSender
import wifihaven.api.press.{PressAgentDispatcher, PressResponder}
import wifihaven.api.routes.MetricsRoutes
import wifihaven.api.support.*
import wifihaven.shared.Clock
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.metrics.connectors.prometheus.PrometheusPublisher
import zio.test.*

import java.io.OutputStream
import java.net.InetSocketAddress

/**
 * #2416 — a cloud-agent dispatch failure that will NEVER succeed on retry (a revoked Anthropic key
 * → 401, a wrong `claudeAgentId` / `claudeEnvironmentId` / `claudeCodeRoutineId` → 404, a stale
 * hard-coded `anthropic-beta` header → 400) must be attributable as a PERMANENT misconfiguration,
 * not collapsed into the same bucket as a transient Anthropic 5xx.
 *
 * Before #2416 BOTH dispatchers (support #2200 and press #2203) mapped every failure to one
 * `logWarning` + a single flat `{support,press}_ai_draft_total{outcome=error}` — so a permanently
 * dead responder read as intermittent noise (docs/process/no-dark-by-default.md). This spec drives
 * the LIVE dispatchers against a local JDK `HttpServer` standing in for the Anthropic boundary
 * (chosen status per test, no real network) through the REAL responders on embedded Postgres (no
 * repo mocks — docs/process/testing.md), and scrapes the live Prometheus publisher on the same `GET
 * /metrics` path prod scrapes, asserting the bounded `reason` label: `config` (4xx, permanent) vs
 * `transient` (5xx / transport).
 *
 * BOTH audiences are pinned because they share ONE classifier
 * ([[wifihaven.api.support.CloudAgentObservability]]) — if it drifts on one path, a test here
 * fails. Fail-open is pinned too: the webhook still reports success-shaped handling and never
 * throws.
 */
object CloudAgentDispatchFailLoudSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val SupportWebhookSecret = "plain-webhook-signing-secret-2416"
  private val PressWebhookSecret   = "press-webhook-signing-secret-2416"
  private val TokenSecret          = "agent-token-secret-0123456789abcdef"

  // ── Anthropic-boundary stub ────────────────────────────────────────────────
  // Returns a fixed status for every request, so one test models "the key is revoked" (401), another
  // "the agent id is wrong" (404), another "Anthropic is having a bad minute" (500). The LIVE
  // ManagedAgents / ClaudeCodeRoutines transports run unchanged against it.
  private def statusServer(status: Int, body: String): ZIO[Scope, Throwable, HttpServer] =
    ZIO.acquireRelease(
      ZIO.attempt {
        val s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext(
          "/",
          (exchange: HttpExchange) => {
            exchange.getRequestBody.readAllBytes()
            val out: Array[Byte] = body.getBytes("UTF-8")
            exchange.sendResponseHeaders(status, out.length.toLong)
            val os: OutputStream = exchange.getResponseBody
            os.write(out)
            os.close()
          },
        )
        s.start()
        s
      },
    )(s => ZIO.attempt(s.stop(0)).ignore)

  private def baseUrlOf(s: HttpServer): String = s"http://127.0.0.1:${s.getAddress.getPort}"

  // ── Prometheus scrape harness (#2042 pattern, as in RouterMetricsIngestSpec) ─
  private val pollInterval = 100.millis

  private val tickPublisher: UIO[Unit] =
    zio.test.TestClock.sleeps.repeatUntil(_.nonEmpty) *> zio.test.TestClock.adjust(pollInterval)

  private def scrape: ZIO[PrometheusPublisher, Response, String] =
    for {
      pub <- ZIO.service[PrometheusPublisher]
      routes = MetricsRoutes.routes(MetricsConfig(enabled = true), pub)
      resp <- routes(Request.get("/metrics"))
      body <- resp.body.asString.orElseFail(
        Response.internalServerError("scrape body decode failed"),
      )
    } yield body

  private def seriesLines(body: String, name: String): List[String] =
    body.linesIterator.filter(l => !l.startsWith("#") && l.startsWith(name)).toList

  /**
   * The current counter value of `name{outcome="error",reason=…}` (0 when the sample doesn't exist
   * yet). Prometheus counters are cumulative and this JVM's registry is shared across the whole
   * suite, so every assertion below is a BEFORE→AFTER **delta** — otherwise a later test could be
   * satisfied by an earlier test's increment and the suite would silently stop discriminating.
   */
  private def errorReasonValue(body: String, name: String, reason: String): Double =
    seriesLines(body, name)
      .find(l => l.contains("""outcome="error"""") && l.contains(s"""reason="$reason""""))
      .flatMap(_.split(' ').lift(1))
      .flatMap(v => scala.util.Try(v.toDouble).toOption)
      .getOrElse(0.0)

  /**
   * Drive `run`, and report how much each `reason` bucket of `name` moved. A correct classification
   * moves EXACTLY ONE bucket — so each test asserts both "+1 on the expected reason" and "+0 on the
   * other", which is what makes e.g. the 429 case a real pin rather than a coincidence.
   */
  private def deltas[R, E, A](name: String, run: ZIO[R, E, A]) =
    for {
      _      <- tickPublisher
      before <- scrape.catchAll(r => r.body.asString.orDie)
      out    <- run
      _      <- tickPublisher
      after  <- scrape.catchAll(r => r.body.asString.orDie)
    } yield (
      out,
      errorReasonValue(after, name, "config") - errorReasonValue(before, name, "config"),
      errorReasonValue(after, name, "transient") - errorReasonValue(before, name, "transient"),
    )

  // ── Support: LIVE dispatcher against the stub boundary ─────────────────────
  private def supportCfg(apiBase: String, dispatcher: String) = SupportConfig(
    responderEnabled = true,
    dispatcher = dispatcher,
    plain = PlainConfig(apiKey = "plain-api-key-test", webhookSecret = SupportWebhookSecret),
    anthropicApiKey = "sk-ant-test",
    anthropicApiBase = apiBase,
    claudeAgentId = "agent_test",
    claudeEnvironmentId = "env_test",
    claudeCodeRoutineId = "routine_test",
    claudeCodeRoutineToken = "sk-ant-oat01-test",
    agentTokenSecret = TokenSecret,
    deploymentEnv = "staging",
  )

  private def supportResponder(cfg: SupportConfig) =
    for {
      hhRepo      <- ZIO.service[HouseholdRepo]
      userRepo    <- ZIO.service[UserRepo]
      billRepo    <- ZIO.service[HouseholdBillingRepo]
      devRepo     <- ZIO.service[DeviceRepo]
      profRepo    <- ZIO.service[ProfileRepo]
      consentRepo <- ZIO.service[SupportConsentRepo]
      clock       <- ZIO.service[Clock]
      dispatcher = CloudAgentDispatcher.transportFor(cfg) match {
        case CloudAgentDispatcher.Transport.ClaudeCodeCloud =>
          new CloudAgentDispatcher.ClaudeCodeCloudLive(cfg)
        case _                                              => new CloudAgentDispatcher.Live(cfg)
      }
    } yield SupportResponder(
      cfg,
      hhRepo,
      userRepo,
      billRepo,
      devRepo,
      profRepo,
      consentRepo,
      PlainClient.noop,
      GithubIssueClient.noop,
      dispatcher,
      clock,
      RateLimiter.allowAll,
      RateLimiter.allowAll,
      RateLimiter.allowAll,
      RateLimiter.allowAll,
      RateLimiter.allowAll,
      RateLimiter.allowAll,
      "https://app.example.test",
    )

  // A UI-originated customer chat on an identified thread (the household resolves), so the responder
  // reaches the dispatch step — the only step #2416 changes.
  private def supportPayload(householdId: Long, threadId: String, text: String): String =
    s"""{"workspaceId":"w_1","id":"pEv_chat","payload":{"eventType":"thread.chat_received",""" +
      s""""chat":{"text":${text.toJson},"createdBy":{"actorType":"customer"}},""" +
      s""""thread":{"id":"$threadId","dataConsent":false,""" +
      s""""customer":{"id":"c_1","externalId":"$householdId"}}}}"""

  /** Drive one support webhook whose dispatch hits `status` at the Anthropic boundary. */
  private def driveSupport(
      status: Int,
      threadId: String,
      dispatcher: String = "managed-agents",
      respBody: String = """{"error":{"message":"nope"}}""",
  ) =
    ZIO.scoped {
      for {
        server <- statusServer(status, respBody)
        cfg = supportCfg(baseUrlOf(server), dispatcher)
        hhRepo <- ZIO.service[HouseholdRepo]
        hh     <- hhRepo.create(s"Family $threadId", s"family-$threadId")
        resp   <- supportResponder(cfg)
        body = supportPayload(hh.value, threadId, "My kid's iPad is blocked, help?")
        out <- resp.handleWebhook(
          body,
          Some(SupportService.hmacSha256Hex(SupportWebhookSecret, body)),
        )
      } yield out
    }

  // ── Press: LIVE dispatcher against the stub boundary ───────────────────────
  private def pressCfg(apiBase: String, dispatcher: String) = PressConfig(
    responderEnabled = true,
    dispatcher = dispatcher,
    webhookSecret = PressWebhookSecret,
    anthropicApiKey = "sk-ant-test",
    anthropicApiBase = apiBase,
    claudeAgentId = "agent_press_test",
    claudeEnvironmentId = "env_press_test",
    claudeCodeRoutineId = "routine_press_test",
    claudeCodeRoutineToken = "sk-ant-oat01-press",
    agentTokenSecret = TokenSecret,
    deploymentEnv = "staging",
    fromAddress = "press@staging.wifihaven.net",
  )

  private def pressResponder(cfg: PressConfig) =
    for {
      pressLog <- ZIO.service[PressMessageRepo]
      clock    <- ZIO.service[Clock]
      emailRef <- Ref.make(List.empty[EmailSender.Sent])
      dispatcher = PressAgentDispatcher.transportFor(cfg) match {
        case PressAgentDispatcher.Transport.ClaudeCodeCloud =>
          new PressAgentDispatcher.ClaudeCodeCloudLive(cfg)
        case _                                              => new PressAgentDispatcher.Live(cfg)
      }
    } yield PressResponder(
      cfg,
      EmailSender.recording(emailRef),
      dispatcher,
      pressLog,
      clock,
      RateLimiter.allowAll,
      RateLimiter.allowAll,
    )

  private def pressPayload(from: String, text: String): String =
    s"""{"from":${from.toJson},"subject":"Press inquiry","text":${text.toJson},"messageId":"<a@m>"}"""

  /** Drive one press inbound whose dispatch hits `status` at the Anthropic boundary. */
  private def drivePress(status: Int, from: String, dispatcher: String = "managed-agents") =
    ZIO.scoped {
      for {
        server <- statusServer(status, """{"error":{"message":"nope"}}""")
        cfg = pressCfg(baseUrlOf(server), dispatcher)
        resp <- pressResponder(cfg)
        body = pressPayload(from, "Requesting comment for a story")
        out <- resp.handleInbound(
          body,
          Some(SupportService.hmacSha256Hex(PressWebhookSecret, body)),
        )
      } yield out
    }

  def spec = suite("Cloud-agent dispatch fail-loud attribution (#2416)")(
    test("support: a 401 (revoked Anthropic key) attributes reason=config, not transient") {
      for {
        _   <- cleanDb
        res <- deltas("support_ai_draft_total", driveSupport(401, "th2416a"))
        (out, config, transient) = res
      } yield assertTrue(
        // Fail-open preserved: the webhook still resolves to a metered outcome, never a defect.
        SupportResponder.WebhookOutcome.label(out) == "error",
        config == 1.0,
        transient == 0.0,
      )
    },
    test("support: a 404 (wrong agent/environment id) also attributes reason=config") {
      for {
        _   <- cleanDb
        res <- deltas("support_ai_draft_total", driveSupport(404, "th2416b"))
        (out, config, transient) = res
      } yield assertTrue(
        SupportResponder.WebhookOutcome.label(out) == "error",
        config == 1.0,
        transient == 0.0,
      )
    },
    test("support: a 500 stays in the TRANSIENT bucket (reason=transient)") {
      for {
        _   <- cleanDb
        res <- deltas("support_ai_draft_total", driveSupport(500, "th2416c"))
        (out, config, transient) = res
      } yield assertTrue(
        SupportResponder.WebhookOutcome.label(out) == "error",
        transient == 1.0,
        config == 0.0,
      )
    },
    test(
      "support: a 400 on the claude-code-cloud routine (stale beta header) attributes reason=config",
    ) {
      for {
        _   <- cleanDb
        res <- deltas(
          "support_ai_draft_total",
          driveSupport(400, "th2416d", dispatcher = "claude-code-cloud"),
        )
        (out, config, transient) = res
      } yield assertTrue(
        SupportResponder.WebhookOutcome.label(out) == "error",
        config == 1.0,
        transient == 0.0,
      )
    },
    test("support: a 429 is TRANSIENT, not a config gap — a rate ceiling self-heals") {
      for {
        _   <- cleanDb
        res <- deltas("support_ai_draft_total", driveSupport(429, "th2416rl"))
        (out, config, transient) = res
      } yield assertTrue(
        SupportResponder.WebhookOutcome.label(out) == "error",
        // 408 and 429 are 4xx but load/timing signals — attributing them to `config` would page an
        // operator to "rotate the key" for a quota blip that fixes itself.
        transient == 1.0,
        config == 0.0,
      )
    },
    test("press: a 401 attributes reason=config on the SEPARATE press series") {
      for {
        _   <- cleanDb
        res <- deltas("press_ai_draft_total", drivePress(401, "reporter-a@example.com"))
        (out, config, transient) = res
      } yield assertTrue(
        PressResponder.WebhookOutcome.label(out) == "error",
        config == 1.0,
        transient == 0.0,
      )
    },
    test(
      "press: a 503 stays in the TRANSIENT bucket (the shared classifier agrees on both paths)",
    ) {
      for {
        _   <- cleanDb
        res <- deltas("press_ai_draft_total", drivePress(503, "reporter-b@example.com"))
        (out, config, transient) = res
      } yield assertTrue(
        PressResponder.WebhookOutcome.label(out) == "error",
        transient == 1.0,
        config == 0.0,
      )
    },
    test("press: a 404 on the claude-code-cloud routine attributes reason=config") {
      for {
        _   <- cleanDb
        res <- deltas(
          "press_ai_draft_total",
          drivePress(404, "reporter-c@example.com", dispatcher = "claude-code-cloud"),
        )
        (out, config, transient) = res
      } yield assertTrue(
        PressResponder.WebhookOutcome.label(out) == "error",
        config == 1.0,
        transient == 0.0,
      )
    },
    test("a SUCCESSFUL dispatch carries reason=none — the label is on every emitted sample") {
      for {
        _    <- cleanDb
        // 200 + a session id in the body ⇒ create-session and the kickoff event both succeed.
        out  <- driveSupport(200, "th2416ok", respBody = """{"id":"sess_2416"}""")
        _    <- tickPublisher
        body <- scrape.catchAll(r => r.body.asString.orDie)
        lines = seriesLines(body, "support_ai_draft_total")
      } yield assertTrue(
        SupportResponder.WebhookOutcome.label(out) == "dispatched",
        // No unattributed sample: `reason` is present on EVERY series sample, so a PromQL
        // `sum by (reason)` never silently drops one.
        lines.nonEmpty,
        lines.forall(_.contains("""reason=""")),
        lines.exists(l => l.contains("""outcome="dispatched"""") && l.contains("""reason="none"""")),
      )
    },
  ).provideSomeLayer[TestDatabase.AllRepos & EmbeddedPostgres & Clock](
    MetricsRuntime.prometheus(pollInterval),
  ) @@ TestAspect.sequential
}
