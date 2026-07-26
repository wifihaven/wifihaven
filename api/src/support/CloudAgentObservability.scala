package wifihaven.api.support

import zio.*

/**
 * #2416 — a non-2xx response from a cloud-agent boundary (Anthropic Managed Agents or Claude Code
 * Cloud routines), carrying the HTTP **status** as data rather than only inside a message string.
 *
 * The status is the load-bearing bit: it is what separates a PERMANENT misconfiguration (a revoked
 * key, a wrong agent/routine id, a stale beta header — a 4xx that no retry fixes) from a TRANSIENT
 * upstream hiccup (5xx, timeout, transport error, and the self-healing 4xx enumerated in
 * [[CloudAgentObservability.classify]]'s self-healing set). Before #2416 both boundaries failed
 * with a bare `RuntimeException` whose message embedded the status, so the dispatchers could only
 * substring-match to recover it — a brittle re-parse of text we produced ourselves. Callers
 * classify with [[CloudAgentObservability.classify]]; nobody should read the message to decide.
 */
final case class CloudAgentHttpError(status: Int, op: String, detail: String)
    extends RuntimeException(s"HTTP $status on $op: ${detail.take(300)}")

/**
 * #2438 — the shared fail-open + observability envelope for BOTH cloud-agent dispatchers (the #2200
 * support [[CloudAgentDispatcher]] and the #2203 press
 * [[wifihaven.api.press.PressAgentDispatcher]]). They are separate traits with separate config, but
 * the fail-open contract and the two live transports (Managed Agents / Claude Code Cloud) are
 * identical — so the outcome logging + metering live here, in ONE place, and neither audience nor
 * transport can drift.
 *
 * WHY it exists: during #2408 support go-live validation on staging, a message dispatched but we
 * could not tell from logs/metrics whether the cloud trigger actually fired — success was silent,
 * the failure WARN had no thread/transport tag, and there was no dispatcher-level metric (the count
 * only appeared conflated in the webhook-level `support_ai_draft_total`). A silent dispatch failure
 * was invisible. This envelope makes every dispatch attempt observable at the transport boundary.
 *
 * PII firewall (the #2408 lesson — log the real cause, never the payload): the ONLY things logged
 * are the bounded transport label, an optional non-PII thread handle (the Plain threadId — a thread
 * key, never customer content), and the transport error's own message. The kickoff text,
 * `customerMessage` / `pressMessage`, household name, and sender address are NEVER logged.
 */
object CloudAgentObservability {

  // The two bounded transport labels — the metric's `transport` dimension and the log tag.
  // Single-sourced here so the support + press call sites and the MetricGuard allowlist can't drift.
  val ManagedAgents: String   = "managed-agents"
  val ClaudeCodeCloud: String = "claude-code-cloud"

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
   * The 4xx statuses that DO self-heal, so they must NOT be attributed as a config gap. Sourced
   * from the HTTP semantics of the two statuses themselves (RFC 9110 §15.5.9 `408 Request Timeout`
   * — the server gave up waiting on the request; RFC 6585 §4 `429 Too Many Requests` — a rate/quota
   * ceiling): both are load/timing signals that the SAME request can succeed on later with no
   * config change. Every other 4xx from these APIs is a credential / resource-id / beta-header
   * rejection — none of which change by themselves.
   *
   * Kept deliberately minimal: a status earns a place here only if BOTH (a) retrying the identical
   * request can succeed without a config change, and (b) these two APIs can actually emit it over
   * our transport. `425 Too Early` passes (a) — RFC 8470 §5.2 makes it retryable on a fresh
   * connection — but fails (b): it is a TLS early-data status, and the JDK `HttpClient` calls here
   * never send early data, so listing it would be speculation rather than classification. If one of
   * these APIs is ever observed returning it, add it with the observation cited.
   */
  private val TransientClientStatuses: Set[Int] = Set(408, 429)

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
   * A candidate fix for a permanent 4xx, named inline in the log line so an operator reading the
   * error doesn't have to go derive it. Deliberately lists both transports' keys — one classifier
   * serves the Managed Agents and Claude Code Cloud paths for both audiences, and the log already
   * carries which audience it is.
   *
   * These are HINTS, hedged as such: the authoritative detail is the upstream response body, which
   * `dispatched` logs verbatim alongside this string. The hint must never read as a diagnosis the
   * body contradicts (a 400 can also be a quota/credit or oversized-request rejection, not a stale
   * header), so read the body first.
   */
  def provisioningHint(status: Int): String = status match {
    // The stale-beta-header guess for a 400 is the pre-existing suspicion recorded at
    // ClaudeCodeRoutines.RoutineBeta ("a stale beta header 400s at the boundary") — a repo comment,
    // not a vendor-documented error contract, so it is offered as one candidate among others rather
    // than asserted. It applies to ManagedAgents.ManagedAgentsBeta by symmetry (same required
    // `anthropic-beta` header mechanism), not from separate evidence.
    case 400       =>
      " — check the response body above first; if it does not name a cause, a candidate is a STALE " +
        "hard-coded anthropic-beta header (ManagedAgents.ManagedAgentsBeta / " +
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
      " — the agent boundary rejected the request and will keep doing so until something changes; " +
        "read the response body above for the cause (it may be config — keys, ids, beta header — " +
        "or a request-level rejection such as quota/credit or size)"
  }

