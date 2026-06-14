package wifihaven.api.feature

import wifihaven.api.metrics.MetricGuard
import zio.*
import zio.metrics.*
import zio.test.*

/**
 * #1718: the router agent will start pushing native OpenWRT OS-level metrics (load / cpu / mem /
 * iface bandwidth / conntrack / wifi clients) through the existing #1205 batch transport. The
 * agent-side emit lands in a follow-up PR; this PR is the API-side allowlist + Grafana panels.
 *
 * The link between the two PRs is the cardinality firewall: when the agent (follow-up) names a
 * series, the server-side guard MUST already permit it or the entire push gets rejected and
 * silently lands in `metrics_rejected_total`. So we pin the `(name, labels)` contract for each new
 * series here, in the PR that ships the allowlist — that way the follow-up agent PR can rely on
 * these names without re-coordinating, and any accidental rename / label drop on this side fails
 * loudly at CI rather than silently on prod.
 *
 * Cardinality firewall reminder (AGENTS.md §instrument-new-functionality + the §4 firewall):
 * `router_id` / `installation_id` are bounded fleet dimensions the server attaches itself; `iface`
 * and `ssid` are bounded by router hardware/config (handful per device). Per-MAC wireless client
 * data is explicitly NOT a Prometheus dimension — only the aggregate `router_host_wifi_clients`
 * count per (iface, ssid) ships as a labeled series. Any per-client drill-in is out of scope here.
 */
object RouterHostMetricsAllowlistSpec extends ZIOSpecDefault {

  private val ServerAttached: Set[String] = Set("router_id", "installation_id")

  private def hasEntry(name: String, expectedExtra: Set[String]): TestResult =
    assertTrue(
      MetricGuard.Allowed.get(name).contains(ServerAttached ++ expectedExtra),
    )

  def spec = suite("RouterHostMetricsAllowlistSpec")(
    test("load avg gauges are allowlisted (router_id + installation_id, no other labels)") {
      hasEntry("router_host_load_m1", Set.empty) &&
      hasEntry("router_host_load_m5", Set.empty) &&
      hasEntry("router_host_load_m15", Set.empty)
    },
    test("cpu percent gauge is allowlisted") {
      hasEntry("router_host_cpu_pct", Set.empty)
    },
    test("memory gauges are allowlisted") {
      hasEntry("router_host_mem_total_kb", Set.empty) &&
      hasEntry("router_host_mem_free_kb", Set.empty) &&
      hasEntry("router_host_mem_buffers_kb", Set.empty) &&
      hasEntry("router_host_mem_cached_kb", Set.empty)
    },
    test("conntrack count gauge is allowlisted") {
      hasEntry("router_host_conntrack_count", Set.empty)
    },
    test("per-iface bandwidth counters carry `iface` and only `iface`") {
      hasEntry("router_host_iface_rx_bytes_total", Set("iface")) &&
      hasEntry("router_host_iface_tx_bytes_total", Set("iface"))
    },
    test("wifi clients gauge carries `iface` and `ssid` — NEVER `mac` (cardinality firewall)") {
      hasEntry("router_host_wifi_clients", Set("iface", "ssid"))
    },
    test("a forbidden per-MAC label on the wifi-clients gauge is rejected") {
      val rejected = Metric.counter("metrics_rejected_total").tagged("reason", "forbidden_label")
      for {
        before <- rejected.value
        _      <- MetricGuard.gauge(
          "router_host_wifi_clients",
          Map("mac" -> "aa:bb:cc:dd:ee:ff"),
          1.0,
        )
        after  <- rejected.value
      } yield assertTrue(after.count - before.count >= 1.0)
    },
    test("a permitted (iface, ssid) labelling on the wifi-clients gauge is accepted") {
      // The reject counter is a global registry series that concurrent specs also touch, so
      // we cannot use a no-drift assertion safely. Verify acceptance by reading the gauge value
      // back — if MetricGuard had rejected the call it would stay at its pre-emit value.
      val gauge = Metric
        .gauge("router_host_wifi_clients")
        .tagged("iface", "phy0-ap0")
        .tagged("ssid", "AcceptedTestSsid")
        .tagged("router_id", "r1")
        .tagged("installation_id", "i1")
      for {
        _   <- MetricGuard.gauge(
          "router_host_wifi_clients",
          Map(
            "iface"           -> "phy0-ap0",
            "ssid"            -> "AcceptedTestSsid",
            "router_id"       -> "r1",
            "installation_id" -> "i1",
          ),
          3.0,
        )
        got <- gauge.value
      } yield assertTrue(got.value == 3.0)
    },
  )
}
