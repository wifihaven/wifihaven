package wifihaven.api.unit

import wifihaven.api.support.GithubIssueClient
import wifihaven.api.support.GithubIssueClient.OpenIssue
import wifihaven.api.support.SupportPrivacy
import zio.test.*

/**
 * #2458 — the support agent filed two near-identical issues 84 seconds apart on its first live day
 * (#2455 / #2457), from two different threads, because nothing checked whether the topic was
 * already tracked. The fix searches the open `support-agent` issues before creating one; this spec
 * pins the pure half of it — the title matcher and the list-response reader.
 *
 * The matcher is deliberately crude (topic-token Jaccard, no stemming beyond a plural fold): it
 * only has to separate "the same gap, phrased twice" from "a different gap". So the pins that
 * matter are the two failure directions, not the score itself:
 *   - the REAL #2455/#2457 pair must match (the bug we shipped);
 *   - genuinely different reports must NOT (a false match silently swallows a real report, which is
 *     strictly worse than the duplicate it prevents — a missed duplicate is only the status quo).
 *
 * `GithubIssueRefSpec` pins the create-response side of the same client.
 */
object GithubIssueDedupSpec extends ZIOSpecDefault {

  private def open(n: Int, title: String) =
    OpenIssue(n, s"https://github.com/wifihaven/wifihaven/issues/$n", title)

  // The two issues verbatim, as filed on 2026-07-26.
  private val issue2455 = "Feature request: date-range / holiday-aware schedule overrides"
  private val issue2457 =
    "Feature request: calendar-aware / date-range schedule overrides (e.g. school holidays)"

