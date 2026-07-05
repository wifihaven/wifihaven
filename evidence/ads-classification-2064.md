# Ads-blocklist pass — classification evidence (#2064)

**Date:** 2026-06-30
**Method:** 30-day prod `GET /api/devices/{mac}/recent-apexes?windowDays=30&limit=500`
sweep across all 26 devices (read-only). Candidate ad/RTB/tracker apexes diffed
against the curated `ads.yml` **and** the StevenBlack `ads-extended` feed
(`raw.githubusercontent.com/StevenBlack/hosts/master/hosts`, ~82.7k hosts). The
38 apexes below are **genuine gaps** — apexes that even `ads-extended` misses
entirely — and are each unambiguously ad-dedicated (RTB exchange, bidder, DSP,
ad server, or impression/measurement tracker), so collateral risk is low.

> IPv4-bias caveat (#1796) still applies — the `recent-apexes` view is
> attribution-biased toward v4-resolved flows; v6-only ad traffic is
> under-counted. Re-confirm next pass.

## Added to `ads.yml` (38 apexes, sorted by 30-day bytes)

| apex | bytes (30d) | hits | what it is |
|------|-------------|------|------------|
| coldbidder.com | 6,209,155 | 163 | RTB bidder endpoint |
| adjust.io | 4,701,492 | 49 | Adjust mobile attribution (sibling of curated `adjust.com`) |
| antibanads.com | 4,252,192 | 64 | BIGO Ads ad-delivery infra, named to evade ad-blocking (security-research confirmed) |
| smarterbidder.com | 1,263,633 | 77 | RTB bidder |
| bidbrain.app | 984,029 | 84 | RTB bidding service |
| measureadv.com | 661,255 | 86 | Ad measurement / verification |
| tmbid.com | 619,184 | 103 | RTB bidder |
| tiktokpangle-b.us | 544,865 | 52 | TikTok Pangle ad network (sibling of curated `tiktokpangle.us`) |
| rtbuniverse.com | 537,242 | 52 | RTB exchange |
| bid-algorix.com | 500,352 | 170 | AlgoriX mobile ad exchange bidder |
| oneadtag.com | 405,629 | 23 | Ad tag delivery |
| bm-ads.io | 349,897 | 2 | Ad network |
| adkit-advertising.amazon | 325,904 | 17 | Amazon ad infra (`.amazon` brand TLD; ad-dedicated subdomain, peer of SB-listed `paa-reporting-advertising.amazon`) |
| rtbrain.app | 306,443 | 32 | RTB bidding service |
| openrtbx.com | 291,727 | 43 | OpenRTB exchange |
| lacunads.com | 275,694 | 17 | Ad network |
| rtblab.net | 237,304 | 30 | RTB exchange |
| one-bid.com | 161,238 | 10 | RTB bidder |
| rtbhouse.com | 157,345 | 50 | RTB House — retargeting DSP (web-verified) |
| ortb.net | 152,204 | 15 | OpenRTB exchange |
| bidgx.com | 136,248 | 41 | RTB bidder/exchange |
| rtb-adv.com | 122,363 | 9 | RTB ad exchange |
| mobidriven.com | 111,463 | 6 | Mobile ad network |
| omsrtb.com | 91,572 | 15 | RTB exchange |
| adnxs.net | 86,397 | 3 | AppNexus/Xandr (sibling of curated `adnxs.com`) |
| rtbscale.com | 75,243 | 4 | RTB exchange |
| adsappier.com | 66,820 | 4 | Appier ads (sibling of curated `appiersig.com`) |
| amxrtb.com | 64,344 | 47 | AMX SSP / RTB |
| cleanmediaadserver.com | 64,009 | 4 | Ad server |
| sharethru.com | 62,636 | 5 | Sharethrough (sibling of curated `sharethrough.com`) |
| rtbanalytics.com | 58,862 | 7 | RTB analytics/tracking |
| valorousadvertising.com | 57,423 | 2 | Ad network |
| rtbwise.com | 56,582 | 13 | RTB exchange |
| imptracking.com | 55,298 | 3 | Ad impression tracking |
| rapidtag.net | 53,725 | 1 | Ad tag delivery |
| iionads.com | 36,181 | 2 | Ad network |
| iqzonertb.live | 20,866 | 11 | RTB exchange |
| liftdsp.com | 13,919 | 8 | Demand-side platform |

## Notable SKIPS (and why)

| apex | bytes | hits | why skipped |
|------|-------|------|-------------|
| instagram.com | 474,144,517 | 312 | Meta social — name-collision false positive; would block IG entirely (not ads category) |
| cdninstagram.com | 220,672,236 | 63 | Instagram CDN — content, not ads |
| app-ads-services.com | 41,420,347 | 272 | Google GA4/ATT segmentation — shared Google infra, dual-use (documented skip from #1923) |
| app-analytics-services.com | 41,307,477 | 770 | Google GA4 — shared Google infra, dual-use (#1923) |
| unity3dusercontent.com | 9,479,887 | 7 | Unity game asset CDN — game content, dual-use |
| minutemediaservices.com | 7,468,363 | 6 | Minute Media is a content publisher; generic `services` apex risks content collateral |
| smartborad.com | 3,091,497 | 71 | scamadviser-flagged malware, identity unclear — not confidently ad-RTB (malware ≠ ads category) |
| zetaglobal.io | 169,654 | 18 | Zeta Global marketing cloud / CRM — carries first-party customer-marketing, dual-use |
| ottadvisors.com | 142,424 | 14 | OTT Advisors AdOps consultancy — apex is corporate/consulting site, ambiguous ad-serving |
| opentrackr.org | 668,874 | 4485 | BitTorrent tracker (`open.opentrackr.org`) — not ad infra (same class as #1923's `demonii.com`) |
| popcorn-tracker.org | 55,704 | 1212 | BitTorrent tracker — not ad infra |
| advolve.io | 76,024 | 13 | AI digital-marketing platform — borderline marketing-automation, low traffic, held out |
| applemediaservices.com | 11,251 | 1 | Apple infra — dual-use |
| indoormediastorage.com / mosspf.com / mosspf.net / lpsnmedia.net / imganalytics.com / analytics-sm.com / tmatrackapp.site | — | — | Unverified ownership; held out pending clearer ad-serving evidence |

Apexes covered by `ads-extended` already (lower curated value, not added this
pass): `rtbhouse`-class names plus `360yield.com`, `adcolony.com`,
`ads-twitter.com`, `kochava.com`, `revcontent.com`, `sonobi.com`, `eyeota.net`,
etc. — the extended feed already drops these for profiles that enable it.
