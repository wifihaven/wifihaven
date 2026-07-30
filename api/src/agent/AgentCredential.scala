package wifihaven.api.agent

import wifihaven.api.metrics.AppMetrics
import wifihaven.api.press.PressToken
import wifihaven.api.support.{ConsentGrant, ConsentToken}
import zio.*

import scala.util.matching.Regex

/**
 * #2508 — strip a cloud agent's OWN bearer credential out of the text it authors, before that text
 * leaves the process, and say so loudly when it happens.
 *
 * WHY it exists. A dispatched support (#2200) or press (#2203) agent holds one credential — the
 * signed session token — for the life of its run, and the reply text is the one thing it fully
 * authors. Nothing scrubbed it: a hijacked or confused agent could quote its token into a
 * customer-visible Plain thread, an untrusted journalist's inbox, the public `/press`
 * correspondence log, or a public GitHub issue. The #2335 / #2336 hand validations reported "zero
 * token-shaped strings" in the outbound rows, which is a real observation about model behaviour
 * under one attack — not a control.
 *
 * WHAT THIS IS, HONESTLY. It is a filter plus a detection signal, NOT a structural guarantee, and
 * it is deliberately weaker than the other legs of both trust models. The reply destination coming
 * from the SIGNED token cannot be defeated by the agent at all; a pattern match over agent-authored
 * text can be — split the token across a newline, base64 it, describe it in words. So the durable
 * value here is twofold: the accident and the naive attack are stopped, and any attempt at all
 * becomes an ERROR log plus a sample on a metric whose expected value is zero. Do not read this
 * file as closing the exfiltration path; read it as making the easy version fail and the attempt
 * visible.
 *
 * WHERE THE SEAM SITS, and why not at a transport. The obvious mirror of `scrubForIssue` (which
 * sits inside [[wifihaven.api.support.GithubIssueClient]] so no caller can forget it) would be to
 * scrub inside [[wifihaven.api.notify.EmailSender]] / [[wifihaven.api.support.PlainClient]]. Two
 * concrete reasons it is here instead:
 *
 *   - **A transport boundary does not cover the sinks.** Agent-authored text reaches outside
 *     parties through paths that never touch either transport: the `/press` correspondence-log row
 *     is written through [[wifihaven.api.db.PressMessageRepo]] (a public pull surface at `/press`),
 *     and an escalation note is folded into a rendered notice body by
 *     [[wifihaven.api.notify.Notifier]] before any transport sees it. Scrubbing at
 *     `EmailSender`/`PlainClient` would leave both uncovered; scrubbing where the agent's text
 *     ENTERS covers every downstream use by construction, because the safe copy is the only copy
 *     the rest of the method can reach.
 *   - **Attribution.** The point of firing loudly is knowing WHICH channel and WHICH callback an
 *     agent tried it on. A transport sees a string and a recipient; it cannot label the sample
 *     `channel`/`op`, and re-deriving that inside the transport is exactly the kind of
 *     back-inference this repo rejects elsewhere.
 *
 * (What is NOT a reason: "the shared `EmailSender` legitimately carries secrets." It does carry the
 * password-reset link and the beta invite token, but neither matches the grammars below, so a
 * transport-level scrub with THESE rules would not have touched them.)
 *
 * BOTH channels get the credential rules; neither gets
 * [[wifihaven.api.support.SupportPrivacy.scrubForIssue]]'s PII rules, for opposite reasons. A
 * support reply legitimately quotes household data back to the household that consented to the read
 * (#2419) — that is the whole point of the consent flow, and scrubbing it would break the product.
 * A press reply must never contain household data at all, but it has no path to any: the press
 * token carries no household and there is no data endpoint on the press side, so the absence is
 * already structural and a PII scrub there would only mangle a journalist's own quoted numbers.
 *
 * PRECISION OVER BREADTH. A redactor that mangled real answers would be worse than none — support
 * would route around it. So both rules are narrow, and `ReplyRedactionSpec` pins the false-positive
 * side as hard as the true-positive side: a UUID, a `v1.2.3` version string, base64-ish words,
 * `Bearer authentication-based access control`, a `YOUR_API_KEY_HERE` placeholder, and a short
 * digit-bearing id (`Bearer token1234` — the one case the LENGTH floor, not the digit check, has to
 * save) all survive byte-identical.
 */
object AgentCredential {

  /**
   * Every signed-token version prefix this subsystem mints, DERIVED from the minting objects rather
   * than re-typed here — so a scheme that bumps its own prefix is followed automatically, and a NEW
   * scheme is a compile-visible one-line addition instead of a silent gap. (`g1` consent grants are
   * included because the agent can read a consent link out of the thread history it now sees,
   * #2441, and can file a public issue.)
   *
   * The prefix is the only part of the grammar a constant can single-source; the rest — separators,
   * base64url payload, hex signature — is re-expressed as a pattern below and CANNOT be kept in
   * step that way. The real anti-drift guard is `ReplyRedactionSpec`, which feeds tokens from the
   * actual `mint` calls through [[scrub]]: change the signature algorithm or the separator and that
   * suite fails, rather than the redactor silently disarming.
   */
  lazy val Versions: Set[String] =
    Set(ConsentToken.Version, PressToken.Version, ConsentGrant.Version)

