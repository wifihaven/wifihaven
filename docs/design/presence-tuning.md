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

**Recommended tuning (two knobs, both server-side in the API rollup):**

1. **Full-bucket credit.** A 60 s bucket containing real (non-heartbeat)
   activity counts as a present *minute* — credit the bucket's wall-clock span
   (`period_end − period_start`), not `max(activeSeconds)`. This is the bulk of
   the fix (~3.3×).
2. **Cross-bucket continuation window `N = 120 s`.** Bridge a think-gap that
   spans an entirely traffic-free bucket: consecutive non-heartbeat buckets
   whose inter-bucket gap ≤ N count as one continuous present interval. Adds a
   conservative ~5% on top of (1) and recovers genuine ~1-minute think gaps
   without merging real breaks.

Both knobs are **anchored on non-heartbeat rows only**, so they compose with the
existing heartbeat filter: a keepalive-only minute neither starts nor extends a
present interval.

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
- **full** — full-bucket credit: each non-heartbeat bucket → its 60 s span.
- **brN** — full-bucket credit **plus** cross-bucket continuation window N.

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

## 3. Tunable parameters and current values

| parameter | where | current | role |
|-----------|-------|--------:|------|
| `usage_report_interval` (bucket size) | router UCI | **60 s** | minute resolution of presence |
| `activity_sample_int` | router UCI | **10 s** | sub-bucket sample granularity; floor of `activeSeconds` |
| `bucketSeconds` definition | API `Presence.scala:52` | `max(activeSeconds)` | **the undercount driver** |
| idle / continuation window | — | **none** | does not exist today |
| `heartbeat_bytes_threshold` | `household_settings` | **10 240** | strips keepalive bytes |
| `heartbeat_host_patterns` | `household_settings` | 16 patterns (V24) | strips keepalive FQDNs |
| `heartbeat_filter_enabled` | `household_settings` | **true** | master switch |

## 4. Recommended tuning

### 4.1 Full-bucket credit (primary)

Redefine the bucket's presence contribution from `max(activeSeconds)` to the
bucket's **wall-clock span** when the bucket contains at least one non-heartbeat,
non-exempt host:

```
bucketSeconds(bucket) = period_end − period_start    # ≈ 60 s
```

Rationale: presence is a **minute-resolution** quantity. If the device exchanged
real interactive traffic (above the heartbeat floor) with a real host during a
60 s window, that minute counts. This is the same standard already implicit in
the bucket size; we simply stop the 10 s sampler from discarding 5/6 of it.

### 4.2 Cross-bucket continuation window `N = 120 s` (secondary)

Within `totalSecondsByMac` / `dedupedTotalSeconds` (and the per-host
`proportionalHostSeconds`), after dropping heartbeat rows, sort the surviving
buckets by `period_start` and **merge any two consecutive buckets whose gap
(`next.period_start − cur.period_end`) ≤ N into one present interval**; sum the
merged spans. This bridges a think-gap that produced a traffic-free minute.

`N = 120 s` is chosen at the knee of the §2c think-gap distribution: it spans one
fully-empty 60 s bucket plus margin for a slow problem, while the > 180 s real
breaks (the 23-minute and 53-minute gaps seen in the raw Math Academy session)
stay separate.

### 4.3 Composition with the heartbeat filter (must-hold invariant)

Both knobs operate on the **post-heartbeat-filter** row set. Concretely:

- A bucket whose only hosts are heartbeats (keepalive bytes < 10 KB or allowlist
  FQDNs) is **not** a present minute and **cannot** anchor or extend a session.
- The continuation window bridges *across* such buckets only when a
  **non-heartbeat** bucket sits on each side within N — it never resurrects a
  keepalive-only minute as presence.

This is the safety property that keeps the fix from re-inflating exactly what
the heartbeat filter was built to strip (#714/#789). The eventual change must
include a test asserting: *keepalive-only minute between two real minutes is
bridged (counts as present via continuation) but a keepalive-only run longer
than N is not* — i.e. the filter still governs what counts as an anchor.

### 4.4 Why server-side (API rollup), not router-side

The fix belongs in `Presence.scala`, **not** `usage.lua`, because:

- The heartbeat filter is server-side; full-bucket credit and continuation must
  be anchored on the post-filter set, which the router cannot see. A router-side
  "fill-forward active_samples" change would re-inflate keepalives and compose
  badly with the filter.
- Cross-bucket continuation is inherently a rollup concern — the router resets
  its tracker every 60 s and cannot bridge across buckets.
- #842 (router-side foreground-host heuristic) remains worthwhile to *sharpen
  per-host byte-share* for `proportionalMins`, but it is **not required** for the
  undercount fix and is currently blocked on the agent freeze.

### 4.5 Make the knobs configurable + invalidate the rollup cache

Add to `household_settings` (a **small** table — no growth-table migration risk
per the prod-data-volume rule):

- `presence_credit_mode` — `full` (recommended default) | `sampled` (legacy).
- `presence_continuation_seconds` — default **120**.

`time_used_daily` must be invalidated (`DELETE`) on any change to these, exactly
as it already is for the heartbeat settings, so the next 15-minute rollup refills
with the new semantics.

## 5. Implementation sub-issues to file

> File these against the Tuning epic (#1445). The eventual tuning change ships
> with **default-pinning tests (cf. #930)** and a **replay validation
> (cf. #790)**. Note the **migration-isolation two-PR rule**: any schema change
> lands in its own migration-only PR before the code that adopts it.

1. **DB: `household_settings` presence-tuning columns (schema-only PR).** Add
   `presence_credit_mode` (default `full`) and `presence_continuation_seconds`
   (default 120). Migration + docs only, per the migration-isolation rule. Small
   table → no prod-volume concern.

2. **API rollup: full-bucket presence credit.** Change `Presence.bucketSeconds`
   to credit the bucket wall-clock span under `presence_credit_mode = full`;
   keep `sampled` as the legacy path. Apply to `totalSecondsByMac`,
   `dedupedTotalSeconds`, and the per-host `proportionalHostSeconds`. Invalidate
   `time_used_daily` on settings change. **Default-pinning test** asserting a
   single-touch 60 s bucket credits 60 s, not 10 s.

3. **API rollup: cross-bucket continuation window.** Implement the gap-bridge
   (`presence_continuation_seconds`, default 120) in the daily-cap and per-host
   functions, anchored strictly on non-heartbeat rows. Tests: bridge a
   traffic-free minute between two real minutes; do **not** bridge a gap > N;
   keepalive-only run longer than N is not bridged (heartbeat-composition test
   from §4.3).

4. **Admin / LuCI UI surface for the presence knobs.** Mirror the heartbeat
   settings UI (#754/#760) — expose credit mode + continuation seconds with the
   recommended defaults pre-filled.

5. **Presence replay/validation harness (sibling of #790).** Extend the
   heartbeat replay tooling under `scripts/analysis/` with a presence replay that
   reproduces the §2b table (current vs full vs brN per device-day) over a week
   of prod data, as the go/no-go gate on the chosen defaults.

6. **e2e default-pinning gate (extends #930).** Pin `presence_credit_mode = full`
   and `presence_continuation_seconds = 120` end-to-end so a future PR can't shift
   the visible screen-time numbers silently.

7. **(Complementary, not blocking) router-side foreground-host heuristic
   (#842).** Re-confirm scope once the agent freeze lifts; it sharpens
   `proportionalMins` byte-share but is independent of this undercount fix.

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

Prod credentials were loaded out-of-band (Render API → DB connection string,
captured into a shell variable, never echoed) and the Render API key should be
rotated after the analysis session per the standing key-handling rule.
