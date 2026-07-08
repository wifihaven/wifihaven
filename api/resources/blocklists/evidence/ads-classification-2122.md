# ads-blocklist pass — 2026-07-07 (#2122)

Traffic-driven ads-blocklist pass via the `/ads-blocklist-pass` skill.

## Method

- Pulled `GET /api/devices/<mac>/recent-apexes?windowDays=30&limit=500` for all
  26 prod devices (read-only, admin token). Aggregated bytes + hits per apex
  across devices (1,006 distinct apexes with traffic this window).
- Classified ad/RTB/SSP/DSP/tracker/attribution apexes not already in
  `ads.yml`.
- Cross-checked each candidate against `ads.yml` (apex + suffix match) and the
  StevenBlack `ads-extended` feed
  (`raw.githubusercontent.com/StevenBlack/hosts/master/hosts`, 78,188 distinct
  hosts) — both at the apex and for any subdomain of the apex the feed lists.
  This separates genuine **apex-level gaps** (feed misses the apex entirely; it
  may list one specific subdomain, but our apex entry suffix-matches all
  subdomains) from apexes the extended feed already covers at the apex.
- Skipped dual-use / shared infra per the collateral rule; web-verified the
  ambiguous / lower-confidence apexes before adding.

IPv4-bias caveat (#1796) applies — byte totals under-count IPv6-heavy flows.

Most of the classic RTB/bidder names surfaced this week were **already covered**
by prior passes' `ads.yml` additions (`coldbidder.com`, `rtblab.net`,
`openrtbx.com`, `one-bid.com`, `servenobid.com`, `tmbid.com`, `bid-algorix.com`,
`rtbuniverse.com`, `smarterbidder.com`, `lacunads.com`, `bm-ads.io`,
`rtb-adv.com`, `adnxs.net`, `adsappier.com`, etc.), so this pass is smaller and
targets only the genuine new gaps.

## Skipped (dual-use / wrong category)

| Apex | bytes / hits | Why skipped |
|------|-------------|-------------|
| `app-ads-services.com` | 42.3 MB / 177 | Google-operated ATT-segmentation endpoint (per #1923). Shared Google pool — collateral. |
| `app-analytics-services.com` | 28.2 MB / 381 | Google GA4 (per #1923). Same shared-product class as `app-measurement.com`. |
| `app-analytics-services-att.com` | 46.2 KB / 3 | Google GA4/ATT sibling (per #1923). |
| `smartborad.com` | 3.3 MB / 83 | Scamadviser-flagged malware, unclear identity (per #2064) — malware list at most, not ads. |
| `freebeacon.com` | 3.0 MB / 5 | Washington Free Beacon — news **content** site, not a tracker (name matched `beacon`). |
| `myfitnesspal.com` | 2.1 MB / 16 | MyFitnessPal fitness app — content/product, not ad infra. |
| `iclasspro.com` | 116.5 KB / 2 | iClassPro class-management SaaS — wrong category. |
| `horsebreedspictures.com` | 56.3 KB / 1 | Made-for-advertising content site; the apex is content, not ad infra. |
| `bdtelemetry.amazon` | 615.4 KB / 89 | Amazon first-party device telemetry on the internal `.amazon` gTLD — not a public ad apex. |
| `imganalytics.com` | 110.1 KB / 20 | Ambiguous ownership: IMG sports-data analytics (Endeavor) **or** HUMAN (anti-bot/ad-fraud) infra per netify. Neither is clearly ad-serving — held out. |

## Held out — unverified ad-ish names

Name reads ad/RTB but ownership could not be confirmed (netify 404 / no
authoritative source). Per the skill's conservative rule, unverified apexes are
held out rather than added: `optimusbid.com` (73.6 KB / 46), `imbid.co`
(38.1 KB / 4), `stbid.ru` (51.7 KB / 3), `growthguru.bid` (62.0 KB / 1),
`gmtrack-visit.com` (44.3 KB / 1 — also collides with GMTRACK vehicle tracking),
`tmatrackapp.site` (73.7 KB / 2), `tpdads.com` (137.2 KB / 2).

## Added — genuine apex-level gaps (NOT covered at the apex by ads-extended)

`bytes / hits` are 30-day per-apex totals summed across devices.

### Ad servers / RTB exchanges / mediation / bidders

| Apex | bytes / hits | What it is |
|------|-------------|-----------|
| `adkernel.com` | 59.7 KB / 6 | AdKernel — white-label RTB ad-server / OpenRTB exchange (verified, adkernel.com). |
| `privateadserver.com` | 41.5 KB / 2 | Private Ad Server — RTB / Prebid-Server ad-serving platform (verified, docs.privateadserver.com). |
| `serverbid.com` | 676.3 KB / 46 | ServerBid — server-side header-bidding-as-a-service over OpenRTB (verified, Crunchbase). |
| `mosspf.com` | 136.2 KB / 27 | TopOn ad-mediation infra; `adx.mosspf.com` is its ad exchange (verified, netify/TopOn). |
| `mosspf.net` | 133.1 KB / 27 | TopOn ad-mediation sibling apex of `mosspf.com`. |
| `gamaibids.com` | 49.8 KB / 14 | RTB bidder — textbook `bid.` / `bids.` / `trk.` subdomain structure (verified via subdomain scan). |
| `progrtb.live` | 113.7 KB / 18 | Programmatic-RTB ad infra (name-is-function; unambiguous `.live` throwaway TLD). |
| `maticooads.com` | 1.5 MB / 52 | zMaticoo (Mintegral-adjacent) mobile ad SSP/DSP (name-is-function). |
| `zmaticoo.com` | 1.3 MB / 126 | zMaticoo mobile ad platform apex; sibling of `maticooads.com`. |

### Attribution / impression + ad-fraud trackers

| Apex | bytes / hits | What it is |
|------|-------------|-----------|
| `yabidos.com` | 599.6 KB / 31 | FraudLogix ad-fraud / behavioral-targeting tracker (`pixel.yabidos.com`, verified via Ghostery/Feroot). |
| `adsninja.ca` | 5.1 MB / 13 | AdsNinja advertising / affiliate network (Valnet); IPFire DBL classifies it under Advertising. |

### Sibling apexes of already-curated ad vendors

| Apex | bytes / hits | What it is |
|------|-------------|-----------|
| `appsflyersdk.com` | 13.4 MB / 1,202 | AppsFlyer SDK / attribution endpoint; `appsflyer.com` already curated. |
| `svr-algorix.com` | 1.3 MB / 192 | AlgoriX ad-exchange server domain; `bid-algorix.com` already curated. |
| `outbrainimg.com` | 112.8 KB / 7 | Outbrain native-ads image CDN; `outbrain.com` already curated. |
| `openxcdn.net` | 18.7 KB / 1 | OpenX ad-exchange CDN; `openx.net` already curated. |
| `minutemedia-prebid.com` | 77.0 KB / 20 | Minute Media header-bidding (prebid) endpoint — the ad-specific sibling of the content publisher (per #2064). |

## Notes

- Every apex above is a genuine **apex-level** gap: none is present in `ads.yml`
  (apex or suffix), and none is present at the apex in `ads-extended`. Several
  (`appsflyersdk.com`, `maticooads.com`, `zmaticoo.com`, `serverbid.com`,
  `adsninja.ca`, `yabidos.com`, `adkernel.com`, `outbrainimg.com`,
  `openxcdn.net`, `minutemedia-prebid.com`) have a single specific subdomain in
  the feed but not the apex — our apex entry suffix-matches all of them.
- `BundledBlocklistsSpec` pins `adkernel.com` as a representative new host in its
  presence assertions.
