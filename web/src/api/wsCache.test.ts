// #1973 (SPA-ws S5): the pure cache-merge helpers (design §3.1) — tested in isolation
// from the transport. These prove the live-edge splice: replace the head bucket in
// place while it accumulates, prepend on rollover, never touch history; prepend +
// dedup connection events; derive B/s from bytes-over-bucket; derive NOW KPIs.
import { describe, it, expect } from 'vitest'
import {
  bucketSeconds,
  deriveNowKpis,
  headBucketRows,
  mergeHeadBucket,
  overallRate,
  prependHead,
  rateFor,
} from './wsCache'
import type {
  DashboardNow,
  QueryLog,
  TrafficUsageAggregateRow,
  TrafficUsageResponse,
} from '@/types/api'

function aggRow(over: Partial<TrafficUsageAggregateRow> & { windowStart: string }): TrafficUsageAggregateRow {
  return {
    groups: {},
    windowEnd: over.windowStart,
    totalBytesIn: 0,
    totalBytesOut: 0,
    totalSeconds: 0,
    ...over,
  }
}

function series(rows: TrafficUsageAggregateRow[], over: Partial<TrafficUsageResponse> = {}): TrafficUsageResponse {
  return {
    bucket: '1m',
    groupBy: ['profile'],
    from: '2026-06-26T10:00:00Z',
    to: '2026-06-26T10:05:00Z',
    tz: 'UTC',
    rawRows: [],
    aggregateRows: rows,
    ...over,
  }
}

describe('mergeHeadBucket (#1973 §3.1)', () => {
  it('seeds the series when there is no prior cache', () => {
    const live = series([aggRow({ windowStart: '2026-06-26T10:05:00Z', totalBytesIn: 100 })])
    expect(mergeHeadBucket(undefined, live)).toBe(live)
  })

  it('replaces the head bucket in place while it accumulates (same windowStart)', () => {
    const prev = series([
      aggRow({ windowStart: '2026-06-26T10:05:00Z', totalBytesIn: 100 }),
      aggRow({ windowStart: '2026-06-26T10:04:00Z', totalBytesIn: 999 }),
    ])
    const live = series([aggRow({ windowStart: '2026-06-26T10:05:00Z', totalBytesIn: 250 })])
    const merged = mergeHeadBucket(prev, live)
    expect(merged.aggregateRows).toHaveLength(2)
    // head replaced with the fresher byte count, older bucket untouched
    expect(merged.aggregateRows[0].totalBytesIn).toBe(250)
    expect(merged.aggregateRows[1].windowStart).toBe('2026-06-26T10:04:00Z')
    expect(merged.aggregateRows[1].totalBytesIn).toBe(999)
  })

  it('prepends a new head when the bucket rolls over (new windowStart)', () => {
    const prev = series([aggRow({ windowStart: '2026-06-26T10:05:00Z', totalBytesIn: 100 })])
    const live = series([aggRow({ windowStart: '2026-06-26T10:06:00Z', totalBytesIn: 5 })])
    const merged = mergeHeadBucket(prev, live)
    expect(merged.aggregateRows.map(r => r.windowStart)).toEqual([
      '2026-06-26T10:06:00Z',
      '2026-06-26T10:05:00Z',
    ])
  })

  it('keeps multiple rows that share the head windowStart (groupBy:profile)', () => {
    const prev = series([
      aggRow({ windowStart: '2026-06-26T10:05:00Z', groups: { profile: 'Kids' }, totalBytesIn: 1 }),
      aggRow({ windowStart: '2026-06-26T10:05:00Z', groups: { profile: 'Adults' }, totalBytesIn: 2 }),
    ])
    const live = series([
      aggRow({ windowStart: '2026-06-26T10:05:00Z', groups: { profile: 'Kids' }, totalBytesIn: 10 }),
      aggRow({ windowStart: '2026-06-26T10:05:00Z', groups: { profile: 'Adults' }, totalBytesIn: 20 }),
    ])
    const merged = mergeHeadBucket(prev, live)
    expect(merged.aggregateRows).toHaveLength(2)
    expect(merged.aggregateRows.map(r => r.totalBytesIn)).toEqual([10, 20])
  })

  it('merges nothing on an empty live head (boundary tick), keeping the prior series', () => {
    const prev = series([aggRow({ windowStart: '2026-06-26T10:05:00Z', totalBytesIn: 100 })])
    const live = series([])
    expect(mergeHeadBucket(prev, live)).toBe(prev)
  })
})

