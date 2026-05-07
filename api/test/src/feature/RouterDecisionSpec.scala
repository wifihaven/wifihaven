package familydns.api.feature

import familydns.api.db.*
import familydns.api.policy.*
import familydns.api.routes.*
import familydns.shared.*
import familydns.shared.Clock.TestClock
import familydns.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

import java.time.Instant
import java.util.UUID

object RouterDecisionSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private def cleanDb = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

  private def seedEnrolledRouter(rRepo: RouterRepo): Task[(UUID, String)] =
    val token = "DECISION_ROUTER_TOKEN_PLAIN"
    val hash  = RouterAuth.sha256Hex(token)
    for
      id <- rRepo.create("test-router", "ENROLL_HASH_DEC")
      _  <- rRepo.completeEnrollment(id, hash)
    yield (id, token)

  private def makePolicyService =
    for
      pr    <- ZIO.service[ProfileRepo]
      sr    <- ZIO.service[ScheduleRepo]
      tlr   <- ZIO.service[TimeLimitRepo]
      stlr  <- ZIO.service[SiteTimeLimitRepo]
      dr    <- ZIO.service[DeviceRepo]
      blr   <- ZIO.service[BlocklistRepo]
      ur    <- ZIO.service[TimeUsageRepo]
      er    <- ZIO.service[TimeExtensionRepo]
      clock <- ZIO.service[Clock]
    yield (new PolicyServiceLive(pr, sr, tlr, stlr, dr, blr, ur, er, clock)): PolicyService

  private def makeRoutes(ps: PolicyService, rRepo: RouterRepo) =
    RouterRoutes.routes(rRepo, ps, RouterAuthLive(rRepo))

  private def decisionReq(mac: String, hostname: String, token: Option[String]): Request =
    val body = RouterDecisionRequest(mac, hostname).toJson
    val base = Request.post(
      URL.decode("/api/router/decision").toOption.get,
      Body.fromString(body),
    )
    token.fold(base)(t => base.addHeader(Header.Authorization.Bearer(t)))

  def spec = suite("POST /api/router/decision")(
    test("no bearer → 401") {
      for
        _     <- cleanDb
        rRepo <- ZIO.service[RouterRepo]
        ps    <- makePolicyService
        resp  <- makeRoutes(ps, rRepo).runZIO(
          decisionReq("aa:bb:cc:dd:ee:ff", "example.com", None),
        )
      yield assertTrue(resp.status == Status.Unauthorized)
    },
    test("MAC not in devices table → allow, no_profile") {
      for
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        (_, tok) <- seedEnrolledRouter(rRepo)
        ps       <- makePolicyService
        resp     <- makeRoutes(ps, rRepo).runZIO(
          decisionReq("aa:bb:cc:99:99:99", "example.com", Some(tok)),
        )
        body     <- resp.body.asString
        out      <- ZIO.fromEither(body.fromJson[RouterDecisionResponse])
      yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(out.decision == "allow") &&
        assertTrue(out.reason == "no_profile")
    },
    test("MAC in devices with NULL profile_id → allow, no_profile") {
      for
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        dRepo    <- ZIO.service[DeviceRepo]
        (_, tok) <- seedEnrolledRouter(rRepo)
        mac = "aa:bb:cc:11:22:99"
        _    <- dRepo.upsertUnknown(mac, "mystery-laptop", Some("10.0.0.5"), Instant.now())
        ps   <- makePolicyService
        resp <- makeRoutes(ps, rRepo).runZIO(
          decisionReq(mac, "example.com", Some(tok)),
        )
        body <- resp.body.asString
        out  <- ZIO.fromEither(body.fromJson[RouterDecisionResponse])
      yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(out.decision == "allow") &&
        assertTrue(out.reason == "no_profile")
    },
    test("MAC with assigned profile and unblocked hostname → allow (not no_profile)") {
      for
        _        <- cleanDb
        rRepo    <- ZIO.service[RouterRepo]
        pRepo    <- ZIO.service[ProfileRepo]
        dRepo    <- ZIO.service[DeviceRepo]
        (_, tok) <- seedEnrolledRouter(rRepo)
        pid      <- pRepo.create("Kids", List("adult"))
        mac = "aa:bb:cc:11:22:33"
        _    <- dRepo.upsert(mac, "kid-ipad", pid, "192.168.1.10")
        ps   <- makePolicyService
        resp <- makeRoutes(ps, rRepo).runZIO(
          decisionReq(mac, "example.com", Some(tok)),
        )
        body <- resp.body.asString
        out  <- ZIO.fromEither(body.fromJson[RouterDecisionResponse])
      yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(out.decision == "allow") &&
        assertTrue(out.reason != "no_profile")
    },
  ) @@ TestAspect.sequential
}
