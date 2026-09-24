# Blocklist pass — 2026-09-22 (#2807)

30-day prod `recent-apexes` sweep across all 30 registered devices (windowDays=30,
limit=500 per device), aggregated to 1,790 unique apexes. Swept all six inline
categories (`ads`, `adult`, `ai`, `gambling`, `games`, `social-media`) by keyword,
checked candidates against each category's current curated list (and, for `ads`,
also cross-checked the StevenBlack `ads-extended` feed).

## Added

| Category | Apex | Bytes | Hits | What it is |
|---|---|---|---|---|
| ads | responsiveads.com | 713,507 | 7 | ResponsiveAds — display-ad creative/serving platform (NBCUniversal, Condé Nast among clients). Observed subdomains `publish.`/`video2.`/`analytics.` corroborate. |
| ads | vdo.ai | 464,205 | 5 | VDO.AI — video-ad platform/adserver. Observed subdomain `ortb.vdo.ai` is a direct open-RTB signal. Another instance of the recurring ".ai TLD is not an AI-product signal" trap (#2599/#2742/#2756/#2759/#2792) — surfaced in the `ai.yml` sweep but is ad-tech. Also present in `ads-extended` (`a.vdo.ai`), corroborating. |
| ai | hellohaven.ai | 1,365,942 | 4 | Hello Haven's "Haven" — a consumer personal-AI digital-twin assistant app (launched 2026-09-17, $15M pre-seed led by Mayfield, available on iOS/Android). Observed at its confirmed product subdomain `my.hellohaven.ai`. |

## Investigated, already curated — corrected during independent review

- **bidsystem.ai** — 3,012,250 bytes / 7 hits, subdomains `ads.`/`assets.`.
  Confirmed as Ezoic's programmatic ad-bidding engine, and initially treated
  as a genuine gap in this pass's first draft. Independent PR review caught
  that it was already added to `ads.yml` in a prior pass (in the RTB/bidder/
  SSP name-pattern block) — the identity call was correct, the membership
  check against `ads.yml` was not done because the candidate surfaced from
  the `ai.yml` keyword sweep and only got cross-checked against `ai.yml`.
  Not re-added; the duplicate line and test pin were removed. Same failure
  class as the #2742 learning ("check membership across every curated file,
  not just the one the sweep bucketed it into") — that lesson wasn't applied
  to a host discovered fresh mid-pass, only to hosts already known from prior
  evidence docs. See the updated Learnings log entry in SKILL.md.

## Held out — investigated this pass, no confirmed identity or wrong scope

- **livesteamstation.com** (games sweep, matched `steam`) — confirmed legitimate
  model-train e-commerce store ("Live Steam Station"), unrelated to Valve Steam.
  False positive, not added.
- **viddea.com** (games sweep) — 739,820 bytes / 92 hits, subdomain
  `us.a.viddea.com`. No confirmed company identity found via websearch. Held
  out for a future pass.
- **adxflyer.com**, **adxvalidation.com**, **allicreative.com**, **infmmtag.com**
  (ads sweep) — low traffic (4–25 hits each), no confirmed company identity.
  Held out.
- **unconstrainedanalytics.org** (ads sweep, matched `analytic`) — confirmed to
  be an unrelated political-commentary blog. False positive, not added.
- **hs-analytics.net** (ads sweep, matched `analytic`) — confirmed HubSpot
  marketing-analytics domain. Same dual-use class as `google-analytics.com`:
  widely embedded by legitimate sites for their own first-party visitor
  analytics. Not added.
- **paperlesspost.com** — matched the `ssp` substring inside "paperle**ssp**ost".
  Legitimate greeting-card/invitations company; false positive on substring,
  not an SSP.

## Already covered — keyword-sweep hits that are existing curated entries

`epicgameshubham.com`, `ads-twitter.com`, `tiktokpangle-b.us`,
`tiktokpangle-cdn-us.com`, `tiktokpangle.us`, `axon.ai`, `koah.ai`,
`mediayo.ai`, `programmaticx.ai`, `trygravity.ai` all matched the games/
social-media/ai keyword sweeps but are already curated in `ads.yml` from prior
passes (verified by direct membership check, not re-added).

## Skipped — dual-use / content-collateral / standing exclusions

- **unity3d.com**, **unity3dusercontent.com** (games sweep) — Unity game-engine
  CDN, dual-use content-collateral (same class as prior `jwplayer.com`/
  `target-video.com` exclusions).
- **lazybumblebee.com** (social-media sweep) — standing rejection re-confirmed
  across four prior passes (#2212, #2348, #2503, #2729); unrelated lifestyle
  blog, not Bumble-affiliated. Not re-added.
- **claude.com**, **dxtech.ai**, **kapa.ai**, **trinitymedia.ai** (ai sweep) —
  standing exclusions from prior passes (WifiHaven's own vendor infra /
  out-of-scope B2B SaaS / embedded widget). Not re-litigated.
- **forethought.ai** (ai sweep) — B2B enterprise customer-support AI platform
  (now Zendesk-owned). Out of scope per the #2792 "B2B SaaS generally" rule.
- **ivy.ai** (ai sweep) — B2B chatbot for colleges/universities, not a consumer
  product. Out of scope.
- **typesafe.ai** (ai sweep) — B2B/enterprise AI lab building models for
  software-to-software consumption ("Jev"), not a consumer product. Out of
  scope.
- **e-volution.ai**, **flux.ai**, **higgs.ai** (ai sweep) — carried forward from
  prior passes' held-out/standing-exclusion lists without re-litigation
  (flux.ai is in fact already explicitly recorded in `ai.yml` as a standing
  dual-use exclusion — PCB-design CAD, not the Flux image model).

## Categories with zero traffic-observed candidates this pass

- **adult.yml** — no adult-content apexes appeared in this week's 30-day
  window across any device (valid empty outcome, consistent with prior passes
  e.g. #2503).
- **gambling.yml** — same; zero gambling-shaped apexes observed.

## Method notes

- Host-set extraction per category used
  `sed -E 's/^  - //; s/[[:space:]]*#.*$//; s/[[:space:]]+$//'` (strips
  trailing inline comments per the #2742 lesson) and was smoke-tested against
  known sentinels (`pubmatic.com` in `ads.yml`, `acebet.cc` in `gambling.yml`)
  before trusting it.
- `doubleclick.net` is intentionally absent from `ads.yml` (removed #2601,
  shared-GFE collateral) — not a missed sentinel, expected.
