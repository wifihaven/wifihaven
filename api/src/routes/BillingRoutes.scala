package wifihaven.api.routes

import wifihaven.api.auth.*
import wifihaven.api.billing.*
import wifihaven.api.db.HouseholdBilling
import wifihaven.api.metrics.AppMetrics
import wifihaven.shared.*
import zio.*
import zio.http.*
import zio.json.*

/**
 * #2135 (multi-tenant P5-5, epic #622): the billing API (design docs/design/multi-tenant-launch.md
 * §5.2). Four surfaces:
 *
 *   - ADMIN `GET /api/billing` — current plan/status for the SPA billing page
 *   - ADMIN `POST /api/billing/checkout` — hosted Checkout Session (conversion / recovery)
 *   - ADMIN `GET /api/billing/portal` — hosted Customer Portal session (cancel / card update)
 *   - PUBLIC `POST /api/billing/webhook` — signature-verified Stripe event → the status machine
 *
 * The three admin surfaces are per-household — they act on `claims.hh` only, never another
 * household's billing (multi-tenant isolation §9). The webhook is unauthenticated by design: Stripe
 * has no bearer token; its `Stripe-Signature` HMAC (verified in [[BillingService.handleWebhook]]
 * via the configured signing secret) IS the authentication. An unsigned/forged/replayed payload is
 * rejected there and the route returns 400.
 */
object BillingRoutes {

  /**
   * Cap the webhook body we read so a hostile unauthenticated caller can't stream us out of memory.
   */
  val MaxWebhookBytes: Long = 512 * 1024

  def routes(
      auth: AuthService,
      billing: BillingService,
      // #2137: the cohort flip-window state (SSOT via FlipService.currentWindow), passed as an
      // already-failure-handled effect so this route stays decoupled from FlipService's deps. Main
      // wires `flipService.currentWindow.orElseSucceed(closed)`; the flip walk is exercised in
      // BetaFlipLifecycleSpec, not through this route.
      flipWindow: UIO[FlipService.FlipWindow],
  ): Routes[Any, Response] =
    Routes(
      // ── Admin: current billing status for the SPA billing page ────────────────
      Method.GET / "api" / "billing" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireAdmin(req, auth)
            row    <- billing.status(claims.hh).mapError(billingErrorToApi)
            // #2137: fold in the cohort flip-window state so the SPA can gate the Subscribe CTA +
            // conversion banner (design §5.4, A1).
            window <- flipWindow
          } yield Response.json(toStatusResponse(row, window).toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },

      // ── Admin: start a Checkout Session, return the redirect URL ───────────────
      Method.POST / "api" / "billing" / "checkout" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireAdmin(req, auth)
            url    <- billing
              .startCheckout(claims.hh)
              .tapError(e => AppMetrics.recordBillingCheckout("checkout", checkoutOutcome(e)))
              .mapError(billingErrorToApi)
            _      <- AppMetrics.recordBillingCheckout("checkout", "ok")
          } yield Response.json(BillingRedirect(url).toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },

      // ── Admin: start a Customer Portal session, return the redirect URL ────────
      Method.GET / "api" / "billing" / "portal" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            claims <- requireAdmin(req, auth)
            url    <- billing
              .startPortal(claims.hh)
              .tapError(e => AppMetrics.recordBillingCheckout("portal", checkoutOutcome(e)))
              .mapError(billingErrorToApi)
            _      <- AppMetrics.recordBillingCheckout("portal", "ok")
          } yield Response.json(BillingRedirect(url).toJson)
          handle.mapError(ErrorMapper.errorToResponse)
        },

      // ── Public: signature-verified Stripe webhook → the status machine ─────────
      Method.POST / "api" / "billing" / "webhook" ->
        handler { (req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            // The RAW body is what the signature was computed over — read it verbatim, never re-serialize.
            body <- req.body.asString.orElseFail(ApiError.BadRequest(""))
            _    <- ZIO
              .fail(ApiError.BadRequest("payload too large"))
              .when(body.length.toLong > MaxWebhookBytes)
            sig = req.headers.get("Stripe-Signature")
            outcome <- billing.handleWebhook(body, sig)
            _       <- AppMetrics.recordBillingWebhook(WebhookOutcome.label(outcome))
            resp    <- outcome match {
              // A bad signature is the security-relevant rejection — 400, no state changed.
              case WebhookOutcome.InvalidSignature =>
                ZIO.fail(ApiError.BadRequest("invalid signature"))
              // Everything else (applied / ignored / unmatched / not-configured) is a 200 so Stripe
              // stops retrying — an unmatched customer or an event type we don't act on is not an error.
              case _                               => ZIO.succeed(Response.ok)
            }
          } yield resp
          handle.mapError(ErrorMapper.errorToResponse)
        },
    )

  private def toStatusResponse(
      b: HouseholdBilling,
      window: FlipService.FlipWindow,
  ): BillingStatusResponse =
    BillingStatusResponse(
      status = b.status,
      founding = b.founding,
      priceId = b.priceId,
      currentPeriodEnd = b.currentPeriodEnd.map(_.toString),
      lapsedAt = b.lapsedAt.map(_.toString),
      flipWindowOpen = window.open,
      flipDate = window.flipDate.map(_.toString),
    )

  // The webhook never leaks WHY to the caller; these outcomes only feed the checkout/portal metric.
  private def checkoutOutcome(e: BillingError): String = e match {
    case BillingError.NotConfigured => "not_configured"
    case BillingError.NoBillingRow  => "no_billing_row"
    case BillingError.NoCustomer    => "no_customer"
    case BillingError.Stripe(_)     => "stripe_error"
    case BillingError.Db(_)         => "error"
  }

  private def billingErrorToApi(e: BillingError): ApiError = e match {
    // Billing not configured (self-hosted / no keys) → 404, the route effectively doesn't exist.
    case BillingError.NotConfigured => ApiError.NotFound("billing not configured")
    case BillingError.NoBillingRow  => ApiError.NotFound("no billing record for this household")
    case BillingError.NoCustomer    =>
      ApiError.Wrapped(Response.status(Status.Conflict))
    case BillingError.Stripe(_)     => ApiError.Internal("billing provider error")
    case BillingError.Db(c)         => ApiError.Db(c)
  }
}
