# App-catalog classification — #2774 (2026-09-11)

First pass run off the **server-side gap list** rather than a hand diff of
`recent-apexes` against `_index.yml`.

Source: prod cloud API, read-only.

```
GET /api/profiles/<id>/usage-by-app?from=2026-08-29&to=2026-09-11   # profiles 1,5,6,7
GET /api/devices/<mac>/recent-apexes?windowDays=30&limit=600        # scoping only
```

`usage-by-app` returns `apps[]` (what IS attributed) and `orphanHosts[]` (every
host carrying real time that no app covers), each with `proportionalSeconds` and
`presenceSeconds`. Aggregating `orphanHosts` across the four kid profiles gives a
time-weighted candidate list directly, which the raw byte table is not.

Totals: **2,808 distinct orphan hosts, 142,672 proportional minutes.**

## Top named orphan candidates (aggregate proportional minutes)

Platform infra, IP literals and ad-tech removed; see the skip sections below.

| minutes | profiles | host | disposition |
| ---: | ---: | --- | --- |
| 8,542 | 1 | `ipv4.icanhazip.com` | skip — IP-echo poller |
| 974 | 1 | `thetraindepartment.com` | **skip** — shared Shopify origin |
| 799 | 1 | `www.youtube-nocookie.com` | **→ `youtube.yml`** |
| 470 | 1 | `avatars.githubusercontent.com` | watch item |
| 399 | 1 | `mouldkingcorp.com` | **skip** — shared Shopify origin |
| 198 | 1 | `api.weather.com` | skip — OS weather backend |
| 162 | 1 | `rebrickable.com` (102) + `cdn.rebrickable.com` (60) | **→ new `rebrickable` app** |
| 126 | 1 | `onenote.officeapps.live.com` | watch item |
| 68 | 1 | `raw.githubusercontent.com` | watch item |

## Change 1 — `youtube-nocookie.com` into `youtube.yml`

YouTube's privacy-enhanced embed domain: a video embedded with that option is
served from `www.youtube-nocookie.com/embed/<id>`. Watching one is watching
YouTube, and none of that time was reaching the app's budget.

Evidence: 799 proportional minutes over the 14d window; 31.9 MB / **334 hits**
over 30d on Kid Laptop, sole observed subdomain `www.youtube-nocookie.com`.
Highest-minute genuine gap in the list.

The entry added is the APEX, on a `www`-only observation. That is deliberate
and follows the catalog's default convention — `HostMatch.matchesApex` and
dnsmasq's `nftset=` are both pure suffix tests, so the apex is what attributes
the observed `www.` traffic — but it does mean the apex itself becomes an
enforcement target on evidence from one child. Accepted here because the zone is
single-purpose by construction: `youtube-nocookie.com` exists only to serve
privacy-enhanced embeds.

This does **not** contradict the `youtubei.googleapis.com` exclusion the template
already carries. That host is barred for riding the shared `*.googleapis.com`
vendor-API pool that also fronts Drive/OAuth/Calendar — `_README.yml` Class 1,
the #1636 collateral. `youtube-nocookie.com` is a YouTube-owned **content**
domain, the same class as `youtube.com` itself, which this template has always
carried. Checked against the mechanical guard: it is not in
`SharedGfeHosts.googleAdApexes` (`shared/src/types/SharedGfeHosts.scala`), so the
#2601 test in `AppTemplatesSpec` passes.

Resolution as of 2026-09-11: `www.youtube-nocookie.com` CNAMEs to
`youtube-ui.l.google.com` and answers on Google frontend addresses. The case for
adding it rests on **what the host is**, not on any claim about which addresses
it shares with `youtube.com` — those answers are DNS-steered and move between
lookups, so a pool-overlap argument either way would be unverifiable.

## Change 2 — new `rebrickable` app

The LEGO parts/set/MOC database. Same cluster as the existing `lego` (scoped to
the Builder experience) and `moc-pilot` (a MOC build catalog), and on the build
side rather than the shop side.

Evidence: 119.8 MB / 88 hits over 30d on Kid Laptop; 162 proportional minutes
across the bare apex and `cdn.` in the 14d orphan window.

Apex entry. The zone is three hosts and single-purpose:

