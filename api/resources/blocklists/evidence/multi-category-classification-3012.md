# Weekly blocklist pass — #3012 (2026-11-17)

30-day prod `recent-apexes` sweep across all 31 devices, aggregated by apex
across devices (1,953 distinct apexes). Keyword-swept the full aggregated
list per category (ads/ai/gambling/adult/games/social-media), then
cross-checked every candidate against all six curated host sets (not just
the one the sweep bucketed it into), the three StevenBlack `-extended`
feeds, and the full file contents (comments included) + prior evidence docs
before treating anything as a "new" gap — several sweep hits that looked
like gaps on a `hosts:`-only membership check were already documented
held-outs/skips in comments (`ebayadservices.com`, `hs-analytics.net`,
`siteimproveanalytics.com`, `bounceexchange.com`, `supersonicads.com`,
`skmads.com`, `desync.com`, `trackcmp.net`, `advolve.io`, `imganalytics.com`,
`heapanalytics.com`, `app-ads-services.com`, `freebeacon.com`,
`opentrackr.org`, `popcorn-tracker.org`, `synctrack.io`, among others).

Also ran a serial `/api/logs` keyword sweep (`porn`, `xxx`, `cam4`,
`xvideo`, `xnxx`, `onlyfans`, `chaturbat`, `xhamster`, `hentai`) over the
30-day window for the `adult` category, per the #2823 two-surface rule —
`recent-apexes` alone under-counts low-byte adult traffic. Only `cam4`
returned rows (22), and `cam4.com` is already curated.

The household's traffic this run was again developer/infra-heavy (top
bytes: `googlevideo.com`, `apple.com`, `githubusercontent.com`,
`google.com`, `googleapis.com`, `wifihaven.net`, `alpinelinux.org`,
`cdn-apple.com`, `claude.ai`, …) — consistent with prior passes; the
category gaps below were found by keyword-filtering the full ranked list,
not by eyeballing the top of it.

## Residual-risk watchlist re-check

Grepped `RESIDUAL RISK` in `ads.yml`: `cdn.bttrack.com`, `video.bttrack.com`,
`cdn.stickyadstv.com`, and `cdn.playablefactory.app` — none observed this
pass (`/api/logs` returned zero rows for all four over the 90-day window
checked). `loudecho.ai`'s own observed host, `cs.loudecho.ai`, was
re-observed (no new siblings). No removals.

## Added

### ads.yml

Five genuine gaps, all with the observed-host CDN check (Step 2.3) and the
unobserved-sibling check (Step 2.4) run per addition. Per-apex detail is in
the `ads.yml` comment itself; summary table:

| Apex | Identity | Observed host | Resolves to | Verdict |
|---|---|---|---|---|
| `impactradius-event.com` | Impact.com (fka Impact Radius) conversion-event tracking | `d.impactradius-event.com` | GCP (`GOOGLE-CLOUD`), apex+siblings all same dedicated address | clean |
| `cadent.com` | Cadent, CTV/addressable-TV ad platform (fka AdTheorent) | `pcs.ssp.cadent.com` | GCP (`GOOGL-2`), dedicated | **residual risk**: `www.` CNAMEs onto `cdn.webflow.com` |
| `rtb-stellormedia.com` | self-descriptive RTB name; "Stellor Media" per Netify catalog (no further corroboration) | `tracker.rtb-stellormedia.com` | CNAME → `tracker-use.ortb.net` (already-curated RTB apex) → Cogent Communications, dedicated | clean |
| `sportradarserving.com` | Sportradar's own ad-serving/SSP-cookie-sync domain (confirmed via its cookie disclosure) | `a.sportradarserving.com` | CNAME → iponweb.net → GCP (`GOOGLE-CLOUD`), dedicated | clean |
| `adotmob.com` | Adotmob/Adot (A.Mob), French mobile ad-tech | `sync.adotmob.com` | RIPE-assigned (Veepee netname, not a CDN) | clean; apex confirmed EC2 (not CloudFront) via `ip-ranges.amazonaws.com` |

All five are absent from `ads-extended` at the apex (`impactradius-event.com`
and `adotmob.com` have feed presence at other subdomains only; the other
three have none).

### adult.yml / ai.yml / gambling.yml / games.yml / social-media.yml

No additions. See "Held out" / "False positives" below for what was
checked and ruled out.

## Held out (CDN-fronted on the observed host — #2823/#2918 rule)

