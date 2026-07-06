# DB partitioning — design

Closes design portion of #793. Companion to #725 (retention) and supersedes a
few indexes from #796.

**Implementation status (2026-07):** the auto-create half (#808) and the
retention-drop half (#812) described below have both landed —
`PartitionMaintenanceJob` runs both passes on the same daily fiber
(`api/src/usage/PartitionMaintenanceJob.scala`), each behind its own advisory
lock (`PartitionRepo.AdvisoryLockKey` / `PartitionRepo.RetentionDropAdvisoryLockKey`).

## Tables in scope

| Table | Decision | Why |
|---|---|---|
| `traffic_reports` | **Partition** (range, `period_start`) | Largest growth surface; every read predicate is either a time range, a `(mac, period_start)` range, or a `router_id` lookup. The unique constraint `(router_id, period_start, mac, host_type, host_value)` already includes the partition key, so native partitioning works without weakening uniqueness. |
| `connection_events` | **Partition** (range, `ts`) | Growth-bound: one row per outbound connection attempt. All non-trivial queries have a `ts > NOW() - INTERVAL …` predicate or are bounded `LIMIT N ORDER BY ts DESC`. No conflicting unique constraint. Retention via partition-drop is the obvious win. |
| `time_usage` | **Do not partition** | Daily aggregates: row count is bounded at ~`(devices × distinct-hosts-per-device-per-day)`. At household scale (~30 devices, ~hundreds of hosts/day) this is a few thousand rows per day, ~1M after years. Queries are point-lookups on `(device_mac, host_type, host_value, date)` — already covered by the unique index. Partitioning costs catalog overhead without meaningful win, and the unique constraint would have to be widened (it doesn't currently include `date` in the partitioned-friendly position — actually it does: `(device_mac, host_type, host_value, date)` — fine for `date`-partitioning, but the read shapes don't motivate it). Revisit if `time_usage` ever grows past tens of millions of rows. |

## Partition key

`traffic_reports`: `RANGE (period_start)`. Confirmed via grep of `Repos.scala`:

- `WHERE tr.mac = $mac AND tr.date = $date` (single-day, single-device) — `date` is correlated with `period_start::date`; planner prunes by `period_start` constraint check.
- `WHERE tr.date BETWEEN $from AND $to AND tr.mac IN (...)` (range view) — prunes.
- `WHERE tr.router_id = $routerId` (per-router listing) — *does not* benefit from partition pruning, but the existing local `(router_id)` index handles it.

Hash-by-mac was considered and rejected: at household scale (~30 macs) the
partition count would have to be small (<= ~8), and retention-as-drop
becomes impossible because every partition contains every age of data.

`connection_events`: `RANGE (ts)`. Same logic — every hot query has a `ts`
predicate or is `ORDER BY ts DESC LIMIT N` (which pruning still helps once
the planner sees a `WHERE ts >` from caller). Per-mac/per-router listings
(`recent`, `listForMac`) use small `LIMIT`s and the local `(mac, ts DESC)`
index handles them without pruning.

## Granularity

**Weekly** for both tables.

| Option | Pros | Cons | Verdict |
|---|---|---|---|
| Daily | Cleanest retention (`drop everything older than 30d` = drop ~30 partitions) | At ~365/year, catalog overhead grows fast; over-fragmented for current household volume (~40k traffic_reports rows/day fits comfortably in one weekly partition) | Reject |
| **Weekly** | Aligns with realistic retention horizons (30/60/90 days = 4-13 live partitions); ~52/year is small; each partition holds ~300k traffic_reports / ~tens of thousands of conn_events at household scale | Retention drops are coarser-grained — "30 day window" rounds to "4 most-recent weekly partitions" (some rows are kept up to 36 days) | **Choose** |
| Monthly | Lowest catalog overhead | Retention granularity too coarse for 30-day windows; one partition per month means a slow query that scans "last 7 days" still scans the whole current month's partition | Reject |

The "extra week of retained data" cost of weekly-vs-daily is fine — the
operator's retention policy is not legally binding, and the savings vs.
keeping everything forever are still ~99%.

## Local vs global indexes

Postgres native partitioning only supports **local** indexes on partitioned
tables, and any **unique** index must include the partition key. Both
constraints are satisfied:

- `traffic_reports` unique `(router_id, period_start, mac, host_type, host_value)` — includes `period_start`. ✓
- `connection_events` has **no** unique constraints. ✓

All existing indexes become local-per-partition:

**traffic_reports** (V2 + V25):
| Existing index | Post-partition fate |
|---|---|
| `idx_traffic_reports_router (router_id)` | Keep — useful for per-router listings. |
| `idx_traffic_reports_date (date)` | **Drop** — redundant with partition pruning (`date` ≈ `period_start::date`). |
| `idx_traffic_reports_mac_date (mac, date)` | **Drop** — `idx_traffic_reports_mac_period_start` (V25) supersedes it within each partition. |
| `idx_traffic_reports_period_start (period_start DESC)` | **Drop** — partition pruning + a per-partition ordered scan beats it. |
| `idx_traffic_reports_mac_period_start (mac, period_start)` [V25] | Keep as local — main per-device range index. |
| `UNIQUE (router_id, period_start, mac, host_type, host_value)` | Keep as local unique — already includes partition key. |

**connection_events** (V4 + V22):
| Existing index | Post-partition fate |
|---|---|
| `idx_conn_events_ts (ts DESC)` | **Drop** — partition pruning replaces it. |
| `idx_conn_events_mac_ts (mac, ts DESC)` | Keep — per-mac listing path. |
| `idx_conn_events_allowed_ts (allowed, ts DESC)` | Keep — `stats` query filters on `allowed`. |
| `idx_conn_events_router_ts (router_id, ts DESC)` | Keep — per-router listing. |
| `idx_conn_events_mac_dest_resolved` (V22, partial) | Keep — read-side FQDN join in #732. |

The "drop" entries above are deferred to the post-burnin cleanup issue —
removing them before the new plan is proven would burn a re-add migration
if something regresses.

## Auto-create future partitions

**Choice: in-process daily job inside the API (ZIO Schedule).**

Rejected alternatives:
- **pg_partman**: not bundled with Render Managed Postgres `basic-256mb`
  (only `pg_stat_statements`, `pgcrypto`, and a few defaults are installed
  by default; extensions outside the allowlist need a support request).
  Even if available, it adds an out-of-tree operational surface.
- **External cron** (Render Cron Job): another deploy unit; another secret
  with DB creds; out of the operator's normal config-change loop.

The API already runs scheduled jobs (heartbeat-filter rollups, etc.). One
more daily job that runs at startup and again every 24h, calling

```
CREATE TABLE IF NOT EXISTS traffic_reports_YYYY_WW
  PARTITION OF traffic_reports
  FOR VALUES FROM (...) TO (...);
```

for "this week, next week, week-after" is ~30 lines of Scala. Idempotent.
Naming convention: `<table>_YYYY_WW` (ISO week). If the API is down when
a partition rollover is due, the next startup catches up — and writes
that arrive without a target partition fail loudly (which is fine; the
router retries idempotently).

### Horizontal-scaling safety

Today `numInstances: 1` (see `render.yaml`), but the design must not paint
into a corner if prod ever scales out. Two concrete races to address:

1. **CREATE TABLE IF NOT EXISTS** is technically idempotent at the SQL
   level, but concurrent runs of partition-creation from N instances
   produce N redundant catalog lookups + N log lines per partition per
   day, and worse, **two instances racing on `CREATE TABLE … PARTITION
   OF`** can produce a `tuple concurrently updated` error on `pg_class`
   even though the `IF NOT EXISTS` guard is present — Postgres holds
   `AccessExclusiveLock` on the parent during the attach step. One
   instance gets it, the others retry or fail.
2. **DETACH/DROP** in the retention sweep (#812) is **not** safe to run
   concurrently — second runner sees the partition gone and errors, or
   worse, races mid-DETACH.

**Choice: Postgres session-scoped advisory locks.** At job entry:

```sql
SELECT pg_try_advisory_lock(<stable-int-key>);
```

If `false`, another instance is running the job — skip this tick, log
at DEBUG, return. If `true`, hold the lock for the duration of the run
and release at the end (or let session close release it). Separate
lock keys for create-future vs retention-drop so they can interleave
across instances if one is slow.

Why advisory locks over alternatives:
- **Leader-election table** (`scheduled_jobs(name, holder, leased_until)`)
  works but adds schema, requires a heartbeat to handle dead leaders,
  and is more code for what is a one-line need.
- **Designate via env var** ("only instance #0 runs the job") is fragile
  — relies on Render's instance ordering being stable across restarts,
  which it isn't.
- **External cron** (Render Cron Job) sidesteps the coordination
  question but reintroduces the operational surface we rejected above.

Advisory locks are session-scoped: if an instance crashes mid-run,
Postgres releases the lock when the TCP connection drops. No stale-leader
problem.

This applies equally to the create-future job (#808) and the retention
sweep (#812). Both pull from the same lock-key constant module so the
two functions can hold independent locks but the convention is shared.

## Backfill / migration

**Choice: detach-and-reattach.** Create a new empty partitioned parent,
attach the existing table as a single historical partition covering its
full range, then create future weekly partitions normally.

```
-- 1. Rename existing.
ALTER TABLE traffic_reports RENAME TO traffic_reports_pre_partition;

-- 2. Create new partitioned parent with identical column set + constraints.
CREATE TABLE traffic_reports ( ... ) PARTITION BY RANGE (period_start);
-- (same columns; unique constraint and indexes attached after.)

-- 3. Attach the old table as a single partition covering its full range.
ALTER TABLE traffic_reports
  ATTACH PARTITION traffic_reports_pre_partition
  FOR VALUES FROM ('-infinity') TO ('<cutover-date>');

-- 4. Create the cutover partition (and a few weeks ahead) normally.
CREATE TABLE traffic_reports_2026_22
  PARTITION OF traffic_reports
  FOR VALUES FROM ('2026-05-25') TO ('2026-06-01');
-- ...

-- 5. Add local indexes on the parent (Postgres propagates to all partitions).
CREATE INDEX ON traffic_reports (mac, period_start);
-- ...
```

`ATTACH PARTITION` validates that all existing rows fall within the
attached range, which requires a full scan — but no rewrite. At current
prod volume (weeks of 5-minute rollups) this is seconds, not minutes.

**Downtime envelope**: the attach + rename happens inside a single
transaction. Writers that hit the table mid-rename get a brief lock wait
(<1s at current volume). The OpenWRT agent retries idempotently, so a
single dropped post is harmless. **No dual-write window required at this
scale** — that pattern is the right answer when downtime is unacceptable
or volumes are high enough that ATTACH validation takes minutes. Neither
applies here.

If post-burnin we want the historical data also split into weekly
partitions (currently it sits in one giant attached blob), a follow-up
job can `INSERT … SELECT` into weekly partitions and `DETACH/DROP` the
omnibus. Low priority; the omnibus partition still benefits from pruning
because the new parent's range constraint excludes it from future queries
that only ask for recent data.

**Verify points**:
- `SELECT COUNT(*) FROM traffic_reports` matches pre-rename count.
- `EXPLAIN` on `/api/usage/series` shows partition pruning (Append node with
  pruned children).
- 24 hours of writes land in the correct (current-week) partition, not
  the omnibus.

## Retention (joins to #725)

Once #725 picks a retention window (likely 30 / 90 days), retention is:

```
DETACH PARTITION traffic_reports_<old_week>;
DROP TABLE traffic_reports_<old_week>;
```

Run daily by the same in-process job that creates future partitions. Cheap
metadata-only operation; no row-by-row delete, no vacuum debt. **This is
the single biggest operational win** from partitioning — it converts
unbounded growth into bounded storage with O(1) maintenance.

Pause: this issue does **not** decide the retention window — #725 does.
File the partition-drop implementation as **blocked on #725**.

## Interaction with #796 (V25 indexes)

#796 already shipped V25 which adds `idx_traffic_reports_mac_period_start`.
Once partitioning lands:

- V25's index becomes a local-per-partition index (no migration needed —
  CREATE INDEX on the partitioned parent propagates).
- V2's `idx_traffic_reports_date`, `idx_traffic_reports_mac_date`,
  `idx_traffic_reports_period_start` become redundant (see table above) —
  drop in the cleanup issue.
- V4's `idx_conn_events_ts` similarly redundant after `connection_events`
  partitioning.

No #796 indexes need to be re-shipped; partitioning either preserves them
locally or makes them deletable. #796 lands cleanly first.

## Rollback

If partitioning regresses query plans:

1. Stop the auto-create job (config flag).
2. `CREATE TABLE traffic_reports_flat AS SELECT * FROM traffic_reports;`
3. Drop the partitioned parent + child partitions.
4. `ALTER TABLE traffic_reports_flat RENAME TO traffic_reports;`
5. Re-add original V2 indexes.

Step 2 is the only expensive one — full table copy. At current prod
volume, minutes. Acceptable for a never-expected rollback.

A safer rollback exists if the dropped V2 indexes haven't been removed
yet: a migration that does only `ATTACH PARTITION` is fully reversible by
`DETACH PARTITION` + dropping the parent. Keep the V2 indexes for a
burn-in period (suggest 2 weeks) before the cleanup migration.

## Render plan implications

- Partitioning **works on any** Render Managed Postgres plan, including
  `basic-256mb`. No extension required for the in-process auto-create
  approach.
- Catalog overhead from ~52 partitions/table/year is trivial (a few KB of
  `pg_class` rows, comparable to a handful of indexes).
- **Memory pressure may improve**: query planner only opens pages from
  pruned partitions, shrinking the working set in shared buffers. Could
  partially offset the conditions that drove #786's bump from free →
  standard. (Don't predict by how much — measure post-deploy.)
- No upgrade needed; partitioning fits in `basic-256mb`.

## Open operator decisions

1. **Daily vs weekly partitions.** Recommendation: weekly. Daily is
   cleaner for retention but over-fragments at current household volume.
2. **Auto-create lead time.** Recommendation: keep 2 weeks ahead. Smaller
   means a failed cron can cause write failures within days; larger
   means more empty partitions sitting in catalog. 2 weeks is a balance.
3. **Historical-data resplit.** Recommendation: skip. The omnibus
   pre-partition blob is fine left alone; retention will eventually
   drop it whole.
4. **Horizontal-scaling readiness.** Today `numInstances: 1`, but
   advisory-lock-gating both jobs (per above) means scaling out doesn't
   need code changes — confirmed safe by design rather than by config.
