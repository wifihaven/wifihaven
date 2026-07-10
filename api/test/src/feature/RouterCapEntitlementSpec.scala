package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
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
 * Multi-tenant P5-4 (#2134, epic #622, design docs/design/multi-tenant-launch.md §6). The FIRST
 * entitlement: `POST /api/admin/routers` rejects creation past the calling admin's
 * `households.router_cap`. The cap resolves PER HOUSEHOLD from the DB column via the `Entitlements`
 * accessor (pricing §7: never a global constant) — so household 1 (backfilled to 10) and any
 * founding household keep their higher cap purely by the column value, with no code special-casing
 * an id.
 */
object RouterCapEntitlementSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & doobie.Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val jwtCfg = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock)

  /** Create a fresh household (router_cap defaults to 1 per V66), return its id. */
  private def newHousehold(name: String): ZIO[doobie.Transactor[Task], Throwable, HouseholdId] =
    ZIO
      .serviceWithZIO[doobie.Transactor[Task]] { xa =>
        sql"INSERT INTO households(name) VALUES($name) RETURNING id".query[Long].unique.transact(xa)
      }
      .map(HouseholdId(_))

  private def setRouterCap(
      hh: HouseholdId,
      cap: Int,
  ): ZIO[doobie.Transactor[Task], Throwable, Unit] =
    ZIO.serviceWithZIO[doobie.Transactor[Task]] { xa =>
      val hhVal = hh.value
      sql"UPDATE households SET router_cap=$cap WHERE id=$hhVal".update.run.transact(xa).unit
    }

  private def seedAdminInHousehold(
      auth: AuthService,
      ur: UserRepo,
      username: String,
      hh: HouseholdId,
  ): ZIO[doobie.Transactor[Task], Throwable, Unit] =
    for {
      h  <- auth.hashPassword("pw")
      _  <- ur.create(username, h, "admin")
      id <- ur.findByUsername(username).map(_.get.id)
      _  <- ur.clearMustChangePassword(id)
      _  <- ZIO.serviceWithZIO[doobie.Transactor[Task]] { xa =>
        val hhVal = hh.value
        sql"UPDATE users SET household_id=$hhVal WHERE username=$username".update.run.transact(xa)
      }
    } yield ()

  /** POST /api/admin/routers, returning the raw Response so we can assert on 4xx status. */
  private def postCreate(
      adminRoutes: Routes[Any, Response],
      token: String,
      name: String,
  ): Task[Response] =
    adminRoutes.runZIO(
      Request
        .post(
          URL.decode("/api/admin/routers").toOption.get,
          Body.fromString(CreateRouterRequest(name).toJson),
        )
        .addHeader(Header.Authorization.Bearer(token)),
    )

  def spec = suite("Router cap entitlement (#2134)")(
    test("household at its router_cap → 4xx on router create") {
      for {
        _          <- cleanDb
        auth       <- makeAuth
        rr         <- ZIO.service[RouterRepo]
        ur         <- ZIO.service[UserRepo]
        hh         <- newHousehold("Capped household") // router_cap defaults to 1
        _          <- seedAdminInHousehold(auth, ur, "cappedadmin", hh)
        adminLogin <- auth.login("cappedadmin", "pw")
        adminRoutes = AdminRouterRoutes.routes(auth, rr, ur)
        // First router fills the cap of 1.
        first  <- postCreate(adminRoutes, adminLogin.token.value, "gw-1")
        // Second must be rejected — the plan includes 1 router.
        second <- postCreate(adminRoutes, adminLogin.token.value, "gw-2")
        body   <- second.body.asString
        count  <- rr.listAllForHousehold(hh).map(_.size)
      } yield assertTrue(first.status == Status.Ok) &&
        assertTrue(second.status.isClientError) &&
        assertTrue(body.contains("1 router")) &&
        assertTrue(count == 1) // the rejected create did not persist a row
    },
    test("household below its router_cap → create succeeds") {
      for {
        _          <- cleanDb
        auth       <- makeAuth
        rr         <- ZIO.service[RouterRepo]
        ur         <- ZIO.service[UserRepo]
        hh         <- newHousehold("Roomy household")
        _          <- setRouterCap(hh, 2)
        _          <- seedAdminInHousehold(auth, ur, "roomyadmin", hh)
        adminLogin <- auth.login("roomyadmin", "pw")
        adminRoutes = AdminRouterRoutes.routes(auth, rr, ur)
        first  <- postCreate(adminRoutes, adminLogin.token.value, "gw-1")
        second <- postCreate(adminRoutes, adminLogin.token.value, "gw-2")
        count  <- rr.listAllForHousehold(hh).map(_.size)
      } yield assertTrue(first.status == Status.Ok, second.status == Status.Ok) &&
        assertTrue(count == 2)
    },
    test("raising the router_cap column → a previously-rejected create succeeds") {
      for {
        _          <- cleanDb
        auth       <- makeAuth
        rr         <- ZIO.service[RouterRepo]
        ur         <- ZIO.service[UserRepo]
        hh         <- newHousehold("Upgrading household") // cap 1
        _          <- seedAdminInHousehold(auth, ur, "upgradeadmin", hh)
        adminLogin <- auth.login("upgradeadmin", "pw")
        adminRoutes = AdminRouterRoutes.routes(auth, rr, ur)
        _       <- postCreate(adminRoutes, adminLogin.token.value, "gw-1") // fills cap 1
        blocked <- postCreate(adminRoutes, adminLogin.token.value, "gw-2") // rejected
        // Raise the cap on the DB column — no code special-cases the id.
        _       <- setRouterCap(hh, 2)
        allowed <- postCreate(adminRoutes, adminLogin.token.value, "gw-2")
        count   <- rr.listAllForHousehold(hh).map(_.size)
      } yield assertTrue(blocked.status.isClientError) &&
        assertTrue(allowed.status == Status.Ok) &&
        assertTrue(count == 2)
    },
  ) @@ TestAspect.sequential
}
