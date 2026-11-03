# Weekly blocklist pass — #2957 (2026-11-03)

30-day prod `recent-apexes` sweep across all 30 devices, plus a serial
`/api/logs` keyword sweep per category. Observed hosts resolved same day
(2026-11-03). Every candidate was cross-checked against the current curated
host sets for all six inline categories, the StevenBlack `ads-extended` /
`adult-extended` / `social-extended` feeds, and the `evidence/*.md` history
from all prior passes before being treated as a "new" gap.

The household's traffic this run was again developer/infra-heavy (top bytes:
`googlevideo.com`, `githubusercontent.com`, `apple.com`, `alpinelinux.org`,
`google.com`, `wifihaven.net`, `googleapis.com`, `claude.ai`, …) — consistent
with prior passes, this does not indicate a thin sweep; the category gaps
were found by keyword-filtering the full 1,865-apex ranked list, not by
eyeballing the top of it.

## Residual-risk watchlist re-check

Per Step 2.4, re-checked every `RESIDUAL RISK` entry currently in `ads.yml`
against `/api/logs` (90 days): `cdn.bttrack.com`, `video.bttrack.com`, the
bare `loudecho.ai` apex, and `cdn.stickyadstv.com`. None has been observed.
No removals this pass.

## Added

### ads.yml

