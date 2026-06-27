// #1973 (SPA-ws S5, design docs/design/spa-websocket.md §3.1): the PURE cache-merge
// helpers the ws push handlers patch the React Query cache with. Kept side-effect-free
// (no QueryClient, no socket) so the live-edge merge logic is unit-testable in isolation
// from the transport. Every payload is an existing REST body (§0.3) — these never invent
// a shape, they only splice the live edge into the GET-loaded series.

import type {
  DashboardNow,
  ProfileTimeStatus,
  ProfileTimeSummary,
  QueryLog,
  TrafficUsageAggregateRow,
  TrafficUsageBucket,
  TrafficUsageResponse,
} from '@/types/api'

// ── trafficUsage: merge the live-edge bucket into the GET-loaded series (§3.1) ──────
//
// The push carries ONLY the current/most-recent bucket (a TrafficUsageResponse whose
// window is just that bucket — possibly several rows sharing one windowStart, one per
// group). We join on `windowStart`: replace the rows of the matching head window while
// it accumulates, prepend a new head when the window rolls over. History is never
// refetched or mutated — the older buckets the GET loaded stay exactly put.
export function mergeHeadBucket(
  prev: TrafficUsageResponse | undefined,
  live: TrafficUsageResponse,
): TrafficUsageResponse {
  // Empty head (the rare boundary tick where the head window is empty, SpaPush §5.3):
  // merge nothing — keep prior series, or seed with the empty live shape if first.
  if (!live.aggregateRows || live.aggregateRows.length === 0) return prev ?? live
  if (!prev) return live
  const headStart = live.aggregateRows[0].windowStart
  // Drop any prior rows in the head window (replace-in-place); keep all older buckets.
  const kept = prev.aggregateRows.filter(r => r.windowStart !== headStart)
  return {
    // Carry the live response's window/bucket metadata (the freshest edge) but keep the
    // full merged series. `to` advances to the live edge; `from` stays the GET's window
    // start so the cached series still describes the whole loaded range.
    ...prev,
    bucket: live.bucket,
    to: live.to,
    aggregateRows: [...live.aggregateRows, ...kept],
  }
}

// The head (newest) bucket's rows — the slice the live gauge reads. The series is
// newest-first (GET orders windowStart DESC, mergeHeadBucket prepends the live edge),
// so the head windowStart is row[0]'s.
export function headBucketRows(
  series: TrafficUsageResponse | undefined,
): TrafficUsageAggregateRow[] {
  const rows = series?.aggregateRows ?? []
  if (rows.length === 0) return []
  const headStart = rows[0].windowStart
  return rows.filter(r => r.windowStart === headStart)
}

// ── B/s derivation: client divides bytes-over-bucket (§1.3) — server stays the source ──
//
// The server ships the same `totalBytes*` row the page renders; the gauge derives the
// rate locally (no second "rate" serializer). bucketSeconds is the bucket's nominal
// width; `raw` has no fixed width — it's whatever cadence the router sent usage at, so
// we read it from the row's [windowStart, windowEnd) span (the ingest period), falling
// back to ~60s (today's REST poll cadence) when no row is available.
const BUCKET_SECONDS: Record<Exclude<TrafficUsageBucket, 'raw'>, number> = {
  '1m': 60,
  '10m': 600,
  '1h': 3_600,
  '12h': 43_200,
  '1d': 86_400,
  '1w': 604_800,
}

export function bucketSeconds(
  bucket: TrafficUsageBucket,
  row?: TrafficUsageAggregateRow,
): number {
  if (bucket !== 'raw') return BUCKET_SECONDS[bucket]
  if (row) {
    const span = (new Date(row.windowEnd).getTime() - new Date(row.windowStart).getTime()) / 1000
    if (Number.isFinite(span) && span > 0) return span
  }
  return 60
}

export interface BandwidthRate {
  bytesInPerSec: number
  bytesOutPerSec: number
  bytesPerSec: number
}

