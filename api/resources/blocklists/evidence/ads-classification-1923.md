# ads-blocklist pass — 2026-06-23 (#1923)

Traffic-driven ads-blocklist pass via the `/ads-blocklist-pass` skill.

## Method

- Pulled `GET /api/devices/<mac>/recent-apexes?windowDays=30&limit=500` for all
  25 prod devices (read-only, admin token). Aggregated bytes + hits per apex
  across devices (1,702 distinct apexes).
- Classified ad/RTB/SSP/DSP/tracker/attribution apexes not already in
  `ads.yml`.
- Cross-checked each candidate against the StevenBlack `ads-extended` feed
  (`raw.githubusercontent.com/StevenBlack/hosts/master/hosts`, 82,622 distinct
  hosts) to separate genuine gaps (feed misses the apex entirely) from apexes
  the extended feed already covers.
- Skipped dual-use / shared infra per the collateral rule; web-verified the
  ambiguous high-traffic apexes.

IPv4-bias caveat (#1796) applies — byte totals under-count IPv6-heavy flows.

## Skipped (dual-use / wrong category)

| Apex | bytes / hits | Why skipped |
|------|-------------|-------------|
| `app-analytics-services.com` | 36.2 MB / 552 | Google GA4 + Firebase SDK endpoint (verified). Same shared-product class as the already-skipped `app-measurement.com`. |
| `app-ads-services.com` | 26.3 MB / 210 | Google-operated (ATT-segmentation pair of the analytics endpoint above). Google shared pool — conservative skip. |
| `ttdns2.com` | 4.8 MB / 334 | TikTok shared DNS infra (CapCut/Pangle/app analytics), **not** TheTradeDesk as the name suggests (verified via netify). Content collateral. |
| `demonii.com` | 1.7 MB / 9,240 | BitTorrent tracker (`open.demonii.com`), not ad infra — wrong category. |

## Added — genuine gaps (NOT in the StevenBlack ads-extended feed)

These ad apexes are absent from `ads-extended` (no apex, no/low subdomains), so
hand-curating them is the only way they get blocked.

| Apex | bytes / hits | What it is |
|------|-------------|-----------|
| `bidmachine.io` | 28.3 MB / 1,975 | Appodeal BidMachine in-app RTB exchange |
| `bidease.com` | 4.7 MB / 299 | Bidease mobile programmatic DSP |
| `kayzen.io` | 1.3 MB / 81 | Kayzen mobile-first programmatic DSP (verified) |
| `openwebmp.com` | 1.2 MB / 308 | OpenWeb ad / monetization infra (verified, netify) |
| `krushmedia.com` | 1.1 MB / 64 | KrushMedia ad exchange / SSP |
| `vervegroupinc.net` | 2.2 MB / 601 | Verve Group (MGI) ad-tech (verified) |
| `verve.net` | 1.2 MB / 39 | Verve Group ad infra |
| `personaly.bid` | 15.7 MB / 87 | Verve Group programmatic bidder endpoint (`.bid`) |
| `appiersig.com` | 0.9 MB / 138 | Appier AI ad-tech signal endpoint |
| `vidazoo.com` | 1.4 MB / 39 | Vidazoo video ad monetization / yield |
| `vidazoo.services` | 3.1 MB / 55 | Vidazoo video ad serving |
| `safedk.com` | 5.3 MB / 328 | SafeDK (AppLovin) mobile ad SDK measurement |
| `sng.link` | 1.2 MB / 158 | Singular attribution smart-links (pairs with `singular.net`, already listed) |
| `clarity.ms` | 5.4 MB / 93 | Microsoft Clarity session-replay / analytics tracker (dedicated domain, same category as the already-listed hotjar/fullstory) |

## Added — clearly-ad apexes also covered by ads-extended (curated baseline)

High-traffic, unambiguous ad-tech. The extended feed already lists these, but
they belong in the curated `ads.yml` so profiles on the curated-only list also
block them.

| Apex | bytes / hits | What it is |
|------|-------------|-----------|
| `applvn.com` | 17.1 MB / 43 | AppLovin tracking / ad domain (short form of `applovin.com`) |
| `adthrive.com` | 9.6 MB / 94 | AdThrive / Raptive publisher display-ad management |
| `jampp.com` | 11.7 MB / 277 | Jampp programmatic app-marketing DSP |
| `inner-active.mobi` | 13.1 MB / 910 | InnerActive (Fyber / Digital Turbine) mobile ad exchange |
| `celtra.com` | 7.1 MB / 86 | Celtra creative ad management |
| `pub.network` | 5.3 MB / 763 | Freestar `pub.network` publisher ad framework |
| `adswizz.com` | 2.8 MB / 129 | AdsWizz audio / podcast advertising |
| `contentsquare.net` | 4.1 MB / 71 | ContentSquare experience-analytics tracker |
| `rayjump.com` | 4.5 MB / 443 | Mintegral / Rayjump mobile ad network |
| `mtgglobals.com` | 4.5 MB / 457 | Mobvista / Mintegral ad infra |
| `aarki.net` | 3.3 MB / 232 | Aarki mobile DSP |
| `mgid.com` | 1.6 MB / 95 | MGID native ad network |
| `creativecdn.com` | 1.1 MB / 310 | RTB House creative CDN |
| `demdex.net` | 1.0 MB / 207 | Adobe Audience Manager (Demdex) DMP / cross-site tracker |

## Deferred to ads-extended (not hand-curated this pass)

Other genuine ad-tech apexes seen in traffic that the StevenBlack feed already
covers and that are lower-traffic / lower-priority for the curated baseline:
`id5-sync.com`, `eu-1-id5-sync.com`, `parsely.com`, `statcounter.com`,
`360yield.com`, `1rx.io`, `the-ozone-project.com`, `postrelease.com`,
`dotomi.com`, `ipredictive.com`, `unrulymedia.com`, `mediavine.com`,
`admanmedia.com`, `sharethis.com`, `intentiq.com`, `intergient.com`,
`omnitagjs.com`, `zmaticoo.com`, `brid.tv`, `ad-score.com`,
`bounceexchange.com`, `permutive.com`, `loopme.me`. Left to `ads-extended`
per the skill's "reserve curated `ads.yml` for a handful of clearly-ad
high-traffic apexes" guidance.

Unverifiable obscure bidders left out for lack of confirmation:
`coldbidder.com`, `smarterbidder.com` (suggestive names, no third-party
confirmation, no collateral risk in omitting).
