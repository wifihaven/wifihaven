package wifihaven.api.unit

import wifihaven.api.support.SupportResponder
import wifihaven.api.support.SupportResponder.{stripLeadingAttribution, AiReplyAttribution}
import zio.test.*

/**
 * #2456 — `SupportResponder.AiReplyAttribution` is prepended server-side onto every agent reply,
 * and the server is its SINGLE owner. Since #2441 the kickoff carries prior AI turns back as
 * history and those stored turns begin with the line, so the agent intermittently reproduces the
 * shape it sees and the customer gets the header twice (measured ~1 in 2 history-carrying replies
 * on the staging thread in the issue; it did NOT compound to three).
 *
 * That is non-deterministic model formatting, not an instruction being followed —
 * `deploy/support-agent/agent.yaml` never asks for the line — so the fix is the structural strip
 * pinned here rather than a prompt rule that would re-open on any prompt edit or model change.
 *
 * The helper is pure, so its contract is pinned at unit level. The load-bearing properties: the
 * LOOP (a doubled header, which is exactly what already sits in the Plain timeline for the replies
 * the issue documents, must collapse to nothing), the LEADING-only scope (a mid-body occurrence is
 * the agent quoting an earlier turn back to the customer — legitimate content), and the leading-
 * whitespace tolerance. The end-to-end reply path is pinned in feature/SupportResponderSpec; the
 * history-render side in unit/SupportThreadHistorySpec.
 */
object SupportAttributionSpec extends ZIOSpecDefault {

  private val body = "Set a Bedtime schedule under the profile."

  def spec = suite("stripLeadingAttribution (#2456)")(
    test("a single leading copy is dropped, the body is untouched") {
      assertTrue(stripLeadingAttribution(s"$AiReplyAttribution\n\n$body") == body)
    },
    test("TWO leading copies both collapse — the loop, not just one strip") {
      // The doubled reply is itself in the thread history now, so this is a real input shape.
      assertTrue(
        stripLeadingAttribution(s"$AiReplyAttribution\n\n$AiReplyAttribution\n\n$body") == body,
      )
    },
    test("a leading copy behind whitespace/newlines is still dropped") {
      assertTrue(stripLeadingAttribution(s"\n  \n$AiReplyAttribution\n$body") == body)
    },
    test("a MID-BODY occurrence SURVIVES — the agent quoting an earlier turn is real content") {
      val quoting = s"Earlier I said:\n\n> $AiReplyAttribution\n\n…and that still applies."
      assertTrue(stripLeadingAttribution(quoting) == quoting)
    },
    test("markdown with no attribution at all is returned unchanged apart from leading blanks") {
      assertTrue(
        stripLeadingAttribution(body) == body,
        // trailing content is never touched — only the leading run is consumed.
        stripLeadingAttribution(s"$body\n") == s"$body\n",
      )
    },
    test("a body that is NOTHING BUT the attribution line strips to empty") {
      // The route's empty-reply guard runs on the stripped text precisely because of this shape —
      // otherwise the customer gets a bare header with no answer and the agent is told Ok.
      assertTrue(
        stripLeadingAttribution(AiReplyAttribution).isEmpty,
        stripLeadingAttribution(s"\n$AiReplyAttribution\n$AiReplyAttribution\n  ").isEmpty,
      )
    },
    test("a NEAR-MISS reproduction is deliberately NOT stripped") {
      // Exact-match only: a fuzzy match would risk eating authored text that merely resembles the
      // line. Documented on the helper so "exactly once" is read as a verbatim-copy guarantee.
      val nearMiss = AiReplyAttribution.replace("🤖 ", "").replace("—", "-")
      assertTrue(
        nearMiss != AiReplyAttribution,
        stripLeadingAttribution(s"$nearMiss\n\n$body") == s"$nearMiss\n\n$body",
      )
    },
    test("the strip is idempotent — the route and the responder both apply it") {
      val once  = stripLeadingAttribution(s"$AiReplyAttribution\n\n$body")
      val twice = stripLeadingAttribution(once)
      assertTrue(once == twice, SupportResponder.AiReplyAttribution.nonEmpty)
    },
  )
}
