import { describe, it, expect } from 'vitest'
import type { TrafficUsageBucket } from '@/types/api'
import {
  DEFAULT_BUCKET_TIERS,
  DEFAULT_RETENTION_HORIZONS,
  bucketAvailability,
} from './retentionGating'

// Anchor "now" to a fixed instant so day-offset arithmetic is deterministic.
const NOW = new Date('2026-05-29T12:00:00.000Z')

function daysAgo(n: number): Date {
  return new Date(NOW.getTime() - n * 24 * 60 * 60 * 1000)
}

function enabledBuckets(until: Date | null): TrafficUsageBucket[] {
  const gates = bucketAvailability(until, NOW, DEFAULT_RETENTION_HORIZONS)
  return (Object.keys(gates) as TrafficUsageBucket[]).filter(b => gates[b].enabled)
}

describe('bucketAvailability — gate granularity on retention horizon', () => {
  // Mirrors RetentionSweepJob: raw 30d / hourly 90d / daily 180d, and the
  // bucket→tier grouping from UsageRoutes (sub-hourly→raw, 1h/12h→hourly,
  // 1d/1w→daily).
  it('exposes the server retention constants', () => {
    expect(DEFAULT_RETENTION_HORIZONS).toEqual({
      rawDays: 30,
      hourlyDays: 90,
      dailyDays: 180,
    })
  })

  it('until=null (now) → every bucket enabled', () => {
    expect(enabledBuckets(null)).toEqual(['raw', '1m', '10m', '1h', '12h', '1d', '1w'])
  })

  it('range within last 30d → raw / hourly / daily all available', () => {
    expect(enabledBuckets(daysAgo(10))).toEqual(['raw', '1m', '10m', '1h', '12h', '1d', '1w'])
  })

  it('exactly at the 30d edge → raw still available', () => {
    const gates = bucketAvailability(daysAgo(30), NOW, DEFAULT_RETENTION_HORIZONS)
    expect(gates.raw.enabled).toBe(true)
  })

  it('older than 30d (within 3mo) → raw swept, hourly+daily only', () => {
    expect(enabledBuckets(daysAgo(45))).toEqual(['1h', '12h', '1d', '1w'])
  })

  it('older than 3mo (within 6mo) → daily only', () => {
    expect(enabledBuckets(daysAgo(120))).toEqual(['1d', '1w'])
  })

  it('older than 6mo → nothing retained', () => {
    expect(enabledBuckets(daysAgo(200))).toEqual([])
  })

  it('disabled sub-hourly buckets carry a retention reason mentioning 30 days', () => {
    const gates = bucketAvailability(daysAgo(45), NOW, DEFAULT_RETENTION_HORIZONS)
    expect(gates.raw.enabled).toBe(false)
    expect(gates.raw.reason).toMatch(/30 days/)
    expect(gates['10m'].reason).toMatch(/30 days/)
  })

  it('disabled hourly buckets mention the 3-month horizon', () => {
    const gates = bucketAvailability(daysAgo(120), NOW, DEFAULT_RETENTION_HORIZONS)
    expect(gates['1h'].enabled).toBe(false)
    expect(gates['1h'].reason).toMatch(/90 days|3 months/)
  })

  it('disabled daily buckets mention the 6-month horizon', () => {
    const gates = bucketAvailability(daysAgo(200), NOW, DEFAULT_RETENTION_HORIZONS)
    expect(gates['1d'].enabled).toBe(false)
    expect(gates['1d'].reason).toMatch(/180 days|6 months/)
  })

  it('honours custom horizons (operator-tuned retention)', () => {
    const custom = { rawDays: 7, hourlyDays: 14, dailyDays: 28 }
    const gates = bucketAvailability(daysAgo(10), NOW, custom)
    expect(gates.raw.enabled).toBe(false)
    expect(gates['1h'].enabled).toBe(true)
    expect(gates['1d'].enabled).toBe(true)
  })
})

// #1743: the bucket → grain mapping is API-driven (GET /api/usage/config); the
// SPA falls back to DEFAULT_BUCKET_TIERS when the boot fetch hasn't returned.
describe('bucketAvailability — API-driven bucket tiers (#1743)', () => {
  it('falls back to DEFAULT_BUCKET_TIERS when no map is supplied', () => {
    const gates = bucketAvailability(daysAgo(45), NOW, DEFAULT_RETENTION_HORIZONS)
    // Defaults: raw goes through the raw tier, 1h through hourly.
    expect(gates.raw.enabled).toBe(false)
    expect(gates['1h'].enabled).toBe(true)
  })

  it('uses the supplied bucketTiers map to classify buckets', () => {
    // Re-classify 1h as raw — at 45d it should now be gated out by the raw horizon.
    const overridden = { ...DEFAULT_BUCKET_TIERS, '1h': 'raw' as const }
    const gates = bucketAvailability(daysAgo(45), NOW, DEFAULT_RETENTION_HORIZONS, overridden)
    expect(gates['1h'].enabled).toBe(false)
    expect(gates['1h'].reason).toMatch(/30 days/)
    // 1d is still daily-tier, still enabled.
    expect(gates['1d'].enabled).toBe(true)
  })

  it('a bucket missing from the supplied map falls back to its default tier', () => {
    // Partial map (older API image, or a smaller payload): only `raw` is listed.
    const partial: Record<string, 'raw' | 'hourly' | 'daily'> = { raw: 'raw' }
    const gates = bucketAvailability(daysAgo(120), NOW, DEFAULT_RETENTION_HORIZONS, partial)
    // 1h should still be classified hourly via the default, and gated out at 120d.
    expect(gates['1h'].enabled).toBe(false)
    // 1d should still be classified daily via the default, and enabled at 120d.
    expect(gates['1d'].enabled).toBe(true)
  })

  it('DEFAULT_BUCKET_TIERS mirrors BucketPolicy.grainForBucket', () => {
    // Backstop sanity-check that the fallback agrees with the API's mapping.
    expect(DEFAULT_BUCKET_TIERS).toEqual({
      raw: 'raw',
      '1m': 'raw',
      '10m': 'raw',
      '1h': 'hourly',
      '12h': 'hourly',
      '1d': 'daily',
      '1w': 'daily',
    })
  })
})