| host | resolves to |
| --- | --- |
| `rebrickable.com` | 104.26.12.40, 104.26.13.40, 172.67.69.201 (Cloudflare) |
| `www.rebrickable.com` | same three |
| `cdn.rebrickable.com` | CNAME `rebrickable.b-cdn.net` → 84.17.63.178 (BunnyCDN) |

`api`, `static`, `img` and `shop` do not resolve. Every child is Rebrickable's
own content by construction, so there is no sub-experience split of the kind
`lego.yml` needs.

Cloudflare's 104.26/172.67 range is multi-tenant — the accepted Class-2 latent
risk from `_README.yml`, the same trade `discordapp.net` makes there.

Not verifiable this pass: `https://rebrickable.com/` returns a Cloudflare bot
challenge ("Just a moment…", HTTP 403) to `curl`, so the site's own copy could
not be fetched to confirm the description. The traffic and DNS above are real;
the description reflects the site's public identity rather than a fetch.

## The two skips worth recording

`mouldkingcorp.com` (399 min) and `thetraindepartment.com` (974 min, **343 hits**
over 30d) are both real engagement, not noise, and the second is the second
largest named orphan in the whole list. Both are skipped, and the reason is
stronger than "it is a shop":

```
www.mouldkingcorp.com       CNAME shops.myshopify.com -> 23.227.38.74
www.thetraindepartment.com  CNAME shops.myshopify.com -> 23.227.38.74
mouldkingcorp.com                                     -> 23.227.38.65
thetraindepartment.com                                -> 23.227.38.72
```

One address demonstrably serving two unrelated stores **in this household's own
traffic** — not a hypothetical about multi-tenancy.