  /**
   * Hex characters in the HMAC-SHA256 signature every one of those schemes appends — 32 bytes, so
   * 64 hex chars. This is what keeps the pattern off ordinary text: no version string, UUID, or
   * dotted prose reaches a 64-hex tail.
   */
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
     * One of our own signed-token grammars. The severe case: the agent quoted a credential this
     * system minted — for the agent tokens, the very one that authenticates it to us.
     */
    val AgentToken: String = "agent_token"

    /**
     * A generic `Bearer <credential>` header value — anything token-shaped the agent pasted out of
     * its own environment (a routine token, an API key) that is not one of our grammars.
     */
    val Bearer: String = "bearer"
  }

  /** Shortest bearer value we will treat as a credential rather than as a word — see below. */
  private val MinBearerValueChars: Int = 16

  /**
   * `<ver>.<base64url payload>.<64 lowercase hex>` — the exact shape `ConsentToken.mint`,
   * `PressToken.mint` and `ConsentGrant.mint` emit, with the version alternation derived from
   * [[Versions]].
   */
  // `lazy` so this cannot depend on declaration order relative to `Versions` — a reorder would
  // otherwise read a null and silently disarm the rule at object init.
  private lazy val AgentToken: Regex = {
    val vers = Versions.toList.sorted.map(Regex.quote).mkString("|")
    raw"\b(?:$vers)\.[A-Za-z0-9_-]+\.[0-9a-f]{$SignatureHexChars}\b".r
  }

  /**
   * A generic bearer credential, deliberately narrow so prose survives. The value must satisfy BOTH
   * of two independent filters, because either alone over-matches English:
   *
   *   - at least [[MinBearerValueChars]] characters of credential alphabet — a floor chosen to sit
   *     above ordinary words while staying below every credential shape we issue or consume (the
   *     shortest of those is a 32-hex digest); and
   *   - at least one DIGIT. This is what keeps hyphenated / dotted / underscored prose out: `Bearer
   *     authentication-based`, `bearer credentials.Rotate` and a `YOUR_API_KEY_HERE` placeholder
   *     are all long enough, and all survive because none carries a digit.
   *
   * Both halves are pinned from both directions in `ReplyRedactionSpec` — the floor on its own by a
   * SHORT digit-bearing word after `Bearer` that must survive, which the digit rule alone would not
   * give.
   *
   * KNOWN GAP, stated rather than waved away: the digit requirement buys the false-positive fix at
   * the cost of a false-negative class. An all-letter credential (`Bearer aBcDeF…`, no digit)
   * passes straight through. That is a deliberate trade — a redactor that mangles real support
   * answers gets routed around, and this rule is a best-effort filter over adversary-authored text
   * either way (see the honesty note at the top of this file). Our OWN minted tokens are
   * unaffected: they always carry a version prefix and a hex signature, so the token rule catches
   * them whatever the bearer rule does.
   */
  // `lazy` for the same reason as the token pattern above — and here the silent failure is
  // worse in kind: a forward reference to an Int yields 0, i.e. `{0,}`, a floor that is gone
  // rather than a crash.
  private lazy val BearerValue: Regex =
    raw"(?i)\bBearer\s+(?=[A-Za-z0-9._~+/=-]{$MinBearerValueChars,})(?=[A-Za-z._~+/=-]*[0-9])[A-Za-z0-9._~+/=-]+".r

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
   * ERROR because a run that quotes a credential back at us is either hijacked or badly confused,
   * and an operator wants to read that thread. It is not reachable by the anonymous internet —
   * every caller sits behind token verification — but note that is NOT a rate limit: the reply
   * callback in particular has no limiter, so one compromised session could repeat the attempt. The
   * counter is therefore the durable signal (a step change in its rate is legible however many
   * lines were written); the log line is the pointer to go look. Either way it carries only the
   * bounded channel / op / reason labels — the credential is never logged, or the alert would
   * become the leak.
   */
  def redact(channel: String, op: String, text: String): UIO[String] = {
    val s = scrub(text)
    ZIO
      .when(s.fired) {
        ZIO.logError(
          s"$channel: agent $op text carried a CREDENTIAL and was redacted before send " +
            s"(reasons=${s.reasons.mkString(",")}) — a hijacked or confused agent quoted a token " +
            s"this system minted; see #2508",
        ) *> ZIO.foreachDiscard(s.reasons)(AppMetrics.agentReplyRedacted(channel, op, _))
      }
      .as(s.text)
  }

  /** [[redact]] for an optional field (an escalation note); `None` passes through untouched. */
  def redactOpt(channel: String, op: String, text: Option[String]): UIO[Option[String]] =
    ZIO.foreach(text)(redact(channel, op, _))
}
