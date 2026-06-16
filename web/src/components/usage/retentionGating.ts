import type { BucketGrain, TrafficUsageBucket } from '@/types/api'

// Gating data for the usage date-picker:
//   (a) retention horizons — #1740, sourced from `RetentionSweepJob` on the API,
//   (b) the bucket → grain mapping — #1743, sourced from `BucketPolicy` on the API.
// Both ride on `UsageConfig` (GET /api/usage/config); `TrafficUsagePage`
// fetches it via `useUsageConfig` at boot and passes both into
// `bucketAvailability()`. `DEFAULT_RETENTION_HORIZONS` and `DEFAULT_BUCKET_TIERS`
// below are FALLBACKS ONLY — used while the fetch is in flight (first paint)
// or when it fails (offline / 5xx). The server is the single source of truth;
// the fallbacks are intentionally conservative so the gate degrades to
// pre-#1740/#1743 behaviour rather than failing closed during an outage. The
// bucket list this file iterates is intentionally SPA-known — adding a new
// bucket code requires a SPA change in addition to the API emitting it, since
// `TrafficUsageBucket` is a closed TS enum.
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
