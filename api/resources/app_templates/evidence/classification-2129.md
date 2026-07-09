# App-catalog pass classification — #2129 (2026-07-08)

Traffic-driven pass per the `/app-catalog-pass` skill. Source: prod
`https://api.wifihaven.net` (read-only), `recent-apexes?windowDays=30&limit=500`
aggregated by bytes across the four kid devices — Kid Mac
(`ca:ef:a1:72:6a:a3`), Octavius iPad (`a6:05:9a:63:83:af`), Prima iPad
(`04:72:ef:d6:e4:5a`), Quintus iPad (`26:74:fc:f9:4e:9e`).

## Outcome: no new app template, no new blocklist entry

The catalog is mature. Every apex with meaningful kid traffic maps to an
existing app template or curated blocklist, and — unlike the 2026-06-29 pass
(#2058) — there is **no host-set gap** on an existing app this week either.

## Per-apex disposition (top of the ranked byte table; shared infra/CDN/ad-tech elided)

| apex | bytes | hits | disposition |
|------|------:|-----:|-------------|
| lego.com | 1025 MB | 1089 | `lego` app ✓ — see LEGO note below; no extension |
| mathacademy.com | 141 MB | 652 | `math-academy` app ✓ |
| prodigygame.com | 96 MB | 347 | `prodigy` app ✓ |
| duolingo.com | 71 MB | 324 | `duolingo` app ✓ |
| khanacademy.org | 24 MB | 92 | `khan-academy` app ✓ (`kastatic.org` also covered) |
| gimkit.com / gimkitconnect.com | 26 MB | 59 | `gimkit` app ✓ (both hosts) |
| tinkercad.com | 11 MB | 23 | `tinkercad` app ✓ |
| elevenlabs.io | 11 MB | 23 | **skip** — only `api.elevenlabs.io`; shared-API collateral, no branded web app the kid navigates to (#1922 learning) |
| feelinggreat.com | 5 MB | 81 | `feeling-great` app ✓ |
| mathplayground.com | 4 MB | 24 | `math-playground` app ✓ |
| sweetwater.com | 18 MB | 6 | **skip** — adult music-gear retail (`www`/`auth`/`assets`), not a kid app |
| unity3d.com | 1.6 MB | 13 | **skip** — all `mediation`/`adq`/`isx` subdomains = Unity **Ads** RTB, not the game engine; ad-tech (classify by subdomain shape, not apex name) |
| tenor.com | 0.9 MB | 1 | **skip** — only `media.tenor.com` (Google's embedded-GIF CDN, giphy-adjacent); below engagement bar. Watch-item if it grows |
| coolmathgames.com | 0.14 MB | 3 | `games.yml` ✓ |
| eaglercraft.com | 0.31 MB | 7 | `eaglercraft` app ✓ (mirror added #2058) |
| dancemattypingguide.com | 0.24 MB | 1 | `dance-mat-typing` app ✓ |
| ytmp3.gg | 3.4 MB | 1 | **skip** — YouTube-to-MP3 ripper, one download; no clean piracy category (#2058 learning) |

Everything else in the >1 MB band is shared infra/CDN (apple/google/icloud/
akamai/fastly/cloudfront/gstatic/gvt/ggpht/mzstatic/aaplimg), analytics/error/
consent (segment, posthog, sentry, launchdarkly, medallia, quantummetric,
singular, cookielaw), ad-tech/RTB (googlesyndication, doubleclick, adtrafficquality,
nitropay, admetricspro, btloader, ad.gt, app-ads-services), or shared corporate
(adobe.com / adobe.io). All skip.

## LEGO host-set — no gap (negative check)

The `lego` app is scoped to the LEGO Builder sub-experience:
`cobuild.i.lego.com`, `dbix.i.lego.com`, `services.lego.com`, `apps.lego.com`.
All observed building subdomains suffix-match one of those entries
(`HostMatch.matchesApex`):

- `api.prod.cobuild.i.lego.com` → `cobuild.i.lego.com`
- `api.prod.dbix.i.lego.com`, `assets.prod.dbix.i.lego.com`,
  `biapp.prod.dbix.i.lego.com`, `imageresizer.prod.dbix.i.lego.com` → `dbix.i.lego.com`
- `appconfig`/`appstate`/`scout`/`c.scout`/`allowed-countries.scout`/
  `npssurveyinvite`/`products.engagement`/`videoprocessingpipeline`.services.lego.com → `services.lego.com`
- `buggy.apps.lego.com` → `apps.lego.com`

The uncovered `lego.com` hosts (`www`, `assets`, `avatar`, `consent`,
`identity`, `image.content`, `cs.analytics`) are the shop / marketing /
identity / analytics side — **deliberately excluded** by the #1815 sub-experience
scoping. No extension needed.
