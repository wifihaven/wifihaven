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
import doobie.implicits.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.{Clock as _, *}
import zio.http.*
import zio.interop.catz.*
import zio.json.*
import zio.test.*

/**
 * Multi-tenant sub-issue C (#2106, epic #622, design docs/design/multi-tenant-isolation.md §4.2). A
 * router is bound to a household at enrollment: the `POST /api/admin/routers` route stamps the new
 * router with the creating admin's household (resolved from the admin's JWT `sub` via UserRepo —
 * decoupled from sub-issue B's JWT `hh` claim), and `RouterAuth.authenticate` surfaces that
 * household on every router request. No wire change: `household_id` appears on no request/response
 * payload.
 */
object RouterHouseholdBindingSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & doobie.Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)

  private val cleanDb = TestDatabase.cleanAndMigrate

  // #2140: inject the DB-backed HouseholdRepo so login can resolve the non-default household's slug.
  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      hr    <- ZIO.service[HouseholdRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock, hr)

  /** Create a second household (with a login slug), return its id. */
  private def newHousehold(
      name: String,
      slug: String,
  ): ZIO[doobie.Transactor[Task], Throwable, HouseholdId] =
    ZIO
      .serviceWithZIO[doobie.Transactor[Task]] { xa =>
        sql"INSERT INTO households(name, slug) VALUES($name, $slug) RETURNING id"
          .query[Long]
          .unique
          .transact(xa)
      }
      .map(HouseholdId(_))

  /** Create an admin user placed directly in `hh`. */
  private def seedAdminInHousehold(
      auth: AuthService,
      ur: UserRepo,
      username: String,
      hh: HouseholdId,
  ): ZIO[doobie.Transactor[Task], Throwable, Unit] =
    for {
      h  <- auth.hashPassword("pw")
      _  <- ur.create(username, h, "admin")
      id <- ur.findByUsername(HouseholdId.Default, username).map(_.get.id)
      // UserRepo.create forces must_change_password (#599); clear it so this
      // seeded admin can exercise the admin route without a 403.
      _  <- ur.clearMustChangePassword(id)
      _  <- ZIO.serviceWithZIO[doobie.Transactor[Task]] { xa =>
        val hhVal = hh.value
        sql"UPDATE users SET household_id=$hhVal WHERE username=$username".update.run.transact(xa)
      }
    } yield ()

  private def createRouter(
      adminRoutes: Routes[Any, Response],
      token: String,
      name: String,
  ): Task[CreateRouterResponse] =
    for {
      resp <- adminRoutes.runZIO(
        Request
          .post(
            URL.decode("/api/admin/routers").toOption.get,
            Body.fromString(CreateRouterRequest(name).toJson),
          )
          .addHeader(Header.Authorization.Bearer(token)),
      )
      body <- resp.body.asString
      out  <- ZIO
        .fromEither(body.fromJson[CreateRouterResponse])
        .mapError(e => new RuntimeException(s"create ${resp.status}: $e / $body"))
    } yield out

  private def register(
      agentRoutes: Routes[Any, Response],
      et: EnrollmentToken,
  ): Task[RegisterRouterResponse] =
    for {
      resp <- agentRoutes.runZIO(
        Request.post(
          URL.decode("/api/router/register").toOption.get,
          Body.fromString(RegisterRouterRequest(et).toJson),
        ),
      )
      body <- resp.body.asString
      out  <- ZIO
        .fromEither(body.fromJson[RegisterRouterResponse])
        .mapError(e => new RuntimeException(s"register ${resp.status}: $e / $body"))
    } yield out

  def spec = suite("Router → household binding (#2106)")(
    test("enrolled router inherits the creating admin's household") {
      for {
        _          <- cleanDb
        auth       <- makeAuth
        rr         <- ZIO.service[RouterRepo]
        ur         <- ZIO.service[UserRepo]
        ber        <- ZIO.service[BlockEventRepo]
        // Admin lives in a fresh, non-default household.
        hhB        <- newHousehold("Beta household", "beta")
        _          <- seedAdminInHousehold(auth, ur, "betaadmin", hhB)
        // #2140: betaadmin lives in household `beta`, so login must name it via slug.
        adminLogin <- auth.login("betaadmin", "pw", Some("beta"))
        adminRoutes = AdminRouterRoutes.routes(auth, rr, ur)
        agentRoutes = RouterRoutes.routes(rr, null, RouterAuthLive(rr), ber)
        created <- createRouter(adminRoutes, adminLogin.token.value, "beta-gw")
        reg     <- register(agentRoutes, created.enrollmentToken)
        // The persisted router carries the admin's household...
        row     <- rr.findById(reg.routerId).map(_.get)
        // ...and RouterAuth.authenticate surfaces it on every router request.
        authed  <- RouterAuthLive(rr).authenticate(
          Request
            .get(URL.decode("/api/router/policy").toOption.get)
            .addHeader(Header.Authorization.Bearer(reg.routerToken.value)),
        )
      } yield assertTrue(hhB.value != 1L) &&
        assertTrue(row.householdId == hhB) &&
        assertTrue(authed.householdId == hhB)
    },
    test("backfill safety: a pre-existing (household 1) router authenticates as household 1") {
      for {
        _  <- cleanDb
        rr <- ZIO.service[RouterRepo]
        // A router created without an explicit household (pre-multi-tenant
        // callers, or the V65 backfill) lands in household 1.
        et = EnrollmentToken.unsafe("et_" + "a" * 32)
        _   <- rr.create("legacy-gw", PolicyService.hashToken(et.value))
        ber <- ZIO.service[BlockEventRepo]
        agentRoutes = RouterRoutes.routes(rr, null, RouterAuthLive(rr), ber)
        reg    <- register(agentRoutes, et)
        authed <- RouterAuthLive(rr).authenticate(
          Request
            .get(URL.decode("/api/router/policy").toOption.get)
            .addHeader(Header.Authorization.Bearer(reg.routerToken.value)),
        )
      } yield assertTrue(authed.householdId == HouseholdId.Default)
    },
  ) @@ TestAspect.sequential
}
