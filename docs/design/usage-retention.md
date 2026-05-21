# Usage time-bucketing & retention policy

**Status:** design — closes [#725](https://github.com/wifihaven/wifihaven/issues/725). Pairs
with [#793](https://github.com/wifihaven/wifihaven/issues/793) (partitioning) and
[#716](https://github.com/wifihaven/wifihaven/issues/716) (`/api/usage/series`).

## TL;DR

| Tier | Source | Grain | Retention | Mechanism |
| ---- | ------ | ----- | --------- | --------- |
| Raw | `traffic_reports` | 5-min × (router, mac, host) | **35 days** | router writes; sweep drops via `DROP PARTITION` if [#793](https://github.com/wifihaven/wifihaven/issues/793) lands, plain `DELETE` otherwise |
| Hourly | new `traffic_hourly` | 1 h × (router, mac, host) | **13 months** | API-side ZIO cron, every 5 min, UPSERT from raw |
| Daily | new `traffic_daily` | 1 day × (router, mac, host) | **forever** (cap at 5 y if it ever becomes a problem) | API-side ZIO cron, daily at 00:15 local, UPSERT from raw |
| Quota | existing `time_usage` | 1 day × (mac, host) bucket-deduped wall-clock | unchanged (forever) | unchanged — written at ingest |

`/api/usage/series` picks the tier by window width: `≤ 6 h → raw`, `≤ 14 d → hourly`,
`> 14 d → daily`. SPA date pickers expose the 35-day horizon for 5-min mode and the
full history for daily mode.

## 1. Raw retention — 35 days

`traffic_reports` is the 5-minute, per-(router, mac, host) fact table the router posts
into ([V2__openwrt.sql](../../api/resources/db/migration/V2__openwrt.sql)). It is the
only growth-unbounded table in the schema.

**Chosen:** 35 days. Reasons:

- The series endpoint only serves 5-min raw for windows ≤ 6 h (see #5 below). Beyond
  that, every user surface reads the hourly rollup. So *user-facing* demand for raw
  is "the last few days," not "the last year."
- 35 days (not 30) gives a one-week buffer for week-shaped queries and live-debug
  forensics — when the operator goes investigating a heartbeat-filter regression or
  a missing Presence row, they want the raw rows from a few weeks back, not a heap of
  daily aggregates.
- At household scale (~30 devices × ~50 hosts/day × 288 5-min buckets) the raw table
  is ~430 k rows/day → ~15 M rows at 35 d. With the indexes from
  [V25](../../api/resources/db/migration/V25__indexes_for_hot_read_paths.sql) this is
  still index-scannable on `basic-256mb` Postgres. Two months in we'd be at ~30 M and
  feeling it; the 35-day horizon keeps the working set stable indefinitely.

**Operator override:** the retention horizon is a single config knob
(`usage.rawRetentionDays`, default 35) so a paying-customer SKU could lengthen it
without code changes if the use case ever appears.

## 2. Hourly rollup — 13 months

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

**Retention 13 months** — year-over-year answers ("how much YouTube did the kid use
*last May*?") cost ~720 rows per (mac, host). At ~50 hosts/device × ~30 devices ×
8760 hr/y ≈ 13 M rows/year. Cheap.

## 3. Daily rollup — forever

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

**No retention.** ~1.5 k rows/day × 365 = ~550 k rows/year — *all* historical years.
The table never grows beyond what a basic Postgres handles. If we ever cross
~5 M rows we add a 5-year sweep, but until then "forever" is the right answer for
"how much TV did we watch in 2027".

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

**Single household, single API instance** → no distributed lock needed. Track
`last_run_at`, `last_error`, `rows_upserted` in a small `rollup_runs` table; expose
via admin debug endpoint.

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
  `ScheduleRepo` wiring. Two fibers: `RollupHourlyJob` (every 5 min),
  `RollupDailyJob` (every 1 h — checks each tick whether 00:15 local has passed
  for any router and not yet been rolled).
- **Failure visibility:** `rollup_runs (job, started_at, finished_at, status,
  error, rows_upserted)`. Latest N rows readable from
  `GET /api/admin/rollup-status` (admin-only).
- **Retries:** idempotent UPSERT means a crash mid-job is safe; the next tick
  re-processes any hours whose source rows changed since `rolled_at`.
- **Sweep job (raw retention):** same pattern — runs daily at 03:00 router-local.
  If [#793](https://github.com/wifihaven/wifihaven/issues/793) lands with daily
  partitions, this is a `DROP TABLE traffic_reports_YYYYMMDD` for every partition
  whose date is `< NOW() - usage.rawRetentionDays`. Otherwise it's
  `DELETE FROM traffic_reports WHERE period_start < $cutoff` with a `LIMIT
  100_000` batch loop. The sweep also runs `DELETE FROM traffic_hourly WHERE
  bucket_start < NOW() - INTERVAL '13 months'`.

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

- **Daily-granularity view:** date picker open to the full retention horizon of
  `traffic_daily` (effectively unbounded).
- **Hourly view:** picker limited to last 13 months.
- **5-minute view:** picker limited to last 35 days. Older dates are greyed out
  with tooltip "5-minute resolution is kept for 35 days. Switch to hourly to see
  this date."
- The SPA auto-promotes the granularity when the user expands the window past a
  threshold (see #5), so the constraint is rarely hit explicitly.

## Open operator questions

1. **Daily rollup horizon — actually forever?** "Forever" is fine until ~5 M rows
   (~10 y at current scale). If you'd rather cap it now ("5 y is plenty for a
   household") say so and the sweep gains a 5-y `DELETE` on `traffic_daily`.
2. **Raw retention knob — config or hard-coded?** Filed as config in the policy;
   confirm you want it operator-tunable rather than a constant.
3. **Daily-rollup local zone.** `traffic_daily.date` should follow `time_usage.date`
   convention (router-local, midnight reset). Confirm — alternative is UTC, which
   makes year-over-year queries simpler but desynchronizes from the quota table.
