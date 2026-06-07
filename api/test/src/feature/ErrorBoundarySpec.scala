package wifihaven.api.feature

import wifihaven.api.ErrorBoundary
import wifihaven.api.MetricsConfig
import wifihaven.api.db.*
import wifihaven.api.metrics.MetricsRuntime
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.metrics.connectors.prometheus.PrometheusPublisher
import zio.test.*

import java.time.Instant
import java.util.UUID

/**
 * #1570: the centralized error handler.
 *
 *   1. [[ErrorMapper.errorToResponse]] maps each typed [[ApiError]] to the EXACT status + body the
 *      hand-rolled code produced before (wire back-compat: the agent branches on status, the SPA
 *      renders the body text). 2. [[ErrorBoundary.observe]] LOGS (4xx → WARN, 5xx → ERROR, with a
 *      bounded body snippet) and METERS (`api_errors_total{route,status}`) every error response,
 *      regardless of whether the route returned it or failed with it. 3. Regression pin for #1569:
 *      a usage decode failure is now LOGGED (the usage route used to map the decode error into the
 *      400 body but never log it — invisible during the incident).
 */
object ErrorBoundarySpec
    extends ZIOSpec[
      TestDatabase.AllRepos & EmbeddedPostgres & Clock & PrometheusPublisher,
    ] {

  // Pin Clock so PolicyService date math in the ingest path is stable (matches RouterIngestSpec).
  private val testClockAt = java.time.LocalDateTime.of(2026, 5, 7, 14, 0, 0)

  override val bootstrap =
    TestDatabase.layer ++
      TestLayers.withClock(testClockAt) ++
      MetricsRuntime.prometheus(100.millis)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private def scrape: ZIO[PrometheusPublisher, Response, String] =
    for {
      pub <- ZIO.service[PrometheusPublisher]
      routes = MetricsRoutes.routes(MetricsConfig(enabled = true), pub)
      resp <- routes(Request.get("/metrics"))
      body <- resp.body.asString.orElseFail(Response.internalServerError("scrape decode failed"))
    } yield body

  private def errorLines(body: String, route: String, status: Int): List[String] =
    body.linesIterator
      .filter(l => !l.startsWith("#") && l.startsWith("api_errors_total"))
      .filter(l => l.contains(s"""route="$route"""") && l.contains(s"""status="$status""""))
      .toList

  private def bodyText(r: Response): UIO[String] = r.body.asString.orElseSucceed("")

  // ── synthetic routes exercising the boundary directly ───────────────────────
  private val syntheticRoutes: Routes[Any, Response] =
    ErrorBoundary.observe(
      Routes(
        // returns a 4xx on the SUCCESS channel (un-migrated "build Response inline" style)
        Method.GET / "api" / "eb" / "client" ->
          handler(Response.badRequest("synthetic client error")),
        // returns a 5xx on the success channel (the DB-503 mapping shape)
        Method.GET / "api" / "eb" / "server" ->
          handler(ErrorMapper.dbErrorToResponse(new RuntimeException("kaboom"))),
        // fails on the ERROR channel with a Response (migrated routes do this, pre-mapping)
        Method.GET / "api" / "eb" / "fail"   ->
          handler((_: Request) => ZIO.fail(Response.notFound("nope"))),
        // a clean 2xx — must NOT log or meter an error
        Method.GET / "api" / "eb" / "ok"     ->
          handler(Response.ok),
      ),
    )

  // Routes[Any, Response].runZIO converts a failed-with-Response handler into a successful
  // Response, so the error channel is Nothing here — the boundary has already logged/metered by
  // the time the Response surfaces.
  private def runSynthetic(path: String) =
    syntheticRoutes.runZIO(Request.get(URL.decode(path).toOption.get))

  // ── real ingest routes wrapped in the boundary (the #1569 incident surface) ──
  private def buildIngest =
    for {
      rRepo <- ZIO.service[RouterRepo]
      tRepo <- ZIO.service[TrafficReportRepo]
      tu    <- ZIO.service[TimeUsageRepo]
      dRepo <- ZIO.service[DeviceRepo]
      cRepo <- ZIO.service[ConnectionEventRepo]
      aRepo <- ZIO.service[AlertRepo]
      hsr   <- ZIO.service[HouseholdSettingsRepo]
      auth = new RouterAuthLive(rRepo)
    } yield ErrorBoundary.observe(
      RouterIngestRoutes.routes(auth, rRepo, tRepo, tu, dRepo, cRepo, aRepo, hsr),
    )

  private def seedRouter(rRepo: RouterRepo): Task[(RouterId, String)] = {
    val token = "ROUTER_TOKEN_PLAIN"
    for {
      id <- rRepo.create("test-router", Sha256Hex.unsafe("m" * 64))
      _  <- rRepo.completeEnrollment(id, Sha256Hex.unsafe(RouterAuth.sha256Hex(token)))
    } yield (id, token)
  }

  private def postIngest(routes: Routes[Any, Response], path: String, body: String, token: String) =
    routes
      .runZIO(
        Request
          .post(URL.decode(path).toOption.get, Body.fromString(body))
          .addHeader(Header.ContentType(MediaType.application.json))
          .addHeader(Header.Authorization.Bearer(token)),
      )

  private def warned(logs: Chunk[ZTestLogger.LogEntry], substr: String): Boolean =
    logs.exists(e => e.logLevel == LogLevel.Warning && e.message().contains(substr))

  private def erroredLog(logs: Chunk[ZTestLogger.LogEntry], substr: String): Boolean =
    logs.exists(e => e.logLevel == LogLevel.Error && e.message().contains(substr))

  def spec = suite("ErrorBoundary + ErrorMapper (#1570)")(
    // ── ErrorMapper.errorToResponse: exact wire shapes (back-compat) ───────────
    test("errorToResponse maps each ApiError to its preserved status + body") {
      for {
        br   <- ZIO.succeed(ErrorMapper.errorToResponse(ApiError.BadRequest("bad")))
        brB  <- bodyText(br)
        dec  <- ZIO.succeed(
          ErrorMapper.errorToResponse(ApiError.DecodeFailure("decode: .records[0]")),
        )
        decB <- bodyText(dec)
        un   <- ZIO.succeed(ErrorMapper.errorToResponse(ApiError.Unauthorized("no token")))
        fo   <- ZIO.succeed(ErrorMapper.errorToResponse(ApiError.Forbidden("nope")))
        nf   <- ZIO.succeed(ErrorMapper.errorToResponse(ApiError.NotFound("missing")))
        in   <- ZIO.succeed(ErrorMapper.errorToResponse(ApiError.Internal("boom")))
        wrapped = Response.status(Status.Conflict)
        wr <- ZIO.succeed(ErrorMapper.errorToResponse(ApiError.Wrapped(wrapped)))
      } yield assertTrue(br.status == Status.BadRequest) &&
        assertTrue(brB == "bad") &&
        assertTrue(dec.status == Status.BadRequest) &&
        // the failing-field detail is preserved in the body (what makes the #1569 log useful)
        assertTrue(decB.contains(".records[0]")) &&
        assertTrue(un.status == Status.Unauthorized) &&
        assertTrue(fo.status == Status.Forbidden) &&
        assertTrue(nf.status == Status.NotFound) &&
        assertTrue(in.status == Status.InternalServerError) &&
        // Wrapped passes an already-formed Response through unchanged.
        assertTrue(wr.status == Status.Conflict)
    },
    test("errorToResponse(Db) preserves the 503 + {status,db} body + Retry-After:30 wire shape") {
      // The agent depends on 503 (retry/back-off) vs 4xx (drop); the body shape + Retry-After
      // are the HealthRoutes-compatible contract. This must not drift.
      for {
        resp <- ZIO.succeed(ErrorMapper.errorToResponse(ApiError.Db(new RuntimeException("x"))))
        body <- bodyText(resp)
      } yield assertTrue(resp.status == Status.ServiceUnavailable) &&
        assertTrue(body == """{"status":"error","db":"RuntimeException"}""") &&
        assertTrue(resp.header(Header.RetryAfter).isDefined)
    },

    // ── ErrorBoundary: logging levels + body snippet + metering ────────────────
    test("boundary logs a 4xx response at WARN with a body snippet and meters api_errors_total") {
      (for {
        resp <- runSynthetic("/api/eb/client")
        _    <- ZIO.sleep(700.millis)
        body <- scrape.catchAll(r => bodyText(r))
        logs <- ZTestLogger.logOutput
      } yield assertTrue(resp.status == Status.BadRequest) &&
        // WARN with the templated route + the response body snippet
        assertTrue(warned(logs, "/api/eb/client")) &&
        assertTrue(warned(logs, "synthetic client error")) &&
        // NOT logged at ERROR (it's a client error)
        assertTrue(!erroredLog(logs, "/api/eb/client")) &&
        assertTrue(errorLines(body, "/api/eb/client", 400).nonEmpty))
        .provideSome[TestDatabase.AllRepos & EmbeddedPostgres & Clock & PrometheusPublisher](
          ZTestLogger.default,
        )
    } @@ TestAspect.withLiveClock,
    test("boundary logs a 5xx response at ERROR and meters it") {
      (for {
        resp <- runSynthetic("/api/eb/server")
        _    <- ZIO.sleep(700.millis)
        body <- scrape.catchAll(r => bodyText(r))
        logs <- ZTestLogger.logOutput
      } yield assertTrue(resp.status == Status.ServiceUnavailable) &&
        assertTrue(erroredLog(logs, "/api/eb/server")) &&
        assertTrue(!warned(logs, "/api/eb/server")) &&
        assertTrue(errorLines(body, "/api/eb/server", 503).nonEmpty))
        .provideSome[TestDatabase.AllRepos & EmbeddedPostgres & Clock & PrometheusPublisher](
          ZTestLogger.default,
        )
    } @@ TestAspect.withLiveClock,
    test(
      "boundary observes errors raised on the ERROR channel (failed handler), not just returned",
    ) {
      (for {
        resp <- runSynthetic("/api/eb/fail")
        logs <- ZTestLogger.logOutput
      } yield assertTrue(resp.status == Status.NotFound) &&
        assertTrue(warned(logs, "/api/eb/fail")))
        .provideSome[TestDatabase.AllRepos & EmbeddedPostgres & Clock & PrometheusPublisher](
          ZTestLogger.default,
        )
    } @@ TestAspect.withLiveClock,
    test("boundary leaves a 2xx alone — no error log, no api_errors_total series for that route") {
      (for {
        resp <- runSynthetic("/api/eb/ok")
        _    <- ZIO.sleep(700.millis)
        body <- scrape.catchAll(r => bodyText(r))
        logs <- ZTestLogger.logOutput
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(!warned(logs, "/api/eb/ok")) &&
        assertTrue(!erroredLog(logs, "/api/eb/ok")) &&
        assertTrue(errorLines(body, "/api/eb/ok", 200).isEmpty))
        .provideSome[TestDatabase.AllRepos & EmbeddedPostgres & Clock & PrometheusPublisher](
          ZTestLogger.default,
        )
    } @@ TestAspect.withLiveClock,

    // ── #1569 regression pin: usage decode failures are now LOGGED ─────────────
    test(
      "REGRESSION #1569: a /api/router/usage decode failure is LOGGED at WARN (not just 400-bodied)",
    ) {
      (for {
        _       <- cleanDb
        rRepo   <- ZIO.service[RouterRepo]
        routes  <- buildIngest
        (_, tk) <- seedRouter(rRepo)
        // malformed: records[0].activeSeconds is a string where a number is required
        bad =
          """{"routerId":"00000000-0000-0000-0000-000000000000","periodStart":"2026-05-07T14:00:00Z","periodEnd":"2026-05-07T14:05:00Z","records":[{"mac":"aa:bb:cc:11:22:33","host":{"type":"fqdn","value":"x.com"},"activeSeconds":"not-a-number","bytesIn":1,"bytesOut":1}]}"""
        resp     <- postIngest(routes, "/api/router/usage", bad, tk)
        _        <- ZIO.sleep(700.millis)
        body     <- scrape.catchAll(r => bodyText(r))
        respBody <- bodyText(resp)
        logs     <- ZTestLogger.logOutput
      } yield
      // Same status the agent already sees (4xx → drop, no retry) — unchanged.
      assertTrue(resp.status == Status.BadRequest) &&
        // back-compat: the decode error is still in the 400 body for the client.
        assertTrue(respBody.nonEmpty) &&
        // THE FIX: it is now logged at WARN under the templated route.
        assertTrue(warned(logs, "/api/router/usage")) &&
        // and metered.
        assertTrue(errorLines(body, "/api/router/usage", 400).nonEmpty))
        .provideSome[TestDatabase.AllRepos & EmbeddedPostgres & Clock & PrometheusPublisher](
          ZTestLogger.default,
        )
    } @@ TestAspect.withLiveClock,
    test(
      "a /api/router/events decode failure is logged at WARN (parity with usage — single emitter)",
    ) {
      (for {
        _       <- cleanDb
        rRepo   <- ZIO.service[RouterRepo]
        routes  <- buildIngest
        (_, tk) <- seedRouter(rRepo)
        bad = """{"routerId":"00000000-0000-0000-0000-000000000000","events":[{"type":}]}"""
        resp <- postIngest(routes, "/api/router/events", bad, tk)
        logs <- ZTestLogger.logOutput
      } yield assertTrue(resp.status == Status.BadRequest) &&
        assertTrue(warned(logs, "/api/router/events")))
        .provideSome[TestDatabase.AllRepos & EmbeddedPostgres & Clock & PrometheusPublisher](
          ZTestLogger.default,
        )
    } @@ TestAspect.withLiveClock,
    test("an invalid router bearer is mapped to 401 and logged at WARN by the boundary") {
      (for {
        _      <- cleanDb
        routes <- buildIngest
        body = UsageReport(
          RouterId(UUID.randomUUID()),
          Instant.parse("2026-05-07T14:00:00Z").toString,
          Instant.parse("2026-05-07T14:05:00Z").toString,
          Nil,
        ).toJson
        resp <- postIngest(routes, "/api/router/usage", body, "not-a-real-token")
        logs <- ZTestLogger.logOutput
      } yield assertTrue(resp.status == Status.Unauthorized) &&
        assertTrue(warned(logs, "/api/router/usage")))
        .provideSome[TestDatabase.AllRepos & EmbeddedPostgres & Clock & PrometheusPublisher](
          ZTestLogger.default,
        )
    } @@ TestAspect.withLiveClock,
  ) @@ TestAspect.sequential
}
