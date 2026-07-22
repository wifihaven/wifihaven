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
 * #2356 (multi-tenant EPIC #622): the persisted, operator-grantable `free_forever` billing status.
 * Full stack, embedded Postgres, REAL [[HouseholdBillingRepo]] / [[BetaCohortRepo]] — only the
 * external [[StripeClient]] is stubbed (docs/process/testing.md: mock ONLY external I/O).
 *
 * Pins the whole shape of the new status:
 *   - household 1 (the operator household) is seeded `free_forever` and `GET /api/billing` reports
 *     it, with Checkout/Portal refused server-side (the defensive backstop behind the hidden CTA);
 *   - the grant is OPERATOR-only — a non-operator admin gets 403;
 *   - a `free_forever` household drops out of the flip cohort (`betaHouseholdIds`) so it is never
 *     flip-targeted, and its Checkout is refused so it is never charged;
 *   - a normal `beta` household is untouched — still `beta`, still in the cohort, Checkout still
 *     reaches Stripe;
 *   - revoke returns a `free_forever` household to `beta` (re-enters the funnel); revoking a
 *     non-`free_forever` household is a no-op conflict.
 */
object FreeForeverBillingSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val jwt = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)

  private val cfg = StripeConfig(
    enabled = true,
    secretKey = "sk_test_dummy",
    webhookSecret = "whsec_test_secret_at_least_32_chars_long!",
    priceMonthly = "price_monthly",
    priceAnnual = "price_annual",
    foundingPromoCode = "promo_founding",
    appBaseUrl = "https://app.test",
  )

  // A stub external client — checkout/portal return fixed URLs (never reached for a free_forever
  // household, whose Checkout/Portal are refused before any Stripe call).
  private val stubStripe: StripeClient = new StripeClient {
    def createCustomer(email: String, householdId: Long): IO[StripeError, String]           =
      ZIO.succeed(s"cus_$householdId")
    def createCheckoutSession(params: CheckoutParams): IO[StripeError, String]              =
      ZIO.succeed(s"https://checkout.stripe.test/${params.clientReferenceId}")
    def createPortalSession(customerId: String, returnUrl: String): IO[StripeError, String] =
      ZIO.succeed(s"https://portal.stripe.test/$customerId")
  }

  private def makeAuth: ZIO[UserRepo & HouseholdRepo & Clock, Nothing, AuthService] =
    for {
      ur    <- ZIO.service[UserRepo]
      hr    <- ZIO.service[HouseholdRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwt, clock, hr): AuthService

  private case class Built(svc: BillingService, routes: Routes[Any, Response], auth: AuthService)

  private def build: ZIO[TestDatabase.AllRepos & Clock, Nothing, Built] =
    for {
      hbr   <- ZIO.service[HouseholdBillingRepo]
      clock <- ZIO.service[Clock]
      auth  <- makeAuth
    } yield {
      val svc    = BillingService(stubStripe, hbr, clock, cfg)
      val routes =
        BillingRoutes.routes(auth, svc, ZIO.succeed(FlipService.FlipWindow(open = false, None)))
      Built(svc, routes, auth)
    }

  // #2164: single-identifier login — the default household is reachable by a bare username; a
  // non-default household names itself via `slug/username`.
  private def login(auth: AuthService, user: String, pw: String, slug: Option[String] = None) =
    auth
      .login(slug.fold(user)(s => s"$s/$user"), pw)
      .mapError(e => new RuntimeException(s"login failed: $e"))
      .map(_.token.value)

  // Seed a second household with its own billing row (default 'beta') + an `adminB` admin whose
  // password hash is copied from the seeded operator `admin` (so `password` == "changeme"). Returns
  // (householdId, slug).
  private def seedHousehold(
      xa: Transactor[Task],
      hbr: HouseholdBillingRepo,
      name: String,
      slug: String,
      status: String = "beta",
      withAdmin: Boolean = false,
  ): Task[HouseholdId] =
    for {
      hid <-
        sql"INSERT INTO households(name, slug, router_cap) VALUES($name, $slug, 1) RETURNING id"
          .query[HouseholdId]
          .unique
          .transact(xa)
      _   <- hbr.create(hid, status, founding = true)
      _   <- ZIO.when(withAdmin)(
        sql"""INSERT INTO users(username, password_hash, role, must_change_password, household_id)
              SELECT 'adminB', password_hash, 'admin', false, $hid FROM users WHERE username='admin'""".update.run
          .transact(xa)
          .unit,
      )
    } yield hid

  private def statusOf(xa: Transactor[Task], hid: HouseholdId): Task[String] =
    sql"SELECT status FROM household_billing WHERE household_id=$hid"
      .query[String]
      .unique
      .transact(xa)

  private def getJson(routes: Routes[Any, Response], path: String, token: String) =
    routes
      .runZIO(
        Request.get(URL.decode(path).toOption.get).addHeader(Header.Authorization.Bearer(token)),
      )
      .flatMap(r => r.body.asString.map((r.status, _)))

  private def opJson(
      routes: Routes[Any, Response],
      method: Method,
      path: String,
      token: String,
  ): Task[Status] =
    routes
      .runZIO(
        Request(
          method = method,
          url = URL.decode(path).toOption.get,
        ).addHeader(Header.Authorization.Bearer(token)),
      )
      .map(_.status)

  def spec = suite("free_forever billing status (#2356)")(
    test(
      "household 1 is seeded free_forever; GET /api/billing reports it; Checkout/Portal refused",
    ) {
      for {
        _           <- cleanDb
        xa          <- ZIO.service[Transactor[Task]]
        clock       <- ZIO.service[Clock]
        hbr         <- ZIO.service[HouseholdBillingRepo]
        now         <- clock.instant
        // The boot-seed the API runs at startup for the operator household (idempotent).
        _           <- hbr.ensureFreeForever(HouseholdId.Default, now)
        b           <- build
        op          <- login(b.auth, "admin", "changeme")
        (gs, body)  <- getJson(b.routes, "/api/billing", op)
        resp        <- ZIO
          .fromEither(body.fromJson[BillingStatusResponse])
          .mapError(new RuntimeException(_))
        // Defensive backstop behind the hidden CTA: a free_forever household is never billed.
        checkoutErr <- b.svc.startCheckout(HouseholdId.Default).either
        portalErr   <- b.svc.startPortal(HouseholdId.Default).either
      } yield assertTrue(
        gs == Status.Ok,
        resp.status == "free_forever",
        checkoutErr == Left(BillingError.FreeForever),
        portalErr == Left(BillingError.FreeForever),
      )
    },
    test("the grant is operator-only — a non-operator admin gets 403; the operator succeeds") {
      for {
        _    <- cleanDb
        xa   <- ZIO.service[Transactor[Task]]
        hbr  <- ZIO.service[HouseholdBillingRepo]
        b    <- build
        hidB <- seedHousehold(xa, hbr, "B Fam", "b-fam", withAdmin = true)
        op   <- login(b.auth, "admin", "changeme")
        bTok <- login(b.auth, "adminB", "changeme", Some("b-fam"))
        path = s"/api/operator/households/${hidB.value}/free-forever"
        forbidden <- opJson(b.routes, Method.POST, path, bTok)
        granted   <- opJson(b.routes, Method.POST, path, op)
        status    <- statusOf(xa, hidB)
      } yield assertTrue(
        forbidden == Status.Forbidden,
        granted == Status.Ok,
        status == "free_forever",
      )
    },
    test(
      "a free_forever household is excluded from the flip cohort and never charged; beta is not",
    ) {
      for {
        _          <- cleanDb
        xa         <- ZIO.service[Transactor[Task]]
        clock      <- ZIO.service[Clock]
        hbr        <- ZIO.service[HouseholdBillingRepo]
        cohortRepo <- ZIO.service[BetaCohortRepo]
        b          <- build
        hidB       <- seedHousehold(xa, hbr, "B Fam", "b-fam")
        hidC       <- seedHousehold(xa, hbr, "C Fam", "c-fam")
        _          <- hbr.setStripeCustomer(hidC, "cus_C") // beta C can reach Checkout
        now        <- clock.instant
        _          <- hbr.grantFreeForever(hidB, now)
        betaIds    <- cohortRepo.betaHouseholdIds
        checkoutB  <- b.svc.startCheckout(hidB).either     // free_forever → refused
        checkoutC  <- b.svc.startCheckout(hidC).either     // beta → reaches Stripe
      } yield assertTrue(
        !betaIds.contains(hidB),
        betaIds.contains(hidC),
        checkoutB == Left(BillingError.FreeForever),
        checkoutC.exists(_.startsWith("https://checkout.stripe.test/")),
      )
    },
    test(
      "revoke returns free_forever → beta; revoking a non-free_forever household is a no-op conflict",
    ) {
      for {
        _          <- cleanDb
        xa         <- ZIO.service[Transactor[Task]]
        clock      <- ZIO.service[Clock]
        hbr        <- ZIO.service[HouseholdBillingRepo]
        cohortRepo <- ZIO.service[BetaCohortRepo]
        b          <- build
        hidB       <- seedHousehold(xa, hbr, "B Fam", "b-fam")
        now        <- clock.instant
        _          <- hbr.grantFreeForever(hidB, now)
        op         <- login(b.auth, "admin", "changeme")
        path = s"/api/operator/households/${hidB.value}/free-forever"
        revoked     <- opJson(b.routes, Method.DELETE, path, op)
        afterBeta   <- statusOf(xa, hidB)
        betaIds     <- cohortRepo.betaHouseholdIds
        // A second revoke — the household is `beta` now, not `free_forever` → 409, no state change.
        revokeAgain <- opJson(b.routes, Method.DELETE, path, op)
        stillBeta   <- statusOf(xa, hidB)
      } yield assertTrue(
        revoked == Status.Ok,
        afterBeta == "beta",
        betaIds.contains(hidB),
        revokeAgain == Status.Conflict,
        stillBeta == "beta",
      )
    },
  ) @@ TestAspect.sequential
}
