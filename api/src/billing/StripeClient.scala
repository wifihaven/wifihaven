package wifihaven.api.billing

import wifihaven.api.StripeConfig
import zio.*

import java.net.URI
import java.net.URLEncoder
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration as JDuration

/**
 * #2135 (multi-tenant P5-5, epic #622): the external Stripe I/O surface — the ONLY thing that talks
 * to api.stripe.com over the network. Everything policy-shaped (the beta/active/lapsed state
 * machine, webhook signature verification, which promo to apply) lives above this in pure code so
 * it can be feature-tested without a live Stripe; this trait is the seam a spec replaces with an
 * in-memory fake (docs/process/testing.md — mock ONLY external I/O, repos stay real).
 *
 * The minimal surface per pricing-analysis.md §7: a Customer at provisioning, a Checkout Session at
 * conversion, and a Customer Portal session for self-serve cancel/card-update. No subscription /
 * schedule / trial objects are created here — the subscription is born inside Stripe-hosted
 * Checkout.
 */
trait StripeClient {

  /** Create a Customer (email only; no card). Returns the `cus_…` id. */
  def createCustomer(email: String, householdId: Long): IO[StripeError, String]

  /**
   * Create a `mode=subscription` Checkout Session for `customerId` on `priceId`. When
   * `promotionCodeId` is set (founding household), it is pre-applied as a discount; otherwise the
   * session allows the customer to enter a promo code. `clientReferenceId` carries the household id
   * back on the `checkout.session.completed` webhook. Returns the hosted Checkout `url`.
   */
  def createCheckoutSession(params: CheckoutParams): IO[StripeError, String]

  /** Create a Customer Portal session for `customerId`. Returns the hosted portal `url`. */
  def createPortalSession(customerId: String, returnUrl: String): IO[StripeError, String]
}

/**
 * Inputs for a Checkout Session — assembled by BillingService from config + the household's row.
 */
final case class CheckoutParams(
    customerId: String,
    priceId: String,
    promotionCodeId: Option[String],
    clientReferenceId: String,
    successUrl: String,
    cancelUrl: String,
)

/** Typed failures the billing routes map to HTTP statuses. */
sealed trait StripeError
object StripeError {
  case object NotConfigured                 extends StripeError // no secret key set (self-hosted)
  case class Api(status: Int, body: String) extends StripeError // Stripe returned a non-2xx
  case class Transport(cause: Throwable)    extends StripeError // network / parse failure
}

object StripeClient {

  /**
   * Pin the Stripe API version. `2025-03-31.basil` is the version whose behavior we verified: its
   * coupon-duration restriction (changelog 2025-03-31) applies ONLY to `amount_off` +
   * `duration=forever` coupons, so our FOUNDING coupon (`percent_off=40, duration=forever`) is
   * unaffected and remains creatable/attachable. See deploy/stripe/README.md.
   */
  val ApiVersion: String = "2025-03-31.basil"

  private val disabledInstance: StripeClient = new StripeClient {
    def createCustomer(email: String, householdId: Long): IO[StripeError, String]           =
      ZIO.fail(StripeError.NotConfigured)
    def createCheckoutSession(params: CheckoutParams): IO[StripeError, String]              =
      ZIO.fail(StripeError.NotConfigured)
    def createPortalSession(customerId: String, returnUrl: String): IO[StripeError, String] =
      ZIO.fail(StripeError.NotConfigured)
  }

  /**
   * Public no-op instance — for self-hosted installs with no keys and for specs that don't drive
   * Stripe.
   */
  val noop: StripeClient = disabledInstance

  /**
   * No-op client for self-hosted / test installs with no Stripe keys: every call fails
   * NotConfigured.
   */
  val disabled: ULayer[StripeClient] = ZLayer.succeed(disabledInstance)

  val layer: ZLayer[StripeConfig, Nothing, StripeClient] =
    ZLayer.fromZIO {
      ZIO.serviceWith[StripeConfig](cfg => if cfg.enabled then new Live(cfg) else disabledInstance)
    }

  final class Live(cfg: StripeConfig) extends StripeClient {
    private val client = HttpClient
      .newBuilder()
      .connectTimeout(JDuration.ofSeconds(15))
      .build()

    private def enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

    /** x-www-form-urlencoded body from ordered (key,value) pairs (Stripe's array bracket form). */
    private def formBody(pairs: List[(String, String)]): String =
      pairs.map { case (k, v) => s"${enc(k)}=${enc(v)}" }.mkString("&")

    private def post(path: String, pairs: List[(String, String)]): IO[StripeError, String] =
      ZIO
        .attemptBlocking {
          val req = HttpRequest
            .newBuilder(URI.create(s"${cfg.apiBase}$path"))
            .header("Authorization", s"Bearer ${cfg.secretKey}")
            .header("Stripe-Version", ApiVersion)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .timeout(JDuration.ofSeconds(30))
            .POST(HttpRequest.BodyPublishers.ofString(formBody(pairs)))
            .build()
          client.send(req, HttpResponse.BodyHandlers.ofString())
        }
        .mapError(StripeError.Transport(_))
        .flatMap { resp =>
          if resp.statusCode() / 100 == 2 then ZIO.succeed(resp.body())
          else ZIO.fail(StripeError.Api(resp.statusCode(), resp.body()))
        }

    // Extract a top-level JSON string field. Stripe responses are flat objects for `id` / `url`.
    private def field(json: String, key: String): IO[StripeError, String] =
      ZIO
        .fromOption(StripeJson.stringField(json, key))
        .orElseFail(StripeError.Transport(new RuntimeException(s"missing $key in Stripe response")))

    def createCustomer(email: String, householdId: Long): IO[StripeError, String] =
      post(
        "/v1/customers",
        List(
          "email"                  -> email,
          "metadata[household_id]" -> householdId.toString,
        ),
      ).flatMap(field(_, "id"))

    def createCheckoutSession(params: CheckoutParams): IO[StripeError, String] = {
      val base     = List(
        "mode"                    -> "subscription",
        "customer"                -> params.customerId,
        "line_items[0][price]"    -> params.priceId,
        "line_items[0][quantity]" -> "1",
        "client_reference_id"     -> params.clientReferenceId,
        "success_url"             -> params.successUrl,
        "cancel_url"              -> params.cancelUrl,
      )
      // Founding households get FOUNDING pre-applied; everyone else may enter a code at Checkout.
      val discount = params.promotionCodeId match {
        case Some(promo) => List("discounts[0][promotion_code]" -> promo)
        case None        => List("allow_promotion_codes" -> "true")
      }
      post("/v1/checkout/sessions", base ++ discount).flatMap(field(_, "url"))
    }

    def createPortalSession(customerId: String, returnUrl: String): IO[StripeError, String] =
      post(
        "/v1/billing_portal/sessions",
        List("customer" -> customerId, "return_url" -> returnUrl),
      ).flatMap(field(_, "url"))
  }
}
