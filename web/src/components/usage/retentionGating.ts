import type { TrafficUsageBucket } from '@/types/api'

// Retention horizons. #1740: the authoritative values now live server-side
// in api/src/usage/RetentionSweepJob.scala and are served from
// GET /api/usage/horizons; TrafficUsagePage fetches them at boot and passes
// them to bucketAvailability(). The shape and the DEFAULT_RETENTION_HORIZONS
// constant below are a FALLBACK ONLY — used when the fetch hasn't landed yet
// (first paint) or fails (offline / 5xx). The server is the single source of
// truth; this fallback is intentionally conservative so the gate degrades to
// pre-#1740 behaviour rather than failing closed during an outage.
export interface RetentionHorizons {
  rawDays: number
  hourlyDays: number
  dailyDays: number
}

// Fallback values — chosen to match the current sweep job at the time of
// writing so the gate behaves identically pre-fetch. NOT a contract the
// caller must keep in sync: the server's response wins as soon as it
// arrives. If the sweep job changes, leaving these stale only widens the
// offline-fallback window — production behaviour still tracks the server.
export const DEFAULT_RETENTION_HORIZONS: RetentionHorizons = {
  rawDays: 30,
  hourlyDays: 90,
  dailyDays: 180,
}

type SourceTier = 'raw' | 'hourly' | 'daily'

// Which source tier backs each display bucket — mirrors the bucket→tier
// grouping in UsageRoutes.scala (minBucketFor / bucketRank): sub-hourly
// buckets can only come from raw 5-min rows; 1h/12h come from the hourly
// rollup (or finer); 1d/1w come from the daily rollup (or finer). A bucket is
// retained as far back as the *coarsest* tier that can produce it, which is
// exactly the tier named here.
const BUCKET_TIER: Record<TrafficUsageBucket, SourceTier> = {
  raw: 'raw',
  '1m': 'raw',
  '10m': 'raw',
  '1h': 'hourly',
  '12h': 'hourly',
  '1d': 'daily',
  '1w': 'daily',
}

const DAY_MS = 24 * 60 * 60 * 1000

export interface BucketGate {
  enabled: boolean
  reason?: string
}

function horizonFor(tier: SourceTier, h: RetentionHorizons): number {
  switch (tier) {
    case 'raw':
      return h.rawDays
    case 'hourly':
      return h.hourlyDays
    case 'daily':
      return h.dailyDays
  }
}

function reasonFor(tier: SourceTier, h: RetentionHorizons): string {
  switch (tier) {
    case 'raw':
      return `5-minute resolution is kept for ${h.rawDays} days. Switch to a coarser bucket to see older data.`
    case 'hourly':
      return `Hourly resolution is kept for ${h.hourlyDays} days. Switch to a daily bucket to see older data.`
    case 'daily':
      return `Daily resolution is kept for ${h.dailyDays} days; no data is retained beyond that.`
  }
}

// Given the right-edge anchor of the viewing window (`until`; null = now),
// return the enable/disable state for every granularity bucket. A bucket is
// disabled when its newest visible point is already older than its source
// tier's retention horizon — i.e. the whole window has been swept.
export function bucketAvailability(
  until: Date | null,
  now: Date,
  horizons: RetentionHorizons = DEFAULT_RETENTION_HORIZONS,
): Record<TrafficUsageBucket, BucketGate> {
  const daysAgo = until === null ? 0 : (now.getTime() - until.getTime()) / DAY_MS
  const gates = {} as Record<TrafficUsageBucket, BucketGate>
  for (const bucket of Object.keys(BUCKET_TIER) as TrafficUsageBucket[]) {
    const tier = BUCKET_TIER[bucket]
    const enabled = daysAgo <= horizonFor(tier, horizons)
    gates[bucket] = enabled ? { enabled } : { enabled, reason: reasonFor(tier, horizons) }
  }
  return gates
}
