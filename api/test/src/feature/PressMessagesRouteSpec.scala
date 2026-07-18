package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.routes.PressRoutes
import wifihaven.shared.Clock
import wifihaven.shared.Clock.TestClock
import wifihaven.shared.types.*
import wifihaven.testinfra.*
import doobie.*
import doobie.implicits.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.interop.catz.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

/**
 * #2296 (press correspondence log, epic #2197/#2203) — the ACCESS GATE on the household-1-only
 * admin read of `GET /api/press/messages`. Press is a single company-global channel (no
 * `household_id` column), surfaced ONLY to the operator household — so the gate is: authenticated +
 * `admin` + household 1. The security-load-bearing pins:
 *   - an hh=1 admin reads the recorded correspondence (newest-first, both directions);
 *   - an hh≠1 admin gets **404** — NOT 403 — so the log's existence is not disclosed across the
 *     tenant boundary (a non-operator household cannot even learn the endpoint exists);
 *   - a non-admin (adult) gets 403 (role gate);
 *   - an unauthenticated caller gets 401.
 *
 * Embedded Postgres, NO repo mocks, Clock injected.
 */
object PressMessagesRouteSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val macA = MacAddress.unsafe("aa:bb:cc:00:00:1a")
  private val macB = MacAddress.unsafe("aa:bb:cc:00:00:1b")

  private val jwt = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)

  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      hr    <- ZIO.service[HouseholdRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwt, clock, hr): AuthService

  private def login(
      auth: AuthService,
      user: String,
      pw: String,
      slug: Option[String] = None,
  ): Task[String] =
    auth
      .login(slug.fold(user)(s => s"$s/$user"), pw)
      .mapError(e => new RuntimeException(s"login failed: $e"))
      .map(_.token.value)

  private def get(
      routes: Routes[Any, Response],
      token: Option[String],
  ): Task[(Status, String)] = {
    val base = Request.get(URL.decode("/api/press/messages").toOption.get)
    val req  = token.fold(base)(t => base.addHeader(Header.Authorization.Bearer(t)))
    routes.runZIO(req).flatMap(r => r.body.asString.map((r.status, _)))
  }

  def spec = suite("Press correspondence log — household-1 admin read gate (#2296)")(
    test("an hh=1 admin reads the recorded correspondence, newest-first") {
      for {
        _        <- cleanDb
        _        <- TestLayers.seedTwoHouseholds(macA, macB)
        pressLog <- ZIO.service[PressMessageRepo]
        auth     <- makeAuth
        // Seed a paired inbound → outbound thread.
        inId     <- pressLog.recordInbound("reporter@ex.com", "Comment?", "the question", "<m1>")
        _        <- pressLog.recordOutbound(
          "reporter@ex.com",
          "Re: Comment?",
          "the reply",
          Some(inId),
          "sent",
        )
        token    <- login(auth, "admin", "changeme")
        routes = PressRoutes.routes(auth, pressLog)
        (status, body) <- get(routes, Some(token))
        msgs = body.fromJson[List[PressMessage]].getOrElse(Nil)
      } yield assertTrue(status == Status.Ok, msgs.size == 2) &&
        // Newest-first: the outbound reply (recorded last) leads.
        assertTrue(
          msgs.head.direction == "outbound",
          msgs.head.inReplyTo.contains(inId),
          msgs.exists(m => m.direction == "inbound" && m.id == inId),
        )
    },
    test("an hh≠1 admin gets 404 — the log's existence is not disclosed across households") {
      for {
        _        <- cleanDb
        two      <- TestLayers.seedTwoHouseholds(macA, macB)
        pressLog <- ZIO.service[PressMessageRepo]
        auth     <- makeAuth
        _        <- pressLog.recordInbound("reporter@ex.com", "Comment?", "q", "<m1>")
        // adminB is a full admin — but of household B, not the operator household.
        token    <- login(auth, two.adminB, two.password, Some(two.slugB))
        routes = PressRoutes.routes(auth, pressLog)
        (status, _) <- get(routes, Some(token))
      } yield assertTrue(status == Status.NotFound)
    },
    test("a non-admin (adult) in household 1 gets 403") {
      for {
        _        <- cleanDb
        _        <- TestLayers.seedTwoHouseholds(macA, macB)
        xa       <- ZIO.service[Transactor[Task]]
        pressLog <- ZIO.service[PressMessageRepo]
        auth     <- makeAuth
        // A non-admin user in household 1 (password_hash copied from admin so `changeme` logs in).
        _        <-
          sql"""INSERT INTO users(username, password_hash, role, must_change_password, household_id)
                SELECT 'viewer', password_hash, 'adult', false, 1 FROM users WHERE username='admin'""".update.run
            .transact(xa)
        token    <- login(auth, "viewer", "changeme")
        routes = PressRoutes.routes(auth, pressLog)
        (status, _) <- get(routes, Some(token))
      } yield assertTrue(status == Status.Forbidden)
    },
    test("an unauthenticated caller gets 401") {
      for {
        _        <- cleanDb
        pressLog <- ZIO.service[PressMessageRepo]
        auth     <- makeAuth
        routes = PressRoutes.routes(auth, pressLog)
        (status, _) <- get(routes, None)
      } yield assertTrue(status == Status.Unauthorized)
    },
  ) @@ TestAspect.sequential
}
