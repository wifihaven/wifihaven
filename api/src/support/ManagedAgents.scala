package wifihaven.api.support

import zio.*
import zio.json.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration as JDuration

/**
 * Shared Anthropic Managed Agents transport — the "create a session + send one kickoff event"
 * plumbing common to the #2200 support responder and the #2203 press responder. Extracted so the
 * two audiences reuse ONE REST implementation (docs/process/single-source-of-truth.md): the
 * transport is audience-neutral; only the agent id / environment / kickoff text differ.
 *
 * Two blocking HTTPS POSTs (create session, send kickoff) — the same JDK-HttpClient /
 * `attemptBlocking` shape as StripeClient / PlainClient, no new build dependency. Fire-and-forget:
 * the agent runs autonomously in the cloud and reports back through OUR authenticated agent
 * endpoints; we do not hold an event stream open. The agent object itself is a pre-provisioned,
 * versioned resource (deploy/{support,press}-agent/agent.yaml) — this code only ever creates
 * sessions, never agents.
 *
 * SECURITY (both audiences): the agent receives ZERO vendor secrets. Its only credential is the
 * short-TTL, thread-bound token carried in the kickoff (out-of-band — the kickoff is the session's
 * user message, never sender-visible text). The Anthropic key stays server-side here.
 */
object ManagedAgents {

  private val UserAgent: String         = "wifihaven-api/1 (+https://wifihaven.net)"
  private val AnthropicVersion: String  = "2023-06-01"
  private val ManagedAgentsBeta: String = "managed-agents-2026-04-01"
  private val ConnectTimeout: JDuration = JDuration.ofSeconds(10)
  private val RequestTimeout: JDuration = JDuration.ofSeconds(30)

  private val sharedClient = HttpClient.newBuilder().connectTimeout(ConnectTimeout).build()

  /**
   * Neutralize a `<customer_message>` delimiter breakout: square-bracket both tag forms so
   * untrusted text embedded in a kickoff can never open or close its own data frame (#2261 review
   * finding). Shared by both responders' kickoff builders so the injection guard has ONE
   * definition.
   */
  def neutralizeTags(s: String): String =
    s.replace("</customer_message>", "[/customer_message]")
      .replace("<customer_message>", "[customer_message]")

  /**
   * Create a Managed Agents session against `agentId`/`environmentId` and send `kickoff` as the
   * first user message. A `Task` that fails on any transport / non-2xx error (the caller maps that
   * to a metered Error and never fails the webhook response).
   */
  def dispatchSession(
      anthropicApiBase: String,
      anthropicApiKey: String,
      agentId: String,
      environmentId: String,
      title: String,
      kickoff: String,
  ): Task[Unit] =
    for {
      sessionId <- createSession(anthropicApiBase, anthropicApiKey, agentId, environmentId, title)
      _         <- sendKickoff(anthropicApiBase, anthropicApiKey, sessionId, kickoff)
    } yield ()

  private def createSession(
      base: String,
      key: String,
      agentId: String,
      environmentId: String,
      title: String,
  ): Task[String] =
    post(
      base,
      key,
      "/v1/sessions",
      CreateSession(AgentRef("agent", agentId), environmentId, title).toJson,
    ).flatMap { body =>
      ZIO
        .fromOption(sessionIdOf(body))
        .orElseFail(new RuntimeException(s"no session id in response: ${body.take(200)}"))
    }

  private def sendKickoff(
      base: String,
      key: String,
      sessionId: String,
      kickoff: String,
  ): Task[Unit] =
    post(
      base,
      key,
      s"/v1/sessions/$sessionId/events",
      SendEvents(List(UserMessage("user.message", List(TextBlock("text", kickoff))))).toJson,
    ).unit

  private def post(base: String, key: String, path: String, payload: String): Task[String] =
    ZIO
      .attemptBlocking {
        val httpReq = HttpRequest
          .newBuilder(URI.create(s"$base$path"))
          .header("x-api-key", key)
          .header("anthropic-version", AnthropicVersion)
          .header("anthropic-beta", ManagedAgentsBeta)
          .header("content-type", "application/json")
          .header("User-Agent", UserAgent)
          .timeout(RequestTimeout)
          .POST(HttpRequest.BodyPublishers.ofString(payload))
          .build()
        sharedClient.send(httpReq, HttpResponse.BodyHandlers.ofString())
      }
      .flatMap { resp =>
        if resp.statusCode() / 100 == 2 then ZIO.succeed(resp.body())
        else
          ZIO.fail(
            new RuntimeException(s"HTTP ${resp.statusCode()} on $path: ${resp.body().take(300)}"),
          )
      }

  private def sessionIdOf(body: String): Option[String] =
    zio.json.ast.Json.decoder
      .decodeJson(body)
      .toOption
      .collect { case o: zio.json.ast.Json.Obj =>
        o.fields.collectFirst { case ("id", zio.json.ast.Json.Str(id)) => id }
      }
      .flatten

  // ── Managed Agents REST shapes (create session + kickoff event) ─────────────
  private final case class AgentRef(`type`: String, id: String)
  private final case class CreateSession(agent: AgentRef, environment_id: String, title: String)
  private final case class TextBlock(`type`: String, text: String)
  private final case class UserMessage(`type`: String, content: List[TextBlock])
  private final case class SendEvents(events: List[UserMessage])
  private given JsonEncoder[AgentRef]      = DeriveJsonEncoder.gen[AgentRef]
  private given JsonEncoder[CreateSession] = DeriveJsonEncoder.gen[CreateSession]
  private given JsonEncoder[TextBlock]     = DeriveJsonEncoder.gen[TextBlock]
  private given JsonEncoder[UserMessage]   = DeriveJsonEncoder.gen[UserMessage]
  private given JsonEncoder[SendEvents]    = DeriveJsonEncoder.gen[SendEvents]
}
