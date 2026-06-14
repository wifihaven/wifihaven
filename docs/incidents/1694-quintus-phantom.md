# #1694 findings — Quintus iPad phantom engagement on 2026-06-12

## Window
Quintus iPad MAC `26:74:fc:f9:4e:9e` (profile 5).
2026-06-12 13:00–19:00 UTC (= 09:00–15:00 ET, kid-at-school).
Source: `GET /api/usage/traffic?mac=26:74:fc:f9:4e:9e&from=…&to=…&bucket=1h&groupBy=domain` against prod.

## Aggregate
- Total engaged seconds in window: **3130 s ≈ 52 min** of phantom engagement.
- 74 distinct hosts.

## Top contributors classified

Bucket key:
- **A** = InfraHosts coverage gap (suppress in InfraHosts)
- **B** = app-attribution over-reach (narrow an app's host-set)
- **C** = session-stitch too generous
- **REAL?** = plausibly real engagement, leave alone
- **OPS** = needs operator clarification before action

| host | sec | bytes | B/s | matches InfraHosts? | bucket |
|---|---:|---:|---:|---|---|
| app.mocpilot.com | 550 | 1086046 | 1975 | no | OPS |
| doc.mocpilot.com | 40 | 77431 | 1936 | no | OPS |
| pubsub.plex.tv | 390 | 18199 | 47 | no | **A** (pubsub keepalive) |
| plex.tv | 80 | 163489 | 2044 | no | REAL? (could be Plex client) |
| 1/2/5/6/8/12/35/36/37/48/50-courier.push.apple.com | ~600 | varied | varied | yes (push.apple.com) | already suppressed |
| api-glb-ausw2b.smoot.apple.com | 80 | 18133 | 227 | yes (smoot.apple.com) | already suppressed |
| gdmf.apple.com | 80 | 156087 | 1951 | yes | already suppressed |
| bag.itunes.apple.com / p35-buy / sandbox / inappcheck | ~180 | varied | varied | yes (itunes.apple.com) | already suppressed |
| android.clients.google.com | 70 | 118916 | 1699 | no | **A** (Google client services) |
| telemetry.1passwordservices.com | 70 | 25741 | 368 | no | **A** (telemetry) |
| guzzoni.apple.com | 60 | 66114 | 1102 | no | **A** (Siri backend, background) |
| o4505093097586688.ingest.us.sentry.io | 50 | 29548 | 591 | no | **A** (Sentry telemetry) |
| clients1.google.com | 40 | 74027 | 1851 | no | **A** (Google update/safebrowsing) |
| clients4.google.com | 30 | 32080 | 1069 | no | **A** |
| ios-api-cf.duolingo.com + brb/zombie/excess/localization | ~130 | varied | varied | no | REAL? (Duolingo, kid app) |
| sessions.bugsnag.com | 30 | 16510 | 550 | no | **A** (Bugsnag telemetry) |
| weather-edge.apple.com | 20 | 28831 | 1442 | no | **A** (Apple Weather backend) |
| news-edge.apple.com | 20 | 21698 | 1085 | no | **A** (Apple News backend) |
| profile.gc.apple.com | 20 | 31793 | 1590 | no | **A** (Game Center) |
| aidc.apple.com | 20 | 14017 | 701 | no | **A** (Apple ID device check) |
| sequoia.cdn-apple.com | 20 | 14564 | 728 | no | **A** (iOS asset/update CDN) |
| 172.17.0.1 / 172.18.0.1 / 10.0.0.250 | 50 | 768 | 15 | n/a (IP literal) | (different issue, low impact) |
| www.nytimes.com | 20 | 13584 | 679 | no | REAL? (1 window only; could be widget) |
| kt-prod.ess.apple.com | 30 | 20890 | 696 | yes (ess.apple.com) | already suppressed |
| p192-fmf.icloud.com / p157-fmipmobile.icloud.com | 40 | 71575 | — | yes (icloud.com) | already suppressed |

## Bucket assignment

**Bucket A — InfraHosts gap** dominates. The shape mirrors #1629 / #1669: Apple
edge/widget services, Google background services, third-party telemetry, and
the Plex pubsub keepalive — all device-level background.

**Bucket B/C** — no clear B or C finding. The Duolingo hosts in this window
plausibly reflect real Duolingo use (kid app, sub-2 min total, structured bytes
to /api endpoints). Session-stitch isn't bridging unrelated app traffic — it's
stitching infra hosts that should never have been counted in the first place.

## Action

Extend `InfraHosts.suppressOnly` (the canonical single source per #1503/#1560)
with the **A** rows above. Conservative additions only — list `clients1` through
`clients6.google.com` explicitly rather than apex-matching `google.com`, list
specific Apple `*-edge.apple.com` services rather than `apple.com`, and keep
`plex.tv`/`duolingo.com`/`mocpilot.com` apexes OUT of the list so real-app
attribution still works.

## Operator follow-ups

- `app.mocpilot.com` / `doc.mocpilot.com` (590 s combined, the single largest
  phantom contributor) — operator confirmed it is an app and created a
  `MOC Pilot` app (id 20) in prod on 2026-06-12. Once profile assignments land,
  its hosts attribute to that app instead of flowing through the un-attributed
  path. Nothing to do here.
- `telemetry.1passwordservices.com` — initially proposed as an InfraHosts apex
  entry. Operator decision: add it to the existing `1Password` app (id 17)
  host-set instead, since that app is already `allowed` + `exempt-from-daily`
  for every kid profile. Member-host attribution carries the same effect
  (allowed traffic, doesn't inflate daily-engaged minutes) AND keeps the
  authoring model consistent. Applied in prod via
  `PUT /api/apps/17/hosts ["1password.com","1passwordservices.com"]`. Removed
  from `InfraHosts.suppressOnly` in this PR.
- `plex.tv` / `www.nytimes.com` — modest contributors, plausibly real; leaving
  for now.
- LAN IP literals (`172.17.0.1`, `172.18.0.1`, `10.0.0.250`) in
  `traffic_reports.host` — minor noise, separate hygiene issue (router agent
  reporting raw IP for unresolved internal flows). Filing a separate issue.
