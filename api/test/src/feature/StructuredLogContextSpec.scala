package wifihaven.api.feature

import wifihaven.api.ErrorBoundary
import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.observability.LogContext
import wifihaven.api.observability.LoggingMiddleware
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.test.*

/**
 * #602: pins that the structured-log context actually attaches to the log entries we expect — not
 * just substring-formatted into the message. Covers:
 *
 *   1. `LoggingMiddleware` injects `route` + `method` per-request, and `ErrorBoundary` adds
 *      `status` on 4xx/5xx. Together they fill the request-shape context with NO handler wrapping.
 *      2. `AuthService.login` attaches per-call `user` + `reason`. The success log inside a real
 *      HTTP request demonstrates FiberRef inheritance: middleware annotations and handler-internal
 *      annotations both land on a single log entry without anyone composing them by hand.
 *
 * MDC visibility in production is then a configuration property of `logback.xml` (we add `%mdc` to
 * the console pattern); the unit test here verifies the upstream annotation contract, not the SLF4J
 * appender format.
 */
object StructuredLogContextSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg = JwtConfig(secret = "test-secret-at-least-32-chars!!", expiryHours = 1)

  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)

  // Mirrors the production wiring in Main.scala: LoggingMiddleware (route/method)
  // outside, ErrorBoundary (status) inside. The two compose — annotations from
  // both flow into every log emitted by a handler.
  private def authRoutes =
    for {
      ur   <- ZIO.service[UserRepo]
      up   <- ZIO.service[UserProfileRepo]
      auth <- makeAuth
    } yield LoggingMiddleware.annotate(ErrorBoundary.observe(AuthRoutes.routes(auth, ur, up)))

  private def url(p: String) = URL.decode(p).toOption.get

  private val cleanDb = TestDatabase.cleanAndMigrate

  private def entryWith(
      logs: Chunk[ZTestLogger.LogEntry],
      level: LogLevel,
      key: String,
      value: String,
  ): Option[ZTestLogger.LogEntry] =
    logs.find(e => e.logLevel == level && e.annotations.get(key).contains(value))

  def spec = suite("#602 structured log context")(
    test("ErrorBoundary 4xx attaches route, method, status annotations") {
      (for {
        _    <- cleanDb
        rs   <- authRoutes
        // unauthenticated GET /api/me → 401, observed by ErrorBoundary
        resp <- rs.runZIO(Request.get(url("/api/me")))
        logs <- ZTestLogger.logOutput
      } yield {
        val warn = logs.find(e => e.logLevel == LogLevel.Warning)
        assertTrue(resp.status.code == 401) &&
        assertTrue(warn.isDefined) &&
        assertTrue(warn.get.annotations.get(LogContext.Route).contains("/api/me")) &&
        assertTrue(warn.get.annotations.get(LogContext.Method).contains("GET")) &&
        assertTrue(warn.get.annotations.get(LogContext.Status).contains("401"))
      })
        .provideSome[TestDatabase.AllRepos & EmbeddedPostgres & Clock](ZTestLogger.default)
    },
    test("AuthService.login success attaches user, reason=ok (route/method via middleware)") {
      (for {
        _    <- cleanDb
        auth <- makeAuth
        // seeded default admin (changeme) exists per TestDatabase migrations
        _    <- auth.login("admin", "changeme")
        logs <- ZTestLogger.logOutput
      } yield {
        val ok = entryWith(logs, LogLevel.Info, LogContext.Reason, "ok")
        assertTrue(ok.isDefined) &&
        assertTrue(ok.get.annotations.get(LogContext.User).contains("admin"))
      })
        .provideSome[TestDatabase.AllRepos & EmbeddedPostgres & Clock](ZTestLogger.default)
    },
    test("AuthService.login bad password attaches user, reason=bad_password") {
      (for {
        _    <- cleanDb
        auth <- makeAuth
        _    <- auth.login("admin", "wrong").either
        logs <- ZTestLogger.logOutput
      } yield {
        val bad = entryWith(logs, LogLevel.Warning, LogContext.Reason, "bad_password")
        assertTrue(bad.isDefined) &&
        assertTrue(bad.get.annotations.get(LogContext.User).contains("admin"))
      })
        .provideSome[TestDatabase.AllRepos & EmbeddedPostgres & Clock](ZTestLogger.default)
    },
    test("LoggingMiddleware annotates inherits into the handler's own logs") {
      // Service-internal log (`AuthService.login` success) emitted from inside an
      // HTTP request must carry route + method from the middleware AND user from
      // the service — proving the FiberRef inheritance.
      (for {
        _  <- cleanDb
        rs <- authRoutes
        body = """{"username":"admin","password":"changeme"}"""
        req  = Request
          .post(url("/api/auth/login"), Body.fromString(body))
          .addHeader(Header.ContentType(MediaType.application.json))
        resp <- rs.runZIO(req)
        logs <- ZTestLogger.logOutput
      } yield {
        val ok = entryWith(logs, LogLevel.Info, LogContext.Reason, "ok")
        assertTrue(resp.status.code == 200) &&
        assertTrue(ok.isDefined) &&
        // From AuthService.login (handler-internal):
        assertTrue(ok.get.annotations.get(LogContext.User).contains("admin")) &&
        // From LoggingMiddleware (per-request, inherited via FiberRef):
        assertTrue(ok.get.annotations.get(LogContext.Route).contains("/api/auth/login")) &&
        assertTrue(ok.get.annotations.get(LogContext.Method).contains("POST"))
      })
        .provideSome[TestDatabase.AllRepos & EmbeddedPostgres & Clock](ZTestLogger.default)
    },
  ) @@ TestAspect.sequential
}
