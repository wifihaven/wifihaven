package wifihaven.api.support

import zio.*

/**
 * #2416 — a non-2xx response from a cloud-agent boundary (Anthropic Managed Agents or Claude Code
 * Cloud routines), carrying the HTTP **status** as data rather than only inside a message string.
 *
 * The status is the load-bearing bit: it is what separates a PERMANENT misconfiguration (a revoked
 * key, a wrong agent/routine id, a stale beta header — 4xx, never self-heals) from a TRANSIENT
 * upstream hiccup (5xx, timeout, transport error). Before #2416 both boundaries failed with a bare
 * `RuntimeException` whose message embedded the status, so the dispatchers could only
 * substring-match to recover it — a brittle re-parse of text we produced ourselves. Callers
 * classify with [[CloudAgentDispatch.classify]]; nobody should read the message to decide.
 */
final case class CloudAgentHttpError(status: Int, op: String, detail: String)
    extends RuntimeException(s"HTTP $status on $op: ${detail.take(300)}")

/**
 * #2416 — the ONE shared fail-open envelope + 4xx-vs-transient classifier for cloud-agent dispatch,
 * used by BOTH [[CloudAgentDispatcher]] (support, #2200) and
 * [[wifihaven.api.press.PressAgentDispatcher]] (press, #2203).
 *
 * WHY shared: the two dispatchers are parallel paths over the same two transports
 * ([[ManagedAgents]], [[ClaudeCodeRoutines]]) and both already stated the intent that the fail-open
 * contract has ONE definition. A hand-copied classifier is exactly the duplicated-decision the
 * single-source-of-truth rule forbids (docs/process/single-source-of-truth.md): the two audiences
 * would drift the moment one learns about a new permanent status.
 *
 * WHAT it fixes (docs/process/no-dark-by-default.md): a **permanently dead responder** used to read
 * as intermittent noise. A revoked `anthropicApiKey` (401), a wrong `claudeAgentId` /
 * `claudeEnvironmentId` / `claudeCodeRoutineId` (404), and a stale hard-coded `anthropic-beta`
 * header (400) all collapsed into a single `logWarning` + one flat
 * `{support,press}_ai_draft_total{outcome=error}` bucket — indistinguishable from a genuine
 * Anthropic 5xx that self-heals on the next message. Now a 4xx is LOUD (`logError` naming the
 * likely fix inline) and attributed on a bounded `reason` label, while transport/5xx keeps the
 * quiet `logWarning`.
 *
 * NOT changed: the fail-open degradation itself. Dispatch failures still never fail the inbound
 * webhook response (Plain / the Cloudflare Worker would retry-storm a 5xx) — #2416 is observability
 * and attribution only, exactly as #2410 kept the Plain customer-upsert outcome intact.
 */
object CloudAgentDispatch {

  /**
   * The bounded `reason` label on `support_ai_draft_total` / `press_ai_draft_total` — WHY a
   * dispatch failed, so an operator can tell a PROVISIONING GAP (needs a human fix) from a blip.
   * Enum-bounded by construction; never a per-thread / per-household / per-sender value (the §4
   * cardinality firewall).
   */
  object Reason {

    /** No dispatch failure to attribute (dispatched, disabled, rate-limited, rejected, …). */
    val None: String = "none"

    /** A 4xx from the agent boundary: bad key / wrong agent-or-routine id / stale beta header. */
    val Config: String = "config"

    /** Transport error, timeout, 5xx, or a malformed-success response — may self-heal. */
    val Transient: String = "transient"
  }

  /** Whether a dispatch failure will EVER succeed on retry without a human changing something. */
  enum FailureKind {
    case Permanent
    case Transient
  }

  /**
   * The 4xx statuses that DO self-heal, so they must NOT be attributed as a config gap: 408 Request
   * Timeout, 425 Too Early, and 429 Too Many Requests are load/timing signals, not a wrong key.
   * Every other 4xx from these APIs is a credential / resource-id / beta-header rejection — none of
   * which change by themselves.
   */
  private val TransientClientStatuses: Set[Int] = Set(408, 425, 429)

  /**
   * Classify a dispatch failure. A 4xx from either agent boundary is PERMANENT except the
   * self-healing statuses above. Everything else (transport, timeout, 5xx, a 2xx whose body lacked
   * a session id) is TRANSIENT. Reads the typed [[CloudAgentHttpError.status]], never the message
   * text.
   */
  def classify(e: Throwable): FailureKind = e match {
    case h: CloudAgentHttpError
        if h.status >= 400 && h.status < 500 && !TransientClientStatuses.contains(h.status) =>
      FailureKind.Permanent
    case _ => FailureKind.Transient
  }

  /** The `reason` label for a classified failure. */
  def reasonFor(kind: FailureKind): String = kind match {
    case FailureKind.Permanent => Reason.Config
    case FailureKind.Transient => Reason.Transient
  }

  /**
   * The likely FIX for a permanent 4xx, named inline in the log line so an operator reading the
   * error doesn't have to go derive it. Deliberately lists both transports' keys — one classifier
   * serves the Managed Agents and Claude Code Cloud paths for both audiences, and the log already
   * carries which audience it is.
   */
  def provisioningHint(status: Int): String = status match {
    case 400       =>
      " — PROVISIONING GAP: the request was rejected at the boundary; the most likely cause is a " +
        "STALE hard-coded anthropic-beta header (ManagedAgents.ManagedAgentsBeta / " +
        "ClaudeCodeRoutines.RoutineBeta) — bump it to the currently documented value"
    case 401 | 403 =>
      " — PROVISIONING GAP: the credential is revoked, wrong, or under-scoped; rotate it " +
        "({support,press}.anthropicApiKey for managed-agents, " +
        "{support,press}.claudeCodeRoutineToken for claude-code-cloud)"
    case 404       =>
      " — PROVISIONING GAP: the referenced cloud resource does not exist; fix the id " +
        "({support,press}.claudeAgentId / .claudeEnvironmentId for managed-agents, " +
        "{support,press}.claudeCodeRoutineId for claude-code-cloud)"
    case _         =>
      " — PROVISIONING GAP: the agent boundary rejected the request as invalid; check the " +
        "dispatcher config (keys, ids, beta header) — a 4xx never self-heals"
  }

  /**
   * The fail-open envelope shared by every live transport of both audiences: a completed
   * fire-and-forget `run` is [[DispatchOutcome.Dispatched]]; a failure is logged and collapsed to
   * [[DispatchOutcome.ConfigError]] (4xx — LOUD, `logError` with the fix named) or
   * [[DispatchOutcome.Error]] (transient — quiet `logWarning`). Never fails, so a cloud hiccup
   * can't fail the webhook response.
   *
   * `audience` is a fixed literal ("support" / "press") supplied by the caller — it only prefixes
   * the log line and never reaches a metric label.
   */
  def dispatched(audience: String, run: Task[Unit]): UIO[DispatchOutcome] =
    run
      .as(DispatchOutcome.Dispatched)
      .catchAll(e =>
        classify(e) match {
          case FailureKind.Permanent =>
            val status = e match {
              case h: CloudAgentHttpError => h.status
              case _                      => 0
            }
            ZIO
              .logError(
                s"$audience agent dispatch FAILED PERMANENTLY [reason=${Reason.Config}]: " +
                  s"${e.getMessage}${provisioningHint(status)}",
              )
              .as(DispatchOutcome.ConfigError)
          case FailureKind.Transient =>
            ZIO
              .logWarning(
                s"$audience agent dispatch errored [reason=${Reason.Transient}]: ${e.getMessage}",
              )
              .as(DispatchOutcome.Error)
        },
      )
}
