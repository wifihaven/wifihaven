# Usage time-bucketing & retention policy

**Status:** design — closes [#725](https://github.com/wifihaven/wifihaven/issues/725). Pairs
with [#793](https://github.com/wifihaven/wifihaven/issues/793) (partitioning) and
[#716](https://github.com/wifihaven/wifihaven/issues/716) (`/api/usage/series`).

## TL;DR

| Tier | Source | Grain | Retention | Mechanism |
| ---- | ------ | ----- | --------- | --------- |
| Raw | `traffic_reports` | 5-min × (router, mac, host) | **30 days** | router writes; sweep drops via `DROP PARTITION` if [#793](https://github.com/wifihaven/wifihaven/issues/793) lands, plain `DELETE` otherwise |
| Events | `connection_events` | per-DNS-decision row | **30 days** | sweep `DELETE` (or `DROP PARTITION` if #793 partitions it too) |
| Hourly | new `traffic_hourly` | 1 h × (router, mac, host) | **3 months** | API-side ZIO cron, every 5 min, UPSERT from raw |
| Daily | new `traffic_daily` | 1 day × (router, mac, host) | **6 months** | API-side ZIO cron, daily at 00:15 local, UPSERT from raw |
| Quota | existing `time_usage` | 1 day × (mac, host) bucket-deduped wall-clock | unchanged (forever) | unchanged — written at ingest |

`/api/usage/series` picks the tier by window width: `≤ 6 h → raw`, `≤ 14 d → hourly`,
`> 14 d → daily`. SPA date pickers expose the 30-day horizon for 5-min mode, 3 months
for hourly, 6 months for daily.

**Sessions are no longer a surface.** The session-stitching endpoint was removed in
[#845](https://github.com/wifihaven/wifihaven/issues/845); the replacement (Connection
Events + Traffic Usage) reads `connection_events` and `traffic_reports` directly and
inherits their retention. See umbrella [#844](https://github.com/wifihaven/wifihaven/issues/844).

## 1. Raw retention — 30 days

`traffic_reports` is the 5-minute, per-(router, mac, host) fact table the router posts
into ([V2__openwrt.sql](../../api/resources/db/migration/V2__openwrt.sql)). It is the
only growth-unbounded table in the schema.

**Chosen:** 30 days. Reasons:

- The series endpoint only serves 5-min raw for windows ≤ 6 h (see #5 below). Beyond
  that, every user surface reads the hourly rollup. So *user-facing* demand for raw
  is "the last few days," not "the last month."
- At household scale (~30 devices × ~50 hosts/day × 288 5-min buckets) the raw table
  is ~430 k rows/day → ~13 M rows at 30 d. With the indexes from
  [V25](../../api/resources/db/migration/V25__indexes_for_hot_read_paths.sql) this is
  still index-scannable on `basic-256mb` Postgres.

**Operator override:** the retention horizon is a single config knob
(`usage.rawRetentionDays`, default 30) so a paying-customer SKU could lengthen it
without code changes if the use case ever appears.

### 1a. `connection_events` retention — 30 days

`connection_events` is the per-DNS-decision event log (one row per decision the router
makes, keyed by `(router_id, mac, dest_ip, ts)`). At household scale it grows faster
than `traffic_reports` per active hour, but slower per device-day. **Retention: 30
days**, same horizon as raw traffic for the same reason — anything older than that is
served by aggregates, not the event log. The sweep deletes (or drops the partition,
if [#793](https://github.com/wifihaven/wifihaven/issues/793) covers it) in the same
job that handles `traffic_reports`.

## 2. Hourly rollup — 3 months

A new `traffic_hourly` table:

```sql
CREATE TABLE traffic_hourly (
  router_id       UUID NOT NULL REFERENCES routers(id) ON DELETE CASCADE,
  mac             TEXT NOT NULL,
  hostname        TEXT NOT NULL,
  bucket_start    TIMESTAMPTZ NOT NULL,  -- truncated to the hour, UTC
  active_seconds  INT  NOT NULL,         -- bucket-deduped, see §7
  bytes_in        BIGINT NOT NULL,
  bytes_out       BIGINT NOT NULL,
  sample_count    INT  NOT NULL,         -- # 5-min source buckets — debugging only
  rolled_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (router_id, mac, hostname, bucket_start)
);
CREATE INDEX idx_traffic_hourly_bucket          ON traffic_hourly(bucket_start DESC);
CREATE INDEX idx_traffic_hourly_mac_bucket      ON traffic_hourly(mac, bucket_start DESC);
```

**Retention 3 months** — hourly resolution exists to answer "what did the network
look like over the last quarter," not for year-over-year forensics. The daily tier
handles longer horizons. At ~50 hosts/device × ~30 devices × 24 hr/d × 90 d
≈ 3.2 M rows. Comfortable on `basic-256mb`.

## 3. Daily rollup — 6 months

A new `traffic_daily` with the same shape, `bucket_start` is `DATE` in the router's
local zone (matches `time_usage.date` convention):

```sql
CREATE TABLE traffic_daily (
  router_id       UUID NOT NULL REFERENCES routers(id) ON DELETE CASCADE,
  mac             TEXT NOT NULL,
  hostname        TEXT NOT NULL,
  date            DATE NOT NULL,
  active_seconds  INT  NOT NULL,
  bytes_in        BIGINT NOT NULL,
  bytes_out       BIGINT NOT NULL,
  sample_count    INT  NOT NULL,
  rolled_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (router_id, mac, hostname, date)
);
CREATE INDEX idx_traffic_daily_date     ON traffic_daily(date DESC);
CREATE INDEX idx_traffic_daily_mac_date ON traffic_daily(mac, date DESC);
```

**Retention 6 months.** ~1.5 k rows/day × 180 d ≈ 270 k rows. Trivial. Six months
covers seasonal comparisons (school year vs summer) without committing to
indefinite-history semantics that we'd then have to either honor or migrate away
from. If a year-over-year use case appears, lengthening the horizon is a one-line
config change — but until then we keep the working set bounded.

## 4. Rollup mechanism — pre-computed table via API-side ZIO cron

**Considered:**

| Option | Verdict |
| ------ | ------- |
| Materialized view, `REFRESH MATERIALIZED VIEW CONCURRENTLY` | Rejected. Requires unique index, full re-scan each refresh, all-or-nothing. We want incremental UPSERTs of the freshly-closed bucket only. |
| Per-request aggregation | Rejected. The whole point of the policy is to free `traffic_reports` so retention can drop it. Per-request aggregation pins raw forever. |
| Postgres `pg_cron` | Rejected. Render's managed Postgres doesn't expose extensions reliably; adding one couples us to the deploy platform. |
| **API-side ZIO scheduled fiber** | **Chosen.** Same in-process pattern we already plan for future cleanup; visible in logs; surfaceable via admin endpoint; tested with the same test harness. |

**Hourly job** — every 5 min:

1. `now := NOW() at local zone`
2. Find closed hours (`bucket_start ≤ now - 1 h`) whose latest source row in
   `traffic_reports` has `created_at > traffic_hourly.rolled_at` (or which have no row yet).
3. For each such hour, UPSERT `(router_id, mac, hostname, bucket_start)` with the
   aggregate of raw rows whose `period_start` falls in that hour.
4. Conflict resolution: `ON CONFLICT (router_id, mac, hostname, bucket_start)
   DO UPDATE SET …` — idempotent.

**Daily job** — at 00:15 router-local each day, same pattern at day granularity.

**Multi-instance safety via Postgres advisory locks.** Each job wraps its execution
in `SELECT pg_try_advisory_lock($key)` (session-scoped) and bails out immediately if
the lock can't be acquired — that tick is being handled by another instance. Two
fixed integer keys (chosen now and reserved in code, never reused):

- `0x726c7570_68720001` — `RollupHourlyJob`
- `0x726c7570_64790001` — `RollupDailyJob`
- `0x726c7570_73770001` — `RetentionSweepJob` (see §9)

Why advisory locks rather than alternatives:

| Option | Verdict |
| ------ | ------- |
| **`pg_try_advisory_lock`** | **Chosen.** No new tables, no leases to refresh, auto-released on connection close (no stale-lock cleanup), zero contention because losers skip the tick. |
| Row-level `SELECT … FOR UPDATE SKIP LOCKED` on a `cron_ticks` table | Works but adds a write path and a heartbeat to think about. Equivalent guarantees, more moving parts. |
| Kubernetes/Render-level leader election | We don't have a primitive for it on Render's plan; coupling to deploy platform is the same trap as `pg_cron`. |
| Trust UPSERT idempotency, run on every instance | The UPSERTs *are* idempotent so this is safe for correctness, but two instances racing each other on the same hour is wasted I/O on the cheapest Postgres plan, and the `rollup_runs` table becomes noisy. Reject. |

The UPSERT remains idempotent, so the advisory lock is a load-shedding optimization,
not a correctness gate — if it ever fails open (lost the lock mid-job, instance crash
mid-transaction), the next tick re-rolls cleanly.

**Observability.** A `rollup_runs (job, instance_id, started_at, finished_at, status,
error, rows_upserted)` table records each successful or failed run.
`instance_id` is the process's hostname + PID so logs and DB rows correlate.
The "I skipped because another instance had the lock" case writes a row with
`status='skipped_locked'` only on the *first* skip per tick interval — successive
skips within the same interval don't spam the table. Admin endpoint
`GET /api/admin/rollup-status` returns the last N rows.

## 5. Granularities

Validates [#716](https://github.com/wifihaven/wifihaven/issues/716)'s proposal with one
refinement on the switch points:

| Window width | Bucket size | Source table |
| ------------ | ----------- | ------------ |
| ≤ 6 h | 5 min | `traffic_reports` |
| > 6 h, ≤ 14 d | 1 h | `traffic_hourly` |
| > 14 d | 1 day | `traffic_daily` |

`?granularity=` query param lets the caller force one; default is the table above.
At 14 d hourly = 336 buckets per series — still chart-friendly. Daily switch at 14 d
(not 30) means even the longest "last month" UI views stay in hourly.

## 6. Backfill — one-shot at migration

Rollup tables ship empty. The migration that creates them includes a one-shot backfill
that walks the current `traffic_reports` once and populates both tables. Single
household, ~15 M rows = a few seconds. *Then* the cron takes over.

Considered "roll forward only from cutover" — rejected because it leaves the historical
window invisible to the new endpoint for 35+ days. The backfill is cheap; do it once.

## 7. Indexes for rollup tables

Cross-ref [#796](https://github.com/wifihaven/wifihaven/issues/796) — matches the same
pattern (V25) chose for `traffic_reports`:

- PK `(router_id, mac, hostname, bucket_start)` covers UPSERT and per-(mac, host)
  trend queries.
- `(bucket_start)` covers "what happened on the network at hour X" range scans.
- `(mac, bucket_start)` covers per-device timelines (#127, #301).

**No `(hostname, bucket_start)` index** until a slow query justifies it.

## 8. `time_usage` relationship — keep separate, document the distinction

`time_usage` (per
[V1](../../api/resources/db/migration/V1__init.sql)) is *not* a daily rollup of
`traffic_reports` — it's the **quota** table the daily-cap policy reads. Two
differences matter:

1. `time_usage.seconds_used` is **bucket-deduped wall-clock active seconds** —
   updated at *insert* time so the daily-cap arithmetic works; not derived from
   `traffic_reports` after-the-fact.
2. `time_usage` has no `bytes_in` / `bytes_out` — the cap doesn't care, and adding
   them would mean either dual-writing at ingest or back-computing them.

**Decision:** keep both. Document that `time_usage` is the policy-input table and
`traffic_daily` is the analytics-output table. In principle `traffic_daily` *could*
re-derive `time_usage.seconds_used` (with the bucket-dedup applied across hostnames
within the same (mac, bucket_start) row), and that's a worthwhile follow-up
*after* this lands and we can compare the two — but not in scope here. The cap
quota system continues to write `time_usage` exactly as it does today.

## 9. Operationalization

- **Scheduling:** ZIO fiber forked from `Main.scala` next to the existing
  `ScheduleRepo` wiring. Three fibers: `RollupHourlyJob` (every 5 min),
  `RollupDailyJob` (every 1 h — checks each tick whether 00:15 local has passed for
  any router and not yet been rolled), `RetentionSweepJob` (every 1 h — runs once
  per local day at 03:00).
- **Multi-instance safety:** every fiber's tick begins with
  `SELECT pg_try_advisory_lock($key)`; on `false`, the tick is a no-op (logged once
  per interval per §4). Lock is `pg_advisory_unlock`'d in a `ZIO.acquireRelease`
  ensure block — and auto-released by Postgres if the connection drops mid-job, so
  a crashing instance can't wedge the lock. The next tick on any instance picks up.
- **Failure visibility:** `rollup_runs (job, instance_id, started_at, finished_at,
  status, error, rows_upserted, rows_deleted)`. Latest N rows readable from
  `GET /api/admin/rollup-status` (admin-only).
- **Retries:** idempotent UPSERT means a crash mid-job is safe; the next tick (on
  any instance) re-processes any hours whose source rows changed since `rolled_at`.
- **Sweep job (retention):** same advisory-lock pattern — runs daily at 03:00
  router-local. If [#793](https://github.com/wifihaven/wifihaven/issues/793) lands
  with daily partitions, this is a `DROP TABLE …_YYYYMMDD` per expired partition.
  Otherwise it's batched `DELETE WHERE <time-col> < $cutoff` with a 100 k row chunk
  loop. Covers `traffic_reports`, `connection_events`, `traffic_hourly`,
  `traffic_daily` per the table at the top of this doc.

## 10. Coordination with #793

The retention story is **substantially better** if `traffic_reports` is partitioned:

- **Drop instead of delete.** `DROP TABLE` on a daily partition is instant and
  releases space; `DELETE` is a long-running write-amplified scan that bloats
  autovacuum and doesn't reclaim disk until VACUUM FULL.
- **Granularity alignment.** This policy assumes daily partitions on
  `traffic_reports` — the 35-day horizon is then "drop 35 partitions". If [#793](https://github.com/wifihaven/wifihaven/issues/793)
  picks weekly partitions instead, we round to 5 weeks (35 days). Monthly is too
  coarse — the data-deletion lag would be up to 30 days past intended horizon.
- **Order of landing.** Rollup tables ship first (this design); retention sweep
  ships after [#793](https://github.com/wifihaven/wifihaven/issues/793) picks a partition shape, so the sweep can be written
  against whichever (partition-drop vs DELETE) the partitioning design lands on.

If [#793](https://github.com/wifihaven/wifihaven/issues/793) is rejected / deferred, the sweep falls back to `DELETE` and we
revisit if pgstat shows the autovacuum cost is real.

## 11. User-facing implications — date picker UX

- **Daily-granularity view:** picker limited to last 6 months.
- **Hourly view:** picker limited to last 3 months.
- **5-minute view:** picker limited to last 30 days. Older dates are greyed out
  with tooltip "5-minute resolution is kept for 30 days. Switch to hourly to see
  this date."
- The SPA auto-promotes the granularity when the user expands the window past a
  threshold (see #5), so the constraint is rarely hit explicitly.

## 12. Sessions — removed

The `/api/sessions` surface was removed in [#845](https://github.com/wifihaven/wifihaven/issues/845); see umbrella
[#844](https://github.com/wifihaven/wifihaven/issues/844) for the replacement
(Connection Events + Traffic Usage), which reads `connection_events` and
`traffic_reports` directly and inherits their retention. No separate sessions
retention policy applies.

## Open operator questions

1. **Raw retention knob — config or hard-coded?** Filed as config in the policy
   (`usage.rawRetentionDays`, default 30); confirm you want it operator-tunable
   rather than a constant.
2. **Daily-rollup local zone.** `traffic_daily.date` should follow `time_usage.date`
   convention (router-local, midnight reset). Confirm — alternative is UTC, which
   makes cross-tier joins simpler but desynchronizes from the quota table.
