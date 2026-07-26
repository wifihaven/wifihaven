package wifihaven.api.feature

import wifihaven.api.{PressConfig, SupportConfig}
import wifihaven.api.press.{PressAgentDispatcher, PressDispatch}
import wifihaven.api.support.{AgentDispatch, CloudAgentDispatcher, DispatchOutcome}
import com.sun.net.httpserver.{HttpExchange, HttpServer}
import zio.*
import zio.metrics.*
import zio.test.*

import java.io.OutputStream
import java.net.InetSocketAddress

/**
 * #2438 — dispatcher-level observability for the #2200 support and #2203 press cloud-agent
 * dispatchers. During #2408 support go-live validation a message dispatched but we could NOT tell
 * from logs/metrics whether the cloud trigger actually fired: success was silent, the failure WARN
 * carried no thread/transport tag, and there was no dispatcher-level metric (only the conflated
 * webhook-level `support_ai_draft_total`). This spec pins the fix at the shared fail-open envelope
 * so a silent dispatch failure can never again be invisible.
 *
 * The dispatcher makes only outbound HTTPS calls (no DB, no clock), so a JDK HttpServer stands in
 * for the Anthropic trigger boundary — the same shape SupportResponderSpec uses for Plain. Each
 * transport is exercised against a real 2xx / non-2xx boundary; metrics are read straight off the
 * global registry (like MetricCardinalityGuardSpec), logs via ZTestLogger.
 *
 * The load-bearing pins:
 *   - a non-2xx trigger response classifies the dispatch as `Error` (both transports) — the
 *     fail-open contract on which the metric depends;
 *   - a SUCCESS emits an INFO tagged with transport + thread, and increments
 *     `support_dispatch_total{outcome="dispatched",transport}`;
 *   - a FAILURE emits a WARN tagged with transport + thread + the real HTTP cause, and increments
 *     `support_dispatch_total{outcome="error",transport}`;
 *   - NO PII: neither the INFO nor the WARN carries the untrusted customer / press message text;
 *   - the disabled no-op increments `support_dispatch_total{outcome="disabled"}` (no transport);
 *   - the press dispatcher is covered by the SAME shared envelope, emitting the independent
 *     `press_dispatch_total` series (and its WARN carries no sender / message PII).
 */
object CloudAgentDispatchObservabilitySpec extends ZIOSpecDefault {

  // A distinctive untrusted payload that must NEVER appear in any log line.
  private val SecretMsg = "SECRET-CUSTOMER-PAYLOAD-do-not-log-1234567890"

  /** A scoped fake Anthropic boundary: every request gets `status` + `body`. */
  private def stubServer(status: Int, body: String): ZIO[Scope, Throwable, HttpServer] =
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

  private def baseOf(s: HttpServer): String =
    s"http://127.0.0.1:${s.getAddress.getPort}"

  // A support config whose Managed Agents / Claude Code Cloud trigger points at the fake boundary.
  private def supportCfg(base: String, dispatcher: String = "managed-agents"): SupportConfig =
    SupportConfig(
      responderEnabled = true,
      dispatcher = dispatcher,
      anthropicApiKey = "sk-ant-test",
      claudeAgentId = "agent_test",
      claudeEnvironmentId = "env_test",
      anthropicApiBase = base,
      claudeCodeRoutineId = "routine_test",
      claudeCodeRoutineToken = "sk-ant-oat01-test",
      agentTokenSecret = "agent-token-secret-0123456789abcdef",
      deploymentEnv = "staging",
    )

  private def pressCfg(base: String, dispatcher: String = "managed-agents"): PressConfig =
    PressConfig(
      responderEnabled = true,
      dispatcher = dispatcher,
      webhookSecret = "press-webhook-secret",
      anthropicApiKey = "sk-ant-test",
      claudeAgentId = "agent_press",
      claudeEnvironmentId = "env_press",
      anthropicApiBase = base,
      claudeCodeRoutineId = "routine_press",
      claudeCodeRoutineToken = "sk-ant-oat01-press",
      agentTokenSecret = "press-token-secret-0123456789abcdef",
      deploymentEnv = "staging",
      fromAddress = "press@staging.wifihaven.net",
    )

  private def supportReq(thread: String): AgentDispatch =
    AgentDispatch(
      threadId = thread,
      householdName = "Family Obs",
      plan = Some("beta"),
      dataConsent = false,
      agentToken = "tok_obs",
      customerMessage = SecretMsg,
    )

  private def pressReq: PressDispatch =
    PressDispatch(
      from = "reporter@press.example",
      subject = "story",
      agentToken = "ptok_obs",
      pressMessage = SecretMsg,
    )

  // A 2xx body carrying a session id so ManagedAgents.createSession can parse it (the events POST
  // body is ignored). The Claude Code Cloud fire path ignores the body entirely.
  private val OkBody = """{"id":"sess_obs"}"""

  private def counterValue(name: String, labels: (String, String)*): UIO[Double] =
    labels
      .foldLeft(Metric.counter(name))((m, kv) => m.tagged(kv._1, kv._2))
      .value
      .map(_.count)

