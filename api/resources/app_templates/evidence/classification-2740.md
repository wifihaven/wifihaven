# App-catalog pass classification — #2740 (2026-08-24)

Traffic-driven pass per the `/app-catalog-pass` skill. Source: prod
`https://api.wifihaven.net` (read-only), `recent-apexes?windowDays=30&limit=500`
aggregated by bytes across the three kid devices — Octavius iPad
(`a6:05:9a:63:83:af`), Prima iPad (`8a:8a:0b:86:5a:63`), Quintus iPad
(`26:74:fc:f9:4e:9e`). (Device roster changed since #2129/#2596/#2699: the
former "Kid Mac" is no longer present and Prima's MAC has rotated —
confirmed via `GET /api/devices`, not assumed from a prior pass.)

## Outcome: no new app template, no new blocklist entry, no host-set gap

The catalog remains mature. Every apex with meaningful kid traffic maps to an
existing app template, a curated blocklist entry, Apple/Google/iCloud OS
background chatter, or ad-tech/RTB — none of it a kid-facing branded
experience, so none of it is app-catalog territory regardless of exactly how
(or whether) `InfraHosts.scala` accounts for it.

## Per-apex disposition (top of the ranked byte table; shared infra/CDN/ad-tech elided)

All bytes/hits below are read directly from the `recent-apexes` pull
(`ranked_apexes.tsv`, aggregated across the 3 device-windows) — not
estimated.

