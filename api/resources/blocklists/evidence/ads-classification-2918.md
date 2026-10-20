# Blocklist pass — ads classification evidence (#2918)

30-day prod `recent-apexes` sweep, all 29 devices, run 2026-10-20 (1,795
unique apexes observed). Keyword-bucketed into ads / adult / ai / gambling /
games / social-media per the `blocklist-pass` skill Step 1, then checked
against all six curated host sets, `evidence/*.md` history, and the
`ads.yml` dated-comment history before treating anything as a new gap.

Adult and gambling both came back with zero candidate apexes this pass —
not just zero gaps, zero hits on any keyword at all, same "valid empty
category" outcome documented in #2503/#2212.

## Observed hosts and where they resolve

Per the #2823 rule, the host that actually appears in traffic (not the bare
apex) is what gets checked for CDN/shared-pool collateral. Observed hosts are
every distinct `host.value` from `/api/logs?domain=<name>&hours=2160` (90
days). Resolution is `dig +short A` + a lookup against the published
`ip-ranges.amazonaws.com/ip-ranges.json` (for CloudFront) and well-known
Cloudflare ranges, run 2026-10-20.

| apex | observed host(s) | resolves to | verdict |
|---|---|---|---|
| `carbonads.com` | none in `/api/logs` (90d); real per `recent-apexes` bytes/hits | bare apex: 52.1.179.177 (AWS EC2, us-east-1 — confirmed via `ip-ranges.json`, NOT CloudFront) | ADD — dedicated |
| `servedby-buysellads.com` | none in `/api/logs` (90d); real per `recent-apexes` bytes/hits | bare apex and `www.` do not resolve at all at verification time | ADD — no IP-layer collateral possible if it isn't resolving |
| `skmads.com` | `cdn.skmads.com` (2), `rtb-us.skmads.com` (6) | `cdn.`: Cloudflare (104.21.55.34/172.67.144.105); `rtb-us.`: 64.34.89.x (Latitude.sh, dedicated) | SKIP — one observed host is Cloudflare |
| `trackcmp.net` | (keyword matched; ActiveCampaign tracking script) | 172.64.153.42/104.18.34.214 — Cloudflare | SKIP — dual-use CRM tool + CDN |
| `instaread.co` | `instaread.co` (3), `player-api.` (2), `player-metrics.` (17), `player.` (4) | `player-metrics.instaread.co`: 99.84.118.x — confirmed CloudFront via `ip-ranges.json` | SKIP — content-widget dual-use + CDN |
| `mmganalytics.com` | `t.mmganalytics.com` (20) | 104.21.8.196/172.67.157.212 — Cloudflare | HELD OUT — unverified identity + CDN |
| `binsiad.com` | `cdn.binsiad.com` (5) | 3.169.202.x — confirmed CloudFront via `ip-ranges.json` | HELD OUT — unverified identity + CDN |
| `ncaudienceexchange.com` | `www.ncaudienceexchange.com` (7) | 13.225.61.x — confirmed CloudFront via `ip-ranges.json` | HELD OUT — unverified identity + CDN |

## ads.yml — 2 additions

| apex | bytes | hits | what it is |
|---|---|---|---|
| `carbonads.com` | 23,590 | 5 | Carbon Ads, BuySellAds' developer-focused native ad network (founded 2010; self-described on carbonads.com/about as reaching developers/designers on documentation sites and developer tools via 260x200 native units). |
| `servedby-buysellads.com` | 30,920 | 5 | BuySellAds' own ad-serving sibling domain for the same Carbon Ads product. The StevenBlack `ads-extended` feed lists `m.servedby-buysellads.com` (a subdomain, not the apex) — the standard "feed under-states apex coverage" corroboration. |

## Held out / skipped this pass (recorded so a later pass does not re-guess)

- **`skmads.com`** — self-described "Performance-Driven Advertising" firm
  (own site: mobile UA, performance lead-gen, programmatic, CTV, in-house
  anti-fraud). Genuine ad-tech identity, but `cdn.skmads.com` — one of its
  two observed hosts — answers on Cloudflare. Per the #2823 rule, any
  Cloudflare-fronted observed host is an unconditional skip for the whole
  apex, even though the dominant host (`rtb-us.skmads.com`, 6 of 8 hits) is
  on dedicated Latitude.sh infra.
