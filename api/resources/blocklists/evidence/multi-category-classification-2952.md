# Blocklist pass — multi-category classification evidence (#2952)

30-day prod `recent-apexes` sweep, all 30 devices, run 2026-10-27 (1,692
unique apexes observed, 1,472 not already covered by any curated category's
exact-match host set). Also ran a serial `/api/logs` keyword sweep per the
#2823 byte-truncation caveat: ads keywords (`ad`, `ads`, `bid`, `rtb`, `ssp`,
`dsp`, `track`, `sync`, `pixel`, `analytics`, `beacon`, `affiliate`,
`monetiz`, `hours=720`) and adult keywords (`porn`, `xxx`, `cam4`,
`onlyfans`, `nsfw`, `adult`, `hours=720`).

Every candidate was checked against `evidence/*.md` history (not just the
current `.yml`) and against every curated category's host set before being
treated as a new gap. The mandatory observed-host CDN check (#2823/#2911/
#2918) was run on every addition and every plausible held-out candidate —
`dig` on each observed host, cross-referenced against Cloudflare's published
IPv4 ranges and the AWS ip-ranges.json feed, with whois as a tiebreaker for
ambiguous allocations.

## ads.yml — 3 additions

| apex | bytes | hits | what it is |
|---|---|---|---|
| `stickyadstv.com` | 1,135,987 | 5 | Comcast FreeWheel's StickyADS.tv (AdExchanger/streamtvinsider coverage of the 2016 acquisition) — a video SSP for premium publishers. Only observed host, `ads.stickyadstv.com`, answers on a dedicated Cogent Communications block (38.134.110.235-236), not a shared CDN. **RESIDUAL RISK**: the unobserved sibling `cdn.stickyadstv.com` CNAMEs onto CloudFront (`dpr1wbpfzrke3.cloudfront.net`). Pinned in the `.yml` comment; remove the entry and pin its absence if that sibling is ever observed. |
| `fout.jp` | — | — | FreakOut Holdings, Inc. — confirmed via whois netname `FOUT-NET` on the observed `sync.fout.jp` address (202.232.238.x), after a plain websearch misleadingly suggested an unrelated "Fout Co., Ltd." IT-services reading. A second observed host, `sync.rfp.fout.jp`, is on a GCP customer load-balancer address (34.49.109.x) — a normal single-tenant deployment, not Google's own shared-GFE product frontend (the nexverse.ai precedent, #2836). Corroborated by `ad.fout.jp`/`ad.rfp.fout.jp`/several others in the ads-extended feed. No CDN-fronted sibling found among the usual candidates. |
| `appier.net` | 13,443 (a.c.appier.net) | 2 | Appier — confirmed AI-driven marketing/advertising company (Netify: cross-site tracking cookies on publisher + advertiser sites). Observed hosts resolve via Akamai's GTM/DNS traffic-steering product (`*.akadns.net`, which returns the customer's own backend IPs) onto Appier's own dedicated compute across GCP/Linode/OVH — distinct from Akamai's shared EdgeSuite CDN product, which fronted the loopme.me held-out below. **RESIDUAL RISK**: the unobserved sibling `static.appier.net` DOES CNAME onto that same shared EdgeSuite product (`edgesuite.net` -> `a1017.g.akamai.net`). Pinned in the `.yml` comment; remove the entry and pin its absence if that sibling is ever observed. |

### Held out / skipped this pass (ads)

