import { describe, it, expect } from 'vitest'
import { formatMins, groupBucketsByLocalDay, localBucketOffsetMin } from './timeFormat'

// Extracted from the old TimePage.test.tsx during the #978 cleanup — the
// helpers now live in `web/src/lib/timeFormat.ts` and these tests pin their
// behavior independently of any UI host.

describe('localBucketOffsetMin (#794)', () => {
  it('returns 0 for whole-hour zones', () => {
    const d = new Date('2026-05-21T12:00:00Z')
    expect(localBucketOffsetMin(d)).toBe(0)
  })
  it('snaps to the nearest 15-min multiple', () => {
    expect([0, 15, 30, 45]).toContain(localBucketOffsetMin())
  })
})

describe('groupBucketsByLocalDay (#794)', () => {
  it('rolls UTC hours into 7 contiguous local-day buckets ending at `to`', () => {
    const out = groupBucketsByLocalDay(
      [
        { bucketStart: '2026-05-14T08:00:00Z', usedMins: 20 },
        { bucketStart: '2026-05-17T08:00:00Z', usedMins: 60 },
        { bucketStart: '2026-05-20T08:00:00Z', usedMins: 15 },
      ],
      '2026-05-20',
    )
    expect(out.map(r => r.date)).toEqual([
      '2026-05-14', '2026-05-15', '2026-05-16',
      '2026-05-17', '2026-05-18', '2026-05-19', '2026-05-20',
    ])
    expect(out.find(r => r.date === '2026-05-14')!.usedMins).toBe(20)
    expect(out.find(r => r.date === '2026-05-17')!.usedMins).toBe(60)
    expect(out.find(r => r.date === '2026-05-20')!.usedMins).toBe(15)
    expect(out.find(r => r.date === '2026-05-18')!.usedMins).toBe(0)
  })
  it('accumulates multiple UTC hours into the same local day', () => {
    const out = groupBucketsByLocalDay(
      [
        { bucketStart: '2026-05-20T08:00:00Z', usedMins: 5 },
        { bucketStart: '2026-05-20T14:00:00Z', usedMins: 25 },
        { bucketStart: '2026-05-20T20:00:00Z', usedMins: 10 },
      ],
      '2026-05-20',
    )
    expect(out.find(r => r.date === '2026-05-20')!.usedMins).toBe(40)
  })

  // #794 boundary pin: previously the chart re-bucketed by UTC date, so a late-night
  // bucket whose UTC instant crossed midnight got attributed to the *next* local day.
  // These tests construct bucket starts from chosen local Y/M/D + H:M via the
  // `new Date(y, m, d, h, m)` constructor (always interpreted in host-local tz), so they
  // pin the boundary correctly regardless of which tz the test runner is in.
  describe('midnight boundary (#794 regression)', () => {
    const localBucketISO = (y: number, m0: number, d: number, h: number, min: number) =>
      new Date(y, m0, d, h, min, 0, 0).toISOString()

    it('a bucket at 23:00 local Thursday attributes to Thursday, not Friday', () => {
      const bs = localBucketISO(2026, 4, 21, 23, 0)
      const out = groupBucketsByLocalDay([{ bucketStart: bs, usedMins: 17 }], '2026-05-21')
      expect(out.find(r => r.date === '2026-05-21')!.usedMins).toBe(17)
      expect(out.find(r => r.date === '2026-05-22')).toBeUndefined()
    })

    it('a bucket at 00:00 local Friday attributes to Friday, not Thursday', () => {
      const bs = localBucketISO(2026, 4, 22, 0, 0)
      const out = groupBucketsByLocalDay([{ bucketStart: bs, usedMins: 9 }], '2026-05-22')
      expect(out.find(r => r.date === '2026-05-22')!.usedMins).toBe(9)
      expect(out.find(r => r.date === '2026-05-21')!.usedMins).toBe(0)
    })

    it('two adjacent buckets straddling local midnight land in different local days', () => {
      const late = localBucketISO(2026, 4, 21, 23, 0)
      const early = localBucketISO(2026, 4, 22, 0, 0)
      const out = groupBucketsByLocalDay(
        [
          { bucketStart: late, usedMins: 30 },
          { bucketStart: early, usedMins: 12 },
        ],
        '2026-05-22',
      )
      expect(out.find(r => r.date === '2026-05-21')!.usedMins).toBe(30)
      expect(out.find(r => r.date === '2026-05-22')!.usedMins).toBe(12)
    })
  })
})

describe('formatMins (#791)', () => {
  it('renders sub-60m values as "Xm"', () => {
    expect(formatMins(0)).toBe('0m')
    expect(formatMins(13)).toBe('13m')
    expect(formatMins(59)).toBe('59m')
  })
  it('renders 60m+ as "H:MM"', () => {
    expect(formatMins(60)).toBe('1:00')
    expect(formatMins(195)).toBe('3:15')
    expect(formatMins(621)).toBe('10:21')
    expect(formatMins(260)).toBe('4:20')
  })
  it('coerces non-finite / negative to "0m"', () => {
    expect(formatMins(NaN)).toBe('0m')
    expect(formatMins(-5)).toBe('0m')
    expect(formatMins(Infinity)).toBe('0m')
  })
})
