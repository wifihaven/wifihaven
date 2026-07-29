package wifihaven.api.support

import wifihaven.api.metrics.AppMetrics
import zio.*

/**
 * #2469 — is the cloud agent that just called us running THIS repo's prompt?
 *
 * `deploy/{support,press}-agent/agent.yaml` is the source of truth for two transports. The
 * Anthropic Managed Agents copy re-applies itself on every main-merge
 * (.github/workflows/master-{support,press}-agent.yml), but the Claude Code Cloud ROUTINE copy does
 * NOT: routine CRUD is web-UI-only. So merging a prompt change leaves the live routine on the OLD
 * prompt — and a stale routine reports a GREEN run while behaving from it. #2419/#2425 (consent)
 * and #2430/#2441 (thread history) both shipped un-re-pasted, and the earlier missing
 * `<routine-fire-payload>` opt-in made a routine post nothing while reporting success. Same class
 * as no-dark-by-default: a change that silently doesn't take effect.
 *
 * We cannot READ the live prompt (no routine API), so the mechanism is inverted: the prompt carries
 * a `PROMPT_VERSION:` marker, the agent echoes it on its reply callback as the DEDICATED
 * `promptVersion` field, and we compare that against [[Channel.expected]] here.
 *
 * Two properties this must never lose:
 *
 *   - **Non-fatal.** [[observe]] is a `UIO` — there is no error channel to propagate into the reply
 *     path, whatever the agent reports. (Callers sequence it just BEFORE the send, so an
 *     authenticated callback records even when the send itself then fails; that ordering is about
 *     coverage, and the `UIO` is what makes it safe.) A drifted routine is an operator problem; it
 *     must never fail an inbound webhook or cost a customer their answer.
 *   - **Instruction-zone only.** The version arrives on its own JSON field, sourced from the
 *     prompt. Untrusted text — the customer's message, the thread history, or a reply body the
 *     agent was talked into writing — is never parsed for a version, so an injected
 *     `PROMPT_VERSION:` line cannot make a stale routine report itself current (pinned in
 *     SupportResponderSpec / PressResponderSpec).
 *
 * The two halves of the comparison sit in different files (the yaml prompt, the constant below)
 * because nothing compiles the yaml into Scala. `AgentPromptVersionSpec` pins them equal, and
 * `.github/scripts/check-agent-prompt-repaste.sh` fails a `system:`-block change that forgot to
 * bump the marker — an un-bumped version is the one way this detector goes blind.
 */
object AgentPromptVersion {

  /**
   * A dispatched-agent audience. `expected` MUST equal the `PROMPT_VERSION:` marker in that
   * channel's `deploy/<channel>-agent/agent.yaml` — bump the two together, in the same commit.
   */
  enum Channel(val wire: String, val expected: String) {
    case Support extends Channel("support", "support-2026-07-28.1")
    case Press   extends Channel("press", "press-2026-07-28.1")
  }

  /**
   * The bounded metric label. `Unknown` (absent field) is deliberately NOT lumped in with stale: an
   * old routine that predates the marker and a routine that dropped the field are the same
   * observation, and neither is a confirmation of anything.
   */
  enum State(val wire: String) {
    case Current extends State("current")
    case Stale   extends State("stale")
    case Unknown extends State("unknown")
  }

  /**
   * Longest reported version we will echo into a log line. A real marker is ~20 chars
   * (`support-2026-07-28.1`); this is a generous ceiling, not a format rule — the value is
   * agent-supplied, so it is bounded rather than trusted.
   */
  val MaxLoggedVersion = 64

  /**
   * Everything a legitimate marker is made of. Anything else — control characters above all — is
   * replaced before the value reaches a log line.
   */
  private val UnsafeForLog = "[^A-Za-z0-9._:+-]".r

  /**
   * Render an agent-reported version safe to interpolate into a log line: bounded in length and
   * stripped of anything outside the marker alphabet. A newline here would be LOG FORGING — the
   * reported value is attacker-influenceable (a hijacked agent, or a caller who guessed the field),
   * and a `\n` would let it synthesize a whole extra line in the Loki stream we alert on.
   */
  def logSafeVersion(reported: String): String =
    UnsafeForLog.replaceAllIn(reported.trim.take(MaxLoggedVersion), "?")

  def classify(channel: Channel, reported: Option[String]): State =
    reported.map(_.trim).filter(_.nonEmpty) match {
      case None                             => State.Unknown
      case Some(v) if v == channel.expected => State.Current
      case Some(_)                          => State.Stale
    }

  /**
   * Record what the live agent reported. Match → one `current` sample and nothing else; mismatch or
   * absence → a loud log plus the `stale` / `unknown` sample. `current` is emitted too, on purpose:
   * without the positive series a silent dashboard is indistinguishable from a dead callback path.
   */
  def observe(channel: Channel, reported: Option[String]): UIO[Unit] = {
    val state = classify(channel, reported)
    val alert = state match {
      case State.Current => ZIO.unit
      case State.Stale   =>
        ZIO.logError(
          s"cloud-agent prompt DRIFT (${channel.wire}): the live agent reports prompt version " +
            s"'${reported.fold("")(logSafeVersion)}' but this build expects " +
            s"'${channel.expected}'. The Claude Code Cloud routine prompt is web-UI-only — re-paste " +
            s"deploy/${channel.wire}-agent/agent.yaml's system: block at https://claude.ai/code/routines (#2469).",
        )
      case State.Unknown =>
        ZIO.logError(
          s"cloud-agent prompt version UNKNOWN (${channel.wire}): the live agent reported no " +
            s"promptVersion on its reply callback, so we cannot confirm it is running this build's " +
            s"prompt ('${channel.expected}'). Most likely a routine still on a pre-#2469 prompt — " +
            s"re-paste deploy/${channel.wire}-agent/agent.yaml's system: block at https://claude.ai/code/routines.",
        )
    }
    alert *> AppMetrics.agentPromptVersion(channel.wire, state.wire)
  }
}
