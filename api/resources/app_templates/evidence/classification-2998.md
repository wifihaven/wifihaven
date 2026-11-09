# App-catalog classification — #2998 weekly pass (2026-11-09)

Autonomous weekly pass per `.claude/skills/app-catalog-pass/SKILL.md`.

Source: prod cloud API, read-only. No policy writes.

```
GET /api/devices                                                       # roster (30 devices)
GET /api/devices/<mac>/recent-apexes?windowDays=30&limit=500           # kid devices, sequential
GET /api/profiles/<id>/usage-by-app?from=<day>&to=<day>                # profiles 5, 6, 7, one day
                                                                         # at a time, 2026-11-02..08
```

Kid profiles: 5 (Quintus: Quintus Chromebook, Quintus iPad, Arduino Nano R4,
Arduino UNO R4), 6 (Prima: Prima iPad), 7 (Octavius: Octavius iPad). Also
pulled `ca:ef:a1:72:6a:a3` ("Kid Laptop", currently unassigned — `profileId
null` — but historically a kid device in prior passes' evidence). All calls
run sequentially, one at a time, per the #2965 incident write-up in the
skill's learnings log; `/api/health` checked after each profile's batch and
stayed healthy throughout.

## JetBrains (new template `jetbrains`)

`recent-apexes`, 30d, on "Kid Laptop" only (`ca:ef:a1:72:6a:a3`):

| apex | bytes | hits |
| --- | ---: | ---: |
| `jetbrains.com` | 1,516,452,241 (~1.5 GB) | 130 |

Subdomains observed: `account`, `data.services`, `download-cdn`, `download`,
`downloads.marketplace`, `ea-report`, `frameworks`, `plugins`, `resources`,
`sgtm`, `www`. 1.5 GB across download/marketplace/website hosts on one
device is the shape of installing and updating a desktop IDE and its
plugins, not a stray link click.

`dig +short` each subdomain (2026-11-09):

| host | resolves to | disposition |
| --- | --- | --- |
| `www.jetbrains.com` | CloudFront 18.238.176.x | **host** — title-confirmed "JetBrains: Essential tools for software developers and teams" |
| `download.jetbrains.com` | CloudFront (+ 18.160.143.x) | **host** — installer downloads |
| `download-cdn.jetbrains.com` | 50.169.148.162 (dedicated) | **host** — installer downloads |
| `downloads.marketplace.jetbrains.com` | CloudFront 13.226.251.x | **host** — plugin downloads |
| `plugins.jetbrains.com` | CloudFront 99.84.105.x | **host** — plugin marketplace browse/API |
| `resources.jetbrains.com` | CloudFront 18.238.176.x | **host** — static site assets |
| `frameworks.jetbrains.com` | CloudFront 13.226.251.x | **host** — static site assets |
| `account.jetbrains.com` | 13.248.188.196, 76.223.63.197 | **excluded** — `whois` confirms generic Amazon Technologies ranges (13.248.0.0/14, 76.223.0.0/17), not a JetBrains-dedicated distribution; sign-in/account management only, not needed to install or run the IDE |
| `data.services.jetbrains.com` | dedicated `intellij.net` infra | **excluded** — 404 on a bare GET, backend config/telemetry endpoint, not page-serving |
| `ea-report.jetbrains.com` | AWS ELB eu-west-1 | **excluded** — 404 on a bare GET, Early Access Program crash/usage telemetry |
| `sgtm.jetbrains.com` | single GCP IP (34.36.120.228) | **excluded** — server-side Google Tag Manager, a tracking relay; same disposition as `ct.canva.com` in `canva.yml` |

Checks from this machine, 2026-11-09: `www.jetbrains.com` presented
`CN=www.jetbrains.com` (Amazon issuer), not our block cert; control
`example.com` presented its own cert. `curl` of `https://www.jetbrains.com/`
returned the real homepage title. Icon
`icons.duckduckgo.com/ip3/jetbrains.com.ico` returned HTTP 200 (21,238
bytes), checked twice.

99.84.105.x (plugins.jetbrains.com) is near but distinct from the
99.84.118.x pool `arduino.yml`/`instructables.yml` flag — a different /24,
no observed collision with any other templated host's specific IPs in this
household's own traffic.

## VEX Robotics (new template `vex`)

`recent-apexes`, 30d:

| device | apex | bytes | hits | subdomains |
| --- | --- | ---: | ---: | --- |
| Arduino Nano R4 (profile 5, Quintus) | `vex.com` | 12,497,236 | 15 | `api.vex.com`, `codev5.vex.com` |
| Arduino UNO R4 (profile 5, Quintus) | `vex.com` | 520 | 2 | `education.vex.com` |

`codev5.vex.com` is VEX's own browser-based VEXcode V5 IDE (web-confirmed
via VEX's knowledge-base articles: "Launch web-based VEXcode V5 by
navigating to codev5.vex.com," runs on Chromebook/Mac/Windows, no install
needed). The traffic itself was observed on the devices named "Arduino
Nano R4"/"Arduino UNO R4" (profile 5, Quintus), not the Quintus Chromebook
— a browser-based IDE running on one of those boards' host machine is an
inference from VEX's documented requirements, not something directly
observed in this traffic. Same educational robotics/coding category as the
already-templated `arduino`.

