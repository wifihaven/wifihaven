# App-catalog pass classification — #2490 (2026-07-27)

Traffic-driven pass per the `/app-catalog-pass` skill. Source: prod
`https://api.wifihaven.net` (read-only), `recent-apexes?windowDays=30&limit=500`
aggregated by bytes across the household's four kid devices.

## Outcome: no new app template, no new blocklist entry

The catalog remains mature. Every apex with meaningful kid traffic maps to an
existing app template or curated blocklist, or is shared Apple/Google
ecosystem infra that's already documented elsewhere as collateral (not a new
finding — just reconfirmed this run).

## Per-apex disposition (top of the ranked byte table; ad-tech/RTB/analytics/CDN noise elided)

| apex | bytes | hits | disposition |
|------|------:|-----:|-------------|
| apple.com | 17.8 GB | 17548 | **mostly shared Apple OS infra** (App Store, Weather, Find My, system services) — only the `ess.apple.com` sliver is templated (`imessage` app, #1529); the rest is Truth-#1-documented shared-IP collateral, not actionable |
| google.com | 7.3 GB | 5449 | **mostly shared Google infra** — only `play.google.com` is templated (`google-play` app, #1705/#1661); rest is Search/Drive/Gmail collateral, not actionable |
| cdn-apple.com / mzstatic.com / aaplimg.com / icloud.com / icloud-content.com / apple-cloudkit.com / safebrowsing.apple | 7.59 GB combined | 3989 | **skip** — shared Apple CDN/sync/safety infra, no brand-specific kid-facing surface |
| googleapis.com / googleusercontent.com / gstatic.com / gvt1-3.com / ggpht.com | 4.80 GB combined | 6214 | **skip** — shared Google CDN/API infra |
| lego.com | 203 MB | 323 | `lego` app ✓ — Builder sub-experience hosts still all covered; see negative check below |
| duolingo.com | 164 MB | 158 | `duolingo` app ✓ |
| mathacademy.com | 101 MB | 360 | `math-academy` app ✓ |
| prodigygame.com | 101 MB | 347 | `prodigy` app ✓ |
| sweetwater.com | 73 MB | 11 | **skip** — adult music-gear retail, not a kid app (recurring skip, #2129) |
| brave.com | 46 MB | 366 | `brave` app ✓ |
| gimkit.com / gimkitconnect.com | 20 MB | 37 | `gimkit` app ✓ (both hosts) |
| mathplayground.com | 13 MB | 19 | `math-playground` app ✓ |
| adobe.com / adobe.io / adobelogin.com / adobedc.net | 15 MB combined | 262 | **skip** — shared corporate (recurring skip) |
| serato.com / sera.to | 16 MB | 258 | `serato` app ✓ (host-set gap fixed #2331) |
| feelinggreat.com | 9 MB | 62 | `feeling-great` app ✓ |
| khanacademy.org / kastatic.org | 2.1 MB | 7 | `khan-academy` app ✓ |
| 1password.com / 1passwordservices.com / agilebits.com / 1passwordusercontent.com | 6.7 MB | 617 | `1password` app ✓ (host-set gap fixed #2331) |
| plex.tv / plex.direct | 1.4 MB | 62 | `plex` app ✓ |
| a-z-animals.com | 0.08 MB | 1 | `a-z-animals` app ✓ |
| giphy.com | 5.2 MB | 6 | `giphy` app ✓ |
| genius.com | 3.3 MB | 10 | **skip** — only `assets`/`t2`/`librato-collector` (analytics/tracking) subdomains observed, no genuine lyrics-page navigation; below engagement bar |
| apple.news | 6.3 MB | 89 | **skip-with-note** — only `c.apple.news` (content-delivery edge for the bundled News app); no dedicated brand surface distinct from the OS-bundled experience, same class as other single-edge Apple services (imessage's `ess.apple.com` is the one exception that got a template, driven by explicit operator ask in #1529) |
| ytmp3.gg | 3.6 MB | 1 | **skip** — YouTube-to-MP3 ripper, one download; no clean piracy category (#2058 learning) |
| youtubekids.com | 0.015 MB | 1 | **skip-with-note, watch-item** — single visit (`www.youtubekids.com`) is below the recurring-engagement bar this run; re-evaluate for a `youtube` host-set extension if it recurs |
| readingeggspress.com | 0.08 MB | 1 | **skip-with-note, watch-item** — single visit, no subdomain detail; re-evaluate if it recurs |

Everything else in the >0.5 MB band is shared infra/CDN (cloudflare, cloudfront,
fastly-edge, akamai, jsdelivr, website-files.com — generic Webflow CDN),
analytics/error/consent (sentry.io, medallia, cookielaw.org, nel.goog,
app-analytics-services.com), ad-tech/RTB (admanmedia, adtrafficquality.google),
or one-off low-hit incidental hosts (southwest.com, fandom.com, paypal.com,
hcaptcha.com). All skip.

## LEGO host-set — no gap (negative check, reconfirmed)

The `lego` app stays scoped to the LEGO Builder sub-experience
(`cobuild.i.lego.com`, `dbix.i.lego.com`, `services.lego.com`,
`apps.lego.com`). `thelegogroup.com` (175 kB / 22 hits this run, all under
`sentry.thelegogroup.com`) is the corporate error-reporting/analytics apex —
already named and deliberately excluded in the template's own comments
(#1815). No change; consistent with the 97 kB figure recorded when the
exclusion was first documented.

## Apple/Google shared-apex clarification (not a new finding)

`apple.com` and `google.com` show up as "app=[...]" in a naive per-apex
match because the aggregate apex includes the templated sliver hosts
(`ess.apple.com`, `play.google.com`) alongside a much larger volume of
unrelated shared-platform traffic. This is the expected, already-documented
shape (imessage.yml and google-play.yml both call it out explicitly) — not an
actionable gap. Recorded here so a future pass doesn't mistake the large byte
totals for "fully covered."
