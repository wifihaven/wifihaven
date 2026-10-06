# Blocklist pass — multi-category classification evidence (#2836)

30-day prod `recent-apexes` sweep, all 30 devices, run 2026-10-06 (1,830
unique apexes observed). Also ran a serial `/api/logs` keyword sweep
(`porn`, `casino`, `bet`, `gambl`, `xxx`, `poker`, `hours=720`) per the
#2823 byte-truncation caveat — six of the 30 devices were at the
`recent-apexes` 500-row cap, with per-device byte floors from 17,865 to
639,981 bytes, so low-byte hosts could be invisible to the primary sweep.
The keyword sweep returned zero adult/gambling rows and only already-curated
`betrivers.com` subdomains + known false positives for `bet`.

Every candidate was checked against `evidence/*.md` history (not just the
current `.yml`) and against every curated category's host set before being
treated as a new gap.

## ads.yml — 5 additions

| apex | bytes | hits | what it is |
|---|---|---|---|
| `adnami.io` | 19,911 | 1 | Adnami ApS — Danish display/high-impact ad-tech vendor, founded 2017, $8.3M 2025 ARR (Crunchbase/ExchangeWire/Latka confirmed). |
| `adition.com` | 9,788 | 2 | ADITION technologies AG / Virtual Minds — German programmatic ad-serving/targeting platform, founded 2001, Düsseldorf (own site + Crunchbase confirmed). |
| `adnpbs.com` | 2,239,510 | 11 | No public company page; self-descriptive "ad network prebid server" name shape, same bar as `inhousedsp.com` (#2212). Resolves to dedicated AWS EC2 addresses (us-east-1), not a shared CDN pool — not disqualified by the #2823 CDN rule. |
| `adxflyer.com` | 32,975 | 4 | No public company page; self-descriptive "ADX" (ad-exchange) name, dedicated Tencent Cloud address. Independently corroborated: listed in hagezi/dns-blocklists as an ads/tracking domain (GitHub issue #6965). |
| `nexverse.ai` | 1,132 | 6 | Nexverse.ai — confirmed "AI-native AdTech platform" (VerSe Innovation subsidiary, Dubai), programmatic ad delivery. Another instance of the `.ai`-TLD-hides-an-ad-network trap (`axon.ai`/`programmaticx.ai`/`trygravity.ai`/`koah.ai`/`mediayo.ai`) — filed in `ads.yml`, not `ai.yml`, despite matching the `ai` sweep on TLD. Resolves to a GCP customer load-balancer IP (dedicated per-tenant LB, not Google's own shared-GFE product pool), so not disqualified by the CDN rule. |

### Held out / skipped this pass (ads)

- `adncdn.net` (614,314B/5 hits) — resolves to AWS CloudFront (`13.226.x`), structural CDN skip per #2823 regardless of name.
- `admbucket.com` (6,028,191B/6 hits) — resolves to a Tencent Cloud/`intlscdn.com` multi-tenant CDN, same structural skip.
- `synctrack.io` (21,304B/6 hits) — resolves to Cloudflare (`104.26.x`/`172.67.x`), structural skip despite a tempting cookie-sync-shaped name.
- `addtoany.com` (185,282B/20 hits) — AddToAny social-share-button widget, embedded on millions of unrelated sites (dual-use); also Cloudflare-fronted.
- `ad-score.com` (6,011,578B/176 hits) — re-confirmed ambiguous: at least three unrelated companies share the "AdScore" name (held out since #2742), no new evidence to resolve which owns this apex.
- `advolve.io` (9,151B/1 hit) — AI-marketing platform, ambiguous identity, held out since #2064, re-confirmed.
- `syncingbridge.com` (548,459B/41 hits) — search surfaced several unrelated "SyncBridge"-named companies (multi-device sync platform, employee-data integration tool) with no confirmation any is ad-tech; held out unverified.
- `adxvalidation.com` (33,783B/4 hits) — same self-descriptive "ADX" naming and dedicated Tencent Cloud hosting as `adxflyer.com`, but no independent corroborating source of its own; its only signal is a traffic shape similar to `adxflyer.com`'s, which comes from the same unconfirmed pull, not a second independent signal. Held out pending stronger confirmation (does not meet the two-independent-signals bar used for `axon.ai`/`trygravity.ai`-class additions).
- `adtrafficquality.google` (141,091,605B/3,022 hits) — resolves to `173.194.193.x`, the same Google anycast GFE range as `gstatic.com`/`google.com`. Structural skip: blocking it risks dropping unrelated Google product traffic, the same IP-layer-collateral reason `SharedGfeHosts` (`shared/src/types/SharedGfeHosts.scala`) exists — though this specific `.google` gTLD apex isn't itself in that hostname list.
- `google-analytics.com`, `app-ads-services.com`, `app-analytics-services.com`, `merchant-center-analytics.goog`, `adobe.com`/`adobe.io`/`adobedc.net`/`adobedtm.com`/`adobelogin.com`/`adoberesources.net`, `popcorn-tracker.org` (BT tracker), `tagsrvcs.com` (HUMAN Security) — standing dual-use/false-positive skips, re-confirmed, no change.

## ai.yml — 1 addition

| apex | bytes | hits | what it is |
|---|---|---|---|
| `hellohaven.ai` | 1,365,942 | 4 | Hello Haven — confirmed consumer personal-AI "digital twin" assistant app (Frank Addante/Duc Chau, Park City UT, $15M pre-seed, launched Sept 2026). Persistent personal AI that triages inbox, drafts briefings, places orders/calls on the user's behalf — squarely a consumer AI-assistant product. |

### Held out / skipped this pass (ai)

- `typesafe.ai` (confirmed company, TypeSafe AI Inc — but its product is a hosted model-API for machine-native decisioning aimed at developers/automation pipelines, not a consumer chatbot/assistant/generator) — out of `ai.yml` scope, same reasoning as excluding `opencode.ai` (#2729).
- `verysane.ai` — no identity confirmation found, held out unverified.
- `loudecho.ai` — Cloudflare-fronted (`104.21.x`/`172.67.x`), structural CDN skip.
- `axon.ai`, `programmaticx.ai`, `bidsystem.ai`, `delivrdsp.ai`, `theagenticx.ai` — already curated in `ads.yml` (or, for `delivrdsp.ai`, a new self-descriptive-DSP-name match not yet curated anywhere but held out this pass pending stronger confirmation — single self-descriptive signal, 13,081B/1 hit, below the two-signal bar used for `inhousedsp.com`).
- `dxtech.ai`, `kapa.ai`, `castify.ai`, `ivy.ai`, `flux.ai`, `learnings.ai`, `higgs.ai` — standing B2B/dual-use/false-positive exclusions, re-confirmed, no new evidence to reverse.
- `claude.ai`, `claude.com`, `anthropic.com`, `claudeusercontent.com`, `x.ai`, `openai.com` — already curated.

## games.yml — 2 additions

| apex | bytes | hits | what it is |
|---|---|---|---|
| `teamwoodgames.com` | 2,170,825 | 9 | Team Wood Games (Copenhagen, Denmark) — developer of Super Auto Pets, an auto-battler with 4M+ installs across Steam/iOS/Android/macOS. |
| `teamwood.games` | 285,697 | 21 | Same studio, sibling apex (confirmed via search as their social-media handle domain and company presence). |

### Held out / skipped this pass (games)

- `livesteamstation.com` (22,159,625B/2 hits) — matched the "steam" substring sweep, but is an unrelated model-train hobby store ("Live Steam" locomotives), not Valve's Steam platform. False positive, no action.
- `steampowered.com`, `store.steampowered.com`, `steamstatic.com`, `steamcommunity.com`, `steamcontent.com`, `eaglercraft.com`/`.ru`/`.dev`, `eaglercraftgame.io`, `epicgameshubham.com` (ads.yml, not games), `wherewindsmeetgame.com` — already curated.

## adult.yml / gambling.yml / social-media.yml — no additions

- **adult**: `cam4.com` (13,099,696B/227 hits) already curated. `cam4tracking.com` re-surfaced (506,959B/3 hits) — already investigated and deliberately excluded per #2823 (CloudFront-fronted, see that file's dated comment); no new evidence to reverse. No other adult-shaped apex observed; keyword sweep (`porn`/`xxx`) returned zero rows.
- **gambling**: zero apexes matched any gambling keyword in either the `recent-apexes` ranked list or the `/api/logs` keyword sweep this window. `betrivers.com` (already curated) appeared in the keyword sweep only. `betweendigital.com` (664B/3 hits, a Moscow SSP, standing false-positive per #2503) re-confirmed, no action.
- **social-media**: every social-media-shaped apex observed (`instagram.com`, `linkedin.com`, `facebook.com`/`.net`, `whatsapp.net`/`.com`, `twitter.com`, `reddit.com`, `tiktok.com`, `tumblr.com`, `snapchat.com`, `pinterest.com`) is already curated. `ads-twitter.com` re-surfaced via the sweep but is already correctly filed in `ads.yml` (X's ad-conversion pixel, not content).

A zero-addition outcome for adult/gambling/social-media this pass is consistent with prior passes' documented precedent (#2503, #2212) — not a search failure.