describe('headBucketRows', () => {
  it('returns only the newest windowStart rows', () => {
    const s = series([
      aggRow({ windowStart: '2026-06-26T10:06:00Z', groups: { profile: 'Kids' } }),
      aggRow({ windowStart: '2026-06-26T10:06:00Z', groups: { profile: 'Adults' } }),
      aggRow({ windowStart: '2026-06-26T10:05:00Z', groups: { profile: 'Kids' } }),
    ])
    expect(headBucketRows(s)).toHaveLength(2)
  })
  it('empty for an empty/undefined series', () => {
    expect(headBucketRows(undefined)).toEqual([])
    expect(headBucketRows(series([]))).toEqual([])
  })
})

describe('B/s derivation (#1973 §1.3)', () => {
  it('divides bytes by the nominal bucket width', () => {
    expect(bucketSeconds('1m')).toBe(60)
    expect(bucketSeconds('1h')).toBe(3600)
    const row = aggRow({ windowStart: '2026-06-26T10:05:00Z', totalBytesIn: 600, totalBytesOut: 120 })
    const r = rateFor(row, '1m')
    expect(r.bytesInPerSec).toBe(10)
    expect(r.bytesOutPerSec).toBe(2)
    expect(r.bytesPerSec).toBe(12)
  })

  it('raw bucket reads the ingest period from the row window span', () => {
    const row = aggRow({
      windowStart: '2026-06-26T10:00:00Z',
      windowEnd: '2026-06-26T10:00:30Z', // 30s ingest period
      totalBytesIn: 300,
    })
    expect(bucketSeconds('raw', row)).toBe(30)
    expect(rateFor(row, 'raw').bytesInPerSec).toBe(10)
  })

  it('overallRate sums the per-profile head rows', () => {
    const rows = [
      aggRow({ windowStart: '2026-06-26T10:05:00Z', totalBytesIn: 600, totalBytesOut: 0 }),
      aggRow({ windowStart: '2026-06-26T10:05:00Z', totalBytesIn: 60, totalBytesOut: 120 }),
    ]
    const o = overallRate(rows, '1m')
    expect(o.bytesInPerSec).toBe(11)
    expect(o.bytesOutPerSec).toBe(2)
    expect(o.bytesPerSec).toBe(13)
  })
})

describe('prependHead (#1973 §3.1)', () => {
  const row = (id: number): QueryLog => ({
    id,
    mac: null,
    deviceName: null,
    profileId: null,
    profileName: null,
    host: { type: 'fqdn', value: `h${id}.com` },
    qtype: 1,
    blocked: true,
    reason: { kind: 'manual' },
    location: null,
    ts: '2026-06-26T10:00:00Z',
  })

  it('prepends new head rows, newest first', () => {
    const out = prependHead([row(2), row(1)], [row(4), row(3)], 20)
    expect(out.map(r => r.id)).toEqual([4, 3, 2, 1])
  })

  it('dedups by id (push overlapping the GET boundary)', () => {
    const out = prependHead([row(2), row(1)], [row(3), row(2)], 20)
    expect(out.map(r => r.id)).toEqual([3, 2, 1])
  })

  it('bounds to the limit', () => {
    const out = prependHead([row(2), row(1)], [row(5), row(4), row(3)], 3)
    expect(out.map(r => r.id)).toEqual([5, 4, 3])
  })

  it('handles an undefined prior cache', () => {
    expect(prependHead(undefined, [row(1)], 20).map(r => r.id)).toEqual([1])
  })
})

describe('deriveNowKpis (#1973 §3.1)', () => {
  const now: DashboardNow = {
    asOf: '2026-06-26T10:00:00Z',
    profiles: [
      {
        id: 1, name: 'Kids', paused: true,
        activeDevices: [
          { id: 1, name: 'iPad', mac: 'a', lastSeenSeconds: 5, topHosts: [] },
        ],
      },
      {
        id: 2, name: 'Adults', paused: false,
        activeDevices: [
          { id: 2, name: 'Phone', mac: 'b', lastSeenSeconds: 5, topHosts: [] },
          { id: 3, name: 'Laptop', mac: 'c', lastSeenSeconds: 5, topHosts: [] },
        ],
      },
    ],
  }
  it('counts online (all active) and blocked (active on paused profiles)', () => {
    const k = deriveNowKpis(now)
    expect(k.onlineNow).toBe(3)
    expect(k.blockedNow).toBe(1)
  })
  it('zeroes for an empty/undefined body', () => {
    expect(deriveNowKpis(undefined)).toEqual({ onlineNow: 0, blockedNow: 0 })
    expect(deriveNowKpis({ asOf: '', profiles: [] })).toEqual({ onlineNow: 0, blockedNow: 0 })
  })
})
