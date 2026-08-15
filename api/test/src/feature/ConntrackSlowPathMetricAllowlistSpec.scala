package wifihaven.api.feature

import wifihaven.api.metrics.MetricGuard
import zio.test.*

/**
 * #2719: the OpenWRT agent bounds its conntrack DNS-attribution-miss path and emits
 * `conntrack_slow_path_capped_total{reason}` when the ceiling trips. The agent and the API deploy
 * independently, so the server-side allowlist has to permit the exact `(name, labels)` the agent
 * names — an off-allowlist series is dropped whole into `metrics_rejected_total`, and this
 * particular signal going missing is indistinguishable from the healthy steady state of zero. Pin
 * the contract here so a rename or a label change fails at CI instead of silently on prod.
 *
 * `reason` is the fixed two-value enum the agent emits (`probes` | `deadline`); `router_id` and
 * `installation_id` are the bounded fleet dimensions the server attaches itself. Nothing per-mac,
 * per-host, or per-destination may ever join this series.
 */
object ConntrackSlowPathMetricAllowlistSpec extends ZIOSpecDefault {

  def spec = suite("ConntrackSlowPathMetricAllowlistSpec")(
    test("the slow-path ceiling counter is allowlisted with `reason` and the fleet dimensions") {
      assertTrue(
        MetricGuard.Allowed
          .get("conntrack_slow_path_capped_total")
          .contains(Set("reason", "router_id", "installation_id")),
      )
    },
  )
}
