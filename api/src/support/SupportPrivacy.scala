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

  // The replacement strings this scrubber substitutes for redacted PII. Named, and then USED by
  // `scrubForIssue` below — not a parallel list beside it. A second copy would be exactly the
  // "keep in sync by hand" smell, and the drift would be silent: [[GithubIssueClient]] strips these
  // out of a SANITISED title before matching it for duplicates (#2458 review), so a new placeholder
  // that the list missed would quietly re-open the false-match it exists to prevent.
  val EmailPlaceholder: String     = "[redacted-email]"
  val MacPlaceholder: String       = "[redacted-mac]"
  val IpPlaceholder: String        = "[redacted-ip]"
  val NumberPlaceholder: String    = "[redacted-number]"
  val TruncatedPlaceholder: String = "[truncated]"

  /**
   * Every string [[scrubForIssue]] can inject. The ONE place downstream code should read to know
   * what is scrubber output rather than customer/agent prose. Adding a redaction rule means adding
   * its placeholder here, and the compiler will not remind you — but every consumer reads THIS, so
   * there is one thing to update rather than N.
   */
  val Placeholders: Set[String] =
    Set(
      EmailPlaceholder,
      MacPlaceholder,
      IpPlaceholder,
      NumberPlaceholder,
      TruncatedPlaceholder,
    )

  /**
   * Remove every [[Placeholders]] string from `text`, leaving a space behind.
   *
   * For consumers that TOKENISE scrubbed text: the placeholders must not survive as topic words
   * (`redacted`, `email`, `mac`, …), because they are shared by every PII-bearing string and would
   * make unrelated ones look alike. Removing the whole literal is the narrow fix — dropping the
   * constituent WORDS from a stop-list instead would also strip `mac` / `email` / `number` out of
   * titles that never carried PII, which in this product ("Cannot change MAC address") is throwing
   * away exactly the words that distinguish one report from another (#2458 review, round 2).
   *
   * The fold is over an UNORDERED set, so removal order is arbitrary — which is only safe while no
   * placeholder is a substring of another (adding `[redacted]` alongside `[redacted-ip]` would make
   * the result order-dependent). `GithubIssueDedupSpec` pins that invariant rather than leaving it
   * to whoever adds the next redaction rule.
   */
  def stripPlaceholders(text: String): String =
    Placeholders.foldLeft(text)((s, p) => s.replace(p, " "))

  private val Email = "(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}".r
  private val Ipv4  = "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b".r
  // IPv6: 2+ hextet groups separated by colons (with optional :: compression). Kept conservative to
  // avoid eating ordinary "a:b" text; matches real addresses like fe80::1 or 2001:db8::1.
  private val Ipv6  = "\\b(?:[0-9A-Fa-f]{1,4}:){2,}(?::)?[0-9A-Fa-f]{0,4}\\b".r
  private val Mac   = "\\b(?:[0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}\\b".r

  /**
   * 9+ consecutive digits (optionally separated by spaces/dashes) — phone / account / card-ish
   * runs.
   *
   * #2458: this was 7+, which matched an ISO date (`2026-12-20` is 8 digits with two dashes) and
   * redacted the AGENT'S OWN example dates out of the issue it was filing (#2457 shipped as
   * "suspend/loosen this window from [redacted-number] to [redacted-number]"). Dates are not PII
   * here, and a scrubber that mangles the report is doing more damage than the leak it guards.
   *
   * The floor is 9 rather than 8 so a date is safely under it rather than exactly at it. The
   * deliberate cost: a bare 7-or-8-digit local phone number (`555-1234`) no longer matches.
   * Accepted — a short separator-less digit run is indistinguishable from a build number, a byte
   * count, or a port, so it was the noisiest part of the rule; and every format that carries an
   * area/country code (10+ digits), every account number, and every card number still matches. This
   * is a COMPENSATING control layered under the agent's standing instruction not to put customer
   * data in an issue at all — not the only guard.
   */
  private val LongDigits = "\\b\\d(?:[\\s-]?\\d){8,}\\b".r

  /**
   * An ISO calendar date, the shape that motivated the #2458 narrowing — with the month and day
   * RANGE-CHECKED, not just shaped.
   *
   * The range check is what keeps this an exemption rather than an escape hatch (#2458 review): a
   * shape-only `\d{4}-\d{2}-\d{2}` would exempt `1234-56-78 9012-34-56`, i.e. a 16-digit account or
   * card number chunked 4-2-2, which WAS redacted before this PR. Contrived for an honest agent,
   * but this is a compensating control against untrusted content, so the narrow rule is the right
   * one.
   *
   * The YEAR is constrained too, and it has to be: a 4-digit year carries no PII on its own, but it
   * is the exemption that leaks, not the year. `4111-11-11 1111-11-11` — the canonical
   * `4111111111111111` test card chunked 4-2-2 — is two range-valid dates once any year is allowed
   * (#2458 review, round 2). `19|20` covers every date a support conversation can plausibly be
   * about while making that composition impossible.
   */
  private val IsoDate = "(?:19|20)\\d{2}-(?:0[1-9]|1[0-2])-(?:0[1-9]|[12]\\d|3[01])".r

  /**
   * True when every whitespace-separated part of `run` is an ISO date — e.g. `2026-12-20
   * 2026-12-21`, which is 16 digits and so clears the 9-digit floor on its own. The floor alone
   * saves a LONE date; this saves a sequence of them.
   */
  private def allIsoDates(run: String): Boolean = {
    val parts = run.split("\\s+").filter(_.nonEmpty)
    parts.nonEmpty && parts.forall(p => IsoDate.matches(p))
  }

  /**
   * Redact obvious PII from `text` and cap its length. Order matters: MAC/IPv6 before the generic
   * digit run so a MAC/address isn't half-eaten by the digit rule.
   */
  def scrubForIssue(text: String): String = {
    var s = text
    s = Email.replaceAllIn(s, EmailPlaceholder)
    s = Mac.replaceAllIn(s, MacPlaceholder)
    s = Ipv6.replaceAllIn(s, IpPlaceholder)
    s = Ipv4.replaceAllIn(s, IpPlaceholder)
    s = LongDigits.replaceAllIn(
      s,
      m =>
        if allIsoDates(m.matched) then java.util.regex.Matcher.quoteReplacement(m.matched)
        else NumberPlaceholder,
    )
    if s.length > MaxIssueBodyChars then s.take(MaxIssueBodyChars) + s"\n\n$TruncatedPlaceholder"
    else s
  }
}