  /**
   * Wrap a fire-and-forget transport `run`: a completed run is [[DispatchOutcome.Dispatched]] (INFO
   * + `record("dispatched", Some(transport))`); a thrown error is [[DispatchOutcome.Error]]
   * (enriched WARN + `record("error", Some(transport))`). Never fails — a cloud hiccup must not
   * fail the webhook response (Plain / the Email Worker would retry-storm a 5xx). ONE definition so
   * the two audiences AND the two transports can't drift on the fail-open + observability contract.
   *
   * @param audience
   *   "support" | "press" — the log wording only.
   * @param transport
   *   the bounded transport label ([[ManagedAgents]] | [[ClaudeCodeCloud]]).
   * @param threadRef
   *   a non-PII thread handle to tag the log with (the support threadId); `None` for press, which
   *   carries no non-PII id at dispatch (from/subject are PII).
   * @param record
   *   the audience's dispatch metric — (outcome, Some(transport)) => emit.
   */
  def dispatched(
      audience: String,
      transport: String,
      threadRef: Option[String],
      record: (String, Option[String]) => UIO[Unit],
      run: Task[Unit],
  ): UIO[DispatchOutcome] = {
    val threadTag = threadRef.map(t => s" thread=$t").getOrElse("")
    run.foldZIO(
      // #2416: a failure is not one thing. A 4xx at the boundary is a PERMANENT misconfiguration
      // that no retry fixes, so it is LOUD (logError, with the likely fix named) and reported as
      // ConfigError; everything else keeps the quiet WARN. Both still record `outcome="error"` —
      // the dispatcher-level series stays a 3-value enum (#2438) and the two failure kinds are
      // told apart by the bounded `reason` label on the webhook series.
      e =>
        classify(e) match {
          case kind @ FailureKind.Permanent =>
            val status = e match {
              case h: CloudAgentHttpError => h.status
              case _                      => 0
            }
            ZIO.logError(
              s"$audience agent dispatch FAILED PERMANENTLY transport=$transport$threadTag " +
                s"[reason=${reasonFor(kind)}]: ${e.getMessage}${provisioningHint(status)}",
            ) *> record("error", Some(transport)).as(DispatchOutcome.ConfigError)
          case kind @ FailureKind.Transient =>
            ZIO.logWarning(
              s"$audience agent dispatch errored transport=$transport$threadTag " +
                s"[reason=${reasonFor(kind)}]: ${e.getMessage}",
            ) *> record("error", Some(transport)).as(DispatchOutcome.Error)
        },
      _ =>
        ZIO.logInfo(
          s"$audience agent dispatched transport=$transport$threadTag",
        ) *> record("dispatched", Some(transport)).as(DispatchOutcome.Dispatched),
    )
  }

  /**
   * The disabled no-op outcome, metered so a per-message dispatch attempt while the responder is
   * explicitly off (#2265) is visible at the dispatcher level too (not only at the webhook). No
   * transport was selected, so the metric carries no `transport` label.
   */
  def disabled(record: (String, Option[String]) => UIO[Unit]): UIO[DispatchOutcome] =
    record("disabled", None).as(DispatchOutcome.Disabled)
}
