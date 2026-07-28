package wifihaven.api.unit

import wifihaven.api.support.SupportPrivacy
import zio.test.*

/**
 * #2453 / #2454 — the two holes in [[SupportPrivacy]], pinned.
 *
 * `scrubForIssue` is documented as THE compensating control for "consented full-household read +
 * autonomous public-issue filing" (#2200 / #2241), and it is applied at the `GithubIssueClient`
 * trait boundary so every filing path is covered. Two things it did not cover:
 *
 *   1. **#2453 — URLs.** A capability URL that reaches agent context (the #2419 consent link, which
 *      since #2430/#2441 re-enters the agent's own prompt as thread history) passed straight through
 *      into the PUBLIC `wifihaven/wifihaven` repo. Redacting every absolute URL is deliberately
 *      blunt: an issue filed by the support agent is a SYMPTOM report, and no legitimate one needs
 *      to republish a link the agent read out of a customer's thread.
 *   2. **#2454 — the redaction PRIMITIVE for consent links**, shared with
 *      `CloudAgentDispatcher.renderHistory`. History must NOT be blanket-URL-scrubbed (a customer
 *      legitimately pastes links, and the agent needs to read them), so the consent-link pattern is
 *      its own narrow rule with its own placeholder.
 *
 * Scope guard: the `LongDigits` over-reach (it eats ISO dates) is #2458's, not this spec's — the
 * cases below deliberately do not pin date behaviour either way.
 */
object SupportPrivacySpec extends ZIOSpecDefault {

  private val Link =
    "https://app.wifihaven.net/support/consent?g=g1.aGVsbG8.deadbeefcafe"

  def spec = suite("SupportPrivacy (#2453 / #2454)")(
    // ── #2453: URLs never reach a public issue ────────────────────────────────
    test("scrubForIssue redacts a consent capability URL out of an issue body") {
      val body = s"The customer clicked $Link and it 404'd."
      val out  = SupportPrivacy.scrubForIssue(body)
      assertTrue(
        !out.contains("g1."),
        !out.contains("/support/consent"),
        out.contains("[redacted-url]"),
        // the surrounding prose — the actual symptom — survives.
        out.contains("The customer clicked"),
        out.contains("and it 404'd."),
      )
    },
    test("scrubForIssue redacts a URL inside markdown link syntax without eating the sentence") {
      val out = SupportPrivacy.scrubForIssue(s"see [the link]($Link) please")
      assertTrue(
        out.contains("[the link]"),
        !out.contains("app.wifihaven.net"),
        out.contains("please"),
      )
    },
    test("scrubForIssue redacts ordinary http/https URLs too, not just consent links") {
      val out = SupportPrivacy.scrubForIssue(
        "logs at https://wifihaven.grafana.net/d/abc and http://192.168.1.1/cgi-bin/x",
      )
      assertTrue(
        !out.contains("grafana.net"),
        !out.contains("cgi-bin"),
        out.contains("[redacted-url]"),
      )
    },
    test("the existing PII rules still fire (scrub order must not have broken them)") {
      val out = SupportPrivacy.scrubForIssue(
        "parent@example.com on aa:bb:cc:dd:ee:ff at 10.0.0.4",
      )
      assertTrue(
        out.contains("[redacted-email]"),
        out.contains("[redacted-mac]"),
        out.contains("[redacted-ip]"),
        !out.contains("example.com"),
      )
    },
    test("the body cap still applies after URL redaction") {
      val out = SupportPrivacy.scrubForIssue("x" * (SupportPrivacy.MaxIssueBodyChars + 500))
      assertTrue(out.endsWith("[truncated]"))
    },

    // ── #2453: the narrow consent-link primitive used on thread history ───────
    test("redactConsentLinks replaces the link with the placeholder") {
      val out = SupportPrivacy.redactConsentLinks(s"click here: $Link now")
      assertTrue(
        out.contains(SupportPrivacy.ConsentLinkPlaceholder),
        !out.contains("g1."),
        !out.contains("/support/consent"),
        out.contains("click here:"),
        out.contains("now"),
      )
    },
    test("redactConsentLinks stops at the closing paren of a markdown link") {
      val out = SupportPrivacy.redactConsentLinks(s"**[Allow me]($Link)** and then some")
      assertTrue(
        out.contains("**[Allow me]("),
        out.contains(")** and then some"),
        !out.contains("g1."),
      )
    },
    test("redactConsentLinks matches a scheme-less consent path too") {
      val out = SupportPrivacy.redactConsentLinks("go to /support/consent?g=g1.abc.def today")
      assertTrue(!out.contains("g1."), out.contains("today"))
    },
    test("redactConsentLinks leaves ordinary URLs alone — history is not blanket-scrubbed") {
      val text = "my router page is at https://192.168.1.1/status and it hangs"
      assertTrue(SupportPrivacy.redactConsentLinks(text) == text)
    },
  )
}
