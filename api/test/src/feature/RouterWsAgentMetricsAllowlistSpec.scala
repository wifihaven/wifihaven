package wifihaven.api.feature

import wifihaven.api.metrics.MetricGuard
import zio.*
import zio.metrics.*
import zio.test.*

/**
 * #1848: the wifihaven-ws sidecar pushes AGENT-side websocket transport metrics through the
 * existing #1205 batch transport (it has no metrics registry of its own; the agent folds its tmpfs
 * tally into the /api/router/metrics push). The cardinality firewall links the two sides: the
 * server-side guard MUST permit each series the agent names, or the whole push is rejected into
 * `metrics_rejected_total`.
 *
 * So we pin the `(name, labels)` contract for each ws agent series here — mirroring
 * [[RouterHostMetricsAllowlistSpec]] — so an accidental rename / label drop on either side fails
 * loudly at CI rather than silently on prod. `router_id` / `installation_id` are the bounded fleet
 * dimensions the server attaches; the per-series extra is `result` (a small fixed enum) or `op`
 * (the fixed envelope vocabulary). Never a per-mac / per-host dimension.
 */
object RouterWsAgentMetricsAllowlistSpec extends ZIOSpecDefault {

  private val ServerAttached: Set[String] = Set("router_id", "installation_id")

  private def hasEntry(name: String, expectedExtra: Set[String]): TestResult =
    assertTrue(MetricGuard.Allowed.get(name).contains(ServerAttached ++ expectedExtra))

  def spec = suite("RouterWsAgentMetricsAllowlistSpec")(
    test("ws_connect_total carries result (reconnect health)") {
      hasEntry("ws_connect_total", Set("result"))
    },
    test("ws_fallback_total carries result (to_http / back_to_ws)") {
      hasEntry("ws_fallback_total", Set("result"))
    },
    test("ws_state is the link gauge with no extra labels") {
      hasEntry("ws_state", Set.empty)
    },
    test("ws_frames_{sent,recv}_total carry op and only op") {
      hasEntry("ws_frames_sent_total", Set("op")) &&
      hasEntry("ws_frames_recv_total", Set("op"))
    },
    // #2037 — policy-poll dormancy. The agent emits this once per HTTP poll it
    // suppressed because the ws link was healthy; the firewall must permit the
    // name + its bounded `reason` enum, else the whole push is rejected.
    test("policy_poll_skipped_total carries reason (the dormancy gate enum)") {
      hasEntry("policy_poll_skipped_total", Set("reason"))
    },
    test("a forbidden per-mac label on a ws series is rejected") {
      val rejected = Metric.counter("metrics_rejected_total").tagged("reason", "forbidden_label")
      for {
        before <- rejected.value
        _      <- MetricGuard.counter(
          "ws_frames_sent_total",
          Map("mac" -> "aa:bb:cc:dd:ee:ff"),
        )
        after  <- rejected.value
      } yield assertTrue(after.count - before.count >= 1.0)
    },
    test("a permitted (op, router_id, installation_id) labelling is accepted") {
      val counter = Metric
        .counter("ws_frames_recv_total")
        .tagged("op", "policy")
        .tagged("router_id", "r1")
        .tagged("installation_id", "i1")
      for {
        before <- counter.value
        _      <- MetricGuard.counter(
          "ws_frames_recv_total",
          Map("op" -> "policy", "router_id" -> "r1", "installation_id" -> "i1"),
        )
        after  <- counter.value
      } yield assertTrue(after.count - before.count == 1.0)
    },
  )
}