- `adinplay.com` (1,135,987B/5 hits) — confirmed Venatus-owned HTML5/browser-game ad network (own site + CBInsights); only observed host `api.adinplay.com` is Cloudflare-fronted (104.26.x/172.67.x), unconditional #2823 skip.
- `loopme.me` (—) — confirmed LoopMe, a London AdTech company that acquired the already-curated Chartboost in Dec 2024. One observed host (`csync.loopme.me`) is on dedicated GCP infra, but a second (`i.loopme.me`) CNAMEs onto Akamai's shared EdgeSuite CDN (`edgesuite.net`/`akamai.net`) — per the skmads.com rule (#2918), any CDN-fronted observed host disqualifies the whole apex.
- `dxtech.ai` (653,032B/76 hits) — previously held out of `ai.yml` as B2B IT-consulting (#2792). Its cookie-sync-shaped observed host here, `sync.dxtech.ai`, is independently Cloudflare-fronted (104.21.x/172.67.x) — an unconditional #2823 skip regardless of the identity question.
- `gd.games` (2,737,696B/19 hits) — confirmed GameDistribution, Azerion's HTML5 game-syndication network (a games.yml-shaped candidate, not ads). Cloudflare-fronted (104.26.x/172.67.x) on its only observed host; held out of games.yml too.
- `suno.ai` (877,301B/1 hit) — confirmed, well-known consumer AI music generator (an ai.yml-shaped candidate). CloudFront-fronted on its only observed host, `cdn2.suno.ai` (CNAME to a `cloudfront.net` distribution whose edge IPs, 99.84.118.x, are shared with other tenants); held out of ai.yml despite the strong, unambiguous identity.
- `smilewanted.com`, `rmbl.ws`, `cognitivlabs.com` — no confirmed identity. A plausible name-match to Rumble (`rmbl.ws`) and to Cognitiv (`cognitivlabs.com`) could not be confirmed; these read as different companies/domains on inspection, not the same entity.
- `samplicio.us` — reads as Dynata-style survey-sample routing infra; Dynata frames itself as a market-research/first-party-data company, not an ad-serving network — closer to a dual-use case than a confirmed ads apex. Only 1 hit.
- `adncdn.net` (787,964B/4 hits) — no confirmed identity beyond generic "content delivery" domain-lookup guesses; AWS-hosted but not clearly CDN-shared or ad-specific.
- `ad-score.com`, `syncingbridge.com`, `adgrx.com`, `adtech.ink` — standing low-signal holds (ad-score.com ambiguous since #2742; syncingbridge.com unconfirmed since #2836), re-confirmed, no new evidence either way.
- `app-ads-services.com`, `adeept.com`, `synctrack.io`, `addtoany.com`, `adsensecustomsearchads.com`, `adobe.com`/`adobe.io`/`adobedc.net`/`adobedtm.com`, `trackcmp.net`, `trackjs.com`, `app-analytics-services.com`, `www.google-analytics.com`, `www.googleadservices.com`, `khanacademy.org`, `freebeacon.com`, `vimeocdn.com`, the Apple/Spotify ad-subdomain family (apex-sharing problem, same as the standing Apple iAd exclusion), Google's `gvt2.com`/`gvt3.com`/`doubleclick.net`/`clients6.google.com` shared pools, `*.run.app` (Google Cloud Run, a shared multi-tenant subdomain), `datadoghq.com` family, `hallow.com`, `plex.tv`, `shopifysvc.com` — all standing dual-use/false-positive/shared-infra skips, re-confirmed, no change. BitTorrent-tracker decoys (`desync.com`, `bittor.pw`, `coppersurfer.tk`, `leechers-paradise.org`, `openbittorrent.com`, `opentrackr.org`, `torrent.eu.org`, `cyberia.is`, `internetwarriors.net`, `popcorn-tracker.org`) also re-confirmed, not ad infra.

### Residual-risk watchlist re-check

Re-ran the #2823 observed-host check for both standing `ads.yml` residual-risk
notes: `cdn.bttrack.com`/`video.bttrack.com` (only the bare `bttrack.com` apex
was observed, consistent with the existing note — no change) and the bare
`loudecho.ai` apex (zero rows in this pass's 90-day `/api/logs` window — no
change either way). Neither trigger fired.

## ai.yml — 0 additions

High-traffic `.ai`-TLD sweep (all uncovered `.ai` apexes in the ranked list):
`learnings.ai`, `sierra.ai`, `wandb.ai`, `modernintelligence.ai`, `ivy.ai`,
`suno.ai`, `dxtech.ai`, `trinitymedia.ai`, `thecon.ai`, `castify.ai`,
`nrich.ai`.

- `suno.ai` — see the ads.yml held-out note above (CloudFront-fronted).
- `ivy.ai` — confirmed B2B conversational-AI chatbot platform for higher-ed/
  healthcare/government (own-site-adjacent vendor directories: SHRM,
  Capterra). Out of this category's consumer-product scope (#2792 precedent).
- `sierra.ai` — confirmed B2B enterprise customer-service AI (Bret Taylor's
  startup, TechCrunch coverage of its $4.5B valuation round). Same
  out-of-scope reasoning as `ivy.ai`.
- `wandb.ai` — Weights & Biases, an MLOps/experiment-tracking tool for ML
  engineers. Developer tooling, out of scope (the opencode.ai precedent,
  #2729).
- `modernintelligence.ai` — a defense-sector AI company (Cutlass maritime
  surveillance system). Not a consumer product at all.
- `learnings.ai` — 139,334,035 bytes / 4,402 hits, the single highest-traffic
  unidentified apex this pass. Observed hosts (`api.learnings.ai`,
  `hermes-api.learnings.ai`, `api-abtest-cdn.learnings.ai`) suggest a mobile
  app backend with A/B-testing infra, consistent with a consumer app, but no
  websearch or direct site fetch (the homepage renders only the word
  "Learnings" with no JS-rendered content reachable via WebFetch) confirmed a
  specific product or company — an App Store developer page exists
  ("Learnings.AI", id1437733852) but lists no discoverable apps. Held out
  unverified per the don't-guess-from-the-name discipline; worth a fresh
  attempt next pass (e.g. a mobile-app-store search by the exact developer
  name, or inspecting the app directly if the household's own device can be
  identified).
- `nrich.ai` — sources disagree between "AI personalization platform" and a
  European account-based-marketing (ABM) product; either way it's B2B
  martech, not a consumer AI product. Low traffic (8 hits).
- `dxtech.ai`, `trinitymedia.ai`, `thecon.ai`, `castify.ai` — standing
  out-of-scope/dual-use skips (#2792, #2729), re-confirmed, no new evidence.

## gambling.yml — 0 additions

- `betgenius.com` (1,597,875B/2 hits) — confirmed Genius Sports' B2B
  sports-betting data/technology brand (Wikipedia, Dealroom, Genius Sports'
  own NYSE filings) — sells odds feeds, pricing, and risk-management
  software TO licensed sportsbooks; not itself a consumer-facing betting
  site. Blocking it would not stop consumer gambling (the sportsbook is a
  separate apex) and risks collateral on unrelated sports-score apps that
  embed the same data feed. Held out as out-of-scope B2B infra.

## games.yml — 0 additions

- `gd.games` — see the ads.yml held-out note above (Cloudflare-fronted).
- `gamerhunk.com` (6,583,979B/8 hits) — no confirmed identity; websearch
  surfaced only unrelated similarly-named gaming-content sites. Held out
  unverified.
- `gameanalytics.com` (2,915,564B/326 hits) — re-confirmed standing dual-use
  skip (#2759): a first-party SDK game developers embed in their own titles,
  not third-party ad infra.

## social-media.yml — 0 additions

- `gorgias.chat` (1,512,420B/81 hits) — confirmed Gorgias, an e-commerce AI
  helpdesk/live-chat widget (Shopify app listing) — a customer-support tool
  sites embed, not a social network itself. Same dual-use class as other
  embedded chat/support SDKs.
- `snapkit.com` (3,631,323B/147 hits), `lazybumblebee.com` (3,145,331B/227
  hits) — re-confirmed standing skips (#2503, #2756): Snap's own developer
  SDK and an unrelated lifestyle blog, respectively. No new evidence either
  way.

## adult.yml — 0 additions

Keyword sweep (`porn`, `xxx`, `cam4`, `onlyfans`, `nsfw`, `adult`, `hours=720`)
returned only already-curated `cam4.com` subdomains (`api.`, `logger007.`,
`static.`) plus `cam4-campaigns.xcdnpro.com`, a different apex that CNAMEs
onto a shared CDN (`xcdnpro.com` -> `sds.rncdn7.com`, a multi-tenant
CDN77-class pool, 8 addresses in 64.210.147.x) — held out per the #2823 rule.
A zero-gap pass is a valid, expected outcome for this category (precedent:
#2503, #2212).
