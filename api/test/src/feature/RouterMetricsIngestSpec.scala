package wifihaven.api.feature

import wifihaven.api.MetricsConfig
import wifihaven.api.db.*
import wifihaven.api.metrics.RouterMetricsService
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.metrics.connectors.prometheus.PrometheusPublisher
import zio.test.*

/**
 * #1205 / design §3 — `POST /api/router/metrics` ingest. Drives the real route (RouterAuth +
 * routerId cross-check) against embedded Postgres and folds router-pushed cumulative counters into
 * the live Prometheus publisher that `GET /metrics` scrapes.
 *
 * Load-bearing assertions: counters are re-exposed under a bounded `router_id` label; cumulative
 * semantics mean a replayed batch never double-counts; an agent restart (changed `agentStartedAt`)
 * re-bases cleanly with no negative delta; and an unknown name / forbidden label is dropped and
 * counted in `metrics_rejected_total`, never emitted.
 */
object RouterMetricsIngestSpec
    extends ZIOSpec[
      TestDatabase.AllRepos & EmbeddedPostgres & Clock & PrometheusPublisher,
    ] {

  override val bootstrap =
    TestDatabase.layer ++
      TestLayers.withClock(TestClock.schoolDayAfternoon) ++
      wifihaven.api.metrics.MetricsRuntime.prometheus(100.millis)

  private val cleanDb = TestDatabase.cleanAndMigrate

  /** Enroll a fresh router, returning its id + the plaintext bearer token. */
  private def newRouter(name: String): ZIO[RouterRepo, Throwable, (RouterId, String)] =
    for {
      repo <- ZIO.service[RouterRepo]
      token = s"TOK_${name}_${java.util.UUID.randomUUID().toString.take(8)}"
      id <- repo.create(name, Sha256Hex.unsafe("e" * 64))
      _  <- repo.completeEnrollment(id, Sha256Hex.unsafe(RouterAuth.sha256Hex(token)))
    } yield (id, token)

  private def post(
      routes: Routes[Any, Response],
      token: String,
      body: String,
  ): ZIO[Any, Response, Response] = {
    val req = Request
      .post(URL.decode("/api/router/metrics").toOption.get, Body.fromString(body))
      .addHeader(Header.ContentType(MediaType.application.json))
      .addHeader(Header.Authorization.Bearer(token))
    routes.runZIO(req)
  }

  private def scrape: ZIO[PrometheusPublisher, Response, String] =
    for {
      pub <- ZIO.service[PrometheusPublisher]
      rs = MetricsRoutes.routes(MetricsConfig(enabled = true), pub)
      resp <- rs(Request.get("/metrics"))
      body <- resp.body.asString.orElseFail(Response.internalServerError("scrape decode failed"))
    } yield body

  /**
   * The numeric value of the first non-comment line of `metric` that contains every `mustContain`.
   * Exposition lines are `name{labels} <value> <timestampMs>`, so the value is the second-to-last
   * whitespace token when a timestamp is present (this connector always emits one).
   */
  private def seriesValue(body: String, metric: String, mustContain: String*): Option[Double] =
    body.linesIterator
      .filter(l => !l.startsWith("#") && l.startsWith(metric) && mustContain.forall(l.contains))
      .flatMap { l =>
        val toks = l.trim.split("\\s+")
        if toks.length >= 3 then toks.lift(toks.length - 2) else toks.lastOption
      }
      .flatMap(_.toDoubleOption)
      .toList
      .headOption

  private def batchJson(
      routerId: RouterId,
      agentStartedAt: String,
      counters: List[MetricCounter] = Nil,
      gauges: List[MetricGauge] = Nil,
      histograms: List[MetricHistogram] = Nil,
  ): String =
    RouterMetricsBatch(
      routerId = routerId,
      agentVersion = "0.3.1",
      agentStartedAt = agentStartedAt,
      sampledAt = "2026-05-30T14:01:00Z",
      counters = counters,
      gauges = gauges,
      histograms = histograms,
    ).toJson

  def spec = suite("router metrics ingest (#1205)")(
    test("valid batch + correct token → 200; series appear under router_id") {
      for {
        _          <- cleanDb
        routerRepo <- ZIO.service[RouterRepo]
        svc        <- RouterMetricsService.make
        (rid, tok) <- newRouter("r-ok")
        routes = RouterMetricsRoutes.routes(new RouterAuthLive(routerRepo), svc)
        body   = batchJson(
          rid,
          "2026-05-30T09:00:00Z",
          counters = List(
            MetricCounter("dnsmasq_restarts_total", Map("reason" -> "boot"), 1),
            MetricCounter("policy_apply_total", Map("result" -> "ok"), 880),
          ),
          gauges = List(MetricGauge("agent_uptime_seconds", Map.empty, 18060)),
          histograms = List(
            MetricHistogram(
              "policy_apply_duration_seconds",
              Map.empty,
              List(
                MetricHistogramBucket("0.05", 3),
                MetricHistogramBucket("0.1", 5),
                MetricHistogramBucket("+Inf", 6),
              ),
              sum = 0.4,
              count = 6,
            ),
          ),
        )
        resp <- post(routes, tok, body)
        _       <- ZIO.sleep(700.millis)
        scraped <- scrape.catchAll(r => r.body.asString.orDie)
      } yield {
        val ridStr = rid.value.toString
        assertTrue(resp.status == Status.Ok) &&
        assertTrue(
          seriesValue(scraped, "dnsmasq_restarts_total", s"""router_id="$ridStr"""", "boot")
            .contains(1.0),
        ) &&
        assertTrue(
          seriesValue(scraped, "policy_apply_total", s"""router_id="$ridStr"""", "ok")
            .contains(880.0),
        ) &&
        assertTrue(
          seriesValue(scraped, "agent_uptime_seconds", s"""router_id="$ridStr"""")
            .contains(18060.0),
        ) &&
        assertTrue(scraped.contains("policy_apply_duration_seconds"))
      }
    },
    test("batch that OMITS `labels` on a label-less series ingests + re-exposes (#1365)") {
      // Real luci.jsonc on the router serializes an empty Lua table as a JSON
      // array (`[]`), so the agent omits `labels` entirely for a label-less
      // series rather than emitting `"labels":[]` (which this decoder would
      // reject, failing the whole batch — every prod batch landed in
      // router_metrics_batches_total{status="malformed"} and nothing reached
      // /metrics, leaving the router-fleet dashboard empty). The API must
      // therefore accept a series with NO `labels` key and default it to the
      // empty map. This hand-written body mirrors the exact prod wire shape;
      // the codec-built bodies elsewhere always emit `"labels":{}` and so never
      // exercised the omitted form.
      for {
        _          <- cleanDb
        routerRepo <- ZIO.service[RouterRepo]
        svc        <- RouterMetricsService.make
        (rid, tok) <- newRouter("r-omit")
        routes = RouterMetricsRoutes.routes(new RouterAuthLive(routerRepo), svc)
        ridStr = rid.value.toString
        body   =
          s"""{
             |  "routerId": "$ridStr",
             |  "agentVersion": "0.3.1",
             |  "agentStartedAt": "2026-05-30T09:00:00Z",
             |  "sampledAt": "2026-05-30T14:01:00Z",
             |  "gauges": [ { "name": "agent_uptime_seconds", "value": 18060.0 } ],
             |  "histograms": [ {
             |    "name": "policy_apply_duration_seconds",
             |    "buckets": [ { "le": "0.05", "count": 3.0 }, { "le": "+Inf", "count": 6.0 } ],
             |    "sum": 0.4, "count": 6.0
             |  } ]
             |}""".stripMargin
        resp    <- post(routes, tok, body)
        _       <- ZIO.sleep(700.millis)
        scraped <- scrape.catchAll(r => r.body.asString.orDie)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(
          seriesValue(scraped, "agent_uptime_seconds", s"""router_id="$ridStr"""")
            .contains(18060.0),
        ) &&
        assertTrue(scraped.contains("policy_apply_duration_seconds"))
    },
    test("agent counter series (#1301/#1302/#1325) re-expose under router_id") {
      // The agent-sourced counters added after #1206: dns_queries_total{result},
      // blocklist_fetch_failures_total{status}, enforcement_drops_total{reason}.
      // All three are on the MetricGuard allowlist with their bounded enum
      // label, so the generic fold must re-expose them under router_id (the
      // #1365 re-export path) rather than rejecting them.
      for {
        _          <- cleanDb
        routerRepo <- ZIO.service[RouterRepo]
        svc        <- RouterMetricsService.make
        (rid, tok) <- newRouter("r-agent-counters")
        routes = RouterMetricsRoutes.routes(new RouterAuthLive(routerRepo), svc)
        body   = batchJson(
          rid,
          "2026-05-30T09:00:00Z",
          counters = List(
            MetricCounter("dns_queries_total", Map("result" -> "resolved"), 41200),
            MetricCounter("dns_queries_total", Map("result" -> "nxdomain"), 318),
            MetricCounter("blocklist_fetch_failures_total", Map("status" -> "5xx"), 4),
            // #1785 — per-id render-skipped counter (defensive byte-cap hit).
            MetricCounter("blocklist_render_skipped_total", Map("id" -> "ads-extended"), 7),
            MetricCounter("enforcement_drops_total", Map("reason" -> "whole_mac"), 1820),
            MetricCounter("enforcement_drops_total", Map("reason" -> "category_block"), 12990),
            // #573 SNI sidecar result counters — must be allowlisted and
            // re-exposed under router_id like the other agent-sourced series.
            MetricCounter("sni_clienthellos_total", Map("result" -> "parsed"), 8421),
            MetricCounter("sni_clienthellos_total", Map("result" -> "truncated"), 37),
          ),
        )
        resp <- post(routes, tok, body)
        _       <- ZIO.sleep(700.millis)
        scraped <- scrape.catchAll(r => r.body.asString.orDie)
      } yield {
        val ridStr = rid.value.toString
        assertTrue(resp.status == Status.Ok) &&
        assertTrue(
          seriesValue(scraped, "dns_queries_total", s"""router_id="$ridStr"""", "resolved")
            .contains(41200.0),
        ) &&
        assertTrue(
          seriesValue(scraped, "blocklist_fetch_failures_total", s"""router_id="$ridStr"""", "5xx")
            .contains(4.0),
        ) &&
        assertTrue(
          seriesValue(
            scraped,
            "blocklist_render_skipped_total",
            s"""router_id="$ridStr"""",
            "ads-extended",
          ).contains(7.0),
        ) &&
        assertTrue(
          seriesValue(scraped, "enforcement_drops_total", s"""router_id="$ridStr"""", "whole_mac")
            .contains(1820.0),
        ) &&
        assertTrue(
          seriesValue(
            scraped,
            "enforcement_drops_total",
            s"""router_id="$ridStr"""",
            "category_block",
          ).contains(12990.0),
        ) &&
        assertTrue(
          seriesValue(scraped, "sni_clienthellos_total", s"""router_id="$ridStr"""", "parsed")
            .contains(8421.0),
        ) &&
        assertTrue(
          seriesValue(scraped, "sni_clienthellos_total", s"""router_id="$ridStr"""", "truncated")
            .contains(37.0),
        )
      }
    },
    test("missing token → 401; routerId mismatch → 403") {
      for {
        _          <- cleanDb
        routerRepo <- ZIO.service[RouterRepo]
        svc        <- RouterMetricsService.make
        (rid, tok) <- newRouter("r-auth")
        routes = RouterMetricsRoutes.routes(new RouterAuthLive(routerRepo), svc)
        noTokResp <- routes
          .runZIO(
            Request.post(
              URL.decode("/api/router/metrics").toOption.get,
              Body.fromString(batchJson(rid, "2026-05-30T09:00:00Z")),
            ),
          )
        otherId = RouterId(java.util.UUID.randomUUID())
        mismatch <- post(routes, tok, batchJson(otherId, "2026-05-30T09:00:00Z"))
      } yield assertTrue(noTokResp.status == Status.Unauthorized) &&
        assertTrue(mismatch.status == Status.Forbidden)
    },
    test("replayed batch does not double-count (cumulative semantics)") {
      for {
        _          <- cleanDb
        routerRepo <- ZIO.service[RouterRepo]
        svc        <- RouterMetricsService.make
        (rid, tok) <- newRouter("r-replay")
        routes = RouterMetricsRoutes.routes(new RouterAuthLive(routerRepo), svc)
        body   = batchJson(
          rid,
          "2026-05-30T09:00:00Z",
          counters = List(MetricCounter("snapshot_poll_total", Map("result" -> "304"), 9300)),
        )
        _ <- post(routes, tok, body)
        _       <- post(routes, tok, body) // identical replay
        _       <- ZIO.sleep(700.millis)
        scraped <- scrape.catchAll(r => r.body.asString.orDie)
      } yield assertTrue(
        seriesValue(
          scraped,
          "snapshot_poll_total",
          s"""router_id="${rid.value.toString}"""",
          "304",
        ).contains(9300.0),
      )
    },
    test("agent restart (changed agentStartedAt) re-bases with no negative delta") {
      for {
        _          <- cleanDb
        routerRepo <- ZIO.service[RouterRepo]
        svc        <- RouterMetricsService.make
        (rid, tok) <- newRouter("r-restart")
        routes = RouterMetricsRoutes.routes(new RouterAuthLive(routerRepo), svc)
        genA   = batchJson(
          rid,
          "2026-05-30T09:00:00Z",
          counters = List(MetricCounter("snapshot_poll_total", Map("result" -> "200"), 100)),
        )
        genB   = batchJson(
          rid,
          "2026-05-30T12:00:00Z", // agent restarted → counters reset to 0, now at 5
          counters = List(MetricCounter("snapshot_poll_total", Map("result" -> "200"), 5)),
        )
        _ <- post(routes, tok, genA)
        _       <- post(routes, tok, genB)
        _       <- ZIO.sleep(700.millis)
        scraped <- scrape.catchAll(r => r.body.asString.orDie)
      } yield
      // 100 (gen A) + 5 (re-based gen B) = 105 — climbed, never went negative.
      assertTrue(
        seriesValue(
          scraped,
          "snapshot_poll_total",
          s"""router_id="${rid.value.toString}"""",
          "200",
        ).contains(105.0),
      )
    },
    test("unknown name / forbidden label is dropped and counted in metrics_rejected_total") {
      for {
        _          <- cleanDb
        routerRepo <- ZIO.service[RouterRepo]
        svc        <- RouterMetricsService.make
        (rid, tok) <- newRouter("r-reject")
        routes = RouterMetricsRoutes.routes(new RouterAuthLive(routerRepo), svc)
        body   = batchJson(
          rid,
          "2026-05-30T09:00:00Z",
          counters = List(
            MetricCounter("totally_made_up_metric", Map.empty, 7),
            MetricCounter("dnsmasq_restarts_total", Map("mac" -> "aa:bb:cc:dd:ee:ff"), 4),
          ),
        )
        resp <- post(routes, tok, body)
        _       <- ZIO.sleep(700.millis)
        scraped <- scrape.catchAll(r => r.body.asString.orDie)
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(!scraped.contains("totally_made_up_metric")) &&
        assertTrue(!scraped.contains("""mac="aa:bb:cc:dd:ee:ff"""")) &&
        assertTrue(scraped.contains("metrics_rejected_total")) &&
        assertTrue(
          seriesValue(scraped, "metrics_rejected_total", """reason="unknown_name"""")
            .exists(_ >= 1.0),
        )
    },
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock
}
