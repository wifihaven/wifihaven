package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock.TestClock
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
 * #2512 — a household has exactly ONE admin, and the API says so readably.
 *
 * V86 (`uq_users_household_single_admin`, a partial unique index on `users(household_id) WHERE role
 * \= 'admin'`) is the BACKSTOP: it makes the invariant structurally true, but on its own a second
 * admin surfaces as a raw unique violation — i.e. `ApiError.Db` → 503 "retry later", which is a lie
 * (retrying never helps). This spec pins the readable 409 that `POST /api/users` and `PATCH
 * /api/users/{id}` return instead, and equally pins that the guard does NOT over-block: the FIRST
 * admin in a household still lands, and non-admin roles are untouched.
 *
 * Household discipline: every admin here is seeded into a FRESH household, never household 1 (which
 * owns the V1-seeded `admin`). Seeding a second admin into household 1 is exactly the fixture bug
 * #2526 had to clean out of four specs before V86 could apply.
 *
 * Full-stack through `AuthRoutes.routes` on embedded Postgres, no repo mocks, Clock injected.
 */
object OneAdminPerHouseholdSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate
  private val jwtCfg  = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)

  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      hr    <- ZIO.service[HouseholdRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock, hr): AuthService

  /**
   * A fresh household holding a single `admin` (password already changed, so routes are usable).
   */
  private def seedHouseholdWithAdmin(
      name: String,
      slug: String,
      username: String,
      pwHash: String,
  ): RIO[Transactor[Task], HouseholdId] =
    for {
      xa <- ZIO.service[Transactor[Task]]
      hh <- sql"INSERT INTO households(name, slug) VALUES ($name, $slug) RETURNING id"
        .query[HouseholdId]
        .unique
        .transact(xa)
      _  <-
        sql"""INSERT INTO users(username, password_hash, role, must_change_password, household_id)
              VALUES ($username, $pwHash, 'admin', false, $hh)""".update.run.transact(xa)
    } yield hh

  private def freshHousehold(name: String, slug: String): RIO[Transactor[Task], HouseholdId] =
    ZIO.serviceWithZIO[Transactor[Task]](xa =>
      sql"INSERT INTO households(name, slug) VALUES ($name, $slug) RETURNING id"
        .query[HouseholdId]
        .unique
        .transact(xa),
    )

  private def routesFor =
    for {
      ur     <- ZIO.service[UserRepo]
      upRepo <- ZIO.service[UserProfileRepo]
      auth   <- makeAuth
    } yield (AuthRoutes.routes(auth, ur, upRepo, RateLimiter.allowAll), auth, ur)

  private def postUser(
      routes: Routes[Any, Response],
      token: String,
      username: String,
      role: UserRole,
  ) =
    routes.runZIO(
      Request
        .post(
          URL.decode("/api/users").toOption.get,
          Body.fromString(CreateUserRequest(username, "initial123456", role, Nil).toJson),
        )
        .addHeader(Header.Authorization.Bearer(token))
        .addHeader(Header.ContentType(MediaType.application.json)),
    )

  private def patchRole(routes: Routes[Any, Response], token: String, id: UserId, role: String) =
    routes.runZIO(
      Request
        .patch(
          URL.decode(s"/api/users/${id.value}").toOption.get,
          Body.fromString(s"""{"role":"$role"}"""),
        )
        .addHeader(Header.Authorization.Bearer(token))
        .addHeader(Header.ContentType(MediaType.application.json)),
    )

  def spec = suite("#2512 — one admin per household")(
    test("POST /api/users with role=admin is REFUSED with a readable 409, not a 503/500") {
      for {
        _                  <- cleanDb
        (routes, auth, ur) <- routesFor
        hash               <- auth.hashPassword("passX")
        hhX                <- seedHouseholdWithAdmin("House X", "house-x", "alpha", hash)
        token              <- auth.login("house-x/alpha", "passX").map(_.token.value)
        resp               <- postUser(routes, token, "second-admin", UserRole.Admin)
        body               <- resp.body.asString
        // The refusal is total: no row was created.
        users              <- ur.listAllForHousehold(hhX)
      } yield assertTrue(resp.status == Status.Conflict) &&
        assertTrue(body == """{"error":"admin_exists"}""") &&
        assertTrue(users.map(_.username) == List("alpha"))
    },
    test("PATCH /api/users/{id} promoting an adult to admin is REFUSED with the same 409") {
      for {
        _                  <- cleanDb
        (routes, auth, ur) <- routesFor
        hash               <- auth.hashPassword("passX")
        hhX                <- seedHouseholdWithAdmin("House X", "house-x", "alpha", hash)
        token              <- auth.login("house-x/alpha", "passX").map(_.token.value)
        created            <- postUser(routes, token, "bob", UserRole.Adult)
        bob                <- ur.findByUsername(hhX, "bob")
        resp               <- patchRole(routes, token, bob.get.id, "admin")
        body               <- resp.body.asString
        after              <- ur.findByUsername(hhX, "bob")
      } yield assertTrue(created.status == Status.Ok) &&
        assertTrue(resp.status == Status.Conflict) &&
        assertTrue(body == """{"error":"admin_exists"}""") &&
        // The role is unchanged — the guard refuses before the UPDATE.
        assertTrue(after.map(_.role) == Some(UserRole.Adult))
    },
    test("the FIRST admin still lands: a household with no admin accepts one") {
      // Not reachable through the route (the route requires an admin caller, which an adminless
      // household by definition has none of) — the real producer is the #2132 invite-accept
      // provisioning path, which calls `userRepo.create` directly. Exercised at that seam, on the
      // same migrated schema, so V86 + the guard are both proven not to block the first admin.
      for {
        _    <- cleanDb
        ur   <- ZIO.service[UserRepo]
        auth <- makeAuth
        hash <- auth.hashPassword("passY")
        hhY  <- freshHousehold("House Y", "house-y")
        id   <- ur.create("first-admin", hash, "admin", hhY)
        got  <- ur.findById(id)
      } yield assertTrue(got.map(_.role) == Some(UserRole.Admin)) &&
        assertTrue(got.map(_.householdId) == Some(hhY))
    },
    test("promoting an adult to admin SUCCEEDS when the admin slot is free") {
      // Free the slot the way the DB sees it — demote the seeded admin's ROW directly. The caller's
      // already-minted JWT still carries `role=admin`, so `requireAdmin` passes and we exercise the
      // route's guard against a household that genuinely has no admin row.
      for {
        _                  <- cleanDb
        (routes, auth, ur) <- routesFor
        xa                 <- ZIO.service[Transactor[Task]]
        hash               <- auth.hashPassword("passZ")
        hhZ                <- seedHouseholdWithAdmin("House Z", "house-z", "zed", hash)
        token              <- auth.login("house-z/zed", "passZ").map(_.token.value)
        _                  <- postUser(routes, token, "delta", UserRole.Adult)
        delta              <- ur.findByUsername(hhZ, "delta")
        _                  <-
          sql"UPDATE users SET role='adult' WHERE household_id=$hhZ AND role='admin'".update.run
            .transact(xa)
        resp               <- patchRole(routes, token, delta.get.id, "admin")
        after              <- ur.findByUsername(hhZ, "delta")
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(after.map(_.role) == Some(UserRole.Admin))
    },
    test("re-setting the household's existing admin to admin is a no-op, not a self-collision") {
      // The guard must exclude the PATCH target itself, or the SPA's save-the-whole-form flow would
      // 409 on an unchanged role.
      for {
        _                  <- cleanDb
        (routes, auth, ur) <- routesFor
        hash               <- auth.hashPassword("passX")
        hhX                <- seedHouseholdWithAdmin("House X", "house-x", "alpha", hash)
        token              <- auth.login("house-x/alpha", "passX").map(_.token.value)
        alpha              <- ur.findByUsername(hhX, "alpha")
        resp               <- patchRole(routes, token, alpha.get.id, "admin")
        after              <- ur.findByUsername(hhX, "alpha")
      } yield assertTrue(resp.status == Status.Ok) &&
        assertTrue(after.map(_.role) == Some(UserRole.Admin))
    },
    test("adult and child are many-per-household — the guard is admin-only") {
      for {
        _                  <- cleanDb
        (routes, auth, ur) <- routesFor
        hash               <- auth.hashPassword("passX")
        hhX                <- seedHouseholdWithAdmin("House X", "house-x", "alpha", hash)
        token              <- auth.login("house-x/alpha", "passX").map(_.token.value)
        a1                 <- postUser(routes, token, "parent2", UserRole.Adult)
        a2                 <- postUser(routes, token, "kid1", UserRole.Child)
        a3                 <- postUser(routes, token, "kid2", UserRole.Child)
        users              <- ur.listAllForHousehold(hhX)
      } yield assertTrue(List(a1, a2, a3).map(_.status) == List.fill(3)(Status.Ok)) &&
        assertTrue(users.size == 4)
    },
    test("the guard is HOUSEHOLD-scoped — another household's admin does not block this one") {
      for {
        _    <- cleanDb
        _    <- ZIO.service[UserRepo]
        auth <- makeAuth
        ur   <- ZIO.service[UserRepo]
        hash <- auth.hashPassword("pass")
        _    <- seedHouseholdWithAdmin("House X", "house-x", "alpha", hash)
        hhY  <- freshHousehold("House Y", "house-y")
        // hh-Y has no admin; hh-X's (and household 1's) admins must not stand in its way.
        id   <- ur.create("y-admin", hash, "admin", hhY)
        got  <- ur.findById(id)
      } yield assertTrue(got.map(_.role) == Some(UserRole.Admin))
    },
  ) @@ TestAspect.sequential
}
