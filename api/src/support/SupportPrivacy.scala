package wifihaven.api.support

/**
 * #2200 / #2241 — the COMPENSATING CONTROL for the flagged combined risk (consented full-household
 * read + autonomous public-issue-filing). A hijacked agent could read the consenting household's
 * data and dump it into a public GitHub issue; even the customer's own data is a leak once it lands
 * in a public repo. So the issue-filing tool MUST NOT embed raw household-data-query output or
 * obvious PII in an issue body — it files the SYMPTOM / summary only.
 *
 * [[scrubForIssue]] is the structural guardrail: it redacts obvious PII (emails, IPv4/IPv6
 * addresses, MAC addresses, long digit runs — phone / account numbers) and caps the body length,
 * regardless of what the agent tried to put there. It is applied at the [[GithubIssueClient]] trait
 * boundary so EVERY path that files an issue is scrubbed — the guarantee doesn't depend on any
 * caller remembering to sanitise. Pure so it is unit-pinnable (the PII-scrub feature test).
 */
object SupportPrivacy {

  /** Cap the issue body so a runaway agent can't paste a whole household export into an issue. */
  val MaxIssueBodyChars: Int = 4000

  private val Email      = "(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}".r
  private val Ipv4       = "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b".r
  // IPv6: 2+ hextet groups separated by colons (with optional :: compression). Kept conservative to
  // avoid eating ordinary "a:b" text; matches real addresses like fe80::1 or 2001:db8::1.
  private val Ipv6       = "\\b(?:[0-9A-Fa-f]{1,4}:){2,}(?::)?[0-9A-Fa-f]{0,4}\\b".r
  private val Mac        = "\\b(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\\b".r
  // 7+ consecutive digits (optionally separated by spaces/dashes) — phone / account / card-ish runs.
  private val LongDigits = "\\b\\d(?:[\\s-]?\\d){6,}\\b".r

  /**
   * Redact obvious PII from `text` and cap its length. Order matters: MAC/IPv6 before the generic
   * digit run so a MAC/address isn't half-eaten by the digit rule.
   */
  def scrubForIssue(text: String): String = {
    var s = text
    s = Email.replaceAllIn(s, "[redacted-email]")
    s = Mac.replaceAllIn(s, "[redacted-mac]")
    s = Ipv6.replaceAllIn(s, "[redacted-ip]")
    s = Ipv4.replaceAllIn(s, "[redacted-ip]")
    s = LongDigits.replaceAllIn(s, "[redacted-number]")
    if s.length > MaxIssueBodyChars then s.take(MaxIssueBodyChars) + "\n\n[truncated]" else s
  }
}
