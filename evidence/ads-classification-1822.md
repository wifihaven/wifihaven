# Ads-blocklist traffic-driven pass — classification evidence (#1822)

Sibling of the #1815 app-catalog pass. This documents the additions to
`api/resources/blocklists/ads.yml`.

## Method

- **Source:** `GET /api/devices/{mac}/recent-apexes?windowDays=30&limit=500`
  swept across all 25 prod devices on api.wifihaven.net (2026-06-21,
  read-only), aggregated by apex (summing bytes + hits across devices).
- **Caveat (IPv4-biased sample):** prod IPv6 per-device host attribution is
  broken (#1796), so traffic volumes here under-count IPv6. Candidates were
  therefore cross-checked against public ad-tech sources rather than inferring
  "no traffic ⇒ not ad-tech" from a quiet apex.
- **Inclusion rule:** an apex was added only if it is **unambiguous ad-tech**
  (mobile ad network / SSP / DSP / exchange / RTB bidder / ad verification /
  identity-resolution / attribution-measurement), is **not already** in
  `ads.yml`, carries **no shared-product-infra collateral**, and clears a
  **high-traffic floor** (≥ ~1 MB bytes *or* ≥ 250 hits in the 30-day window).
- **Enforcement note:** the router suffix-matches subdomains, so only apexes
  are listed. Per WifiHaven's architecture an `ads` blocklist entry enforces
  via per-`(MAC, blocklistId)` nftables IP drops on the resolved IPs — it is
  **not** a DNS sinkhole. These hosts will still resolve; their resolved IPs
  are what gets dropped when the list is enabled on a profile.

## Added apexes (45)

| apex | 30d bytes | 30d hits | what it is | why it was a gap |
|---|---:|---:|---|---|
| applovin.com | 643.3 MB | 2662 | AppLovin mobile ad network / MAX mediation | top-traffic mobile ad SDK, not in curated list |
| liftoff-creatives.io | 426.2 MB | 416 | Liftoff creative/ad delivery | Liftoff growth-ads infra uncovered |
| adsmoloco.com | 351.9 MB | 357 | Moloco ad-serving CDN | Moloco programmatic mobile ads uncovered |
| flashtalking.com | 124.5 MB | 388 | Flashtalking (Mediaocean) ad serving | major creative ad server uncovered |
| vungle.com | 76.4 MB | 1357 | Vungle (Liftoff) in-app video ads | high-hit mobile video ad network |
| innovid.com | 67.2 MB | 149 | Innovid video ad serving/measurement | CTV/video ad server uncovered |
| primis.tech | 57.3 MB | 299 | Primis video-discovery / RTB ad platform | verified ad-tech (martech/ExchangeWire) |
| inmobicdn.net | 35.5 MB | 477 | InMobi ad CDN | InMobi delivery host, sibling of inmobi.com |
| tiktokpangle-cdn-us.com | 26.3 MB | 403 | Pangle (ByteDance) ad CDN | Pangle ad-network CDN uncovered |
| inmobi.com | 25.6 MB | 1461 | InMobi mobile ad network | high-hit ad network uncovered |
| liftoff.io | 23.2 MB | 555 | Liftoff mobile ad network | apex sibling of liftoff-creatives.io |
| moloco.com | 18.6 MB | 799 | Moloco DSP | Moloco programmatic apex uncovered |
| doubleverify.com | 17.8 MB | 636 | DoubleVerify ad verification | verification/measurement tracker uncovered |
| chartboost.com | 15.8 MB | 360 | Chartboost mobile-game ad network | game ad SDK uncovered |
| tiktokpangle.us | 14.5 MB | 874 | Pangle (ByteDance) ad network | Pangle RTB apex uncovered |
| 3lift.com | 11.2 MB | 642 | TripleLift SSP | exchange/SSP uncovered |
| media.net | 10.4 MB | 871 | Media.net contextual ad network | contextual ad network uncovered |
| connatix.com | 10.1 MB | 119 | Connatix video ad platform | video SSP uncovered |
| smadex.com | 9.5 MB | 196 | Smadex (Entravision) DSP | mobile DSP uncovered |
| adsafeprotected.com | 9.5 MB | 249 | Integral Ad Science (IAS) verification | ad-verification tracker uncovered |
| sharethrough.com | 7.5 MB | 349 | Sharethrough SSP | native/exchange SSP uncovered |
| mintegral.com | 6.5 MB | 16 | Mintegral (Mobvista) mobile ad network | mobile ad SDK uncovered |
| pubnative.net | 6.1 MB | 304 | PubNative (Verve) SSP | mobile SSP uncovered |
| kueezrtb.com | 6.1 MB | 158 | KueezRTB header-bidding adaptor | verified RTB bidder (Prebid docs) |
| onetag-sys.com | 4.7 MB | 282 | OneTag programmatic exchange | SSP/exchange uncovered |
| seedtag.com | 4.6 MB | 414 | Seedtag contextual ads | contextual ad network uncovered |
| singular.net | 4.3 MB | 398 | Singular mobile attribution | attribution/measurement uncovered |
| fyber.com | 4.0 MB | 413 | Fyber (Digital Turbine) SSP | mobile SSP uncovered |
| kargo.com | 3.5 MB | 127 | Kargo mobile ad marketplace | mobile ad SSP uncovered |
| lijit.com | 3.3 MB | 522 | Sovrn (Lijit) ad exchange | exchange uncovered |
| adjust.com | 3.0 MB | 263 | Adjust mobile attribution | attribution SDK (peer of branch.io, already listed) |
| rlcdn.com | 2.8 MB | 495 | LiveRamp (RampID) identity | identity-resolution data broker uncovered |
| richaudience.com | 2.6 MB | 228 | Rich Audience SSP | SSP uncovered |
| 33across.com | 2.5 MB | 257 | 33Across SSP / identity | SSP + identity uncovered |
| stackadapt.com | 2.2 MB | 309 | StackAdapt DSP | programmatic DSP uncovered |
| appsflyer.com | 2.1 MB | 203 | AppsFlyer mobile attribution | attribution SDK uncovered |
| servenobid.com | 2.0 MB | 213 | ServeNoBid programmatic RTB infra | verified RTB platform |
| liadm.com | 1.6 MB | 379 | LiveIntent identity | identity-resolution uncovered |
| gumgum.com | 1.4 MB | 204 | GumGum contextual ads | contextual ad network uncovered |
| agkn.com | 1.3 MB | 332 | Neustar AdAdvisor identity | data-broker tracker uncovered |
| smaato.net | 1.2 MB | 361 | Smaato mobile SSP | mobile SSP uncovered |
| teads.tv | 1.2 MB | 105 | Teads outstream video ads | video ad network uncovered |
| tapad.com | 1.1 MB | 257 | Tapad (Experian) cross-device identity | identity-resolution uncovered |
| contextweb.com | 1.0 MB | 179 | PulsePoint (ContextWeb) exchange | exchange uncovered |
| adform.net | 0.8 MB | 280 | Adform DSP / ad serving | DSP uncovered (cleared via ≥250 hits) |

## Deliberately NOT added

**Shared / dual-use infra (collateral rule — would drag non-ad product traffic
into the drop):** `app-analytics-services.com` (Google Android app telemetry),
the `*.amazon` ad/telemetry hosts (`adkit-advertising.amazon`,
`paa-reporting-advertising.amazon`, `bdtelemetry.amazon` — share Amazon device
infra). `google-analytics.com` / `googleadservices.com` etc. are already in
`ads.yml`.

**Not ad-tech (misclassified by keyword match):** `grandgamestech.com` (Grand
Games — a mobile-game publisher's own first-party domain, not an ad network),
`youngle.tech` (tech career/education site), `scriptwrapper.com` (could not be
confidently classified as ad-tech).

**Below the high-traffic floor (genuine ad-tech, observed, but < ~1 MB and
< 250 hits — left for the comprehensive `ads-extended` StevenBlack feed rather
than hand-curated here):** `mfadsrvr.com` (BidSwitch, 780 KB/87h),
`mgid.com`, `360yield.com`, `sitescout.com`, `pippio.com`, `revcontent.com`,
`crwdcntrl.net`, `aniview.com`, `indexww.com`, `emxdgt.com`, `eyeota.net`,
`everesttech.net`, `demdex.net`, `adcolony.com`, `supersonicads.com`,
`adition.com`, `stickyadstv.com`, `colossusssp.com`, `amxrtb.com`,
`kochava.com`, `zemanta.com`, `tribalfusion.com`, `exelator.com`,
`outbrain.org`/`outbrainimg.com`, `chartbeat.net`, `tiktokpangle-b.us`.

> If the operator wants broader coverage than this curated high-traffic set,
> the right lever is the URL-sourced `ads-extended` (StevenBlack/hosts) list —
> hand-maintaining the full long tail in `ads.yml` is explicitly not the goal.
