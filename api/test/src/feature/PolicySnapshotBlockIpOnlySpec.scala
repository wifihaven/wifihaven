package familydns.api.feature

import familydns.api.JwtConfig
import familydns.api.auth.*
import familydns.api.db.*
import familydns.api.policy.*
import familydns.api.routes.*
import familydns.shared.*
import familydns.shared.types.*
import familydns.shared.Clock.TestClock
import familydns.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

/**
 * #424: per-profile BlockRules.blockIpOnly. Router-side enforcement landed in #353 (PR #426); the
 * BlockRules wire field landed in #354. This spec covers the API plumbing: snapshot reflects the
 * profile column, the PUT/POST endpoints round-trip the field, omitted field preserves prior value
 * on PUT, and ETag flips when the toggle changes.
 */
object PolicySnapshotBlockIpOnlySpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val adminJwt = JwtConfig(secret = "test-secret-at-least-32-chars!!", expiryHours = 1)

  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, adminJwt, clock)

  private def cleanDb = ZIO.serviceWithZIO[EmbeddedPostgres](pg =>
    TestDatabase.cleanAndMigrate.provide(ZLayer.succeed(pg)),
  )

  def spec = suite("PolicySnapshot — blockIpOnly (#424)")(
    test("default is false for seeded profiles") {
      for {
        _        <- cleanDb
        pr       <- ZIO.service[ProfileRepo]
        profiles <- pr.listAll
      } yield assertTrue(profiles.forall(!_.blockIpOnly))
    },
    test("snapshot carries each profile's blockIpOnly value") {
      for {
        _      <- cleanDb
        pr     <- ZIO.service[ProfileRepo]
        sr     <- ZIO.service[ScheduleRepo]
        tlr    <- ZIO.service[TimeLimitRepo]
        stlr   <- ZIO.service[SiteTimeLimitRepo]
        dr     <- ZIO.service[DeviceRepo]
        blr    <- ZIO.service[BlocklistRepo]
        trRepo <- ZIO.service[TrafficReportRepo]
        er     <- ZIO.service[TimeExtensionRepo]
        hsr    <- ZIO.service[HouseholdSettingsRepo]
        clock  <- ZIO.service[Clock]
        svc = PolicyServiceLive(pr, sr, hsr, tlr, stlr, dr, blr, trRepo, er, clock)
        profiles0 <- pr.listAll
        kidsId   = profiles0.find(_.name == "Kids").get.id
        adultsId = profiles0.find(_.name == "Adults").get.id
        _    <- pr.update(profiles0.find(_.name == "Kids").get.copy(blockIpOnly = true))
        _    <- pr.update(profiles0.find(_.name == "Adults").get.copy(blockIpOnly = false))
        snap <- svc.snapshot
      } yield assertTrue(snap.profiles(kidsId).rules.blockIpOnly) &&
        assertTrue(!snap.profiles(adultsId).rules.blockIpOnly)
    },
    test("ETag flips when blockIpOnly toggles") {
      for {
        _      <- cleanDb
        pr     <- ZIO.service[ProfileRepo]
        sr     <- ZIO.service[ScheduleRepo]
        tlr    <- ZIO.service[TimeLimitRepo]
        stlr   <- ZIO.service[SiteTimeLimitRepo]
        dr     <- ZIO.service[DeviceRepo]
        blr    <- ZIO.service[BlocklistRepo]
        trRepo <- ZIO.service[TrafficReportRepo]
        er     <- ZIO.service[TimeExtensionRepo]
        hsr    <- ZIO.service[HouseholdSettingsRepo]
        clock  <- ZIO.service[Clock]
        svc = PolicyServiceLive(pr, sr, hsr, tlr, stlr, dr, blr, trRepo, er, clock)
        profiles0 <- pr.listAll
        kids = profiles0.find(_.name == "Kids").get
        _     <- pr.update(kids.copy(blockIpOnly = false))
        snap1 <- svc.snapshot
        _     <- pr.update(kids.copy(blockIpOnly = true))
        snap2 <- svc.snapshot
      } yield assertTrue(snap1.etag != snap2.etag)
    },
    test("PUT /api/profiles/:id round-trips blockIpOnly=true then back to false") {
      for {
        _           <- cleanDb
        profileRepo <- ZIO.service[ProfileRepo]
        schedRepo   <- ZIO.service[ScheduleRepo]
        tlRepo      <- ZIO.service[TimeLimitRepo]
        stlRepo     <- ZIO.service[SiteTimeLimitRepo]
        auth        <- makeAuth
        token       <- auth.login("admin", "changeme").map(_.token.value)
        profiles0   <- profileRepo.listAll
        kidsId = profiles0.find(_.name == "Kids").get.id
        userProfileRepo <- ZIO.service[UserProfileRepo]
        userRepoSvc     <- ZIO.service[UserRepo]
        routes = ProfileRoutes.routes(
          auth,
          profileRepo,
          schedRepo,
          tlRepo,
          stlRepo,
          userProfileRepo,
          userRepoSvc,
        )
        mkReq  = (ipOnly: Option[Boolean]) => {
          val body = UpsertProfileRequest(
            name = "Kids",
            blockedCategories = Nil,
            extraBlocked = Nil,
            extraAllowed = Nil,
            paused = false,
            schedules = Nil,
            timeLimit = None,
            siteTimeLimits = Nil,
            blockIpOnly = ipOnly,
          ).toJson
          Request
            .put(URL.decode(s"/api/profiles/$kidsId").toOption.get, Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json))
        }
        // Initial state: false (column default).
        before <- profileRepo.findById(kidsId)
        // Flip true.
        respOn    <- routes.runZIO(mkReq(Some(true)))
        afterOn   <- profileRepo.findById(kidsId)
        // Omit → preserve true (PATCH semantics).
        respKeep  <- routes.runZIO(mkReq(None))
        afterKeep <- profileRepo.findById(kidsId)
        // Flip back to false.
        respOff   <- routes.runZIO(mkReq(Some(false)))
        afterOff  <- profileRepo.findById(kidsId)
      } yield assertTrue(before.exists(!_.blockIpOnly)) &&
        assertTrue(respOn.status == Status.Ok) &&
        assertTrue(afterOn.exists(_.blockIpOnly)) &&
        assertTrue(respKeep.status == Status.Ok) &&
        assertTrue(afterKeep.exists(_.blockIpOnly)) &&
        assertTrue(respOff.status == Status.Ok) &&
        assertTrue(afterOff.exists(!_.blockIpOnly))
    },
    test("POST /api/profiles defaults blockIpOnly=false when omitted, persists true when set") {
      for {
        _               <- cleanDb
        profileRepo     <- ZIO.service[ProfileRepo]
        schedRepo       <- ZIO.service[ScheduleRepo]
        tlRepo          <- ZIO.service[TimeLimitRepo]
        stlRepo         <- ZIO.service[SiteTimeLimitRepo]
        auth            <- makeAuth
        token           <- auth.login("admin", "changeme").map(_.token.value)
        userProfileRepo <- ZIO.service[UserProfileRepo]
        userRepoSvc     <- ZIO.service[UserRepo]
        routes = ProfileRoutes.routes(
          auth,
          profileRepo,
          schedRepo,
          tlRepo,
          stlRepo,
          userProfileRepo,
          userRepoSvc,
        )
        mkReq  = (name: String, ipOnly: Option[Boolean]) => {
          val body = UpsertProfileRequest(
            name = name,
            blockedCategories = Nil,
            extraBlocked = Nil,
            extraAllowed = Nil,
            paused = false,
            schedules = Nil,
            timeLimit = None,
            siteTimeLimits = Nil,
            blockIpOnly = ipOnly,
          ).toJson
          Request
            .post(URL.decode("/api/profiles").toOption.get, Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json))
        }
        _ <- routes.runZIO(mkReq("Defaulted", None))
        _        <- routes.runZIO(mkReq("Strict", Some(true)))
        profiles <- profileRepo.listAll
        defaulted = profiles.find(_.name == "Defaulted").get
        strict    = profiles.find(_.name == "Strict").get
      } yield assertTrue(!defaulted.blockIpOnly) && assertTrue(strict.blockIpOnly)
    },
  ) @@ TestAspect.sequential
}
