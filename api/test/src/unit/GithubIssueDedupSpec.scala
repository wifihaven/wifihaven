package wifihaven.api.unit

import wifihaven.api.support.GithubIssueClient
import wifihaven.api.support.GithubIssueClient.OpenIssue
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
    test("a title with no topic words at all never matches anything") {
      // All stop-words / fragments — there is nothing to compare, so filing is the safe answer.
      assertTrue(GithubIssueClient.findDuplicate("Bug report", List(open(2455, issue2455))).isEmpty)
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
  )
}
