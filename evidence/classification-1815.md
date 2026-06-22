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
| lego.com         | 277 MB / 178     | **NEW app template `lego`** (LEGO Builder — see below) |
| duckmath.org     | 65 MB / 3        | **Added to `blocklists/games.yml`** (unblocked-games / filter-bypass portal) — NOT an app (a block target, not a time-budget app; operator decision) |
| icloud-content.com, apple-dns.net, apple.com, fastly.net, akadns.net, aaplimg.com, gvt2.com | large | infra / Apple+Google service pools — NOT templatable (shared) |
| flashtalking.com, innovid.com, adsrvr.org, doubleclick.net, pubmatic.com, casalemedia.com, … | large | ad / RTB networks — noise, skip |
| kastatic.org, kasandbox.org | 381 / 12 MB | already covered by `khan-academy` |
| mathacademy.com  | 120 MB           | already covered by `math-academy` |
| gimkitconnect.com, gimkit.com | 15 MB     | already covered by `gimkit` |
| adobe.com / adobe.io / autodesk.com | medium | shared corporate infra (Adobe CC / Autodesk login), not a single kid app — skip |
| tenor.com        | ~1 MB            | low signal + Google-shared infra — skip |
| mathplayground.com | 181 kB / 4     | below engagement bar — skip (don't template incidental hosts) |
| temu.com, ticketm.net, apple.news, reddit.com | low | not a kid app — skip |

## NEW app template (1)

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

**Lego Builder vs other Lego properties — separated, not a whole-apex app.**
The Builder building experience is served from building-specific subdomains
(`*.i.lego.com` = cobuild + dbix, `*.services.lego.com`, `*.apps.lego.com`); the
LEGO shop / marketing lives on `www.lego.com` and the bare apex. Although these
share the `lego.com` registrable apex, the host-set is NOT constrained to
apexes: `Hostname.parse` accepts any valid multi-label host, nothing in the
API→agent path reduces a host to its apex, and the agent emits one verbatim
`nftset=/<host>/...` directive per host (`render.lua` ~L559). dnsmasq populates
the ipset for `<host>` AND its subdomains by **suffix**, so a host entry of
`dbix.i.lego.com` matches `*.dbix.i.lego.com` but NOT `www.lego.com`. We
therefore ship the four building-specific **parent subdomains** —
`cobuild.i.lego.com`, `dbix.i.lego.com`, `services.lego.com`, `apps.lego.com` —
which cover every observed Builder FQDN while keeping the shop OUT of the app's
time-limit budget. (Earlier draft shipped the whole `lego.com` apex on a mistaken
"can't separate at apex granularity" belief; corrected after verifying the
verbatim-host nftset path.) Sibling LEGO properties on **distinct apexes** remain
excluded: **BrickLink** (`bricklink.com`, a parts marketplace), **LEGO
Education** (`legoeducation.com`, classroom), and the corporate analytics apex
`thelegogroup.com` (97 kB). None appeared in meaningful kid traffic.

Coverage check (observed FQDN → host entry):

```
api.prod.cobuild.i.lego.com         → cobuild.i.lego.com
api.prod.dbix.i.lego.com            → dbix.i.lego.com
assets.prod.dbix.i.lego.com         → dbix.i.lego.com
biapp.prod.dbix.i.lego.com          → dbix.i.lego.com
imageresizer.prod.dbix.i.lego.com   → dbix.i.lego.com
appconfig.services.lego.com         → services.lego.com
scout.services.lego.com             → services.lego.com
videoprocessingpipeline.services.lego.com → services.lego.com
buggy.apps.lego.com                 → apps.lego.com
```

## Blocklist addition — `duckmath.org` → `blocklists/games.yml`

65 MB on Prima iPad (one long session) + a Kid Mac trace. Despite the "math"
name, DuckMath is an **unblocked-games portal** (250+ browser games) that ships
proxy and cloaking tools to disguise game traffic and bypass network content
filters. It is a **block target, not a time-budget app** — there is no reason to
give a kid a daily allowance of a filter-evasion site — so it goes in the curated
**Games** blocklist (`api/resources/blocklists/games.yml`) only, alongside the
other browser-game portals (poki, crazygames, coolmathgames, …), and is NOT
shipped as an app template (operator decision, 2026-06-21). The proxy/mirror
sites DuckMath links to are added to the same blocklist (see commit history).

## No host-set changes to existing templates

Khan Academy, Math Academy, and Gimkit were the gap-risk surfaces and all
attribute correctly via existing apexes / suffix match.
