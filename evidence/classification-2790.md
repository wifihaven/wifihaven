# #2790 — orphan-driven app-catalog pass (kid profiles, last 30 days)

Source: read-only prod cloud API (`https://api.wifihaven.net`),
`GET /api/profiles/{id}/usage-by-app?from=2026-08-15&to=2026-09-14` for the
four kid profiles, using the server-side `orphanHosts[]` gap list (Step 1 of
the `app-catalog-pass` skill) rather than a hand diff of `recent-apexes`:

- Kid Laptop / Kid Mac (2), profile `1`
- Quintus iPad `26:74:fc:f9:4e:9e`, profile `5`
- Prima iPad `8a:8a:0b:86:5a:63`, profile `6`
- Octavius iPad `a6:05:9a:63:83:af`, profile `7`

3,948 total `orphanHosts` rows across the four profiles; 1,209 distinct
`fqdn`-typed hosts after de-duplicating and dropping bare IPv4/IPv6 literals
(literal-typed rows are not catalogable — see #2775). Ranked by aggregate
`proportionalSeconds`.

## NEW app template (1)

### `bricklink` — "BrickLink" — hosts `bricklink.com`, `bricklink.info`, `bricksafe.com`

BrickLink was explicitly named and excluded as a "sibling LEGO property on a
distinct apex" when `lego.yml` was written (#1815) — correct at the time (no
observed traffic). This pass shows real, recurring use:

| host | proportional s | presence s |
| --- | --- | --- |
| api.prod.studio.bricklink.info | 7,997 | 1,090 |
| file.bricklink.info | 2,104 | 240 |
| img.bricklink.com | 1,518 | 400 |
| bricksafe.com | 1,235 | 470 |
| cms-api.bricklink.com | 573 | 100 |
| collector.prod.scout.bricklink.com | 561 | 100 |
| www.bricklink.com | 527 | 70 |
| studio.download.bricklink.info | 522 | 40 |
| cache2.bricklink.info | 512 | 40 |
| v2.bricklink.com | 511 | 100 |
| www.v2.bricklink.com | 60 | 60 |
| forum.bricklink.com | 32 | 60 |

Total: 16,152 proportional seconds (~4.5h) / 2,770 presence seconds (~46min)
across 12 rows. The dominant host is `api.prod.studio.bricklink.info` —
BrickLink Studio, LEGO Group's free CAD tool for designing digital builds —
so this is mostly Studio use, not marketplace browsing.

**Host-set: apex-scoped, two zones plus a companion domain.**

