# Sessions: stitching `traffic_reports` into per-host sessions

Design for [#260](https://github.com/wifihaven/wifihaven/issues/260) — replace
the per-query log surface with sessions-per-host as the primary user-facing
view of device activity.

## Source of truth

Sessions are derived from the `traffic_reports` table — the per-period
(default 5-min) rollups that the OpenWRT agent posts via
`POST /api/router/usage`. Each row has shape

```
(router_id, mac, ip, hostname, date, period_start, period_end,
 active_seconds, bytes_in, bytes_out)
```

with a unique key on `(router_id, period_start, mac, hostname)`. This is
already the right granularity: each row is one 5-min slice of one device's
traffic to one host. Sessions are contiguous runs of those slices.

`connection_events` (one row per allow/block decision) stays as a separate
**Raw events** diagnostic surface — useful for "did this specific request
get blocked?" deep-dives — but is not the primary user view.

## Stitching algorithm

Given an ordered list of `traffic_reports` rows for a single `(mac, hostname)`
key, sorted by `period_start`:

1. A **session** is a maximal run of rows where each row's `period_start`
   equals the previous row's `period_end` (strict contiguity). Any gap —
   even one missed 5-min period — starts a new session.
2. **Sessions are split at midnight** to align with the daily `time_usage`
   aggregate. A run that would span midnight becomes two sessions, one per
   date. This guarantees that for any (device, host, date), the sum of
   session `durationSeconds` equals what the screen-time view shows.

Rationale: the agent only emits a `traffic_reports` row for a 5-min period
in which there was actual traffic to that hostname. If a row is missing for
a period, the device was idle to that host for those 5 minutes. Treating an
idle period as a session boundary keeps "two 5-min usages separated by a 5-min
break" as two sessions totaling 10 minutes — which is what the time-used
display reports.

Each session emits:

| field              | source                                                                |
| ------------------ | --------------------------------------------------------------------- |
| `mac`              | the (mac, hostname) key                                               |
| `hostname`         | the (mac, hostname) key                                               |
| `routerId`         | first row's `router_id` (a (mac, hostname) run on one router)         |
| `startedAt`        | first row's `period_start`                                            |
| `endedAt`          | last row's `period_end`                                               |
| `durationSeconds`  | **sum of `active_seconds` across rows** (NOT `endedAt - startedAt`)   |
| `bytesIn`          | sum of `bytes_in`                                                     |
| `bytesOut`         | sum of `bytes_out`                                                    |
| `periodCount`      | number of `traffic_reports` rows folded in (debug / "thick" sessions) |

`durationSeconds` is the sum of `active_seconds` rather than wall-clock span
because `active_seconds` reflects actual on-the-wire activity within each
5-min slice. A period that only saw 30s of traffic should contribute 30s, not
300s. The two numbers differ whenever the device was idle within a period.

If a (mac, hostname) run spans two different `router_id`s — e.g. a laptop
moves between two APs in a future multi-router deployment — they are emitted
as **separate sessions**, broken at the router boundary. (Today there's one
router per install, so this is just future-proofing.)

## Materialization: compute-on-read

Two options were considered:

- **(a) Compute on read** — run the stitching as a SQL window query or
  app-side fold over `traffic_reports` for the requested time window.
- **(b) Materialize on write** — maintain a `sessions` table updated as
  `POST /api/router/usage` lands.

We are going with **(a)** for v1. Reasons:

- No schema change, no migration risk.
- Stitching is a heuristic (`GAP_TOLERANCE`); changing it later doesn't
  require backfill.
- Late-arriving / retried reports are handled trivially — the read just sees
  the new rows next time.
- `traffic_reports` already has `idx_traffic_reports_period_start DESC` and
  `idx_traffic_reports_mac_date`. Typical UI queries are bounded to a day or
  a week and scope to one device/profile, so the read is small (hundreds to
  low thousands of rows).

If read perf becomes a problem we revisit by introducing (b) as a
materialized view or a side table updated in the same transaction as the
report ingest. This is filed as a follow-up issue, not done in this PR.

## Implementation shape

- **Stitching is pure** — lives in a small `Sessions` object (Scala) with a
  `stitch(rows: List[TrafficReport], gap: FiniteDuration): List[Session]`
  function. Unit-testable without a DB.
- **Repo** — `TrafficReportRepo` gains a single new query method that fetches
  the rows the API needs in stitching order: `listForFilter(filter)` returning
  `(mac, hostname, router_id, period_start, period_end, active_seconds,
  bytes_in, bytes_out)` ordered by `(mac, hostname, period_start)`. The route
  groups by `(mac, hostname)` and applies `stitch` to each group.
- **Route** — `SessionRoutes.routes(auth, trafficRepo, deviceRepo,
  profileRepo, upRepo)` mounted at `GET /api/sessions`.

## API: `GET /api/sessions`

Same auth as the existing `/api/logs` route (Bearer JWT, admin/parent token).

### Query parameters

| param        | type     | default            | notes                                                                   |
| ------------ | -------- | ------------------ | ----------------------------------------------------------------------- |
| `mac`        | string   | —                  | exact match                                                             |
| `deviceId`   | long     | —                  | resolved to MAC server-side                                             |
| `profileId`  | long     | —                  | filters via `devices.profile_id`                                        |
| `host`       | string   | —                  | `ILIKE '%host%'` (substring, case-insensitive — same as `/api/logs`)    |
| `since`      | ISO-8601 | —                  | sessions with `endedAt >= since`                                        |
| `until`      | ISO-8601 | —                  | sessions with `startedAt < until`                                       |
| `hours`      | int      | 24 (when no since) | shortcut: `since = now - hours` if `since` not given                    |
| `limit`      | int      | 100                | clamped to 500                                                          |
| `cursor`     | string   | —                  | opaque cursor for pagination (next page's `startedAt|id` watermark)     |

Profile-filtered view is enforced even without `profileId`: a parent token
sees only sessions for devices in their visible profiles (same scoping as
`/api/logs` does today via `filterLogs`).

### Response

```json
{
  "sessions": [
    {
      "mac": "aa:bb:cc:dd:ee:01",
      "deviceName": "Kid's iPad",
      "profileId": 3,
      "profileName": "Kids",
      "hostname": "youtube.com",
      "routerId": "…",
      "startedAt": "2026-05-12T14:30:00Z",
      "endedAt":   "2026-05-12T14:44:30Z",
      "durationSeconds": 770,
      "bytesIn": 18234112,
      "bytesOut": 412988,
      "periodCount": 3
    }
  ],
  "nextCursor": "2026-05-12T14:30:00Z|aa:bb:cc:dd:ee:01|youtube.com"
}
```

Sort: most-recent `startedAt` first.

### Pagination

We stitch all rows in the requested time window, then paginate the resulting
session list in the route (sessions are bounded enough for this to be fine at
the limits above). `nextCursor` encodes
`startedAt|mac|hostname` of the last session on the page; the next request
filters to sessions strictly earlier. This is a v1 scheme — straightforward
to swap for keyset pagination later.

## UI

`web/src/pages/LogsPage.tsx` becomes a tabbed page:

- **Sessions** (default) — `api.sessions.list(...)`. Columns: device (name +
  MAC), profile, hostname, started (local time), duration (`Xm Ys` /
  `Xh Ym`), bytes (humanized). Filter chips: device, profile, host, time
  range.
- **Raw events** — `api.logs.query(...)` (the existing endpoint, unchanged).
  Same filters as today.

The existing tests in `web/src/pages/LogsPage.test.tsx` (which target the
query-log shape) are rewritten to cover the new Sessions surface; raw-events
behavior moves to its own test file.

## Out of scope for this PR

- Graphs of usage per profile/device/app — that's
  [#127](https://github.com/wifihaven/wifihaven/issues/127). The session
  shape is the building block; graphs will aggregate it.
- Removing `connection_events` ingestion — it backs Raw events.
- Materialized sessions table — filed as follow-up if (a) is too slow.

## Coordination

- [#259](https://github.com/wifihaven/wifihaven/issues/259) — hostnames in
  query log. Sessions are useless if `hostname` is an IP. Confirm #259's fix
  has landed (or at least that the `traffic_reports.hostname` column is
  populated with real hostnames, not IPs) before merging this PR.
- [#261](https://github.com/wifihaven/wifihaven/issues/261) — screen-time
  shows 0m. Already closed; same source data, so this PR should benefit from
  that fix.
- [#127](https://github.com/wifihaven/wifihaven/issues/127) — usage
  graphs. Sessions API will be the input.
- [#64](https://github.com/wifihaven/wifihaven/issues/64) — traffic /
  session UI. This PR is the v1 of that surface.
