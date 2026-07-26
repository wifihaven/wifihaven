package wifihaven.api.support

import zio.*

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
      e =>
        ZIO.logWarning(
          s"$audience agent dispatch errored transport=$transport$threadTag: ${e.getMessage}",
        ) *> record("error", Some(transport)).as(DispatchOutcome.Error),
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
