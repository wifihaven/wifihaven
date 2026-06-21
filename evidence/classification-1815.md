# #1815 — traffic-driven app-catalog pass (kid devices, last 30 days)

Source: read-only prod cloud API (`https://api.wifihaven.net`),
`GET /api/devices/:mac/recent-apexes?windowDays=30&limit=500` for the four kid
devices, aggregated by apex bytes:

- Octavius iPad `a6:05:9a:63:83:af` (profile 7)
- Prima iPad `04:72:ef:d6:e4:5a` (profile 6)
- Quintus iPad `26:74:fc:f9:4e:9e` (profile 5)
- Kid Mac `ca:ef:a1:72:6a:a3` (profile 1)

> **CAVEAT:** IPv6 host attribution is broken on prod (#1796; fixes #1807/#1802
> not yet deployed), so this sample is IPv4-biased. Candidates were cross-checked
> with web research, not byte counts alone. A quiet apex was not treated as "no
> traffic."

## Top uncovered apexes (in "Other"), and disposition

| apex             | kid bytes / hits | disposition |
| ---------------- | ---------------- | ----------- |
| lego.com         | 277 MB / 178     | **NEW template `lego`** (LEGO Builder — see below) |
| duckmath.org     | 65 MB / 3        | **NEW template `duckmath`** (unblocked-games / filter-bypass portal) |
| icloud-content.com, apple-dns.net, apple.com, fastly.net, akadns.net, aaplimg.com, gvt2.com | large | infra / Apple+Google service pools — NOT templatable (shared) |
| flashtalking.com, innovid.com, adsrvr.org, doubleclick.net, pubmatic.com, casalemedia.com, … | large | ad / RTB networks — noise, skip |
| kastatic.org, kasandbox.org | 381 / 12 MB | already covered by `khan-academy` |
| mathacademy.com  | 120 MB           | already covered by `math-academy` |
| gimkitconnect.com, gimkit.com | 15 MB     | already covered by `gimkit` |
| adobe.com / adobe.io / autodesk.com | medium | shared corporate infra (Adobe CC / Autodesk login), not a single kid app — skip |
| tenor.com        | ~1 MB            | low signal + Google-shared infra — skip |
| mathplayground.com | 181 kB / 4     | below engagement bar — skip (don't template incidental hosts) |
| temu.com, ticketm.net, apple.news, reddit.com | low | not a kid app — skip |

## NEW templates (2)

### `lego` — "LEGO Builder" — host `lego.com`

Largest uncovered cluster. Observed subdomains are unambiguously the **LEGO
Builder** 3D building-instructions / co-build experience, not the shop:

```
api.prod.cobuild.i.lego.com         co-build (build-together) backend
api.prod.dbix.i.lego.com            digital building-instructions experience
assets.prod.dbix.i.lego.com        build-instruction assets
biapp.prod.dbix.i.lego.com          building-instructions app service
imageresizer.prod.dbix.i.lego.com   instruction-image resizer
appconfig.services.lego.com         Builder app config
scout.services.lego.com             Builder app service
videoprocessingpipeline.services.lego.com
buggy.apps.lego.com                 Builder app surface
```

**Lego Builder vs other Lego properties.** The Builder building experience is
served from subdomains of the `lego.com` apex (`*.i.lego.com`,
`*.services.lego.com`, `*.apps.lego.com`). The router matches host-sets by apex
suffix (dnsmasq `nftset=`), so these cannot be separated from the LEGO shop
(`www.lego.com`), which shares the same apex — hence a single `lego` app on the
`lego.com` apex (the explicitly-allowed fallback in #1815). The observed traffic
is overwhelmingly the Builder app, so the time-limit budget tracks real building
activity. Sibling LEGO properties live on **distinct apexes** and are
deliberately excluded: **BrickLink** (`bricklink.com`, a parts marketplace),
**LEGO Education** (`legoeducation.com`, classroom), and the corporate analytics
apex `thelegogroup.com` (97 kB — not the building experience). None of those
appeared in meaningful kid traffic.

### `duckmath` — "DuckMath" — host `duckmath.org`

65 MB on Prima iPad (one long session) + a Kid Mac trace. Despite the "math"
name, DuckMath is an **unblocked-games portal** (250+ browser games) that ships
proxy and cloaking tools to disguise game traffic and bypass network content
filters. Templating it gives the operator a one-click block / time-limit surface
for a known filter-evasion vector — high value precisely because the name is
camouflage. Single brand-specific apex (observed `db2.duckmath.org` covered by
suffix match); heavy game-asset CDN bytes intentionally not pinned (#1661).

## No host-set changes to existing templates

Khan Academy, Math Academy, and Gimkit were the gap-risk surfaces and all
attribute correctly via existing apexes / suffix match.
