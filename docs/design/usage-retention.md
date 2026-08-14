# Usage time-bucketing & retention policy

**Status:** design — closes [#725](https://github.com/wifihaven/wifihaven/issues/725). Pairs
with [#793](https://github.com/wifihaven/wifihaven/issues/793) (partitioning) and
[#716](https://github.com/wifihaven/wifihaven/issues/716) (`/api/usage/series`).

## TL;DR

| Tier | Source | Grain | Retention | Mechanism |
| ---- | ------ | ----- | --------- | --------- |
| Raw | `traffic_reports` | 5-min × (router, mac, host) | **30 days** | router writes; RANGE-partitioned (#793) — weekly partitions past the horizon are DETACHed + DROPped (#812), row-`DELETE` covers the still-partial boundary week |
| Events | `connection_events` | per-DNS-decision row | **30 days** | RANGE-partitioned (#793) — same DETACH+DROP (#812) + boundary-week `DELETE` as `traffic_reports` |
| Hourly | new `traffic_hourly` | 1 h × (router, mac, host) | **3 months** | API-side ZIO cron, every 5 min, UPSERT from raw |
| Daily | new `traffic_daily` | 1 day × (router, mac, host) | **6 months** | API-side ZIO cron, daily at 00:15 local, UPSERT from raw |
| Quota | existing `time_usage` | 1 day × (mac, host) bucket-deduped wall-clock | **30 days** ([#2086](https://github.com/wifihaven/wifihaven/issues/2086)) | sweep `DELETE` on `date` |
| Block events | existing `block_events` | per-block-decision row | **30 days** ([#2086](https://github.com/wifihaven/wifihaven/issues/2086)) | sweep `DELETE` on `ts` |

`/api/usage/series` picks the tier by window width: `≤ 6 h → raw`, `≤ 14 d → hourly`,
`> 14 d → daily`. SPA date pickers expose the 30-day horizon for 5-min mode, 3 months
for hourly, 6 months for daily.

**`time_usage` / `block_events` retention (#2086, 2026-07):** this doc originally
(2026-05) called `time_usage` "unchanged (forever)" and didn't mention
`block_events` at all — both were, in fact, unbounded, the same latent class as
the #2053 partition-runway P0. Every `time_usage` read site queries a single
`date` ("today" — see `Repos.scala`'s `getSecondsAndBytes` / `listForDevice` /
`snapshotAll`), so 30 days is generous headroom over actual demand rather than a
tight bound; `block_events`' only read (`recent`) is `LIMIT`-bounded,
not date-bounded, so 30 days mirrors `connection_events`' raw horizon. Neither
table is partitioned (V41/V42 partitioned only `traffic_reports` /
`connection_events`), so both sweep via plain `DELETE`, same as the other
non-partitioned tiers above.

**Sessions are no longer a surface.** The session-stitching endpoint was removed in
[#845](https://github.com/wifihaven/wifihaven/issues/845); the replacement (Connection
Events + Traffic Usage) reads `connection_events` and `traffic_reports` directly and
inherits their retention. See umbrella [#844](https://github.com/wifihaven/wifihaven/issues/844).

## 1. Raw retention — 30 days

`traffic_reports` is the 5-minute, per-(router, mac, host) fact table the router posts
into ([V2__openwrt.sql](../../api/resources/db/migration/V2__openwrt.sql)). At the time
this doc was written it was believed to be the only growth-unbounded table in the
schema; `time_usage` and `block_events` were also unbounded (fixed in #2086 above).

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
served by aggregates, not the event log. Retention drops the partition (#812) in the
same job that handles `traffic_reports`.

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

- **Scheduling:** ZIO fiber forked from `Main.scala`. Three fibers: `RollupHourlyJob` (every 5 min),
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
- **Sweep job (retention):** same advisory-lock pattern — runs daily. `RetentionSweepJob`
  covers `time_usage`, `block_events`, `traffic_hourly`, `traffic_daily`,
  `connection_events_hourly`, `connection_events_daily` via batched
  `DELETE WHERE <time-col> < $cutoff`; `traffic_reports` and `connection_events` are
  covered by the DETACH+DROP pass below (with `RetentionSweepJob`'s `DELETE` as a
  redundant-but-harmless trim of the still-partial boundary week).

## 10. Coordination with #793 (landed 2026-07, #812)

`traffic_reports` and `connection_events` are now RANGE-partitioned (#793, weekly —
not daily; see `docs/design/db-partitioning.md`), so the retention story for them is
DETACH+DROP rather than row-`DELETE`:

- **Drop instead of delete.** `ALTER TABLE ... DETACH PARTITION` + `DROP TABLE` on a
  weekly partition is metadata-only and instant, and releases space immediately;
  `DELETE` is a long-running write-amplified scan that bloats autovacuum and doesn't
  reclaim disk until VACUUM FULL.
- **Granularity alignment.** Partitions are weekly (not daily), so the 30-day raw
  horizon rounds down to whole weeks: a candidate partition is dropped once its
  upper bound falls at or before `now - 30 days` — see `PartitionRepo.dropExpiredPartitions`
  (`api/src/db/PartitionRepoLive.scala`). The still-partial current/boundary week can
  carry a few rows past the exact day cutoff until the next run rounds that week away
  too; `RetentionSweepJob`'s `DELETE` trims that residual in the meantime.
- **Implementation.** `PartitionMaintenanceJob` runs both the create-future pass (#808)
  and the retention-drop pass (#812) on the same daily fiber, each under its own
  advisory lock (`PartitionRepo.AdvisoryLockKey` / `PartitionRepo.RetentionDropAdvisoryLockKey`)
  so the two — and `RetentionSweepJob`'s own lock — can interleave across instances
  without racing.

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
