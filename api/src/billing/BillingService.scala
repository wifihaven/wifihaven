package wifihaven.api.billing

import wifihaven.api.StripeConfig
import wifihaven.api.db.{HouseholdBilling, HouseholdBillingRepo}
import wifihaven.shared.Clock
import wifihaven.shared.types.HouseholdId
import zio.*

/**
 * #2135 (multi-tenant P5-5, epic #622): the billing orchestration layer — the ONE state machine
 * (design docs/design/multi-tenant-launch.md §5.1) that drives `household_billing.status` through
 * `beta → active → lapsed → active`, plus the Checkout / Portal entry points. It sits between the
 * routes and the pure Stripe pieces:
 *   - external I/O (customer/checkout/portal creates) goes through [[StripeClient]] (mocked in
 *     specs);
 *   - webhook signature verification + event parsing is pure ([[StripeWebhook]]);
 *   - the resulting status transitions go through [[HouseholdBillingRepo]] (real in specs).
 *
 * LAPSE MODEL (operator decision 2026-07-09, §5.3): a lapse means enforcement STOPS (a permissive
 * snapshot via existing wire fields — implemented on the P5-6/#2137 side); there is NO read-only
 * gating here. This service only moves the status column; PolicyService reads it to decide whether
 * to enforce. Recovery (`checkout.session.completed`) re-arms by flipping back to `active`.
 *
 * `Clock` is injected so `lapsed_at` / `updated_at` and the webhook replay window are
 * TestClock-driven.
 */
final case class BillingService(
    stripe: StripeClient,
    repo: HouseholdBillingRepo,
    clock: Clock,
    cfg: StripeConfig,
) {
  import BillingService.*

  /**
   * Provisioning-time Customer seam (#2135 scope 2, extends #2132's approve path): create a Stripe
   * Customer for the freshly-provisioned household and store its id on `household_billing`. No
   * card, no subscription during beta (pricing §5.2). Best-effort — a Stripe hiccup must NOT fail
   * the whole approval (the household already exists); the checkout path is the backstop that
   * surfaces a missing customer. Returns whether a customer was created (feeds the outcome metric).
   */
  def provisionCustomer(householdId: HouseholdId, email: String): UIO[Boolean] =
    if !cfg.enabled then ZIO.succeed(false)
    else
      stripe
        .createCustomer(email, householdId.value)
        .flatMap(cid => repo.setStripeCustomer(householdId, cid).orDie.as(true))
        .catchAll(err =>
          ZIO
            .logWarning(
              s"stripe customer provisioning failed for household ${householdId.value}: $err",
            )
            .as(false),
        )

  /**
   * `POST /api/billing/checkout`: create a hosted Checkout Session for the household's Customer and
   * return the redirect URL. FOUNDING is pre-applied when the household is `founding` and a promo
   * code is configured. Requires a Customer to exist (created at provisioning); a missing Customer
   * is a NoCustomer error the route surfaces as 409.
   */
  def startCheckout(householdId: HouseholdId): IO[BillingError, String] =
    for {
      _        <- ZIO.fail(BillingError.NotConfigured).when(!cfg.enabled)
      billing  <- loadBilling(householdId)
      customer <- ZIO.fromOption(billing.stripeCustomerId).orElseFail(BillingError.NoCustomer)
      priceId  <- ZIO
        .fromOption(Option(cfg.priceMonthly).map(_.trim).filter(_.nonEmpty))
        .orElseFail(BillingError.NotConfigured)
      promo = if billing.founding then cfg.foundingPromoCodeOpt else None
      url <- stripe
        .createCheckoutSession(
          CheckoutParams(
            customerId = customer,
            priceId = priceId,
            promotionCodeId = promo,
            clientReferenceId = householdId.value.toString,
            successUrl = cfg.checkoutSuccessUrl,
            cancelUrl = cfg.checkoutCancelUrl,
          ),
        )
        .mapError(BillingError.Stripe(_))
    } yield url

  /**
   * `GET /api/billing/portal`: hosted Customer Portal session for self-serve cancel/card-update.
   */
  def startPortal(householdId: HouseholdId): IO[BillingError, String] =
    for {
      _        <- ZIO.fail(BillingError.NotConfigured).when(!cfg.enabled)
      billing  <- loadBilling(householdId)
      customer <- ZIO.fromOption(billing.stripeCustomerId).orElseFail(BillingError.NoCustomer)
      url      <- stripe
        .createPortalSession(customer, cfg.portalReturnUrl)
        .mapError(BillingError.Stripe(_))
    } yield url

  /** Current billing state for the SPA billing page (plan/status + founding flag). */
  def status(householdId: HouseholdId): IO[BillingError, HouseholdBilling] =
    loadBilling(householdId)

  private def loadBilling(householdId: HouseholdId): IO[BillingError, HouseholdBilling] =
    repo
      .findByHousehold(householdId)
      .mapError(BillingError.Db(_))
      .someOrFail(BillingError.NoBillingRow)

  /**
   * `POST /api/billing/webhook`: the signature-verified state-machine driver. Verifies the
   * `Stripe-Signature` HMAC against the configured signing secret (rejecting
   * unsigned/forged/replayed payloads), maps the event's customer to a household, and applies the
   * transition:
   *   - `checkout.session.completed` → active (store subscription/price ids, clear lapsed_at)
   *   - `invoice.payment_failed` (terminal) / `customer.subscription.deleted` → lapsed (+
   *     lapsed_at)
   *
   * Returns a bounded [[WebhookOutcome]] for the metric — never a per-customer/-household value.
   */
  def handleWebhook(payload: String, sigHeader: Option[String]): UIO[WebhookOutcome] =
    if cfg.webhookSecret.trim.isEmpty then ZIO.succeed(WebhookOutcome.NotConfigured)
    else
      clock.instant.flatMap { now =>
        StripeWebhook.verifyAndParse(payload, sigHeader, cfg.webhookSecret, now) match {
          case Left(_)      => ZIO.succeed(WebhookOutcome.InvalidSignature)
          case Right(event) => applyEvent(event, now)
        }
      }

  private def applyEvent(event: StripeWebhook.Event, now: java.time.Instant): UIO[WebhookOutcome] =
    event.eventType match {
      case t if ActiveEvents.contains(t) =>
        withHousehold(event) { hid =>
          repo
            .markActive(hid, event.subscriptionId, event.priceId, event.currentPeriodEnd, now)
            .orDie
            .as(WebhookOutcome.Active)
        }
      case t if LapseEvents.contains(t)  =>
        withHousehold(event) { hid => repo.markLapsed(hid, now).orDie.as(WebhookOutcome.Lapsed) }
      case _                             =>
        ZIO.succeed(WebhookOutcome.Ignored)
    }

  // Resolve the household from the event: prefer client_reference_id (set on Checkout to the
  // household id), else the customer-id lookup (the only handle terminal events carry).
  private def withHousehold(
      event: StripeWebhook.Event,
  )(f: HouseholdId => UIO[WebhookOutcome]): UIO[WebhookOutcome] = {
    val byRef = event.clientReferenceId
      .flatMap(s => s.toLongOption)
      .map(HouseholdId(_))
    byRef match {
      case Some(hid) => f(hid)
      case None      =>
        event.customerId match {
          case None      => ZIO.succeed(WebhookOutcome.Unmatched)
          case Some(cid) =>
            repo.findByStripeCustomerId(cid).orDie.flatMap {
              case Some(b) => f(b.householdId)
              case None    => ZIO.succeed(WebhookOutcome.Unmatched)
            }
        }
    }
  }
}

