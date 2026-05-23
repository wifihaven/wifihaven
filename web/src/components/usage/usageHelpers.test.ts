import { describe, it, expect } from 'vitest'
import { fmtDuration } from './usageHelpers'

describe('fmtDuration (H:MM, round-half-up minute; 0<s<60 → 0:01)', () => {
  const cases: Array<[number, string]> = [
    [0,     '0:00'],
    [1,     '0:01'],
    [30,    '0:01'],
    [59,    '0:01'],
    [60,    '0:01'],
    [89,    '0:01'],
    [90,    '0:02'],
    [330,   '0:06'],
    [3599,  '1:00'],
    [3600,  '1:00'],
    [7199,  '2:00'],
    [36000, '10:00'],
  ]
  for (const [secs, expected] of cases) {
    it(`${secs}s → ${expected}`, () => {
      expect(fmtDuration(secs)).toBe(expected)
    })
  }
})