  def spec = suite("CloudAgentDispatcher observability (#2438)")(
    test("a non-2xx trigger response classifies the dispatch as Error (managed-agents)") {
      ZIO.scoped {
        for {
          server <- stubServer(500, "boom")
          out    <- new CloudAgentDispatcher.Live(supportCfg(baseOf(server)))
            .dispatch(supportReq("th_err_ma"))
        } yield assertTrue(out == DispatchOutcome.Error)
      }
    },
    test("a non-2xx trigger response classifies the dispatch as Error (claude-code-cloud)") {
      ZIO.scoped {
        for {
          server <- stubServer(500, "boom")
          out    <- new CloudAgentDispatcher.ClaudeCodeCloudLive(
            supportCfg(baseOf(server), "claude-code-cloud"),
          ).dispatch(supportReq("th_err_ccc"))
        } yield assertTrue(out == DispatchOutcome.Error)
      }
    },
    test("a successful dispatch logs an INFO tagged transport + thread, with NO customer text") {
      ZIO
        .scoped {
          for {
            server <- stubServer(200, OkBody)
            out    <- new CloudAgentDispatcher.Live(supportCfg(baseOf(server)))
              .dispatch(supportReq("th_ok_ma"))
            logs   <- ZTestLogger.logOutput
          } yield {
            val infos = logs.filter(_.logLevel == LogLevel.Info).map(_.message())
            assertTrue(
              out == DispatchOutcome.Dispatched,
              infos.exists(m =>
                m.contains("support agent dispatched") &&
                  m.contains("transport=managed-agents") &&
                  m.contains("thread=th_ok_ma"),
              ),
              // PII firewall: the untrusted customer text is never logged.
              logs.forall(e => !e.message().contains(SecretMsg)),
            )
          }
        }
        .provide(ZTestLogger.default)
    },
    test("a failed dispatch enriches the WARN with transport + thread + the real cause, no PII") {
      ZIO
        .scoped {
          for {
            server <- stubServer(503, "unavailable")
            out    <- new CloudAgentDispatcher.Live(supportCfg(baseOf(server)))
              .dispatch(supportReq("th_warn_ma"))
            logs   <- ZTestLogger.logOutput
          } yield {
            val warns = logs.filter(_.logLevel == LogLevel.Warning).map(_.message())
            assertTrue(
              out == DispatchOutcome.Error,
              warns.exists(m =>
                m.contains("support agent dispatch errored") &&
                  m.contains("transport=managed-agents") &&
                  m.contains("thread=th_warn_ma") &&
                  m.contains("503"),
              ),
              logs.forall(e => !e.message().contains(SecretMsg)),
            )
          }
        }
        .provide(ZTestLogger.default)
    },
    test("a successful dispatch increments support_dispatch_total{dispatched,managed-agents}") {
      ZIO.scoped {
        for {
          server <- stubServer(200, OkBody)
          before <- counterValue(
            "support_dispatch_total",
            "outcome"   -> "dispatched",
            "transport" -> "managed-agents",
          )
          _      <- new CloudAgentDispatcher.Live(supportCfg(baseOf(server)))
            .dispatch(supportReq("th_m1"))
          after  <- counterValue(
            "support_dispatch_total",
            "outcome"   -> "dispatched",
            "transport" -> "managed-agents",
          )
        } yield assertTrue(after - before >= 1.0)
      }
    },
    test("a failed dispatch increments support_dispatch_total{error,claude-code-cloud}") {
      ZIO.scoped {
        for {
          server <- stubServer(500, "boom")
          before <- counterValue(
            "support_dispatch_total",
            "outcome"   -> "error",
            "transport" -> "claude-code-cloud",
          )
          _      <- new CloudAgentDispatcher.ClaudeCodeCloudLive(
            supportCfg(baseOf(server), "claude-code-cloud"),
          ).dispatch(supportReq("th_m2"))
          after  <- counterValue(
            "support_dispatch_total",
            "outcome"   -> "error",
            "transport" -> "claude-code-cloud",
          )
        } yield assertTrue(after - before >= 1.0)
      }
    },
    test("the disabled no-op increments support_dispatch_total{disabled} (no transport label)") {
      for {
        before <- counterValue("support_dispatch_total", "outcome" -> "disabled")
        out    <- CloudAgentDispatcher.Disabled.dispatch(supportReq("th_dis"))
        after  <- counterValue("support_dispatch_total", "outcome" -> "disabled")
      } yield assertTrue(out == DispatchOutcome.Disabled, after - before >= 1.0)
    },
    test("the press dispatcher shares the envelope: error logs + press_dispatch_total, no PII") {
      ZIO
        .scoped {
          for {
            server <- stubServer(500, "boom")
            before <- counterValue(
              "press_dispatch_total",
              "outcome"   -> "error",
              "transport" -> "managed-agents",
            )
            out    <- new PressAgentDispatcher.Live(pressCfg(baseOf(server))).dispatch(pressReq)
            after  <- counterValue(
              "press_dispatch_total",
              "outcome"   -> "error",
              "transport" -> "managed-agents",
            )
            logs   <- ZTestLogger.logOutput
          } yield {
            val warns = logs.filter(_.logLevel == LogLevel.Warning).map(_.message())
            assertTrue(
              out == DispatchOutcome.Error,
              after - before >= 1.0,
              warns.exists(m =>
                m.contains("press agent dispatch errored") && m.contains(
                  "transport=managed-agents",
                ),
              ),
              // No sender address, no message body in any log line.
              logs.forall(e =>
                !e.message().contains(SecretMsg) && !e.message().contains("reporter@press.example"),
              ),
            )
          }
        }
        .provide(ZTestLogger.default)
    },
  ) @@ TestAspect.withLiveClock @@ TestAspect.sequential
}
