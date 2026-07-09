# Design — multi-tenant launch (Phase 5: beta, enrollment, billing, marketing)

Status: **accepted / in execution.** The decisions in this doc are locked —
business decisions in [`pricing-analysis.md`](pricing-analysis.md) (#2117,
merged PR [#2118](https://github.com/wifihaven/wifihaven/pull/2118)), scope
decomposition in the Phase-5 execution-plan comment on
[#622](https://github.com/wifihaven/wifihaven/issues/622) (2026-07-08) and its
P5-8 addition (2026-07-09), plus the operator's beta-gating decision
(2026-07-08: request-access with manual approval, no open signup). This doc
**consolidates** those decisions into one reviewable design; it does not open
them. Execution is already underway — live status in [§8](#8-execution-map).

> **Scope of this doc.** Phase 5 of the multi-tenant epic
> [#622](https://github.com/wifihaven/wifihaven/issues/622): the **product
> surfaces** that let a second (and Nth) household actually enroll and pay —
> beta gating, the request→approval→provisioning pipeline, household-aware
> login, Stripe billing, entitlements, and the marketing surface. It sits
> directly on the isolation substrate designed in
> [`multi-tenant-isolation.md`](multi-tenant-isolation.md) (#2085, waves A–F =
> #2104–#2109, all merged 2026-07-06→08) and assumes its invariant holds.
> Sub-issues: [#2130](https://github.com/wifihaven/wifihaven/issues/2130),
> [#2131](https://github.com/wifihaven/wifihaven/issues/2131)–[#2135](https://github.com/wifihaven/wifihaven/issues/2135),
> [#2137](https://github.com/wifihaven/wifihaven/issues/2137),
> [#2138](https://github.com/wifihaven/wifihaven/issues/2138),
> [#2140](https://github.com/wifihaven/wifihaven/issues/2140).

---

## 0. Relationship to the isolation substrate

The isolation design ([`multi-tenant-isolation.md`](multi-tenant-isolation.md))
deliberately deferred the product layer. Its §9 non-goals said:

> *Tenant-enrollment UI, billing page, Stripe integration … are product
> surfaces layered on top of a **correct** isolation substrate; building them
> first would bake in leaks. Deferred to their own issues once the §7
> invariant holds.*

That precondition is now met: the invariant — *a household-A principal must
never read or write a household-B row* — is CI-pinned by
`MultiTenantIsolationSpec` (landed with
[#2108](https://github.com/wifihaven/wifihaven/issues/2108), PR
[#2127](https://github.com/wifihaven/wifihaven/pull/2127); extended by every
subsequent PR that touches a scoped surface, e.g.
[#2139](https://github.com/wifihaven/wifihaven/pull/2139)). Phase 5 picks up
exactly the deferred items:

**In scope (this phase):**

- Beta gating + the request→approval→provisioning pipeline (§2–§3)
- Household-aware login: per-household usernames + login slug (§4)
- Billing: `household_billing` state machine + minimal Stripe surface (§5)
- Entitlements: per-household `router_cap` (§6)
- Marketing surface: request-access CTA + pricing section (§7)

**Still out of scope (unchanged non-goals, carried from isolation §9 and
pricing §6/§7):**

- **Custom per-household domains** — v1 stays one shared apex
  (`app.wifihaven.net`); edge globals stay per-deployment
  ([`custom-domain-edge-config.md`](custom-domain-edge-config.md), #2109).
- **Tiers beyond `router_cap`** — retention-length and alerting entitlements
  are *reserved axes*, not built (pricing §6); the public multi-home tier is
  priced later, when demand shows (pricing §1/§6).
- **Cross-household admin** — with exactly one deliberate, narrow exception:
  the operator gate over `beta_requests` (§3.2). No principal reads another
  household's *data* in v1.
- **Multi-instance fan-out** ([#1952](https://github.com/wifihaven/wifihaven/issues/1952))
  — a scaling gate at ~100 households (pricing §4.3), not a launch item.
- Subscription schedules, metered billing, per-seat quantities, multi-currency
  / Stripe Tax, lifetime SKUs (pricing §7, "explicitly not needed").

None of this phase touches the router wire. The router never learns the words
"household", "beta", or "billing" — the wire-invisibility argument of
isolation §6 carries through unchanged, and §5.3's *never-brick* rule makes it
load-bearing.

---

## 1. Locked business inputs (from pricing-analysis.md)

The pricing analysis (#2117) fixed these; Phase-5 code treats them as
constants of the design, and the marketing copy must match them verbatim (§7):

| Decision | Value | Source |
|---|---|---|
| Unit of purchase | the **household** (one subscription per home) | pricing §6 |
| Launch price | **$10/mo or $96/yr** ($8/mo effective), single public price, unlimited profiles/devices, 1 router | pricing §1 |
| Beta | **4 months free**, cohort-wide flip date printed at signup; **no card during beta** | pricing §5 |
| Founding discount | **$6/mo (or $57/yr) for as long as they stay subscribed** — Stripe coupon 40% off, `duration=forever` | pricing §1/§5.5 |
| Self-hosted | **free forever, stated out loud** | pricing §1/§3 |
| Stripe surface | 1 Product + 2 Prices + 1 forever-coupon + Checkout + Portal + webhook — nothing else | pricing §7 |
| Non-conversion / dunning | read-only **grace**, never brick the network | pricing §5.4 |
| Multi-router | capability ships (internal/founding only); public plan says 1 router; multi-home tier later at ~1.5–2× | pricing §1/§6 |
| Entitlements | resolve **per household**, never global constants | pricing §7 |

Plus one operator decision made after the pricing doc merged (2026-07-08,
recorded in the #622 plan comment): **beta access is request-based with manual
operator approval — no open self-serve signup.** And a second (2026-07-09,
P5-8 comment on #622): **the same username must work in different households**,
so the login page gains a household field (§4).

---

## 2. Beta model

Per pricing §5, consolidated with the request-gating decision:

1. **Request-gated intake, manual approval.** There is no self-serve signup.
   A prospective household submits a request (email, name, note) from the
   marketing site / SPA `/beta` form; the operator reviews and approves or
   rejects each one by hand (#2132). This caps cohort size deliberately —
   pricing §4.4 sizes the beta at 25–50 households and warns: *cap the beta at
   what one API instance + one PG tier step carries — do not let beta size
   force the #1952 step.*
2. **The deal is stated at signup**: free during beta, through the printed
   cohort-wide flip date; founding households keep $6/mo (40% off) for as long
   as they stay subscribed (pricing §5.1). Nobody is surprised at the flip.
3. **No card during beta.** A Stripe Customer is created at provisioning,
   nothing else — no subscription, no trial objects, no card (pricing §5.2,
   §7 "Beta period: **Nothing.**"). The conversion moment is the natural
   card-collection moment.
4. **T−30 / T−7 notices + in-SPA banner from T−30**, each carrying a one-click
   Checkout link with the founding promotion pre-applied (pricing §5.3; built
   in #2137, §5.4 below).
5. **Grandfathering = discount-for-life, not free-for-life** (pricing §5.5).
   The founding 40%-forever discount converts goodwill into revenue without
   training the next cohort to wait.
6. **Conversion expectation**: 40–70% for a hand-recruited cohort with a
   founding discount; below 40% is a pricing/value red flag to investigate,
   not a reason to discount deeper (pricing §5, final paragraph).

---

## 3. Beta-request → approval → provisioning state machine

The pipeline (#2131 schema, #2132 API, #2133 SPA) is the only way a second
household comes into existence.

### 3.1 `beta_requests` (V66, #2131)

A dedicated table — deliberately named `beta_requests`, **not** "access
requests", because `AccessRequest` is an existing block-page domain concept
(`AccessRequestKind` in `shared/src/Models.scala`; #2131 scope item 1):

```
pending ──approve──► approved   (decided_at, decided_by, invite_token_hash,
   │                             invite_expires_at, household_id stamped)
   └─────reject────► rejected   (decided_at, decided_by)
```

Columns per #2131: `email` (unique), `name`, `note`, `status`
(`pending`/`approved`/`rejected` CHECK), timestamps, `decided_by → users(id)`,
`invite_token_hash` (unique), `invite_expires_at`, and a nullable
`household_id` set at provisioning.

**Intake** is `POST /api/beta/request` — unauthenticated, rate-limited
(reusing the [#2079](https://github.com/wifihaven/wifihaven/issues/2079)
rate-limit machinery), and idempotent on duplicate email with a 200 that leaks
no enumeration signal (#2132 scope item 1).

### 3.2 The operator gate — the one narrow isolation exception

Review endpoints (`GET /api/operator/beta-requests`,
`POST …/{id}/approve|reject`) are restricted to **admins of household 1** —
the deployment operator's household — via a `requireOperator` guard
(`admin && claims.hh == HouseholdId.Default`; #2132 scope item 2).

This is a **deliberate, narrow exception** to isolation §9's "no
cross-household admin" non-goal, and it is scoped tightly: the operator
surface reads **only** `beta_requests` rows — which belong to no household
until approval — never another household's data. It is documented as
v1-pragmatic; a real ops console remains a future, deliberately-privileged
surface (isolation §9).

### 3.3 Provisioning on approve (#2132)

Approval provisions the household atomically:

1. Create the `households` row (name from the request) — the first
   `HouseholdRepo` (create/find); none exists yet (#2132, final note).
2. Assign the household's unique **slug** (from the requested name,
   de-duplicated; `households.slug`, #2131 scope addition) — consumed at
   login (§4) and carried on the invite URL/accept response so the SPA can
   set the `wh_household` cookie (#2132 scope addition).
3. Create the `household_billing` row: `status='beta'`, `founding=true`
   (§5.1). Stripe Customer creation is #2135's seam — provisioning leaves the
   hook, the billing PR fills it.
4. Mint a **single-use invite token** — hash stored, TTL ~7 days, following
   the enrollment-token conventions
   ([`docs/process/security.md`](../process/security.md), #2083).
5. Stamp `beta_requests.household_id`.
6. **Notify via the `Notifier` pattern**
   ([`api/src/notify/Notifier.scala:12`](../../api/src/notify/Notifier.scala))
   — one method, `.live` logs. **No email transport is invented**
   ([`docs/design/alerting.md`](alerting.md) §4 "no invented transports";
   `memory` rule of the same name): the approve response returns the invite
   URL and the operator sends it manually in v1.

### 3.4 Invite accept → first admin user (#2132/#2133)

`POST /api/beta/accept` `{token, username, password}` validates the token
(single-use, unexpired — Clock-injected so TestClock drives TTL tests),
creates the household's **first admin user** (password policy per
[#2084](https://github.com/wifihaven/wifihaven/issues/2084)), and invalidates
the token. This is the second-household admin bootstrap that does not exist
today — the only admin-creation path before this is the V1 seed
(`V1__init.sql:129-131`, per #2132 scope item 4).

The SPA side (#2133): `/beta` (request form) and `/welcome?token=…` (accept →
auto-login into the new household's empty dashboard) join today's only two
unauthenticated routes, `/login` and `/blocked`
([`web/src/App.tsx:44-45`](../../web/src/App.tsx)); the operator queue page is
gated on the §3.2 operator check; and the first-run empty-household experience
points at router enrollment as the next step. `/welcome` sets the
`wh_household` cookie (slug) **before** auto-login, so a new household's
members never see the household field (§4; #2133 scope addition).

**Isolation pins (merge-gating, #2132 scope item 5):** a newly provisioned
household-B admin sees zero household-A rows across every listing; a hh-B
admin cannot read or approve `beta_requests`; an accepted invite cannot be
replayed.

---

## 4. Household-aware login (#2140)

**Why it exists — a latent bug, not just UX.** V65 already relaxed
`users.username` from global-unique to `UNIQUE(household_id, username)`
([`V65__households.sql:83`](../../api/resources/db/migration/V65__households.sql)).
The moment two households share a username, every `findByUsername(u)` lookup
is ambiguous. Verified call sites (2026-07-08, current `main`):

- login — [`AuthService.scala:93`](../../api/src/auth/AuthService.scala)
- verify / token_version — [`AuthService.scala:172`](../../api/src/auth/AuthService.scala)
- password change — [`AuthService.scala:212`](../../api/src/auth/AuthService.scala), [`:223`](../../api/src/auth/AuthService.scala)
- enrollment household lookup — [`RouterRoutes.scala:226`](../../api/src/routes/RouterRoutes.scala)

Authenticated paths already carry the household (`claims.hh`,
[#2105](https://github.com/wifihaven/wifihaven/issues/2105)) and simply
resolve `(claims.hh, claims.sub)`. **Login is the one path with no household
in hand** — and the username can't supply it. Hence:

1. `findByUsername` becomes `findByUsername(hh, username)`, keyed on the V65
   unique constraint; all five call sites updated (#2140 scope item 1).
2. `POST /api/auth/login` accepts an optional `household` field carrying the
   **slug** (`households.slug` — unique and human-facing; `name` is not
   unique). **Absent/empty resolves to the default household** (slug
   `default`), so self-hosted single-household deploys keep exactly today's
   UX and existing API clients keep working — additive, back-compat (#2140
   scope item 2).
3. Wrong-household failure is indistinguishable from wrong-password — no
   household/username enumeration (#2140 scope item 3).
4. SPA (`web/src/pages/LoginPage.tsx`): household field pre-filled from a
   long-lived `wh_household` cookie (`Max-Age` ~10 years), collapsed behind a
   "different household?" affordance when present; (re)set on every
   successful login and at invite-accept. **The cookie is a UX hint only —
   never an auth input**; the server trusts the posted field + password, not
   the cookie (#2140 scope items 4–5).

Sequencing: #2140 merges **before** #2133, whose invite-accept flow creates
the first potentially-colliding usernames (#2140 / #2133 scope addition).

---

## 5. Billing (#2131 schema, #2135 Stripe, #2137 lifecycle)

### 5.1 `household_billing` — one status machine for everything

One row per household (V66, #2131 scope item 2): `household_id` PK,
`stripe_customer_id` / `stripe_subscription_id` / `price_id`, `founding`
boolean, `current_period_end`, and the load-bearing column:

```
status: 'beta' → 'active'                    (checkout.session.completed)
        'beta' → 'grace'  → 'locked'         (non-conversion at flip, #2137)
        'active' → 'grace' → 'locked'        (dunning lapse, #2135 webhook)
        'grace' → 'active'                   (recovery via Checkout/Portal)
```

**There is deliberately ONE state machine** serving both the non-conversion
flip and the dunning lapse (pricing §7 "one state machine serves both paths";
[`docs/process/single-source-of-truth.md`](../process/single-source-of-truth.md)).
#2135 drives the `active`-side transitions from the webhook; #2137 drives the
`beta`-side flip from the cohort date. Neither invents its own states.

### 5.2 Minimal Stripe surface (#2135, from pricing §7)

| Need | Primitive |
|---|---|
| Two prices ($10/mo, $96/yr) | 1 Product + 2 recurring Prices |
| Founding discount | 1 Coupon `percent_off=40, duration=forever` in Promotion Code `FOUNDING`, `restrictions[first_time_transaction]=true` |
| Beta period | **nothing** — Customer at provisioning only |
| Conversion | Checkout Session (`mode=subscription`, promo pre-applied for founding); Customer Portal for cancel/card-update |
| Dunning | Stripe Smart Retries + signature-verified webhook → the §5.1 machine |

Endpoints: authenticated `POST /api/billing/checkout`,
`GET /api/billing/portal`, and `POST /api/billing/webhook`
(signature-verified; secret via env/config, never in repo —
[`docs/process/security.md`](../process/security.md)). Webhook transitions
(#2135 scope item 4): `checkout.session.completed` → `active` (+ store
subscription/price ids); terminal `invoice.payment_failed` /
`customer.subscription.deleted` → `grace`. A minimal SPA billing page shows
plan/status + portal + checkout entry, founding price surfaced.

**Pre-build verification required** (pricing §7 caveat, restated in #2135):
Stripe's 2025-03-31 "basil" changelog appears to restrict coupons without an
end time on newer API versions — `duration=forever` must be confirmed on the
pinned API version before building, with the finding cited in the PR.

**Explicitly not built** (#2135 scope item 6 = pricing §7): subscription
schedules, metered billing, per-seat, multi-currency/Tax, lifetime SKUs.

Instrumentation per
[`docs/process/instrumentation.md`](../process/instrumentation.md): webhook
events counter (bounded event-type label) + checkout outcomes, Grafana panel
in the same PR; #2137 adds a households-by-billing-status gauge + flip-notice
counter.

### 5.3 The never-brick-the-network rule

From pricing §5.4, the strongest constraint in this phase:

> *Never brick the network at the flip — that's the Plex mistake with worse
> stakes (it's the family's internet).*

Concretely (#2137 scope items 4–5):

- **Router-wire routes are NEVER gated on billing status.** The policy
  snapshot always serves — to `beta`, `active`, `grace`, and `locked`
  households alike. Enforcement on the family's network keeps working
  regardless of payment state.
- `grace` (30 days) and `locked` gate only the **SPA-facing mutating routes**,
  via a single middleware/guard keyed off `household_billing.status`: cloud
  dashboards/history/edits go read-only; in `locked`, the snapshot still
  serves and history is retained per the retention windows only.

### 5.4 Flip lifecycle (#2137)

- **Cohort flip date** is a config value (env/HOCON via zio-config) — one
  cohort-wide date, not per-household in v1; printed at every signup surface
  ("free through *date*").
- **T−30 / T−7 notices**: a scheduled in-process job (Clock-injected, modeled
  on `RetentionSweepJob`) emits per-household notices for unconverted
  (`status='beta'`) households through the `Notifier` one-method pattern —
  `.live` logs a line carrying everything an email needs, so a future
  transport ([#874](https://github.com/wifihaven/wifihaven/issues/874)) drops
  in without call-site reshaping. The operator sends actual emails manually
  in v1 (no invented transport, as §3.3).
- **In-SPA banner from T−30** for unconverted households: dismissible per
  session, flip date + one-click Checkout with the founding promo pre-applied.
- **Feature tests**: TestClock walks a household beta → T−30 notice → flip →
  grace (writes 403, reads OK, **router snapshot OK**) → locked; converted
  households untouched (#2137 scope item 6).

---

## 6. Entitlements (#2134)

The first entitlement is the **router cap**. Pricing §7's forward-compat
requirement, verbatim: entitlements *"resolve per household from the
subscription's Price/Product, not from a global constant."*

- `households.router_cap INT NOT NULL DEFAULT 1` (V66, #2131 scope item 3).
  Household 1 is backfilled to a higher cap (e.g. 10) — the operator
  household runs multiple routers (pricing §1: multi-router ships
  internal-only at launch).
- `POST /api/admin/routers`
  ([`RouterRoutes.scala:215`](../../api/src/routes/RouterRoutes.scala))
  counts the household's existing routers and rejects creation past the cap
  with a clear "your plan includes N router(s)" error. No cap enforcement
  exists today — the 2026-07-08 survey found zero `MaxRouters`-style
  constants (#2134).
- A small `Entitlements` accessor (household → caps) is the seam: the later
  multi-home tier becomes "add a second Price + raise the cap for households
  on it" (pricing §7), and the reserved future axes — retention length (the
  per-deploy horizon constants at
  [`RetentionSweepJob.scala:56-58`](../../api/src/usage/RetentionSweepJob.scala))
  and alerting — plug into the same lookup **later**. Per pricing §6 they are
  reserved, **not built** now.
- No code special-cases household 1 or founding households — their higher cap
  is just the column value (#2134).
- Isolation pin: hh-B's router count never affects hh-A's cap check.

---

## 7. Marketing surface (#2138)

`web-marketing/site/index.html` — static, dependency-free, no build step,
deployed to Cloudflare Pages `wifihaven-www` by
[`.github/workflows/master-marketing.yml`](../../.github/workflows/master-marketing.yml).

1. **"Request beta access" replaces "Open the app →" as primary CTA**, linking
   out to the SPA's `/beta` form at `app.wifihaven.net/beta` (#2133). The
   link-out is deliberate: POSTing directly from `wifihaven.net` would require
   adding that origin to the API's CORS `allowedOrigins`
   ([`Config.scala:120`](../../api/src/Config.scala),
   [`Cors.scala`](../../api/src/Cors.scala)) — **do not widen CORS without
   need** (#2138 scope item 1; the edge globals stay per-deployment per
   isolation §2 gap 5).
2. **Pricing section copy must match the locked §1 decisions verbatim**
   (#2138 scope item 2): free during beta through the printed cohort flip
   date (no open-ended free); $10/month or $96/year per household after,
   unlimited profiles + devices, 1 router; founding households $6/mo (or
   $57/yr) for as long as they stay subscribed; **self-hosted free forever
   with the GitHub repo linked as the trust signal** (the Nabu Casa/Tailscale
   playbook, pricing §3 — a headline, not a footnote); no card required
   during beta.
3. Beta-expectation copy: requests reviewed manually; invite link on approval.
4. Copy can land dark behind the #2132 endpoint; verify against the live
   deploy after merge.

---

## 8. Execution map

From the #622 plan comment (waves) + P5-8 addition; **live status verified
2026-07-08** via `gh issue view` / `gh pr list` (no PR exists yet for #2131 —
the `fix/2131-v66-phase5-schema` branch is not on the remote).

| Wave | Issue | Unit | Depends on | Status (2026-07-08) |
|---|---|---|---|---|
| W1 | [#2130](https://github.com/wifihaven/wifihaven/issues/2130) | fix: `POST /api/users` wrote into household 1 regardless of caller | — | **MERGED** — PR [#2139](https://github.com/wifihaven/wifihaven/pull/2139) |
| W1 | [#2131](https://github.com/wifihaven/wifihaven/issues/2131) | V66 schema-only: `beta_requests` + `household_billing` + `router_cap` + `slug` + V65 `DROP DEFAULT`s | #2130 | OPEN, next up |
| W2 | [#2132](https://github.com/wifihaven/wifihaven/issues/2132) | beta intake + operator approval + provisioning + invite accept (API) | #2131 | OPEN |
| W2 | [#2134](https://github.com/wifihaven/wifihaven/issues/2134) | per-household `router_cap` enforcement | #2131 | OPEN |
| W2 | [#2140](https://github.com/wifihaven/wifihaven/issues/2140) | household-aware login (slug + cookie) | #2131; before #2133 | OPEN |
| W3 | [#2133](https://github.com/wifihaven/wifihaven/issues/2133) | SPA: `/beta`, `/welcome`, operator queue | #2132, #2140 | OPEN |
| W3 | [#2135](https://github.com/wifihaven/wifihaven/issues/2135) | Stripe: customer, Checkout + Portal, webhook, FOUNDING | #2131, #2132 | OPEN |
| W3 | [#2138](https://github.com/wifihaven/wifihaven/issues/2138) | marketing: CTA + pricing section | #2132 (endpoint; copy can land dark) | OPEN |
| W4 | [#2137](https://github.com/wifihaven/wifihaven/issues/2137) | beta-flip lifecycle: date, notices, banner, grace | #2135 | OPEN |

The W1 serialization is load-bearing: V66's `DROP DEFAULT` on the V65
`household_id DEFAULT 1` columns
([`V65__households.sql:70-76`](../../api/resources/db/migration/V65__households.sql))
is only safe once every `INSERT` stamps the household explicitly — PR #2139's
body carries the full seven-table audit proving exactly that, and #2131 must
still re-verify per table and drop only where proven (the unconditional
migration-isolation gate, [#2098](https://github.com/wifihaven/wifihaven/issues/2098),
is the proof).

Process per the plan comment: one do-er session per unit, monitored to
MERGED before dependents release; **the operator merges everything**.

---

## 9. Isolation invariant carry-through

Every new Phase-5 surface extends `MultiTenantIsolationSpec` (merge-gating,
per isolation §7 — embedded PG, two real households, no repo mocks, Clock
injected):

| Surface | Pin (from the issue's own test scope) |
|---|---|
| user creation (#2130) | hh-B admin `POST /api/users` → row stamped hh-B, invisible to hh-A — **landed** (PR #2139, red-first) |
| provisioning (#2132) | new hh-B admin sees zero hh-A rows across every listing; hh-B admin cannot read/approve `beta_requests`; invite not replayable |
| login (#2140) | same username in two households: each slug logs into its own household; wrong slug + right password fails like a bad password; absent slug → default household; verify/password-change resolve within `claims.hh` |
| router cap (#2134) | hh-B's router count never affects hh-A's cap check |
| billing lifecycle (#2137) | grace/locked gates only the household's own SPA mutations; the router snapshot serves regardless of billing status |

The billing/webhook and marketing surfaces add no new cross-household read
paths (webhook resolves a household by its own `stripe_customer_id`; the
marketing page is static), but #2135's feature tests still run through the
full stack with real repos, mocking only the external Stripe I/O
([`docs/process/testing.md`](../process/testing.md)).

---

## 10. Open questions / flagged tensions

Conflicts are flagged here, not silently resolved (none is a blocker for the
current waves):

1. **`household_billing.founding DEFAULT TRUE` vs. post-beta provisioning.**
   #2131 defaults `founding` to true, which is correct while the *only*
   intake is the beta pipeline — but no issue defines the post-beta public
   signup path (the beta-request pipeline is the only enrollment mechanism
   designed). When the beta closes, either provisioning must set
   `founding=false` explicitly or the default must flip. Deferred until the
   flip nears; noting it so it isn't discovered at flip time.
2. **Cohort flip date is stated in two places.** #2137 makes it an env/HOCON
   config value; #2138 prints it in static marketing HTML. That is a
   single-source-of-truth tension
   ([`docs/process/single-source-of-truth.md`](../process/single-source-of-truth.md))
   accepted for v1 (a static page cannot read config); the #2138 copy must be
   updated by hand if the date ever changes. Flagged as ACCEPT-class
   proximity, not collapsible without adding a build step to the static page.
3. **Stripe `duration=forever` on the pinned API version** — unverified
   (pricing §7 caveat / §8 honesty ledger). #2135 carries the mandatory
   pre-build verification; if basil-era versions reject open-ended coupons,
   the founding mechanism needs a fallback (e.g. pinning an older API
   version, or a repeating-duration coupon with the longest allowed horizon —
   **a decision to bring back to the operator, not to make silently**).
4. **Citation drift in #2138**: the issue cites the CORS surfaces at
   `Config.scala:101,:165`; on current `main` they are at
   [`Config.scala:120`](../../api/src/Config.scala) (`CorsConfig.allowedOrigins`)
   and [`:135`](../../api/src/Config.scala) (`uiAllowedHosts`) /
   [`:184`](../../api/src/Config.scala) (WS `allowedOrigins`). Cosmetic —
   the referenced surfaces are the same; this doc's citations are the
   verified ones.

---

## 11. References

- Epic: [#622](https://github.com/wifihaven/wifihaven/issues/622) — Phase-5
  execution-plan comment (2026-07-08) + P5-8 addition (2026-07-09)
- Business decisions: [`pricing-analysis.md`](pricing-analysis.md)
  ([#2117](https://github.com/wifihaven/wifihaven/issues/2117))
- Substrate: [`multi-tenant-isolation.md`](multi-tenant-isolation.md)
  ([#2085](https://github.com/wifihaven/wifihaven/issues/2085), sub-issues
  #2104–#2109, all merged)
- Sub-issues: #2130 (fix, merged), #2131 (P5-1 schema), #2132 (P5-2 API),
  #2133 (P5-3 SPA), #2134 (P5-4 entitlement), #2135 (P5-5 Stripe),
  #2137 (P5-6 flip), #2138 (P5-7 marketing), #2140 (P5-8 login)
- Process: [`migrations.md`](../process/migrations.md),
  [`wire-contract.md`](../process/wire-contract.md),
  [`testing.md`](../process/testing.md),
  [`single-source-of-truth.md`](../process/single-source-of-truth.md),
  [`instrumentation.md`](../process/instrumentation.md),
  [`security.md`](../process/security.md)
- No-invented-transports: [`alerting.md`](alerting.md) §4
