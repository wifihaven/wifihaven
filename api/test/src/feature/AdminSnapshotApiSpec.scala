package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.policy.*
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

/**
 * #1641 — admin /api/admin/snapshot endpoint and snapshot_changed INFO logging.
 *
 * Covers:
 *   1. GET /api/admin/snapshot with admin token → 200, returns the same PolicySnapshot JSON the
 *      router gets at /api/router/policy, with ETag header matching. 2. ReadOnly (child) token →
 *      403. 3. No auth → 401. 4. PolicyService logs `event=snapshot_changed` exactly once per ETag
 *      transition: first compute, no-op repeats (same etag → no log), and after a state mutation
 *      that moves the etag (next call logs again).
 */
object AdminSnapshotApiSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg = JwtConfig(secret = "test-secret-at-least-32-chars!!", expiryHours = 1)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)

  private def makePolicyService =
    for {
      pr     <- ZIO.service[ProfileRepo]
      sr     <- ZIO.service[ScheduleRepo]
      hsr    <- ZIO.service[HouseholdSettingsRepo]
      tlr    <- ZIO.service[TimeLimitRepo]
      atlr   <- ZIO.service[AppTimeLimitRepo]
      dr     <- ZIO.service[DeviceRepo]
      blr    <- ZIO.service[BlocklistRepo]
      trRepo <- ZIO.service[TrafficReportRepo]
      er     <- ZIO.service[TimeExtensionRepo]
      ar     <- ZIO.service[AppRepo]
      clock  <- ZIO.service[Clock]
    } yield PolicyServiceLive(
      pr,
      sr,
      hsr,
      tlr,
      atlr,
      dr,
      blr,
      trRepo,
      er,
      ar,
      clock,
    ): PolicyService

  private def snapshotChangedLines(logs: Chunk[ZTestLogger.LogEntry]): Int = {
    val needle = "event=snapshot_changed"
    logs.count(e => e.logLevel == LogLevel.Info && e.message().contains(needle))
  }

  def spec = suite("Admin snapshot API (#1641)")(
    test("GET /api/admin/snapshot with admin token returns full PolicySnapshot + ETag header") {
      for {
        _          <- cleanDb
        pr         <- ZIO.service[ProfileRepo]
        sr         <- ZIO.service[ScheduleRepo]
        dr         <- ZIO.service[DeviceRepo]
        _          <- TestLayers.seedKidsProfile(pr, sr)
        auth       <- makeAuth
        ps         <- makePolicyService
        adminLogin <- auth.login("admin", "changeme")
        routes = AdminDebugRoutes.routes(auth, ps)
        resp <- routes.runZIO(
          Request
            .get(URL.decode("/api/admin/snapshot").toOption.get)
            .addHeader(Header.Authorization.Bearer(adminLogin.token.value)),
        )
        body <- resp.body.asString
        snap <- ZIO.fromEither(body.fromJson[PolicySnapshot])
        // The handler emits a strong ETag header carrying `snap.etag` modulo the wrapping
        // quotes (Header.ETag.Strong appends them on render). Assert the round-trip equals
        // the body's etag exactly after one normalize step — this is what pins the
        // header-emission contract (the previous "both contain sha256:" check would pass
        // against a hard-coded sentinel value).
        etagHeader = resp.headers.get(Header.ETag).map(_.renderedValue).getOrElse("")
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(snap.profiles.nonEmpty) &&
        assertTrue(etagHeader.nonEmpty) &&
        assertTrue(etagHeader == snap.etag.value)
    },
    test("GET /api/admin/snapshot with child (non-admin) token → 403") {
      for {
        _          <- cleanDb
        ur         <- ZIO.service[UserRepo]
        auth       <- makeAuth
        ps         <- makePolicyService
        hash       <- auth.hashPassword("kidpass")
        kidId      <- ur.create("kid", hash, "child")
        // Child user defaults to must_change_password=true; clear it so we can authenticate.
        _          <- ur.clearMustChangePassword(kidId)
        childLogin <- auth.login("kid", "kidpass")
        routes = AdminDebugRoutes.routes(auth, ps)
        resp <- routes.runZIO(
          Request
            .get(URL.decode("/api/admin/snapshot").toOption.get)
            .addHeader(Header.Authorization.Bearer(childLogin.token.value)),
        )
      } yield assertTrue(resp.status == Status.Forbidden)
    },
    test("GET /api/admin/snapshot without bearer token → 401") {
      for {
        _    <- cleanDb
        auth <- makeAuth
        ps   <- makePolicyService
        routes = AdminDebugRoutes.routes(auth, ps)
        resp <- routes.runZIO(Request.get(URL.decode("/api/admin/snapshot").toOption.get))
      } yield assertTrue(resp.status == Status.Unauthorized)
    },
    test(
      "PolicyService.snapshot emits exactly one snapshot_changed INFO log per ETag transition",
    ) {
      (for {
        _    <- cleanDb
        pr   <- ZIO.service[ProfileRepo]
        sr   <- ZIO.service[ScheduleRepo]
        kid  <- TestLayers.seedKidsProfile(pr, sr)
        ps   <- makePolicyService
        // First snapshot: should log once (None -> Some(etag1)).
        s1   <- ps.snapshot
        // Second snapshot with no DB change: same etag, should NOT log.
        s2   <- ps.snapshot
        // Mutate state: pause the kids profile. Should change the etag.
        _    <- pr.setPaused(kid, true)
        s3   <- ps.snapshot
        // Final snapshot, same as s3: should not log again.
        s4   <- ps.snapshot
        logs <- ZTestLogger.logOutput
      } yield assertTrue(s1.etag == s2.etag) &&
        assertTrue(s1.etag != s3.etag) &&
        assertTrue(s3.etag == s4.etag) &&
        assertTrue(snapshotChangedLines(logs) == 2))
        .provideSome[TestDatabase.AllRepos & EmbeddedPostgres & Clock](ZTestLogger.default)
    },
  ) @@ TestAspect.sequential
}
