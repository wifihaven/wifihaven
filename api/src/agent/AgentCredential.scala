package wifihaven.api.agent

import wifihaven.api.metrics.AppMetrics
import zio.*

import scala.util.matching.Regex

/**
 * #2508 — the structural guard against a cloud agent leaking its OWN bearer credential into the
 * text it sends out.
 *
 * WHY it exists. A dispatched support (#2200) or press (#2203) agent holds one credential — the
 * signed session token — for the life of its run, and its reply text is the ONE thing it fully
 * authors. Every other guarantee in both trust models holds structurally rather than by prompt: the
 * reply destination and the household come from the SIGNED token, issue bodies are PII-scrubbed at
 * the [[wifihaven.api.support.GithubIssueClient]] boundary. Credential disclosure was the one leg
 * still resting on the prompt. The #2335 / #2336 hand validations reported "zero token-shaped
 * strings" in the outbound rows, which is a real observation about model behaviour under one attack
 * — not a control. This is the control.
 *
 * WHERE THE SEAM SITS, and why not at the transport. The obvious mirror of `scrubForIssue` would be
 * to redact inside [[wifihaven.api.notify.EmailSender]] / [[wifihaven.api.support.PlainClient]].
 * That is the wrong invariant:
 *
 *   - `EmailSender` is the SHARED #578 transport. It legitimately carries server-minted secrets —
 *     the password-reset link (`PasswordResetService`) and the beta invite token — so a
 *     transport-level "never email a credential" rule is simply false, and any rule narrow enough
 *     to be true there would have to know which caller it was serving. What IS true is narrower and
 *     specific: *agent-authored* text must never carry the agent's credential.
 *   - So the seam is the agent-facing callback surface itself — the same boundary that verifies the
 *     token. Every path by which agent-authored bytes leave this process goes through one of the
 *     handful of `agent*` methods on [[wifihaven.api.support.SupportResponder]] /
 *     [[wifihaven.api.press.PressResponder]], and each redacts as its FIRST act, so the redacted
 *     text is the only text the rest of the method can reach (the reply body, the `/press`
 *     correspondence-log row, the GitHub issue, and the operator escalation note all share it).
 *
 * BOTH channels get the credential rules; they deliberately do NOT share
 * [[wifihaven.api.support.SupportPrivacy.scrubForIssue]]'s PII rules. A support reply legitimately
 * quotes household data back to the household that consented to the read (#2419) — that is the
 * whole point of the consent flow, and scrubbing it would break the product. A press reply must
 * never contain household data at all, but it has no path to any: the press token carries no
 * household and there is no data endpoint on the press side, so the absence is already structural
 * and a PII scrub there would only mangle a journalist's own quoted numbers.
 *
 * PRECISION OVER BREADTH. The grammars below are specific enough that ordinary prose survives
 * byte-identical: a UUID, a `v1.2.3` version string, a base64-ish word, or the word "Bearer" used
 * in a sentence are all left alone (pinned in `ReplyRedactionSpec`). A redactor that mangled real
 * answers would be worse than none — support would route around it.
 */
object AgentCredential {

  /**
   * The version prefix BOTH agent-token grammars mint with. Single-sourced HERE and referenced by
   * [[wifihaven.api.support.ConsentToken]] and [[wifihaven.api.press.PressToken]], so the minting
   * code and this redactor cannot drift apart; `ReplyRedactionSpec` additionally feeds REAL minted
   * tokens through [[scrub]], so a grammar change that this file did not follow fails there.
   */
  val Version: String = "v1"

  /** Hex characters in the HMAC-SHA256 signature both grammars append. */
  val SignatureHexChars: Int = 64

  /**
   * What a redacted credential is replaced with — visible, so a reader can see something was cut.
   */
  val Marker: String = "[redacted-credential]"

  /** Which responder authored the text. A fixed 2-value enum — the metric's `channel` label. */
  object Channel {
    val Support: String = "support"
    val Press: String   = "press"
  }

  /**
   * WHICH grammar matched. A fixed 2-value enum — the metric's `reason` label (an EXISTING key in
   * the #1210 bounded vocabulary; a new `rule` key would have enlarged it for no gain).
   */
  object Reason {

