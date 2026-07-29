package wifihaven.api.unit

import wifihaven.api.support.SupportPrivacy
import zio.test.*

/**
 * #2458 (secondary) — `scrubForIssue`'s `LongDigits` rule ate ORDINARY CALENDAR DATES. The very
 * first duplicate the support agent filed (#2457) reads "suspend/loosen this window from
 * [redacted-number] to [redacted-number]": the agent had written two example ISO dates, and the
 * 7-digits-with-optional-separators rule matched `2026-12-20` exactly.
 *
 * Dates are not PII here, and a redaction that silently damages the agent's own output is worse
 * than the leak it is guarding against — the issue becomes unreadable and the operator cannot tell
 * a scrubbed date from a scrubbed account number.
 *
 * The tradeoff is explicit and pinned in BOTH directions below: the rule is narrowed (≥9 digits,
 * plus an ISO-date-shape exemption) so dates survive, and every genuine phone / account / card-ish
 * run in the second test must still be redacted. The cost of the ≥9 floor is that a bare
 * 7-or-8-digit local phone number (`555-1234`) no longer matches — accepted deliberately: a
 * separator-less 7-digit run is indistinguishable from a build number, a byte count, or a port
 * scan, and the scrubber is a COMPENSATING control (the agent is instructed not to put customer
 * data in an issue at all), not the only one.
 *
 * A pure function, so a unit spec is the right level (docs/process/testing.md).
 *
 * #2453 adds the OTHER two gaps in the same scrubber, pinned in the second group below:
 *
 *   - **URLs reached a public issue.** A capability URL that gets into agent context — the #2419
 *     consent link, which since #2430/#2441 re-enters the agent's own prompt as thread history —
 *     passed straight through into the PUBLIC `wifihaven/wifihaven` repo. Redacting every absolute
 *     URL is deliberately blunt: an issue filed by the support agent is a SYMPTOM report, and no
 *     legitimate one needs to republish a link the agent read out of a customer's thread.
 *   - **The narrow consent-link primitive**, shared with `CloudAgentDispatcher`'s kickoff render.
 *     Thread history must NOT be blanket-URL-scrubbed (a customer legitimately pastes links the
 *     agent needs to read), so the consent-link pattern is its own rule with its own placeholder.
 *
 * The two rules compose with #2458's narrowing rather than reopening it: URL redaction runs FIRST,
 * so a digit run inside a URL is removed with the URL instead of being half-eaten, and the ISO-date
 * exemption is untouched (the date tests above still run, unchanged).
 *
 * #2454's own fix is structural and lives in `SupportResponder.agentFileIssue` (a `dataAccess=true`
 * session cannot file at all), pinned in feature/SupportConsentSpec — no regex here could cover the
 * household + profile names that read returns.
 */
object SupportPrivacySpec extends ZIOSpecDefault {

  private val Link =
    "https://app.wifihaven.net/support/consent?g=g1.aGVsbG8.deadbeefcafe"

