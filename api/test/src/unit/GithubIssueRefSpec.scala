package wifihaven.api.unit

import wifihaven.api.support.GithubIssueClient
import zio.test.*

/**
 * #2461 — when the support agent files an issue on a customer's behalf it could only say "I've
 * filed it": `GithubIssueClient.Live` discarded GitHub's 2xx body, which already carries the
 * created issue's `number` and `html_url`. Parsing that body is the whole fix, so the parse is a
 * pure function pinned here (the Live transport's `ApiBase` is a constant, so the JSON boundary is
 * the testable seam — docs/process/testing.md reserves unit tests for exactly this).
 *
 * The load-bearing properties: a real GitHub create-issue response yields the number + browser URL
 * verbatim; a body that is truncated, non-JSON, or missing either field yields None rather than a
 * fabricated link (an over-promised issue link is worse than none); and a `html_url` outside the
 * public target repo is REJECTED — that last one is what the customer-facing "safe to hand out
 * verbatim" claim on `IssueRef` actually rests on.
 */
object GithubIssueRefSpec extends ZIOSpecDefault {

  // Abbreviated but field-accurate: GitHub's POST /repos/{repo}/issues 201 body.
  private val realResponse =
    """{"id":123456789,"node_id":"I_kwDO","number":2455,
      |"url":"https://api.github.com/repos/wifihaven/wifihaven/issues/2455",
      |"html_url":"https://github.com/wifihaven/wifihaven/issues/2455",
      |"title":"Blocking silently fails","state":"open",
      |"labels":[{"id":1,"name":"support-agent"}]}""".stripMargin

  def spec = suite("GithubIssueClient.parseCreated (#2461)")(
    test("a real create-issue response yields the number and the browser url") {
      val ref = GithubIssueClient.parseCreated(realResponse)
      assertTrue(
        ref.map(_.number).contains(2455),
        ref.map(_.url).contains("https://github.com/wifihaven/wifihaven/issues/2455"),
      )
    },
    test("a malformed / truncated / field-less body yields None, never a fabricated link") {
      assertTrue(
        GithubIssueClient.parseCreated("").isEmpty,
        GithubIssueClient.parseCreated("not json at all").isEmpty,
        GithubIssueClient.parseCreated(realResponse.take(40)).isEmpty,
        GithubIssueClient.parseCreated("""{"number":2455}""").isEmpty,
        GithubIssueClient
          .parseCreated("""{"html_url":"https://github.com/wifihaven/wifihaven/issues/2455"}""")
          .isEmpty,
      )
    },
    test("the REASON is discriminated — the operator's only signal points at the right hunt") {
      // parseCreatedDetailed exists so the caller reads WHICH condition failed off the one
      // derivation instead of re-inferring it. Both causes meter identically (`ok_no_link`), so the
      // log line built from this is the operator's sole discriminator: "unreadable body" (transient
      // / GitHub shape change) vs "foreign html_url" (repo renamed or transferred, EVERY filing
      // loses its link). Swapping the two would send them hunting the wrong thing — pin it.
      import GithubIssueClient.CreatedParse
      assertTrue(
        GithubIssueClient.parseCreated(realResponse).isDefined,
        GithubIssueClient.parseCreatedDetailed(realResponse) match {
          case CreatedParse.Parsed(ref) => ref.number == 2455
          case _                        => false
        },
        GithubIssueClient.parseCreatedDetailed("not json at all") == CreatedParse.Unreadable,
        GithubIssueClient.parseCreatedDetailed("") == CreatedParse.Unreadable,
        GithubIssueClient.parseCreatedDetailed("""{"number":2455}""") == CreatedParse.Unreadable,
        GithubIssueClient.parseCreatedDetailed(
          """{"number":2455,"html_url":"https://github.com/someone-else/private/issues/2455"}""",
        ) == CreatedParse.ForeignRepo("https://github.com/someone-else/private/issues/2455"),
      )
    },
    test("a url outside the public target repo is rejected — we only hand out our own links") {
      // The link's value is that it points at the PUBLIC wifihaven/wifihaven repo, so the customer
      // can open it. Enforce that structurally rather than trusting that the request targeted the
      // right repo: a private-repo, look-alike, or foreign-host url must never reach a customer.
      val foreign =
        (u: String) => GithubIssueClient.parseCreated(s"""{"number":2455,"html_url":"$u"}""")
      assertTrue(
        foreign("https://github.com/someone-else/private/issues/2455").isEmpty,
        foreign("https://github.com.evil.test/wifihaven/wifihaven/issues/2455").isEmpty,
        foreign("https://github.com/wifihaven/wifihaven-internal/issues/2455").isEmpty,
        foreign("https://github.com/wifihaven/wifihaven/issues/2455").nonEmpty,
      )
    },
  )
}
