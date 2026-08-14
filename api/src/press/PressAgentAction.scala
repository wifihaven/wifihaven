package wifihaven.api.press

/**
 * #2517 — the PRESS cloud-agent callback vocabulary, in ONE place (the press twin of
 * [[wifihaven.api.support.AgentAction]]).
 *
 * These strings are load-bearing twice over: they are the `op` label on `press_agent_action_total`
 * (`AppMetrics.pressAgentAction`, via `PressResponder.withClaims`) AND the key that decides whether
 * a callback CLOSES an outstanding dispatch ([[Terminal]], read by `DispatchTracker.calledBack`).
 * Left as literals at the call sites, renaming one would compile cleanly and silently stop closing
 * dispatches — so the tracker would report a journalist who WAS answered as a dead session
 * (docs/process/single-source-of-truth.md — COLLAPSE, don't keep-in-sync). Support learned this the
 * same way in #2472.
 */
object PressAgentAction {

  /** Email the agent's copy to the token-bound journalist (#2203, destination-locked). */
  val Reply: String = "reply"

  /** Hand the inquiry to a human (#2437) — the operator notice. */
  val Escalate: String = "escalate"

  /**
   * The callbacks that CLOSE a dispatch: the ones that put something in front of the journalist or
   * in front of a human. [[Reply]] answers them; [[Escalate]] pages the operator, who then does.
   *
   * Unlike support this is the WHOLE vocabulary, not a subset — press has no consent request, no
   * household read, and no issue filing, because the press token carries no household and no data
   * scope by construction (`PressToken`). There is no press callback that proves a session is alive
   * without also serving the journalist, so nothing is excluded here; if one is ever added, it must
   * be weighed against "read something, then died", the failure the tracker exists to catch.
   */
  val Terminal: Set[String] = Set(Reply, Escalate)
}
