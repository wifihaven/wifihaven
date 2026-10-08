# App-catalog pass 2026-09-28 (#2811): weather, OneNote, ipify

Method: `GET /api/profiles/{1,5,6,7}/usage-by-app?from=2026-08-29&to=2026-09-28`
(orphan hosts, bare IP literals filtered), then `recent-apexes?windowDays=30` on
the two Kids-profile devices to scope host-sets. All candidates are on the Kids
profile (id 1); `api64.ipify.org` also appears on profile 5 (Chromebook, 8 min).

| host | proportional min (30d) | disposition |
|---|---|---|
| api.weather.com | 3,532 | app `weather` (display cleanup) |
| api0.weather.com | 499 | `weather` (apex suffix) |
| weatherbug.net / .com | 158 / 16 | `weather` |
| usc-onenote.officeapps.live.com | 1,517 | app `onenote` |
| onenote.officeapps.live.com | 1,186 | `onenote` |
| common.online.office.com | 1,486 | `onenote` shared_hosts |
| oauth.officeapps.live.com | 1,307 | `onenote` shared_hosts |
| ecs.office.com | 558 | `onenote` shared_hosts |
| res.public.onecdn.static.microsoft | 556 | `onenote` shared_hosts |
| api.ipify.org / api64.ipify.org | 173 / 8 | app `ipify` (display cleanup) |
| collect.analytics.unity3d.com | 2,770 | skip: Unity SDK analytics, every Unity game |
| cdp.cloud.unity3d.com | 2,202 | skip: same |
| thetraindepartment.com / mouldkingcorp.com | 2,784 / 1,374 | skip: shared `shops.myshopify.com` origin (#2774) |
| inquisition.goguardian.com, log.blocksi.net | 472 / 62 | skip: school-mandated monitoring |
| bam.nr-data.net, clinch, adthrive, raptive etc. | - | skip: ad-tech / analytics |
| weatherkit.apple.com | 80 | skip: Apple platform infra |

Device-level (recent-apexes, Kids devices): `weather.com` 376 MB / 2,242 hits
(subdomains api, api0, dsx, mparticle); `live.com` 22 MB (onenote/usc-onenote
plus login/storage/onedrive, which are excluded as shared Microsoft identity).

Collateral checks (dig, 2026-09-28):
- OneNote hosts, `oauth.officeapps.live.com` and `common.online.office.com` all
  resolve via `app-geo.wac.trafficmanager.net` to 52.108.8.12 / 52.108.9.12, the
  Office Online front door for every web app (Word/Excel/PowerPoint too). Both
  directions documented in `onenote.yml`; ships for attribution.
- `api.weather.com` -> `api.twc.map.fastly.net` (199.232.65.91, Fastly shared
  edge); `weather.com` -> Akamai. Documented in `weather.yml`.
- `www.ipify.org` -> CloudFront 99.84.118.x (pool flagged in arduino.yml), so
  `ipify` lists the two API hosts only, not the apex.
