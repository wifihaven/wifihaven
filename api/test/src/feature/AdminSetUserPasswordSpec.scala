package wifihaven.api.feature

import wifihaven.api.JwtConfig
import wifihaven.api.auth.*
import wifihaven.api.db.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.api.routes.*
import wifihaven.shared.*
import wifihaven.shared.types.*
import wifihaven.shared.Clock
import wifihaven.shared.Clock.TestClock
import wifihaven.testinfra.*
import at.favre.lib.crypto.bcrypt.BCrypt
import doobie.*
import doobie.implicits.*
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.interop.catz.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

/**
 * #2576 — **admin-initiated password set for another household member**.
 *
 * The gap this closes: #2308's forgot-password flow delivers a reset link by EMAIL, and children
 * generally have no email address (nor does an admin-created adult). So the only recovery path we
 * ship structurally cannot serve the users most likely to be locked out. The admin — physically
 * present, owns the account — sets a handoff credential directly, and the target is forced to
 * change it at next login (#2492/#586's `must_change_password`), so it never persists as a shared
 * secret the admin knows.
 *
 * The three load-bearing invariants, each pinned NEGATIVELY here because each is a
 * silently-passing-if-backwards defect:
 *
 * **`requireAdmin`, not `requireWriter` (#2522).** `requireAdmin` is the ACCOUNT gate;
 * `requireWriter` is the EDITING gate. Setting another user's credential is an account operation,
 * so an ADULT must be refused — an adult may author policy but must never be able to seize a
 * household member's account. A `requireWriter` typo here passes every positive test in this file;
 * only `adult is refused` catches it.
 *
 * **Household scoping derived server-side from the JWT.** `users.id` is globally unique, so the id
 * alone reaches every tenant. A cross-household set is a full account-takeover primitive against
 * another family — the #2533/#2386 defect class. Pinned with a real two-household fixture, and the
 * foreign-id and nonexistent-id answers are asserted BYTE-IDENTICAL so comparing them is not a
 * user-enumeration oracle.
 *
 * **`AuthService.setPassword` is the only credential write (#2308 SSOT).** Asserted through its
 * observable side effects: the stored bcrypt hash verifies the new plaintext, and `token_version`
 * is bumped (#2080) so every session minted before the set is revoked. A second hand-rolled hashing
 * path would satisfy the first and silently drop the second.
 *
 * Full stack on embedded Postgres, no repo mocks. Household discipline follows
 * `AdultEditBoundarySpec`: fixtures are minted FRESH via `HouseholdRepo.create` (never household 1,
 * whose V1-seeded `admin` would collide with V86's `uq_users_household_single_admin`).
 */
object AdminSetUserPasswordSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate
  private val jwtCfg  = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)

  private val OldPassword = "old-password-123"
  private val NewPassword = "handoff-password-456"

  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      hr    <- ZIO.service[HouseholdRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock, hr): AuthService

  private def authRoutes =
    for {
      ur     <- ZIO.service[UserRepo]
      upRepo <- ZIO.service[UserProfileRepo]
      auth   <- makeAuth
    } yield AuthRoutes.routes(auth, ur, upRepo, RateLimiter.allowAll)

  /**
   * `UserRepo.create` sets `must_change_password = true`, and `requireAuth` refuses a token minted
   * for such a user — so fixtures insert directly with the flag already cleared (the shape
   * `AdultEditBoundarySpec` / `OneAdminPerHouseholdSpec` use). Clearing it up front is also what
   * makes the post-set assertion meaningful: the flag can only be `true` afterwards because the
   * route set it.
   */
  private def insertUser(
      xa: Transactor[Task],
      hh: HouseholdId,
      username: String,
      role: String,
      hash: String,
  ): Task[UserId] =
    sql"""INSERT INTO users(username, password_hash, role, must_change_password, household_id)
          VALUES ($username, $hash, $role, false, $hh) RETURNING id"""
      .query[UserId]
      .unique
      .transact(xa)

  /** One household's three members and their live tokens. */
  private final case class House(
      hh: HouseholdId,
      slug: String,
      adminTok: String,
      adultTok: String,
      childTok: String,
      adminId: UserId,
      adultId: UserId,
      childId: UserId,
  )

  private def makeHouse(slug: String, name: String) =
    for {
      xa      <- ZIO.service[Transactor[Task]]
      hr      <- ZIO.service[HouseholdRepo]
      auth    <- makeAuth
      hh      <- hr.create(name, slug)
      hash    <- auth.hashPassword(OldPassword)
      adminId <- insertUser(xa, hh, "boss", "admin", hash)
      adultId <- insertUser(xa, hh, "parent2", "adult", hash)
      childId <- insertUser(xa, hh, "kiddo", "child", hash)
      aTok    <- auth.login(s"$slug/boss", OldPassword).map(_.token.value)
      dTok    <- auth.login(s"$slug/parent2", OldPassword).map(_.token.value)
      cTok    <- auth.login(s"$slug/kiddo", OldPassword).map(_.token.value)
    } yield House(hh, slug, aTok, dTok, cTok, adminId, adultId, childId)

  // ── request helpers ──────────────────────────────────────────────────────

  private def setPassword(
      routes: Routes[Any, Response],
      targetId: Long,
      token: String,
      newPassword: String = NewPassword,
  ): Task[Response] =
    routes.runZIO(
      Request(
        method = Method.POST,
        url = URL.decode(s"/api/users/$targetId/password").toOption.get,
        body = Body.fromString(s"""{"newPassword":${newPassword.toJson}}"""),
      ).addHeader(Header.Authorization.Bearer(token))
        .addHeader(Header.ContentType(MediaType.application.json)),
    )

  /** The stored row's password hash + forced-change flag + token version, read straight from PG. */
  private def rowOf(xa: Transactor[Task], id: UserId): Task[(String, Boolean, Int)] =
    sql"SELECT password_hash, must_change_password, token_version FROM users WHERE id=$id"
      .query[(String, Boolean, Int)]
      .unique
      .transact(xa)

  private def verifies(hash: String, plaintext: String): Boolean =
    BCrypt.verifyer().verify(plaintext.toCharArray, hash).verified

  // ── specs ────────────────────────────────────────────────────────────────

  def spec = suite("AdminSetUserPasswordSpec (#2576)")(
    test("admin sets an ADULT's password: they log in with it and must change it at next login") {
      for {
        _      <- cleanDb
        xa     <- ZIO.service[Transactor[Task]]
        routes <- authRoutes
        auth   <- makeAuth
        h      <- makeHouse("house-a", "House A")
        before <- rowOf(xa, h.adultId)
        res    <- setPassword(routes, h.adultId.value, h.adminTok)
        body   <- res.body.asString
        after  <- rowOf(xa, h.adultId)
        // The new credential actually works…
        login  <- auth.login(s"${h.slug}/parent2", NewPassword)
        // …and the OLD one no longer does.
        oldRej <- auth.login(s"${h.slug}/parent2", OldPassword).either
      } yield assertTrue(
        res.status == Status.Ok,
        // Never echo the plaintext back — not in the body, not anywhere.
        !body.contains(NewPassword),
        verifies(after._1, NewPassword),
        !verifies(after._1, OldPassword),
        // #2492/#586: the handoff credential is single-use — forced change at next login.
        after._2,
        login.mustChangePassword,
        // #2080 via the setPassword SSOT: every session minted before the set is revoked.
        after._3 > before._3,
        oldRej == Left(AuthError.InvalidCredentials),
      )
    },
    test("admin sets a CHILD's password: same handoff semantics") {
      for {
        _      <- cleanDb
        xa     <- ZIO.service[Transactor[Task]]
        routes <- authRoutes
        auth   <- makeAuth
        h      <- makeHouse("house-b", "House B")
        res    <- setPassword(routes, h.childId.value, h.adminTok)
        after  <- rowOf(xa, h.childId)
        login  <- auth.login(s"${h.slug}/kiddo", NewPassword)
      } yield assertTrue(
        res.status == Status.Ok,
        verifies(after._1, NewPassword),
        after._2,
        login.mustChangePassword,
      )
    },
    test("the target's EXISTING sessions are revoked by the set (#2080)") {
      for {
        _      <- cleanDb
        routes <- authRoutes
        auth   <- makeAuth
        h      <- makeHouse("house-r", "House R")
        // The child's pre-set token is valid right up to the moment the admin sets the password.
        okPre  <- auth.verify(h.childTok).either
        _      <- setPassword(routes, h.childId.value, h.adminTok)
        after  <- auth.verify(h.childTok).either
      } yield assertTrue(okPre.isRight, after == Left(AuthError.TokenRevoked))
    },

    // ── the #2522 boundary: this is an ACCOUNT operation, not an EDITING one ──
    test("an ADULT is REFUSED (403) — requireAdmin, not requireWriter (#2522)") {
      for {
        _      <- cleanDb
        xa     <- ZIO.service[Transactor[Task]]
        routes <- authRoutes
        h      <- makeHouse("house-c", "House C")
        res    <- setPassword(routes, h.childId.value, h.adultTok)
        after  <- rowOf(xa, h.childId)
      } yield assertTrue(
        res.status == Status.Forbidden,
        // The refusal is real, not just a status: the credential is untouched.
        verifies(after._1, OldPassword),
        !after._2,
      )
    },
    test("a CHILD is REFUSED (403)") {
      for {
        _      <- cleanDb
        xa     <- ZIO.service[Transactor[Task]]
        routes <- authRoutes
        h      <- makeHouse("house-d", "House D")
        res    <- setPassword(routes, h.adultId.value, h.childTok)
        after  <- rowOf(xa, h.adultId)
      } yield assertTrue(res.status == Status.Forbidden, verifies(after._1, OldPassword))
    },
    test("an admin who is themselves mid-forced-change is REFUSED, and writes nothing") {
      // #586: `requireAuth` refuses every route but change-password while the CALLER's own
      // must_change_password is set. Worth pinning here rather than assuming the shared gate covers
      // it, because the failure is silent in the wrong direction: an admin still holding a
      // seeded/handoff credential must not be able to hand it on to a child before replacing it.
      for {
        _      <- cleanDb
        xa     <- ZIO.service[Transactor[Task]]
        routes <- authRoutes
        h      <- makeHouse("house-m", "House M")
        _      <- sql"UPDATE users SET must_change_password=true WHERE id=${h.adminId}".update.run
          .transact(xa)
        res    <- setPassword(routes, h.childId.value, h.adminTok)
        body   <- res.body.asString
        after  <- rowOf(xa, h.childId)
      } yield assertTrue(
        res.status == Status.Forbidden,
        body.contains("password_change_required"),
        verifies(after._1, OldPassword),
        !after._2,
      )
    },
    test("an unauthenticated request is REFUSED (401)") {
      for {
        _      <- cleanDb
        routes <- authRoutes
        h      <- makeHouse("house-u", "House U")
        res    <- routes.runZIO(
          Request(
            method = Method.POST,
            url = URL.decode(s"/api/users/${h.childId.value}/password").toOption.get,
            body = Body.fromString(s"""{"newPassword":"$NewPassword"}"""),
          ).addHeader(Header.ContentType(MediaType.application.json)),
        )
      } yield assertTrue(res.status == Status.Unauthorized)
    },

    // ── tenancy: the account-takeover primitive this must never be ──────────
    test("a FOREIGN household's user is REFUSED, and is indistinguishable from a nonexistent id") {
      for {
        _       <- cleanDb
        xa      <- ZIO.service[Transactor[Task]]
        routes  <- authRoutes
        a       <- makeHouse("house-e", "House E")
        b       <- makeHouse("house-f", "House F")
        // House A's admin aims at House B's child, by its real, valid id.
        foreign <- setPassword(routes, b.childId.value, a.adminTok)
        fBody   <- foreign.body.asString
        // …and at an id that exists nowhere.
        ghost   <- setPassword(routes, 987654321L, a.adminTok)
        gBody   <- ghost.body.asString
        bAfter  <- rowOf(xa, b.childId)
        // The victim can still log in with their own password — nothing was touched.
        auth    <- makeAuth
        bLogin  <- auth.login(s"${b.slug}/kiddo", OldPassword).either
      } yield assertTrue(
        foreign.status == Status.NotFound,
        // 404 both ways, same body: comparing the two answers reveals nothing about
        // whether the id exists in some other household.
        ghost.status == foreign.status,
        gBody == fBody,
        verifies(bAfter._1, OldPassword),
        !bAfter._2,
        bLogin.isRight,
      )
    },
    test("a foreign household's ADULT is refused too (not just the child)") {
      for {
        _      <- cleanDb
        xa     <- ZIO.service[Transactor[Task]]
        routes <- authRoutes
        a      <- makeHouse("house-g", "House G")
        b      <- makeHouse("house-h", "House H")
        res    <- setPassword(routes, b.adultId.value, a.adminTok)
        after  <- rowOf(xa, b.adultId)
      } yield assertTrue(res.status == Status.NotFound, verifies(after._1, OldPassword))
    },

    // ── target validity ─────────────────────────────────────────────────────
    test("an ADMIN target is refused — admin-to-admin is out of scope (#2512)") {
      for {
        _      <- cleanDb
        xa     <- ZIO.service[Transactor[Task]]
        routes <- authRoutes
        h      <- makeHouse("house-i", "House I")
        // Post-#2512 the household's only admin is the caller, so this is also the self-target case:
        // an admin rotating their OWN password uses change-password / the #2308 reset flow.
        res    <- setPassword(routes, h.adminId.value, h.adminTok)
        after  <- rowOf(xa, h.adminId)
      } yield assertTrue(
        res.status == Status.BadRequest,
        verifies(after._1, OldPassword),
        !after._2,
      )
    },
    test("a password shorter than the #2084 minimum is refused, and nothing is written") {
      for {
        _      <- cleanDb
        xa     <- ZIO.service[Transactor[Task]]
        routes <- authRoutes
        h      <- makeHouse("house-j", "House J")
        weak = "short"
        res   <- setPassword(routes, h.childId.value, h.adminTok, weak)
        after <- rowOf(xa, h.childId)
      } yield assertTrue(
        weak.length < AuthService.MinPasswordLength,
        res.status == Status.BadRequest,
        verifies(after._1, OldPassword),
        !after._2,
      )
    },
    test("a malformed body is a 400, not a 500") {
      for {
        _      <- cleanDb
        routes <- authRoutes
        h      <- makeHouse("house-k", "House K")
        res    <- routes.runZIO(
          Request(
            method = Method.POST,
            url = URL.decode(s"/api/users/${h.childId.value}/password").toOption.get,
            body = Body.fromString("""{"nope":true}"""),
          ).addHeader(Header.Authorization.Bearer(h.adminTok))
            .addHeader(Header.ContentType(MediaType.application.json)),
        )
      } yield assertTrue(res.status == Status.BadRequest)
    },
  ) @@ TestAspect.sequential @@ TestAspect.withLiveClock
}
