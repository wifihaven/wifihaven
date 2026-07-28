# ads-blocklist pass — 2026-07-28 (#2503)

Traffic-driven blocklist pass via the `/blocklist-pass` skill (weekly automated
run). This file covers the **ads** category; adult / ai / gambling / games /
social-media findings are in `misc-classification-2503.md`.

## Method

- Pulled `GET /api/devices/<mac>/recent-apexes?windowDays=30&limit=500` for all
  26 prod devices (read-only, admin token). Aggregated bytes + hits per apex
  across devices — **1,636 distinct apexes** with traffic this window.
- Classified each apex by what it *is* (vendor identity), not by a substring
  match. Cross-checked every candidate against the full curated `ads.yml`
  (apex + suffix match) through the #2348 additions, plus the StevenBlack
  `ads-extended` feed as a lower-priority cross-check.
- Web-verified ownership for every ambiguous apex via WebSearch before adding.

IPv4-bias caveat (#1796) applies — byte totals under-count IPv6-heavy flows.

## Added apexes (14)

| apex | bytes | hits | what it is |
|------|------:|-----:|------------|
| oms.live | 1.7M | 178 | Online Media Solutions (OMS) — SSP/ad-serving, part of Brightcom Group; same vendor as already-curated `omsrtb.com`, different apex |
| cxense.com | 2.1M | 155 | Cxense, a Piano company — data management platform (DMP) for publisher targeted-advertising/personalization |
| inhousedsp.com | 6.4M | 159 | Self-descriptive "in-house DSP" name; already present in the StevenBlack `ads-extended` feed as `content.inhousedsp.com`, corroborating ad/tracking function even without a confirmed company name (#2348 held this out for lack of company-name confirmation — the name-is-function + external-feed corroboration together clears that bar this pass) |
| feathr.co | 1.3M | 114 | Feathr — nonprofit/event marketing platform whose core product is paid sponsored-retargeting advertising |
| freedomadnetwork.com | 1.0M | 102 | Freedom Ad Network — self-service + managed ad network (Prebid header-bidding adapter) |
| admanmedia.com | 1.8M | 73 | ADman Media — video SSP (Barcelona), acquired by AcuityAds 2018 |
| merequartz.com | 555K | 69 | Confirmed anti-adblock-circumvention infra serving Admiral popup scripts under an obscured domain (registered by Leven Labs) — directly enables ad delivery by defeating adblockers |
| getadmiral.com | 761K | 61 | Admiral — adblock-recovery/revenue-recovery platform; its core function is detecting and circumventing user adblockers so ads display |
| inspectlet.com | 269K | 61 | Session-recording/heatmap/behavioral-tracking tool — same class as already-curated `hotjar.com`/`mouseflow.com` |
| adsco.re | 274K | 18 | Self-descriptive ad-tech name (`ads.co.re`); already in `ads-extended` feed as `4.adsco.re` |
| dataseat.com | 180K | 86 | Dataseat — contextual mobile ad DSP, acquired by Verve Group |
| adsmovil.com | 521B | 2 | AdsMovil — US Hispanic-market mobile ad network; already in `ads-extended` feed as `atr.adsmovil.com` |
| tru-bid.com | 40K | 2 | Self-descriptive RTB-bidder name (name-is-function precedent) |
| adscale.de | 9.3K | 1 | Adscale GmbH — German ad network; already in `ads-extended` feed at the apex |

## Investigated and rejected / reverted — do NOT re-add

- **xlgmedia.com** (4.7M bytes, 212 hits) — matched the "named ad company"
  pattern (XLMedia, sports/gaming performance publisher) and was initially
  drafted as an add, but #2348's evidence doc already tried this exact apex
  and reverted it: XLMedia's business is substantially gambling-affiliate
  **content** publishing across 2,000+ owned properties, not dedicated ad
  serving — the observed subdomain is a shared script host across that
  publisher network (content-collateral, same class as `target-video.com`).
  Confirmed the #2348 judgment still holds; not re-added.
- **betweendigital.com** — already curated in `ads.yml` (added #2348, under
  the RTB-name-pattern section, after being reclassified from an initial
  gambling-substring false-positive). Re-confirmed present; not duplicated.
- **px-cloud.net** (1.5M bytes, 79 hits) — confirmed PerimeterX/HUMAN
  Security bot-mitigation infra via web search (same vendor as the already-
  skipped `script.ac`/`protechts.net`/`tagsrvcs.com`). Dual-use bot-mitigation
  deployed broadly on login/checkout flows, not ad-serving. Skipped.
- **adit.com** (8.8M bytes, 96 hits) — matched an `ad`-prefix heuristic but is
  Adit, an AI-powered dental/healthcare practice-management SaaS. Wrong
  category entirely (false positive, not ads). Skipped.
- **glance.net** (319K bytes, 62 hits) — Glance CX, a co-browsing/screen-share
  customer-support platform. Not ad-serving; dual-use CX tool. Skipped.
- **viafoura.co** (735K bytes, 91 hits) — Viafoura CommunityOS, an audience-
  engagement/commenting platform for publishers (same class as Disqus, which
  is also not curated as ads despite heavy traffic). Skipped.

## Held out — UNVERIFIED this run (do NOT re-add from the name alone)

- `str-nrg.com`, `gt162037.com`, `qvdt3feo.com`, `tq-tungsten.com`,
  `qwadro.com`, `ntnltech.com`, `ogyfmts.com` (suggestive of Ogury but no
  direct confirmation), `yellowblue.io` (one thin/uncorroborated source
  calling it a "bitcoin advertising network" — not enough to add on), `ad.gt`
  (general domain-redirect-ads context but no identity confirmation), `betrad.com`
  (possible truncated/CDN variant of Betradar/Sportradar sports-data B2B infra,
  or a standalone gambling-adjacent tracker — ambiguous, carried forward from
  #2348), `krautmtrk.com`, `lazybumblebee.com` — all carried forward from
  #2348's held-out list; still unidentifiable this run. Do not guess from the
  name alone (the ttdns2.com/youngle.tech traps).
