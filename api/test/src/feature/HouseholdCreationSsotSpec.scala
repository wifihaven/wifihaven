package wifihaven.api.feature

import wifihaven.api.{JwtConfig, StripeConfig}
import wifihaven.api.auth.*
import wifihaven.api.billing.*
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
import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
import zio.interop.catz.*
import zio.{Clock as _, *}
import zio.http.*
import zio.json.*
import zio.test.*

/**
 * #2355: single-source-of-truth for household creation. Every household-create path must yield a
 * `household_billing` row, so `GET /api/billing` never 404s `NoBillingRow` for a provisioned
 * household. Full stack, embedded Postgres, NO repo mocks (only the external StripeClient is
 * stubbed). Pins:
 *   - a household minted via [[HouseholdRepoLive.create]] has a billing row and `GET /api/billing`
 *     returns 200 beta (positive sees-own-data);
 *   - [[BetaRequestRepo.approveAndProvision]] still seeds billing + global-sentinel atomically, and
 *     a double-approve still rolls the whole thing back (no second household, no second billing
 *     row);
 *   - the boot-time backfill seed is idempotent and gives a pre-existing rowless household a beta
 *     row.
 */
object HouseholdCreationSsotSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val jwt = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)

  private def makeAuth: ZIO[UserRepo & HouseholdRepo & Clock, Nothing, AuthService] =
    for {
      ur    <- ZIO.service[UserRepo]
      hr    <- ZIO.service[HouseholdRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwt, clock, hr): AuthService

  private def billingRoutes(
      auth: AuthService,
      hbr: HouseholdBillingRepo,
      clock: Clock,
  ): Routes[Any, Response] = {
    val svc = BillingService(StripeClient.noop, hbr, clock, StripeConfig())
    // No cohort clock started in this spec → closed flip window.
    BillingRoutes.routes(
      auth,
      svc,
      ZIO.succeed(FlipService.FlipWindow(open = false, flipDate = None)),
    )
  }

  // Insert an admin user into `hh`, reusing the seeded admin's bcrypt hash so `password` is
  // "changeme" — no AuthService dependency in the fixture (mirrors TestDatabase.seedTwoHouseholds).
  private def seedAdmin(xa: Transactor[Task], hh: HouseholdId, username: String): Task[Unit] =
    sql"""INSERT INTO users(username, password_hash, role, must_change_password, household_id)
          SELECT $username, password_hash, 'admin', false, $hh FROM users WHERE username='admin'""".update.run
      .transact(xa)
      .unit

  private def getJson(
      routes: Routes[Any, Response],
      path: String,
      token: String,
  ): Task[(Status, String)] =
    for {
      resp <- routes.runZIO(
        Request.get(URL.decode(path).toOption.get).addHeader(Header.Authorization.Bearer(token)),
      )
      body <- resp.body.asString
    } yield (resp.status, body)

  private def billingRowCount(xa: Transactor[Task], hh: HouseholdId): Task[Int] =
    sql"SELECT COUNT(*)::int FROM household_billing WHERE household_id=$hh"
      .query[Int]
      .unique
      .transact(xa)

  def spec = suite("Household-creation SSOT (#2355)")(
    test("HouseholdRepoLive.create seeds a billing row; GET /api/billing returns 200 beta") {
      for {
        _          <- cleanDb
        xa         <- ZIO.service[Transactor[Task]]
        hr         <- ZIO.service[HouseholdRepo]
        hbr        <- ZIO.service[HouseholdBillingRepo]
        clock      <- ZIO.service[Clock]
        auth       <- makeAuth
        hid        <- hr.create("Create Fam", "create-fam", 1)
        _          <- seedAdmin(xa, hid, "admin") // per-household admin; slug disambiguates login
        // Billing row exists at the repo layer.
        billing    <- hbr.findByHousehold(hid)
        // And the SPA billing endpoint resolves it (positive sees-own-data pin).
        token      <- auth
          .login("create-fam/admin", "changeme")
          .mapError(e => new RuntimeException(s"login failed: $e"))
          .map(_.token.value)
        (st, body) <- getJson(billingRoutes(auth, hbr, clock), "/api/billing", token)
        resp       <- ZIO
          .fromEither(body.fromJson[BillingStatusResponse])
          .mapError(new RuntimeException(_))
      } yield assertTrue(
        billing.exists(_.status == "beta"),
        // create() is not the founding-member path — that flag is set only by approveAndProvision.
        billing.exists(!_.founding),
        st == Status.Ok,
        resp.status == "beta",
      )
    },
    test("approveAndProvision seeds billing + global-sentinel atomically") {
      for {
        _     <- cleanDb
        xa    <- ZIO.service[Transactor[Task]]
        br    <- ZIO.service[BetaRequestRepo]
        clock <- ZIO.service[Clock]
        now   <- clock.instant
        _     <- br.create("prov@example.com", Some("Prov Family"), None)
        reqId <- br.findByEmail("prov@example.com").someOrFailException.map(_.id)
        opId  <- sql"SELECT id FROM users WHERE username='admin'".query[UserId].unique.transact(xa)
        hid   <- br.approveAndProvision(
          reqId,
          decidedBy = opId,
          householdName = "Prov Family",
          slug = "prov-family",
          routerCap = 1,
          billingStatus = "beta",
          founding = true,
          inviteTokenHash = Sha256Hex.unsafe("a" * 64),
          inviteExpiresAt = now.plusSeconds(3600),
          decidedAt = now,
        )
        billingCount  <- billingRowCount(xa, hid)
        sentinelCount <-
          sql"SELECT COUNT(*)::int FROM profiles WHERE household_id=$hid AND is_global"
            .query[Int]
            .unique
            .transact(xa)
        founding      <- sql"SELECT founding FROM household_billing WHERE household_id=$hid"
          .query[Boolean]
          .unique
          .transact(xa)
      } yield assertTrue(billingCount == 1, sentinelCount == 1, founding)
    },
    test("double-approve rolls back — no second household, no orphan billing row") {
      for {
        _     <- cleanDb
        xa    <- ZIO.service[Transactor[Task]]
        br    <- ZIO.service[BetaRequestRepo]
        clock <- ZIO.service[Clock]
        now   <- clock.instant
        _     <- br.create("dup@example.com", Some("Dup Family"), None)
        reqId <- br.findByEmail("dup@example.com").someOrFailException.map(_.id)
        opId  <- sql"SELECT id FROM users WHERE username='admin'".query[UserId].unique.transact(xa)
        approve = (slug: String) =>
          br.approveAndProvision(
            reqId,
            decidedBy = opId,
            householdName = "Dup Family",
            slug = slug,
            routerCap = 1,
            billingStatus = "beta",
            founding = true,
            inviteTokenHash = Sha256Hex.unsafe("b" * 64),
            inviteExpiresAt = now.plusSeconds(3600),
            decidedAt = now,
          )
        hid          <- approve("dup-family")
        // Second approval of the now-`approved` request must raise and change nothing.
        secondResult <- approve("dup-family-2").either
        hhCount      <- sql"SELECT COUNT(*)::int FROM households WHERE name='Dup Family'"
          .query[Int]
          .unique
          .transact(xa)
        billingCount <- billingRowCount(xa, hid)
      } yield assertTrue(secondResult.isLeft, hhCount == 1, billingCount == 1)
    },
    test("backfill seed is idempotent and gives a rowless household a beta row") {
      for {
        _      <- cleanDb
        xa     <- ZIO.service[Transactor[Task]]
        // A household with NO billing row — models a pre-#2355 household minted via the old
        // billing-less create path (raw insert, bypassing the HouseholdSeed primitive).
        hid    <-
          sql"INSERT INTO households(name, slug, router_cap) VALUES('Legacy Fam','legacy-fam',1) RETURNING id"
            .query[HouseholdId]
            .unique
            .transact(xa)
        before <- billingRowCount(xa, hid)
        _      <- HouseholdSeed.backfillMissingBilling.transact(xa)
        after1 <- billingRowCount(xa, hid)
        row    <- sql"SELECT status, founding FROM household_billing WHERE household_id=$hid"
          .query[(String, Boolean)]
          .unique
          .transact(xa)
        // Second run is a no-op (idempotent) — no duplicate row, existing row untouched.
        _      <- HouseholdSeed.backfillMissingBilling.transact(xa)
        after2 <- billingRowCount(xa, hid)
      } yield assertTrue(
        before == 0,
        after1 == 1,
        row == ("beta", false),
        after2 == 1,
      )
    },
  ) @@ TestAspect.sequential
}
