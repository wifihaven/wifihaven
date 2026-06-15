import type { BucketGrain, TrafficUsageBucket } from '@/types/api'

// Gating data for the usage date-picker: (a) retention horizons (still
// hard-coded as defaults, paired with #1740 to move them API-side), and (b)
// the bucket → grain mapping the API now emits via GET /api/usage/config
// (#1743). `bucketAvailability` is the consumer; everything below is its
// inputs. The bucket list this file iterates is intentionally SPA-known —
// adding a new bucket code requires a SPA change in addition to the API
// emitting it, since `TrafficUsageBucket` is a closed TS enum.
//
// Retention horizons, mirrored from the server. The sweep job
// (api/src/usage/RetentionSweepJob.scala) drops raw rows after 30d, hourly
// rollups after 90d, daily rollups after 180d. We hard-code the same values
// here so the date-picker never offers a granularity whose source table has
// already been swept. Operator-tunable horizons (GET /api/usage/horizons) are
// a follow-up; until then these constants must track RetentionSweepJob.
export interface RetentionHorizons {
  rawDays: number
  hourlyDays: number
  dailyDays: number
}

export const DEFAULT_RETENTION_HORIZONS: RetentionHorizons = {
  rawDays: 30,
  hourlyDays: 90,
  dailyDays: 180,
}

type SourceTier = BucketGrain

// #1743: which source tier backs each display bucket. The API emits this from
// `BucketPolicy.bucketTiers` (GET /api/usage/config), and the SPA reads it
// via `useUsageConfig`. The defaults below are a fallback so the date-picker
// works before the boot fetch completes (or when offline) and double as the
// list of buckets the SPA renders.
export const DEFAULT_BUCKET_TIERS: Record<TrafficUsageBucket, SourceTier> = {
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
//
// `bucketTiers` is the API-emitted mapping (#1743); falls back to
// `DEFAULT_BUCKET_TIERS` when the caller hasn't fetched it yet. A bucket not
// present in the supplied map falls back to its default tier, so an older API
// image missing a newer client-side bucket code does not break the UI.
export function bucketAvailability(
  until: Date | null,
  now: Date,
  horizons: RetentionHorizons = DEFAULT_RETENTION_HORIZONS,
  bucketTiers: Record<string, SourceTier> = DEFAULT_BUCKET_TIERS,
): Record<TrafficUsageBucket, BucketGate> {
  const daysAgo = until === null ? 0 : (now.getTime() - until.getTime()) / DAY_MS
  const gates = {} as Record<TrafficUsageBucket, BucketGate>
  for (const bucket of Object.keys(DEFAULT_BUCKET_TIERS) as TrafficUsageBucket[]) {
    const tier = bucketTiers[bucket] ?? DEFAULT_BUCKET_TIERS[bucket]
    const enabled = daysAgo <= horizonFor(tier, horizons)
    gates[bucket] = enabled ? { enabled } : { enabled, reason: reasonFor(tier, horizons) }
  }
  return gates
}