object BillingService {

  /** Event types that flip a household to `active` (conversion + recovery). */
  val ActiveEvents: Set[String] = Set("checkout.session.completed")

  /** Dunning-terminal event types that flip a household to `lapsed` (§5.1). */
  val LapseEvents: Set[String] =
    Set("invoice.payment_failed", "customer.subscription.deleted")

  val layer: ZLayer[
    StripeClient & HouseholdBillingRepo & Clock & StripeConfig,
    Nothing,
    BillingService,
  ] =
    ZLayer.fromFunction(BillingService.apply)
}

/** Bounded webhook outcomes for the metric label (never per-household). */
enum WebhookOutcome   {
  case Active, Lapsed, Ignored, Unmatched, InvalidSignature, NotConfigured
}
object WebhookOutcome {
  def label(o: WebhookOutcome): String = o match {
    case Active           => "active"
    case Lapsed           => "lapsed"
    case Ignored          => "ignored"
    case Unmatched        => "unmatched"
    case InvalidSignature => "invalid_signature"
    case NotConfigured    => "not_configured"
  }
}

/** Typed failures the billing routes map to HTTP statuses. */
sealed trait BillingError
object BillingError {
  case object NotConfigured extends BillingError // billing disabled (no keys) → 404-shaped
  case object NoBillingRow  extends BillingError // household has no household_billing row
  case object NoCustomer    extends BillingError // no Stripe Customer yet (provisioning gap)
  case class Stripe(e: StripeError) extends BillingError
  case class Db(cause: Throwable)   extends BillingError
}
