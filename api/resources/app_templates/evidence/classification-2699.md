# App-catalog pass classification — #2699 (2026-08-14)

Traffic-driven pass per the `/app-catalog-pass` skill. Source: prod
`https://api.wifihaven.net` (read-only), `recent-apexes?windowDays=30&limit=500`
aggregated by bytes across the four kid-profile devices: Kid Mac
(`ca:ef:a1:72:6a:a3`, profile 1), Quintus iPad (`26:74:fc:f9:4e:9e`, profile
5), Prima iPad (`8a:8a:0b:86:5a:63`, profile 6), Octavius iPad
(`a6:05:9a:63:83:af`, profile 7). 317 distinct apexes surfaced; the ranked
table below covers everything with meaningful byte volume.

## Outcome: one new app template, two existing-app host-set extensions

## New app: Zoom

| apex | bytes | hits | disposition |
|------|------:|-----:|-------------|
| zoom.us | 213 MB | 95 | **new app** — Zoom video calls |

All traffic on Kid Mac only, across `cdn.zoom.us` (web-client assets +
media) and `us.telemetry.zoom.us`. No bare `zoom.us` or dated meeting-join
host (e.g. `us04web.zoom.us`) was observed, but the combination of real
byte volume (213 MB, consistent with video-call media) and a sustained hit
count (95, not a single page load) across both the CDN and telemetry hosts
is the same "recurring engagement shape over single-burst" bar used for
`serato` (#2331) and `freckle` (#2596) — read as school/tutoring video-call
usage, not a stray beacon. Templated as `zoom` with a plain `zoom.us` apex
host (suffix-matches any future meeting-join subdomain).

## Existing-app host-set extensions

| app | host(s) added | bytes | hits | evidence |
|-----|---------------|------:|-----:|----------|
| `poki` | poki.com, poki-cdn.com, poki-gdn.com | 15.4 MB | 41 | The #1705 template explicitly deferred `poki.com` as "marketing surface only... add it later if the operator's traffic shows otherwise" (14d window). This pass's 30d sample shows `games.poki.com` / `poki-auth.poki.com` / `devs-api.poki.com` — real navigational + auth traffic, not marketing. `poki-cdn.com` (`img`/`a`/`v` subdomains) and `poki-gdn.com` are Poki's own branded CDN/game-delivery domains (web-confirmed via Poki's own SDK docs and site footprint), Class 2 dedicated-per-app CDN per `_README.yml` — same precedent as `rbxcdn.com`/`sc-cdn.net`. |
| `eaglercraft` | eaglercraftgame.io, relay.lax1dude.net, relay.deev.is, relay.shhnowisnottheti.me | 452 KB | 32 | `eaglercraftgame.io` is a fourth Eaglercraft mirror, already dual-listed in `blocklists/games.yml` (#2599) but not wired into the app template — same gap pattern as `.com`/`.ru` in #2058/#2596. The three `relay.*` hosts are WebSocket relay servers the Eaglercraft client falls back through for LAN-world-sharing multiplayer (web-confirmed: `lax1dude.net` is the Eaglercraft creator's own relay; `deev.is` and `shhnowisnottheti.me` are community relays referenced in Eaglercraft relay configs). Near-identical byte pairs across all three (69/23 KB, 69/23 KB, 60/20 KB) is the client trying its relay list in order — support infra for the app, not a game-hosting mirror, so NOT added to `games.yml` (same reasoning as the existing account-scoped `workers.dev` entry). |

## Notable skips (below bar / collateral / already-handled)

| apex | bytes | hits | reason |
|------|------:|-----:|--------|
| googlevideo.com, apple.com, flashtalking.com, cdn-apple.com, icloud-content.com, google.com, gvt1.com, icloud.com, googleapis.com, gstatic.com, app-measurement.com, mzstatic.com, googleusercontent.com, gvt2.com, wixstatic.com, cloudflare.com, aaplimg.com, apple-cloudkit.com, digicert.com, akamai.net, amazonaws.com | multi-GB–multi-MB | high | shared vendor-anycast pools / OS-level infra — no dedicated bytes worth blocking, Class 1 collateral per `_README.yml` |
| mcsrvstat.us | 7.8 MB | 89 | vendor-API-only (`api.mcsrvstat.us`, a Minecraft server-status checker) — no branded web surface a kid navigates to; same "API backend, not an app" skip as `elevenlabs.io` (#1922) |
| apple.news | 9.9 MB | 137 | reconfirms #2490: single-edge Apple service (`c.apple.news`), not operator-requested — skip-with-note |
| genius.com | 3.3 MB | 10 | reconfirms #2490: `assets`/`t2`/`librato-collector` — analytics/tracking only, zero navigational hits |
| ggpht.com | 1.37 MB | 33 | `yt3.ggpht.com` YouTube avatar CDN, but `ggpht.com` is a shared Google-content-hosting apex (Blogger images, Google Photos legacy) beyond just YouTube — Class 1 collateral risk, held out (youtube.yml already covers the actual video CDN via `googlevideo.com`) |
| microsoft.com | 320 KB | 19 | `minecraftprivacy.microsoft.com` / `displaycatalog.mp.microsoft.com` are Minecraft-Marketplace-adjacent, but `microsoft.com` is shared corporate/telemetry infra used by many unrelated MS products — collateral risk, held as a watch-item |
| nocookie.net, fandom.com | 2.5 MB combined | 10 | Fandom wiki (gaming wiki lookups) — below engagement bar, no sustained session shape |
| duckmath.org | 651 KB | 4 | already in `blocklists/games.yml` — unblocked-math filter-bypass site, block-target only, correctly not an app |
| clean.gg | 55 KB | 5 | web-confirmed as HumanSecurity (`clean.io`) bot-mitigation/fraud-detection telemetry, not a game site despite the `.gg` TLD — skip |
| cpx.to, activemetering.com | 116 KB / 106 KB | 10 / 38 | reward-network / game-portal ad-metering SDKs — ad-tech, skip |
| southwest.com, temu.com | 3.2 MB / 67 KB | 9 / 9 | adult shopping/travel, not kid-facing |
| khanacademy.org, minecraft.net family, prodigygame.com, poki.io, snapchat.com, duolingo.com, freckle.com, math-playground.com, giphy.com, plex.tv, 1password.com, brave.com, serato.com, gimkit.com, lego.com | various | various | all already covered by existing app templates; host-sets checked against observed subdomains, no gaps found |

## Method note

Ran the full ranked-by-bytes apex sweep (top ~180 entries down to ~40 bytes)
rather than stopping at a fixed cutoff, per the "a below-bar apex from a past
pass is a standing TODO" lesson from #2596 — re-checked prior passes'
skip/watch-items (`apple.news`, `genius.com`, `minecraft.net` sibling infra)
against this run's traffic; none graduated this time except the two
documented above.
