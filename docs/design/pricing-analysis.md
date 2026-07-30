# Pricing analysis — multi-tenant launch (#2117)

Decision-ready pricing analysis for opening the WifiHaven cloud to outside
households (#622 / #2085 waves A–F). Research date: **2026-07-06** — all
competitor prices were fetched from vendor pricing pages on that date unless
marked *unverified*. Infra numbers come from `render.yaml`, live read-only
queries against `wifihaven-pg-prod`, and vendor pricing pages.

Related: #622 (multi-tenant + Stripe billing), #2085 (isolation waves),
#1171 (marketing site), #1952 (multi-instance gate).

---

## 1. Executive summary + recommendation

**Recommendation (primary):**

| Decision | Call |
|---|---|
| Beta length | **4 months**, cohort-wide flip date announced at signup ("free through *date*") |
| Launch price | **$10/month or $96/year** (= $8/mo effective, 20% annual discount), per household |
| Tiers | **Single public price at launch.** Unlimited profiles + devices, 1 router/location. No feature gates pre-PMF. **Multi-router support itself ships regardless** (our own household runs multiple routers; #2104 already models it) — it launches internal-only, and a public **multi-home tier** is the named first upsell (§6). |
| Beta conversion | Beta households get a **founding price of $6/mo (or $57/yr) for as long as they stay subscribed** — Stripe coupon `duration=forever`, 40% off |
| Self-hosted | **Free forever, explicitly.** The Nabu Casa / Tailscale playbook: self-hosted is the funnel and the trust signal; cloud sells convenience (no server to run, hosted upgrades, remote access, retention). |
| Stripe build (#622) | Checkout + customer portal + one price pair (monthly/annual) + one forever-coupon promotion code. **No subscription schedules, no metered billing, no per-seat logic.** |

**Why this lands where it does.** $10/mo sits exactly at the consumer
household band: eero Plus $9.99/mo (the closest comp — router-level,
per-household, hardware-attached), Bark Premium $14/mo ($99/yr), Aura Kids
$10/mo annual, Canopy $9.99/mo, Qustodio Complete ~$8.75/mo. It is deliberately
*above* the DNS-only utilities (NextDNS $1.99, AdGuard DNS $2.49, Control D
$3–6) because WifiHaven is not DNS filtering — it is connection-layer
enforcement with per-profile screen time, which is the per-device-suite job at
the network layer. And it is comfortably above the cost floor: marginal infra
cost is ≈ **$1–3/household/month** (§4), so contribution margin is ~70–90%.
Breakeven on today's ~$78/mo fixed stack is **~10 paying households** at $10,
**~14** at the $6 founding price (§4.4).

**Alternative (only one):** $7/mo or $70/yr, Nabu Casa–style (their price:
$6.50/$65). Pick this only if the launch cohort is expected to stay
early-adopter/self-hoster-adjacent for a long time and goodwill matters more
than margin. It halves contribution per household and makes the beta discount
awkward (there's little room under $7). The $10 primary keeps room to discount
down; you can't discount up.

---

## 2. Competitive landscape

All prices checked 2026-07-06 against the cited page. "Unit" is what you pay
per; effective monthly = annual/12.

### 2.1 Parental-control suites (per-device agents)

| Product | Monthly | Annual (eff. $/mo) | Unit | Free tier | Trial | Hardware | Source |
|---|---|---|---|---|---|---|---|
| Aura Kids | $13 | $120 ($10) | household, unlimited kids/devices | none | 14 days | none (Circle Home Plus box discontinued post-2021 acquisition) | [aura.com/parental-controls](https://www.aura.com/parental-controls) |
| Aura Family | $50 | $384 ($32) | household (5 adults, full ID-theft suite) | none | 14 days | none | same |
| Bark Premium | $14 | $99 ($8.25) | family, unlimited devices | none | free trial exists; length unverified | optional: Bark Home box $79 one-time; Bark Phone $240+ | [bark.us/pricing](https://www.bark.us/pricing/) |
| Bark Jr | $5 | $49 ($4.08) | family | none | unverified | same ecosystem | same |
| Qustodio Basic | — | $59.95 ($5.00) | 5 devices | yes — 1 device, basic | 30-day money-back | none | [qustodio.com/en/premium](https://www.qustodio.com/en/premium/) |
| Qustodio Complete | — | $104.95–109.95 ($8.75–9.16; exact list vs promo unverified) | unlimited devices | same | 30-day money-back | none | same |
| Canopy Family | — | $9.99/mo billed annually | 10 devices | none | 7 days | none | [canopy.us](https://canopy.us/) |
| Net Nanny 5-device | — | $79.99 list / $54.99 promo ($4.58–6.67) | 5 devices | none | unverified (sources conflict) | none | [netnanny.com/products](https://www.netnanny.com/products/) |
| Net Nanny 20-device | — | $129.99 list / $89.99 promo | 20 devices | none | unverified | none | same |

Notes: the suites cluster at **$5–14/mo, trending per-household/unlimited-device**
(Aura, Bark, Qustodio Complete) rather than per-device caps (Net Nanny, Canopy,
Qustodio Basic — the legacy shape). Every one of these is a per-device agent
install; the notable exception is Bark Home ($79 one-time router-side box) —
i.e. Bark monetizes network-level enforcement as cheap hardware, not as the
subscription. Circle, the original router-appliance player, was acquired by
Aura (Dec 2021) and its hardware wound down — the standalone
network-appliance-with-subscription category effectively folded into either
app suites (Aura) or hardware ecosystems (Firewalla, eero).

### 2.2 Network-level offerings

| Product | Monthly | Annual (eff. $/mo) | Unit | Free tier | Hardware | Enforcement | Source |
|---|---|---|---|---|---|---|---|
| NextDNS Pro | $1.99 | $19.90 ($1.66) | household, unlimited devices | 300k queries/mo, all features, fail-open after quota | none | DNS-only | [nextdns.io/pricing](https://nextdns.io/pricing) |
| Control D | $3 (Some) / $6 (Full) | $30 / $60 (17% off) | personal, per-device profiles | free public resolvers (no dashboard); 14-day trial | none | DNS-only | [controld.com/pricing](https://controld.com/pricing) |
| AdGuard DNS Personal | $2.49 + VAT | annual exists; exact figure unverified | ~20 devices, 10M req/mo (caps secondary-sourced) | free Starter plan | none | DNS-only | [adguard-dns.io/en/license.html](https://adguard-dns.io/en/license.html) (partial render) |
| AdGuard Home | free | — | self-hosted, unlimited | 100% free OSS | user-supplied host | DNS-only, self-hosted | [github.com/AdguardTeam/AdGuardHome](https://github.com/AdguardTeam/AdGuardHome) |
| Firewalla | none confirmed | — | hardware purchase | — | **is** the hardware: Purple SE $279, Gold SE $499, Gold Plus $609, Gold Pro $929 | firewall/router-level | [firewalla.com/collections/all](https://firewalla.com/collections/all) |
| eero Plus | $9.99 | $99.99 ($8.33) | household | none | requires eero mesh (from ~$55) | router/cloud hybrid | [eero.com/eero-plus](https://eero.com/eero-plus) |
| CleanBrowsing Basic | $8.99 | $75 ($6.25) | 25 devices / 3.75M req/mo | free public filtered resolvers | none | DNS-only | [cleanbrowsing.org/pricing](https://cleanbrowsing.org/pricing/) |

Notes: DNS-only utilities price at **$2–6/mo**; the moment a product is
household-positioned with real enforcement + family features, it jumps to the
**$9–14 band** (eero Plus, CleanBrowsing, Bark). Firewalla proves a
prosumer segment pays $280–930 up-front for router-level control with **no
subscription at all** — that segment is WifiHaven's *self-hosted* audience,
not its cloud audience.

### 2.3 Self-hosted-core + paid-cloud analogs

| Product | Cloud price | Free/self-hosted shape | Lesson | Source |
|---|---|---|---|---|
| Home Assistant Cloud (Nabu Casa) | $6.50/mo or $65/yr, 31-day trial | HA itself free forever, self-hosted | The canonical model: monetize only remote access / integrations / convenience; never gate core features. ~Zero community backlash ever. | [nabucasa.com/pricing](https://www.nabucasa.com/pricing) |
| Tailscale Personal | $0 (6 users, unlimited devices); Standard $8/user/mo for orgs | free tier IS the product for households | Generous personal free tier as funnel to paid B2B. Apr 2026: retired the $5 "Personal Plus" tier and folded its benefits into free — free tier got *wider*, not narrower. | [tailscale.com/pricing](https://tailscale.com/pricing) |
| Plex Pass | $6.99/mo, $69.99/yr; Lifetime $249.99 → **$749.99 (Jul 2026)** | server free; **remote streaming paywalled Apr 2025** (enforced Nov 2025) | The cautionary tale: converting a historically-free capability to paid produced sustained backlash + active bypass culture (Tailscale workarounds). Existing Lifetime holders were grandfathered — the carve-out is what kept it survivable. | [support.plex.tv](https://support.plex.tv/articles/requirements-for-remote-playback-of-personal-media/), [MacRumors](https://www.macrumors.com/2026/05/19/lifetime-plex-pass-price-increase/) |
| Bitwarden | Premium $1.65/mo (annual), Families $3.99/mo | server OSS + free tier | Same funnel shape as Nabu Casa; ultra-low price works because marginal cost ≈ 0 and volume is huge. | [bitwarden.com/pricing](https://bitwarden.com/pricing/) |
| Pi-hole | none — no official paid/cloud tier exists (donations only) | 100% free OSS | The unmonetized end-state. Third parties capture the hosting revenue instead. | [pi-hole.net](https://pi-hole.net/) |

---

## 3. Where WifiHaven sits

**Differentiators to price on:**

1. **Router-level connection-layer enforcement, no per-device agents.** Every
   suite in §2.1 requires installing and maintaining an agent on each child
   device (and loses to MDM removal, new devices, guest devices). WifiHaven
   enforces at the gateway via nftables forward-drop — coverage is the
   network, not the install base. The DNS-only products (§2.2) are trivially
   bypassed by DoH/hard-coded IPs; WifiHaven's `blockIpOnly` + encrypted-DNS
   blocking (#1911) closes exactly that hole. Only Firewalla enforces at the
   same layer, and it costs $279–929 in hardware.
2. **Whole-home per-profile screen time** — schedules, daily limits, per-app
   limits — evaluated server-side across all of a child's devices. The suites
   do this per-device; the DNS products don't do it at all.
3. **No hardware sale.** BYO OpenWRT router. This caps the initial addressable
   market to households with (or willing to get) an OpenWRT-capable router —
   a technical-leaning early market — but it also means zero COGS, no
   inventory, and no $279 purchase gate.

**The real competitor for the cloud tier is self-hosting WifiHaven for free.**
That is fine — it's the Nabu Casa position, and it should be embraced rather
than fought:

- **Self-hosted stays free forever, stated out loud** (marketing site, #1171).
  Every attempt to claw back a free capability in this space (Plex, §2.3)
  generated backlash and bypass culture; every generous-free-core product
  (Tailscale, Home Assistant, Bitwarden) grew on the trust. The self-hosting
  population is also the review-writing, HN-posting population that makes a
  BYO-router product credible at all.
- **What the cloud tier actually sells** (real convenience deltas, not gated
  features): nothing to run or upgrade, managed Postgres + retention, access
  from anywhere without exposing a home server, hosted dashboards/alerting,
  multi-account households, and (later) multi-location. This is exactly the
  Nabu Casa bundle, and Nabu Casa charges $6.50 for it. WifiHaven's bundle is
  bigger (the whole control plane + history, vs. a relay), which supports
  sitting above Nabu Casa at $10.
  > Corrected 2026-07-28: this originally read "multi-**admin** households".
  > A household now has exactly one `admin`
  > ([#2512](https://github.com/wifihaven/wifihaven/issues/2512), enforced by
  > `V86`); the capability being sold is several *logins* per household —
  > second parent as `adult`, kids as `child` — not several admins. The
  > pricing conclusion is unaffected.

---

## 4. Cost: current spend + scaling curve

### 4.1 Current actual spend (single-household status quo)

Plans from [`render.yaml`](../../render.yaml); prices from Render/vendor pages
checked 2026-07-06. Render's pricing page is JS-rendered and would not render
for direct fetch, so per-plan dollar figures marked † are corroborated from
Render docs + secondary aggregators, not the primary pricing table — verify
against the actual Render invoice before publishing numbers anywhere.

| Line item | Plan | $/mo |
|---|---|---|
| wifihaven-api-prod | Render web, `standard` | $25 (cited in render.yaml #786 note) |
| wifihaven-api-staging | Render web, `standard` (#1989) | $25 |
| wifihaven-alloy | Render worker, `starter` | ~$7 † |
| wifihaven-pg-prod | Render Postgres `basic-1gb` | ~$19–20 † + storage $0.30/GB/mo ([render.com/docs/postgresql-refresh](https://render.com/docs/postgresql-refresh), confirmed) ≈ $0.75 at today's 2.4 GB |
| wifihaven-pg-staging | free | $0 |
| Cloudflare Pages ×2 + DNS | free tier | $0 |
| Grafana Cloud | free tier (10k series, 50 GB logs/mo) | $0 |
| Domain (wifihaven.net) | — | ~$1 |
| **Total** | | **≈ $78/mo** (≈ $53/mo of it prod-serving; staging is fixed dev overhead) |

### 4.2 Per-household unit of growth (measured, prod, 2026-07-06)

Read-only queries against `wifihaven-pg-prod`. Our one household = 1 router,
8 profiles, **26 devices** (heavy; assume a typical customer household is
~10–15 devices, i.e. ~0.5× these numbers).

| Quantity | Measured | Basis |
|---|---|---|
| DB total size | 2,422 MB | `pg_database_size` |
| `traffic_reports` rows | 823k/week ≈ **118k rows/day** | count over last 7 days |
| `connection_events` rows | 83k/week ≈ 12k rows/day | count over last 7 days |
| Raw weekly partition size | ~250–290 MB/week | `pg_total_relation_size` on `traffic_reports_2026_2x` |
| Steady-state storage | **≈ 2.4 GB/household (heavy); ≈ 1–1.5 GB typical** | retention bounds growth: raw 30d, hourly 90d, daily 180d (`RetentionSweepJob.scala`), partition-drop #812 |

Storage does **not** grow unboundedly per household — retention makes it a
fixed plateau. Marginal storage cost ≈ 1.5 GB × $0.30 = **$0.45/household/mo**
(heavy: $0.75).

Grafana Cloud is near-flat per household: metric labels are bounded enums by
policy (no per-mac/per-domain labels — `AGENTS.md` instrumentation rule), so
series count doesn't scale with households; logs (INFO) scale with request
volume and have 50 GB/mo free headroom. Assumption: observability stays $0
through ~100 households, then Grafana Pro at $19/mo + log overage.

Bandwidth: router polling every 5 s + SPA traffic. Render includes 5 GB
(hobby) / 25 GB (Pro workspace, $25/mo) then $0.15/GB
([render.com/docs/new-workspace-plans](https://render.com/docs/new-workspace-plans),
confirmed). Assumption: ~1–3 GB egress/household/mo → $0.15–0.45/household at
scale. (Current workspace plan not verified from repo — check dashboard.)

### 4.3 Scaling curve — cost steps as tenants onboard

The binding constraint observed so far is **Postgres compute**: one heavy
household continuously pegged 0.1 vCPU on `basic-256mb` (#1251 note in
render.yaml) — call it **~0.05–0.1 vCPU per typical household** of ingest +
rollups + dashboards. The JVM API at `standard` (1 CPU/2 GB) serves one
household with large headroom; capacity per instance is **unmeasured** —
assumption below is 50–100 routers/instance (5 s polls are cheap ETag hits;
websocket push #1945 reduces even that). Treat every per-step household count
as an assumption to re-measure at ~5 and ~25 households.

| Scale | Stack changes needed | Est. total infra $/mo | $/household | Gates |
|---|---|---|---|---|
| **1 (today)** | — | $78 | $78 | — |
| **~10 households** | PG → next basic/pro tier (~0.5–1 vCPU more) +$30–75†; storage +$5; else unchanged | **~$115–160** | ~$12–16 | none — plan upgrades only |
| **~100 households** | PG → Pro 2–4 CPU (~$150–250†); API `standard`→`pro` (~$85†) — still **one** instance; Grafana Pro $19 + log overage ~$20; Render Pro workspace $25 + bandwidth ~$30 | **~$330–480** | ~$3.30–4.80 | approaching the single-instance ceiling — start #1952 here |
| **~1000 households** | API **multi-instance** — **BLOCKED on #1952** (cross-instance ws fan-out: RouterWsRegistry/SpaWsRegistry/SpaEventHub/trafficUsage aggregator are all in-memory single-process); 3–4× `pro` instances ~$300; PG Pro 8–16 CPU ~$500–900†; bandwidth ~1–3 TB $150–450; Grafana ~$100 | **~$1,100–1,900** | ~$1.10–1.90 | **#1952 is an engineering milestone, not a plan upgrade** — schedule it before the ~100→1000 leg, alongside its dependents (per-instance snapshot/push correctness) |

† per-tier Render prices unverified against the primary pricing table (see
§4.1 caveat); the *shape* of the curve (storage $0.30/GB confirmed, tier
families confirmed) is solid, exact step prices are ±30%.

The important economics: **$/household falls an order of magnitude
(~$12 → ~$1.50) between 10 and 1000 households**, because today's stack is
almost entirely fixed cost. Marginal cost at scale ≈ storage $0.45 + PG
compute ~$0.50–1 + bandwidth ~$0.30 ≈ **$1–3/household/mo** — which is the
floor the price must clear, and $10 clears it 3–10×.

### 4.4 Breakeven + cost of the free beta

At the recommended prices (Stripe fees from
[stripe.com pricing](https://stripe.com/pricing): 2.9% + 30¢ processing +
0.7% Billing → net ≈ $9.34 of $10; ≈ $5.48 of $6):

- **Breakeven on today's full $78/mo fixed stack:** ~10 households at $10/mo
  net; ~14 households at the $6 founding price. (Against the $53 prod-serving
  share: ~7 / ~10.)
- **Free-beta carry:** a 25-household beta cohort pushes the stack into the
  "~10 households" step (~$115–160/mo with typical-sized households) for 4
  months against $0 revenue → incremental beta cost ≈ **$150–330 above
  baseline** ((~$115–160 − $78) × 4). At a 60% conversion rate that cohort
  then yields 15 × $6 ≈ $90/mo net, recovering the carry in **2–4 months** —
  cheap for what it buys (load data at 10× scale, the §4.3 assumptions
  re-measured, testimonials). At 50 beta households, run the same math one
  step up (roughly double the carry); still fine, but cap the beta at what
  one API instance + one PG tier step carries — **do not** let beta size
  force the #1952 step.

### 4.5 Threading cost into price

$10/mo yields ~$9.34 net → contribution after marginal cost is **$6–8/household**
at every point on the curve past ~15 households. Revenue at the steps: 100
households ≈ $850–1,000/mo (mixed founding/standard) vs ~$400 cost; 1,000
households ≈ $9,000+/mo vs ~$1,500–2,000 cost. The price does not merely
cover the marginal household — it funds each step *before* the step is needed
(the ~100-household revenue pays for #1952's engineering window). A $5-ish
price (the alternative) would still clear the marginal floor but puts
breakeven at ~20+ households and halves the buffer that pays for scaling
steps; that's the main reason it's the alternative and not the primary.

---

## 5. Beta → paid plan

**Length: 4 months.** Inside the operator's 3–6 month frame; long enough to
cover a full school-term of screen-time usage (the retention proof point) and
two monthly billing cycles of load data, short enough that "free" doesn't
become the product's identity. Research on free-to-paid consumer products
(§2.3 + conversion literature) says the risk isn't beta length per se — it's
an *open-ended* free period with no flip date. Superhuman's early-access
lock-in and Plex's grandfathering both worked because terms were explicit
up-front; Plex's remote-streaming flip hurt because it *changed* terms users
believed were permanent.

**Mechanics:**

1. **Signup states the deal**: "Free during beta (through *date*). Founding
   households keep $6/mo (40% off) for as long as they stay subscribed."
   The flip date is cohort-wide and printed at signup — nobody is surprised.
2. **No card during beta.** Create a Stripe Customer at enrollment, nothing
   else. Collecting cards for a $0 period adds friction at the top of the
   funnel and PCI-adjacent surface with zero benefit; the conversion moment
   is the natural card-collection moment.
3. **T−30 and T−7 emails**, in-SPA banner from T−30, with a one-click
   Checkout link carrying the founding promotion code pre-applied.
4. **At flip**: households that don't convert drop to a read-only grace state
   (policy enforcement keeps working on the router — it's offline-tolerant by
   design — but cloud dashboards/history/edits lock) for 30 days, then the
   snapshot serves but history is retained per the retention windows only.
   Never brick the network at the flip — that's the Plex mistake with worse
   stakes (it's the family's internet).
5. **Grandfathering = discount-for-life, not free-for-life.** Free-for-life
   beta users train the next cohort to wait; a visible founding discount
   converts goodwill into revenue and testimonials. 40%-forever is at the
   generous end of the pattern (Superhuman locked *list* price; Nabu Casa did
   early-adopter pricing) — deliberate, because the first cohort of a
   BYO-OpenWRT product is doing real QA for us.

**Conversion expectation:** self-serve freemium benchmarks run 3–8%
(secondary-sourced), but a hand-recruited 25–50 household beta with a founding
discount is not freemium — expect 40–70%, and treat <40% as a pricing/value
red flag to investigate before public launch, not a reason to discount deeper.

---

## 6. Tier structure

**Launch with a single price. No tiers.** Rationale:

- Pre-PMF, every tier boundary is a guess that costs real engineering
  (entitlement checks, upgrade/downgrade paths, #622 billing surface ×N) and
  real positioning clarity. The suites that tier by device count (Net Nanny,
  Canopy, Qustodio Basic) read as legacy; the products WifiHaven emulates
  (Nabu Casa, eero Plus, Bark Premium, Aura Kids) are one-price-per-household.
- Device/profile caps specifically would *fight the architecture*: router-level
  enforcement covers whatever joins the network, and "unlimited devices" is
  the honest description of how it works. Gating it would manufacture a
  weakness the per-device competitors actually have.
- **Multi-router is a special case — the capability ships, the tier comes
  later.** Our own household needs multiple routers, so multi-router-per-
  household support gets built regardless of pricing (the households model
  #2104 already allows it; #2106 binds routers to a household, not 1:1).
  At launch it is **internal/founding-household only**: the public plan says
  "1 router," and the enrollment path caps public households there. That
  keeps the launch price simple while the capability matures on our own
  deployment. The public **multi-home tier** is then the first, already-named
  upsell — and a high-value one: the segment that owns a vacation home or
  runs multiple gateways/WAPs skews affluent and is exactly who pays $280–930
  for Firewalla hardware today. One policy surface spanning every location a
  kid's device can roam to is something no per-device suite or single-box
  product offers. Indicative shape when it opens: ~1.5–2× base (e.g.
  $15–20/mo for up to 3 routers), priced when real demand shows up — not
  built into #622's launch billing surface beyond keeping the price/entitlement
  lookup per-household rather than hardcoded.
  The **customer-facing** phrasing of this — what a support agent tells someone
  who asks "can I add another router?", and what to do for them today (manual
  cap bump; no self-serve upgrade) — lives in
  [`docs/support-faq.md` §Can I add another router?](../support-faq.md#can-i-add-another-router)
  (#2499). Keep the two in sync when the multi-home tier actually opens.
- The other future tier axes, reserved (not built) now — each is additive
  and none is needed at launch: **history retention length** (retention
  windows are per-deploy constants today, `RetentionSweepJob`; making them
  per-household is a clean paid axis), **alerting/notifications**,
  **API access**. Revisit tiers when either (a) multi-home demand is real
  or (b) a business/MSP segment shows up (Firewalla MSP, Control D
  per-endpoint pricing show that segment exists in adjacent products).

The only structural pricing decision to lock now: the unit is **the
household** (one subscription covers the home), matching both the domain
model (#2104/#2106: routers bind to households) and where the market has
converged.

---

## 7. Stripe primitives implied (feeds #622)

The recommendation deliberately requires the *smallest* Stripe surface:

| Need | Stripe primitive | Notes |
|---|---|---|
| Two prices (monthly $10 / annual $96) | 1 Product + 2 recurring Prices | standard |
| Founding-household discount | 1 Coupon, `percent_off=40`, **`duration=forever`**, wrapped in a Promotion Code (e.g. `FOUNDING`), `restrictions[first_time_transaction]=true` | Caveat from research: Stripe changelog 2025-03-31 ("basil") appears to restrict coupons without an end time on newer API versions — **verify `duration=forever` behavior on the pinned API version before building** ([docs.stripe.com/changelog/basil/2025-03-31/restrict-coupon-duration](https://docs.stripe.com/changelog/basil/2025-03-31/restrict-coupon-duration), title-only, unverified) |
| Beta period | **Nothing.** Stripe Customer at enrollment; subscription created only at conversion via Checkout | avoids: trials with `trial_end` bookkeeping, subscription schedules (10-phase limit, draft-invoice edge cases), card-upfront friction. The "flip" is a product-side state change + email, not a billing-object transition |
| Conversion checkout | Checkout Session (mode=subscription) with the promo pre-applied; customer portal for self-serve cancel/card-update | both are hosted Stripe surfaces — near-zero UI build |
| Dunning/lapse | Stripe Smart Retries + webhook → the same read-only grace state as non-conversion (§5.4) | one state machine serves both paths |
| Fees | 2.9% + 30¢ + 0.7% Billing (pay-as-you-go) — checked 2026-07-06, [stripe.com/pricing](https://stripe.com/pricing), [stripe.com/billing/pricing](https://stripe.com/billing/pricing) | at $10/mo → ~$0.66/charge; annual billing also cuts per-charge fixed fees 12× |

Explicitly **not** needed for launch (don't build): subscription schedules,
metered/usage-based billing, per-seat quantities, tax-inclusive multi-currency
pricing (launch US-only; enable Stripe Tax when non-US demand appears),
lifetime SKUs (Plex's $249→$749 lifetime whiplash is the argument against).

One forward-compatibility requirement from §6: entitlements (router cap, and
later retention/alerting) resolve **per household from the subscription's
Price/Product**, not from a global constant — so the later multi-home tier is
"add a second Price + raise the cap for households on it," with the founding
and internal households simply flagged past the cap, not a billing-model
rework.

---

## 8. Verification caveats (honesty ledger)

Verified directly from vendor pages 2026-07-06: NextDNS, Control D,
CleanBrowsing, eero Plus, Firewalla hardware, Tailscale, Nabu Casa, Bitwarden,
Aura, Bark, Qustodio (page fetch), Canopy, Net Nanny, Grafana Cloud, Stripe
fees, Render storage $0.30/GB + bandwidth + workspace plans.

Unverified / conflicting — do not quote externally without re-checking:
Render per-tier compute prices (JS-rendered pricing table; †-marked, ±30%),
AdGuard DNS Personal annual price + exact free caps, Qustodio Complete exact
list price ($104.95 vs $109.95), Bark and Net Nanny trial lengths, Firewalla+
/ MSP subscription pricing, Net Nanny enforcement architecture, Cloudflare
Pages "unlimited bandwidth" (secondary-sourced), Stripe basil coupon-duration
restriction (title-only), API-instance household capacity (assumption —
re-measure at ~5 and ~25 households).