- `bricklink.com` — dig confirms `www`, `img`, `forum` all CNAME to the same
  dedicated ALB (`alb-nginx-prod-2108465416.us-east-1.elb.amazonaws.com`,
  `3.225.190.157`/`3.215.211.58`) — a BrickLink-specific load balancer, not a
  shared multi-tenant hostname pattern (contrast the `shops.myshopify.com`
  skip from #2774). `cms-api.bricklink.com` CNAMEs to a dedicated API Gateway
  custom domain (`d-jii7k5trh6.execute-api.us-east-1.amazonaws.com`) — this
  app's own endpoint. `www.bricklink.com` title: "BrickLink - Buy and sell
  LEGO Parts, Sets and Minifigures". The one analytics-labeled child
  (`collector.prod.scout.bricklink.com`) rides along under the bare apex —
  trivial volume (561s/30d), not worth splitting out `amazon-telemetry`-style.
- `bricklink.info` — all four observed children
  (`api.prod.studio.`, `file.`, `studio.download.`, `cache2.`) are Studio-app
  content. `studio.download.bricklink.info` title: "Studio Download
  [BrickLink]". `file.bricklink.info` CNAMEs to a dedicated CloudFront
  distribution (`dkdonnqq45453.cloudfront.net`, `99.84.105.x`) — standard
  accepted Class-2 CDN-edge collateral.
- `bricksafe.com` — companion LEGO file-sharing site ("100% LEGO... share
  your LEGO creations", confirmed via page content), used alongside Studio
  for uploading/downloading build files. Not confirmed under the same
  corporate ownership as BrickLink, but functionally part of the same
  building-community workflow observed here. Resolves on Cloudflare's shared
  `104.21`/`172.67` pool — same accepted Class-2 trade as `rebrickable.yml`.

#1983 overlap check: none of `bricklink.com`, `bricklink.info`,
`bricksafe.com` appear on any curated blocklist.

## Other clusters checked and disposed

| host(s) | proportional s (approx) | disposition |
| --- | --- | --- |
| ipv4.icanhazip.com, ssl.gstatic.com, accounts/docs/drive/play.google*, apple icloud-content edges, office.com/officeapps.live.com | very large | shared Google/Apple/Microsoft platform infra — skip (established pile) |
| googleads*, doubleclick.net, googlesyndication.com, criteo, pubmatic, taboola, outbrain, rubiconproject, casalemedia, amazon-adsystem.com, … | very large, wide | ad/RTB networks — noise, skip |
| mouldkingcorp.com, thetraindepartment.com (+ `the-train-department.myshopify.com`, `monorail-edge.shopifysvc.com`) | 123k/47k s | already-known Shopify shared-origin skip (#2774) — reconfirmed, no change |
| www.lego.com, lego.com, assets.lego.com, cs.analytics.lego.com, identity.lego.com | 4.7k–1.6k s | expected exclusion — LEGO shop/analytics, deliberately outside `lego.yml`'s Builder scope |
| sentry.thelegogroup.com | 960 s | corporate analytics apex, already excluded (#1815) |
| ideas.lego.com, ideascdn.lego.com | 516 s each | LEGO Ideas (community set-idea voting) — below engagement bar, watch-item |
| images.brickset.com | 537 s | Brickset (LEGO fan set database, different operator from BrickLink) — below engagement bar, watch-item |
| api.weather.com, api0.weather.com, weather.com, www.wunderground.com, www.weatherbug.com, prod.weatherfx.com | ~1.3k s each | weather infra / OS-level widgets, not a kid-navigated app — skip |
| www.jrustonapps.net, www.jrustonapps.info | 2.9k/2.4k s | jRustonApps — an indie iOS/Android weather/travel-utility developer's portfolio site (Pollen Forecast, Lightning/Hurricane/Earthquake trackers, etc.); presence time is near-zero (190s/30d total) against notable proportional weight — the background-beacon signature, not a page a kid navigates to. Can't identify which specific installed app is calling home. Skip, below-bar. |
| collect.analytics.unity3d.com, o-sdk.mediation.unity3d.com, i-adq.mediation.unity3d.com | — | Unity Ads/mediation SDK traffic (per #2129 precedent) — ad-tech, skip |
| bam.nr-data.net, cdn.quantummetric.com, cdn.speedcurve.com, ingesteu.quantummetric.com, js.monitor.azure.com, browser.sentry-cdn.com, rum.browser-intake-datadoghq.com | — | third-party page-performance/error monitoring SDKs embedded across many unrelated sites — skip |
| realtime.clinch.co, trk.clinch.co, img-cdn.clinch.co, cdn.clinch.co | — | Clinch (ad-tech dynamic creative) — skip |
| webchat-customer.scholastic.com, clubs.scholastic.com, ltm.scholastic.com, embed.cdn.pais.scholastic.com, www.scholastic.com | 606–546 s | Scholastic Book Clubs — already classified (#2740) as parent/admin commerce, not a kid-engaged app — reconfirmed |
| emojikitchen.dev, backend.emojikitchen.dev | 1,688/508 s | already covered by `emoji-kitchen.yml` (#2778/#2779, merged into `main` just before this pass) — this window mostly predates the template's deployment, so historical orphaning here is expected, not a new gap |
| www.temu.com, www.imdb.com, www.sweetwater.com | ≤1.1k s | adult shopping / media reference — not kid apps, skip |
| emojipedia.org | 711 s | emoji-meanings reference site, different operator from `emoji-kitchen` — below engagement bar, watch-item |
| assets.lego.com | 1,608 s | LEGO shop static assets — expected exclusion (shop side) |

## No host-set changes to existing templates

Checked `lego`, `rebrickable`, `emoji-kitchen`, `amazon`/`amazon-telemetry`,
`arduino`, `giphy` for sibling-domain gaps against this pass's traffic; none
found beyond the BrickLink cluster above.
