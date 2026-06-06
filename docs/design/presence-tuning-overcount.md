# Presence / time-on-site accuracy — background OVERcount (#1499)

Part of the **Tuning** epic ([#1445](https://github.com/wifihaven/wifihaven/issues/1445)).
Companion to [`presence-tuning.md`](presence-tuning.md) (the #1446 *under*count
side). Analysis + recommended tuning only — this document does **not** implement
the change; it quantifies the over-count and lists follow-up implementation
sub-issues (to be filed separately).

## TL;DR

The prod Kids profile reads **usedMins 22–26 / 30** for the Kid device
`ca:ef:a1:72:6a:a3`, which does not match the operator's in-person observation
of app use. We pulled the device's full raw `traffic_reports` for 2026-06-06 and
replayed the exact server-side presence math (`Presence.totalSecondsByMac` with
the live prod `heartbeatFilter` + `presenceContinuationSeconds=120`).

**Finding: the daily total is ~93% background OS / telemetry / infra chatter,
not app use.** Of the 55 traffic rows that actually count toward the daily cap
(after the heartbeat and exempt-from-daily filters), **51 (93%) are Apple/Google
OS infrastructure, update beacons, analytics SaaS, cert/safe-browsing checks** —
none of which represent the kid using anything. Only ~4 rows (7%) are plausibly
genuine foreground browse, and even those are "device had a packet," not focused
use.

**The dominant cause is background inflation, NOT app under-attribution.**
This refines the issue's two-cause hypothesis: app host-set attribution (cause
*a*) is real but is the **wrong lever for this over-count** (see §3). The
durable fix is a **background/infra suppression + foreground-engagement notion**
([#842](https://github.com/wifihaven/wifihaven/issues/842),
[#790](https://github.com/wifihaven/wifihaven/issues/790)/[#930](https://github.com/wifihaven/wifihaven/issues/930)
heartbeat tuning) — applied carefully so it does **not** reintroduce the #1446
undercount (§5).

## 1. Data: what the 22–26 minutes are actually made of

Source: `GET /api/usage/traffic?mac=ca:ef:a1:72:6a:a3&from=2026-06-06T00:00:00Z&to=2026-06-07T00:00:00Z&bucket=raw`
(204 raw rows, untruncated), replayed through the production presence model. The
snapshot window is the full UTC day (slightly wider/later than the moment
`usedMins=22` was read, so the replayed total lands at ~40 min — the *composition*
below is the point, not the exact total).

**groupBy=app view** (what the dashboard's per-app card shows):

| app | active-seconds | bytes (in/out) | domains |
|---|---|---|---|
| **"Other" (uncategorized)** | 2140 s | 1.67 MB / 1.11 MB | **70** |
| Math Academy | 140 s | 377 KB / 1.18 MB | 1 (`www.mathacademy.com`) |
| YouTube | 20 s | 156 B / 120 B | 1 (`www.youtube.com`) |

**Daily-cap composition** — the 55 rows that survive `countedRows`
(`Presence.scala:168`, drops heartbeats **and** `exemptFromDaily` site-limited
hosts), categorized by row count and bytes (overlap-free measures; per-host
*minutes* overlap heavily in wall-clock and are not additive):

| category | rows | row % | bytes % | domains | examples |
|---|---:|---:|---:|---:|---|
| Google update/beacon infra | 20 | 36% | 21% | 7 | `beacons{2,3,4,5}.gvt2.com`, `*.gvt3.com` |
| Apple OS/infra telemetry | 14 | 26% | 44% | 12 | `*.ls.apple.com`, `weather-edge.apple.com`, `swallow.apple.com`, `api.apple-cloudkit.com` |
| Google API/infra | 8 | 15% | 11% | 7 | `*.googleapis.com`, `ssl.gstatic.com`, `b1.nel.goog`, `clients4.google.com` |
| Analytics/SaaS telemetry | 4 | 7% | 6% | 3 | `app-analytics-services.com`, `events.launchdarkly.com`, `cc-api-data.adobe.io` |
| Google Workspace | 3 | 5% | 3% | 2 | `drive.google.com`, `chat.google.com` |
| Security/cert checks | 1 | 2% | 3% | 1 | `safebrowsing.google.com` |
| "Other genuine" / IP-literal | 4 | 7% | 12% | 4 | LAN IP, misc |
| Genuine app/browse (untracked) | 1 | 2% | 1% | 1 | `www.tinkercad.com` |

**Background OS/telemetry/infra/security ≈ 93% of counted rows. Plausibly-genuine
foreground browse ≈ 7%.**

## 2. Why the existing filters don't catch this

The daily total already passes through two server-side filters
(`Presence.countedRows`, `Presence.scala:168`):

- **Heartbeat filter** (`isHeartbeat`, `Presence.scala:312`) — drops a row if
  `bytes_in + bytes_out < bytesThreshold` **OR** the host matches
  `heartbeatHostPatterns`. Live prod config: `enabled=true`,
  `bytesThreshold=10000`, 16 host patterns (`*.push.apple.com`, `time.apple.com`,
  `gdmf.apple.com`, `connectivitycheck.gstatic.com`, …). This dropped **141 of
  204 rows** — it is doing real work.
- **Exempt-from-daily** — site-limited app domains
  (`mathacademy.com`, `khanacademy.org`, `gimkit.com`, …) are excluded from the
  main daily cap by design (they tick their own per-site limit instead;
  see `SiteTimeLimit`).

The 55 survivors leak through because:

1. **The 10 KB byte threshold is far too low for modern OS telemetry.** The
   counted Apple/Google infra rows carry **15 KB – 225 KB each** — an OS pushing
   a config blob or an update beacon easily clears 10 KB while representing zero
   human engagement. The threshold catches keep-alive pings but not chatty
   background sync.
2. **The host-pattern list is a small hand-curated allow-set** that covers
   `push.apple.com` / `time.apple.com` / connectivity checks but **not** the
   bulk of background infra observed here: the `gvt2`/`gvt3` update beacons (36%
   of counted rows!), `*.googleapis.com` background, `*.ls.apple.com` location
   services, analytics SaaS (`launchdarkly`, `adobe`, `app-analytics-services`),
   or cert/safe-browsing checks.

Net: today's filter is *byte-floor + tiny denylist*. Background infra that is
both >10 KB and not on the 16-host list counts as "the kid was present."

## 3. Why app host-set attribution (#1337/#1411) is the WRONG lever here

The issue (correctly) notes that Math Academy's real CDN/asset traffic lands in
the traffic page's "Other" bucket because `app_hosts` stores only the apex
(`mathacademy.com`), and the matcher (`HostMatch.lookupApex`) only attributes a
host to an app when that host's apex is a stored `app_hosts` row. So Math Academy
reads 140 s on the dashboard instead of its true ~16 min of stitched presence.

That is a genuine **reporting** defect — but fixing it would **not** reduce the
22–26 min over-count, for two compounding reasons:

1. **Site-limited apps are exempt from the daily cap regardless of attribution.**
   Math Academy / Khan / Gimkit traffic is dropped by `countedRows` *before* it
   reaches the daily total. Better attribution moves minutes from "Other" to
   "Math Academy" **on the traffic page**, but those minutes never counted toward
   `usedMins` in the first place.
2. **The counted background is genuinely OS infra, not mis-attributed app CDN.**
   The dominant counted hosts — `beacons*.gvt2.com`, `*.ls.apple.com`,
   `*.googleapis.com`, `weather-edge.apple.com` — are iOS/Chrome/Android
   background services, *not* Math Academy's dependency set. Attributing them to
   an app would be wrong; they belong to no app.

So app host-set attribution is worth doing **for accurate per-app reporting and
for non-exempt apps**, but it is orthogonal to this over-count. Pursuing it as
*the* fix for #1499 would leave `usedMins` unchanged.

## 4. Dominant cause

**Background inflation (issue cause *b*).** `usedMins` today means "the device
emitted non-heartbeat, non-exempt traffic in this minute" — i.e. *any-traffic
presence*. On a modern iOS/Android device with a kid logged into iCloud/Google,
that fires near-continuously from OS sync, update beacons, analytics, and cert
checks, independent of whether a human is engaged. The result over-represents
background and (because the real educational app is exempt) effectively reports
*the device's idle OS chatter* as screen time.

## 5. Recommended fixes (in priority order) and how they compose with #1446

The lever is to move `usedMins` from *any-traffic presence* toward *engaged
presence*, **without** undoing the #1464 session-stitch model that fixed the
#1446 undercount. The two are orthogonal **if** suppression keys on host
*identity* (known background infra) while stitching keys on *timing* — see the
composition rule at the end.

### Fix A (primary) — expand background/infra suppression for the daily cap

Treat known OS/telemetry/infra/analytics/cert/safe-browsing hosts as
non-counting for presence, the same way heartbeats are dropped. Concretely:

- **Grow the `heartbeatHostPatterns` set** (or introduce a sibling
  `backgroundHostPatterns`) to cover the observed infra classes: `*.gvt2.com`,
  `*.gvt3.com`, `*.ls.apple.com`, `*.googleapis.com` background endpoints,
  `*.nel.goog`, analytics SaaS, OCSP/cert and safe-browsing hosts. Ship it as
  curated seed data (a migration, like V24) so every install benefits.
- **Reconsider the byte threshold's role.** Raising `bytesThreshold` alone is
  blunt (it would also drop genuine low-byte app requests — the exact #1446
  failure mode). Prefer host-identity suppression over a higher byte floor.

This is the highest-leverage change: it directly removes the 93%.

### Fix B (strategic) — a real foreground/engagement notion (#842)

Curated denylists are a maintenance treadmill. The durable model is to define
presence as *foreground engagement*. Options to evaluate in #842:

- A **dominant-host / engagement heuristic**: a minute counts only if it
  contains sustained two-way transfer to a non-infra host above a volume floor,
  not just any packet.
- **Positive attribution as the gate**: a minute counts toward presence only if
  it contains traffic to a host attributed to a *known app* (foreground apps),
  rather than counting everything not on a denylist. This inverts the model from
  allow-by-default to count-by-attribution and naturally excludes infra.

### Fix C (parallel, NOT a fix for this over-count) — app host-set attribution

Map each app's full dependency host set (#1337/#1411) so the **traffic page**
attributes app CDN/asset traffic correctly. Improves per-app reporting and the
daily cap for *non-exempt* apps. Track separately; do not gate #1499 on it.

### Composition with #1446 (undercount) — the guardrail

#1446's fix (session-stitch, `presence-tuning.md`) raised focused-session
presence by treating each non-heartbeat row's full `[period_start, period_end]`
window as continuous evidence and stitching across idle gaps. The over-count fix
must not silently reverse this:

- **Suppression must key on host identity, not on low byte/short activity.**
  Dropping rows because they are *small* or *brief* is exactly the #1446
  undercount mechanism — a request-driven app's legitimate requests look like
  that. Drop rows because the *host* is known background infra.
- **A host attributed to an active app is never background.** When Fix C lands,
  feed app membership into the suppression decision so a host an app genuinely
  depends on can never be suppressed as infra — this is the clean seam that lets
  A/B/C coexist with #1464 without re-opening #1446.
- **Validate against both fixtures.** Any tuning PR must show the Kid-device
  over-count case drops toward observed use **and** the #1446 sparse-app
  fixtures still read correctly — one regression suite, two opposing
  directions.

## 6. Proposed implementation sub-issues (to be filed under #1445; not filed here)

1. **Seed an expanded background/infra host-suppression set** (migration, like
   V24): add the observed infra classes (`*.gvt2.com`, `*.gvt3.com`,
   `*.ls.apple.com`, background `*.googleapis.com`, `*.nel.goog`, analytics SaaS,
   OCSP/safe-browsing) to the presence suppression list. Schema-only migration
   per the migration-isolation rule; code adoption follows. — Fix A.
2. **Distinguish "background" from "heartbeat" suppression** if a separate
   `backgroundHostPatterns` field is preferred over overloading
   `heartbeatHostPatterns` (additive wire/settings field). — Fix A.
3. **Foreground-engagement presence model** — design + implement #842 (dominant
   host / count-by-attribution). Carries the durable fix; supersedes the curated
   denylist over time. — Fix B.
4. **Feed app host-set membership into the suppression gate** so an active app's
   dependency hosts are never suppressed (the #1446 guardrail seam). Depends on
   #1337/#1411. — composition.
5. **Presence regression fixtures for the over-count case**: add the Kid-device
   background-heavy day to the presence spec alongside the #1446 sparse-app
   fixtures; assert the daily total tracks engaged use, not OS chatter. — test
   guardrail for A/B.
6. **Dashboard: surface "background/infra excluded" on the per-device usage
   drill-in** so operators can see what was suppressed and why (observability for
   the new suppression). — follow-up.

## Appendix — method & reproduction

- Auth: `POST /api/auth/login` (admin) → Bearer; read-only prod, no mutations.
- Raw rows: `GET /api/usage/traffic?mac=…&from=…Z&to=…Z&bucket=raw&limit=500`
  (204 rows, `rawRowsTruncated=false`).
- Settings: `GET /api/household/settings` → `heartbeatFilter` (enabled,
  `bytesThreshold=10000`, 16 patterns), `presenceContinuationSeconds=120`.
- Replay: per-row heartbeat classify (`bytes<10000 || host∈patterns`) + exempt
  classify (site-limit patterns from `/api/time/status`), then
  `effectiveGap=max(120, 2·max period_seconds)`, per-`(device,host)` stitch,
  union — mirroring `Presence.totalSecondsByMac` (`Presence.scala:193`).
- Key code: `Presence.scala:79` (`spanOf` uses `period_end − period_start`),
  `:168` (`countedRows` = drop heartbeat ∧ exempt), `:312` (`isHeartbeat`);
  `Repos.scala:1824` (`periodSeconds = pEnd − pStart`); attribution in
  `shared/HostMatch.scala` (`lookupApex`) + `UsageTraffic.scala:222`
  (`membershipsFor` → `Other` fallback); `app_hosts` apex storage in
  `V28__apps.sql`.
