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
 * #1570 Stage 2 (PR B): the `Routes.scala` families (Auth/Profile/Device/Time/Log/Blocklist/
 * Household) now fail with a typed [[ApiError]] mapped centrally by [[ErrorMapper.errorToResponse]]
 * and observed by [[ErrorBoundary.observe]]. This spec wraps Auth + Profile in the boundary exactly
 * as `Main` does and pins, end-to-end against embedded Postgres (no mocks):
 *
 *   1. Wire back-compat for the most consumer-sensitive bodies — especially the
 *      `{"error":"password_change_required"}` 403 the SPA sniffs (#586), which `requireAuth` still
 *      produces as a `Response` and the migrated handler bridges through [[ApiError.Wrapped]] so it
 *      survives byte-for-byte. Plus the `Invalid credentials` 401 and `Profile not found` 404. 2.
 *      Boundary integration: those migrated error responses are now LOGGED (4xx → WARN) and METERED
 *      (`api_errors_total{route,status}`) in one place.
 */
object ErrorBoundaryRoutesSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  // Poll interval for the Prometheus snapshot fiber; tests advance the zio TestClock by exactly
  // this much to drive one deterministic scrape.
  private val pollInterval = 100.millis

  // #2042: deterministically fire ONE scrape of the shared Prometheus publisher fiber. The key is
  // waiting until that background fiber has actually re-parked on the TestClock (its `Schedule.fixed`
  // sleep is registered) BEFORE advancing — under parallel CI load a bare `adjust` can slip past a
  // momentarily mid-flight and never trigger the scrape. The publisher layer is provided PER TEST
  // (suite-level `provideSomeLayer` below) so its snapshot fiber is a supervised descendant of the
  // test fiber — only then does `TestClock.adjust` await it (a bootstrap-scope fiber is invisible to
  // its `awaitSuspended`). Once parked, advancing one interval fires exactly one registry snapshot.
  private val tickPublisher: UIO[Unit] =
    zio.test.TestClock.sleeps.repeatUntil(_.nonEmpty) *> zio.test.TestClock.adjust(pollInterval)

  override val bootstrap =
    TestDatabase.layer ++
      TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)

  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private def authRoutes =
    for {
      ur   <- ZIO.service[UserRepo]
      up   <- ZIO.service[UserProfileRepo]
      auth <- makeAuth
    } yield ErrorBoundary.observe(AuthRoutes.routes(auth, ur, up, RateLimiter.allowAll))

  private def profileRoutes =
    for {
      pr   <- ZIO.service[ProfileRepo]
      tlr  <- ZIO.service[TimeLimitRepo]
      up   <- ZIO.service[UserProfileRepo]
      ur   <- ZIO.service[UserRepo]
      auth <- makeAuth
    } yield ErrorBoundary.observe(ProfileRoutes.routes(auth, pr, tlr, up, ur))

  private def url(p: String) = URL.decode(p).toOption.get
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

  def spec = suite("ErrorBoundary Stage 2 — Routes.scala families (#1570 PR B)")(
    test(
      "must_change_password user hitting GET /api/me keeps the 403 password_change_required JSON",
    ) {
      (for {
        _       <- cleanDb
        ur      <- ZIO.service[UserRepo]
        auth    <- makeAuth
        rs      <- authRoutes
        // A freshly-created user defaults to must_change_password=true (#599); login still
        // issues a token, but requireAuth must 403 with the SPA-sniffed body until it's cleared.
        hash    <- auth.hashPassword("childpass")
        _       <- ur.create("kid", hash, "child")
        tok     <- auth.login("kid", "childpass").map(_.token.value)
        resp    <- get(rs, "/api/me", tok)
        body    <- bodyText(resp)
        _       <- tickPublisher
        scrapeB <- scrape.catchAll(r => bodyText(r))
        logs    <- ZTestLogger.logOutput
      } yield assertTrue(resp.status == Status.Forbidden) &&
        assertTrue(body == """{"error":"password_change_required"}""") &&
        assertTrue(warned(logs, "/api/me")) &&
        assertTrue(meteredStatus(scrapeB, 403)))
        .provideSome[TestDatabase.AllRepos & EmbeddedPostgres & Clock & PrometheusPublisher](
          ZTestLogger.default,
        )
    },
    test("login with bad credentials is 401 'Invalid credentials', logged + metered") {
      (for {
        _       <- cleanDb
        rs      <- authRoutes
        resp    <- rs.runZIO(
          Request
            .post(url("/api/auth/login"), Body.fromString(LoginRequest("admin", "nope").toJson))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
        body    <- bodyText(resp)
        _       <- tickPublisher
        scrapeB <- scrape.catchAll(r => bodyText(r))
        logs    <- ZTestLogger.logOutput
      } yield assertTrue(resp.status == Status.Unauthorized) &&
        assertTrue(body == "Invalid credentials") &&
        assertTrue(warned(logs, "/api/auth/login")) &&
        assertTrue(meteredStatus(scrapeB, 401)))
        .provideSome[TestDatabase.AllRepos & EmbeddedPostgres & Clock & PrometheusPublisher](
          ZTestLogger.default,
        )
    },
    test("GET /api/profiles/{unknown} is 404 'Profile not found', logged + metered") {
      (for {
        _       <- cleanDb
        auth    <- makeAuth
        rs      <- profileRoutes
        tok     <- auth.login("admin", "changeme").map(_.token.value)
        resp    <- get(rs, "/api/profiles/99999", tok)
        body    <- bodyText(resp)
        _       <- tickPublisher
        scrapeB <- scrape.catchAll(r => bodyText(r))
        logs    <- ZTestLogger.logOutput
      } yield assertTrue(resp.status == Status.NotFound) &&
        assertTrue(body == "Profile not found") &&
        assertTrue(warned(logs, "Profile not found")) &&
        assertTrue(meteredStatus(scrapeB, 404)))
        .provideSome[TestDatabase.AllRepos & EmbeddedPostgres & Clock & PrometheusPublisher](
          ZTestLogger.default,
        )
    },
  ).provideSomeLayer[TestDatabase.AllRepos & EmbeddedPostgres & Clock](
    MetricsRuntime.prometheus(pollInterval),
  ) @@ TestAspect.sequential
}
