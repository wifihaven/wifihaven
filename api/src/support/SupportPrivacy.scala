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

  /** #2453 — what a redacted absolute URL becomes on the way OUT to a public issue. */
  val UrlPlaceholder: String = "[redacted-url]"

  /**
   * #2453 — what a redacted consent link renders as in the agent's thread transcript. A visible
   * placeholder, not a silent deletion: the agent must be able to tell that a link WAS there (so it
   * doesn't re-ask for consent that is already pending) without being able to read it.
   */
  val ConsentLinkPlaceholder: String = "[consent link omitted]"

  /**
   * Every string [[scrubForIssue]] can inject. The ONE place downstream code should read to know
   * what is scrubber output rather than customer/agent prose. Adding a redaction rule means adding
   * its placeholder here, and the compiler will not remind you — but every consumer reads THIS, so
   * there is one thing to update rather than N.
   *
   * #2453's two entries are the first additions since #2458 built this: [[scrubForIssue]] can
   * inject BOTH (the `Url` rule, and [[redactConsentLinks]] for the scheme-less consent path the
   * URL rule cannot see), so both belong here or issue-dedup would treat them as topic words.
   * `SupportPrivacySpec` pins that membership, and the bracket-delimited invariant below with it.
   */
  val Placeholders: Set[String] =
    Set(
      EmailPlaceholder,
      MacPlaceholder,
      IpPlaceholder,
      NumberPlaceholder,
      TruncatedPlaceholder,
      UrlPlaceholder,
      ConsentLinkPlaceholder,
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
   * placeholder is a substring of another. Every placeholder being bracket-DELIMITED is what makes
   * that hold for free (`[redacted-ip]` does NOT contain `[redacted]` — the `]` terminates), so the
   * invariant would only break on an unterminated entry like `"[redacted"`. Cheap enough to pin
   * anyway rather than leave to whoever adds the next redaction rule; `GithubIssueDedupSpec` does.
   */
  def stripPlaceholders(text: String): String =
    Placeholders.foldLeft(text)((s, p) => s.replace(p, " "))

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
  private val Url   = "(?i)\\bhttps?://[^\\s)\\]>\"']+".r
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
   * Redact obvious PII from `text` and cap its length. Order matters: URLs first (so an address or
   * digit run INSIDE a URL isn't half-eaten, leaving a mangled but still-readable link), then
   * MAC/IPv6 before the generic digit run so a MAC/address isn't half-eaten by the digit rule.
   */
  def scrubForIssue(text: String): String = {
    var s = text
    // #2453: absolute URLs FIRST, then the scheme-less consent-path form the URL rule cannot see.
    // Before the PII rules on purpose: a digit run or IP inside a URL is then removed WITH the URL
    // rather than half-eaten, which also keeps #2458's ISO-date exemption from having to reason
    // about dates embedded in query strings.
    s = Url.replaceAllIn(s, UrlPlaceholder)
    s = redactConsentLinks(s)
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
