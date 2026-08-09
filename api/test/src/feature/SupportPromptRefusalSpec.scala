package wifihaven.api.feature

import zio.test.*

import java.nio.file.{Files, Path, Paths}

/**
 * #2671 — the support prompt's honest-refusal instruction exists.
 *
 * The one thing a prompt edit can lose silently is a whole instruction. `docs/ops/support-data-
 * consent.md` and `docs/ops/prod-beta-validation-runbook.md` both cite "step 6b" by label as the
 * reason a data-access session's refusal is observed in the REPLY rather than in
 * `support_agent_action_total{op="issue",outcome="denied_data_session"}` — so a later rewrite that
 * drops the step would leave two operator docs describing a behaviour nothing instructs, and the
 * only detector would be a human reading a support transcript (which is how #2671 was found).
 *
 * Deliberately narrow. This pins that the step is PRESENT and still turns on the public-tracker
 * conflict; it does not pin wording, tone, or ordering — asserting on prose would freeze phrasing
 * without checking behaviour, and the behaviour lives in a model answering a live thread. The
 * server-side guard (#2454, `SupportResponder.agentFileIssue`) remains the actual control; this is
 * only about the explanation the customer gets.
 */
object SupportPromptRefusalSpec extends ZIOSpecDefault {

  /** Walk up from the test's working directory to the repo root (the dir containing `api/src`). */
  private val repoRoot: Path = {
    var cur: Path = Paths.get(sys.props.getOrElse("user.dir", ".")).toAbsolutePath
    while cur != null && !Files.isDirectory(cur.resolve("api/src")) do cur = cur.getParent
    if cur == null then sys.error("could not locate repo root (api/src) from user.dir")
    cur
  }

  private val prompt: String =
    new String(Files.readAllBytes(repoRoot.resolve("deploy/support-agent/agent.yaml")))

  /**
   * The `6b.` step, from its label to the blank line that ends it. Two assumptions, both of which
   * fail CLOSED rather than silently widening the window: the label is line-initial (step 5's
   * cross-reference writes it `(6b)`, without the period, so it is not matched — a later edit that
   * wrote `see 6b.` would retarget this to step 5, whose text satisfies neither assertion below),
   * and the step is one blank-line-delimited paragraph.
   */
  private val stepSixB: String = {
    val start = prompt.indexOf("\n  6b.") match {
      case -1 => -1
      case i  => i + 3
    }
    if start < 0 then ""
    else {
      val rest = prompt.substring(start)
      val end  = rest.indexOf("\n\n")
      if end < 0 then rest else rest.substring(0, end)
    }
  }

  def spec = suite("SupportPromptRefusalSpec")(
    test("the support prompt still carries a 6b. step") {
      assertTrue(stepSixB.nonEmpty)
    },
    test("6b. turns on the public-tracker conflict, not on process") {
      // The whole point of #2671: the customer is told WHY — that a session holding account data
      // cannot publish to a public tracker — rather than that a human is "the right path".
      val text = stepSixB.toLowerCase
      assertTrue(text.contains("public")) &&
      assertTrue(text.contains("data access"))
    },
  )
}
