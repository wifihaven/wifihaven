# App-catalog pass classification — #2754 (2026-08-31)

Traffic-driven pass per the `/app-catalog-pass` skill. Source: prod
`https://api.wifihaven.net` (read-only), `recent-apexes?windowDays=30&limit=500`
aggregated by bytes across the household's kid devices, pulled fresh from
`GET /api/devices` per the standing "device roster rotates" rule
(#2740 learning) — not reused from a prior pass:

- Kid Laptop (`ca:ef:a1:72:6a:a3`) — 278 apex rows
- Kid Mac (2) (`b0:de:28:25:93:89`) — 0 apex rows (no traffic this window)
- Octavius iPad (`a6:05:9a:63:83:af`) — 113 apex rows
- Prima iPad (`8a:8a:0b:86:5a:63`) — 210 apex rows
- Quintus iPad (`26:74:fc:f9:4e:9e`) — 64 apex rows

## Outcome: 2 new app templates, 1 existing-app host-set gap fixed

- **`canva`** (new) — `www.canva.com` / `static.canva.com` / `template.canva.com`
- **`instructables`** (new) — `www.instructables.com` / `content.instructables.com`
- **`arduino`** (existing, gap) — added `login.arduino.cc`,
  `projecthub-api.arduino.cc`, `builder.arduino.cc`

## New app: Canva

22.46 MB / 34 hits, single device (Kid Laptop). Observed subdomains:
`www`, `static`, `template`, `ct`, `telemetry`. The `www`+`static`+`template`
combination is the shape of a real design-editor session (page load + asset
CDN + template fetches), not a stray link click — same "recurring shape over
raw volume" bar `serato` (#2331) and `freckle` (#2596) cleared at similar or
lower byte counts.

`dig`-verified: `www`/`static`/`template`/`telemetry.canva.com` all resolve to
the same Cloudflare pair (104.16.102.112/104.16.103.112) — Class 2 latent
shared-CDN risk per `_README.yml`, already accepted broadly across the
catalog. `ct.canva.com` resolves off that pool entirely, onto Google's
216.239.32-38.21 range — a click/conversion-tracking redirector.

Kept: `www`, `static`, `template` (the three hosts that actually carry design
content). Excluded: `ct.canva.com` (Google-routed tracking, not app content)
and `telemetry.canva.com` (first-party analytics beacon — same Cloudflare
pool as the kept hosts, so zero *additional* collateral either way, excluded
because the kid's session doesn't depend on it functioning).

## New app: Instructables

8.08 MB / 18 hits, single device (Kid Laptop). Observed subdomains: `www`,
`content`. Autodesk-owned DIY/maker tutorial site, the same product family as
the already-templated `tinkercad` (also Autodesk) and `thingiverse`.

`dig`-verified: bare `instructables.com` resolves to `99.84.118.{29,99,100,106}`
— AWS CloudFront's shared pool, the *exact* pool `arduino.yml` already
documents as collateral risk from the #2753 pass. The two observed subdomains
instead sit on their own dedicated CloudFront distributions:

- `www.instructables.com` → `dwdpktf6trw8q.cloudfront.net` (3.169.202.x)
- `content.instructables.com` → `d38kwnbqqt6im9.cloudfront.net` (13.226.251.x)

Scoped to the two dedicated-distribution subdomains, bare apex excluded — same
move `arduino.yml` and `lego.yml` make for the same reason.

## Existing-app gap: `arduino.yml` login flow was broken

`arduino.cc` was the single largest apex this pass not already fully resolved
(294 MB / 1,691 hits, Quintus iPad) — expected, since `arduino`/`geofs` merged
only this week (#2753, commit `22a66aa3`). Observed subdomains against the
current 10-entry host-set:

| observed subdomain | in host-set? |
|---|---|
| `www`, `cdn`, `content`, `downloads`, `api2` | ✓ already kept |
| `projecthub-api` | ✗ — `projecthub` (frontend) is kept, its API backend wasn't |
| `login` | ✗ |
| `builder` | ✗ |
| `sgtm` | not kept (see below) |
| `api.aayinltcs`, `evs.aayinltcs` | not kept (see below) |
| `forum` | not kept — correctly, per the existing comment (Discourse-hosted) |

Investigated each candidate with `dig`, `curl -I`, and a page-source grep of
`app.arduino.cc` / `create.arduino.cc` / `cloud.arduino.cc` for what those
already-kept pages actually link to:

- **`login.arduino.cc`** — CNAMEs to
  `arduino-prod-cd-7d4guw5p2tbeio38.edge.tenants.auth0.com`, Arduino's own
  dedicated Auth0 tenant (naming embeds "arduino-prod"), fronted by the same
  class of Cloudflare edge (172.64.150.238 / 104.18.37.18) as the rest of the
  kept set — no new collateral risk class. Confirmed **functionally required**,
  not just traffic-present: `curl`-ing the HTML of `app.arduino.cc`,
  `create.arduino.cc`, and `cloud.arduino.cc` (all three already in the
  host-set) shows each one links directly to `login.arduino.cc` for sign-in.
  Without it a kid can load the Cloud Editor shell but can never authenticate
  — this was a functional bug in the template as merged, found by exercising
  the pages the template already allows, not a "new traffic" finding.
- **`projecthub-api.arduino.cc`** — CNAMEs to
  `prd-arduino-prjhub-be-335870183.us-east-1.elb.amazonaws.com`, a
  dedicated Arduino-named ELB, not a shared ALB pool. Companion API backend
  for the already-kept `projecthub.arduino.cc` frontend — added for the same
  reason `api2.arduino.cc` pairs with `cloud`/`app`/`create`.
- **`builder.arduino.cc`** — `dig`-verified to resolve to the *identical* four
  IPs as `api2.arduino.cc` (18.238.176.{7,46,72,103}); `curl -I` on both
  returns the same `awselb`+CloudFront error signature. Same CloudFront
  distribution already accepted for the Cloud API, so adding it is zero
  incremental collateral. Real hits observed; exact purpose beyond that isn't
  publicly documented, kept on same-distribution + observed-use grounds.
- **`sgtm.arduino.cc`** — resolves to an isolated Google Frontend IP
  (34.110.195.2), response headers (`server: Google Frontend`,
  `x-cloud-trace-context`) match a server-side Google Tag Manager container.
  Tracking/analytics relay, not app content — **excluded**, same call as
  excluding `ct.canva.com` above.
- **`api.aayinltcs.arduino.cc`**, **`evs.aayinltcs.arduino.cc`** — the
  `aayinltcs` label is a randomized-looking string with no recognizable
  Arduino product name, and the `api`/`evs` (events) subdomain shape is
  typical of a CNAME-cloaked third-party telemetry vendor embedded on the
  page. No CNAME resolves to any identifiable third party (masked, IPs spread
  across unrelated AWS ASNs/regions for the two hosts) — **excluded** as
  likely tracking collateral, not confirmed app surface.
- **`forum.arduino.cc`** — already correctly excluded by the existing
  template comment (Discourse-hosted community, shared IP with unrelated
  Discourse tenants). Re-observed this pass, no change.

## Below engagement bar — watch-items, not gaps

| apex | bytes | hits | subdomains | note |
|---|---:|---:|---|---|
| `jrustonapps.info` | 33.9 MB | 4 | `www` only | web-confirmed: jRustonApps B.V., a mobile-app developer (weather/travel/utility iOS+Android apps). High-byte/very-low-hit shape = one large asset/app download, not recurring use (same tell as `ytmp3.gg`, #2058) |
| `jrustonapps.net` | 1.0 MB | 10 | `www` only | same developer, alternate TLD; still thin, single subdomain |
| `rexqualis.com` | 1.9 MB | 4 | `www` only | web-confirmed: REXQualis, an Arduino-kit accessory/electronics-kit vendor (adjacent to this household's newly-templated `arduino` app) — but single `www` hit shape reads as a one-off tutorial-page visit, not recurring engagement, same bar `scholastic.com` failed in #2740 |
| `sparkfun.com` | 67.8 KB | 3 | — | same class as `rexqualis.com`: electronics-kit vendor, thin one-off traffic |

None graduate this pass. Re-check next pass per the standing "prior
watch-items are a standing TODO" rule (#2596 learning).

## Coverage sweep — everything else maps cleanly

Ranked apex table cross-checked against `_index.yml` slugs and each matched
app's host-set (not just the slug, per the #2490 learning that a large apex
matching a slug name can still be mostly unrelated collateral):

| apex(es) | disposition |
|---|---|
| `apple.com`, `cdn-apple.com`, `googlevideo.com`, `gvt1.com`/`gvt2.com`/`gvt3.com`, `safebrowsing.apple`, `icloud-content.com`, `aaplimg.com`, `icloud.com`, `mzstatic.com`, `edge.apple`, `googleusercontent.com`, `googlezip.net`, `one.one` | **skip** — OS/device background chatter (Apple/Google), not a branded kid experience |
| `googleapis.com`, `google.com`, `gstatic.com`, `googletagmanager.com`, `google-analytics.com`, `app-measurement.com`, `app-analytics-services.com`, `googletagservices.com`, `googleadservices.com`, `withgoogle.com` | **skip** — shared Google API/analytics pools, no independent branded surface (Class 1 collateral, per `_README.yml`) |
| `flashtalking.com`, `googlesyndication.com`, `adtrafficquality.google`, `doubleclick.net`, `casalemedia.com`, `pubmatic.com`, `rubiconproject.com`, `nitropay.com`, `3lift.com`, `openx.net`, `everesttech.net`, `amazon-adsystem.com`, `2mdn.net`, `richaudience.com`, `ad-score.com`, `adsafeprotected.com`, `onetag-sys.com`, `adnxs.com`, `forter.com`, `smartadserver.com`, `sharethrough.com`, `bidbrain.app`, `samplicio.us`, `indexww.com`, `adsrvr.org`, `criteo.com`, `demdex.net`, `adform.net`, `doubleverify.com`, `rlcdn.com`, `yieldmo.com`, `bidswitch.net`, `sonobi.com`, `criteo.net`, `id5-sync.com`, `eu-1-id5-sync.com`, `tapad.com`, `connatix.com`, `taboola.com`, `inmobi.com`, `inmobicdn.net`, `monetate.net`, `peer-39.com`, `unrulymedia.com`, `ad-delivery.net`, `scorecardresearch.com`, `pendo.io`, `ads-twitter.com`, `sentry-cdn.com`, `datadoghq-browser-agent.com` | **skip** — ad-tech/RTB/analytics, matches the standing pattern |
| `ssl-images-amazon.com`, `amazon.com`, `media-amazon.com`, `amazonaws.com`, `a2z.com`, `amazon.dev` | **skip** — Amazon shopping/AWS shared infra, not a kid app |
| `arduino.cc`, `arduinocontent.cc` | `arduino` app ✓ — see gap fix above |
| `geo-fs.com` | `geofs` app ✓ — apex-scoped, all 5 observed subdomains (`app21`/`data`/`mps`/`weather`/`www`) suffix-match cleanly, no gap |
| `khanacademy.org`, `kastatic.org` | `khan-academy` app ✓ |
| `lego.com` | `lego` app ✓ |
| `zoom.us` | `zoom` app ✓ |
| `icloud.com` (already listed above) | — |
| `mathacademy.com`, `d3js.org`, `jsdelivr.net` | `math-academy` app ✓ |
| `tinkercad.com` | `tinkercad` app ✓ |
| `duolingo.com` | `duolingo` app ✓ |
| `eaglercraft.dev`, `eaglercraft.ru`, `eaglercraft.com`, `eaglercraftgame.io`, `lax1dude.net`, `deev.is`, `shhnowisnottheti.me` | `eaglercraft` app ✓ |
| `workers.dev` (`eaglercraft-counter.eaglercraft-99f.workers.dev` + unrelated `pioeg.admetricspro.workers.dev`) | mixed: eaglercraft subdomain ✓, `admetricspro` subdomain **skip** (ad-tech) — same split as #2740, unchanged |
| `1password.com`, `wifihaven.net`, `canva.com` (see above), `poki.com`/`poki.io`/`poki-cdn.com`, `gimkit.com`/`gimkitconnect.com`, `apple.news`, `strava.com`, `mcsrvstat.us`, `scholastic.com`, `serato.com`, `freckle.com`, `mathplayground.com`, `snapchat.com`, `instructables.com` (see above), `prodigygame.com`, `giphy.com`, `facebook.com` | app-matched or already-classified per prior passes; each re-checked against its full host-set this pass, no gaps found beyond the arduino fix |
| `apple.news`, `strava.com`, `scholastic.com`, `mcsrvstat.us` | **skip**, unchanged from #2740/#2490 reasoning (single-edge OS app, adult fitness tracker, parent commerce flow, shared multi-tenant status API) |
| `southwest.com`, `hamstudy.org`, `boulderperformingarts.com`, `linkedin.com` | **skip** — adult/parent-account traffic (airline booking, ham-radio study site, performing-arts venue, professional network), not kid-facing |
| `1passwordusercontent.com` | `1password` app ✓ — already in host-set (added #2331) |
| `deepai.org` | **skip** — `ai` blocklist category already covers AI tools categorically; not app-catalog territory |
| `duckmath.org` | `games.yml` ✓ — confirmed still present |
| `cloudflare.com`, `cloudfront.net`, `fastly-edge.com`, `akamai.net`, `akamaized.net`, `akamaiedge.net`, `akadns.net`, `edgesuite.net`, `edgekey.net`, `github.io`, `githubusercontent.com`, `jsdelivr.net`, `unpkg.com`, `onrender.com`, `wixstatic.com`, `wixapps.net`, `wix.com`, `parastorage.com`, `azure.com` | **skip** — shared hosting/CDN infra |
| `digicert.com`, `rapidssl.com`, `comodoca.com`, `usertrust.com` (not observed this pass but same class) | **skip** — CA/TLS infra |
| `sentry.io`, `launchdarkly.com`, `clarity.ms`, `nr-data.net`, `bugsnag.com`, `dynatrace.com`, `iubenda.com`, `cookielaw.org`, `onetrust.com`, `privacymanager.io`, `cloudflareinsights.com`, `nel.goog`, `sgtm.arduino.cc` (see above), `activemetering.com`, `webcontentassessor.com`, `signalstuff.com`, `zeronaught.com`, `p7cloud.net`, `koah.ai`, `trygravity.ai`, `kidsafe.com`, `gt162037.com`, `revenuecat.com`, `crashlytics.com`, `tiqcdn.com`, `sc-static.net`, `redditstatic.com`, `reddit.com`, `yahoo.com`, `bing.com`, `microsoft.com`, `adobe.com`, `adobe.io`, `adobelogin.com`, `adobeccstatic.com`, `adobedtm.com`, `typekit.net`, `autodesk.com`, `salesforce-scrt.com`, `sendtonews.com`, `ampproject.org`, `assertcom.de`, `recaptcha.net`, `calculator.net`, `pypi.org`, `python.org`, `fwupd.org`, `ntv.io`, `shopify.com`, `licdn.com`, `samba.tv`, `wixapps.net`, `editmysite.com`, `apple-cloudkit.com`, `arkoselabs.com`, `kvaedit.site`, `site.com` | **skip** — analytics/error-reporting/consent-management/shared-corporate-infra/below-bar incidental, no branded kid surface |
| `thelegogroup.com` | **skip** — LEGO corporate error-reporting, excluded by design (#1815) |

Everything at or below `~40 KB` bytes and 1-hit rows not individually
enumerated: same shape (ad-tech tail, CA infra, single-request incidental),
all skip.
