#!/usr/bin/env bash
# #2135 (multi-tenant P5-5, epic #622): one-time Stripe object bootstrap.
#
# Creates the MINIMAL Stripe surface WifiHaven bills on (design docs/design/pricing-analysis.md §7,
# multi-tenant-launch.md §5.2):
#   - 1 Product ("WifiHaven")
#   - 2 recurring Prices: $10/mo and $96/yr
#   - 1 Coupon: percent_off=40, duration=forever   (founding discount)
#   - 1 Promotion Code "FOUNDING" wrapping that coupon, first_time_transaction only
#
# This is declarative-by-idempotence: re-running with the same lookup keys does not duplicate the
# Product/Prices (it reuses them by lookup_key). Coupons/promo codes have no lookup_key, so the
# script only creates them if the promo code "FOUNDING" does not already exist.
#
# It prints the created ids at the end — copy them into the deployment's env
# (WIFIHAVEN_STRIPE_PRICE_MONTHLY / _PRICE_ANNUAL / _FOUNDING_PROMO). The secret key + webhook
# signing secret are NEVER stored here — pass the secret key via env for THIS run only.
#
# ── Basil coupon-duration caveat (verified before building) ─────────────────────
# Stripe's 2025-03-31 "basil" changelog restricts open-ended coupons — but ONLY coupons that combine
# `amount_off` with `duration=forever`. Our FOUNDING coupon uses `percent_off=40` with
# `duration=forever`, which is NOT covered by the restriction and remains creatable and attachable to
# subscriptions / Checkout Sessions on the pinned API version (2025-03-31.basil). Verified via
# docs.stripe.com/changelog/basil/2025-03-31/restrict-coupon-duration. So no workaround (per-invoice
# discount) is needed.
#
# Usage:
#   STRIPE_SECRET_KEY=sk_test_... ./deploy/stripe/setup.sh
#   STRIPE_SECRET_KEY=sk_live_... ./deploy/stripe/setup.sh    # live mode
#
# Requires: bash, curl, jq.

set -euo pipefail

: "${STRIPE_SECRET_KEY:?set STRIPE_SECRET_KEY (sk_test_… or sk_live_…) for this run — never commit it}"

API="https://api.stripe.com/v1"
VER="2025-03-31.basil"   # pin the same API version the server uses (StripeClient.ApiVersion)

# Small wrapper: authenticated, version-pinned form POST to the Stripe API.
stripe_post() {
  local path="$1"; shift
  curl -sS --fail-with-body \
    -u "${STRIPE_SECRET_KEY}:" \
    -H "Stripe-Version: ${VER}" \
    "${API}/${path}" "$@"
}
stripe_get() {
  local path="$1"; shift
  curl -sS --fail-with-body \
    -u "${STRIPE_SECRET_KEY}:" \
    -H "Stripe-Version: ${VER}" \
    -G "${API}/${path}" "$@"
}

echo "==> Product (lookup by name; created if absent)"
PRODUCT_ID="$(stripe_post products \
  -d "name=WifiHaven" \
  -d "description=WifiHaven household network management" | jq -r '.id')"
echo "    product: ${PRODUCT_ID}"

# Prices are idempotent by lookup_key: if one already exists, reuse it.
ensure_price() {
  local lookup_key="$1" amount="$2" interval="$3"
  local existing
  existing="$(stripe_get prices -d "lookup_keys[]=${lookup_key}" -d "limit=1" | jq -r '.data[0].id // empty')"
  if [ -n "${existing}" ]; then
    echo "${existing}"
    return
  fi
  stripe_post prices \
    -d "product=${PRODUCT_ID}" \
    -d "currency=usd" \
    -d "unit_amount=${amount}" \
    -d "recurring[interval]=${interval}" \
    -d "lookup_key=${lookup_key}" \
    -d "transfer_lookup_key=true" | jq -r '.id'
}

echo "==> Prices"
PRICE_MONTHLY="$(ensure_price wifihaven_monthly 1000 month)"   # $10.00
PRICE_ANNUAL="$(ensure_price wifihaven_annual 9600 year)"      # $96.00
echo "    monthly: ${PRICE_MONTHLY}"
echo "    annual:  ${PRICE_ANNUAL}"

echo "==> Founding promo code (created only if 'FOUNDING' does not already exist)"
EXISTING_PROMO="$(stripe_get promotion_codes -d "code=FOUNDING" -d "limit=1" | jq -r '.data[0].id // empty')"
if [ -n "${EXISTING_PROMO}" ]; then
  PROMO_ID="${EXISTING_PROMO}"
  echo "    reused: ${PROMO_ID}"
else
  # percent_off + duration=forever is NOT restricted by the basil coupon-duration change (that only
  # affects amount_off + forever). See header.
  COUPON_ID="$(stripe_post coupons \
    -d "percent_off=40" \
    -d "duration=forever" \
    -d "name=Founding household (40% forever)" | jq -r '.id')"
  PROMO_ID="$(stripe_post promotion_codes \
    -d "coupon=${COUPON_ID}" \
    -d "code=FOUNDING" \
    -d "restrictions[first_time_transaction]=true" | jq -r '.id')"
  echo "    coupon: ${COUPON_ID}"
  echo "    promo:  ${PROMO_ID}"
fi

cat <<EOF

==> Done. Set these on the deployment (Render env / application.conf stripe {}):

  WIFIHAVEN_STRIPE_PRICE_MONTHLY=${PRICE_MONTHLY}
  WIFIHAVEN_STRIPE_PRICE_ANNUAL=${PRICE_ANNUAL}
  WIFIHAVEN_STRIPE_FOUNDING_PROMO=${PROMO_ID}

Also set (out of band, never committed):
  WIFIHAVEN_STRIPE_SECRET_KEY=<the sk_… key used for this run>
  WIFIHAVEN_STRIPE_WEBHOOK_SECRET=<whsec_… from the Stripe webhook endpoint you create for
                                   POST https://api.<env>.wifihaven.net/api/billing/webhook>
EOF
