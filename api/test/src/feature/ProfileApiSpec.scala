package familydns.api.feature

import familydns.api.JwtConfig
import familydns.api.auth.*
import familydns.api.db.*
import familydns.api.routes.*
import familydns.shared.*
import familydns.shared.Clock.TestClock
import familydns.testinfra.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*
import zio.test.Assertion.*

/**
 * Feature tests for the Profile API.
 *
 * These exercise the full stack: HTTP handler → service → real Postgres. No mocks except Clock and
 * upstream DNS socket.
 */
object ProfileApiSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap: ZLayer[Any, Throwable, TestDatabase.AllRepos & EmbeddedPostgres & Clock] =
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

  def spec = suite("Profile API")(
    suite("GET /api/profiles")(
      test("returns seeded default profiles") {
        for {
          _               <- cleanDb
          profileRepo     <- ZIO.service[ProfileRepo]
          schedRepo       <- ZIO.service[ScheduleRepo]
          tlRepo          <- ZIO.service[TimeLimitRepo]
          stlRepo         <- ZIO.service[SiteTimeLimitRepo]
          auth            <- makeAuth
          userRepo        <- ZIO.service[UserRepo]
          token           <- auth.login("admin", "changeme").map(_.token)
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
          req    = Request
            .get(URL.decode("/api/profiles").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          resp    <- routes.runZIO(req)
          body    <- resp.body.asString
          details <- ZIO.fromEither(body.fromJson[List[ProfileDetail]])
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(details.length >= 2) &&
          assertTrue(details.exists(_.profile.name == "Kids")) &&
          assertTrue(details.exists(_.profile.name == "Adults"))
      },
      test("returns 401 without token") {
        for {
          profileRepo     <- ZIO.service[ProfileRepo]
          schedRepo       <- ZIO.service[ScheduleRepo]
          tlRepo          <- ZIO.service[TimeLimitRepo]
          stlRepo         <- ZIO.service[SiteTimeLimitRepo]
          auth            <- makeAuth
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
          req    = Request.get(URL.decode("/api/profiles").toOption.get)
          resp <- routes.runZIO(req)
        } yield assertTrue(resp.status == Status.Unauthorized)
      },
    ),
    suite("POST /api/profiles")(
      test("admin can create a profile with schedules and time limits") {
        for {
          _               <- cleanDb
          profileRepo     <- ZIO.service[ProfileRepo]
          schedRepo       <- ZIO.service[ScheduleRepo]
          tlRepo          <- ZIO.service[TimeLimitRepo]
          stlRepo         <- ZIO.service[SiteTimeLimitRepo]
          auth            <- makeAuth
          token           <- auth.login("admin", "changeme").map(_.token)
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
          body   = UpsertProfileRequest(
            name = "Teenager",
            blockedCategories = List("adult", "gambling"),
            extraBlocked = List("tiktok.com"),
            extraAllowed = List("khanacademy.org"),
            paused = false,
            schedules = List(
              ScheduleRequest("Bedtime", List("mon", "tue", "wed", "thu", "fri"), "22:00", "08:00"),
            ),
            timeLimit = Some(180),
            siteTimeLimits = List(
              SiteTimeLimitRequest("*.youtube.com", 45, "YouTube"),
            ),
          ).toJson
          req    = Request
            .post(URL.decode("/api/profiles").toOption.get, Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json))
          resp     <- routes.runZIO(req)
          // Verify everything was persisted
          profiles <- profileRepo.listAll
          teen     <- ZIO
            .fromOption(profiles.find(_.name == "Teenager"))
            .orElseFail(new Exception("Profile not found"))
          scheds   <- schedRepo.listForProfile(teen.id)
          tl       <- tlRepo.findForProfile(teen.id)
          stls     <- stlRepo.listForProfile(teen.id)
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(teen.blockedCategories.contains("adult")) &&
          assertTrue(teen.extraBlocked.contains("tiktok.com")) &&
          assertTrue(teen.extraAllowed.contains("khanacademy.org")) &&
          assertTrue(scheds.length == 1) &&
          assertTrue(scheds.head.blockFrom == "22:00") &&
          assertTrue(tl.exists(_.dailyMinutes == 180)) &&
          assertTrue(stls.length == 1) &&
          assertTrue(stls.head.label == "YouTube") &&
          assertTrue(stls.head.dailyMinutes == 45)
      },
      test("child user cannot create profiles") {
        for {
          _               <- cleanDb
          profileRepo     <- ZIO.service[ProfileRepo]
          schedRepo       <- ZIO.service[ScheduleRepo]
          tlRepo          <- ZIO.service[TimeLimitRepo]
          stlRepo         <- ZIO.service[SiteTimeLimitRepo]
          userRepo        <- ZIO.service[UserRepo]
          auth            <- makeAuth
          hash            <- auth.hashPassword("readpass")
          _               <- userRepo.create("reader", hash, "child")
          token           <- auth.login("reader", "readpass").map(_.token)
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
          body   = UpsertProfileRequest("Test", Nil, Nil, Nil, false, Nil, None, Nil).toJson
          req    = Request
            .post(URL.decode("/api/profiles").toOption.get, Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
        } yield assertTrue(resp.status == Status.Forbidden)
      },
    ),
    suite("PUT /api/profiles/:id")(
      test("update profile name, categories, and time limit") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          stlRepo     <- ZIO.service[SiteTimeLimitRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token)
          // Get the Kids profile id
          profiles    <- profileRepo.listAll
          kidsId = profiles.find(_.name == "Kids").get.id
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
          body   = UpsertProfileRequest(
            name = "Kids Updated",
            blockedCategories = List("adult"),
            extraBlocked = Nil,
            extraAllowed = List("pbs.org"),
            paused = false,
            schedules = List(
              ScheduleRequest("Bedtime", List("mon", "tue", "wed", "thu", "fri"), "20:00", "07:00"),
            ),
            timeLimit = Some(120),
            siteTimeLimits = Nil,
          ).toJson
          req    = Request
            .put(
              URL.decode(s"/api/profiles/$kidsId").toOption.get,
              Body.fromString(body),
            )
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json))
          resp    <- routes.runZIO(req)
          updated <- profileRepo.findById(kidsId)
          tl      <- tlRepo.findForProfile(kidsId)
          scheds  <- schedRepo.listForProfile(kidsId)
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(updated.exists(_.name == "Kids Updated")) &&
          assertTrue(updated.exists(_.extraAllowed.contains("pbs.org"))) &&
          assertTrue(tl.exists(_.dailyMinutes == 120)) &&
          assertTrue(scheds.exists(_.blockFrom == "20:00"))
      },
    ),
    suite("POST /api/profiles/:id/pause")(
      test("toggles pause state") {
        for {
          _           <- cleanDb
          profileRepo <- ZIO.service[ProfileRepo]
          schedRepo   <- ZIO.service[ScheduleRepo]
          tlRepo      <- ZIO.service[TimeLimitRepo]
          stlRepo     <- ZIO.service[SiteTimeLimitRepo]
          auth        <- makeAuth
          token       <- auth.login("admin", "changeme").map(_.token)
          profiles    <- profileRepo.listAll
          kidsId = profiles.find(_.name == "Kids").get.id
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
          req    = Request
            .post(
              URL.decode(s"/api/profiles/$kidsId/pause").toOption.get,
              Body.empty,
            )
            .addHeader(Header.Authorization.Bearer(token))
          resp1       <- routes.runZIO(req)
          afterPause  <- profileRepo.findById(kidsId)
          resp2       <- routes.runZIO(req)
          afterResume <- profileRepo.findById(kidsId)
        } yield assertTrue(resp1.status == Status.Ok) &&
          assertTrue(afterPause.exists(_.paused)) &&
          assertTrue(resp2.status == Status.Ok) &&
          assertTrue(afterResume.exists(!_.paused))
      },
    ),
    suite("Profile ↔ user linking")(
      test("PUT /api/profiles/:id/users sets the link set; GET returns those users") {
        for {
          _               <- cleanDb
          profileRepo     <- ZIO.service[ProfileRepo]
          schedRepo       <- ZIO.service[ScheduleRepo]
          tlRepo          <- ZIO.service[TimeLimitRepo]
          stlRepo         <- ZIO.service[SiteTimeLimitRepo]
          userRepo        <- ZIO.service[UserRepo]
          userProfileRepo <- ZIO.service[UserProfileRepo]
          auth            <- makeAuth
          token           <- auth.login("admin", "changeme").map(_.token)
          hash            <- auth.hashPassword("pw")
          aliceId         <- userRepo.create("alice", hash, "child")
          bobId           <- userRepo.create("bob", hash, "adult")
          profiles        <- profileRepo.listAll
          kidsId  = profiles.find(_.name == "Kids").get.id
          routes  = ProfileRoutes.routes(
            auth,
            profileRepo,
            schedRepo,
            tlRepo,
            stlRepo,
            userProfileRepo,
            userRepo,
          )
          putBody = SetProfileUsersRequest(List(aliceId, bobId)).toJson
          putReq  = Request
            .put(URL.decode(s"/api/profiles/$kidsId/users").toOption.get, Body.fromString(putBody))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json))
          putResp <- routes.runZIO(putReq)
          getReq = Request
            .get(URL.decode(s"/api/profiles/$kidsId/users").toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          getResp   <- routes.runZIO(getReq)
          body      <- getResp.body.asString
          summaries <- ZIO.fromEither(body.fromJson[List[UserSummary]])
        } yield assertTrue(putResp.status == Status.Ok) &&
          assertTrue(getResp.status == Status.Ok) &&
          assertTrue(summaries.map(_.username).toSet == Set("alice", "bob"))
      },
      test("removing a user from one profile leaves their links to other profiles intact") {
        for {
          _               <- cleanDb
          profileRepo     <- ZIO.service[ProfileRepo]
          schedRepo       <- ZIO.service[ScheduleRepo]
          tlRepo          <- ZIO.service[TimeLimitRepo]
          stlRepo         <- ZIO.service[SiteTimeLimitRepo]
          userRepo        <- ZIO.service[UserRepo]
          userProfileRepo <- ZIO.service[UserProfileRepo]
          auth            <- makeAuth
          token           <- auth.login("admin", "changeme").map(_.token)
          hash            <- auth.hashPassword("pw")
          aliceId         <- userRepo.create("alice", hash, "child")
          profiles        <- profileRepo.listAll
          kidsId   = profiles.find(_.name == "Kids").get.id
          adultsId = profiles.find(_.name == "Adults").get.id
          // Alice is linked to BOTH profiles.
          _ <- userProfileRepo.setProfilesForUser(aliceId, List(kidsId, adultsId))
          routes  = ProfileRoutes.routes(
            auth,
            profileRepo,
            schedRepo,
            tlRepo,
            stlRepo,
            userProfileRepo,
            userRepo,
          )
          // Remove alice from Kids by setting empty user list for Kids.
          putBody = SetProfileUsersRequest(Nil).toJson
          putReq  = Request
            .put(URL.decode(s"/api/profiles/$kidsId/users").toOption.get, Body.fromString(putBody))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json))
          _             <- routes.runZIO(putReq)
          aliceProfiles <- userProfileRepo.listProfilesForUser(aliceId)
        } yield assertTrue(aliceProfiles == List(adultsId))
      },
      test("non-admin gets 403 on PUT /api/profiles/:id/users") {
        for {
          _               <- cleanDb
          profileRepo     <- ZIO.service[ProfileRepo]
          schedRepo       <- ZIO.service[ScheduleRepo]
          tlRepo          <- ZIO.service[TimeLimitRepo]
          stlRepo         <- ZIO.service[SiteTimeLimitRepo]
          userRepo        <- ZIO.service[UserRepo]
          userProfileRepo <- ZIO.service[UserProfileRepo]
          auth            <- makeAuth
          hash            <- auth.hashPassword("pw")
          _               <- userRepo.create("adult1", hash, "adult")
          token           <- auth.login("adult1", "pw").map(_.token)
          profiles        <- profileRepo.listAll
          kidsId = profiles.find(_.name == "Kids").get.id
          routes = ProfileRoutes.routes(
            auth,
            profileRepo,
            schedRepo,
            tlRepo,
            stlRepo,
            userProfileRepo,
            userRepo,
          )
          body   = SetProfileUsersRequest(Nil).toJson
          req    = Request
            .put(URL.decode(s"/api/profiles/$kidsId/users").toOption.get, Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json))
          resp <- routes.runZIO(req)
        } yield assertTrue(resp.status == Status.Forbidden)
      },
    ),
  ) @@ TestAspect.sequential
}
