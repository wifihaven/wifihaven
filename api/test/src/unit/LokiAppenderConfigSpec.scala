package wifihaven.api.unit

import zio.test.*

import java.nio.file.{Files, Paths}
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.{Element, Node}

/**
 * #1873 (epic #1831): pins the structural contract of the Grafana Cloud Loki appender in
 * `api/resources/logback.xml`. Logback `<if>` conditions resolve against the JVM environment, which
 * a test cannot mutate, so we assert on the *config shape* rather than a live appender instance.
 *
 *   - DEPLOYED-ENV GATING: the LOKI appender, and its root attachment, are wrapped in `<if
 *     condition='isDefined("GRAFANA_CLOUD_LOKI_URL")'>`. Local dev and `mill __.test` never set
 *     that secret, so the appender is never instantiated there; it activates only where Render
 *     injects GRAFANA_CLOUD_LOKI_*.
 *   - LABEL WHITELIST: Loki STREAM LABELS are exactly {service, env, level}. `mac` (and any other
 *     per-device/per-host MDC key) as a label would be a cardinality explosion, forbidden by the
 *     same bounded-cardinality rule as metrics. All other MDC keys must ride STRUCTURED METADATA
 *     via the `* = %%mdc` bulk pattern instead.
 *   - FAIL-OPEN: the <batch> bounds the send queue (drop-on-backpressure) and does not drain on
 *     stop, so Loki being slow/down can never wedge the request path or JVM shutdown.
 */
object LokiAppenderConfigSpec extends ZIOSpecDefault {

  private val LokiGate = "GRAFANA_CLOUD_LOKI_URL"

  private val doc = {
    // Prefer the checked-in source file; fall back to the classpath copy if the layout changes.
    val srcPath = Paths.get("api/resources/logback.xml")
    val bytes   =
      if (Files.exists(srcPath)) Files.readAllBytes(srcPath)
      else
        Option(getClass.getClassLoader.getResourceAsStream("logback.xml"))
          .getOrElse(throw new IllegalStateException("logback.xml not found on classpath"))
          .readAllBytes()
    val factory = DocumentBuilderFactory.newInstance()
    factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(bytes))
  }

  private def elements(parent: Node, tag: String): List[Element] = {
    val nodes = parent match {
      case e: Element => e.getElementsByTagName(tag)
      case _          => doc.getElementsByTagName(tag)
    }
    (0 until nodes.getLength).map(nodes.item).collect { case e: Element => e }.toList
  }

  /** True iff `node` has an `<if>` ancestor whose condition references GRAFANA_CLOUD_LOKI_URL. */
  private def gatedByLokiSecret(node: Node): Boolean = {
    var cur   = node.getParentNode
    var found = false
    while (cur != null && !found) {
      cur match {
        case e: Element if e.getTagName == "if" =>
          val cond = e.getAttribute("condition")
          if (cond.contains(LokiGate)) found = true
        case _                                  => ()
      }
      cur = cur.getParentNode
    }
    found
  }

  /** Parse a logback labels/structuredMetadata block (`key = value` lines) into its key set. */
  private def keysOf(blockText: String): Set[String] =
    blockText
      .split("\n")
      .map(_.trim)
      .filter(_.nonEmpty)
      .flatMap(line => line.split("=", 2).headOption.map(_.trim))
      .filter(_.nonEmpty)
      .toSet

  private def lokiAppender: Element =
    elements(doc, "appender")
      .find(_.getAttribute("name") == "LOKI")
      .getOrElse(throw new AssertionError("no <appender name=\"LOKI\"> in logback.xml"))

  def spec = suite("LokiAppenderConfigSpec")(
    test("the LOKI appender is gated on the GRAFANA_CLOUD_LOKI_URL secret (deployed envs only)") {
      assertTrue(gatedByLokiSecret(lokiAppender))
    },
    test("the root attaches LOKI only behind the same secret gate") {
      val lokiRefs = elements(doc, "appender-ref").filter(_.getAttribute("ref") == "LOKI")
      assertTrue(lokiRefs.nonEmpty) &&
      assertTrue(lokiRefs.forall(gatedByLokiSecret))
    },
    test("stream labels are whitelisted to exactly service/env/level") {
      val labelsEl = elements(lokiAppender, "labels").head
      val keys     = keysOf(labelsEl.getTextContent)
      assertTrue(keys == Set("service", "env", "level"))
    },
    test("mac (and other high-cardinality MDC keys) are NOT stream labels") {
      val labelsEl = elements(lokiAppender, "labels").head
      val keys     = keysOf(labelsEl.getTextContent)
      assertTrue(!keys.contains("mac")) &&
      assertTrue(!keys.contains("route")) &&
      assertTrue(!keys.contains("op")) &&
      assertTrue(!keys.contains("host"))
    },
    test("all MDC keys are routed to structured metadata via the bulk pattern") {
      val smEl = elements(lokiAppender, "structuredMetadata").head
      // `* = %%mdc` expands every MDC entry into structured metadata, not labels.
      assertTrue(smEl.getTextContent.replaceAll("\\s", "").contains("*=%%mdc"))
    },
    test("fail-open: bounded send queue (drop-on-backpressure) and no drain-on-stop") {
      val batchEl     = elements(lokiAppender, "batch").head
      val sendQueue   = elements(batchEl, "sendQueueMaxBytes").headOption.map(_.getTextContent.trim)
      val drainOnStop = elements(batchEl, "drainOnStop").headOption.map(_.getTextContent.trim)
      assertTrue(sendQueue.exists(_.toLong > 0L)) &&
      assertTrue(drainOnStop.contains("false"))
    },
    // The shared DOM `Document` is not thread-safe; run these checks serially.
  ) @@ TestAspect.sequential
}
