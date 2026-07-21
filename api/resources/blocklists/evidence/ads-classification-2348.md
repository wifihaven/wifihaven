# ads-blocklist pass — 2026-07-21 (#2348)

Traffic-driven blocklist pass via the `/blocklist-pass` skill (weekly automated
run). This file covers the **ads** category; gambling / games / ai findings are
in `misc-classification-2348.md`.

## Method

- Pulled `GET /api/devices/<mac>/recent-apexes?windowDays=30&limit=500` for all
  26 prod devices (read-only, admin token). Aggregated bytes + hits per apex
  across devices — **1,712 distinct apexes** with traffic this window.
- Classified each apex by what it *is* (vendor identity), not by a substring
  match. Cross-checked every candidate against the full curated `ads.yml`
  (apex + suffix match) through the #2212 additions, plus the StevenBlack
  `ads-extended` feed as a lower-priority cross-check.
- Web-verified ownership for every ambiguous apex before adding — websearch
  tooling was available and used for all candidates this run (unlike #2212,
  where it was intermittently down).
- **Followed up on #2212's "held out — unverified" list.** That pass asked the
  next run to re-verify a set of apexes it couldn't confirm; this run
  web-searched every one of them (see below).

IPv4-bias caveat (#1796) applies — byte totals under-count IPv6-heavy flows.

## Key finding: two veins this week

1. **The RTB/bidder/SSP name-pattern vein is still productive** — 14 more
   `*rtb*`/`*bid*`/`*ssp*`-shaped apexes turned up that aren't in `ads.yml` or
   `ads-extended`. Same reasoning as #2064/#2122: the name is the function, so
   the collateral rule doesn't bite.
2. **Resolving #2212's unverified backlog.** Of the 27 apexes #2212 held out,
   9 are now confirmed genuine ad-tech (websearch worked this time), 3 are
   confirmed **dual-use security infra** (same vendor, HUMAN Security, running
   three differently-named domains), and 15 remain genuinely unidentifiable —
   carried forward again.

## Added apexes (30)

### RTB/bidder/SSP name-pattern gaps (name is the function — low collateral)
| apex | bytes | hits | what it is |
|------|------:|-----:|------------|
| optimusbid.com | 582K | 146 | RTB bidder (name-pattern) |
| globalrtb.com | 475K | 47 | RTB exchange (name-pattern); #2212 held out, now genuine gap confirmed |
| adspsp.com | 284K | 4 | SSP (name-pattern) |
| htlbid.com | 197K | 4 | Header-bidding wrapper (name-pattern) |
| rapidbidding.com | 197K | 10 | RTB bidder (name-pattern); #2212 held out, now genuine gap confirmed |
| tubrtb.com | 127K | 11 | RTB exchange (name-pattern) |
| bidsystem.ai | 94K | 2 | Bidder platform (name-pattern) |
| imbid.co | 89K | 13 | RTB bidder (name-pattern) |
| colossusssp.com | 36K | 1 | SSP (name-pattern); #2212 held out, now genuine gap confirmed |
| rtb.mx | 8K | 2 | RTB exchange (name-pattern) |
| anyrtb.com | 7K | 2 | RTB exchange (name-pattern) |
| bidmatic.io | 7K | 1 | RTB bidder (name-pattern) |
| rtb-oveeo.com | 7K | 1 | RTB exchange (name-pattern) |
| prebid.cloud | 3K | 1 | Hosted Prebid Server (open-source header bidding) |
| betweendigital.com | 15K | 6 | Between Digital — SSP/PMP, RTB (Moscow); name looked like a gambling false-positive ("bet" substring) but is ad-tech — see misc doc |

### Verified-ownership vendor apexes (ambiguous names confirmed via web)
| apex | bytes | hits | what it is |
|------|------:|-----:|------------|
| geoedge.be | 33.5M | 611 | GeoEdge — ad quality/security (malvertising protection), founded 2010 |
| xlgmedia.com | 4.7M | 208 | XLMedia — ad network/performance publisher (observed subdomain `s.xlgmedia.com`, the serving/script host, not the main content site) |
| adtonos.com | 4.1M | 140 | AdTonos — programmatic audio advertising platform |
| adrta.com | 4.0M | 432 | Pixalate ad-fraud/analytics platform; #2212 held out unverified, now confirmed |
| permutive.app | 3.1M | 47 | Permutive — contextual/first-party-data ad targeting for publishers |
| permutive.com | 2.1M | 108 | Permutive (sibling apex) |
| acobt.tech | 1.5M | 55 | Bigo Ads — one of many deliberately obfuscated/anonymous tracking domains Bigo Ads rotates across mobile apps (same evasion pattern as the already-curated `antibanads.com`); #2212 held out unverified, now confirmed |
| admatic.de | 199K | 5 | AdMatic GmbH — German independent DSP/SSP ad-tech; #2212 held out unverified, now confirmed |
| mfadsrvr.com | 162K | 31 | Media Force Ltd ad server, integrates via BidSwitch; #2212 held out unverified, now confirmed |
| ninthdecimal.com | 118K | 11 | NinthDecimal (ex-JiWire, acquired by InMarket 2020) — location-based ad marketing platform; #2212 held out unverified, now confirmed |
| rixengine.com | 81K | 12 | RixEngine — programmatic ad exchange incubated by Baidu Global; #2212 held out unverified, now confirmed |
| sparteo.com | 33K | 3 | Sparteo — full-stack publisher ad-tech (SSP/video/display/CMP), French; #2212 held out unverified, now confirmed |
| adtarget.biz | 30K | 5 | AdTarget — RTB/header-bidding retargeting platform (Lithuania); #2212 held out unverified, now confirmed |
| mediago.io | 12K | 4 | MediaGo (Baidu Global) — DSP/programmatic ad platform; #2212 held out unverified, now confirmed |
| 4dsply.com | 84K | 1 | AdSupply, Inc. — rich-media ad network; independently flagged by security researchers for traffic laundering / pop-up abuse |

## Skipped — dual-use / wrong-category (collateral rule)

- **script.ac, protechts.net, tagsrvcs.com** — all three are **HUMAN Security**
  (bot-mitigation/ad-fraud cybersecurity vendor, formerly White Ops) running
  under differently-named domains. Same collateral class as the already-skipped
  `datadome.co`/`confiant-integrations.net`: HUMAN protects login/checkout/
  general traffic on legitimate sites broadly, not ad-serving exclusively —
  blocking it risks breaking unrelated site functionality, not just ads.
  `tagsrvcs.com` was previously (incorrectly) attributed to "Bazaarvoice
  reviews widget" in the #2212 evidence doc; this run's web search instead
  confirms HUMAN Security ownership via Netify — the #2212 note was wrong,
  correcting it here.
- **betrad.com** (362K bytes, 21 hits) — ambiguous. One source description
  reads as a betting/wagering site; another (cside.com) describes it as
  third-party tracking-script infra used *by* gambling-adjacent properties.
  Given the ambiguity, held out rather than added to either `gambling.yml` or
  `ads.yml`.
- **hs-analytics.net** (477K bytes, 40 hits) — HubSpot Analytics. Same
  collateral class as `google-analytics.com`: first-party website analytics
  widely embedded by legitimate small/medium business sites, not ad-serving.

## Held out — UNVERIFIED this run (do NOT re-add from the name alone)

15 of the 27 apexes #2212 held out are still unverifiable — carried forward
again for next pass to re-check:

- `native-cloud.com` (2.0M, 1,032 hits), `dailyinnovation.biz` (3.8M, 266
  hits), `lazybumblebee.com` (6.9M, 496 hits), `onegg.site` (3.2M, 114 hits),
  `gt162037.com` (1.1M, 52 hits), `ammnlth.net` (890K, 34 hits), `4dex.io`
  (1.0M, 69 hits), `tk0x1.com` (1.3M, 176 hits), `impression.link` (306K, 41
  hits), `qvdt3feo.com` (2.7M, 198 hits), `str-nrg.com` (2.9M, 238 hits),
  `tq-tungsten.com` (2.8M, 190 hits), `img-static.tech` (990K, 89 hits),
  `digital-services.solutions` (340K, 64 hits), `qwadro.com` (985K, 163 hits).
- `youngle.tech` (25.1M bytes, only 7 hits — high bytes/hit ratio typical of
  ad-creative loads) returned **contradictory** search results (one source
  describes it as a legitimate tech-careers blog; others flag `gdl.youngle.tech`
  on scam-adviser tools). Given the mismatch, held out rather than trusting
  the low-confidence "blog" explanation.
- `nextmillmedia.com`, `adelement.com` — plausible ad-tech names, not
  confirmed this run either.

New this pass (not previously seen): `pod-ad.com` (22M bytes, 4 hits —
subdomain `iowl.pod-ad.com`, no identifiable owner found), `growthguru.bid`
(63K bytes, 1 hit — `.bid` TLD suggestive but no confirmed identity),
`inhousedsp.com` (6.7M bytes, 122 hits — subdomain `content.inhousedsp.com`,
generic DSP terminology but no confirmed specific company).