- **`buysellads.net`** (BuySellAds' own ad-marketplace domain; confirmed via
  the company's own site, Crunchbase, Dealroom — a Boston ad network/
  marketplace operating the Carbon Ads product since 2008). Only observed
  host: `cdn4.buysellads.net`, which CNAMEs to `srv.buysellads.com`,
  answering on two dedicated DigitalOcean addresses (`159.203.151.34`,
  `24.144.70.77` — both confirmed `DigitalOcean, LLC` via whois), not a
  shared CDN pool. Checked unobserved siblings (bare apex, `www.`, `cdn.`,
  `cdn1.`–`cdn3.`, `ads.`) — none resolve. Distinct company-family outcome
  from the already-held-out `servedby-buysellads.com` / `carbonads.com`
  (#2918), both CDN-fronted on their serving hosts — same vendor family,
  different apex, different CDN exposure.

- **`playablefactory.app`** (Playable Factory, a Turkey-based ad-tech company
  founded 2018 that builds interactive "playable" mini-game ads for mobile
  app/game publishers — confirmed via its own site, TechRound interview
  coverage, PG Connects sponsor listing). This is an ad *format* company, not
  a real-game-content distributor, so there is no games.yml content-collateral
  concern. Only observed host: `logger2.playablefactory.app`, which resolves
  via a dedicated AWS Application Load Balancer
  (`raw-event-alb-1915104033.eu-central-1.elb.amazonaws.com`, `3.75.168.200`,
  `63.185.98.55` — both Amazon-owned per whois, but a customer's own ALB, not
  CloudFront) — same "customer load balancer vs. shared product frontend"
  distinction as the `nexverse.ai` GCP-LB precedent (#2836).
  **RESIDUAL RISK:** `cdn.playablefactory.app` (unobserved) CNAMEs onto
  CloudFront (`d1zyemcopc5d66.cloudfront.net`). Not currently observed, but
  the apex entry suffix-matches it — if it appears in traffic, remove this
  entry and pin its absence (the `ebayadservices.com` precedent, #2816).

  The sibling apex **`playablefactory.com`** (same company, `.com` TLD) was
  **held out instead**: its own observed host, `play.playablefactory.com`,
  answers directly on Cloudflare (`104.26.9.224`, `104.26.8.224`,
  `172.67.74.62`) — an unconditional #2823 skip on the *observed* host
  itself, not a residual-risk case. Pinned absent in `BundledBlocklistsSpec`.

## Held out — CDN-fronted on the observed host (#2823/#2918/#2952 rule)

Pinned absent in `BundledBlocklistsSpec` (`#2957: CDN-fronted blocklist-pass
candidates stay out of every inline list`):

| Apex | Traffic | Identity | Observed/checked host(s) | CDN |
|---|---|---|---|---|
| `gamepix.com` | 81,314B / 2 hits (never in `/api/logs`) | Confirmed: GamePix, Rome-based HTML5/WebGL browser-game distribution platform, acquired by Xsolla — games.yml-shaped | bare apex, `www.` | Bare apex `18.160.143.x` = CloudFront (AWS ip-ranges.json); `www.gamepix.com` explicit CNAME to `*.cdn.cloudflare.net` |
| `xboxlive.com` | 1,743,601B / 35 hits | Confirmed: Microsoft's Xbox Live gaming-services backend (xbox.com already curated) — games.yml-shaped | `accounts.`, `peoplehub.`, `sisu.`, `title.mgt.`, `titlehub.`, `userpresence.`, `xapprove.` | 4 of 7 (`accounts.`/`title.mgt.`/`userpresence.`/`xapprove.`) resolve via Azure GTM steering (`akadns.net` only, dedicated Microsoft addresses — not a shared pool, same as the `appier.net` GTM precedent #2952); but 3 of 7 (`peoplehub.`/`sisu.`/`titlehub.`) CNAME through `edgekey.net` onto shared Akamai EdgeSuite edges (`184.27.144.51`, `23.37.96.49`) — Akamai's actual caching product, the `ebayadservices.com` class. One CDN-fronted observed host disqualifies the whole apex (the `skmads.com` rule, #2918) |
| `mgln.ai` | 74,429B / 5 hits | Confirmed: Magellan AI, podcast-advertising-analytics company — an `ai.yml`-TLD trap (same class as `axon.ai`/`trygravity.ai`) | bare apex, `cdn.` | Both Cloudflare (`104.26.x`/`172.67.x`) |
| `panxo.ai` | 49,545B / 20 hits (never in `/api/logs`) | Confirmed: Panxo, AI-referred-traffic ad-monetization layer for publishers ("Panxo Layer", own contact page + WordPress plugin confirm `api.`/`cdn.` subdomains) — same TLD-trap class | bare apex, `www.`, `api.`, `cdn.` | All Cloudflare (`104.26.x`/`172.67.x`) |
| `megaplay.buzz` | 20,423,829B / 1,471 hits | No confirmed owner — a security scanner (Gridinsoft) flags it low-trust (31/100) with privacy-redacted WHOIS, 1-year-old registration | bare apex | Cloudflare (`104.21.21.28`, `172.67.196.5`) |
| `ygametime.com` | 516,064B / 13 hits | No confirmed owner — no search result names this exact domain | bare apex | Cloudflare (`172.66.42.248`, `172.66.41.8`) |
| `brand-display.com` | 489B / 2 hits (never in `/api/logs`) | No confirmed owner | bare apex, `www.` | CloudFront (`18.238.136.x`, confirmed via AWS ip-ranges.json) |

## False positives / out of scope — no action

- **`skypixel.com`** — DJI's drone-photography community platform (founded
  2014, 6M+ registered users per DJI press materials). Matched the ads
  `pixel` substring sweep; unrelated to any curated category.
- **`saasexch.com`** — a SaaS-business acquisition marketplace. Matched the
  adult `sex` substring sweep purely on the string "saas**exch**" — not
  adult content.
- **`heapanalytics.com`** — Heap (acquired by Contentsquare, 2023), a
  first-party product-analytics SDK sites embed for their own usage data.
  Same dual-use class as `google-analytics.com`/`hs-analytics.net`.
- **`hsadspixel.net`** — HubSpot's own shared mechanism for hosting *other*
  ad networks' pixels (own knowledge-base: "install pixels from external ad
  networks") on behalf of its customers. Low traffic (1,479B/7 hits) and
  shared across thousands of unrelated HubSpot sites — same dual-use-infra
  class as `hs-analytics.net`, not a specific advertiser's own domain.
- **`crisp.chat`** / **`livechatinc.com`** — confirmed live-chat customer-
  support widget vendors (Crisp, France; LiveChat Inc., US). Same dual-use
  class as `gorgias.chat` (#2503).
- **`eightfold.ai`** — confirmed B2B HR-tech "talent intelligence" platform
  (enterprise/public-sector recruiting). Out of `ai.yml`'s consumer-product
  scope, same class as `dxtech.ai`/`castify.ai` (#2792).
- **`yofi.ai`** — a B2B ecommerce fraud/return-policy-abuse protection tool
  (Tel Aviv, founded 2021), since rebranded "Wyllo". Out of scope.
- **`anagram.ai`** — a B2B AI product-data-platform / agent-tooling startup.
  Out of scope.
- **`idsequoia.ai`** — no confirmed identity found; low traffic (26,597B/20
  hits). Held out unverified, carried forward.
- **`ads-pubsfusion.com`** — does not currently resolve (bare apex, `www.`
  both fail); zero `/api/logs` hits despite appearing in `recent-apexes`
  (14,873B/2 hits). No identity found. Held out — nothing to classify.

## Categories with no change this pass

- **adult.yml, gambling.yml, social-media.yml**: keyword sweeps surfaced no
  genuine gaps — only already-curated hosts (`ads-twitter.com`,
  `tiktokpangle-b.us`/`tiktokpangle-cdn-us.com`/`tiktokpangle.us`, `axon.ai`,
  `nexverse.ai` — all correctly curated in `ads.yml`, not their
  keyword-matched category, per the #2742 cross-category-duplicate lesson)
  and standing documented skips/false-positives (`betgenius.com`,
  `betweendigital.com`, `gorgias.chat`). Consistent with the recurring
  observation that adult/gambling can go a full pass with zero new traffic.
- **ai.yml**: the full `.ai`-TLD sweep (23 apexes) surfaced nothing new for
  this category — every candidate was either a standing re-confirmed
  held-out from a prior pass, a fresh B2B/out-of-scope product
  (`eightfold.ai`, `yofi.ai`, `anagram.ai`), unverified (`idsequoia.ai`), or
  (for `mgln.ai`/`panxo.ai`) genuine ad-tech wearing the `.ai` TLD — correctly
  routed to the ads.yml CDN-held-out list above, not added here.
