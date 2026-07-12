# Advertising plan — low-cost (<$250/mo)

Status: draft 2026-07-12. Ads stay **OFF until GA** (P4 in
[`launch-plan.md`](launch-plan.md)) — the 25-slot beta is filled by press +
community launch, and paid acquisition into a free product with no card on
file measures nothing.

## Budget & economics

- Budget: **$200/mo** ads + ~$0 tooling (Google Ads conversion tracking is
  free; use GA4 or self-hosted Plausible).
- Unit economics: $10/mo ≈ $9.34 net (pricing doc §4.4); marginal infra
  $1–3/household → contribution ≈ $6–8/mo, ~$75–100/yr assuming ~12-month
  retention. **Target CAC ≤ $50; kill threshold $100.**
- At $200/mo and plausible $2–5 CPCs for niche technical keywords, expect
  40–100 clicks/mo. With a 5–10% signup rate that's 2–10 signups/mo — small,
  but the point at this stage is learning which queries convert, not volume.

## Channel split

| Channel | $/mo | Why |
|---|---|---|
| Google Search (exact/phrase, US) | $150 | Only channel with harvestable intent at this budget |
| Reddit ads (r/openwrt, r/selfhosted, r/HomeNetworking, r/Parenting) | $50 | Cheap CPMs, exactly the audience; run as honest text-style promoted posts |
| Display / YouTube / Meta / retargeting | $0 | Skip — budget too small to exit learning phase; revisit >$1k/mo |

## Google Ads structure

One campaign, manual/max-clicks to start (switch to tCPA only after ≥30
conversions), US-only, search partners OFF, content exclusions ON.

**Ad group 1 — bypass-proof intent (highest value):**
"parental controls kids can't bypass", "parental controls that block DoH",
"parental control router level", "block internet by device schedule",
"parental controls without installing app".

**Ad group 2 — competitor/comparison:**
"gryphon router alternative", "circle home plus alternative" (orphaned
users — Circle hardware wound down), "firewalla parental controls",
"eero plus alternative", "bark alternative", "pihole parental controls".
Bid low; comparison intent converts but CPCs run higher.

**Ad group 3 — self-host/OpenWRT (cheapest, most qualified):**
"openwrt parental controls", "openwrt screen time", "self hosted parental
controls", "open source parental controls", "nftables parental control".
These searchers are the self-host funnel — they may never pay, but they're
the review-writing population; fine at these CPCs.

**Negative keywords (seed list):** free, crack, bypass parental controls,
how to get around, jobs, apk, iphone-only terms ("parental controls iphone"
— we can't help without the router), windows, chromebook school.
Add "openwrt" as negative to ad groups 1–2 only if cross-matching muddies data.

**Ad copy angles (test 2–3 RSAs):**
1. "Parental Controls Kids Can't Delete — Enforced at your router, not their
   phone. No apps to install. $10/mo."
2. "Screen Time That Actually Sticks — Blocks encrypted-DNS workarounds.
   Open source. Free to self-host."
3. Competitor group: "A Gryphon Alternative on Your Own Router — No $200
   hardware. Open source. From $10/mo."

**Landing pages:** ad group 1–2 → cloud signup page; ad group 3 → self-host
quickstart page with a soft cloud cross-sell. Never send paid traffic to the
homepage.

## Reddit ads

Promoted post, not display: plain-spoken title ("We built open-source
parental controls that run in your OpenWRT router"), link to the technical
blog post, not the pricing page. Reddit converts on credibility. $50/mo,
pause immediately if comment sentiment goes sideways.

## Measurement & cadence

- Conversions: (1) beta/GA signup started, (2) checkout completed (primary),
  (3) self-host quickstart docs visit ≥2 min (secondary, ad group 3).
- Weekly 15-min check: search-terms report → harvest negatives; pause
  keywords with >$25 spend and 0 signups.
- Monthly: CAC per ad group vs. $50 target; shift budget toward whichever
  group converts; write down one lesson.
- Review whole plan at 3 months post-GA: if blended CAC <$50, scale to
  $500/mo; if >$100, pause paid and double down on content/SEO (the same
  keywords, organically — see launch-plan.md §3.5).

## Free/near-free multipliers (do these regardless)

Search Console tracking of the ad keywords for organic rank; the 2–3
technical blog posts as landing/organic magnets; Awesome-Selfhosted and
alternativeto.net listings (free "ads" with permanent intent traffic);
answer relevant r/openwrt and OpenWRT-forum threads as a disclosed founder.