| apex | bytes | hits | disposition |
|------|------:|-----:|-------------|
| apple.com / cdn-apple.com / icloud-content.com / icloud.com / gstatic.com / mzstatic.com / aaplimg.com / safebrowsing.apple / edge.apple | 13.55 GB combined | 20,658 | **skip** — OS/device background chatter, not a branded kid experience. Per-host `InfraHosts.scala` coverage detailed below the table (mixed and, for one apex, absent — noted for completeness; doesn't change the app-catalog disposition either way) |
| googlevideo.com / ytimg.com / youtubekids.com / youtube.com | 8.92 GB combined | 262 | `youtube` app ✓ |
| flashtalking.com / googlesyndication.com / doubleclick.net / rubiconproject.com / pubmatic.com / everesttech.net / media.net / tubemogul.com / openx.net / richaudience.com / a-mo.net / casalemedia.com / confiant-integrations.net / onetag-sys.com / adtrafficquality.google / doubleverify.com / adsafeprotected.com / btloader.com / ad-score.com / amazon-adsystem.com / postrelease.com / ntv.io / ay.delivery (pbs-poki-us prebid) / amazon.dev (advertising.amazon.dev) | 2.85 GB combined | 1,301 | **skip** — ad-tech/RTB |
| ssl-images-amazon.com / amazon.com / media-amazon.com / amazonaws.com / a2z.com | 2.51 GB combined | 771 | **skip** — Amazon shopping/AWS shared infra, not a kid app |
| lego.com | 290 MB | 178 | `lego` app ✓ — LEGO Builder sub-experience re-checked clean, see below |
| mathacademy.com (+ shared `d3js.org`, `jsdelivr.net`) | 50.6 MB combined | 191 | `math-academy` app ✓ |
| gimkit.com / gimkitconnect.com | 21.0 MB combined | 41 | `gimkit` app ✓ |
| poki.com / poki.io / poki-cdn.com | 20.1 MB combined | 100 | `poki` app ✓ |
| eaglercraft.dev / eaglercraft.ru / eaglercraft.com / eaglercraftgame.io / lax1dude.net / deev.is / shhnowisnottheti.me | 54.9 MB combined | 147 | `eaglercraft` app ✓ — relay subdomains suffix-match the templated hosts (`HostMatch.matchesApex`), no gap |
| workers.dev (0.30 MB / 30 hits, apex-level — mixes `eaglercraft-counter.eaglercraft-99f.workers.dev` with unrelated `pioeg.admetricspro.workers.dev`; `recent-apexes` doesn't split bytes below the apex, so the two can't be separated) | 0.30 MB | 30 | mixed: eaglercraft counter subdomain suffix-matches the templated `eaglercraft-99f.workers.dev` host (✓, no gap); `admetricspro` subdomain is ad-tech (**skip**) |
| duolingo.com | 10.2 MB | 304 | `duolingo` app ✓ |
| scholastic.com | 8.0 MB | 6 | **skip** — Scholastic *Book Clubs* (`clubs`/`ltm`/`sstats`/`webchat-customer`/`www`), a parent/school book-ordering commerce flow, not a kid-facing recurring app; thin single-session spread |
| mcsrvstat.us | 7.8 MB | 89 | **skip** — `api.mcsrvstat.us` only; shared third-party Minecraft-server-status API used by many unrelated sites/bots (web-confirmed), not owned/branded by Mojang or Eaglercraft — same "vendor API, no branded web surface" collateral class as `elevenlabs.io` (#1922 learning). Watch-item: template into `eaglercraft`/`minecraft` only if a future pass can attribute it to the game client itself rather than a third-party status checker |
| 1password.com / 1passwordservices.com / agilebits.com / 1passwordusercontent.com | 5.5 MB combined | 117 | `1password` app ✓ |
| apple.news (`c.apple.news`) | 4.7 MB | 61 | **skip** — single OS-bundled edge CDN host, no independent branded surface; consistent with #2490's iMessage-precedent reasoning (traffic/hit volume has grown since #2490 but the shape is unchanged — one edge host, no page-view surface) |
| southwest.com | 3.7 MB | 11 | **skip** — airline booking, adult/family-admin use, not a kid app |
| adobe.com | 3.7 MB | 7 | **skip** — shared corporate infra |
| freckle.com | 2.9 MB | 7 | `freckle` app ✓ |
| plex.tv | 2.4 MB | 198 | `plex` app ✓ |
| arkoselabs.com | 2.4 MB | 2 | **skip** — shared CAPTCHA/bot-detection vendor, embedded across many unrelated sites |
| cloudflare.com | 2.2 MB | 43 | **skip** — shared infra |
| mathplayground.com | 1.4 MB | 6 | `math-playground` app ✓ |
| one.one (`one.one.one.one`) | 1.0 MB | 81 | **skip** — Cloudflare 1.1.1.1 DoH resolver, device-level infra |
| ggpht.com | 0.83 MB | 23 | **skip** — shared Google content-hosting apex (Blogger/legacy Photos), collateral risk at the apex per #2699 learning even though only `yt3.ggpht.com`-class subdomains observed |
| snapchat.com / sc-cdn.net / snap-dev.net / snapkit.com | 0.39 MB combined | 73 | `snapchat` app ✓ |
| duckmath.org | 0.65 MB | 4 | `games.yml` ✓ (filter-bypass blocklist, confirmed already present) |
| khanacademy.org | 0.58 MB | 3 | `khan-academy` app ✓ |
| thelegogroup.com (`sentry.thelegogroup.com`) | 0.08 MB | 8 | **skip** — LEGO corporate error-reporting/analytics, excluded by design (#1815) |
| readingeggspress.com | 0.09 MB | 1 | **watch-item, unchanged** — still below engagement bar (1 hit) |
| renaissance.com (`ui.renaissance.com`) | 0.08 MB | 1 | **watch-item, unchanged** — still below engagement bar (1 hit) |
| poki-gdn.com | 0.02 MB | 2 | **watch-item, unchanged** — same volume/shape as #2699's explicit "add if it grows" note in `poki.yml`; no growth this pass |

### Apple/Google OS-background row — per-host `InfraHosts.scala` detail

None of this row is a branded kid experience, so all of it is a skip for
app-catalog purposes regardless of infra-allowlist coverage — but the table
cell above elides *how* each host is (or isn't) accounted for in
`shared/src/types/InfraHosts.scala`, verified by reading the file directly
rather than inferring from apex name:

- `icloud.com` — bare apex, `suppressOnly` (`InfraHosts.scala:180`).
- `cdn-apple.com`, `icloud-content.com`, `safebrowsing.apple`,
  `tether.edge.apple` (the apex logged as `edge.apple`) — bare/exact hosts,
  `cloudBackground` (`InfraHosts.scala:391`, `:389`, `:399`, `:445`) — a tier
  distinct from `canonical`/`suppressOnly`.
- `apple.com`, `gstatic.com` — never listed as bare apexes; most of the
  observed traffic is dozens of *specific* subdomains individually
  enumerated across `canonical`/`suppressOnly` (`push.apple.com`,
  `itunes.apple.com`, `ess.apple.com`, `smoot.apple.com`, `ls.apple.com`,
  `connectivitycheck.gstatic.com`, etc.). One observed `gstatic.com`
  subdomain, `ssl.gstatic.com`, is **deliberately excluded** per an explicit
  comment at `InfraHosts.scala:279-280` ("the gstatic apex is too broad and
  `ssl.gstatic.com` itself fans out across" other uses) rather than merely
  unlisted.
- `g.aaplimg.com` — `canonical` (`InfraHosts.scala:77`); observed hosts are
  all `*.g.aaplimg.com`, not the bare `aaplimg.com` apex.
- `mzstatic.com` (App Store/Music icon CDN, 37.8 MB / 164 hits of the total)
  — genuinely **absent** from `InfraHosts.scala`; not enumerated in any tier.

Everything else in the sample is ad-tech/RTB/analytics/consent (googletagmanager,
quantummetric, sentry.io, bugsnag, id5-sync, criteo, adform, rlcdn, and the long
tail of exchange/DSP/SSP domains), shared CDN/platform infra (wixstatic.com,
parastorage.com, unpkg.com, cloudfront.net, akamai*.net, fastly-edge.com,
googleusercontent.com), CA/TLS infra (digicert.com, usertrust.com, rapidssl.com,
comodoca.com, amazontrust.com, lencr.org, geotrust.com, sectigo.com), or
below-engagement-bar incidental hosts (temu.com, kvaedit.site, ntp.org, and
similar 1-2-hit apexes). All skip.

## LEGO host-set — no gap (negative check)

The `lego` app is scoped to the LEGO Builder sub-experience:
`cobuild.i.lego.com`, `dbix.i.lego.com`, `services.lego.com`, `apps.lego.com`.
A fresh 290 MB / 178-hit sample (3 device-windows) shows only:

- `api.prod.cobuild.i.lego.com` → `cobuild.i.lego.com`
- `api.prod.dbix.i.lego.com`, `assets.prod.dbix.i.lego.com`,
  `biapp.prod.dbix.i.lego.com`, `imageresizer.prod.dbix.i.lego.com` → `dbix.i.lego.com`
- `appconfig`/`allowed-countries.scout`/`scout`/`videoprocessingpipeline`.services.lego.com → `services.lego.com`

all of which suffix-match one of the four scoped entries (`HostMatch.matchesApex`).
The uncovered `www.lego.com` is the shop side — deliberately excluded by the
#1815 sub-experience scoping. No extension needed.

## Watch-items re-checked, unchanged

Per the skill's standing rule (2026-08-03 learning) to re-check prior passes'
skipped/watch-item apexes every run: `poki-gdn.com`, `readingeggspress.com`,
`renaissance.com` were all re-pulled this window and show the same thin,
below-bar volume as when they were first flagged (#2699/#2596) — no
graduation this pass.
