# Presence / time-on-site accuracy — sparse-app undercount (#1446)

Part of the **Tuning** epic ([#1445](https://github.com/wifihaven/wifihaven/issues/1445)).
Analysis + recommended tuning. This document does **not** implement the change;
it specifies it and lists the follow-up implementation sub-issues.

## TL;DR

Presence (the minutes that drive screen-time totals, time-limit enforcement,
and the dashboards) is derived from a **per-10-second activity sample** that
only ticks when *new bytes* are observed. For request-driven apps — exactly the
educational / reading apps with long local "think time" between requests — most
60-second buckets register only the **floor 10 s** of activity for a minute the
kid was fully engaged. Measured against real prod data, presence reads roughly
**3.3× low** for the kid devices, and the dominant loss is *within the minute*,
not across minutes.

**Recommended model (one primitive, server-side in the API rollup):**

Stop computing presence per fixed bucket. Instead **sessionize activity**:

1. **Session-stitch on activity, split on a wall-clock idle gap `N`.** Per
   (device, app), order the non-heartbeat activity and merge it into a session
   as long as the gap to the next activity is ≤ `N`. A gap > `N` ends the
   session ("no activity for N ⇒ done"). A session's presence is the span from
   its **first to last activity** — every activity in between counts as
   continuous, exactly as a kid working a problem locally between requests is
   continuously present. Recommended **`N = 120 s`** (sensitivity 180 s), set at
   the knee of the measured think-gap distribution.
2. **Aggregate by interval-union per profile, not by summing.** A profile is one
   human; two apps (or two devices) active in the same minute are **one** minute
   of presence. The daily cap is the **union** of all session intervals across
   the profile's apps and devices; per-app time-on-site is that one app's
   session-span. Summing per-app minutes is wrong (double-counts) **and**
   unstable across reporting rates.

**Why this shape, not "full-bucket credit":** crediting a whole bucket is
bucket-size-dependent — it over-counts at 5-minute reporting and under-counts at
10-second reporting. The session/union model is defined in wall-clock seconds on
activity timestamps, so **bucket size and sample/report rate are only the
*resolution of the evidence*, never a term in the formula** — provided two
constraints hold (validated in §2d):

- **`N` must be ≥ the report interval `R`.** If `N < R`, contiguous buckets stop
  merging and presence collapses to ~0. With per-request timestamps (§4.4) `R`
  is effectively sub-second and this is automatic; with bucket intervals it
  pins a hard rule: **N ≥ 2 × `usage_report_interval`**.
- **Anchor timing on the finest evidence available** (per-request
  `connection_events`), so the trailing edge of a session isn't inflated by the
  bucket width.

Everything is **anchored on non-heartbeat activity only**, so it composes with
the existing heartbeat filter: a keepalive-only minute neither starts nor
extends a session.

Measured against real prod data, today's computation reads **~3.3× low** for the
kid devices, and the dominant loss is *within the reporting window*, not across
windows.

---

## 1. How presence is computed today

Data flow (file:line references against `main` at time of writing):

```
Router (openwrt-agent)
  every activity_sample_int = 10 s:  usage.tracker_sample(counters)
      → for each (mac,dst_ip): if (bytes grew since last tick) active_samples[key] += 1
  every usage_report_interval = 60 s: usage.build_report(...)
      → activeSeconds = min(10 × active_samples, 60)         # usage.lua
      → POST /api/router/usage { periodStart, periodEnd, records[] }
API ingest  (RouterIngestRoutes.handleUsage → applyDelta)
  → INSERT traffic_reports (period_start, period_end, active_seconds, bytes_in, bytes_out, …)
API rollup  (TimeUsedRollupJob every 15 min → Presence.scala)
  → listPresenceRows(macs, date)                              # raw buckets
  → Presence.totalSecondsByMac / dedupedTotalSeconds
        bucketSeconds(bucket) = max(activeSeconds across hosts)   ← Presence.scala:52
        sum bucketSeconds over all (mac, period_start) buckets
  → time_used_daily.used_seconds → /api/time/status usedMinutes
```

Key facts:

- **Bucket = 60 s** (`usage_report_interval`, default 60). Confirmed on prod:
  247 773 buckets at exactly 60 s span, 239 798 at 61 s; the long tail is agent
  timer drift, not a different window.
- **`activeSeconds` is sampled at 10 s and gated on byte growth.** A bucket
  where the device touched a host in only one 10 s slice reports
  `activeSeconds = 10`, regardless of whether the user was present the whole
  minute. Source of truth:
  [`Presence.bucketSeconds`](../../api/src/presence/Presence.scala) —
  `bucket.iterator.map(_.activeSeconds).maxOption` (line 52–53).
- **No idle-gap fill or session continuation exists anywhere.** Each 60 s
  bucket stands alone; a minute with zero traffic produces no row and is simply
  absent from the daily total.
- **Heartbeat filter** (`Presence.isHeartbeat`, line ~133) runs server-side at
  this aggregation step: a row is dropped from the daily cap if
  `bytes_in + bytes_out < 10 240` **or** the FQDN matches one of the 16 default
  keepalive patterns (V24). It applies **only** to the daily-cap functions, not
  to per-host / per-site surfaces.
- Background (#715, closed): per-FQDN time-on-site was "bucket-max, not real."
  #842 (open, blocked on the agent freeze) is the router-side foreground-host
  heuristic — complementary, not required for this fix.

The miscalibration is conceptual: `activeSeconds` treats *"no new bytes in this
10 s slice"* as *"not present,"* which is the same flawed inference the
architecture warns against in the DNS-vs-enforcement context — absence of
traffic is not absence of the user. A kid reading a Khan Academy article or
working a Math Academy problem downloads nothing for 50 s of a minute they are
fully engaged.

## 2. Quantifying the undercount (real prod data, 2026-05-30 → 06-05)

All figures are read-only `SELECT`/`EXPLAIN`-class queries against prod
`traffic_reports` / `connection_events`. No prod state was mutated.

### 2a. The activity sample bottoms out at the floor

`active_seconds` distribution over **all** prod buckets:

| active_seconds | buckets  | share |
|---------------:|---------:|------:|
| 0              |    6 779  |  1.0% |
| **10**         | **345 474** | **57.7%** |
| 20             |  139 876  | 23.4% |
| 30             |   52 235  |  8.7% |
| 40             |   24 497  |  4.1% |
| 50             |   16 844  |  2.8% |
| 60             |   30 659  |  5.1% |

**57.7% of all active buckets register the 10 s floor; only 5% reach the full
minute.** Restricting to *substantial* (≥ 10 KB) buckets for educational apps
(`mathacademy`, `khanacademy`) makes it starker — **73% are ≤ 30 s and only
2.7% hit 60 s**, even though these are real foreground sessions well above the
heartbeat threshold:

| class         | ≤30 s | =60 s |
|---------------|------:|------:|
| edu (sparse)  |  73%  |  2.7% |
| video (chatty)|  76%  |  5.1% |

### 2b. The minute-by-minute undercount model

For each kid device-day (the two kid iPads / Mac, all hosts, heartbeat filter
applied with prod defaults: 10 KB floor + 16-pattern allowlist), comparing:

- **cur** — current shipped presence: `Σ max(activeSeconds)` per bucket.
- **full** — credit each non-heartbeat reporting window its full span (here the
  prod `R = 60 s`). This is the session model evaluated *at the current report
  interval*; §2d shows why it must be expressed as sessions, not bucket spans.
- **brN** — session-stitched with idle gap `N` (merge buckets whose gap ≤ N).

Minutes:

```
mac/date              cur  full  br0 br30 br60 br120 br180 br300 br600
Octavius 05-31         25    85   85   85   85    85    85    85   103
Octavius 06-02         31    72   72   72   75    83    94   104   113
Kid Mac  05-31         10   194  194  194  194   194   199   221   254
Kid Mac  06-01         32    73   73   73   78    84    90    97   102
Kid Mac  06-02         57   137  137  137  142   147   171   181   212
Kid Mac  06-04         30    72   64   64   66    69    73    84    84
...
TOTAL (min)           242   796  788  789  804   837   897   968  1123
ratio vs current             3.29x          3.46x       4.00x  4.64x
```

**Findings:**

- **The dominant loss is within the minute, not across minutes.** Going from
  `cur` → `full` is **3.29×**; the additional cross-bucket bridging at N = 120 s
  adds only ~5% (796 → 837). The fix that matters is crediting the full minute
  for a minute that had real activity.
- **Cross-bucket bridging is real but secondary.** Some device-days show clear
  think-gap recovery as N rises (Octavius 06-02: 72 → 83 at br120 → 104 at
  br300) — these are think-gaps that span a whole traffic-free minute.
- **Large N over-counts.** br600 (4.64×) starts merging genuine breaks and idle
  tails; br300 is already on the edge. N should sit at the knee of the think-gap
  distribution, not past it.

### 2c. The think-gap distribution justifies N ≈ 120 s

Inter-request gaps from `connection_events` for Math Academy (6 days, the literal
"fetch question → submit answer" cadence):

| gap ≤ (s) | events |
|----------:|-------:|
| 20        | 1126   |
| 40        |  115   |
| 60        |   91   |
| 80        |   40   |
| 100       |   13   |
| 120       |   12   |
| 140       |   12   |
| 160       |    8   |
| 180       |    6   |
| > 180     |   60   |

The body of the think-gap distribution lives in the **40–120 s** range — exactly
the ~1-minute think time in the hypothesis, gaps that straddle a bucket boundary
or span a traffic-free minute. The **> 180 s** tail (60 events) is genuine
breaks. **N = 120 s covers the think-gap body while leaving the real-break tail
unbridged.**

### 2d. Bucket-/rate-independence — the formula must not depend on the report rate

The router's `usage_report_interval` (R) and `activity_sample_int` may change
(today 60 s / 10 s; a future build might use 5-minute reports or tighter
sampling). Presence must be **invariant** to that. To test it, I took the native
per-request timestamps from `connection_events` (Kid Mac, 2026-06-02, FQDN
heartbeats removed) and re-derived presence two ways while simulating coarser
reporting — collapsing the same requests into buckets of width R and re-running
the identical stitch.

**(i) Summing per-app session minutes is NOT invariant — it explodes with R:**

| evidence resolution | Σ per-app min | profile-union min |
|---------------------|--------------:|------------------:|
| native (per-request)|          353  |            **71** |
| report R = 10 s     |          579  |              99  |
| report R = 60 s     |        1 415  |             165  |
| report R = 300 s    |        4 545  |             300  |
| report R = 600 s    |        8 340  |             440  |

Per-app summing multiplies the per-window trailing-edge inflation across every
host (515 hosts that day), so it is unusable. The **profile interval-union** is
far more stable and is the correct daily-cap aggregate, but note it still drifts
up with R because each coarse bucket inflates its session's trailing edge by up
to R. **The invariant target is the native per-request union (~71 min).**

**(ii) The idle gap N must be ≥ the report interval R, or presence collapses.**
Stitching point-anchored buckets (Kid Mac union minutes, by N × R):

| N \ R | 10 s | 60 s | 120 s | 300 s |
|------:|-----:|-----:|------:|------:|
| 60 s  |  55  |  64  |  **0**|  **0**|
| 120 s |  77  |  85  |  92   |  **0**|
| 180 s |  90  | 101  |  92   |  **0**|
| 300 s | 117  | 119  | 122   |  125  |

When `N < R`, contiguous reporting windows are farther apart than the gap
threshold, nothing merges, every window becomes a zero-span session, and
presence falls to **0**. When `N ≥ R` the number stabilizes (the `N = 300` row
is flat ~117–125 min across R = 10…300).

**Conclusions that shape the model (§4):**

- Presence must be **sessionized on activity timestamps and aggregated by
  profile interval-union**, never "credit a bucket" and never "sum per-app."
- The idle window must satisfy **N ≥ R** (recommend **N ≥ 2 × R** for margin).
  At today's R = 60 s, N = 120 s is safe; if reporting moves to 5 minutes, a
  120 s gap would silently zero out presence unless timing is anchored on
  per-request events.
- For true rate-independence, **anchor session timing on `connection_events`
  (per-request, R-independent)** and keep `traffic_reports` for the byte-based
  heartbeat classification. That removes R from the formula entirely.

## 3. Tunable parameters and current values

| parameter | where | current | role |
|-----------|-------|--------:|------|
| `usage_report_interval` (bucket size / report interval R) | router UCI | **60 s** | resolution of the evidence — **must not** be a term in the presence formula |
| `activity_sample_int` | router UCI | **10 s** | sub-window sample granularity; floor of `activeSeconds` |
| `bucketSeconds` definition | API `Presence.scala:52` | `max(activeSeconds)` | **the undercount driver** — to be replaced by session-stitch |
| presence aggregation | API `Presence.scala` | group by `period_start`, `max`, sum buckets | bucket-grid dedup — to be replaced by interval-union |
| idle / continuation window `N` | — | **none** | does not exist today; recommend **120 s**, constraint **N ≥ 2·R** |
| `heartbeat_bytes_threshold` | `household_settings` | **10 240** | strips keepalive bytes |
| `heartbeat_host_patterns` | `household_settings` | 16 patterns (V24) | strips keepalive FQDNs |
| `heartbeat_filter_enabled` | `household_settings` | **true** | master switch |

## 4. Recommended model

One primitive — **session-stitch** — with two aggregations. All of it server-side
in the API rollup (`Presence.scala`).

### 4.1 The session-stitch primitive (idle gap `N`)

Per `(device, app)`, take the **non-heartbeat** activity in timestamp order and
fold it into sessions:

```
sort activity by time
start session at first activity
for each next activity:
    if (next.time − session.lastActivity) ≤ N:  extend session   # still engaged
    else:                                        close session; start new one
session presence interval = [first activity, last activity]
```

"No activity for `N` ⇒ done"; otherwise everything between first and last
activity counts as continuous. **`N = 120 s`** (sensitivity 180 s), chosen at the
knee of the §2c think-gap distribution — it spans a full traffic-free reporting
window plus margin while the > 180 s real breaks (the 23- and 53-minute gaps in
the raw Math Academy session) stay separate.

**Activity granularity (this is what makes it rate-independent):** anchor on
per-request `connection_events` timestamps where available, so the session
boundaries don't depend on `usage_report_interval`. When only `traffic_reports`
buckets are available, each non-heartbeat row contributes its `[period_start,
period_end]` interval as the activity — correct in the limit, but with a
trailing-edge uncertainty of one report interval (§2d).

### 4.2 Aggregation 1 — daily cap = **profile interval-union**

The daily cap (and any "total screen time") is the **union** of every session
interval across all of the profile's apps **and** devices — measured as covered
wall-clock, so two apps (or two devices) active in the same minute count once.
This replaces the current "group by `period_start`, take max, sum buckets" dedup
(`totalSecondsByMac` / `dedupedTotalSeconds`) with a true interval union, and is
the most rate-stable aggregate (§2d table (i), union column). **Never sum
per-app session minutes for the cap** — that double-counts and is unstable.

### 4.3 Aggregation 2 — per-app time-on-site = **that app's session span**

Per-app screen-time ("how long on Math Academy") is the sum of *that one app's*
session spans — the successor to `proportionalMins` / `proportionalHostSeconds`.
Same primitive, no union across apps. Heartbeat filtering should apply here too
(today the per-host surfaces don't filter — fold that in).

### 4.4 The two invariants the implementation must hold

1. **Rate-independence: `N ≥ R` (recommend `N ≥ 2·R`).** §2d (ii) shows that
   when `N < R` presence collapses to **0**. The code must read `period_start` /
   `period_end` from the data (never assume 60 s), and a config check should
   reject `presence_continuation_seconds < 2 × usage_report_interval`. Anchoring
   on per-request events (§4.1) removes `R` from the formula and makes this
   automatic.
2. **Heartbeat composition.** Sessions are built from **non-heartbeat** activity
   only. A keepalive-only window (bytes < 10 KB or an allowlist FQDN) cannot
   start or extend a session; the idle window bridges *across* such a window only
   when a non-heartbeat session sits within `N` on each side. This keeps the fix
   from re-inflating exactly what the heartbeat filter strips (#714/#789).
   Required test: keepalive-only window between two real sessions is bridged;
   a keepalive-only run longer than `N` is not.

### 4.5 Why server-side, not router-side

Sessionization and union are rollup concerns: the heartbeat filter is
server-side and the router resets its tracker every report interval, so it can
neither see the post-filter set nor bridge across windows. A router-side
"fill-forward" would re-inflate keepalives. #842 (router-side foreground-host
heuristic) still helps *sharpen per-host byte-share* but is independent of this
fix and is blocked on the agent freeze.

### 4.6 Make the knobs configurable + invalidate the rollup cache

Add to `household_settings` (a **small** table — no growth-table migration risk):

- `presence_continuation_seconds` — default **120**; validated `≥ 2 × R`.
- `presence_model` — `session` (recommended default) | `legacy` (the current
  `max(activeSeconds)` path, kept for one deprecation window).

`time_used_daily` must be invalidated (`DELETE`) on any change to these, exactly
as it already is for the heartbeat settings, so the next rollup refills with the
new semantics.

## 5. Implementation sub-issues to file

> File these against the Tuning epic (#1445). The eventual tuning change ships
> with **default-pinning tests (cf. #930)** and a **replay validation
> (cf. #790)**. Note the **migration-isolation two-PR rule**: any schema change
> lands in its own migration-only PR before the code that adopts it.

1. **DB: `household_settings` presence-tuning columns (schema-only PR).** Add
   `presence_continuation_seconds` (default 120) and `presence_model` (default
   `session`). Migration + docs only. Small table → no prod-volume concern.

2. **API rollup: session-stitch primitive + interval-union daily cap.** Replace
   `bucketSeconds = max(activeSeconds)` and the `period_start`-grid dedup in
   `totalSecondsByMac` / `dedupedTotalSeconds` with: per-(device,app) session
   stitching on the idle gap, then profile-level interval union. Read window
   bounds from `period_start`/`period_end`; **enforce `N ≥ 2 × R`**. Keep the
   `legacy` path behind `presence_model`. Tests pin: (a) a continuous sparse
   session reads its full span, not the sampled floor; (b) the same logical day
   re-bucketed at R = 10 s vs 300 s yields the same minutes (the §2d invariant);
   (c) two concurrent apps in one minute = one minute (union).

3. **API rollup: per-app time-on-site via session span + heartbeat filtering on
   per-host surfaces.** Re-express `proportionalHostSeconds` as per-app session
   spans; apply the heartbeat filter to per-host/per-site surfaces (today they
   don't). Test the `N < R` collapse guard and the keepalive-bridge composition
   from §4.4.

4. **Anchor session timing on `connection_events` (rate-independence).** Join
   per-request timestamps (timing) with `traffic_reports` (bytes / heartbeat
   classification) so the report interval `R` drops out of the formula entirely.
   Mind the §query-explain rule — `connection_events` is a growth table; prove
   the join's plan at prod scale and add the supporting index in the same PR.
   (Can land after #2 as the rate-independence hardening.)

5. **Admin / LuCI UI surface for the presence knobs.** Mirror the heartbeat
   settings UI (#754/#760) — expose `presence_continuation_seconds` with the
   `N ≥ 2 × R` validation surfaced, and `presence_model`.

6. **Presence replay/validation harness (sibling of #790).** Extend the replay
   tooling under `scripts/analysis/` to reproduce §2b/§2d (current vs session at
   each `N`, and the re-bucketing invariance check) over a week of prod data, as
   the go/no-go gate on the defaults.

7. **e2e default-pinning gate (extends #930).** Pin `presence_model = session`
   and `presence_continuation_seconds = 120` end-to-end so a future PR — or a
   change to `usage_report_interval` — can't shift the visible numbers silently.

8. **(Complementary, not blocking) router-side foreground-host heuristic
   (#842).** Re-confirm scope once the agent freeze lifts; sharpens per-host
   byte-share but is independent of this undercount fix.

## Appendix — reproducing the data

Read-only against prod `traffic_reports` / `connection_events`
(2026-05-30 → 06-05). Core query shapes:

- §2a — `SELECT active_seconds, count(*) FROM traffic_reports GROUP BY 1`.
- §2b — export `(mac,date,period_start,period_end,host,bytes,active_seconds)`
  for the kid macs; in code, drop heartbeats (bytes < 10 240 OR allowlist FQDN),
  group by `period_start`, then compare `Σ max(active_seconds)` vs `Σ span` vs
  gap-merged span at each N.
- §2c — `lag(ts)` window over `connection_events` for the Math Academy FQDN,
  histogram the inter-event gap.
- §2d — export `(mac,host,host_type,ts)` from `connection_events` for the kid
  macs; in code, drop FQDN-allowlist heartbeats, then for each simulated report
  interval `R` collapse the per-request timestamps into width-`R` buckets,
  session-stitch at gap `N`, and report both `Σ` per-app spans and the profile
  interval-union. The union under `N ≥ R` is flat across `R`; `N < R` → 0.
  (`connection_events` carries no bytes, so only the FQDN half of the heartbeat
  filter is modeled here — §2d is an invariance proof of the *algorithm*, not an
  absolute minute count; the production path keeps byte-based filtering via the
  `traffic_reports` join, sub-issue #4.)

Prod credentials were loaded out-of-band (Render API → DB connection string,
captured into a shell variable, never echoed) and the Render API key should be
rotated after the analysis session per the standing key-handling rule.
