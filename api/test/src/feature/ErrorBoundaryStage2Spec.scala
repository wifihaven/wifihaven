package wifihaven.api.feature

import wifihaven.api.ErrorBoundary
import wifihaven.api.JwtConfig
import wifihaven.api.MetricsConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.metrics.MetricsRuntime
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
 * #1570 Stage 2: the migrated route files now fail with a typed [[ApiError]] mapped centrally by
 * [[ErrorMapper.errorToResponse]] and observed by [[ErrorBoundary.observe]]. This spec wraps a few
 * representative migrated families (Schedules, Apps, Global Policy) in the boundary exactly as
 * `Main` does and pins, end-to-end against embedded Postgres (no mocks):
 *
 *   1. Wire back-compat: each error path returns the EXACT status + body the hand-rolled code
 *      produced — including the structured `name_taken` / `slug_taken` 409 JSON bodies the SPA
 *      parses (preserved verbatim through [[ApiError.Wrapped]]). 2. Boundary integration: the
 *      migrated error responses are now LOGGED (4xx → WARN) and METERED
 *      (`api_errors_total{route,status}`) in one place — the Stage-1 coverage now extends to these
 *      routes too. Auth-helper failures bridged via [[ApiError.Wrapped]] are observed identically.
 *
 * Stage-1's [[ErrorBoundarySpec]] proves the boundary mechanism + the ingest routes; this is the
 * Stage-2 analogue for the admin/SPA-facing families.
 */
object ErrorBoundaryStage2Spec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & PrometheusPublisher] {

  override val bootstrap =
    TestDatabase.layer ++
      TestLayers.withClock(TestClock.schoolDayAfternoon) ++
      MetricsRuntime.prometheus(100.millis)

  private val jwtCfg = JwtConfig(secret = "test-secret-at-least-32-chars!!", expiryHours = 1)

  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private def adminToken =
    for {
      auth  <- makeAuth
      token <- auth.login("admin", "changeme").map(_.token.value)
    } yield token

  private def scheduleRoutes =
    for {
      nsr  <- ZIO.service[NamedScheduleRepo]
      auth <- makeAuth
    } yield ErrorBoundary.observe(ScheduleRoutes.routes(auth, nsr))

  private def appRoutes =
    for {
      appRepo     <- ZIO.service[AppRepo]
      profileRepo <- ZIO.service[ProfileRepo]
      upRepo      <- ZIO.service[UserProfileRepo]
      auth        <- makeAuth
    } yield ErrorBoundary.observe(AppRoutes.routes(auth, appRepo, profileRepo, upRepo))

  private def globalRoutes =
    for {
      repo <- ZIO.service[GlobalPolicyRepo]
      ur   <- ZIO.service[UserRepo]
      auth <- makeAuth
    } yield ErrorBoundary.observe(GlobalPolicyRoutes.routes(auth, repo, ur))

  private def url(p: String) = URL.decode(p).toOption.get

  private def post(rs: Routes[Any, Response], path: String, body: String, token: String) =
    rs.runZIO(
      Request
        .post(url(path), Body.fromString(body))
        .addHeader(Header.Authorization.Bearer(token))
        .addHeader(Header.ContentType(MediaType.application.json)),
    )

  private def get(rs: Routes[Any, Response], path: String, token: String) =
    rs.runZIO(Request.get(url(path)).addHeader(Header.Authorization.Bearer(token)))

  private def bodyText(r: Response): UIO[String] = r.body.asString.orElseSucceed("")

  private def scrape: ZIO[PrometheusPublisher, Response, String] =
    for {
      pub <- ZIO.service[PrometheusPublisher]
      routes = MetricsRoutes.routes(MetricsConfig(enabled = true), pub)
      resp <- routes(Request.get("/metrics"))
      body <- resp.body.asString.orElseFail(Response.internalServerError("scrape decode failed"))
    } yield body

