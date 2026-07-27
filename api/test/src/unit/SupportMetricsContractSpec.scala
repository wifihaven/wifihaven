package wifihaven.api.unit

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

  /** Every `expr` on the volume panel. */
  private lazy val volumePanelExprs: List[String] = {
    val panels = dashboard.toOption
      .flatMap(_.asObject)
      .flatMap(_.get("panels"))
      .flatMap(_.asArray)
      .getOrElse(Chunk.empty)
    panels.toList
      .filter(p => strField(p, "title").exists(_.startsWith(VolumePanelTitlePrefix)))
      .flatMap { p =>
        p.asObject
          .flatMap(_.get("targets"))
          .flatMap(_.asArray)
          .getOrElse(Chunk.empty)
          .toList
          .flatMap(t => strField(t, "expr"))
      }
  }

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
    test("success is exactly ok + ok_no_link, derived from the enum") {
      // Guards the derivation itself: a new case must be classified deliberately, not default into
      // (or out of) the success set by accident.
      assertTrue(AgentActionResult.SuccessLabels == Set("ok", "ok_no_link"))
    },
  )
}
