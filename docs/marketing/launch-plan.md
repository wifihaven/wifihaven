# WifiHaven launch & marketing plan

Status: draft, 2026-07-12. Pricing figures from
[`docs/design/pricing-analysis.md`](../design/pricing-analysis.md) (research
date 2026-07-06). Timeline below is the **operator's revised plan** and
supersedes the pricing doc's fixed 4-month beta where they differ.

## 1. Positioning

**One-liner:** WifiHaven is open-source parental controls and screen time
enforced at the router — no apps to install on kids' devices, no DNS tricks to
bypass, running on hardware you already own.

**The story for tech press (in order):**

1. **Self-hosted and free forever** — the whole control plane is open source;
   run it yourself at no cost. Stated explicitly, Nabu Casa-style.
2. **Enforcement is connection-layer, not DNS.** nftables forward-drop on the
   gateway; DNS always resolves. DoH / hard-coded-IP bypass is closed
   (`blockIpOnly`). This is the technical differentiator the savvy audience
   will actually test — every DNS-filter competitor fails this test.
3. **No per-device agents.** Coverage is the network, not the install base.
   Immune to app deletion, MDM removal, new/guest devices.
4. **Cloud tier = convenience, not gated features.** Hosted control plane,
   history/retention, remote access. Beta opening now.
5. **BYO OpenWRT router.** Zero hardware cost vs. Firewalla ($279–929) or
   Gryphon; honest about the audience this implies at this stage.

**Do not say** (per AGENTS.md architectural model): anything implying DNS
sinkholing, "blocking domains at the DNS level," or per-device policy
authoring. Blocking language is always "connection-layer" / "at the gateway."

## 2. Launch phases & timeline

Milestone-driven, not date-driven:

| Phase | Trigger | What happens |
|---|---|---|
| **P0 — Prep** | now | Marketing site copy (#1171), beta signup flow, press kit (screenshots, architecture diagram, founder bio), docs polish for self-host quickstart |
| **P1 — Beta launch** | press release day | Press release + pitches out; Show HN; r/openwrt + r/selfhosted posts; beta open |
| **P2 — Beta full** | **25th beta household enrolls** | Close beta signups (waitlist continues); announce the GA date = this day + 2 months, to beta users and waitlist |
| **P3 — Quiet period** | 2 months after P2 | Fix what beta surfaced; T−30 and T−7 conversion emails + in-app banner (per pricing doc §5); collect testimonials; second press touch ("what we learned") optional |
| **P4 — GA** | P2 + 2 months | Charging starts: $10/mo or $96/yr. Beta households offered $6/mo (or $57/yr) **forever** (`FOUNDING` promo). Waitlist admitted. Ads switched on |

Notes on the deltas from the pricing doc:

- Beta length is **event-based (25 users) + 2 months**, not a fixed 4 months.
  Keep the doc's key principle anyway: **the flip terms are printed at
  signup** — "Free during beta. GA pricing starts 2 months after the beta
  fills (25 households); you'll get 60 days' notice and the $6/mo founding
  price for life."
- If the beta fills very fast (<1 month), total free period could be ~3
  months; if slowly, longer. Cap risk: if 25 signups haven't arrived in ~3
  months, treat that as a demand signal to investigate, not a reason to keep
  waiting passively.
- Everything else from pricing doc §5 stands: no card during beta, read-only
  grace state for non-converters (never brick the network), 40%-forever
  founding coupon, <40% conversion = red flag.

## 3. Channels (priority order)

1. **Earned press** — see [`media-list.md`](media-list.md) and
   [`press-release.md`](press-release.md). Tech-savvy pubs only at this
   stage; mainstream parenting press is a GA+multi-platform-router story,
   not a beta-on-OpenWRT story.
2. **Community launches** (same week as press):
   - Show HN (best single channel for this exact audience; self-hosted +
     networking + open source is HN catnip). Founder answers comments all day.
   - r/openwrt, r/selfhosted, r/HomeNetworking, r/Parenting (careful,
     value-first), OpenWRT forum Community Builds section, lobste.rs.
   - Self-hosting newsletters/aggregators: selfh.st, Awesome-Selfhosted PR,
     console.dev.
3. **YouTube/podcast outreach** (weeks 2–6): Self-Hosted podcast, Techno Tim,
   Lawrence Systems, Wolfgang's Channel, NetworkChuck — offer a guided demo.
   One good self-hosting YouTube review outperforms most written press for
   this audience.
4. **Paid ads** — OFF during beta (25 slots don't need paid fill; press +
   HN will oversubscribe or tell us something important). ON at GA. See
   [`ad-plan.md`](ad-plan.md).
5. **Content/SEO** (ongoing, low effort): 2–3 technical posts that double as
   pitches — "Why DNS filtering can't do screen time," "Parental controls
   that survive DoH," "Running WifiHaven on a $30 router." These are the
   organic-search magnets for the exact queries the ad plan buys.

## 4. Beta recruitment funnel

Target: 25 households. Expected sources: HN + Reddit (15–20), press coverage
(5–10), OpenWRT forum (2–5). Signup asks: router model, OpenWRT version,
household size — screen for supportable configs; over-subscription is
handled by waitlist, which becomes the GA day-one cohort.

Beta users' obligations (stated up front): willing to file issues, one
15-minute feedback call mid-beta, quote/testimonial permission ask (opt-in)
near GA.

## 5. Metrics

- P1: press pickups (target ≥3 including 1 top-tier), HN front page (yes/no),
  beta signups/week, waitlist size.
- P2–P3: activation rate (enrolled router actually enforcing within 7 days),
  weekly active dashboards, issues filed/fixed, NPS-ish mid-beta question.
- P4: beta→paid conversion (expect 40–70%; <40% = investigate pricing/value
  before pushing ads), waitlist→paid, CAC from ads vs. $10/mo LTV (see
  ad-plan.md), MRR vs. the ~$115–160/mo infra step (pricing doc §4.3).

## 6. Owner & assets checklist (P0)

- [ ] Marketing site: pricing page (single price + founding banner), self-host
  vs cloud comparison, security/architecture page (#1171)
- [ ] Press kit page: logo, screenshots (dashboard, schedule editor, block
  page), 1-para + 1-page product descriptions, founder photo/bio
- [ ] Beta signup form + waitlist
- [ ] Self-host quickstart validated on a clean router (the press will try it)
- [ ] `FOUNDING` Stripe promo code (verify `duration=forever` on pinned API
  version — pricing doc §7 caveat)
- [ ] Email templates: beta welcome, P2 GA-date announcement, T−30, T−7

## 7. Launch dependencies (GA gates)

Two engineering blockers gate **paid GA (P4)** — not the beta. The beta ships
on the self-host/single-household stack that is already in production; charging
strangers to share a hosted control plane is what raises the bar.

| Dependency | Issue | State (2026-07-12) | Gates | Why it's a gate |
|---|---|---|---|---|
| Signed router package auto-update | [#2078](https://github.com/wifihaven/wifihaven/issues/2078) | **Closed** ✅ | P1 (beta) | Auto-update installed unsigned `.apk`/`.ipk` with `--allow-untrusted`; a launch-security-audit (#369) High. Resolved — this was a beta blocker (we ship agents to beta households), now cleared. |
| Multi-tenant isolation | [#2085](https://github.com/wifihaven/wifihaven/issues/2085) | **Open** ⛔ | **P4 (paid GA)** | The hosted tier will hold *multiple households'* devices, policies, and browsing history in one DB/API. #369 found single-household isolation gaps. Every wave (roots users/routers/profiles/devices by household, wire-invisible) must land before we take money from a second paying household. This is the critical-path GA gate. |

**Rule:** the P2→P4 clock (beta fill + 2 months) does not start counting toward
a *billable* GA until #2085's isolation waves are merged and verified. If the
beta fills before #2085 is done, extend the free period rather than open billing
on an un-isolated multi-tenant store — a cross-household data leak at launch is
existential. The multi-tenant design and decomposition live under
[#2085](https://github.com/wifihaven/wifihaven/issues/2085) /
[#622](https://github.com/wifihaven/wifihaven/issues/622).

Secondary, non-gating: the self-host quickstart must be validated on a clean
router before P1 (press *will* try it, §6), and the `FOUNDING` Stripe coupon's
`duration=forever` must be verified on the pinned API version before P4
(pricing doc §7).

## 8. Risks & open questions (operator decisions)

Opinionated calls above; these are the ones the operator should confirm or
override before P1.

1. **Beachhead audience is narrow by design.** Starting OpenWRT-only means the
   beachhead is self-hosters/homelabbers, not the mass "worried parent" market.
   That's deliberate (they'll test the tech, write the reviews, tolerate rough
   edges) — but it caps beta volume and means mainstream parenting press is a
   *post-GA, multi-router* story. **Confirm** we're comfortable with a small,
   technical P1 rather than chasing scale early.
2. **Beta size = 25.** Big enough to surface real config diversity, small enough
   to support hands-on. **Open:** is 25 right, or do we want 15 (tighter support)
   or 40 (more signal, more load on one founder)?
3. **Event-based beta length** (fill + 2 months) diverges from the pricing doc's
   fixed 4 months. Locked in per operator 2026-07-12 — flagged here so the
   pricing doc and signup copy stay reconciled.
4. **Open-core trust.** r/selfhosted and HN punish any hint of bait-and-switch.
   The "self-hosted is complete, not crippleware; cloud = convenience" line must
   be *literally true* at launch. **Confirm** no cloud-only enforcement feature
   ships before we can honestly say the self-host tier is full-featured.
5. **Founder-as-support.** P1–P3 assume the founder personally answers HN,
   Reddit, issues, and one call per beta household. That's the plan at 25; it
   does not scale past low-hundreds. Fine for now — noted so it's a conscious
   choice, not a surprise at GA.
6. **Un-priced items:** press-kit production (screenshots/diagram/bio), the two
   marketing-site pages beyond copy (#1171), and any legal/ToS/privacy review
   for taking payment and hosting minors' browsing data. **Open:** does the
   hosted tier holding children's traffic need a privacy/legal pass before P4?
7. **`~$30 OpenWRT router` claim** in the press release is UNVERIFIED — pick and
   price a current example model (e.g. a GL.iNet entry unit) before send, or
   soften to "starting well under $100."