  private def meteredStatus(body: String, status: Int): Boolean =
    body.linesIterator.exists(l =>
      !l.startsWith("#") && l.startsWith("api_errors_total") && l.contains(s"""status="$status""""),
    )

  private def warned(logs: Chunk[ZTestLogger.LogEntry], substr: String): Boolean =
    logs.exists(e => e.logLevel == LogLevel.Warning && e.message().contains(substr))

  def spec = suite("ErrorBoundary Stage 2 — migrated route families (#1570)")(
    test("Schedules: duplicate name keeps the 409 + name_taken JSON body, logged + metered") {
      (for {
        _     <- cleanDb
        token <- adminToken
        rs    <- scheduleRoutes
        body = CreateNamedScheduleRequest(name = "Bedtime").toJson
        _       <- post(rs, "/api/schedules", body, token)
        dupe    <- post(rs, "/api/schedules", body, token)
        dBody   <- bodyText(dupe)
        _       <- ZIO.sleep(700.millis)
        scrapeB <- scrape.catchAll(r => bodyText(r))
        logs    <- ZTestLogger.logOutput
      } yield assertTrue(dupe.status == Status.Conflict) &&
        // back-compat: the SPA parses this exact structured body
        assertTrue(dBody == """{"error":"name_taken","name":"Bedtime"}""") &&
        assertTrue(warned(logs, "/api/schedules")) &&
        assertTrue(meteredStatus(scrapeB, 409)))
        .provideSome[TestDatabase.AllRepos & EmbeddedPostgres & Clock & PrometheusPublisher](
          ZTestLogger.default,
        )
    } @@ TestAspect.withLiveClock,
    test("Schedules: GET unknown id is 404 'Schedule not found', logged at WARN + metered") {
      (for {
        _       <- cleanDb
        token   <- adminToken
        rs      <- scheduleRoutes
        resp    <- get(rs, "/api/schedules/99999", token)
        rBody   <- bodyText(resp)
        _       <- ZIO.sleep(700.millis)
        scrapeB <- scrape.catchAll(r => bodyText(r))
        logs    <- ZTestLogger.logOutput
      } yield assertTrue(resp.status == Status.NotFound) &&
        assertTrue(rBody == "Schedule not found") &&
        assertTrue(warned(logs, "Schedule not found")) &&
        assertTrue(meteredStatus(scrapeB, 404)))
        .provideSome[TestDatabase.AllRepos & EmbeddedPostgres & Clock & PrometheusPublisher](
          ZTestLogger.default,
        )
    } @@ TestAspect.withLiveClock,
    test("Apps: duplicate slug keeps the 409 + slug_taken JSON body, metered") {
      (for {
        _     <- cleanDb
        token <- adminToken
        rs    <- appRoutes
        body = CreateAppRequest(name = "YouTube", slug = Some("youtube")).toJson
        _       <- post(rs, "/api/apps", body, token)
        dupe    <- post(rs, "/api/apps", body, token)
        dBody   <- bodyText(dupe)
        _       <- ZIO.sleep(700.millis)
        scrapeB <- scrape.catchAll(r => bodyText(r))
      } yield assertTrue(dupe.status == Status.Conflict) &&
        assertTrue(dBody == """{"error":"slug_taken","slug":"youtube"}""") &&
        assertTrue(meteredStatus(scrapeB, 409)))
        .provideSome[TestDatabase.AllRepos & EmbeddedPostgres & Clock & PrometheusPublisher](
          ZTestLogger.default,
        )
    } @@ TestAspect.withLiveClock,
    test("Global policy: a missing token is mapped to 401 and logged at WARN by the boundary") {
      // requireAuth fails with a Response (Missing token); the migrated handler bridges it via
      // ApiError.Wrapped, so the boundary still observes the 401 identically.
      (for {
        _       <- cleanDb
        rs      <- globalRoutes
        resp    <- rs.runZIO(Request.get(url("/api/global/policy")))
        _       <- ZIO.sleep(700.millis)
        scrapeB <- scrape.catchAll(r => bodyText(r))
        logs    <- ZTestLogger.logOutput
      } yield assertTrue(resp.status == Status.Unauthorized) &&
        assertTrue(warned(logs, "/api/global/policy")) &&
        assertTrue(meteredStatus(scrapeB, 401)))
        .provideSome[TestDatabase.AllRepos & EmbeddedPostgres & Clock & PrometheusPublisher](
          ZTestLogger.default,
        )
    } @@ TestAspect.withLiveClock,
  ) @@ TestAspect.sequential
}
