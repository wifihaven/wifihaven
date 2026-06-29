package wifihaven.api.feature

import wifihaven.api.MetricsConfig
import wifihaven.api.metrics.MetricsRuntime
import wifihaven.api.routes.MetricsRoutes
import zio.*
import zio.http.*
import zio.metrics.*
import zio.metrics.connectors.prometheus.PrometheusPublisher
import zio.metrics.jvm.DefaultJvmMetrics
import zio.test.*

// #1242: the Prometheus /metrics exposition endpoint. Drives the real route
// against a live publisher + JVM metrics, so the assertions exercise the same
// registry → publisher → text-exposition path production uses.
object MetricsApiSpec extends ZIOSpecDefault {

  // Poll interval for the publisher's background snapshot fiber; tests advance
  // the TestClock by exactly this much to drive a deterministic scrape.
  private val pollInterval = 100.millis

  // #2042: deterministically fire ONE scrape of the Prometheus publisher fiber (provided per-test
  // via `.provide` below, so it is a supervised test descendant). The key is waiting until that
  // background fiber has actually re-parked on the TestClock (its `Schedule.fixed` sleep is
  // registered) BEFORE advancing — under parallel CI load a bare `adjust` can slip past a fiber
  // that's momentarily mid-flight and never trigger the scrape (the original flake, relocated).
  // Once it's parked, advancing exactly one interval fires exactly one registry snapshot.
  private val tickPublisher: UIO[Unit] =
    zio.test.TestClock.sleeps.repeatUntil(_.nonEmpty) *> zio.test.TestClock.adjust(pollInterval)

  private def scrape(
      cfg: MetricsConfig,
      auth: Option[String],
  ): ZIO[PrometheusPublisher, Response, Response] =
    for {
      pub <- ZIO.service[PrometheusPublisher]
      routes = MetricsRoutes.routes(cfg, pub)
      base   = Request.get("/metrics")
      req    = auth.fold(base)(t => base.addHeader(Header.Authorization.Bearer(t)))
      resp <- routes(req)
    } yield resp

  def spec = suite("Metrics /metrics exposition (#1242)")(
    test(
      "GET /metrics returns 200 text/plain with HELP/TYPE, a runtime metric, and a custom metric",
    ) {
      val probe = Metric.counter("wifihaven_test_probe_total")
      ZIO
        .scoped {
          for {
            // Build DefaultJvmMetrics.live explicitly so its collectors run and the
            // jvm_* series register. Its output is Unit, which ZIO would prune if
            // it were just listed in `.provide`.
            _    <- DefaultJvmMetrics.live.build
            _    <- probe.update(1L)
            // Drive one deterministic snapshot of the publisher fiber (runs on the
            // TestClock) so the exposition reflects the metrics registered above.
            _    <- tickPublisher
            resp <- scrape(MetricsConfig(enabled = true), None).merge
            body <- resp.body.asString
            ct = resp.header(Header.ContentType).map(_.renderedValue).getOrElse("")
          } yield assertTrue(resp.status == Status.Ok) &&
            assertTrue(ct.startsWith("text/plain")) &&
            assertTrue(body.contains("# HELP")) &&
            assertTrue(body.contains("# TYPE")) &&
            assertTrue(body.contains("jvm")) &&
            assertTrue(body.contains("wifihaven_test_probe_total"))
        }
        .provide(MetricsRuntime.prometheus(pollInterval))
    },
    test("with a scrape token configured, no/invalid bearer is 401 and the right bearer is 200") {
      val cfg = MetricsConfig(enabled = true, scrapeToken = "s3cret-token")
      // Auth gating is evaluated before the exposition is rendered, so this test
      // doesn't depend on a snapshot having run — no clock advance needed.
      (for {
        noAuth  <- scrape(cfg, None).merge
        badAuth <- scrape(cfg, Some("wrong")).merge
        okAuth  <- scrape(cfg, Some("s3cret-token")).merge
      } yield assertTrue(noAuth.status == Status.Unauthorized) &&
        assertTrue(badAuth.status == Status.Unauthorized) &&
        assertTrue(okAuth.status == Status.Ok))
        .provide(MetricsRuntime.prometheus(pollInterval))
    },
    test("metrics.enabled = false serves no /metrics route") {
      val cfg = MetricsConfig(enabled = false)
      (for {
        pub <- ZIO.service[PrometheusPublisher]
        routes = MetricsRoutes.routes(cfg, pub)
        resp <- routes(Request.get("/metrics")).merge
      } yield assertTrue(resp.status == Status.NotFound))
        .provide(MetricsRuntime.prometheus(pollInterval))
    },
  )
}
