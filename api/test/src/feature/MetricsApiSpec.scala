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

  // Short poll interval so the publisher's background snapshot is fresh well
  // within the per-test sleep below; live clock required for the fiber to tick.
  private val pollInterval = 100.millis

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
      (for {
        _    <- probe.update(1L)
        _    <- ZIO.sleep(800.millis)
        resp <- scrape(MetricsConfig(enabled = true), None).merge
        body <- resp.body.asString
        ct = resp.header(Header.ContentType).map(_.renderedValue).getOrElse("")
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(ct.startsWith("text/plain")) &&
        assertTrue(body.contains("# HELP")) &&
        assertTrue(body.contains("# TYPE")) &&
        assertTrue(body.contains("jvm")) &&
        assertTrue(body.contains("wifihaven_test_probe_total")))
        .provide(DefaultJvmMetrics.live, MetricsRuntime.prometheus(pollInterval))
    } @@ TestAspect.withLiveClock,
    test("with a scrape token configured, no/invalid bearer is 401 and the right bearer is 200") {
      val cfg = MetricsConfig(enabled = true, scrapeToken = "s3cret-token")
      (for {
        _       <- ZIO.sleep(300.millis)
        noAuth  <- scrape(cfg, None).merge
        badAuth <- scrape(cfg, Some("wrong")).merge
        okAuth  <- scrape(cfg, Some("s3cret-token")).merge
      } yield assertTrue(noAuth.status == Status.Unauthorized) &&
        assertTrue(badAuth.status == Status.Unauthorized) &&
        assertTrue(okAuth.status == Status.Ok))
        .provide(MetricsRuntime.prometheus(pollInterval))
    } @@ TestAspect.withLiveClock,
    test("metrics.enabled = false serves no /metrics route") {
      val cfg = MetricsConfig(enabled = false)
      (for {
        pub <- ZIO.service[PrometheusPublisher]
        routes = MetricsRoutes.routes(cfg, pub)
        resp <- routes(Request.get("/metrics")).merge
      } yield assertTrue(resp.status == Status.NotFound))
        .provide(MetricsRuntime.prometheus(pollInterval))
    } @@ TestAspect.withLiveClock,
  )
}
