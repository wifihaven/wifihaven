# App-catalog pass classification — #2331 (2026-07-20)

Traffic-driven pass per the `/app-catalog-pass` skill. Source: prod
`https://api.wifihaven.net` (read-only), `recent-apexes?windowDays=30&limit=500`
aggregated by bytes across the four kid devices — Kid Mac
(`ca:ef:a1:72:6a:a3`), Octavius iPad (`a6:05:9a:63:83:af`), Prima iPad
(`04:72:ef:d6:e4:5a`), Quintus iPad (`26:74:fc:f9:4e:9e`).

## Outcome: one new app template, one existing-app host-set extension

## New app: Serato

| apex | bytes | hits | device | disposition |
|------|------:|-----:|--------|-------------|
| serato.com | 8.99 MB | 106 | Kid Mac | **new app** — DJ/music-production software |
| sera.to | 4.78 MB | 10 | Kid Mac | folded into the `serato` app — Serato's own CDN/short-link domain |

`serato.com` subdomains observed: `api`, `ecom`, `id`, `insights`, `license`,
`my`, `myserato`, `notifications`, `profile`, `rewards`, `static`, `whatsnew`
— all first-party account/licensing/rewards surface, no third-party ad-tech
mixed in. `sera.to` resolves to `in.api.sera.to` + `m.cdn.sera.to`; web search
confirms `a.cdn.sera.to` serves Serato DJ software downloads directly, so it's
Serato's dedicated infra, not a shared shortener — included alongside the apex
per the same reasoning as `plex.direct` (plex.yml) and `eaglercraft-99f.workers.dev`
(eaglercraft.yml): account-scoped/brand-owned subdomain of a shared platform,
not the shared platform itself.

Consistent hit pattern (106 hits spread across license checks, rewards,
notifications, not a single burst) over the 30-day window indicates genuine
recurring software use on Kid Mac, not incidental/marketing traffic — warrants
a template (time-limit/allow/block surface), same bar as brave/1password/plex.

## Existing-app host-set extension: 1Password

| apex | bytes | hits | devices | disposition |
|------|------:|-----:|---------|-------------|
| agilebits.com | 251 KB | 26 | all 4 kid devices | added to `1password.yml` |
| 1passwordusercontent.com | 66 KB | 5 | 3 of 4 kid devices | added to `1password.yml` |

`agilebits.com` (`cache.agilebits.com`, `op7.agilebits.com`) is 1Password's
legacy company domain — AgileBits was the original company name — and serves
the favicon/site-icon cache the browser extension/app uses to show icons next
to vault items. `1passwordusercontent.com` (`a.1passwordusercontent.com`)
hosts encrypted vault attachment storage. Both are first-party 1Password infra
required for normal use, present on every kid device with a 1Password
install, and were unattributed to the `1password` app before this pass — same
class of gap as the `eaglercraft.com` extension in #2058.

## Per-apex disposition (rest of the top-80-by-bytes band; shared infra/CDN/ad-tech elided)

| apex | bytes | hits | disposition |
|------|------:|-----:|-------------|
| lego.com | 1005 MB | 998 | `lego` app ✓ — see LEGO note below; no extension (re-confirms #2129) |
| mathacademy.com | 99 MB | 337 | `math-academy` app ✓ |
| prodigygame.com | 101 MB | 347 | `prodigy` app ✓ |
| duolingo.com | 185 MB | 223 | `duolingo` app ✓ |
| gimkit.com / gimkitconnect.com | 27 MB | 60 | `gimkit` app ✓ (both hosts) |
| brave.com | 12 MB | 201 | `brave` app ✓ |
| elevenlabs.io | 11 MB | 23 | **skip** — only `api.elevenlabs.io`; shared-API collateral (#1922 learning), also wired via `feeling-great` shared_hosts |
| plex.tv | 2.4 MB | 254 | `plex` app ✓ |
| mathplayground.com | 4.6 MB | 21 | `math-playground` app ✓ |
| giphy.com | 4.5 MB | 4 | `giphy` app ✓ |
| a-z-animals.com | 76 KB | 1 | `a-z-animals` app ✓ |
| sweetwater.com | 73 MB | 11 | **skip** — adult music-gear retail, not a kid app (#2129 precedent) |
| coolmathgames.com / duckmath.org | 0.15 MB / 18 KB | 3 / 1 | `games.yml` ✓ (block-only, not apps) |
| eaglercraft.com | 40 B | 1 | `eaglercraft` app ✓ (below-bar hit this week, negligible) |
| eaglercraft.ru | 40 B | 1 | **skip** — new mirror apex, single DNS-lookup-scale hit; watch-item, extend the `eaglercraft` app if it grows |
| youtubekids.com | 14.8 KB | 1 | **skip** — below engagement bar |
| ytmp3.gg / yt2mp3.gs | 3.6 MB / 6 KB | 1 / 2 | **skip** — YouTube-to-MP3 rippers, no clean piracy category (#2058 learning) |
| ftstatic.com | 8.5 MB | 7 | **skip** — Freshworks/Freshchat embedded-widget static assets (agen-assets/ajs-assets), not a standalone app |
| unity3d.com | 1.2 MB | 11 | **skip** — ad-tech/RTB mediation, not the game engine (#1922 learning) |

Everything else in the top-250 apex band is shared infra/CDN (apple/google/
icloud/akamai/fastly/cloudfront/gstatic/gvt/ggpht/mzstatic/aaplimg/edgekey/
edgesuite/akadns), analytics/error/consent (segment, posthog, sentry, bugsnag,
launchdarkly, medallia, quantummetric, iubenda, onetrust, trustarc, qualtrics),
ad-tech/RTB (googlesyndication, doubleclick, admetricspro, rtbhouse, id5-sync,
nitropay, pubmatic, mgid, adjust.io), payment/identity infra (stripe, paypal,
braintree, auth0, digicert), or shared corporate (adobe.com/adobe.io). All skip.

## LEGO host-set — no gap (negative check, re-confirms #2129)

The `lego` app remains scoped to the LEGO Builder sub-experience
(`cobuild.i.lego.com`, `dbix.i.lego.com`, `services.lego.com`,
`apps.lego.com`). This week's observed subdomains all suffix-match one of
those four entries except `assets.lego.com`, `avatar.lego.com`,
`consent.lego.com`, `cs.analytics.lego.com`, `identity.lego.com`,
`image.content.lego.com`, and `www.lego.com` — the same shop/account/
marketing-side hosts identified as correctly-excluded in #2129. No extension.
