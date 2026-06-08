package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
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
import zio.test.Assertion.*

/**
 * Feature tests for the Profile API.
 *
 * These exercise the full stack: HTTP handler → service → real Postgres. No mocks except Clock and
 * upstream DNS socket.
 *
 * Post-#764: profile-side `extraBlocked`/`extraAllowed`/`appTimeLimits` were migrated into the apps
 * schema and dropped from the wire — those concerns are exercised via app endpoints/specs.
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

  private val cleanDb = TestDatabase.cleanAndMigrate

  private def mkRoutes =
    for {
      profileRepo     <- ZIO.service[ProfileRepo]
      schedRepo       <- ZIO.service[ScheduleRepo]
      tlRepo          <- ZIO.service[TimeLimitRepo]
      auth            <- makeAuth
      userProfileRepo <- ZIO.service[UserProfileRepo]
      userRepoSvc     <- ZIO.service[UserRepo]
    } yield (
      auth,
      ProfileRoutes.routes(auth, profileRepo, schedRepo, tlRepo, userProfileRepo, userRepoSvc),
    )

  def spec = suite("Profile API")(
    suite("GET /api/profiles")(
      test("returns seeded default profiles") {
        for {
          _              <- cleanDb
          (auth, routes) <- mkRoutes
          token          <- auth.login("admin", "changeme").map(_.token.value)
          req = Request
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
          (_, routes) <- mkRoutes
          req = Request.get(URL.decode("/api/profiles").toOption.get)
          resp <- routes.runZIO(req)
        } yield assertTrue(resp.status == Status.Unauthorized)
      },
    ),
    suite("POST /api/profiles")(
      test("admin can create a profile with categories and time limits") {
        for {
          _              <- cleanDb
          profileRepo    <- ZIO.service[ProfileRepo]
          tlRepo         <- ZIO.service[TimeLimitRepo]
          (auth, routes) <- mkRoutes
          token          <- auth.login("admin", "changeme").map(_.token.value)
          // #1494: schedules are no longer part of the upsert; block schedules
          // attach via PUT /api/profiles/{id}/schedules (see SchedulesApiSpec).
          body = UpsertProfileRequest(
            name = "Teenager",
            blockedCategories = List(BlocklistId.unsafe("adult"), BlocklistId.unsafe("gambling")),
            paused = false,
            timeLimit = Some(180),
          ).toJson
          req  = Request
            .post(URL.decode("/api/profiles").toOption.get, Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json))
          resp     <- routes.runZIO(req)
          profiles <- profileRepo.listAll
          teen     <- ZIO
            .fromOption(profiles.find(_.name == "Teenager"))
            .orElseFail(new Exception("Profile not found"))
          tl       <- tlRepo.findForProfile(teen.id)
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(teen.blockedCategories.contains(BlocklistId.unsafe("adult"))) &&
          assertTrue(tl.exists(_.dailyMinutes == 180))
      },
      // #1494: enforcement now reads schedules from named_schedules /
      // profile_schedule_rules (#1482/#1490). The profile upsert must therefore
      // STOP writing the dead V1 `schedules` table — an inline `schedules`
      // array on the request body is ignored, never persisted.
      test("profile upsert does not write the legacy schedules table (#1494)") {
        for {
          _              <- cleanDb
          profileRepo    <- ZIO.service[ProfileRepo]
          schedRepo      <- ZIO.service[ScheduleRepo]
          (auth, routes) <- mkRoutes
          token          <- auth.login("admin", "changeme").map(_.token.value)
          // Raw JSON deliberately carries a legacy inline `schedules` array; a
          // newer body would omit the field entirely. Either way nothing may
          // land in the legacy table.
          body =
            """{"name":"NoLegacy","blockedCategories":[],"paused":false,""" +
              """"schedules":[{"name":"Bedtime","days":["mon"],"startLocal":"21:00","endLocal":"07:00","tz":"UTC"}],""" +
              """"timeLimit":null}"""
          req  = Request
            .post(URL.decode("/api/profiles").toOption.get, Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json))
          resp     <- routes.runZIO(req)
          profiles <- profileRepo.listAll
          created  <- ZIO
            .fromOption(profiles.find(_.name == "NoLegacy"))
            .orElseFail(new Exception("Profile not found"))
          scheds   <- schedRepo.listForProfile(created.id)
        } yield assertTrue(resp.status == Status.Ok) && assertTrue(scheds.isEmpty)
      },
      test("failureMode defaults to LastKnownGood when omitted from create request (#385)") {
        for {
          _              <- cleanDb
          profileRepo    <- ZIO.service[ProfileRepo]
          (auth, routes) <- mkRoutes
          token          <- auth.login("admin", "changeme").map(_.token.value)
          body =
            """{"name":"Defaulted","blockedCategories":[],"paused":false,"schedules":[],"timeLimit":null}"""
          req  = Request
            .post(URL.decode("/api/profiles").toOption.get, Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json))
          _        <- routes.runZIO(req)
          profiles <- profileRepo.listAll
          found = profiles.find(_.name == "Defaulted").get
        } yield assertTrue(found.failureMode == FailureMode.LastKnownGood)
      },
      test("failureMode=AllowAll in create request persists AllowAll (#385 admin override)") {
        for {
          _              <- cleanDb
          profileRepo    <- ZIO.service[ProfileRepo]
          (auth, routes) <- mkRoutes
          token          <- auth.login("admin", "changeme").map(_.token.value)
          body = UpsertProfileRequest(
            name = "AdultsAllowAll",
            blockedCategories = Nil,
            paused = false,
            timeLimit = None,
            failureMode = Some(FailureMode.AllowAll),
          ).toJson
          req  = Request
            .post(URL.decode("/api/profiles").toOption.get, Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json))
          _        <- routes.runZIO(req)
          profiles <- profileRepo.listAll
          found = profiles.find(_.name == "AdultsAllowAll").get
        } yield assertTrue(found.failureMode == FailureMode.AllowAll)
      },
      test("PUT /api/profiles/:id updates failureMode") {
        for {
          _              <- cleanDb
          profileRepo    <- ZIO.service[ProfileRepo]
          (auth, routes) <- mkRoutes
          token          <- auth.login("admin", "changeme").map(_.token.value)
          profiles0      <- profileRepo.listAll
          kidsId = profiles0.find(_.name == "Kids").get.id
          body   = UpsertProfileRequest(
            name = "Kids",
            blockedCategories = Nil,
            paused = false,
            timeLimit = None,
            failureMode = Some(FailureMode.AllowAll),
          ).toJson
          req    = Request
            .put(URL.decode(s"/api/profiles/$kidsId").toOption.get, Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
            .addHeader(Header.ContentType(MediaType.application.json))
          resp    <- routes.runZIO(req)
          updated <- profileRepo.findById(kidsId)
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(updated.exists(_.failureMode == FailureMode.AllowAll))
      },
      test("child user cannot create profiles") {
        for {
          _              <- cleanDb
          userRepo       <- ZIO.service[UserRepo]
          (auth, routes) <- mkRoutes
          hash           <- auth.hashPassword("readpass")
          _              <- userRepo.create("reader", hash, "child")
          token          <- auth.login("reader", "readpass").map(_.token.value)
          body = UpsertProfileRequest("Test", Nil, false, None).toJson
          req  = Request
            .post(URL.decode("/api/profiles").toOption.get, Body.fromString(body))
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
        } yield assertTrue(resp.status == Status.Forbidden)
      },
    ),
    suite("PUT /api/profiles/:id")(
      test("update profile name, categories, and time limit") {
        for {
          _              <- cleanDb
          profileRepo    <- ZIO.service[ProfileRepo]
          tlRepo         <- ZIO.service[TimeLimitRepo]
          (auth, routes) <- mkRoutes
          token          <- auth.login("admin", "changeme").map(_.token.value)
          profiles       <- profileRepo.listAll
          kidsId = profiles.find(_.name == "Kids").get.id
          body   = UpsertProfileRequest(
            name = "Kids Updated",
            blockedCategories = List(BlocklistId.unsafe("adult")),
            paused = false,
            timeLimit = Some(120),
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
        } yield assertTrue(resp.status == Status.Ok) &&
          assertTrue(updated.exists(_.name == "Kids Updated")) &&
          assertTrue(tl.exists(_.dailyMinutes == 120))
      },
    ),
    suite("PUT /api/profiles/:id pause is idempotent (#406)")(
      // #406: the toggle endpoint POST /api/profiles/:id/pause was deleted
      // because it was a read-then-write race (two near-simultaneous calls
      // silently flip state twice). Callers must now set `paused` explicitly
      // via the full PUT, which is idempotent by construction.
      test("two consecutive PUT(paused=true) leave state at paused=true") {
        for {
          _              <- cleanDb
          profileRepo    <- ZIO.service[ProfileRepo]
          (auth, routes) <- mkRoutes
          token          <- auth.login("admin", "changeme").map(_.token.value)
          profiles       <- profileRepo.listAll
          kidsId = profiles.find(_.name == "Kids").get.id
          mkReq  = (paused: Boolean) => {
            val body = UpsertProfileRequest(
              name = "Kids",
              blockedCategories = Nil,
              paused = paused,
              timeLimit = None,
            ).toJson
            Request
              .put(URL.decode(s"/api/profiles/$kidsId").toOption.get, Body.fromString(body))
              .addHeader(Header.Authorization.Bearer(token))
              .addHeader(Header.ContentType(MediaType.application.json))
          }
          resp1 <- routes.runZIO(mkReq(true))
          after1 <- profileRepo.findById(kidsId)
          resp2  <- routes.runZIO(mkReq(true))
          after2 <- profileRepo.findById(kidsId)
          resp3  <- routes.runZIO(mkReq(false))
          after3 <- profileRepo.findById(kidsId)
        } yield assertTrue(resp1.status == Status.Ok) &&
          assertTrue(after1.exists(_.paused)) &&
          assertTrue(resp2.status == Status.Ok) &&
          assertTrue(after2.exists(_.paused)) &&
          assertTrue(resp3.status == Status.Ok) &&
          assertTrue(after3.exists(!_.paused))
      },
      test("legacy POST /api/profiles/:id/pause is removed (404)") {
        for {
          _              <- cleanDb
          profileRepo    <- ZIO.service[ProfileRepo]
          (auth, routes) <- mkRoutes
          token          <- auth.login("admin", "changeme").map(_.token.value)
          profiles       <- profileRepo.listAll
          kidsId = profiles.find(_.name == "Kids").get.id
          req    = Request
            .post(URL.decode(s"/api/profiles/$kidsId/pause").toOption.get, Body.empty)
            .addHeader(Header.Authorization.Bearer(token))
          resp <- routes.runZIO(req)
        } yield assertTrue(resp.status == Status.NotFound)
      },
    ),
    suite("Profile ↔ user linking")(
      test("PUT /api/profiles/:id/users sets the link set; GET returns those users") {
        for {
          _              <- cleanDb
          profileRepo    <- ZIO.service[ProfileRepo]
          userRepo       <- ZIO.service[UserRepo]
          (auth, routes) <- mkRoutes
          token          <- auth.login("admin", "changeme").map(_.token.value)
          hash           <- auth.hashPassword("pw")
          aliceId        <- userRepo.create("alice", hash, "child")
          bobId          <- userRepo.create("bob", hash, "adult")
          profiles       <- profileRepo.listAll
          kidsId  = profiles.find(_.name == "Kids").get.id
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
          userRepo        <- ZIO.service[UserRepo]
          userProfileRepo <- ZIO.service[UserProfileRepo]
          (auth, routes)  <- mkRoutes
          token           <- auth.login("admin", "changeme").map(_.token.value)
          hash            <- auth.hashPassword("pw")
          aliceId         <- userRepo.create("alice", hash, "child")
          profiles        <- profileRepo.listAll
          kidsId   = profiles.find(_.name == "Kids").get.id
          adultsId = profiles.find(_.name == "Adults").get.id
          _ <- userProfileRepo.setProfilesForUser(aliceId, List(kidsId, adultsId))
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
          _              <- cleanDb
          profileRepo    <- ZIO.service[ProfileRepo]
          userRepo       <- ZIO.service[UserRepo]
          (auth, routes) <- mkRoutes
          hash           <- auth.hashPassword("pw")
          _              <- userRepo.create("adult1", hash, "adult")
          token          <- auth.login("adult1", "pw").map(_.token.value)
          profiles       <- profileRepo.listAll
          kidsId = profiles.find(_.name == "Kids").get.id
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
