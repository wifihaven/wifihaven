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
import doobie.*
import doobie.implicits.*
import java.time.ZoneId
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.interop.catz.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.json.ast.Json
import zio.test.*

/**
 * #2533 (multi-tenant, epic #2085) — `GET`/`PUT`/`PATCH /api/household/settings` must read and
 * write the CALLER's household row, never household #1's.
 *
 * The bug: `HouseholdSettingsRoutes` called the unscoped `HouseholdSettingsRepo.get` /
 * `.update(s)`, both hardcoded to `WHERE id=1`. So a user in household N read, and wrote, household
 * #1's `household_settings` row. Meanwhile the router snapshot / `PolicyService` path reads the
 * scoped `getForHousehold(household)` (#2107) — so enforcement read the RIGHT row while the SPA
 * edited the WRONG one, and the two silently diverged for every non-default household. The
 * user-visible symptom: a setting appears to save (200 + the value reads back) and then does
 * nothing.
 *
 * Pins, all through the ROUTE on the full stack (embedded Postgres, no repo mocks):
 *   - two freshly-minted households write distinguishable values and each reads back its OWN — and
 *     neither observes the other's (positive sees-own + negative no-cross-tenant);
 *   - neither household's write touches household #1's row (the operator household, which is what
 *     the unscoped `id=1` accessors targeted);
 *   - after a `PUT` and after a `PATCH`, `getForHousehold(thatHousehold)` — the read *enforcement*
 *     uses — observes the change. This is the divergence itself, pinned directly.
 *
 * Households are minted via `HouseholdRepo.create`, the #2355 SSOT seam that seeds the settings +
 * billing + global-sentinel rows in one transaction — never household 1 by hand.
 */
object HouseholdSettingsRouteScopeSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate
  private val jwtCfg  = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)

  private val Password = "tenancy123"

  private def makeAuth =
    for {
      ur    <- ZIO.service[UserRepo]
      hr    <- ZIO.service[HouseholdRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwtCfg, clock, hr): AuthService

  /** One household's admin: the household id and a usable bearer token. */
  private final case class Tenant(hh: HouseholdId, slug: String, token: String)

  // `users.must_change_password` defaults true on `UserRepo.create` and `requireAuth` refuses such
  // a token, so the fixture inserts the row directly with the flag already cleared (the shape
  // `AdultEditBoundarySpec` / `OneAdminPerHouseholdSpec` use).
  private def insertAdmin(
      xa: Transactor[Task],
      hh: HouseholdId,
      username: String,
      hash: String,
  ): Task[UserId] =
    sql"""INSERT INTO users(username, password_hash, role, must_change_password, household_id)
          VALUES ($username, $hash, 'admin', false, $hh) RETURNING id"""
      .query[UserId]
      .unique
      .transact(xa)

  private def tenant(name: String, slug: String) =
    for {
      xa   <- ZIO.service[Transactor[Task]]
      hr   <- ZIO.service[HouseholdRepo]
      auth <- makeAuth
      hh   <- hr.create(name, slug)
      hash <- auth.hashPassword(Password)
      _    <- insertAdmin(xa, hh, "boss", hash)
      tok  <- auth.login(s"$slug/boss", Password).map(_.token.value)
    } yield Tenant(hh, slug, tok)

  private def settingsRoutes =
    for {
      hsr  <- ZIO.service[HouseholdSettingsRepo]
      auth <- makeAuth
    } yield HouseholdSettingsRoutes.routes(auth, hsr)

  private def send(
      routes: Routes[Any, Response],
      method: Method,
      token: String,
      body: Option[String] = None,
  ): Task[Response] = {
    val base = Request(
      method = method,
      url = URL.decode("/api/household/settings").toOption.get,
      body = body.fold(Body.empty)(Body.fromString(_)),
    ).addHeader(Header.Authorization.Bearer(token))
    routes.runZIO(
      body.fold(base)(_ => base.addHeader(Header.ContentType(MediaType.application.json))),
    )
  }

  /**
   * The `notifyEmail` the GET body reports — the field each tenant writes a distinct value into.
   */
  private def readNotifyEmail(resp: Response): Task[Option[String]] =
    resp.body.asString.map { s =>
      s.fromJson[Json]
        .toOption
        .flatMap(_.asObject)
        .flatMap(_.get("notifyEmail"))
        .flatMap(_.asString)
    }

  def spec = suite("HouseholdSettingsRouteScopeSpec")(
    test("two households' settings are independent through the route") {
      for {
        _   <- cleanDb
        hs  <- ZIO.service[HouseholdSettingsRepo]
        rts <- settingsRoutes
        a   <- tenant("House A", "house-a")
        b   <- tenant("House B", "house-b")
        // Each tenant PATCHes a distinguishable recipient onto ITS OWN settings.
        pa  <- send(rts, Method.PATCH, a.token, Some("""{"notifyEmail":"a@example.com"}""")).map(
          _.status,
        )
        pb  <- send(rts, Method.PATCH, b.token, Some("""{"notifyEmail":"b@example.com"}""")).map(
          _.status,
        )
        // Each reads back its OWN value through the route…
        ga  <- send(rts, Method.GET, a.token).flatMap(readNotifyEmail)
        gb  <- send(rts, Method.GET, b.token).flatMap(readNotifyEmail)
        // …and the same is true of the row enforcement reads.
        ra  <- hs.getForHousehold(a.hh)
        rb  <- hs.getForHousehold(b.hh)
        // Household #1 — the row the unscoped `id=1` accessors wrote — is untouched by either.
        one <- hs.getForHousehold(HouseholdId.Default)
      } yield assertTrue(
        pa == Status.Ok,
        pb == Status.Ok,
        // sees-own
        ga.contains("a@example.com"),
        gb.contains("b@example.com"),
        // no cross-tenant read
        !ga.contains("b@example.com"),
        !gb.contains("a@example.com"),
        // the enforcement-side read agrees with what the route reported
        ra.notifyEmail.contains("a@example.com"),
        rb.notifyEmail.contains("b@example.com"),
        // neither write landed on the operator household's row
        one.notifyEmail.isEmpty,
      )
    },
    test("PATCH is observed by getForHousehold — the read enforcement uses") {
      for {
        _      <- cleanDb
        hs     <- ZIO.service[HouseholdSettingsRepo]
        rts    <- settingsRoutes
        a      <- tenant("House Patch", "house-patch")
        before <- hs.getForHousehold(a.hh)
        st     <- send(rts, Method.PATCH, a.token, Some("""{"blockEncryptedDns":true}""")).map(
          _.status,
        )
        after  <- hs.getForHousehold(a.hh)
        one    <- hs.getForHousehold(HouseholdId.Default)
      } yield assertTrue(
        st == Status.Ok,
        !before.blockEncryptedDns,
        // the actual #2533 symptom: 200 from the route, and the row enforcement reads changed
        after.blockEncryptedDns,
        // …without collaterally flipping household #1
        !one.blockEncryptedDns,
      )
    },
    test("PUT is observed by getForHousehold — the read enforcement uses") {
      for {
        _         <- cleanDb
        hs        <- ZIO.service[HouseholdSettingsRepo]
        rts       <- settingsRoutes
        a         <- tenant("House Put", "house-put")
        // `HouseholdSettings` and `UpdateHouseholdSettingsRequest` share field names, so the
        // model's own JSON is a valid PUT body — no hand-built fixture to drift from the model.
        cur       <- hs.getForHousehold(a.hh)
        // Household #1's OWN pre-write value. Captured rather than compared against `cur` — the two
        // rows reach their default through independent seed paths (`ensureDefault` for #1,
        // `HouseholdSeed.insertHousehold`'s column defaults for a fresh household), so equality
        // between them is a coincidence, not an invariant, and would silently stop being a real pin
        // if either default moved.
        oneBefore <- hs.getForHousehold(HouseholdId.Default)
        body = cur.copy(dailyResetTz = ZoneId.of("America/Denver")).toJson
        st    <- send(rts, Method.PUT, a.token, Some(body)).map(_.status)
        after <- hs.getForHousehold(a.hh)
        one   <- hs.getForHousehold(HouseholdId.Default)
      } yield assertTrue(
        st == Status.Ok,
        // the full-replace write landed on THIS household's row…
        after.dailyResetTz.getId == "America/Denver",
        // …and household #1 still holds exactly what IT held before the PUT. Equality against the
        // pre-write value rather than `!= "America/Denver"` — the latter would also pass if the PUT
        // had silently written nothing at all.
        one.dailyResetTz == oneBefore.dailyResetTz,
      )
    },
    test("update fails loud for a household that owns no settings row") {
      // #2533 / no-dark-by-default: a write that matches 0 rows is a provisioning bug, not a
      // silent success. `rawHousehold` mints a households row WITHOUT the settings row that
      // `HouseholdRepo.create` would have seeded — the same shape `HouseholdSettingsIsolationSpec`
      // uses to pin the read-side fail-loud. Without this the route would 200 on a write that
      // landed nowhere, which is the exact failure mode #2533 was.
      for {
        _    <- cleanDb
        hs   <- ZIO.service[HouseholdSettingsRepo]
        xa   <- ZIO.service[Transactor[Task]]
        hid  <-
          sql"INSERT INTO households(name, slug, router_cap) VALUES('no-settings','no-settings',1) RETURNING id"
            .query[HouseholdId]
            .unique
            .transact(xa)
        base <- hs.getForHousehold(HouseholdId.Default)
        res  <- hs.update(hid, base.copy(blockEncryptedDns = true)).either
        // …and the failed write did NOT fall through onto household #1's row.
        one  <- hs.getForHousehold(HouseholdId.Default)
      } yield assertTrue(
        res.isLeft,
        !one.blockEncryptedDns,
      )
    },
  ) @@ TestAspect.sequential
}