`www.vex.com` and `codev5.vex.com` both answer a Cloudflare bot-challenge
("Just a moment...", HTTP 403) to a plain curl — inconclusive from a direct
fetch, not evidence the site is dead, same disposition as the
`coddy`/`rebrickable` precedent. Purpose confirmed independently via VEX's
own published support documentation instead.

`dig +short` on all four checked hosts (`codev5`, `api`, `www`,
`education.vex.com`) returned the identical Cloudflare pair
(104.18.39.158 / 172.64.148.98) — standard accepted Class-2 shared-edge
risk already present throughout this catalog, no new collateral class.

Hosts: `codev5.vex.com`, `api.vex.com`, `education.vex.com` (the last at
520 bytes/2 hits — kept for completeness since it's the same official
ecosystem on the same pool, adding no incremental IP risk).

## GDevelop host-set gap: `gd.games` (corrects #2952/#2955's "GameDistribution" call)

**#2952 (2026-10-27) and #2955 (2026-11-02) both classified `gd.games` as
"confirmed GameDistribution (Azerion-owned HTML5 game-syndication
network)" and held it out as a deliberate blocklist-only skip.** That was
wrong. A direct fetch of the live page (2026-11-09) shows zero occurrences
of "gamedistribution" or "azerion" anywhere in the HTML, and 450+
occurrences of "gdevelop"; the `<title>` and meta description both name
GDevelop directly ("Play multiple web games on gd.games! Discover and play
games created with GDevelop"). Corrected the stale comments in
`games.yml`, `ads.yml`, and `evidence/classification-2955.md`, and the
skill's learnings log. Either the prior passes misread the page or the
domain's content changed since 2026-10-27 — this doc doesn't speculate
further than what's directly checkable today.

`recent-apexes`, 30d:

| device | apex | bytes | hits |
| --- | --- | ---: | ---: |
| Arduino Nano R4 | `gd.games` | 1,032,451 | 3 |
| Arduino UNO R4 | `gd.games` | 2,657,039 | 57 |

`gd.games` is not in `gdevelop.yml`'s host-set (`gdevelop.io`,
`gdevelop-app.com`). Title-confirmed directly: its own meta description
reads "Play multiple web games on gd.games! Discover and play games created
with GDevelop, the free and easy game-making app," and its H1 reads "Free
online games, made by the GDevelop community" — a third apex of the same
vendor (the editor's own community game-hosting portal), not a
look-alike or unrelated site. `dig +short gd.games` returned
104.26.3.97/104.26.2.97/172.67.70.130 — the same Cloudflare 104.26.x/172.67.x
edge `gdevelop.yml` already documents for its other two hosts. Added
`gd.games` to `gdevelop.yml`'s host-set; no new template.

## Other candidates checked, not actioned

| candidate | 30d volume | disposition |
| --- | --- | --- |
| `modrinth.com` | 1.2 MB / 4 hits | below engagement bar |
| `codecademy.com` | 1.2 MB / 16 hits | below engagement bar |
| `quizlet.com` | 1.1 MB / 10 hits | below engagement bar |
| `follettsoftware.com`/`follettdestiny.com` | 3.7+2.4 MB / 15 hits | school library admin flow, not kid-navigated; skip-with-note |
| `disney.com`/`disney.io` | 2.1+0.9 MB / 7 hits | below engagement bar |
| `mcpedl.com` | 4.2 MB / 4 hits | single-event download pattern (#2908 precedent) |
| `graphicalanalysis.app`/`spectralanalysis.app`/`instrumentalanalysis.app` (Vernier) | 7.4/3.3/2.5 MB, 1 hit each | single-event pattern; below bar |
| `playfab.com` | 33.7 MB / 4 hits | shared Microsoft game-backend API, no branded surface; skip |
| `forgecdn.net` | 15.1 MB / 3 hits | thin; CurseForge mod CDN, below bar |
| `pypi.org`/`pythonhosted.org` | 16.5/16.9 MB, 3-4 hits | shared Python package registry (not JetBrains-owned); correlates with the `jetbrains`/PyCharm finding but is generic developer infra, not a brand-owned surface |
| `adeept.com` | 57.6 MB / 3 hits | thin; Arduino/RPi robot-kit vendor, below bar |
| `bricksafe.com` | 17.1 MB / 4 hits | thin; LEGO MOC file-sharing community site, below bar watch-item |
| `klaviyo.com`, `quantummetric.com` | 19.9/18.4 MB, 148/90 hits | shared marketing-email/session-replay SaaS infra used by many unrelated sites; skip |
| `ic.svvsd.org` | 0.5 MB / 2 hits (this window) | already a watch-item below `classlink`'s bar (#2943); unchanged |
| `twc.health`, `learnings.ai`, `bronze.systems`, `jrustonapps.info`, `bgcdn.com` | 26/24/15/8/42 MB, low hit counts | unclear purpose, thin; skip-with-note |

Orphan-host sweep (profiles 5/6/7, one day at a time, 2026-11-02..08) found
no additional genuine gap beyond `gd.games` above — the top of the list was
entirely Google/Apple platform infra and ad-tech (`www.google.com`,
`ssl.gstatic.com`, `bam.nr-data.net`, `accounts.google.com`,
`googleads.g.doubleclick.net`, etc.), the usual skip pile. `www.lego.com`
appeared at 782s/790 presence — expected and intentional per `lego.yml`'s
deliberate sub-experience scoping (shop excluded), not a gap.
