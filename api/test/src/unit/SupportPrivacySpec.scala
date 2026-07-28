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
 */
object SupportPrivacySpec extends ZIOSpecDefault {

  def spec = suite("SupportPrivacy.scrubForIssue (#2458)")(
    test("ordinary calendar dates survive — the #2457 body reads as written") {
      val body =
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
  )
}