    /**
     * One of our own agent-token grammars — `v1.<b64url>.<64 hex>`. The severe case: the agent
     * quoted the exact credential that authenticates it to us.
     */
    val AgentToken: String = "agent_token"

    /**
     * A generic `Bearer <credential>` header value — anything token-shaped the agent pasted out of
     * its own environment (a routine token, an API key) that is not one of our grammars.
     */
    val Bearer: String = "bearer"
  }

  /**
   * BOTH agent-token grammars: `v1.<base64url payload>.<64 lowercase hex>` — the exact shape
   * `ConsentToken.mint` and `PressToken.mint` emit. The 64-hex tail is what keeps this off ordinary
   * text: a version string, a UUID, or dotted prose cannot reach it.
   */
  private val AgentToken: Regex =
    raw"\b${Regex.quote(Version)}\.[A-Za-z0-9_-]{8,}\.[0-9a-f]{$SignatureHexChars}\b".r

  /** Shortest bearer value we will treat as a credential rather than as a word. */
  private val MinBearerValueChars: Int = 16

  /**
   * A generic bearer credential. Deliberately narrow so prose survives: the value must be at least
   * [[MinBearerValueChars]] characters of credential alphabet AND contain a non-letter (a digit or
   * one of `. _ ~ + / = -`). "Bearer authentication over HTTPS" therefore does not match — the word
   * is too short and is all letters — while `Bearer sk-ant-oat01-9f3a…` does.
   */
  private val BearerValue: Regex =
    raw"(?i)\bBearer\s+(?=[A-Za-z0-9._~+/=-]{$MinBearerValueChars,})(?=[A-Za-z]*[0-9._~+/=-])[A-Za-z0-9._~+/=-]+".r

  /** The result of a scrub: the safe text, plus which rules fired (empty ⇒ nothing was changed). */
  final case class Scrubbed(text: String, reasons: List[String]) {
    def fired: Boolean = reasons.nonEmpty
  }

  /**
   * Redact any credential-shaped substring. Pure, so it is unit-pinnable and so the same call can
   * be applied to a title, a body, a note, or a reply without a side effect. The token grammar runs
   * FIRST so `Bearer v1.…` is attributed to the specific rule rather than the generic one.
   */
  def scrub(text: String): Scrubbed = {
    val afterToken  = AgentToken.replaceAllIn(text, Regex.quoteReplacement(Marker))
    val afterBearer = BearerValue.replaceAllIn(afterToken, Regex.quoteReplacement(Marker))
    val reasons     =
      List(
        Option.when(afterToken != text)(Reason.AgentToken),
        Option.when(afterBearer != afterToken)(Reason.Bearer),
      ).flatten
    Scrubbed(afterBearer, reasons)
  }

  /**
   * Scrub `text` and, when anything fired, say so LOUDLY: one ERROR log plus
   * `agent_reply_redacted_total{channel,op,reason}`.
   *
   * ERROR is the right level and cannot be used to flood the operator's log: every caller sits
   * BEHIND token verification, so only a caller holding a token we minted can reach it — and a run
   * that quotes its own credential back at us is either hijacked or badly confused. Either way an
   * operator wants to know. The line carries only the bounded channel / op / reason labels: the
   * credential itself is never logged, or the alert would become the leak.
   */
  def redact(channel: String, op: String, text: String): UIO[String] = {
    val s = scrub(text)
    ZIO
      .when(s.fired) {
        ZIO.logError(
          s"$channel: agent $op text carried a CREDENTIAL and was redacted before send " +
            s"(reasons=${s.reasons.mkString(",")}) — a hijacked or confused agent quoted its own " +
            s"session token; see #2508",
        ) *> ZIO.foreachDiscard(s.reasons)(AppMetrics.agentReplyRedacted(channel, op, _))
      }
      .as(s.text)
  }

  /** [[redact]] for an optional field (an escalation note); `None` passes through untouched. */
  def redactOpt(channel: String, op: String, text: Option[String]): UIO[Option[String]] =
    ZIO.foreach(text)(redact(channel, op, _))
}