  def spec = suite("GithubIssueClient duplicate detection (#2458)")(
    test("the real #2455 / #2457 pair matches — the duplicate we actually shipped") {
      assertTrue(
        GithubIssueClient.titleSimilarity(issue2455, issue2457) >=
          GithubIssueClient.DuplicateThreshold,
        GithubIssueClient.findDuplicate(issue2457, List(open(2455, issue2455))).map(_.number) ==
          Some(2455),
      )
    },
    test("genuinely different reports do NOT match — a false match loses a real report") {
      val existing = List(
        open(2455, issue2455),
        open(2460, "Blocking silently fails on a device using iCloud Private Relay"),
        open(2470, "Feature request: per-app time limits"),
      )
      val distinct = List(
        "Feature request: per-device pause from the phone's lock screen",
        "Screen time over-counts when a device sleeps mid-session",
        "Blocklist updates take up to an hour to take effect",
        // Shares the whole boilerplate prefix with two of the above and nothing else. This is what
        // the stop-word list is for: without it every "Feature request:" title starts pre-matched.
        "Feature request: export the weekly report as CSV",
      )
      assertTrue(distinct.forall(t => GithubIssueClient.findDuplicate(t, existing).isEmpty))
    },
    test("a single shared topic word is not enough on its own") {
      // "schedule" alone must not pull an unrelated schedule-flavoured report onto #2455. The
      // two-shared-token floor is what stops a short title riding one common noun over the line.
      val existing = List(open(2455, issue2455))
      assertTrue(
        GithubIssueClient
          .findDuplicate("Schedule editor loses focus while typing", existing)
          .isEmpty,
      )
    },
    test("the OLDEST matching issue wins, whatever order GitHub returned them in") {
      // The canonical issue must not depend on GitHub's paging order — the customer's link would
      // otherwise change between two identical asks.
      val dupes = List(open(2457, issue2457), open(2455, issue2455))
      assertTrue(
        GithubIssueClient.findDuplicate(issue2455, dupes).map(_.number) == Some(2455),
        GithubIssueClient.findDuplicate(issue2455, dupes.reverse).map(_.number) == Some(2455),
      )
    },
    test("a title with fewer than two topic words never matches anything") {
      // Nothing to compare, so filing is the safe answer — the asymmetric-cost rule: refusing to
      // dedup files a second issue, matching wrongly drops a real report.
      assertTrue(
        GithubIssueClient.findDuplicate("Bug report", List(open(2455, issue2455))).isEmpty,
        GithubIssueClient.findDuplicate("Crash", List(open(2460, "Crash"))).isEmpty,
      )
    },
    test("the scrubber's own [redacted-*] words are never topic words") {
      // Titles reach the matcher AFTER SupportPrivacy.scrubForIssue, so a title that carried PII
      // arrives as "… [redacted-email]". If those placeholder words counted as topic words, two
      // unrelated reports whose only surviving words were placeholders would match EXACTLY — the
      // second customer's genuine report silently dropped and pointed at something unrelated,
      // which is worse than the duplicate this whole feature prevents (#2458 review).
      val a = SupportPrivacy.scrubForIssue("Issue for alice@example.com")
      val b = SupportPrivacy.scrubForIssue("Bug: bob@example.com")
      assertTrue(
        // Precondition: both really were scrubbed, so this is testing the real input shape.
        a.contains("[redacted-email]"),
        b.contains("[redacted-email]"),
        GithubIssueClient.titleTokens(a).isEmpty,
        GithubIssueClient.findDuplicate(b, List(open(2455, a))).isEmpty,
        // And the placeholder does not inflate a real comparison either.
        !GithubIssueClient.titleTokens("device aa:bb:cc:dd:ee:ff").contains("redacted"),
      )
    },
    test("no placeholder is a substring of another — the strip is order-independent") {
      // `stripPlaceholders` folds String.replace over an UNORDERED set, so this is the invariant
      // that makes the result deterministic. It holds for free while every placeholder is
      // bracket-DELIMITED; an unterminated entry like "[redacted" would break it silently.
      val ps         = SupportPrivacy.Placeholders.toList
      val nested     = for {
        a <- ps
        b <- ps if a != b && a.contains(b)
      } yield s"$a contains $b"
      // Guards against a VACUOUS pass — with 0 or 1 placeholders there is no pair to nest.
      val nonVacuous = ps.sizeIs > 1
      // `assert(…)(isEmpty)` rather than `assertTrue(nested.isEmpty)` so a failure prints the
      // offending pair, which is the whole reason the message strings are built.
      assertTrue(nonVacuous) && assert(nested)(Assertion.isEmpty)
    },
    test("every SupportPrivacy placeholder is stripped, whatever the list grows to") {
      // Read from SupportPrivacy.Placeholders rather than a literal, so adding a redaction rule
      // there without teaching the matcher about it fails HERE.
      assertTrue(
        SupportPrivacy.Placeholders.nonEmpty,
        SupportPrivacy.Placeholders.forall(p =>
          GithubIssueClient.titleTokens(s"alpha $p beta") == Set("alpha", "beta"),
        ),
      )
    },
    test("stripping placeholders does NOT cost us the words the agent really wrote") {
      // The narrow fix matters: dropping `mac` / `email` / `number` as stop-words instead would
      // strip them from titles that never carried PII, and in this product those are the words
      // that distinguish one report from another — "Cannot change MAC address" and "Cannot change
      // email address" would reduce to the SAME token set and the second report would be swallowed
      // (#2458 review, round 2).
      val macTitle   = "Cannot change MAC address"
      val emailTitle = "Cannot change email address"
      assertTrue(
        GithubIssueClient.titleTokens(macTitle).contains("mac"),
        GithubIssueClient.titleTokens(emailTitle).contains("email"),
        GithubIssueClient.titleTokens(macTitle) != GithubIssueClient.titleTokens(emailTitle),
        GithubIssueClient.findDuplicate(emailTitle, List(open(2455, macTitle))).isEmpty,
        // …and the same holds for the other domain-central placeholder words.
        GithubIssueClient.titleTokens("Device number shown twice").contains("number"),
      )
    },
    test("parseOpenIssues reads GitHub's list body and drops anything outside the public repo") {
      val body   =
        """[{"number":2455,"html_url":"https://github.com/wifihaven/wifihaven/issues/2455",
          | "title":"Feature request: date-range / holiday-aware schedule overrides"},
          | {"number":2456,"html_url":"https://github.com/wifihaven/wifihaven/pull/2456",
          | "title":"a pull request GitHub mixed into the issues list"},
          | {"number":2457,"html_url":"https://github.com/someone-else/private/issues/2457",
          | "title":"a foreign issue"}]""".stripMargin
      val parsed = GithubIssueClient.parseOpenIssues(body)
      assertTrue(
        parsed.map(_.number) == List(2455),
        parsed.head.title == issue2455,
        // Unreadable ⇒ NO candidates, so the caller files rather than guessing. Same discipline as
        // parseCreated: never invent, and never let a parse failure suppress a real report.
        GithubIssueClient.parseOpenIssues("not json").isEmpty,
        GithubIssueClient.parseOpenIssues("").isEmpty,
        GithubIssueClient.parseOpenIssues("[]").isEmpty,
      )
    },
    test("'unreadable' and 'readable but nothing usable' are DIFFERENT — they meter differently") {
      // The caller meters an unreadable body as a never-self-healing `schema` scan_error into an
      // expect-0 panel. Reconstructing that from `open.isEmpty` would report a perfectly good scan
      // — e.g. a `support-agent` label applied to a PULL REQUEST, whose /pull/ url is filtered —
      // as a schema drift (#2458 review). `decoded` is also the PRE-filter count, so a full page
      // holding one PR still trips the page-full warning.
      import GithubIssueClient.ListParse
      val onlyAPr =
        """[{"number":2456,"html_url":"https://github.com/wifihaven/wifihaven/pull/2456",
          | "title":"a pull request"}]""".stripMargin
      assertTrue(
        GithubIssueClient.parseOpenIssuesDetailed("not json") == ListParse.Unreadable,
        GithubIssueClient.parseOpenIssuesDetailed("") == ListParse.Unreadable,
        GithubIssueClient.parseOpenIssuesDetailed("[]") == ListParse.Parsed(Nil, 0),
        GithubIssueClient.parseOpenIssuesDetailed(onlyAPr) == ListParse.Parsed(Nil, 1),
      )
    },
  )
}
