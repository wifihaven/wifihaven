package wifihaven.api.support

import zio.*
import zio.json.*

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.Duration as JDuration

/**
 * #2300 (support intake C follow-up, epic #2197) — the Claude Code Cloud transport: fire a
 * pre-provisioned **routine** on demand, one run per inbound support message. The alternative to
 * [[ManagedAgents]] behind the ONE [[CloudAgentObservabilityer]] trait, added because Claude Code
 * Cloud routines bill against the **Claude subscription** (Pro/Max/Team) rather than the
 * pay-as-you-go **API credits** the Managed Agents session consumes — the credits we can't get
 * provisioned.
 *
 * TRIGGER MODEL (spiked 2026-07-18, cited in the PR): Claude Code Cloud routines expose an
 * on-demand **API trigger** — `POST /v1/claude_code/routines/{routine_id}/fire` with the routine's
 * per-routine bearer token and a `{"text": …}` context field. So this stays a per-message PUSH (no
 * poller needed), exactly like the Managed Agents path — only the endpoint, auth scheme, and beta
 * header differ. Docs: https://code.claude.com/docs/en/routines.md#add-an-api-trigger and the API
 * spec at https://platform.claude.com/docs/en/api/claude-code/routines-fire.
 *
 * The routine is a pre-provisioned, versioned resource (created once in the Claude Code web UI; its
 * system prompt carries the same support-agent instructions as deploy/support-agent/, and its
 * environment's network allowlist must include `support.agentApiBase` so the run can reach our
 * `/api/support/agent/` callbacks). This code only ever FIRES it, never creates it.
 *
 * SECURITY: unchanged from [[ManagedAgents]]. The run receives ZERO vendor secrets. Its only
 * credential is the short-TTL, thread- + household-bound token carried in the fired `text`
 * (out-of-band — the customer never sees it); every side effect routes back through OUR
 * authenticated agent endpoints. The per-routine bearer token stays server-side here.
 *
 * One blocking HTTPS POST (fire) — the same JDK-HttpClient / `attemptBlocking` shape as
 * [[ManagedAgents]] / StripeClient / PlainClient, no new build dependency. Fire-and-forget: the run
 * executes autonomously in the cloud and reports back through our agent endpoints.
 */
object ClaudeCodeRoutines {

  private val UserAgent: String         = "wifihaven-api/1 (+https://wifihaven.net)"
  private val AnthropicVersion: String  = "2023-06-01"
  // The routines API is beta; the header is required (docs/routines-fire). Bump in lockstep with the
  // documented value — a stale beta header 400s at the boundary (surfaced as a metered Error).
  private val RoutineBeta: String       = "experimental-cc-routine-2026-04-01"
  private val ConnectTimeout: JDuration = JDuration.ofSeconds(10)
  private val RequestTimeout: JDuration = JDuration.ofSeconds(30)

  // The fire endpoint caps `text` at 65,536 chars (routines-fire spec). Our kickoff is tiny; guard
  // defensively so a pathological input can't 400 the whole dispatch — truncation degrades one
  // reply, a 400 loses it. Last-resort only: truncation drops the TAIL of the kickoff (the untrusted
  // customer text, and at worst its closing </customer_message> delimiter), never the head — the
  // SECURITY preamble + opening delimiter that establish injection containment are front-loaded by
  // CloudAgentObservabilityer.kickoffPrompt, so a cut tail loses content, not containment.
  private val MaxTextChars: Int = 65536

  private val sharedClient = HttpClient.newBuilder().connectTimeout(ConnectTimeout).build()

  /**
   * The fire URL for a routine — pure, unit-pinnable (the endpoint shape is the load-bearing bit).
   */
  def fireUrl(apiBase: String, routineId: String): String =
    s"${apiBase.stripSuffix("/")}/v1/claude_code/routines/$routineId/fire"

  /**
   * The fire request body — pure, unit-pinnable. `text` is the kickoff context, capped + JSON-safe.
   */
  def firePayload(text: String): String =
    FireBody(text.take(MaxTextChars)).toJson

  /**
   * Fire the routine with `text` as the run's context. A `Task` that fails on any transport /
   * non-2xx error (the caller maps that to a metered Error and never fails the webhook response).
   */
  def fireRoutine(
      apiBase: String,
      routineId: String,
      routineToken: String,
      text: String,
  ): Task[Unit] =
    ZIO
      .attemptBlocking {
        val httpReq = HttpRequest
          .newBuilder(URI.create(fireUrl(apiBase, routineId)))
          .header("Authorization", s"Bearer $routineToken")
          .header("anthropic-version", AnthropicVersion)
          .header("anthropic-beta", RoutineBeta)
          .header("content-type", "application/json")
          .header("User-Agent", UserAgent)
          .timeout(RequestTimeout)
          .POST(HttpRequest.BodyPublishers.ofString(firePayload(text)))
          .build()
        sharedClient.send(httpReq, HttpResponse.BodyHandlers.ofString())
      }
      .flatMap { resp =>
        if resp.statusCode() / 100 == 2 then ZIO.unit
        // #2416: fail with the STATUS as typed data (see ManagedAgents.post). A 4xx here is a
        // permanent misconfiguration — a revoked routine token (401), a wrong routine id (404), or a
        // stale `RoutineBeta` header (400) — and never self-heals on retry.
        else
          ZIO.fail(
            CloudAgentHttpError(resp.statusCode(), s"routines/$routineId/fire", resp.body()),
          )
      }

  private final case class FireBody(text: String)
  private given JsonEncoder[FireBody] = DeriveJsonEncoder.gen[FireBody]
}