- **`trackcmp.net`** — confirmed ActiveCampaign site/event-tracking domain
  (POST to `/event`, redirect links via `/redir`; WHOIS shows GoDaddy
  registration since 2013). Same dual-use CRM/marketing-automation class as
  the standing `hs-analytics.net` skip. Independently also Cloudflare-fronted.
- **`instaread.co`** — confirmed embeddable article-to-audio narration SDK
  for publishers/newsrooms (Capterra: "Instaread Player... embeddable
  article-to-audio tool"). Same content-collateral/widget-embedded-on-
  unrelated-sites class as `trinitymedia.ai`/`gpteng.co` (#2729). Its
  dominant observed host is also CloudFront-fronted.
- **`mmganalytics.com`**, **`binsiad.com`**, **`ncaudienceexchange.com`** —
  no identity confirmation found (websearch returned nothing specific to
  any of these three domains). All three are also Cloudflare/CloudFront-
  fronted on their only observed host, so even a future identity
  confirmation would not clear the #2823 rule without a different observed
  host appearing.
- **`coderpad.io`** — false positive from the regex sweep (a developer
  technical-interview platform; `app.`/`execute.`/`lsp-mutualized.`
  subdomains are product infra, zero ad-tech signal).
- **`4kdownload.com`, `tinkercad.com`, `desync.com`, `gameanalytics.com`,
  `hs-analytics.net`, `siteimproveanalytics.com`/`.io`, `bounceexchange.com`,
  `freebeacon.com`, `ebayadservices.com`, `smartborad.com`,
  `imganalytics.com`, `app-ads-services.com`, `app-analytics-services.com`,
  `opentrackr.org`, `popcorn-tracker.org`, `cam4tracking.com`,
  `merchant-center-analytics.goog`, `ad-score.com`, `insiad.com`,
  `syncingbridge.com`** — all re-confirmed standing skips/held-outs already
  documented in `ads.yml`'s dated comments; no new evidence to reverse any
  of them.

## Residual-risk watchlist re-check (Step 2.4)

Re-ran the `/api/logs` check for both open residual-risk siblings in
`ads.yml`: `cdn.bttrack.com`/`video.bttrack.com` (still not observed; only
the bare `bttrack.com` apex, 69 rows) and the bare `loudecho.ai` apex (still
not observed; `loudecho.ai` domain filter returned zero rows this pass
total). No removal/pin-absent action needed.

## ai / games / social-media — no new gaps

- **ai**: `.ai`-TLD and consumer-chatbot keyword sweep surfaced
  `modernintelligence.ai` (new — confirmed defense/maritime-surveillance AI
  company, Austin TX, "Building One A.I. For Defense" — out-of-scope B2B/
  defense SaaS, same class as `dxtech.ai`/`castify.ai`), `grok-sandbox.com`
  (new — no official confirmation; plausibly xAI's own internal sandbox
  infra rather than a consumer product, held out unverified), and
  `stashbase.ai` (new — no identity confirmation found, held out). All
  other `.ai`/AI-keyword candidates (`sierra.ai`, `ivy.ai`, `wandb.ai`,
  `learnings.ai`, `axon.ai`, `bidsystem.ai`, `verysane.ai`, `dxtech.ai`,
  `castify.ai`) are standing documented skips/held-outs, re-confirmed.
- **games**: `gamerhunk.com` (new, 8 hits/6.6MB) — no identity confirmation
  found, held out unverified. `unity3d.com`/`unity3dusercontent.com`/
  `gameanalytics.com` are standing dual-use skips. Everything else observed
  (`teamwoodgames.com`, `steamstatic.com`, `eaglercraftgame.io`,
  `teamwood.games`, `steampowered.com`) is already curated.
- **social-media**: every platform apex observed (`x.com`, `instagram.com`,
  `linkedin.com`, `facebook.com`, `whatsapp.net`, `twitter.com`, the
  `tiktok*` cluster, `reddit.com`, `pinterest.com`, `snapchat.com`,
  `vk.com`) is already curated. `ads-twitter.com` and `linkedin-ei.com` are
  standing `ads.yml` entries/skips, not social-media gaps.
  `twitteracquisitionlitigation.com` (a legal-case site) and
  `trentbereal.com` (an unrelated personal domain matching the "bereal"
  substring) are false positives from the keyword regex, not platforms.
