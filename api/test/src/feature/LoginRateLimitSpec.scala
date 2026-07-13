package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

/**
 * #2079: POST /api/auth/login rate-limits per source IP — previously bcrypt cost was the only
 * throttle on repeated attempts, with no cap on sustained online brute-force / credential stuffing.
 */
object LoginRateLimitSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg  = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)
  private val cleanDb = TestDatabase.cleanAndMigrate

  private def loginReq(password: String, ip: String) =
    Request
      .post(
        URL.decode("/api/auth/login").toOption.get,
        Body.fromString(LoginRequest(identifier = Some("admin"), password = password).toJson),
      )
      .addHeader(Header.ContentType(MediaType.application.json))
      .addHeader("X-Forwarded-For", ip)

  def spec = suite("Login rate limiting")(
    test("the Nth+1 attempt from the same IP within the window is rejected 429") {
      for {
        _      <- cleanDb
        ur     <- ZIO.service[UserRepo]
        upRepo <- ZIO.service[UserProfileRepo]
        clock  <- ZIO.service[Clock]
        auth = AuthServiceLive(ur, jwtCfg, clock)
        limiter <- RateLimiterLive.make(maxAttempts = 3, windowSeconds = 900)
        routes = AuthRoutes.routes(auth, ur, upRepo, limiter)
        // 3 failed attempts consume the whole budget (wrong password on purpose — the limiter
        // gates BEFORE credential checking, so failures count the same as successes).
        r1 <- routes.runZIO(loginReq("wrong", "203.0.113.9"))
        r2 <- routes.runZIO(loginReq("wrong", "203.0.113.9"))
        r3 <- routes.runZIO(loginReq("wrong", "203.0.113.9"))
        r4 <- routes.runZIO(loginReq("changeme", "203.0.113.9"))
      } yield assertTrue(r1.status == Status.Unauthorized) &&
        assertTrue(r2.status == Status.Unauthorized) &&
        assertTrue(r3.status == Status.Unauthorized) &&
        assertTrue(r4.status == Status.TooManyRequests)
    },
    test("a different source IP has its own independent budget") {
      for {
        _      <- cleanDb
        ur     <- ZIO.service[UserRepo]
        upRepo <- ZIO.service[UserProfileRepo]
        clock  <- ZIO.service[Clock]
        auth = AuthServiceLive(ur, jwtCfg, clock)
        limiter <- RateLimiterLive.make(maxAttempts = 1, windowSeconds = 900)
        routes = AuthRoutes.routes(auth, ur, upRepo, limiter)
        _       <- routes.runZIO(loginReq("changeme", "203.0.113.1"))
        blocked <- routes.runZIO(loginReq("changeme", "203.0.113.1"))
        otherIp <- routes.runZIO(loginReq("changeme", "203.0.113.2"))
      } yield assertTrue(blocked.status == Status.TooManyRequests) &&
        assertTrue(otherIp.status == Status.Ok)
    },
    // Regression: our nginx reverse-proxy config (docs/install-api.md §7.2) sets
    // X-Forwarded-For via $proxy_add_x_forwarded_for, which APPENDS the real client
    // onto whatever the caller sent — so the LEFTMOST hop is attacker-controlled.
    // Reading it (instead of the rightmost / X-Real-IP) would let an attacker bypass
    // the whole rate limit by sending a fresh forged leftmost value on every request.
    test("a forged leftmost X-Forwarded-For hop does not bypass the limit") {
      for {
        _      <- cleanDb
        ur     <- ZIO.service[UserRepo]
        upRepo <- ZIO.service[UserProfileRepo]
        clock  <- ZIO.service[Clock]
        auth = AuthServiceLive(ur, jwtCfg, clock)
        limiter <- RateLimiterLive.make(maxAttempts = 1, windowSeconds = 900)
        routes = AuthRoutes.routes(auth, ur, upRepo, limiter)
        realIp = "203.0.113.7"
        req1   = Request
          .post(
            URL.decode("/api/auth/login").toOption.get,
            Body.fromString(LoginRequest(identifier = Some("admin"), password = "changeme").toJson),
          )
          .addHeader(Header.ContentType(MediaType.application.json))
          .addHeader("X-Forwarded-For", s"198.51.100.1, $realIp")
        req2   = Request
          .post(
            URL.decode("/api/auth/login").toOption.get,
            Body.fromString(LoginRequest(identifier = Some("admin"), password = "changeme").toJson),
          )
          .addHeader(Header.ContentType(MediaType.application.json))
          // Different forged leftmost value, SAME real (rightmost) client IP.
          .addHeader("X-Forwarded-For", s"198.51.100.99, $realIp")
        r1 <- routes.runZIO(req1)
        r2 <- routes.runZIO(req2)
      } yield assertTrue(r1.status == Status.Ok) &&
        assertTrue(r2.status == Status.TooManyRequests)
    },
    test("X-Real-IP takes precedence over X-Forwarded-For") {
      for {
        _      <- cleanDb
        ur     <- ZIO.service[UserRepo]
        upRepo <- ZIO.service[UserProfileRepo]
        clock  <- ZIO.service[Clock]
        auth = AuthServiceLive(ur, jwtCfg, clock)
        limiter <- RateLimiterLive.make(maxAttempts = 1, windowSeconds = 900)
        routes = AuthRoutes.routes(auth, ur, upRepo, limiter)
        req    = Request
          .post(
            URL.decode("/api/auth/login").toOption.get,
            Body.fromString(LoginRequest(identifier = Some("admin"), password = "changeme").toJson),
          )
          .addHeader(Header.ContentType(MediaType.application.json))
          .addHeader("X-Real-IP", "203.0.113.55")
          .addHeader("X-Forwarded-For", "203.0.113.56")
        r1 <- routes.runZIO(req)
        // Same X-Real-IP, different X-Forwarded-For — still blocked (X-Real-IP wins).
        req2 = Request
          .post(
            URL.decode("/api/auth/login").toOption.get,
            Body.fromString(LoginRequest(identifier = Some("admin"), password = "changeme").toJson),
          )
          .addHeader(Header.ContentType(MediaType.application.json))
          .addHeader("X-Real-IP", "203.0.113.55")
          .addHeader("X-Forwarded-For", "203.0.113.99")
        r2 <- routes.runZIO(req2)
      } yield assertTrue(r1.status == Status.Ok) &&
        assertTrue(r2.status == Status.TooManyRequests)
    },
  ) @@ TestAspect.sequential
}
