package wifihaven.api.unit

import wifihaven.api.support.GithubIssueClient
import wifihaven.api.support.SupportResponder
import wifihaven.api.support.SupportResponder.AgentActionResult
import zio.Chunk
import zio.json.ast.Json
import zio.json.*
import zio.test.*

import java.nio.file.{Files, Paths}

/**
 * #2461 — pins the checked-in Grafana panel that consumes `support_agent_action_total` against the
 * enum that MINTS its label values, so the two cannot drift apart silently.
 *
 * This exists because they did drift, in this very PR: splitting the issue-filing success into `ok`
 * (link read back) and `ok_no_link` (issue created, link unreadable) left the #2241 "Agent-filed
 * issues (24h)" volume feed matching `outcome="ok"` alone, so genuinely-filed issues stopped being
 * counted in the one place an operator judges whether the agent is filing too aggressively. Nothing
 * failed — the panel is JSON, the label is a string, and no test connected them.
 *
 * `AgentActionResult.SuccessLabels` is derived from `values`, so the #2458 "matched an existing
 * issue instead of filing a duplicate" case (which `IssueOutcome` is already shaped for) will widen
 * it automatically — and this spec turns that into a failing test on the dashboard rather than a
 * silent under-count discovered months later.
 *
 * Deliberately a UNIT spec (docs/process/testing.md): it asserts a pure relationship between a
 * checked-in file and a constant, with no stack to drive.
 */
object SupportMetricsContractSpec extends ZIOSpecDefault {

  private val DashboardPath = "deploy/grafana/dashboards/support.json"

  /** The panel whose count must include EVERY success label — the #2241 volume-alert feed. */
  private val VolumePanelTitlePrefix = "Agent-filed issues (24h)"

  /** #2460 — the panel that must count every resume outcome where the customer got nothing back. */
  private val DeadEndPanelTitlePrefix = "Consent grants that dead-ended"

  /** #2462 — the two "expect 0" panels for a read that FAILED rather than returning empty. */
  private val ReadFailedPanelTitlePrefix   = "Consented household reads that FAILED"
  private val OriginFailedPanelTitlePrefix = "Webhook origins we could NOT look up"

  /**
   * #2458 — the panel that must count every scan failure the duplicate check cannot recover from.
   */
  private val BlindDedupPanelTitlePrefix = "Duplicate check PERMANENTLY blind"

  /** #2667 — the panel that must count BOTH directions of a suppressed consent-adjacent message. */
  private val ExclusionPanelTitlePrefix = "Agent text suppressed beside a consent prompt"

  /**
   * Mill's cwd at test time is not the repo root, so walk up to find the checked-in dashboard — but
   * STOP at the first checkout root (`build.mill`). Worktrees live at
   * `<repo>/.claude/worktrees/<name>`, so the PARENT checkout also contains this path; without the
   * stop, a lookup starting below a worktree that lacked the file would silently validate the
   * parent checkout's dashboard and let this branch's regression pass.
   */
  private def findDashboard(p: java.nio.file.Path): Option[java.nio.file.Path] =
    if (p == null) None
    else if (Files.exists(p.resolve(DashboardPath))) Some(p.resolve(DashboardPath))
    else if (Files.exists(p.resolve("build.mill"))) None
    else findDashboard(p.getParent)

  private lazy val dashboard: Either[String, Json] =
    findDashboard(Paths.get(".").toAbsolutePath)
      .toRight(s"$DashboardPath not found by walking up from ${Paths.get(".").toAbsolutePath}")
      .flatMap(p => new String(Files.readAllBytes(p), "UTF-8").fromJson[Json])

  private def strField(obj: Json, name: String): Option[String] =
    obj.asObject.flatMap(_.get(name)).flatMap(_.asString)

  /** Every `expr` on the panels whose title matches — checked per-expr, never unioned. */
  private def panelExprs(titleMatches: String => Boolean): List[String] = {
    val panels = dashboard.toOption
      .flatMap(_.asObject)
      .flatMap(_.get("panels"))
      .flatMap(_.asArray)
      .getOrElse(Chunk.empty)
    panels.toList
      .filter(p => strField(p, "title").exists(titleMatches))
      .flatMap { p =>
        p.asObject
          .flatMap(_.get("targets"))
          .flatMap(_.asArray)
          .getOrElse(Chunk.empty)
          .toList
          .flatMap(t => strField(t, "expr"))
      }
  }

