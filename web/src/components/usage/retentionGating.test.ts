import { describe, it, expect } from 'vitest'
import type { TrafficUsageBucket } from '@/types/api'
import {
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
