package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import doobie.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

/**
 * #2126 (multi-tenant isolation, epic #2085/#622, design docs/design/multi-tenant-isolation.md §2
 * gap 4) — the cross-tenant leak on `GET /api/schedules`.
 *
 * Before V71 + this source PR, `NamedScheduleRepo.listAll` returned EVERY household's named
 * schedules (V50 created the table single-household, V65 never added `household_id`), so a
 * household-B admin listing `/api/schedules` saw household A's schedules. `named_schedules`
 * attaches to profiles M:N (no direct profile FK), so scoping "via an attached profile" would
 * wrongly hide a freshly-created-but-unattached schedule from its own authoring UI — hence the
 * schema column (V71) + `listAllForHousehold` here, so an unattached schedule stays visible to ITS
 * OWN household.
 *
 * Full stack against embedded Postgres — no repo mocks, Clock injected. The negative pins (A's
 * schedule absent for B, and the per-id read/write guards) sit beside the positive one (each
 * household sees its own, including the unattached schedule).
 */
object NamedScheduleHouseholdScopeSpec
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

  private def login(auth: AuthService, user: String, pw: String, slug: Option[String] = None) =
    auth
      .login(slug.fold(user)(s => s"$s/$user"), pw)
      .mapError(e => new RuntimeException(s"login failed: $e"))
      .map(_.token.value)

  private def scheduleRoutes =
    for {
      nsr  <- ZIO.service[NamedScheduleRepo]
      auth <- makeAuth
    } yield ScheduleRoutes.routes(auth, nsr)

  private def url(p: String) = URL.decode(p).toOption.get

  private def post(rs: Routes[Any, Response], path: String, body: String, token: String) =
    rs.runZIO(
      Request
        .post(url(path), Body.fromString(body))
        .addHeader(Header.Authorization.Bearer(token))
        .addHeader(Header.ContentType(MediaType.application.json)),
    )

  private def get(rs: Routes[Any, Response], path: String, token: String) =
    rs.runZIO(Request.get(url(path)).addHeader(Header.Authorization.Bearer(token)))

  private def delete(rs: Routes[Any, Response], path: String, token: String) =
    rs.runZIO(Request.delete(url(path)).addHeader(Header.Authorization.Bearer(token)))

  private def createSchedule(rs: Routes[Any, Response], name: String, token: String) =
    post(rs, "/api/schedules", CreateNamedScheduleRequest(name = name).toJson, token)
      .flatMap(_.body.asString)
      .flatMap(b => ZIO.fromEither(b.fromJson[NamedSchedule]))

  private def listNames(rs: Routes[Any, Response], token: String) =
    get(rs, "/api/schedules", token)
      .flatMap(_.body.asString)
      .flatMap(b => ZIO.fromEither(b.fromJson[List[NamedSchedule]]))
      .map(_.map(_.name).toSet)

  def spec = suite("Named schedules are household-scoped (#2126)")(
    // ── The headline leak: GET /api/schedules is scoped to the caller's household ─
    test("GET /api/schedules returns ONLY the caller's household schedules, unattached included") {
      for {
        _      <- cleanDb
        two    <- TestLayers.seedTwoHouseholds(macA, macB)
        rs     <- scheduleRoutes
        auth   <- makeAuth
        tokA   <- login(auth, two.adminA, two.password)
        tokB   <- login(auth, two.adminB, two.password, Some(two.slugB))
        // Each admin creates a fresh, unattached schedule in their OWN household (create stamps hh).
        _      <- createSchedule(rs, "A-Bedtime", tokA)
        _      <- createSchedule(rs, "B-School", tokB)
        namesA <- listNames(rs, tokA)
        namesB <- listNames(rs, tokB)
      } yield
      // A sees only its own; B's unattached schedule never leaks into A's list.
      assertTrue(namesA == Set("A-Bedtime")) &&
        // B sees its own freshly-created unattached schedule, and never A's.
        assertTrue(namesB == Set("B-School"))
    },
    // ── Per-id read guard: B cannot read A's schedule by id (404, not the row) ───
    test("GET /api/schedules/{id} 404s across the household boundary") {
      for {
        _      <- cleanDb
        two    <- TestLayers.seedTwoHouseholds(macA, macB)
        rs     <- scheduleRoutes
        auth   <- makeAuth
        tokA   <- login(auth, two.adminA, two.password)
        tokB   <- login(auth, two.adminB, two.password, Some(two.slugB))
        aSched <- createSchedule(rs, "A-Bedtime", tokA)
        // B reads A's schedule id → not found (existence never leaks across the tenant boundary).
        crossB <- get(rs, s"/api/schedules/${aSched.id.value}", tokB)
        // A reads its own id → 200.
        ownA   <- get(rs, s"/api/schedules/${aSched.id.value}", tokA)
      } yield assertTrue(crossB.status == Status.NotFound) &&
        assertTrue(ownA.status == Status.Ok)
    },
    // ── Per-id write guard: B cannot delete A's schedule ────────────────────────
    test("DELETE /api/schedules/{id} 404s across the household boundary; A's row survives") {
      for {
        _        <- cleanDb
        two      <- TestLayers.seedTwoHouseholds(macA, macB)
        rs       <- scheduleRoutes
        auth     <- makeAuth
        tokA     <- login(auth, two.adminA, two.password)
        tokB     <- login(auth, two.adminB, two.password, Some(two.slugB))
        aSched   <- createSchedule(rs, "A-Bedtime", tokA)
        crossDel <- delete(rs, s"/api/schedules/${aSched.id.value}", tokB)
        stillA   <- listNames(rs, tokA)
      } yield assertTrue(crossDel.status == Status.NotFound) &&
        assertTrue(stillA == Set("A-Bedtime"))
    },
    // ── Single-backfilled-household is a no-op: the default admin still sees all ─
    test("single household (default admin) sees its own schedules unchanged") {
      for {
        _     <- cleanDb
        auth  <- makeAuth
        tok   <- login(auth, "admin", "changeme")
        rs    <- scheduleRoutes
        _     <- createSchedule(rs, "Bedtime", tok)
        _     <- createSchedule(rs, "School hours", tok)
        names <- listNames(rs, tok)
      } yield assertTrue(names == Set("Bedtime", "School hours"))
    },
  ) @@ TestAspect.sequential
}
