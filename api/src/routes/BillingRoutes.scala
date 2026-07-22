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
            // #2137 (A1): subscribing during the open flip window defers the first charge to the
            // window end (Stripe trial_end) — everyone gets the full free beta. Outside the window
            // (closed / not started) there's no trial and the first charge is immediate.
            window <- flipWindow
            trialEnd = if window.open then window.flipDate else None
            url <- billing
              .startCheckout(claims.hh, trialEnd)
              .tapError(e => AppMetrics.recordBillingCheckout("checkout", checkoutOutcome(e)))
              .mapError(billingErrorToApi)
            _   <- AppMetrics.recordBillingCheckout("checkout", "ok")
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

      // ── Operator: grant a household the free_forever status (#2356) ────────────
      // requireOperator (admin AND household 1) — the same narrow cross-household gate beta approval
      // uses. Acts on an ARBITRARY household id (not claims.hh), which is exactly the operator's
      // privileged capability. Never self-serve.
      Method.POST / "api" / "operator" / "households" / long("id") / "free-forever" ->
        handler { (id: Long, req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            _ <- requireOperator(req, auth)
            _ <- billing
              .grantFreeForever(wifihaven.shared.types.HouseholdId(id))
              .mapError(billingErrorToApi)
            _ <- AppMetrics.recordFreeForever("grant")
          } yield Response.status(Status.Ok)
          handle.mapError(ErrorMapper.errorToResponse)
        },

      // ── Operator: revoke free_forever, returning the household to beta (#2356) ──
      Method.DELETE / "api" / "operator" / "households" / long("id") / "free-forever" ->
        handler { (id: Long, req: Request) =>
          val handle: ZIO[Any, ApiError, Response] = for {
            _ <- requireOperator(req, auth)
            _ <- billing
              .revokeFreeForever(wifihaven.shared.types.HouseholdId(id))
              .mapError(billingErrorToApi)
            _ <- AppMetrics.recordFreeForever("revoke")
          } yield Response.status(Status.Ok)
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
    case BillingError.NotConfigured  => "not_configured"
    case BillingError.NoBillingRow   => "no_billing_row"
    case BillingError.NoCustomer     => "no_customer"
    case BillingError.FreeForever    => "free_forever"
    case BillingError.NotFreeForever => "not_free_forever"
    case BillingError.Stripe(_)      => "stripe_error"
    case BillingError.Db(_)          => "error"
  }

  private def billingErrorToApi(e: BillingError): ApiError = e match {
    // Billing not configured (self-hosted / no keys) → 404, the route effectively doesn't exist.
    case BillingError.NotConfigured  => ApiError.NotFound("billing not configured")
    case BillingError.NoBillingRow   => ApiError.NotFound("no billing record for this household")
    case BillingError.NoCustomer     =>
      ApiError.Wrapped(Response.status(Status.Conflict))
    // #2356: a free_forever household is never billed (checkout/portal backstop), and revoking a
    // household that isn't free_forever is a no-op — both are 409 Conflict (state, not "not found").
    case BillingError.FreeForever    => ApiError.Wrapped(Response.status(Status.Conflict))
    case BillingError.NotFreeForever => ApiError.Wrapped(Response.status(Status.Conflict))
    case BillingError.Stripe(_)      => ApiError.Internal("billing provider error")
    case BillingError.Db(c)          => ApiError.Db(c)
  }
}
