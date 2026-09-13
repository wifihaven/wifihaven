package wifihaven.api.feature

import wifihaven.api.metrics.MetricGuard
import zio.test.*

/**
 * #2785: the router agent's cooperative `on_tick` loop stalled on prod for 4m11s with both
 * processes alive — pushed policy unapplied, usage and events unreported — and the only trace off
 * the device was nothing at all. The agent now emits three series for that state. The agent and the
 * API deploy independently, so an off-allowlist series is dropped whole into
 * `metrics_rejected_total`, and for a stall signal that failure is invisible: a dropped counter and
 * a healthy router both render as zero. Pin the contract so a rename or a label change fails at CI.
 *
 * `step` is the agent's fixed six-value enum from `tick_guard.STEPS` (ws_apply / block_page_token /
 * blocklist_refresh / eb_refresh / usage_report / metrics_push); `router_id` and `installation_id`
 * are the bounded fleet dimensions the server attaches. Nothing per-mac, per-host or per-url may
 * ever join these series.
 */
object TickStallMetricAllowlistSpec extends ZIOSpecDefault {

  private val Fleet = Set("router_id", "installation_id")

  def spec = suite("TickStallMetricAllowlistSpec")(
    test("the tick-stall counter is allowlisted with the fleet dimensions and no others") {
      assertTrue(MetricGuard.Allowed.get("agent_tick_stall_total").contains(Fleet))
    },
    test("the slow-step counter is allowlisted with `step` plus the fleet dimensions") {
      assertTrue(
        MetricGuard.Allowed.get("agent_slow_step_total").contains(Fleet + "step"),
      )
    },
    test("the eb_/bl_ sweep-size gauge is allowlisted with the fleet dimensions") {
      assertTrue(MetricGuard.Allowed.get("eb_refresh_inventory_hosts").contains(Fleet))
    },
    test("W16's alert expression targets the series the agent actually emits") {
      // The alert sums `rate(agent_tick_stall_total[15m])` by router_id, and the
      // dashboard slices agent_slow_step_total by `step`. Both only work if the
      // allowlist admits those exact label sets — this is the same drift the
      // #2646 stale-agent-version alert was bitten by.
      assertTrue(
        MetricGuard.Allowed.get("agent_tick_stall_total").exists(_.contains("router_id")),
        MetricGuard.Allowed.get("agent_slow_step_total").exists(_.contains("step")),
      )
    },
  )
}
