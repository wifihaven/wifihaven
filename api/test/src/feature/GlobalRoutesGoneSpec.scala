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
import zio.test.*

// #1772 — the /api/global/... routes are gone (deleted in #1775); the SPA targets the per-profile
// routes on globalProfile.id instead. Pin the 404s so a future revert can't quietly re-introduce
// a parallel surface. Also pin GET /api/profiles/global (#1773) — the SPA's one window into the
// sentinel — including its admin gate and that the literal "global" route precedence over the
// /profiles/long("id") matcher holds (a reorder would flip the request to the long(id) handler
// and the SPA would silently lose its global-policy surface).
object GlobalRoutesGoneSpec extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb  = TestDatabase.cleanAndMigrate
  private val adminJwt = JwtConfig(secret = "test-secret-at-least-32-chars!!", expiryHours = 1)

  private val deletedPaths = List(
    "/api/global",
    "/api/global/policy",
    "/api/global/allow",
    "/api/global/blocks",
    "/api/global/blocklists",
    "/api/global/flags",
  )

  def spec = suite("Global routes deleted (#1772) + sentinel surface (#1773)")(
    test("every former /api/global/* path 404s through the assembled profile routes") {
      for {
        _     <- cleanDb
        pr    <- ZIO.service[ProfileRepo]
        tlr   <- ZIO.service[TimeLimitRepo]
        upr   <- ZIO.service[UserProfileRepo]
        ur    <- ZIO.service[UserRepo]
        nsr   <- ZIO.service[NamedScheduleRepo]
        clock <- ZIO.service[Clock]
        auth = AuthServiceLive(ur, adminJwt, clock)
        token <- auth.login("admin", "changeme").map(_.token.value)
        routes = ProfileRoutes.routes(auth, pr, tlr, upr, ur, nsr)
        statuses <- ZIO.foreach(deletedPaths) { path =>
          val req = Request
            .get(URL.decode(path).toOption.get)
            .addHeader(Header.Authorization.Bearer(token))
          routes.runZIO(req).map(_.status)
        }
      } yield assertTrue(statuses.forall(_ == Status.NotFound))
    },
    test("GET /api/profiles/global returns 200 with the is_global=TRUE sentinel for admins") {
      for {
        _     <- cleanDb
        pr    <- ZIO.service[ProfileRepo]
        tlr   <- ZIO.service[TimeLimitRepo]
        upr   <- ZIO.service[UserProfileRepo]
        ur    <- ZIO.service[UserRepo]
        nsr   <- ZIO.service[NamedScheduleRepo]
        clock <- ZIO.service[Clock]
        auth = AuthServiceLive(ur, adminJwt, clock)
        token <- auth.login("admin", "changeme").map(_.token.value)
        routes = ProfileRoutes.routes(auth, pr, tlr, upr, ur, nsr)
        req    = Request
          .get(URL.decode("/api/profiles/global").toOption.get)
          .addHeader(Header.Authorization.Bearer(token))
        resp <- routes.runZIO(req)
        body <- resp.body.asString
      } yield assertTrue(resp.status == Status.Ok) &&
        // The literal "global" route precedes /profiles/long("id") and resolves to the sentinel —
        // not a 404 from the long(id) handler trying to parse "global" as a Long, and not a
        // collision with a numeric profile id.
        assertTrue(body.contains("\"isGlobal\":true")) &&
        assertTrue(body.contains("\"name\":\"Global\""))
    },
    test("GET /api/profiles/global requires an admin token") {
      for {
        _     <- cleanDb
        pr    <- ZIO.service[ProfileRepo]
        tlr   <- ZIO.service[TimeLimitRepo]
        upr   <- ZIO.service[UserProfileRepo]
        ur    <- ZIO.service[UserRepo]
        nsr   <- ZIO.service[NamedScheduleRepo]
        clock <- ZIO.service[Clock]
        auth    = AuthServiceLive(ur, adminJwt, clock)
        routes  = ProfileRoutes.routes(auth, pr, tlr, upr, ur, nsr)
        anonReq = Request.get(URL.decode("/api/profiles/global").toOption.get)
        anonResp <- routes.runZIO(anonReq)
      } yield assertTrue(anonResp.status == Status.Unauthorized)
    },
  ) @@ TestAspect.sequential
}
