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
    test("the LOSER of the race still gets the 409 — a real V86 violation is classified, not 503") {
      // The route's pre-check is a check-then-write, so a concurrent promotion can reach the INSERT
      // with the slot already taken. That path is unreachable deterministically through the route
      // (you cannot schedule the interleaving), so it is pinned at its seam instead: provoke a REAL
      // unique violation from Postgres by going around the pre-check via the repo, and assert the
      // classifier maps it to the same 409 rather than the generic `ApiError.Db` → 503.
      //
      // This is the load-bearing assumption of the whole race argument. Without it, if the
      // constraint name ever stopped appearing on the exception (a rename, a driver change), the
      // code would silently degrade back to "retry a state that can never succeed" and the rest of
      // this suite would stay green.
      for {
        _     <- cleanDb
        auth  <- makeAuth
        ur    <- ZIO.service[UserRepo]
        hash  <- auth.hashPassword("pass")
        hhX   <- seedHouseholdWithAdmin("House X", "house-x", "alpha", hash)
        err   <- ur.create("second-admin", hash, "admin", hhX).flip
        // An UNRELATED violation must keep its 503 — the classifier is specific to V86's index,
        // not to unique violations in general. V65's UNIQUE(household_id, username) supplies one.
        other <- ur.create("alpha", hash, "adult", hhX).flip
      } yield assertTrue(AuthRoutes.singleAdminViolation(err)) &&
        assertTrue(
          ErrorMapper.errorToResponse(AuthRoutes.dbOrAdminExists(err)).status == Status.Conflict,
        ) &&
        assertTrue(!AuthRoutes.singleAdminViolation(other)) &&
        assertTrue(
          ErrorMapper
            .errorToResponse(AuthRoutes.dbOrAdminExists(other))
            .status == Status.ServiceUnavailable,
        )
    },
    test("PATCH cannot reach across households — an hh-B admin gets 404, not hh-A's admin slot") {
      // The #2512 guard keys on the TARGET's household, so without a caller-owns-target check an
      // hh-B admin could promote an hh-A user into hh-A's free admin slot and the guard would wave
      // it through (the slot IS free). 404 rather than 403: a cross-household id must not be
      // distinguishable from a nonexistent one.
      for {
        _                  <- cleanDb
        (routes, auth, ur) <- routesFor
        hashA              <- auth.hashPassword("passA")
        hashB              <- auth.hashPassword("passB")
        hhA                <- freshHousehold("House A", "house-a")
        victim             <- ur.create("victim", hashA, "adult", hhA)
        _                  <- seedHouseholdWithAdmin("House B", "house-b", "bravo", hashB)
        tokenB             <- auth.login("house-b/bravo", "passB").map(_.token.value)
        resp               <- patchRole(routes, tokenB, victim, "admin")
        after              <- ur.findById(victim)
      } yield assertTrue(resp.status == Status.NotFound) &&
        assertTrue(after.map(_.role) == Some(UserRole.Adult))
    },
    test("DELETE cannot reach across households either — and an unknown id is indistinguishable") {
      // The same unscoped-`findById` shape as PATCH. Left open it is the sharpest form of #2529: an
      // hh-B admin deleting hh-A's only admin locks hh-A out unrecoverably. Both the cross-household
      // id and a nonexistent one answer 404, so neither confirms the other's existence.
      for {
        _                  <- cleanDb
        (routes, auth, ur) <- routesFor
        hashA              <- auth.hashPassword("passA")
        hashB              <- auth.hashPassword("passB")
        hhA                <- freshHousehold("House A", "house-a")
        victim             <- ur.create("victim", hashA, "adult", hhA)
        _                  <- seedHouseholdWithAdmin("House B", "house-b", "bravo", hashB)
        tokenB             <- auth.login("house-b/bravo", "passB").map(_.token.value)
        del = (id: Long) =>
          routes.runZIO(
            Request
              .delete(URL.decode(s"/api/users/$id").toOption.get)
              .addHeader(Header.Authorization.Bearer(tokenB)),
          )
        cross  <- del(victim.value)
        absent <- del(999999L)
        after  <- ur.findById(victim)
      } yield assertTrue(cross.status == Status.NotFound) &&
        assertTrue(absent.status == Status.NotFound) &&
        // The victim survives — the cross-household delete was refused, not merely reported.
        assertTrue(after.isDefined)
    },
    test("PUT /api/users/{id}/profiles cannot reach across households either") {
      // The third route keyed on a global users.id, and the one most easily forgotten: profile
      // assignment decides whose policy a user sees and edits. All three now share `ownUser`.
      for {
        _                  <- cleanDb
        (routes, auth, ur) <- routesFor
        upRepo             <- ZIO.service[UserProfileRepo]
        pRepo              <- ZIO.service[ProfileRepo]
        hashA              <- auth.hashPassword("passA")
        hashB              <- auth.hashPassword("passB")
        hhA                <- freshHousehold("House A", "house-a")
        victim             <- ur.create("victim", hashA, "adult", hhA)
        _                  <- seedHouseholdWithAdmin("House B", "house-b", "bravo", hashB)
        tokenB             <- auth.login("house-b/bravo", "passB").map(_.token.value)
        // The profile's own household is irrelevant here — what is under test is that the hh-A USER
        // cannot be reached. (Nothing validates which household a `profileIds` entry belongs to
        // either; that is the object-side hole, tracked as #2531.)
        pid                <- pRepo.create("Some Profile", Nil)
        resp               <- routes.runZIO(
          Request
            .put(
              URL.decode(s"/api/users/${victim.value}/profiles").toOption.get,
              Body.fromString(SetUserProfilesRequest(List(pid)).toJson),
            )
            .addHeader(Header.Authorization.Bearer(tokenB))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
        after              <- upRepo.listProfilesForUser(victim)
      } yield assertTrue(resp.status == Status.NotFound) &&
        // Nothing was bound onto the hh-A user — refused, not merely reported.
        assertTrue(after.isEmpty)
    },
    test("PUT /api/profiles/{id}/users — the INVERSE write cannot reach across households either") {
      // The two directions of the same `user_profiles` write. Guarding only the user-keyed route
      // leaves this one to undo it: an hh-B admin picks one of their OWN profiles (so the profile
      // check passes) and names an hh-A user in the BODY. The scoped GET counterpart would then
      // hide the resulting link, so it lands invisibly.
      for {
        _                  <- cleanDb
        (routes, auth, ur) <- routesFor
        upRepo             <- ZIO.service[UserProfileRepo]
        pRepo              <- ZIO.service[ProfileRepo]
        tlRepo             <- ZIO.service[TimeLimitRepo]
        hashA              <- auth.hashPassword("passA")
        hashB              <- auth.hashPassword("passB")
        hhA                <- freshHousehold("House A", "house-a")
        victim             <- ur.create("victim", hashA, "adult", hhA)
        hhB                <- seedHouseholdWithAdmin("House B", "house-b", "bravo", hashB)
        tokenB             <- auth.login("house-b/bravo", "passB").map(_.token.value)
        // A profile hh-B genuinely owns, so `requireProfileInHousehold` passes and the ONLY thing
        // standing between the caller and the hh-A user is the body-side `ownUser` guard.
        bPid               <- pRepo.create("B Kids", Nil, hhB)
        pRoutes = ProfileRoutes.routes(auth, pRepo, tlRepo, upRepo, ur)
        resp   <- pRoutes.runZIO(
          Request
            .put(
              URL.decode(s"/api/profiles/${bPid.value}/users").toOption.get,
              Body.fromString(SetProfileUsersRequest(List(victim)).toJson),
            )
            .addHeader(Header.Authorization.Bearer(tokenB))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
        linked <- upRepo.listUsersForProfile(bPid)
      } yield assertTrue(resp.status == Status.NotFound) &&
        assertTrue(linked.isEmpty)
    },
    test("the same 409 classification covers the UPDATE path, not just the INSERT") {
      // `dbOrAdminExists` is wired to BOTH `create` and `updateRole`. The race pin above provokes
      // the violation through the INSERT; this one provokes it through the UPDATE against the same
      // index, so the UPDATE half is proven rather than inferred.
      for {
        _    <- cleanDb
        auth <- makeAuth
        ur   <- ZIO.service[UserRepo]
        hash <- auth.hashPassword("pass")
        hhX  <- seedHouseholdWithAdmin("House X", "house-x", "alpha", hash)
        bob  <- ur.create("bob", hash, "adult", hhX)
        err  <- ur.updateRole(bob, "admin").flip
      } yield assertTrue(AuthRoutes.singleAdminViolation(err)) &&
        assertTrue(
          ErrorMapper.errorToResponse(AuthRoutes.dbOrAdminExists(err)).status == Status.Conflict,
        )
    },
  ) @@ TestAspect.sequential
}
