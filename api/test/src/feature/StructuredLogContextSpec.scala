package wifihaven.api.feature

import wifihaven.api.ErrorBoundary
import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.observability.LogContext
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.test.*

/**
 * #602: pins that the structured-log context introduced in `LogContext` actually attaches to the
 * log entries we expect — not just substring-formatted into the message. Targets two representative
 * hot paths:
 *
 *   1. ErrorBoundary 4xx — the templated `route`, `method`, and `status` keys land in
 *      `LogEntry.annotations`, which is what zio-logging-slf4j writes to SLF4J MDC. 2.
 *      AuthService.login — the `op=login`, `user`, and `reason` keys land for both the explicit
 *      success log and the bad-password failure log.
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

  private def authRoutes =
    for {
      ur   <- ZIO.service[UserRepo]
      up   <- ZIO.service[UserProfileRepo]
      auth <- makeAuth
    } yield ErrorBoundary.observe(AuthRoutes.routes(auth, ur, up))

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
    test("AuthService.login success attaches op=login, user, reason=ok") {
      (for {
        _    <- cleanDb
        auth <- makeAuth
        // seeded default admin (changeme) exists per TestDatabase migrations
        _    <- auth.login("admin", "changeme")
        logs <- ZTestLogger.logOutput
      } yield {
        val ok = entryWith(logs, LogLevel.Info, LogContext.Reason, "ok")
        assertTrue(ok.isDefined) &&
        assertTrue(ok.get.annotations.get(LogContext.Op).contains("login")) &&
        assertTrue(ok.get.annotations.get(LogContext.User).contains("admin"))
      })
        .provideSome[TestDatabase.AllRepos & EmbeddedPostgres & Clock](ZTestLogger.default)
    },
    test("AuthService.login bad password attaches op=login, user, reason=bad_password") {
      (for {
        _    <- cleanDb
        auth <- makeAuth
        _    <- auth.login("admin", "wrong").either
        logs <- ZTestLogger.logOutput
      } yield {
        val bad = entryWith(logs, LogLevel.Warning, LogContext.Reason, "bad_password")
        assertTrue(bad.isDefined) &&
        assertTrue(bad.get.annotations.get(LogContext.Op).contains("login")) &&
        assertTrue(bad.get.annotations.get(LogContext.User).contains("admin"))
      })
        .provideSome[TestDatabase.AllRepos & EmbeddedPostgres & Clock](ZTestLogger.default)
    },
  ) @@ TestAspect.sequential
}
