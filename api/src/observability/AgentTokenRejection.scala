package wifihaven.api.observability

import wifihaven.api.metrics.AppMetrics
import wifihaven.api.press.PressToken
import wifihaven.api.support.ConsentToken
import zio.*

/**
 * #2473 — the ONE place a rejected cloud-agent callback becomes observable, shared by the #2200
 * support responder and the #2203 press responder.
 *
 * WHY it exists: the agent token is the agent's only credential, and the reply callback is the ONLY
 * path a customer's answer travels back on. Before this, every rejection — expired token, forged
 * token, no token at all — collapsed into `{support,press}_agent_action_total{outcome="denied"}`
 * alongside ordinary denials, with no log line and no way to tell "someone probed the endpoint"
 * from "a real, dispatched answer was thrown away". That is exactly how #2473's lost reply stayed
 * invisible: the run resumed 2.5h after a 30-minute token was minted, got 401, and nothing surfaced
 * it. A dropped customer reply must never be silent (#2266 no-dark, #2416 fail-loud).
 *
 * Both channels keep emitting their existing `…_agent_action_total{op,outcome="denied"}` sample —
 * this series is ADDITIVE, so no dashboard or alert built on that one changes meaning.
 *
 * PII firewall: the log line carries only the bounded channel / op / reason. NEVER the token, the
 * thread id, the sender address, or the reply text.
 */
object AgentTokenRejection {

  /** Which responder rejected the callback. A fixed 2-value enum — the metric's `channel` label. */
  object Channel {
    val Support: String = "support"
    val Press: String   = "press"
  }

  /**
   * WHY the callback was rejected. A fixed 3-value enum — the metric's `reason` label, bounded by
   * construction (it is derived from the token verifier's own sealed `Err`, never from input).
   */
  object Reason {

    /**
     * The token was genuine and correctly signed but past its expiry — WE minted it, so this is
     * always a real dispatched answer that was thrown away. The #2473 signal: sustained non-zero
     * here means the TTL is again shorter than the transport's real round-trip latency.
     */
    val TokenExpired: String = "token_expired"

    /**
     * Malformed or wrongly-signed — not something we ever minted (a probe, or a secret rotation).
     */
    val Invalid: String = "invalid"

    /** The callback carried no bearer at all. */
    val Missing: String = "missing"
  }

  /**
   * The support token's rejection vocabulary. Kept HERE next to press's so the two mappings are
   * read side by side and cannot disagree about what counts as "expired".
   */
  def reasonFor(err: ConsentToken.Err): String = err match {
    case ConsentToken.Err.Expired                                   => Reason.TokenExpired
    case ConsentToken.Err.Malformed | ConsentToken.Err.BadSignature => Reason.Invalid
  }

  /** The press token's rejection vocabulary — same three cases, same mapping. */
  def reasonFor(err: PressToken.Err): String = err match {
    case PressToken.Err.Expired                                 => Reason.TokenExpired
    case PressToken.Err.Malformed | PressToken.Err.BadSignature => Reason.Invalid
  }

  /**
   * Log + meter one rejected agent callback.
   *
   * Level is deliberately split by who caused it. `token_expired` is OUR bug or misconfiguration —
   * a token we minted, for a dispatch we made, whose answer is now lost — so it is an ERROR an
   * operator should act on. `invalid` / `missing` are reachable by anyone on the internet (the
   * agent endpoints are public, authenticated only by the bearer), so they are WARN: real signal,
   * but not something an attacker can use to flood the error log.
   */
  def rejected(channel: String, op: String, reason: String): UIO[Unit] = {
    val line =
      s"$channel: agent callback REJECTED op=$op reason=$reason — the agent's answer was NOT delivered"
    val log  =
      if reason == Reason.TokenExpired then
        ZIO.logError(
          s"$line (the token outlived its TTL — a cloud-agent run paused longer than " +
            s"wifihaven.$channel.agentTokenTtlMinutes; see #2473)",
        )
      else ZIO.logWarning(line)
    log *> AppMetrics.agentTokenRejected(channel, op, reason)
  }
}
