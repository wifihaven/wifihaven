# App-catalog classification — #2943 weekly pass (2026-10-26)

Orphan-driven pass. Source: prod cloud API, read-only. No policy writes.

```
GET /api/devices                                                       # roster
GET /api/profiles/<id>/usage-by-app?from=2026-09-26&to=2026-10-26       # profiles 5,6,7 — orphanHosts
```

Kid profiles: 5 (Quintus — Quintus Chromebook, Quintus iPad), 6 (Prima —
Prima iPad), 7 (Octavius — Octavius iPad). `orphanHosts` filtered to
`host.type == "fqdn"` (bare IP literals excluded, per the standing #2775
caveat) and aggregated across the three profiles by `proportionalSeconds`.

## Observed traffic — top non-platform clusters

The overwhelming majority of the ranked list (`ssl.gstatic.com`,
`www.google.com`, `accounts.google.com`, `bam.nr-data.net` (New Relic
telemetry), `*.googleapis.com`, `*.doubleclick.net`, Apple platform hosts,
etc.) is Google/Apple platform infra and ad-tech — the usual skip pile, not
re-litigated here.

Below that, three already-documented skip clusters recurred with no new
information:

| cluster | proportional min (30d) | disposition |
| --- | ---: | --- |
| Shopify storefront infra (`mouldkingcorp.com`, `shop.app`, `*.shopifysvc.com`, `fonts.shopifycdn.com`, `cdn-odl.avada.io`, `fraud.blockifyapp.com`, `config.gorgias.{chat,help}`, `monsterupsells.com`) | ~230 combined | **skip** — demonstrated shared Shopify origin (`shops.myshopify.com`), established at #2774/#2908 |
| `download.testnav.com`, `cdn-download-prod.drcedirect.com` | 50 + 41 | **skip** — Pearson TestNav / DRC INSIGHT standardized state-testing downloads, same single-event pattern noted at #2908 |
| `www.clarity.ms` / `scripts.clarity.ms` / `q.clarity.ms` / `h.clarity.ms` | ~98 combined | **skip** — Microsoft Clarity, third-party session-recording analytics embedded by many sites, not a destination |
| LEGO family (`assets.lego.com`, `cs.analytics.lego.com`, `prod.lake-router.legogroup.io`) | ~56 combined | **skip** — already considered and excluded in `lego.yml` (analytics/telemetry; `prod.lake-router` explicitly named there) |
| Arduino telemetry (`sgtm.arduino.cc`, `store-usa.arduino.cc`, `api.aayinltcs.arduino.cc`, `evs.aayinltcs.arduino.cc`) | ~77 combined | **skip** — already-documented Arduino analytics/CNAME-cloaked tracking pattern (#2754) |
| `teamwoodgames.com`/`teamwood.games` family (`api.teamwood.games`) | 11 | already covered — `games.yml` blocklist entry (#2778) |

## New finding: GDevelop (shipped as an app)

| host | proportional min (30d) | presence min | rows |
| --- | ---: | ---: | ---: |
| `gdevelop.io` | 11 | 12 | 1 |
| `public-game-resources.gdevelop.io` | 11 | 10 | 1 |
| `resources.gdevelop-app.com` | 12 | 12 | 1 |
| **total** | **34** | **34** | — |

Single kid profile (Quintus). Verified live (2026-10-26):

```
$ curl -sSL https://gdevelop.io | grep -io '<title>[^<]*</title>'
<title>GDevelop: Free, Fast, Easy Game Engine - No-code, AI-assisted, Lightweight, Super Powerful | GDevelop</title>
$ dig +short gdevelop.io
172.67.68.67
104.26.15.77
104.26.14.77
$ dig +short public-game-resources.gdevelop.io
104.26.15.77 / 172.67.68.67 / 104.26.14.77   # same Cloudflare edge as gdevelop.io
$ dig +short resources.gdevelop-app.com
104.26.8.65 / 104.26.9.65 / 172.67.74.45     # different distribution, same Cloudflare /24 class
```

`gdevelop-app.com` is confirmed first-party by a direct link, not just
name-similarity plus shared infra (the #2753 whois-trap lesson): gdevelop.io's
own homepage embeds a "games-showcase" section whose images are served
directly from `gdevelop-app.com/games-showcase/images/…`.

```
$ curl -sSL https://gdevelop.io | grep -o 'gdevelop-app\.com/games-showcase/images/[^"]*' | head -1
gdevelop-app.com/games-showcase/images/10d3mBn4E5e8y2Z3JYd8mmIpQMFfTRvYz
```

All three hosts are also on Cloudflare's shared 104.26.x/172.67.x anycast
edge — ordinary accepted Class 2 (multi-tenant CDN, no demonstrated
collision with an unrelated site in this household's own traffic; same
standing as `youtube.yml`'s Cloudflare-fronted hosts) — but that shared-edge
fact is collateral context, not the evidence for the brand link above.

GDevelop is a no-code, browser-based game-creation engine — functionally
the same category as the already-templated `scratch`/`tinkercad` (a kid
*builds* something) rather than an "unblocked games" filter-bypass portal,
so it's a new app template, not a `games.yml` entry.

Volume is thin by byte/minute standards, comparable to the `freckle`
(#2596, 2.9 MB/7 hits) and `serato` (#2331, 8.9 MB/106 hits) precedents for
a below-typical-volume app. What makes this a genuine app rather than a
beacon is the *shape*: three distinct hosts across two separate apexes (the
editor/marketing site plus two independent game-asset CDNs), not a single
tracking pixel or a one-time download. `gdevelop.io` alone scores a real
page load (title-confirmed); the two asset-CDN hosts are what a published
GDevelop-made game pulls its sprites/sounds from at play time.

**Shipped:** `api/resources/app_templates/gdevelop.yml`, hosts
`gdevelop.io` + `gdevelop-app.com`, registered in `_index.yml` and the
pinned `AppTemplatesSpec.expected` set.

## Watch-items (not shipped — insufficient or ambiguous evidence)

- **`scalinghub.codehs.com`** (12 min, 1 row) — CodeHS is a real K-12
  computer-science platform (`codehs.com` main site title-confirms: "The
  Top Coding and Computer Science Platform for K-12 Schools"), but the
  *only* host observed is `scalinghub.codehs.com`, which resolves to plain
  AWS IPs (not the CloudFront edge the main site uses) and answers `403
  Forbidden` with no page body — reads as an internal
  scaling/load-balancer or live-coding-session WebSocket endpoint, not
  something a kid's browser navigates to directly. No branded content host
  (`codehs.com`, `www.codehs.com`, an editor subdomain) showed up in this
  window. Same shape as the #1922 "vendor-API backend hit only at its `api.`
  host with no branded web surface" skip pattern. Re-check next pass if a
  content-facing host appears alongside it.
- **`ic.svvsd.org`** (15 min, 1 row) — resolves via CNAME to
  `stvrainco.infinitecampus.org`; title-confirms as the St. Vrain Valley
  School District's Infinite Campus student/parent login portal. Same
  functional category as `classlink.yml` (school SSO/administrative
  portal, display-cleanup only) but a *different* vendor, and per the
  #2908 precedent ("different vendors, not interchangeable instances of
  one function") that alone wouldn't disqualify a template. What does hold
  it back here: a single observed host, 15 minutes total, one presence
  session — reads as one grade-check login, not a recurring pattern.
  `classlink.yml` shipped at 76 minutes across 12 subdomains; this is far
  thinner. Re-check next pass for growth or additional Infinite Campus
  subdomains (e.g. a gradebook-specific API host) before templating.
- **`coddy.tech`** (not observed this window at meaningful volume) —
  carried over as a standing watch-item from #2915 (Cloudflare bot
  challenge blocks title verification); no new signal this pass.

## Outcome

One new app template (`gdevelop`). No existing-app host-set gaps found. No
new blocklist entries — nothing in this window's traffic matched an
uncovered category (ads/adult/ai/gambling/games/social) apex not already
in a curated list.