The usual form of this argument is the `eb_` one `arduino.yml` makes for its own
store ("blocking Arduino must not drop every Shopify store"). The allow side is
worse and is what decides it here: per #1899 the block side takes distinctive
hosts only, but the allow side takes the full set, so the shared address
reaches `ea_` — and `extraAllowed` beats every drop it reaches (#421).

The precise scope is subtle and two successive drafts of this evidence doc got
it wrong, so what is recorded here is only what is verified and load-bearing: the carve
exists, it reaches the `bl_`/`eb_` drops, and that alone makes templating either
shop unsafe. The gates that kept being missed, for anyone who needs the exact
scope later:

| gate | source | effect |
| --- | --- | --- |
| `val timeLimitedUnderCap = if (state.blocked) Nil else timeLimitedUnderCapHosts(state)` | `PolicyService.scala:1506-1507` | the time-limited carve does not survive a whole-MAC block |
| `if (isHardPause) Nil` | `PolicyService.scala:1509-1511` (#1418) | zeroes all PER-PROFILE carves together, Allowed-mode included. `global.extraAllowed` survives by design (`:1486-1497`); an app template's hosts never land there |
| `capGroups = perApp.filter(_.mode == AppMode.TimeLimited)` | `ProfileAppDispositions.scala:53-54` (#2747) | the exempt carve is TimeLimited-only, even with `exemptFromDaily` set |
| `suppressedByScheduleToggle` | `ProfileAppDispositions.scala:145` (#1679) | withholds the Allowed-mode carve during a Schedule block, but only when the assignment opts out — `allowed_during_schedule_block` defaults TRUE (`V56__…sql:22`), so it does not fire on a default assignment |

The `eb_` half bites regardless of any of that — a brand host is distinctive, so
it lands in `eb_` too, which is the form `arduino.yml` already documents for its
own store.

Contrast with `rebrickable`, which is why one is templated and these are not:
Cloudflare's range is multi-tenant in the ordinary Class-2 way, but no address in
this sample is shown serving a second unrelated site, and Rebrickable is not a
purchasing surface.

## Skip pile

- **IP-echo services** — `ipv4.icanhazip.com` (8,542 min, the single largest
  orphan) and `api.ipify.org` (153 min). Background pollers that return the
  device's public IP; no surface anyone engages with.
- **Google / Apple platform infra** — `ssl.gstatic.com`, `accounts.google.com`,
  `docs.google.com`, `www.google.com`, `play.googleapis.com`,
  `drivefrontend-pa.googleapis.com`, `safebrowsing.googleapis.com`,
  `kidsmanagement-pa.googleapis.com`, `c.apple.news`, `api.apple-cloudkit.com`,
  `tether.edge.apple`. The expected top of the list. Several are on the #2601
  ban list outright; the rest are shared platform surfaces.
- **Ad-tech → blocklist territory, not apps** —
  `trk`/`realtime`/`img-cdn.clinch.co`,
  `s0.2mdn.net`, `dpm.demdex.net`, `gum.criteo.com`, `match.adsrvr.org`,
  `ib.adnxs.com`, `ups.analytics.yahoo.com`, `app-measurement.com`,
  `ift.px-cloud.net`, `googleads.g.doubleclick.net`.
- **Shared CDN / infra** — `cdn.jsdelivr.net`, `cdnjs.cloudflare.com`,
  `d2lbyuknrhysf9.cloudfront.net` (a raw distribution hostname, unattributable),
  `static.cloudflareinsights.com`, `cp10.cloudflare.com`, `one.one.one.one`,
  `ohttp-relay1.fastly-edge.com`, `cdn.cookielaw.org`, `app.launchdarkly.com`,
  `bf86358stq.bf.dynatrace.com`, `edgedl.me.gvt1.com`.
- **Shared corporate** — `prod-rel-ffc-ccm.oobesaas.adobe.com`,
  `ffc-static-cdn.oobesaas.adobe.com`.
- **OS weather backends** — `api.weather.com` (198 min),
  `web-push.pulse.weatherbug.net`, `www.jrustonapps.net`. Single `api.` hosts
  behind an OS-bundled app, same call as `elevenlabs` (#1922) and `apple.news`
  (#2490).

`www.lego.com` (24 min) and `assets.lego.com` (21 min) appearing as orphans is
**correct, not a gap**: `lego.yml` is deliberately scoped to the Builder
experience and excludes the shop side (#1815). Their presence here confirms that
scoping still works as designed.

## Watch items for the next pass

- **GitHub asset CDNs** — `avatars.githubusercontent.com` (470 min),
  `raw.githubusercontent.com` (68 min), with **no `github.com` traffic at all**.
  A media/asset host with no branded surface the kid navigates to is the
  skip-with-note class (`media.tenor.com`, #2129). Template a `github` app only
  if real navigational traffic appears.
- **Microsoft 365 / OneNote** — ~420 min spread thin across
  `onenote.officeapps.live.com` (126), `res.public.onecdn.static.microsoft`
  (109), `ecs.office.com` (71), `common.online.office.com` (57),
  `teams.microsoft.com` (55). Plausibly real schoolwork, but every one is a
  heavily shared Microsoft platform host, so the Class-1 question needs
  answering before any of them becomes an IP-layer enforcement target.

## The finding that is not an app gap — filed as #2775

**48,079 of the 142,672 orphan proportional minutes (33.7%) are bare IP
literals**: 1,093 distinct addresses, nearly all IPv6.

| prefix | addresses | owner |
| --- | ---: | --- |
| `2607:f8b0:4001` | 189 | Google |
| `2620:149:a42` | 184 | Apple (`APPLE-WWNET`) |
| `2600:1405:7400` | 63 | Akamai |
| `2607:f8b0:4023` | 56 | Google |
| `2620:149:1361` | 48 | Apple |
| `2607:f8b0:400f` | 35 | Google |

Individual addresses carry real time: `2620:149:136f:1::8` is 1,267 proportional
minutes across 4 profiles, and roughly twenty Apple v6 addresses each exceed 900.

No app template can attribute an IP literal, so this share is permanently
unattributable regardless of catalog completeness. Some share is expected by
design (`blockIpOnly` exists because some destinations have no attributable
hostname), and **no cause is claimed here** — #1796's v6 fixes (#1807, #1802) are
merged, so it is not simply that bug still open. Filed as #2775 with suggested
first steps rather than diagnosed in a catalog pass.

## Validation

`mill api.test.testOnly 'wifihaven.api.feature.AppTemplatesSpec'` — 38 tests
passed, 0 failed, including the #2601 shared-GFE guard and the #1041 icon
invariant. `scalafmt --check --non-interactive` clean. No blocklist files
touched, so `BundledBlocklistsSpec` was not run.