export function rateFor(
  row: TrafficUsageAggregateRow,
  bucket: TrafficUsageBucket,
): BandwidthRate {
  const secs = bucketSeconds(bucket, row)
  const inPs = secs > 0 ? row.totalBytesIn / secs : 0
  const outPs = secs > 0 ? row.totalBytesOut / secs : 0
  return { bytesInPerSec: inPs, bytesOutPerSec: outPs, bytesPerSec: inPs + outPs }
}

// Sum the head-bucket rows into the overall-household rate (groupBy:profile → one row
// per profile, so the household total is their sum).
export function overallRate(
  rows: TrafficUsageAggregateRow[],
  bucket: TrafficUsageBucket,
): BandwidthRate {
  return rows.reduce<BandwidthRate>(
    (acc, r) => {
      const x = rateFor(r, bucket)
      return {
        bytesInPerSec: acc.bytesInPerSec + x.bytesInPerSec,
        bytesOutPerSec: acc.bytesOutPerSec + x.bytesOutPerSec,
        bytesPerSec: acc.bytesPerSec + x.bytesPerSec,
      }
    },
    { bytesInPerSec: 0, bytesOutPerSec: 0, bytesPerSec: 0 },
  )
}

// ── connectionEvents: prepend new head rows, bounded + dedup by id (§3.1) ───────────
//
// The push carries the genuinely-new head rows (newest-first); cursor-paged history is
// untouched. We prepend, dedup by id (a row already in the cache is never duplicated —
// the GET and the push can overlap at the boundary), and cap to `limit` so the live
// feed stays bounded.
export function prependHead(
  prev: QueryLog[] | undefined,
  rows: QueryLog[],
  limit: number,
): QueryLog[] {
  const merged = [...rows, ...(prev ?? [])]
  const seen = new Set<number>()
  const out: QueryLog[] = []
  for (const r of merged) {
    if (seen.has(r.id)) continue
    seen.add(r.id)
    out.push(r)
  }
  return out.slice(0, limit)
}

// ── now: derived KPIs computed client-side off the pushed DashboardNow (§3.1) ───────
//
// "Online now" = the count of active devices across all profiles; "Blocked now" = the
// active devices whose profile is currently paused (a paused profile blocks all its
// traffic). Both recompute from the same pushed body — no separate stream.
export interface NowKpis {
  onlineNow: number
  blockedNow: number
}

export function deriveNowKpis(now: DashboardNow | undefined | null): NowKpis {
  const profiles = now?.profiles ?? []
  const activeDevices = profiles.flatMap(p => p.activeDevices)
  const blocked = profiles.filter(p => p.paused).flatMap(p => p.activeDevices)
  return { onlineNow: activeDevices.length, blockedNow: blocked.length }
}

// ── timeStatus: project the pushed ProfileTimeStatus[] onto the lighter summary shape ───
//
// #1974 (S6a, §3.1): the `timeStatus` push carries the full `/api/time/status`
// ProfileTimeStatus[] body. The /profiles collapsed list reads the lighter
// `/api/time/status/summary` shape (ProfileTimeSummary[], #777). ProfileTimeSummary is a
// strict field-subset of ProfileTimeStatus, so we PROJECT the one pushed body onto it (no
// recompute — pure field selection) and patch the summary cache too, so the collapsed bars
// live-update AND their adaptive ladder can safely go dormant while the push is live (§3.3).
// One pushed body is the single source for both caches.
export function projectTimeStatusToSummary(rows: ProfileTimeStatus[]): ProfileTimeSummary[] {
  return rows.map(r => ({
    profileId: r.profileId,
    profileName: r.profileName,
    date: r.date,
    dailyLimitMins: r.dailyLimitMins,
    usedMins: r.usedMins,
    extensionMins: r.extensionMins,
    remainingMins: r.remainingMins,
  }))
}
