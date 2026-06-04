package wifihaven.api.feature

import wifihaven.api.metrics.MetricGuard
import zio.*
import zio.metrics.*
import zio.test.*

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*

/**
 * #1210: the cardinality review gate, made a standing automated check rather than a one-time
 * review. The gate is the §4 hard rule from `docs/design/metrics-observability.md`: a metric label
 * may only exist if its value-set is bounded, small, and known at code-write time.
 *
 * The defenses this spec locks in:
 *
 *   1. Every metric series emitted in `api/src` goes through the [[MetricGuard]] allowlist — a new
 *      series added with a bare `Metric.counter/gauge/histogram(...)` that bypasses the firewall
 *      fails the build until it is allowlisted (or routed through the guard). 2. Every metric name
 *      the OpenWRT agent pushes is in the allowlist, so a new agent counter can't silently land in
 *      `metrics_rejected_total` on prod. 3. No allowlisted series may carry a label key outside the
 *      small, known [[MetricGuard.KnownLabelKeys]] vocabulary — introducing *any* new label key
 *      forces a deliberate edit there, which is the review checkpoint. Forbidden keys (§4.3) can
 *      never be in that vocabulary. 4. The firewall actually rejects (and counts) a
 *      deliberately-unbounded label and an unknown name — proving the gate works, not just that it
 *      is configured.
 */
object MetricCardinalityGuardSpec extends ZIOSpecDefault {

  /** Walk up from the test's working directory to the repo root (the dir containing `api/src`). */
  private val repoRoot: Path = {
    var cur: Path = Paths.get(sys.props.getOrElse("user.dir", ".")).toAbsolutePath
    while cur != null && !Files.isDirectory(cur.resolve("api/src")) do cur = cur.getParent
    if cur == null then sys.error("could not locate repo root (api/src) from user.dir")
    cur
  }

  private def readAll(p: Path): String = new String(Files.readAllBytes(p))

  private def scalaFilesUnder(rel: String): List[Path] = {
    val dir = repoRoot.resolve(rel)
    if !Files.isDirectory(dir) then Nil
    else
      Files
        .walk(dir)
        .iterator()
        .asScala
        .filter(p => Files.isRegularFile(p) && p.toString.endsWith(".scala"))
        .toList
  }

  // Bare `Metric.counter("name"...)` / `.gauge` / `.histogram` / `.summary` / `.frequency` with a
  // *string-literal* name. The guard's own internals use a `name` variable (no quote), so they
  // don't match — only hand-written literal series do.
  private val BareEmit =
    """Metric\.(?:counter|gauge|histogram|summary|frequency)\(\s*"([^"]+)"""".r

  // `metrics.inc_counter(reg, "name", ...)` / `set_gauge` / `observe` in the Lua agent.
  private val AgentEmit =
    """metrics\.(?:inc_counter|set_gauge|observe)\(\s*[A-Za-z_][A-Za-z0-9_]*\s*,\s*"([^"]+)"""".r

  // The firewall's own reject counter is emitted with a bare literal by design — it is the gate, so
  // it does not itself live in the allowlist.
  private val GuardInternal = Set("metrics_rejected_total")

  def spec = suite("MetricCardinalityGuardSpec")(
    test("every bare Metric.* series in api/src is allowlisted (no firewall bypass)") {
      val names     =
        scalaFilesUnder("api/src")
          .flatMap(p => BareEmit.findAllMatchIn(readAll(p)).map(_.group(1)))
          .toSet
      val unallowed = names -- MetricGuard.Allowed.keySet -- GuardInternal
      assertTrue(unallowed.isEmpty)
    },
    test("every metric name the OpenWRT agent emits is allowlisted") {
      val agent     = repoRoot.resolve("openwrt/files/usr/sbin/wifihaven-agent")
      val names     =
        if Files.exists(agent) then AgentEmit.findAllMatchIn(readAll(agent)).map(_.group(1)).toSet
        else Set.empty[String]
      val unallowed = names -- MetricGuard.Allowed.keySet
      assertTrue(names.nonEmpty) && assertTrue(unallowed.isEmpty)
    },
    test("no allowlisted series carries a label key outside the known vocabulary") {
      val unknownKeys = MetricGuard.Allowed.values.flatten.toSet -- MetricGuard.KnownLabelKeys
      assertTrue(unknownKeys.isEmpty)
    },
    test("the known label vocabulary never overlaps a forbidden key") {
      assertTrue((MetricGuard.KnownLabelKeys & MetricGuard.ForbiddenKeys).isEmpty)
    },
    test("a deliberately-unbounded (forbidden) label is rejected and counted") {
      val rejected = Metric.counter("metrics_rejected_total").tagged("reason", "forbidden_label")
      for {
        before <- rejected.value
        // `http_requests_total` allows {route,method,status} — `mac` is a forbidden per-device key.
        _      <- MetricGuard.counter("http_requests_total", Map("mac" -> "aa:bb:cc:dd:ee:ff"))
        after  <- rejected.value
        // `>= 1` rather than `== 1`: the reject counter is a global registry series, so a spec
        // running concurrently could also increment it between the two reads.
      } yield assertTrue(after.count - before.count >= 1.0)
    },
    test("an unknown metric name is rejected and counted") {
      val rejected = Metric.counter("metrics_rejected_total").tagged("reason", "unknown_name")
      for {
        before <- rejected.value
        _      <- MetricGuard.counter("totally_made_up_series_total", Map.empty)
        after  <- rejected.value
      } yield assertTrue(after.count - before.count >= 1.0)
    },
  )
}
