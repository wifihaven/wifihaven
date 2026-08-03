# App-catalog pass classification — #2596 (2026-08-03)

Traffic-driven pass per the `/app-catalog-pass` skill. Source: prod
`https://api.wifihaven.net` (read-only), `recent-apexes?windowDays=30&limit=500`
aggregated by bytes across the five kid-profile devices — Kid Mac
(`ca:ef:a1:72:6a:a3`, profile "Kids"), Octavius iPad (`a6:05:9a:63:83:af`,
profile "Octavius"), Prima iPad (`04:72:ef:d6:e4:5a`) and Prima iPad (2)
(`8a:8a:0b:86:5a:63`, profile "Prima"), Quintus iPad (`26:74:fc:f9:4e:9e`,
profile "Quintus"). #1796/#1807/#1802 IPv6 attribution remains fixed per the
#1922 re-confirmation; sample is not IPv4-biased.

## Outcome: one new app template, three existing-app host-set extensions

## New app: Freckle

| apex | bytes | hits | disposition |
|------|------:|-----:|-------------|
| freckle.com | 2.91 MB | 7 | **new app** — Freckle by Renaissance, K-8 math/ELA adaptive practice |

Subdomains observed: `api`, `images`, `student`, `translations`, `tts`,
`tts-assets`, `vendor-assets` — a genuine student-portal session (dashboard +
API + localized TTS audio + vendor assets), not a single stray beacon, despite
the low absolute hit count. Web search confirms Freckle by Renaissance is a
real, widely-assigned K-8 practice product with a `student.freckle.com`
dashboard. Templated as `freckle` with host `freckle.com` (suffix-matches all
observed subdomains).

Two adjacent low-signal apexes were left untemplated as watch items:
`renaissance.com` (`ui.renaissance.com`, 1 hit, 75 KB — likely the parent
company's dashboard/auth, not confirmed as part of the Freckle student flow)
and `readingeggspress.com` (2 hits, 172 KB, no subdomains resolved — too thin
to confirm a real session). Extend `freckle.yml` or template separately if
either grows.

## Existing-app host-set extensions

| app | host added | bytes | hits | evidence |
|-----|-----------|------:|-----:|----------|
| `eaglercraft` | eaglercraft.ru | 17.5 MB | 10 | `cdn.eaglercraft.ru` — a third Eaglercraft browser-Minecraft mirror. Already in `blocklists/games.yml` (#2212) but not wired into the app template. #2331's evidence doc flagged this exact apex as a 1-hit/40-byte "watch-item... extend the app if it grows" — it grew from 40 bytes to 17.5 MB this pass, confirming the prediction. |
| `youtube` | youtubekids.com | 16.6 MB | 167 | `www.youtubekids.com`, observed on 3 of 5 kid devices. #2331's evidence doc skipped this apex at 14.8 KB/1 hit as "below engagement bar" — now a clear multi-device recurring pattern. |
| `minecraft` | minecraft-services.net | 182 KB | 2 | `net-secondary.web.minecraft-services.net` — confirmed via web search as genuine Minecraft Bedrock backend infra, distinct domain from the already-templated `minecraftservices.com`. Low byte count but unambiguous first-party naming; same bar as the #2331 1Password `agilebits.com`/`1passwordusercontent.com` extensions (low-traffic but clearly first-party). |

## Per-apex disposition (top band by bytes; shared infra/CDN/ad-tech elided)

| apex | bytes | hits | disposition |
|------|------:|-----:|-------------|
| apple.com / cdn-apple.com / icloud.com / icloud-content.com / mzstatic.com / gvt1-3.com / gstatic.com / googleusercontent.com / ggpht.com / aaplimg.com | multi-GB down to single-digit MB | high | **skip** — shared OS/platform infra (App Store, push, iCloud sync, Chrome/Android updates); re-confirms the #2490 "large-byte apex ≠ actionable" trap |
| googlevideo.com / ytimg.com / googleapis.com | large | high | `youtube`/`youtube` shared-pool hosts ✓ (already templated) |
| duolingo.com | 167 MB | 634 | `duolingo` app ✓ |
| mathacademy.com | 156 MB | 229 | `math-academy` app ✓ |
| lego.com | 155 MB | 261 | `lego` app ✓ — LEGO Builder sub-experience scoping re-confirmed, no new gap this pass |
| minecraft.net | 85 MB | 6 | `minecraft` app ✓ |
| eaglercraft.dev / eaglercraft.com | 15 MB / 1 MB | 9 / 13 | `eaglercraft` app ✓ |
| serato.com / sera.to | 13.4 MB / 4.8 MB | 496 / 3 | `serato` app ✓ |
| gimkit.com / gimkitconnect.com | 15.6 MB / 3.6 MB | 26 / 19 | `gimkit` app ✓ |
| mathplayground.com | 14.3 MB | 19 | `math-playground` app ✓ |
| poki.com / poki-cdn.com / poki.io | 12 MB / 6.8 KB / 1.3 MB | 23 / 21 / 54 | `poki` app ✓ |
| apple.news | 10.3 MB | 122 | **skip** — single-edge OS-bundled Apple service, not an independent branded surface (#2490 precedent) |
| 1password.com / 1passwordservices.com / agilebits.com / 1passwordusercontent.com | 8.8 MB / — / 652 KB / — | 851 / — / 96 / — | `1password` app ✓, no further gap |
| southwest.com / sweetwater.com | 8.3 MB / 73 MB | 13 / 11 | **skip** — adult travel/retail, not kid apps |
| feelinggreat.com | 8.1 MB | 57 | `feeling-great` app ✓ |
| gimkitconnect.com / genius.com | 3.6 MB / 3.3 MB | 19 / 10 | genius.com: **skip** — pure `assets`/`t2`/`librato-collector` analytics, zero navigational hits (re-confirms #2490) |
| khanacademy.org | 1.8 MB | 8 | `khan-academy` app ✓ |
| giphy.com | 1.85 MB | 3 | `giphy` app ✓ |
| plex.tv | 2.3 MB | 239 | `plex` app ✓ |
| unity3d.com | 503 KB | 44 | **skip** — ad-tech mediation (`mediation.unity3d.com`), not the game engine (#2129 learning) |
| duckmath.org | 651 KB | 4 | `games.yml` ✓ (block-only, already listed) |
| thelegogroup.com | 123 KB | 14 | **skip** — LEGO corporate-analytics exclusion, re-confirmed |
| minecraft-services.net | 182 KB | 2 | `minecraft` app extension (see above) |
| a-z-animals.com / prodigygame.com | not in top-100 this pass but present | — | `a-z-animals` / `prodigy` apps ✓ |

Everything else in the top-350 apex band is ad-tech/RTB (flashtalking,
googlesyndication, doubleclick, casalemedia, rubiconproject, pubmatic, mgid,
criteo, adnxs, id5-sync, 33across, openx, and dozens more), analytics/error/
consent (sentry, bugsnag, launchdarkly, quantummetric, iubenda, onetrust,
qualtrics, segment), CDN/cert infra (digicert, akamai*, cloudflare*, fastly*,
godaddy, sectigo), or shared corporate (adobe.com/adobe.io, microsoft.com,
zoom.us). All skip.
