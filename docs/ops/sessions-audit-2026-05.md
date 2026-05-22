# `/api/sessions` data-quality audit — 2026-05

Audit for [#817](https://github.com/wifihaven/wifihaven/issues/817). Read-only
against prod (`https://api.wifihaven.net`). No production code changes in
this PR.

## Source files referenced

- Stitcher: [api/src/sessions/Sessions.scala](../../api/src/sessions/Sessions.scala)
- Route: [api/src/routes/SessionRoutes.scala](../../api/src/routes/SessionRoutes.scala)
- Repo SQL: [api/src/db/Repos.scala](../../api/src/db/Repos.scala) — `listSessionRows`
- Time accounting: [api/src/presence/Presence.scala](../../api/src/presence/Presence.scala) — `totalSecondsByMac`
- Time-usage write path: [api/src/routes/RouterIngestRoutes.scala](../../api/src/routes/RouterIngestRoutes.scala) — `applyDelta`
- Index: [V25](../../api/resources/db/migration/V25__indexes_for_hot_read_paths.sql) — `idx_traffic_reports_mac_period_start`
- Design doc: [sessions-design.md](../sessions-design.md)

## Findings

### Finding 1 — Per-(mac, day) reconciliation: sessions sum ≫ `time/status` device total

- **Reproducer**: `mac=52:1a:60:d8:4e:32` (Sameer Mac), date `2026-05-21`.
- **Measurement**
  - `/api/time/status?date=2026-05-21` → Sameer profile, device `usedMins = 430` (`~7h10m`).
  - `/api/sessions?mac=…&since=…T00:00:00Z&until=…T00:00:00Z` paginated to exhaustion: **1020 sessions, Σ durationSeconds = 148 200 s = 2 470 min (~41h7m)**.
  - Ratio **≈ 5.7×** over `time/status` device total.
- **Diagnosis**: Σ session `durationSeconds` is `Σ over all (host, period)` of `active_seconds`. `time/status` uses bucket-deduped accounting in `Presence.totalSecondsByMac` ([api/src/presence/Presence.scala:64](../../api/src/presence/Presence.scala)) where each `(mac, period_start)` counts `max(active_seconds)` once, regardless of how many hosts the device touched. Sessions never apply that dedup. This is the same root cause as #715 at the Presence layer, but exposed via a *different* API surface, so anyone reconciling sessions against `time/status` (the more-trusted side) sees a 3-6× over-count on real devices.
- **Verdict**: Real bug. The over-count is a property of the per-host axis itself — wall-clock sums across hosts cannot equal wall-clock activity without de-duping at the bucket layer. See finding 3 for the structural fix.

### Finding 2 — Sessions split at midnight by design, mis-perceived as a bug

- **Code path**: [Sessions.scala:45](../../api/src/sessions/Sessions.scala) — `groupBy(r => (r.routerId, r.mac, r.host, r.date))`. The `date` axis means a contiguous run across 23:55–00:05 emits as two sessions (one ending at `T00:00:00Z`, one starting at `T00:00:00Z`).
- **Design intent**: [sessions-design.md §Stitching algorithm](../sessions-design.md) explicitly states "Sessions are split at midnight to align with the daily `time_usage` aggregate".
- **Reproducer**: code citation only. Production scans of 2026-05-20→2026-05-21 and 2026-05-21→2026-05-22 midnight windows for the always-on devices (Lennox, Sonos, B-Hyve, NAS, Plex) returned no traffic in `±5 min` around midnight, so no live cross-midnight rows existed to demonstrate. The behaviour is unambiguous in code.
- **Quantified impact**: For any device with a single contiguous activity that crosses midnight, exactly one extra "session" appears per night-crossing run. For UI consumers, the impact is mostly cosmetic; for downstream analytics that count sessions, it inflates the count.
- **Verdict**: **By design**, but **mis-documented for end users**. Operator's framing in #817 treats the split as a defect, not a feature. Two reasonable options:
  - Keep the split (preserves `time_usage` alignment) and label sessions clearly in the UI as "per-day per-host".
  - Drop the `date` from the grouping key and instead clip wall-clock spans to the requested day in the route. Requires a documented contract change.

### Finding 3 — Per-host bucket over-count (cross-ref #715)

- **Reproducer**: `mac=52:1a:60:d8:4e:32` on `2026-05-21`.
- **Measurement**: 172 distinct hosts during the day. Top-8 hosts each show 1-3 h of session time individually. Sum across hosts = 2 470 min vs 430 min wall-clock device total (5.7×). For comparison, `/api/time/status` hostUsage rows for the Sameer profile show 10+ FQDNs each at 14 min on `2026-05-22`, while the per-device total is 14 min — the same multi-host signature.
- **Diagnosis**: Same axis problem as #715. The per-host time displayed by both `time_usage.listForDevice` (rendered by `time/status` hostUsage) and `/api/sessions` is `max(active_seconds) per (mac, host, period_start)` — but a single window of activity touches many hosts and credits each with the full bucket. Fix is upstream: per-(mac, period_start) the bucket has *one* wall-clock duration that should be apportioned across hosts (e.g. byte-weighted), not duplicated.
- **Verdict**: **Real bug, but the fix belongs to #715**, not a new sub-issue. This audit confirms `/api/sessions` is a second surface that exhibits the bug.

### Finding 4 — Heartbeat filter not applied to `/api/sessions`

- **Code path**: [Repos.scala:1034 `listSessionRows`](../../api/src/db/Repos.scala) selects directly from `traffic_reports` with no heartbeat predicate. The post-#788/#799 heartbeat filter only runs inside [Presence.totalSecondsByMac](../../api/src/presence/Presence.scala) and is not threaded into the sessions read.
- **Reproducer**: `mac=ec:11:27:c5:c7:96` (Lutron Bridge — DNS-only heartbeats).
  - `/api/time/status?date=2026-05-22` Family-profile total for Lutron: **0 min**.
  - `/api/sessions?mac=ec:11:27:c5:c7:96` returns dozens of sessions to `8.8.8.8`, `8.8.4.4`, `208.67.222.222`, `209.244.0.3`, etc. with `durationSeconds` of 0-30 s each.
- **Diagnosis**: An idle device whose only traffic is heartbeat DNS shows up as 0 min in `time/status` (filter strips it) but as many phantom sessions in `/api/sessions` (filter never runs there).
- **Verdict**: Real coverage gap. One-shot fix: thread the configured `HeartbeatFilter` into `listSessionRows` (or post-filter in the route before `Sessions.stitch`), with the same `bytes_in+bytes_out < bytesThreshold || host matches heartbeatHostPatterns` predicate Presence uses. Cross-ref #788/#799.

### Finding 5 — `durationSeconds = Σ activeSeconds`, UI labels it "Duration"

- **Code path**: [Sessions.scala:36](../../api/src/sessions/Sessions.scala) docstring is explicit; [LogsPage.tsx:239,252](../../web/src/pages/LogsPage.tsx) renders the column header as `Duration` and formats via `fmtDuration(s.durationSeconds)` which produces `"5m 30s"` style output indistinguishable from wall-clock.
- **Reproducer**: a session with two contiguous 5-min periods where the second only saw 30 s of activity emits `durationSeconds = 330`, formatted as `5m 30s`. The wall-clock span (`endedAt − startedAt`) is `10m`.
- **Quantified impact**: For chatty active hosts (video, file sync) `Σ active = wall-clock` and the labels agree. For sparsely-active hosts the displayed "duration" undercounts the wall-clock span; users have no way to tell which.
- **Verdict**: **By design but mis-presented**. Two safe options:
  - Rename the column to "Active time" (or surface both `Σ active` and `endedAt − startedAt`).
  - Compute and emit both fields in the API (`activeSeconds` and `wallClockSeconds`), let the SPA display whichever the user expects.
- Low-risk, low-impact compared to findings 1, 3, 4.

### Finding 6 — `/api/sessions` query plan post-V25

- **State**: V25 added `idx_traffic_reports_mac_period_start (mac, period_start)`. The query in `listSessionRows` filters by `tr.mac IN (…) AND tr.period_end > $since AND tr.period_start < $until` plus the LATERAL `connection_events` lookup (covered by V22's `idx_conn_events_mac_dest_resolved`). The composite mac/period_start index is well-shaped for the dominant filter.
- **Reproducer (light)**: paging the full `mac=52:1a:60:d8:4e:32, 2026-05-21` window (1020 sessions across 3 pages) completed in under a second per page from a residential link — no qualitative slow-query signal. A definitive EXPLAIN ANALYZE requires DB access, which this audit explicitly does not have (read-only HTTP only).
- **Verdict**: **No new sub-issue from this audit**. Defer to the broader EXPLAIN audit in #798 — if that audit re-flags `/api/sessions`, fix there. Cross-ref #796 (which shipped the index).

## Summary table

| # | Failure mode                          | Verdict             | Action                          |
|---|---------------------------------------|---------------------|---------------------------------|
| 1 | sessions Σ vs `time/status` device    | Real bug            | Subsumed by finding 3 / #715    |
| 2 | midnight split                        | By design           | UX-only sub-issue               |
| 3 | per-host bucket over-count            | Real bug            | Cross-ref #715 — fix lives there |
| 4 | heartbeat filter missing on sessions  | Real bug            | New sub-issue (one-line fix)    |
| 5 | `durationSeconds = Σ active`          | By design           | UX-only sub-issue               |
| 6 | EXPLAIN ANALYZE post-V25              | Looks fine          | Defer to #798                   |

## Retention follow-up

Once findings 3 and 4 land, re-open the sessions-retention question on
[#725](https://github.com/wifihaven/wifihaven/issues/725). The "derived view,
bounded by raw traffic_reports retention" framing remains correct; this audit
finds no evidence that sessions need to outlive the 30-day raw horizon.
