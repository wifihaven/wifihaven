package wifihaven.api.support

/**
 * #2200 / #2241 — the COMPENSATING CONTROL for the flagged combined risk (consented full-household
 * read + autonomous public-issue-filing). A hijacked agent could read the consenting household's
 * data and dump it into a public GitHub issue; even the customer's own data is a leak once it lands
 * in a public repo. So the issue-filing tool MUST NOT embed raw household-data-query output or
 * obvious PII in an issue body — it files the SYMPTOM / summary only.
 *
 * [[scrubForIssue]] is the structural guardrail: it redacts obvious PII (URLs, emails, IPv4/IPv6
 * addresses, MAC addresses, long digit runs — phone / account numbers) and caps the body length,
 * regardless of what the agent tried to put there. It is applied at the [[GithubIssueClient]] trait
 * boundary so EVERY path that files an issue is scrubbed — the guarantee doesn't depend on any
 * caller remembering to sanitise. Pure so it is unit-pinnable (`SupportPrivacySpec`).
 *
 * #2454 — what this scrubber CANNOT do, and what covers the gap. The consented read returns a
 * household NAME and PROFILE names (by product design, typically children's given names). Those are
 * ordinary words: they match no pattern here and never will, so a regex scrubber can't be the
 * control for that payload. The actual control is structural and lives in
 * [[SupportResponder.agentFileIssue]] — a session whose token carries `dataAccess=true` is REFUSED
 * issue filing outright, so the read and the public publish never compose in one session. This
 * scrubber remains the second layer, for what a scope-less session can still put in a body.
 *
 * #2453 — [[redactConsentLinks]] is the OTHER direction: it strips the consent capability URL out
 * of text flowing INTO the agent's own prompt. Kept here, next to the issue scrubber, so the two
 * redaction rules and their deliberately different breadth sit in one file.
 */
object SupportPrivacy {

  /** Cap the issue body so a runaway agent can't paste a whole household export into an issue. */
  val MaxIssueBodyChars: Int = 4000

  /**
   * #2453 — what a redacted consent link renders as in the agent's thread transcript. A visible
   * placeholder, not a silent deletion: the agent must be able to tell that a link WAS there (so it
   * doesn't re-ask for consent that is already pending) without being able to read it.
   */
  val ConsentLinkPlaceholder: String = "[consent link omitted]"

  // #2453 — the #2419 consent capability link, `<appBaseUrl>/support/consent?g=<signed grant>`. The
  // origin is deliberately not pinned (it is per-deployment config) and the scheme is optional, so
  // a bare `/support/consent?g=…` path matches too. The character class stops at the delimiters a
  // URL is wrapped in — whitespace, `)`, `]`, `>`, quotes — so a markdown link redacts to
  // `[Allow me](<placeholder>)` with the surrounding syntax intact.
  private val ConsentLink =
    "(?i)(?:https?://[^\\s)\\]>\"']*)?/support/consent\\?g=[^\\s)\\]>\"']*".r

  /**
   * #2453 — strip #2419 consent links out of `text`.
   *
   * The consent prompt is posted into the customer's Plain thread through the SAME machine-user
   * write path as every AI reply, so since #2430/#2441 it comes back on the timeline and re-enters
   * the agent's own kickoff as `ai_assistant` history. That defeats the documented anti-phishing
   * guarantee on [[SupportResponder.agentRequestConsent]] — "the agent supplies no text here, so a
   * prompt-injected agent cannot craft a phishing message under our attribution" — because a
   * prompt-injected agent can re-post the REAL, VALID URL wrapped in a pretext of its own. The
   * premise holds only if the agent never SEES the link.
   *
   * Deliberately NARROW, and deliberately not [[scrubForIssue]]: thread history must not be
   * blanket-URL-scrubbed, because a customer legitimately pastes links the agent needs to read.
   * Only the capability URL goes.
   */
  def redactConsentLinks(text: String): String =
    ConsentLink.replaceAllIn(text, java.util.regex.Matcher.quoteReplacement(ConsentLinkPlaceholder))

  // #2453 — any absolute http(s) URL. On the way OUT to a PUBLIC repo the bar is the opposite of
  // the history bar above: an issue filed by the support agent is a SYMPTOM report, and no
  // legitimate one needs to republish a link the agent read out of a customer's thread — while a
  // capability URL that survives (the consent link is the known case) is a live credential in a
  // public issue. So every URL goes, not just the consent shape.
  private val Url        = "(?i)\\bhttps?://[^\\s)\\]>\"']+".r
  private val Email      = "(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}".r
  private val Ipv4       = "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b".r
  // IPv6: 2+ hextet groups separated by colons (with optional :: compression). Kept conservative to
  // avoid eating ordinary "a:b" text; matches real addresses like fe80::1 or 2001:db8::1.
  private val Ipv6       = "\\b(?:[0-9A-Fa-f]{1,4}:){2,}(?::)?[0-9A-Fa-f]{0,4}\\b".r
  private val Mac        = "\\b(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\\b".r
  // 7+ consecutive digits (optionally separated by spaces/dashes) — phone / account / card-ish runs.
  private val LongDigits = "\\b\\d(?:[\\s-]?\\d){6,}\\b".r

  /**
   * Redact obvious PII from `text` and cap its length. Order matters: URLs first (so an address or
   * digit run INSIDE a URL isn't half-eaten, leaving a mangled but still-readable link), then
   * MAC/IPv6 before the generic digit run so a MAC/address isn't half-eaten by the digit rule.
   */
  def scrubForIssue(text: String): String = {
    var s = text
    // #2453: absolute URLs, then the scheme-less consent-path form the URL rule cannot see.
    s = Url.replaceAllIn(s, "[redacted-url]")
    s = redactConsentLinks(s)
    s = Email.replaceAllIn(s, "[redacted-email]")
    s = Mac.replaceAllIn(s, "[redacted-mac]")
    s = Ipv6.replaceAllIn(s, "[redacted-ip]")
    s = Ipv4.replaceAllIn(s, "[redacted-ip]")
    s = LongDigits.replaceAllIn(s, "[redacted-number]")
    if s.length > MaxIssueBodyChars then s.take(MaxIssueBodyChars) + "\n\n[truncated]" else s
  }
}