- **`aditude.io`** (ads candidate) — confirmed genuine, $15M-funded
  publisher ad-ops/header-bidding platform. Both observed hosts,
  `edge.aditude.io` and `raven-static.aditude.io`, resolve to
  `104.18.14.29`/`104.18.15.29` — Cloudflare. Held out despite the clean
  identity match, same pattern as `suno.ai`/`gd.games` (#2952).
- **`content-loader.com`** (ads candidate) — per an unverified EduGeek
  forum post corroborated by an AdGuard blog post describing the exact
  anti-adblock-recovery mechanism, apparently part of Ad-Shield's
  anti-adblock system (same function class as the `getadmiral.com`
  precedent, #2503). Every observed host (bare apex, `stg.`, `0.`,
  `0.stg.`, `3.stg.`, `4.stg.`, `5.stg.`, `6.stg.`, `7.stg.`, `7.v.`, `8.v.`)
  resolves to `104.18.6.55`/`104.18.7.55` — Cloudflare. Held out; not
  pinned absent in the spec since the identity itself is one unverified
  forum post, weaker corroboration than the other pinned entries.
- **`adingo.jp`** (ads candidate) — a real Japanese ad network/DSP-exchange
  partner (adingo, Inc.) per a 2013 press release. Observed host
  `cs.adingo.jp` resolves to `99.84.105.x` — CloudFront (confirmed
  CloudFront range per the #2918 `ip-ranges.json` precedent). Held out.
- **`pixad.com.tr`** (ads candidate) — a Turkish programmatic/
  performance-marketing agency per Netify. Observed host
  `prebid.pixad.com.tr` resolves to `104.26.7.88`/`104.26.6.88`/
  `172.67.74.147` — Cloudflare. Held out.
- **`remio.ai`** (ai candidate) — a genuine consumer AI product (an
  AI-powered personal-knowledge/note-taking app with a local-first desktop
  component and an iPhone companion app — squarely in `ai.yml`'s scope).
  Zero `/api/logs` hits over the 90-day window (the #2957
  "never-observed-but-in-recent-apexes" pattern); the only subdomain in the
  raw `recent-apexes` payload, `www.remio.ai`, CNAMEs through
  `pointing.wixdns.net` (Wix, a shared multi-tenant site builder) onto
  Cloudflare (`162.159.143.12`, `172.66.3.8`). Double shared-platform
  exposure; held out. Low value anyway (278KB/7 hits).

## Held out (no confirmed identity or ambiguous shared infra, low traffic)

- **`adtarget.com.tr`** (ads candidate) — confirmed genuine Turkish
  RTB/programmatic company (own privacy policy names a CEO and describes
  IAB-TCF-compliant programmatic participation). But its one observed
  host, `s.console.adtarget.com.tr`, geo-routes through
  `s-unoadsrv-com.uno-geo-dns.com` across ~70 IPs spanning a dozen
  unrelated hosting netblocks (`23.227.x`, `192.155.x`, `64.202.x`,
  `98.142.x`, `172.240.x`, `107.151.x`, `152.232.x`, `205.234.x`,
  `162.254.x`, `96.46.x`, `173.201.x`, `209.192.x`) — too ambiguous a
  geo-DNS pattern to confidently clear or fail the CDN check either way.
  Only 1 hit this pass; carried forward rather than guessed.
- **`innovative-tradedata.com`, `gssprt.jp`, `adview.com`, `adkomo.com`,
  `spadsync.com`, `adverge.ai`, `servicesadmedia.com`** (all ads
  candidates) — no identity confirmation found (websearch returned nothing
  on-point for `innovative-tradedata.com`; the others weren't searched —
  all are 1-9 hits / under 50KB, below the effort bar this pass).

## False positives / out of scope, no action

- **Colorado government/school-district apexes**: `operacolorado.org`,
  `longmontcolorado.gov`, `colorado.edu`, `colorado.gov`,
  `coloradoshinespdis.com`, `bouldercolorado.gov`,
  `connectingcolorado.gov`, `coloradowebcam.net`, `flagstaffacademy.org`
  (the operator's kids' school) — matched the ads keyword sweep on
  substring alone (`ads`/`track`/`sync`), none are ad-tech.
- **Ed-tech**: `khanacademy.org`, `i-ready.com`, `mathacademy.com`,
  `codecademy.com`, `tinkercad.com` — same substring false-positive class.
- **Dual-use SaaS**: `avada.io` (WordPress theme/plugin vendor), `gladly.com`
  / `ada.support` / `ladesk.com` (customer-support chat platforms),
  `adaptytech.com` / `adapty.io` (B2B mobile-subscription-analytics SaaS),
  `browser-intake-datadoghq.com` / `datadoghq-browser-agent.com` /
  `browser-intake-us5-datadoghq.com` / `browser-intake-us3-datadoghq.com`
  (Datadog RUM/APM, same dual-use monitoring class as Sentry/Bugsnag).
- **Unrelated brand matching an ad-shaped substring**: `godaddy.com`
  (domain registrar), `cbssports.com`, `hotpads.com` (real-estate
  listings, Zillow network), `stackexchange.com`, `techradar.com`,
  `badssl.com`, `addevent.com` / `addtocalendar.com` (calendar tools,
  "add"), `emojitracker.com` ("track", a fan Twitter-emoji counter),
  `ikea.com` (matched the games sweep's `ea\.com` substring — furniture
  retailer), `eternallyradicalidea.com` (a blog/podcast, content not ads).
- **No confirmed identity, low traffic, not investigated further**:
  `simplymadefun.com`, `madebyhighland.com`, `madgeniusclub.com`,
  `fadb.xyz`.
- **B2B / developer-tool AI products, out of `ai.yml`'s consumer-product
  scope**: `devin.ai` (Cognition's AI coding agent, same class as
  `opencode.ai` #2729), `justt.ai` (B2B chargeback-automation fintech
  SaaS), `sardine.ai` (B2B fraud-prevention/AML SaaS for banks and
  fintechs).

## Gambling / games / social-media

No new candidates this pass. `betgenius.com`, `betrivers.com`,
`betweendigital.com` (gambling sweep) are all already documented
(`betrivers.com` curated; `betgenius.com` held out as B2B sports-data;
`betweendigital.com` an already-documented SSP false positive). `gd.games`
(games sweep) remains held out (CloudFront, #2952/#2998). `redditmedia.com`
and the Pangle apexes (social-media sweep) are already curated/correctly
filed under `ads.yml`. `unity3d.com`/`unity3dusercontent.com` remain the
standing dual-use skip. `xboxlive.com`/`xboxservices.com`/`minecraft.net`/
`minecraft-services.net`/`steampowered.com` are all already curated or
already-documented held-outs.

## Self-update

See the Learnings log entry appended to `SKILL.md` in this same PR.