  def spec = suite("SupportPrivacy.scrubForIssue (#2458 / #2453)")(
    test("ordinary calendar dates survive — the #2457 body reads as written") {
      val body     =
        "Customer wants to suspend/loosen this window from 2026-12-20 to 2027-01-05 " +
          "(school holidays). Also mentioned 2026-07-26."
      val scrubbed = SupportPrivacy.scrubForIssue(body)
      assertTrue(
        scrubbed.contains("2026-12-20"),
        scrubbed.contains("2027-01-05"),
        scrubbed.contains("2026-07-26"),
        !scrubbed.contains("[redacted-number]"),
      )
    },
    test("a run of ISO dates separated only by spaces still survives") {
      // Adjacent dates form one long digit-and-separator run, so the ≥9-digit floor alone would not
      // save them — the ISO-date-shape exemption is what does.
      val scrubbed = SupportPrivacy.scrubForIssue("blocked on 2026-12-20 2026-12-21 2026-12-22")
      assertTrue(scrubbed.contains("2026-12-20 2026-12-21 2026-12-22"))
    },
    test("genuine phone / account / card-ish runs are STILL redacted") {
      val cases = List(
        "call them on 555-123-4567 please",
        "call them on 555 123 4567 please",
        "account 123456789 is affected",
        "card 4111 1111 1111 1111 declined",
        "+1 415 555 0132 — reachable there",
      )
      assertTrue(
        cases.forall { c =>
          val s = SupportPrivacy.scrubForIssue(c)
          s.contains("[redacted-number]")
        },
        !SupportPrivacy.scrubForIssue("account 123456789 is affected").contains("123456789"),
        !SupportPrivacy.scrubForIssue("call them on 555-123-4567").contains("555-123-4567"),
      )
    },
    test("the date exemption is RANGE-checked, not shape-only — no 4-2-2 escape hatch") {
      // #2458 review: a shape-only \d{4}-\d{2}-\d{2} would exempt a 16-digit account or card
      // number chunked 4-2-2, which WAS redacted before the narrowing. This is a compensating
      // control against untrusted content, so the exemption has to be the narrow rule.
      val cases = List(
        "ref 1234-56-78 9012-34-56 on file", // month 56 / day 78 — not a date
        "ref 2026-13-01 2026-14-02 on file", // plausible shape, impossible month
        "ref 2026-01-32 2026-01-33 on file", // impossible day
        // The canonical 4111111111111111 test card chunked 4-2-2. Every part is a range-valid
        // month and day, so ONLY the year constraint stops the exemption swallowing it — it is the
        // exemption that leaks, not the year (#2458 review, round 2).
        "ref 4111-11-11 1111-11-11 on file",
      )
      assertTrue(
        cases.forall(c => SupportPrivacy.scrubForIssue(c).contains("[redacted-number]")),
      ) &&
      // …while a real date range still survives, so the tightening did not undo the fix.
      assertTrue(
        SupportPrivacy
          .scrubForIssue("ref 2026-12-31 2027-01-01 on file")
          .contains("2026-12-31 2027-01-01"),
      )
    },
    test("the other PII rules are untouched by the narrowing") {
      val s = SupportPrivacy.scrubForIssue(
        "parent@example.com on aa:bb:cc:dd:ee:ff at 192.168.10.42 / fe80::1",
      )
      assertTrue(
        s.contains("[redacted-email]"),
        s.contains("[redacted-mac]"),
        s.contains("[redacted-ip]"),
        !s.contains("parent@example.com"),
        !s.contains("192.168.10.42"),
      )
    },

    // ── #2453: URLs never reach a public issue ────────────────────────────────
    test("scrubForIssue redacts a consent capability URL out of an issue body") {
      val body = s"The customer clicked $Link and it 404'd."
      val out  = SupportPrivacy.scrubForIssue(body)
      assertTrue(
        !out.contains("g1."),
        !out.contains("/support/consent"),
        out.contains(SupportPrivacy.UrlPlaceholder),
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
        out.contains(SupportPrivacy.UrlPlaceholder),
      )
    },
    test("#2453 does not reopen #2458: a date next to a URL still survives") {
      // The two rules have to compose. URL redaction runs first, so it must not drag the
      // neighbouring date into its match, and the ISO-date exemption must still fire.
      val out = SupportPrivacy.scrubForIssue("see https://example.test/x on 2026-12-20 please")
      assertTrue(
        out.contains("2026-12-20"),
        out.contains(SupportPrivacy.UrlPlaceholder),
        !out.contains("[redacted-number]"),
      )
    },
    test("the body cap still applies after URL redaction") {
      val out = SupportPrivacy.scrubForIssue("x" * (SupportPrivacy.MaxIssueBodyChars + 500))
      assertTrue(out.endsWith(SupportPrivacy.TruncatedPlaceholder))
    },
    test("every placeholder scrubForIssue can inject is registered in Placeholders (#2458)") {
      // #2458 built `Placeholders` as the ONE list downstream dedup reads to tell scrubber output
      // from prose, and its own docstring warns the compiler will not remind you to extend it. The
      // two #2453 placeholders are the first additions since — pin the contract rather than trust
      // the comment, and pin the bracket-delimited invariant `stripPlaceholders` relies on.
      assertTrue(
        SupportPrivacy.Placeholders.contains(SupportPrivacy.UrlPlaceholder),
        SupportPrivacy.Placeholders.contains(SupportPrivacy.ConsentLinkPlaceholder),
        SupportPrivacy.Placeholders.forall(p => p.startsWith("[") && p.endsWith("]")),
        // no placeholder is a substring of another, which is what makes the unordered fold in
        // stripPlaceholders order-independent.
        SupportPrivacy.Placeholders.forall(a =>
          SupportPrivacy.Placeholders.forall(b => a == b || !a.contains(b)),
        ),
      )
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