  /** Every `expr` on the volume panel. */
  private lazy val volumePanelExprs: List[String] =
    panelExprs(_.startsWith(VolumePanelTitlePrefix))

  def spec = suite("support metrics ↔ dashboard contract (#2461)")(
    test("the volume panel exists and queries the series we actually emit") {
      assertTrue(
        // A missing/unparseable dashboard fails HERE rather than at class-init, so the failure
        // reads as a test failure with a message instead of a reflective loader crash.
        dashboard.isRight,
        volumePanelExprs.nonEmpty,
        volumePanelExprs.forall(_.contains("support_agent_action_total")),
        volumePanelExprs.forall(_.contains("op=\"issue\"")),
      )
    },
    test("its outcome matcher covers EVERY success label the enum can emit") {
      // A PromQL `=~` alternation, order-insensitive: what matters is that no success value is
      // missing (which under-counts) — extra unrelated values would be a different bug.
      //
      // Checked PER EXPR, never on the union across targets: a panel with one good
      // `outcome=~"ok|ok_no_link"` target and one under-counting `outcome="ok"` target would pass a
      // union check, the good target masking the bad one.
      val matcher                             = "outcome=~\"([^\"]+)\"".r
      def labelsIn(expr: String): Set[String] =
        matcher.findAllMatchIn(expr).flatMap(_.group(1).split('|')).toSet

      val shortfalls =
        volumePanelExprs.map(e => e -> (AgentActionResult.SuccessLabels -- labelsIn(e)))
      assertTrue(shortfalls.forall(_._2.isEmpty)) &&
      assertTrue(shortfalls.nonEmpty)
    },
    test("#2462: the two failure panels select label values the enums can actually MINT") {
      // Third instance of the same drift class, and the nastiest shape of it: both panels are
      // "expect 0" stats. A panel selecting a label value nothing can ever emit reads as a
      // permanent, reassuring zero — indistinguishable from "this failure never happens" — which is
      // strictly worse than having no panel at all. So assert the selected value against the enum
      // that mints it, not against a hand-written string.
      //
      // Scope, stated honestly: this asserts the PANEL side only. Nothing here scrapes the
      // registry, so neither counter's INCREMENT is pinned by a test. What carries that gap is
      // type-enforcement, and it is not equally strong on the two chains:
      //   - webhook: SupportResponderSpec asserts `WebhookOutcome.label(outcome)` directly, and
      //     `meter` emits that exact expression — so the asserted string IS the emitted label.
      //   - household_read: SupportResponderSpec asserts the route's 500, one step removed. It is
      //     `doneE("household_read", Error)` that both produces the 500 and emits the sample via
      //     the single `AgentActionResult.label` derivation, and it is the only producer of either
      //     — so the 500 does imply the sample, by construction rather than by assertion.
      // A real scrape assertion would close it outright; that needs `PrometheusPublisher` in this
      // suite's environment (see CloudAgentDispatchFailLoudSpec's harness) and is worth doing if
      // this class of drift ever actually bites.
      val hhExprs     = panelExprs(_.startsWith(ReadFailedPanelTitlePrefix))
      val originExprs = panelExprs(_.startsWith(OriginFailedPanelTitlePrefix))
      assertTrue(
        hhExprs.nonEmpty,
        hhExprs.forall(_.contains("support_agent_action_total")),
        hhExprs.forall(_.contains("op=\"household_read\"")),
        // The refusal path returns AgentActionResult.Error; this is the label it mints.
        hhExprs.forall(
          _.contains(s"outcome=\"${AgentActionResult.label(AgentActionResult.Error)}\""),
        ),
      ) &&
      assertTrue(
        originExprs.nonEmpty,
        originExprs.forall(_.contains("support_ai_draft_total")),
        originExprs.forall(
          _.contains(
            s"outcome=\"${SupportResponder.WebhookOutcome
                .label(SupportResponder.WebhookOutcome.OriginLookupFailed)}\"",
          ),
        ),
      )
    },
    test("success is exactly ok + ok_no_link + ok_duplicate, derived from the enum") {
      // Guards the derivation itself: a new case must be classified deliberately, not default into
      // (or out of) the success set by accident. #2458 added `ok_duplicate` — the agent asked to
      // file, we matched an ALREADY-OPEN issue and handed that one back instead of creating a
      // duplicate. It is a success (the customer gets a real, canonical tracking link), so the
      // volume panel must count it: an operator judging "is the agent filing too aggressively"
      // wants the ask rate, and silently dropping the deduped asks would make the fix look like a
      // traffic collapse.
      assertTrue(AgentActionResult.SuccessLabels == Set("ok", "ok_no_link", "ok_duplicate"))
    },
    test("#2460: the dead-end panel counts EXACTLY the resume outcomes that mean 'got nothing'") {
      // Same drift class, second series: the "Consent grants that dead-ended" panel selects a
      // SUBSET of `support_consent_total{outcome}` by string. A new resume_* failure outcome added
      // without widening the panel would silently under-count customers who granted permission and
      // were still left waiting — the exact #2460 symptom, invisible on the dashboard built to
      // catch it.
      //
      // EQUALITY, not containment: an outcome in the panel but NOT in DeadEnd would count a benign
      // case (resumed / resume_skipped / resume_no_message) as a dead end and make an "expect 0"
      // panel cry wolf, which is how such a panel stops being read.
      //
      // DeadEnd is DERIVED from the enum, so a new resume outcome classified dead-end widens it
      // automatically and fails HERE — the forgotten-to-list case the hand-written set allowed.
      val matcher                             = "outcome=~\"([^\"]+)\"".r
      def labelsIn(expr: String): Set[String] =
        matcher.findAllMatchIn(expr).flatMap(_.group(1).split('|')).toSet

      val exprs = panelExprs(_.startsWith(DeadEndPanelTitlePrefix))
      assertTrue(
        exprs.nonEmpty,
        exprs.forall(_.contains("support_consent_total")),
        exprs.forall(labelsIn(_) == SupportResponder.ResumeOutcome.DeadEnd),
      )
    },
    test("#2458: the blind-dedup panel counts EXACTLY the never-self-healing scan reasons") {
      // Same drift class, third series. The expect-0 panel selects a SUBSET of
      // `support_issue_dedup_total{reason}` by string. A new never-self-healing cause added to
      // `DedupReason` without widening the panel would drop out of the ONE place a permanently
      // blind duplicate check is visible — and because the check is fail-open, everything else
      // (the filing, the volume panel) still reads healthy, so nothing else would surface it.
      //
      // EQUALITY, not containment: a TRANSIENT reason counted here would make an expect-0 panel
      // fire on ordinary GitHub timeouts, which is how such a panel stops being read.
      val matcher                             = "reason=~\"([^\"]+)\"".r
      def labelsIn(expr: String): Set[String] =
        matcher.findAllMatchIn(expr).flatMap(_.group(1).split('|')).toSet

      val exprs = panelExprs(_.startsWith(BlindDedupPanelTitlePrefix))
      assertTrue(
        exprs.nonEmpty,
        exprs.forall(_.contains("support_issue_dedup_total")),
        exprs.forall(_.contains("outcome=\"scan_error\"")),
        exprs.forall(labelsIn(_) == GithubIssueClient.DedupReason.NeverSelfHealing),
        // Guards the derivation itself — a new reason must be classified deliberately.
        GithubIssueClient.DedupReason.NeverSelfHealing == Set("permission", "schema"),
      )
    },
    test("#2667: the consent-exclusion panel counts BOTH directions of a suppressed message") {
      // Same drift class, fourth series — and the one where a silent under-count would be worst.
      // This panel is the only place a suppressed agent message beside a consent prompt is visible,
      // and it is an expect-0 security panel: an expression selecting a label value nothing can
      // emit reads as a permanent, reassuring zero, which is worse than having no panel at all.
      //
      // EQUALITY, not containment, and both directions: reply-after-prompt is the observed prod
      // shape, but prompt-after-reply is the direction an injected agent would prefer (its framing
      // lands first), so a panel watching only one of them watches the wrong half.
      val matcher                             = "outcome=~\"([^\"]+)\"".r
      def labelsIn(expr: String): Set[String] =
        matcher.findAllMatchIn(expr).flatMap(_.group(1).split('|')).toSet

      val exprs = panelExprs(_.startsWith(ExclusionPanelTitlePrefix))
      assertTrue(
        exprs.nonEmpty,
        exprs.forall(_.contains("support_consent_total")),
        exprs.forall(labelsIn(_) == SupportResponder.ExclusionOutcomes),
        // Guards the derivation: the set is mapped from AgentAction.ThreadWrites, so a third
        // thread-writing action would widen it here rather than quietly going unpanelled.
        SupportResponder.ExclusionOutcomes ==
          Set("reply_after_consent_prompt", "consent_prompt_after_reply"),
      )
    },
  )
}
