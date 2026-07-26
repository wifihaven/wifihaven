# Stripe billing bootstrap (#2135, multi-tenant P5-5)

The minimal Stripe surface WifiHaven bills on — deliberately small, per
[`docs/design/pricing-analysis.md`](../../docs/design/pricing-analysis.md) §7 and
[`docs/design/multi-tenant-launch.md`](../../docs/design/multi-tenant-launch.md) §5.

## Objects

| Need | Stripe primitive |
|---|---|
| Two prices ($10/mo, $96/yr) | 1 Product + 2 recurring Prices |
| Founding discount | 1 Coupon `percent_off=40, duration=forever` → Promotion Code `FOUNDING`, `restrictions[first_time_transaction]=true` |
| Beta period | **nothing** — a Customer is created at provisioning only (no card, no subscription) |
| Conversion | Checkout Session (`mode=subscription`, FOUNDING pre-applied for founding households) |
| Self-serve cancel / card update | Customer Portal session |
| Dunning | Stripe Smart Retries + the signature-verified webhook |

## One-time bootstrap

```sh
# Test mode
STRIPE_SECRET_KEY=sk_test_... ./deploy/stripe/setup.sh
# Live mode (prod)
STRIPE_SECRET_KEY=sk_live_... ./deploy/stripe/setup.sh
```

The script is idempotent (reuses the Product/Prices by `lookup_key`, and only creates the coupon /
promo if `FOUNDING` doesn't already exist). It prints the price + promo ids to copy into the
deployment env (`WIFIHAVEN_STRIPE_PRICE_MONTHLY`, `_PRICE_ANNUAL`, `_FOUNDING_PROMO`). Run it once
per Stripe mode (test ids ≠ live ids), which is exactly why those ids are config, not constants.

## Webhook endpoint

Create a Stripe webhook endpoint (Dashboard → Developers → Webhooks, or the API) pointing at:

```
POST https://api.wifihaven.net/api/billing/webhook          # prod (live mode)
POST https://api-staging.wifihaven.net/api/billing/webhook  # staging (test mode)
```

Subscribe it to at least: `checkout.session.completed`, `invoice.payment_failed`,
`customer.subscription.deleted`. Copy the endpoint's signing secret (`whsec_…`) into
`WIFIHAVEN_STRIPE_WEBHOOK_SECRET` (Render sync:false secret — **never committed**). The server
verifies every delivery's `Stripe-Signature` HMAC against this secret; an unsigned/forged/replayed
payload is rejected with 400 and changes no state.

## Secrets

Never commit the secret key or webhook signing secret. On cloud they are Render-managed `sync:false`
env vars (see [`render.yaml`](../../render.yaml)); on self-hosted they go in the `stripe {}` block of
`application.conf` (see [`config/application.conf.example`](../../config/application.conf.example)).
**Absence of a secret is NOT the off-switch** — `wifihaven.stripe.enabled` is
(`WIFIHAVEN_STRIPE_ENABLED`; #2266, no-dark-by-default rule 3). `enabled=false` is the correct
self-hosted default (self-hosted installs never bill): `POST /api/billing/checkout` and
`/portal` then return **404** `"billing not configured"` and no secret is required (`GET
/api/billing` is not gated — it still reports the household's billing row). With `enabled=true`,
**both** `secretKey` and
`webhookSecret` are REQUIRED, and an empty one **fails boot** naming the missing key
(`StripeConfig.validate` → `AppConfig.validateRequired`) — it does not disable anything. So set both
secrets *before* flipping `WIFIHAVEN_STRIPE_ENABLED` to `true` in an environment. The
`webhookSecret` half is #2414: without it every delivery would answer HTTP 200 and no-op, so Stripe
would record success, never retry, and no subscription state would ever advance.

## Basil coupon-duration caveat (pre-build verification)

`docs/design/pricing-analysis.md` §7 flagged that Stripe's **2025-03-31 "basil"** changelog appears
to restrict coupons without an end time, and required verifying `duration=forever` before building.

**Finding (verified):** the restriction
([docs.stripe.com/changelog/basil/2025-03-31/restrict-coupon-duration](https://docs.stripe.com/changelog/basil/2025-03-31/restrict-coupon-duration))
removes support for coupons that combine **`amount_off`** with **`duration=forever`** — it does *not*
apply to **`percent_off`** coupons. Our FOUNDING coupon is `percent_off=40, duration=forever`, so it
is unaffected and remains creatable and attachable to subscriptions / Checkout Sessions on the pinned
API version `2025-03-31.basil` (see `StripeClient.ApiVersion`). No workaround (per-invoice discount)
is needed.

## Explicitly NOT built (pricing §7)

Subscription schedules, metered/usage billing, per-seat quantities, multi-currency / Stripe Tax,
lifetime SKUs. Entitlements resolve per household from the subscription's Price/Product (never a
global constant), so a later multi-home tier is "add a second Price + raise `router_cap`."
