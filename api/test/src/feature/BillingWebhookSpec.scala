package wifihaven.api.feature

import wifihaven.api.{JwtConfig, StripeConfig}
import wifihaven.api.auth.*
import wifihaven.api.billing.*
import wifihaven.api.db.*
import wifihaven.api.db.TypeMeta.given
import wifihaven.api.routes.*
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
import zio.test.*

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * #2135 (multi-tenant P5-5, epic #622): the billing webhook state machine, end to end. Full stack,
 * embedded Postgres, REAL [[HouseholdBillingRepo]] — only the external [[StripeClient]] is stubbed
 * (docs/process/testing.md: mock ONLY external I/O). The webhook signature is computed in-test with
 * the same HMAC scheme the server verifies, and `now` is TestClock-driven so the replay window is
 * deterministic.
 *
 * Pins the transitions from design §5.1 (beta→active, active→lapsed, lapsed→active recovery) plus
 * the security property: an unsigned / forged payload changes nothing and the route 400s.
 */
object BillingWebhookSpec
    extends ZIOSpec[TestDatabase.AllRepos & EmbeddedPostgres & Clock & Transactor[Task]] {

  override val bootstrap =
    TestDatabase.layer ++ TestLayers.withClock(TestClock.schoolDayAfternoon)

  private val cleanDb = TestDatabase.cleanAndMigrate

  private val WebhookSecret = "whsec_test_secret_at_least_32_chars_long!"
  private val CustomerId    = "cus_test123"

  private val jwt = JwtConfig(secret = "test-secret-at-least-32-chars!!x", expiryHours = 1)

  private val cfg = StripeConfig(
    secretKey = "sk_test_dummy",
    webhookSecret = WebhookSecret,
    priceMonthly = "price_monthly",
    priceAnnual = "price_annual",
    foundingPromoCode = "promo_founding",
    appBaseUrl = "https://app.test",
  )

  // A stub external client — checkout/portal return fixed URLs. The webhook path never calls it
  // (verification + repo only); these back the admin route tests.
  private val stubStripe: StripeClient = new StripeClient {
    def createCustomer(email: String, householdId: Long): IO[StripeError, String]           =
      ZIO.succeed(CustomerId)
    def createCheckoutSession(params: CheckoutParams): IO[StripeError, String]              =
      ZIO.succeed(s"https://checkout.stripe.test/${params.clientReferenceId}")
    def createPortalSession(customerId: String, returnUrl: String): IO[StripeError, String] =
      ZIO.succeed(s"https://portal.stripe.test/$customerId")
  }

  // Compute a valid `Stripe-Signature` header (t=<ts>,v1=<hex>) over `ts.payload`.
  private def sign(payload: String, ts: Long): String = {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(new SecretKeySpec(WebhookSecret.getBytes("UTF-8"), "HmacSHA256"))
    val hex = mac.doFinal(s"$ts.$payload".getBytes("UTF-8")).map(b => f"${b & 0xff}%02x").mkString
    s"t=$ts,v1=$hex"
  }

  private def makeAuth: ZIO[UserRepo & HouseholdRepo & Clock, Nothing, AuthService] =
    for {
      ur    <- ZIO.service[UserRepo]
      hr    <- ZIO.service[HouseholdRepo]
      clock <- ZIO.service[Clock]
    } yield AuthServiceLive(ur, jwt, clock, hr): AuthService

  // Seed a household + its household_billing row (status='beta', a stored Stripe customer).
  private def seedHousehold(xa: Transactor[Task], hbr: HouseholdBillingRepo): Task[HouseholdId] =
    for {
      hid <-
        sql"INSERT INTO households(name, slug, router_cap) VALUES('Bill Fam','bill-fam',1) RETURNING id"
          .query[HouseholdId]
          .unique
          .transact(xa)
      _   <- hbr.create(hid, "beta", founding = true)
      _   <- hbr.setStripeCustomer(hid, CustomerId)
    } yield hid

  // Returns (status, lapsed_at-is-set). A boolean avoids a tuple Read over Option[Instant].
  private def statusOf(
      xa: Transactor[Task],
      hid: HouseholdId,
  ): Task[(String, Boolean)] =
    sql"SELECT status, (lapsed_at IS NOT NULL) FROM household_billing WHERE household_id=$hid"
      .query[(String, Boolean)]
      .unique
      .transact(xa)

  private def checkoutPayload(hid: HouseholdId): String =
    s"""{"type":"checkout.session.completed","data":{"object":{"customer":"$CustomerId","subscription":"sub_1","client_reference_id":"${hid.value}"}}}"""

  private val paymentFailedPayload =
    s"""{"type":"invoice.payment_failed","data":{"object":{"customer":"$CustomerId"}}}"""

  private val subDeletedPayload =
    s"""{"type":"customer.subscription.deleted","data":{"object":{"id":"sub_1","customer":"$CustomerId"}}}"""

  def spec = suite("Billing webhook state machine (#2135)")(
    test("full lifecycle: beta → active (checkout) → lapsed (payment_failed) → active (recovery)") {
      for {
        _     <- cleanDb
        xa    <- ZIO.service[Transactor[Task]]
        clock <- ZIO.service[Clock]
        hbr   <- ZIO.service[HouseholdBillingRepo]
        svc = BillingService(stubStripe, hbr, clock, cfg)
        hid <- seedHousehold(xa, hbr)
        now <- clock.instant
        p1 = checkoutPayload(hid)
        o1       <- svc.handleWebhook(p1, Some(sign(p1, now.getEpochSecond)))
        (s1, l1) <- statusOf(xa, hid)
        o2       <- svc.handleWebhook(
          paymentFailedPayload,
          Some(sign(paymentFailedPayload, now.getEpochSecond)),
        )
        (s2, l2) <- statusOf(xa, hid)
        o3       <- svc.handleWebhook(p1, Some(sign(p1, now.getEpochSecond)))
        (s3, l3) <- statusOf(xa, hid)
        sub <- sql"SELECT stripe_subscription_id FROM household_billing WHERE household_id=$hid"
          .query[Option[String]]
          .unique
          .transact(xa)
      } yield assertTrue(
        o1 == WebhookOutcome.Active,
        s1 == "active",
        !l1,
        o2 == WebhookOutcome.Lapsed,
        s2 == "lapsed",
        l2,
        o3 == WebhookOutcome.Active,
        s3 == "active",
        !l3,
        sub.contains("sub_1"),
      )
    },
    test("customer.subscription.deleted lapses an active household") {
      for {
        _     <- cleanDb
        xa    <- ZIO.service[Transactor[Task]]
        clock <- ZIO.service[Clock]
        hbr   <- ZIO.service[HouseholdBillingRepo]
        svc = BillingService(stubStripe, hbr, clock, cfg)
        hid <- seedHousehold(xa, hbr)
        now <- clock.instant
        p1 = checkoutPayload(hid)
        _      <- svc.handleWebhook(p1, Some(sign(p1, now.getEpochSecond)))
        o      <- svc.handleWebhook(
          subDeletedPayload,
          Some(sign(subDeletedPayload, now.getEpochSecond)),
        )
        (s, l) <- statusOf(xa, hid)
      } yield assertTrue(o == WebhookOutcome.Lapsed, s == "lapsed", l)
    },
    test("an unsigned or forged payload is rejected and changes nothing") {
      for {
        _     <- cleanDb
        xa    <- ZIO.service[Transactor[Task]]
        clock <- ZIO.service[Clock]
        hbr   <- ZIO.service[HouseholdBillingRepo]
        svc = BillingService(stubStripe, hbr, clock, cfg)
        hid <- seedHousehold(xa, hbr)
        p1 = checkoutPayload(hid)
        oNone   <- svc.handleWebhook(p1, None)
        oForged <- svc.handleWebhook(p1, Some("t=1,v1=deadbeef"))
        (s, _)  <- statusOf(xa, hid)
      } yield assertTrue(
        oNone == WebhookOutcome.InvalidSignature,
        oForged == WebhookOutcome.InvalidSignature,
        s == "beta",
      )
    },
    test("the webhook route returns 400 on a bad signature, 200 on a valid one") {
      for {
        _     <- cleanDb
        xa    <- ZIO.service[Transactor[Task]]
        clock <- ZIO.service[Clock]
        hbr   <- ZIO.service[HouseholdBillingRepo]
        auth  <- makeAuth
        svc    = BillingService(stubStripe, hbr, clock, cfg)
        // #2137: this spec exercises only the webhook route; the flip-window effect (GET /api/billing
        // only) is a fixed closed window here and is covered end-to-end in BetaFlipLifecycleSpec.
        routes = BillingRoutes.routes(
          auth,
          svc,
          ZIO.succeed(FlipService.FlipWindow(open = false, flipDate = None)),
        )
        hid <- seedHousehold(xa, hbr)
        now <- clock.instant
        p1 = checkoutPayload(hid)
        badResp <- routes.runZIO(
          Request
            .post(URL.decode("/api/billing/webhook").toOption.get, Body.fromString(p1))
            .addHeader("Stripe-Signature", "t=1,v1=deadbeef"),
        )
        okResp  <- routes.runZIO(
          Request
            .post(URL.decode("/api/billing/webhook").toOption.get, Body.fromString(p1))
            .addHeader("Stripe-Signature", sign(p1, now.getEpochSecond)),
        )
      } yield assertTrue(badResp.status == Status.BadRequest, okResp.status == Status.Ok)
    },
    // #2137 (A1): a checkout started DURING the open flip window defers the first charge to the
    // window end via Stripe trial_end; outside the window there is no trial (immediate charge).
    test("A1: startCheckout threads the window-end trial_end into the Checkout Session") {
      for {
        _        <- cleanDb
        xa       <- ZIO.service[Transactor[Task]]
        clock    <- ZIO.service[Clock]
        hbr      <- ZIO.service[HouseholdBillingRepo]
        captured <- Ref.make(Option.empty[CheckoutParams])
        recStripe = new StripeClient {
          def createCustomer(email: String, householdId: Long): IO[StripeError, String]           =
            ZIO.succeed(CustomerId)
          def createCheckoutSession(params: CheckoutParams): IO[StripeError, String]              =
            captured.set(Some(params)).as("https://checkout.stripe.test/x")
          def createPortalSession(customerId: String, returnUrl: String): IO[StripeError, String] =
            ZIO.succeed("https://portal.stripe.test/x")
        }
        svc       = BillingService(recStripe, hbr, clock, cfg)
        hid <- seedHousehold(xa, hbr)
        now <- clock.instant
        windowEnd = java.time.Instant.parse("2026-09-01T00:00:00Z") // well beyond the 49h floor
        _        <- svc.startCheckout(hid, Some(windowEnd))
        inWindow <- captured.get
        _        <- svc.startCheckout(hid, None)
        noWindow <- captured.get
        // Within the 49h floor Stripe would reject trial_end — the trial is dropped (immediate charge).
        _        <- svc.startCheckout(hid, Some(now.plus(java.time.Duration.ofHours(1))))
        nearEnd  <- captured.get
      } yield assertTrue(
        inWindow.exists(_.trialEnd.contains(windowEnd)),
        noWindow.exists(_.trialEnd.isEmpty),
        nearEnd.exists(_.trialEnd.isEmpty),
      )
    },
  ) @@ TestAspect.sequential
}
