package wifihaven.api.support

/**
 * #2472 — the cloud-agent callback vocabulary, in ONE place.
 *
 * These strings are load-bearing twice over: they are the `op` label on
 * `support_agent_action_total` (`AppMetrics.supportAgentAction`, via
 * `SupportResponder.withClaimsE`) AND the key that decides whether a callback CLOSES an outstanding
 * dispatch ([[Terminal]], read by [[DispatchTracker.calledBack]]). Before this object they were
 * literals at the call sites plus a hand-copied `Set` in `DispatchTracker`, whose comment asserted
 * the two "cannot drift" — nothing enforced it. Renaming one literal compiled cleanly and would
 * have silently stopped closing dispatches, so the tracker would have reported a served customer as
 * a dead session (docs/process/single-source-of-truth.md — COLLAPSE, don't keep-in-sync).
 */
object AgentAction {

  /** Post the AI-attributed reply into the token-bound thread. */
  val Reply: String = "reply"

  /** Hand the thread to a human (#2437). */
  val Escalate: String = "escalate"

  /** Ask the customer for data-access consent (#2419) — the server posts the prompt. */
  val ConsentRequest: String = "consent_request"

  /** File a scrubbed GitHub issue (#2241). */
  val Issue: String = "issue"

  /** The consented single-household read. */
  val HouseholdRead: String = "household_read"

  /**
   * The callbacks that CLOSE a dispatch: the ones that put something in front of the customer or in
   * front of a human. [[Reply]] answers them; [[Escalate]] hands the thread to the operator;
   * [[ConsentRequest]] makes the server post a permission prompt into the thread.
   *
   * [[Issue]] and [[HouseholdRead]] are deliberately EXCLUDED: both prove the session is alive,
   * neither produces anything the customer sees — and "read the household, then died" is exactly
   * the failure [[DispatchTracker]] exists to catch.
   */
  val Terminal: Set[String] = Set(Reply, Escalate, ConsentRequest)
}
