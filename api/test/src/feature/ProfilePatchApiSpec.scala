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
import zio.test.*

// #423: PATCH /api/profiles/:id — partial, field-scoped updates so the SPA
// can issue per-field writes (e.g. a paused toggle, an autosave on name) and
// avoid the read-then-write clobber that today's PUT requires.
//
// Tri-state JSON semantics via FieldPatch:
//   - field absent  → preserve existing value
//   - field == null → clear (only legal on nullable fields, currently `timeLimit`)
//   - field == v    → assign v
object ProfilePatchApiSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg   = JwtConfig(secret = "test-secret-at-least-32-chars!!", expiryHours = 1)
  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)
  private val cleanDb  = TestDatabase.cleanAndMigrate

  private def url(p: String) = URL.decode(p).toOption.get

  private def setupKids(timeLimit: Option[Int] = Some(120)) =
    for {
      _           <- cleanDb
      profileRepo <- ZIO.service[ProfileRepo]
      tlRepo      <- ZIO.service[TimeLimitRepo]
      profiles    <- profileRepo.listAll
      kids = profiles.find(_.name == "Kids").get
      _ <- timeLimit match {
        case Some(m) => tlRepo.upsert(kids.id, m)
        case None    => tlRepo.delete(kids.id)
      }
    } yield kids

  private def routesAndToken =
    for {
      auth            <- makeAuth
      profileRepo     <- ZIO.service[ProfileRepo]
      tlRepo          <- ZIO.service[TimeLimitRepo]
      userProfileRepo <- ZIO.service[UserProfileRepo]
      userRepo        <- ZIO.service[UserRepo]
      token           <- auth.login("admin", "changeme").map(_.token.value)
    } yield (
      ProfileRoutes.routes(auth, profileRepo, tlRepo, userProfileRepo, userRepo),
      token,
    )

  private def patch(routes: Routes[Any, Response], token: String, pid: ProfileId, body: String) =
    routes.runZIO(
      Request
        .patch(url(s"/api/profiles/${pid.value}"), Body.fromString(body))
        .addHeader(Header.Authorization.Bearer(token))
        .addHeader(Header.ContentType(MediaType.application.json)),
    )

  def spec = suite("PATCH /api/profiles/:id (#423)")(
    test("single-field patch updates only `paused`; all other fields unchanged") {
      for {
        kids         <- setupKids()
        (routes, tk) <- routesAndToken
        profileRepo  <- ZIO.service[ProfileRepo]
        tlRepo       <- ZIO.service[TimeLimitRepo]
        before       <- profileRepo.findById(kids.id).someOrFailException
        beforeTl     <- tlRepo.findForProfile(kids.id)
        resp         <- patch(routes, tk, kids.id, """{"paused":true}""")
        after        <- profileRepo.findById(kids.id).someOrFailException
        afterTl      <- tlRepo.findForProfile(kids.id)
      } yield assertTrue(
        resp.status == Status.Ok,
        after.paused, // newly true
        !before.paused,
        after.name == before.name,
        after.blockedCategories == before.blockedCategories,
        after.failureMode == before.failureMode,
        after.blockIpOnly == before.blockIpOnly,
        after.crossDeviceOverlapMode == before.crossDeviceOverlapMode,
        after.pauseMode == before.pauseMode,
        after.defaultDeny == before.defaultDeny,
        afterTl == beforeTl,
      )
    },
    test("single-field patch updates only `name`") {
      for {
        kids         <- setupKids()
        (routes, tk) <- routesAndToken
        profileRepo  <- ZIO.service[ProfileRepo]
        resp         <- patch(routes, tk, kids.id, """{"name":"Littles"}""")
        after        <- profileRepo.findById(kids.id).someOrFailException
      } yield assertTrue(
        resp.status == Status.Ok,
        after.name == "Littles",
        after.paused == kids.paused,
      )
    },
    test("multi-field patch updates each supplied field") {
      for {
        kids         <- setupKids()
        (routes, tk) <- routesAndToken
        profileRepo  <- ZIO.service[ProfileRepo]
        tlRepo       <- ZIO.service[TimeLimitRepo]
        resp         <- patch(
          routes,
          tk,
          kids.id,
          """{"paused":true,"blockIpOnly":true,"defaultDeny":true,"timeLimit":45}""",
        )
        after        <- profileRepo.findById(kids.id).someOrFailException
        afterTl      <- tlRepo.findForProfile(kids.id)
      } yield assertTrue(
        resp.status == Status.Ok,
        after.paused,
        after.blockIpOnly,
        after.defaultDeny,
        afterTl.map(_.dailyMinutes).contains(45),
      )
    },
    test("empty patch preserves every field") {
      for {
        kids         <- setupKids(timeLimit = Some(90))
        (routes, tk) <- routesAndToken
        profileRepo  <- ZIO.service[ProfileRepo]
        tlRepo       <- ZIO.service[TimeLimitRepo]
        resp         <- patch(routes, tk, kids.id, "{}")
        after        <- profileRepo.findById(kids.id).someOrFailException
        afterTl      <- tlRepo.findForProfile(kids.id)
      } yield assertTrue(
        resp.status == Status.Ok,
        after == kids,
        afterTl.map(_.dailyMinutes).contains(90),
      )
    },
    test("null on nullable `timeLimit` clears the row") {
      for {
        kids         <- setupKids(timeLimit = Some(60))
        (routes, tk) <- routesAndToken
        tlRepo       <- ZIO.service[TimeLimitRepo]
        resp         <- patch(routes, tk, kids.id, """{"timeLimit":null}""")
        afterTl      <- tlRepo.findForProfile(kids.id)
      } yield assertTrue(resp.status == Status.Ok, afterTl.isEmpty)
    },
    test("setting `timeLimit` upserts the row when none existed") {
      for {
        kids         <- setupKids(timeLimit = None)
        (routes, tk) <- routesAndToken
        tlRepo       <- ZIO.service[TimeLimitRepo]
        resp         <- patch(routes, tk, kids.id, """{"timeLimit":75}""")
        afterTl      <- tlRepo.findForProfile(kids.id)
      } yield assertTrue(resp.status == Status.Ok, afterTl.map(_.dailyMinutes).contains(75))
    },
    test("null on non-nullable `name` returns 400") {
      for {
        kids         <- setupKids()
        (routes, tk) <- routesAndToken
        resp         <- patch(routes, tk, kids.id, """{"name":null}""")
      } yield assertTrue(resp.status == Status.BadRequest)
    },
    test("null on non-nullable `paused` returns 400") {
      for {
        kids         <- setupKids()
        (routes, tk) <- routesAndToken
        resp         <- patch(routes, tk, kids.id, """{"paused":null}""")
      } yield assertTrue(resp.status == Status.BadRequest)
    },
    test("invalid JSON body returns 400") {
      for {
        kids         <- setupKids()
        (routes, tk) <- routesAndToken
        resp         <- patch(routes, tk, kids.id, "not json")
      } yield assertTrue(resp.status == Status.BadRequest)
    },
    test("404 when profile is missing") {
      for {
        _            <- cleanDb
        (routes, tk) <- routesAndToken
        resp         <- patch(routes, tk, ProfileId(99999), """{"paused":true}""")
      } yield assertTrue(resp.status == Status.NotFound)
    },
    test("401 without token") {
      for {
        kids   <- setupKids()
        routes <- routesAndToken.map(_._1)
        resp   <- routes.runZIO(
          Request
            .patch(url(s"/api/profiles/${kids.id.value}"), Body.fromString("""{"paused":true}"""))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
      } yield assertTrue(resp.status == Status.Unauthorized)
    },
  ) @@ TestAspect.sequential
}
