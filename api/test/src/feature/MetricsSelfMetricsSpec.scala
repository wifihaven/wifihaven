package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.MetricsConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.metrics.{DbMetrics, HttpMetrics, MetricsRuntime}
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

import java.time.Instant

/**
 * #1204: API self-metrics (http_requests_total / http_request_duration_seconds /
 * db_query_duration_seconds / auth_failures_total / traffic_reports_filtered_zero_bytes_total).
 * Drives the real routes (wrapped in the production [[HttpMetrics.instrument]] middleware) and the
 * real auth + ingest paths against embedded Postgres, then scrapes the live Prometheus publisher
 * used by `GET /metrics`.
 *
 * The load-bearing assertion is the templated `route` label: a DELETE of a concrete MAC must
 * surface as `route="/api/devices/:mac"`, never the concrete id, and no
 * `mac`/`device_id`/`ip`/`domain` label key may appear on the http series — that is the §4
 * cardinality firewall.
 */
object MetricsSelfMetricsSpec
    extends ZIOSpec[
      TestDatabase.AllRepos & EmbeddedPostgres & Clock,
    ] {

  // Poll interval for the Prometheus snapshot fiber; tests advance the zio TestClock by exactly
  // this much to drive one deterministic scrape.
  private val pollInterval = 100.millis

  // #2042: deterministically fire ONE scrape of the Prometheus publisher fiber. The publisher
  // layer is provided PER TEST (suite-level `provideSomeLayer` below, not `bootstrap`) so the
  // snapshot fiber is a supervised descendant of the test fiber — that is what lets `TestClock`
  // await it. We wait until it parks on the TestClock, then advance exactly one interval, which
  // fires exactly one registry snapshot. (A bootstrap-scope fiber is invisible to
  // `TestClock.adjust`'s `awaitSuspended`, so a bare advance would race the scrape — the original
  // flake.)
  private val tickPublisher: UIO[Unit] =
    zio.test.TestClock.sleeps.repeatUntil(_.nonEmpty) *> zio.test.TestClock.adjust(pollInterval)

  override val bootstrap =
    TestDatabase.layer ++
      TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val adminJwt = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)
  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, adminJwt, clock)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private def scrape: ZIO[PrometheusPublisher, Response, String] =
    for {
      pub <- ZIO.service[PrometheusPublisher]
      routes = MetricsRoutes.routes(MetricsConfig(enabled = true), pub)
      resp <- routes(Request.get("/metrics"))
      body <- resp.body.asString.orElseFail(
        Response.internalServerError("scrape body decode failed"),
      )
    } yield body

  /**
   * Lines of the exposition whose series name matches `metric` (ignores HELP/TYPE comment lines).
   */
  private def seriesLines(body: String, metric: String): List[String] =
    body.linesIterator.filter(l => !l.startsWith("#") && l.startsWith(metric)).toList

  def spec = suite("API self-metrics (#1204)")(
    test(
      "exercising routes + auth + ingest emits http/db/auth/zero-byte series with a templated route label and no high-cardinality labels",
    ) {
      val concreteMac = "aa:bb:cc:dd:ee:ff"
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        deviceRepo  <- ZIO.service[DeviceRepo]
        upRepo      <- ZIO.service[UserProfileRepo]
        routerRepo  <- ZIO.service[RouterRepo]
        auth        <- makeAuth
        token       <- auth.login("admin", "changeme").map(_.token.value)
        profiles    <- profileRepo.listAll
        kidsId = profiles.find(_.name == "Kids").get.id
        _ <- deviceRepo.upsert(MacAddress.unsafe(concreteMac), "iPad", Some(kidsId), "192.168.1.50")

        // Real router-ingest routes so a zero-byte usage record is dropped + counted (#864).
        ingestAuth = new RouterAuthLive(routerRepo)
        tu       <- ZIO.service[TimeUsageRepo]
        connR    <- ZIO.service[ConnectionEventRepo]
        alertR   <- ZIO.service[AlertRepo]
        hsR      <- ZIO.service[HouseholdSettingsRepo]
        trafficR <- ZIO.service[TrafficReportRepo]
        routerToken = "ROUTER_TOKEN_PLAIN"
        routerId <- routerRepo.create("r1", Sha256Hex.unsafe("m" * 64))
        _        <- routerRepo.completeEnrollment(
          routerId,
          Sha256Hex.unsafe(RouterAuth.sha256Hex(routerToken)),
        )

        // The whole real route surface, wrapped in the production HTTP metrics middleware.
        routes = HttpMetrics.instrument(
          DeviceRoutes.routes(auth, deviceRepo, upRepo, profileRepo) ++
            RouterIngestRoutes
              .routes(ingestAuth, routerRepo, trafficR, tu, deviceRepo, connR, alertR, hsR),
        )

        // GET /api/devices → device.listAll (db metric) + http metric.
        getReq = Request
          .get(URL.decode("/api/devices").toOption.get)
          .addHeader(Header.Authorization.Bearer(token))
        _ <- routes.runZIO(getReq)

        // DELETE /api/devices/<concrete mac> → http metric with templated route label.
        delReq = Request
          .delete(URL.decode(s"/api/devices/$concreteMac").toOption.get)
          .addHeader(Header.Authorization.Bearer(token))
        _ <- routes.runZIO(delReq)

        // A bad-password login → auth_failures_total{reason="bad_password"}.
        _ <- auth.login("admin", "totally-wrong").either

        // A zero-byte usage record → traffic_reports_filtered_zero_bytes_total.
        zeroRec   = UsageRecord(
          MacAddress.unsafe(concreteMac),
          Some(IpAddress.unsafe("192.168.1.50")),
          HostId.Fqdn(Hostname.unsafe("example.com")),
          0L,
          0L,
          0L,
        )
        usageBody = UsageReport(
          routerId,
          Instant.parse("2026-05-07T14:00:00Z").toString,
          Instant.parse("2026-05-07T14:05:00Z").toString,
          List(zeroRec),
        ).toJson
        usageReq  = Request
          .post(URL.decode("/api/router/usage").toOption.get, Body.fromString(usageBody))
          .addHeader(Header.ContentType(MediaType.application.json))
          .addHeader(Header.Authorization.Bearer(routerToken))
        _ <- routes.runZIO(usageReq)

        // Advance one poll interval so the publisher fiber (on the TestClock) snapshots the registry.
        _    <- tickPublisher
        body <- scrape.catchAll(resp => resp.body.asString.orDie)

        httpLines = seriesLines(body, "http_requests_total")
      } yield
      // All five new series are present.
      assertTrue(body.contains("http_requests_total")) &&
        assertTrue(body.contains("http_request_duration_seconds")) &&
        assertTrue(body.contains("db_query_duration_seconds")) &&
        assertTrue(body.contains("auth_failures_total")) &&
        assertTrue(body.contains("""reason="bad_password"""")) &&
        assertTrue(body.contains("traffic_reports_filtered_zero_bytes_total")) &&
        // The DELETE surfaces under the templated route, never the concrete id.
        assertTrue(httpLines.exists(_.contains("""route="/api/devices/:mac""""))) &&
        assertTrue(!httpLines.exists(_.contains(concreteMac))) &&
        // No forbidden high-cardinality label key on the http series.
        assertTrue(!httpLines.exists(_.contains("mac="))) &&
        assertTrue(!httpLines.exists(_.contains("device_id="))) &&
        assertTrue(!httpLines.exists(_.contains("ip="))) &&
        assertTrue(!httpLines.exists(_.contains("domain="))) &&
        assertTrue(!httpLines.exists(_.contains("path=")))
    },
    test(
      "DbMetrics.timed records db_queries_total{op,status} — ok on success, error on failure — so the per-op DB success-rate panel has data",
    ) {
      for {
        _    <- DbMetrics.timed("test.dbOk")(ZIO.succeed(1))
        // A failing query must still be counted, tagged status=error (not swallowed).
        _    <- DbMetrics.timed("test.dbErr")(ZIO.fail(new RuntimeException("boom"))).either
        _    <- tickPublisher
        body <- scrape.catchAll(resp => resp.body.asString.orDie)
        lines = seriesLines(body, "db_queries_total")
      } yield assertTrue(
        lines.exists(l => l.contains("""op="test.dbOk"""") && l.contains("""status="ok"""")),
      ) &&
        assertTrue(
          lines.exists(l => l.contains("""op="test.dbErr"""") && l.contains("""status="error"""")),
        ) &&
        // The error op must NOT have logged an ok sample (the failure is attributed correctly).
        assertTrue(
          !lines.exists(l => l.contains("""op="test.dbErr"""") && l.contains("""status="ok"""")),
        )
    },
  ).provideSomeLayer[TestDatabase.AllRepos & EmbeddedPostgres & Clock](
    MetricsRuntime.prometheus(pollInterval),
  ) @@ TestAspect.sequential
}
