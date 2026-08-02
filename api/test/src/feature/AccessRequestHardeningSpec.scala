package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.notify.{EscalationNotice, Notifier}
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.Clock.TestClock
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

/**
 * #2081: POST /api/access-requests (public, no auth — the block page's kid-request path)
 * rate-limits per source IP and caps `note` length. Previously the only abuse control was a
 * per-(mac,host) debounce, which a varying `host` (or `note`) bypasses entirely.
 */
object AccessRequestHardeningSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg  = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)
  private val cleanDb = TestDatabase.cleanAndMigrate
  private val mac1    = MacAddress.unsafe("aa:bb:cc:11:22:33")

  private val noopNotifier: Notifier = new Notifier {
    def alertCreated(a: Alert): UIO[Unit]                                          = ZIO.unit
    def betaInvite(
        email: String,
        slug: String,
        inviteUrl: String,
        ttlHours: Int,
    ): UIO[Unit] = ZIO.unit
    def betaFlipNotice(
        householdId: wifihaven.shared.types.HouseholdId,
        slug: String,
        window: String,
        flipDate: java.time.Instant,
        daysUntilFlip: Int,
    ): UIO[Unit] = ZIO.unit
    def passwordReset(email: String, resetUrl: String, ttlMinutes: Int): UIO[Unit] = ZIO.unit
    // #2437: this suite drives no escalation path; the notice is a no-op here.
    def escalation(notice: EscalationNotice): UIO[Unit]                            = ZIO.unit
  }

  private def setup(rateLimiter: RateLimiter) =
    for {
      alertRepo <- ZIO.service[AlertRepo]
      dRepo     <- ZIO.service[DeviceRepo]
      pRepo     <- ZIO.service[ProfileRepo]
      extRepo   <- ZIO.service[TimeExtensionRepo]
      appRepo   <- ZIO.service[AppRepo]
      hsRepo    <- ZIO.service[HouseholdSettingsRepo]
      upRepo    <- ZIO.service[UserProfileRepo]
      ur        <- ZIO.service[UserRepo]
      clock     <- ZIO.service[Clock]
      auth = AuthServiceLive(ur, jwtCfg, clock)
      _       <- hsRepo.ensureDefault(java.time.ZoneId.of("UTC"))
      kidsPid <- TestLayers.seedKidsProfile(pRepo)
      _       <- dRepo.upsert(mac1, "kid-laptop", Some(kidsPid), "")
    } yield AlertRoutes.routes(
      auth,
      alertRepo,
      dRepo,
      pRepo,
      extRepo,
      appRepo,
      hsRepo,
      upRepo,
      noopNotifier,
      clock,
      rateLimiter,
    )

  private def postCreateAR(
      routes: Routes[Any, Response],
      host: Hostname,
      note: Option[String] = None,
      ip: String = "203.0.113.9",
  ) =
    routes.runZIO(
      Request
        .post(
          URL.decode("/api/access-requests").toOption.get,
          Body.fromString(
            CreateAccessRequest(mac1, host, AccessRequestKind.Exemption, note).toJson,
          ),
        )
        .addHeader(Header.ContentType(MediaType.application.json))
        .addHeader("X-Forwarded-For", ip),
    )

  def spec = suite("Access-request hardening")(
    test(
      "varying host past the rate limit is still rejected 429 (debounce alone doesn't gate it)",
    ) {
      for {
        _       <- cleanDb
        limiter <- RateLimiterLive.make(maxAttempts = 2, windowSeconds = 300)
        routes  <- setup(limiter)
        r1      <- postCreateAR(routes, Hostname.unsafe("a.example.com"))
        r2      <- postCreateAR(routes, Hostname.unsafe("b.example.com"))
        r3      <- postCreateAR(routes, Hostname.unsafe("c.example.com"))
      } yield assertTrue(r1.status == Status.Created) &&
        assertTrue(r2.status == Status.Created) &&
        assertTrue(r3.status == Status.TooManyRequests)
    },
    test("a different source IP has its own independent budget") {
      for {
        _       <- cleanDb
        limiter <- RateLimiterLive.make(maxAttempts = 1, windowSeconds = 300)
        routes  <- setup(limiter)
        _       <- postCreateAR(routes, Hostname.unsafe("a.example.com"), ip = "203.0.113.1")
        blocked <- postCreateAR(routes, Hostname.unsafe("z.example.com"), ip = "203.0.113.1")
        // Different host too, so this doesn't hit the pre-existing per-(mac,host) debounce —
        // it's the source-IP budget under test here, not the debounce.
        otherIp <- postCreateAR(routes, Hostname.unsafe("y.example.com"), ip = "203.0.113.2")
      } yield assertTrue(blocked.status == Status.TooManyRequests) &&
        assertTrue(otherIp.status == Status.Created)
    },
    test("an over-length note is truncated, not rejected") {
      for {
        _         <- cleanDb
        routes    <- setup(RateLimiter.allowAll)
        alertRepo <- ZIO.service[AlertRepo]
        longNote = "x" * (AlertRoutes.MaxNoteLength + 100)
        resp   <- postCreateAR(routes, Hostname.unsafe("a.example.com"), Some(longNote))
        stored <- alertRepo.list(includeAll = true)
      } yield assertTrue(resp.status == Status.Created) &&
        assertTrue(stored.exists(_.note.exists(_.length == AlertRoutes.MaxNoteLength)))
    },
  ) @@ TestAspect.sequential
}
