import { describe, it, expect } from 'vitest'
import { formatLeft, tighterLeft } from './budget'

describe('formatLeft (#2935)', () => {
  it('writes minutes under an hour and hours with minutes above', () => {
    expect(formatLeft(20)).toBe('20m')
    expect(formatLeft(70)).toBe('1h 10m')
    expect(formatLeft(120)).toBe('2h')
  })
})

describe('tighterLeft (#2935, D12)', () => {
  it('picks the window with less time left', () => {
    expect(tighterLeft({ dailyLimit: 60, dailyUsed: 40, weeklyLimit: 300, weekUsed: 100 }))
      .toEqual({ text: '20m left today', exhausted: false })
    expect(tighterLeft({ dailyLimit: 60, dailyUsed: 10, weeklyLimit: 300, weekUsed: 230 }))
      .toEqual({ text: '1h 10m left this week', exhausted: false })
  })
  it('uses the only limit that is set', () => {
    expect(tighterLeft({ dailyLimit: 60, dailyUsed: 10 })?.text).toBe('50m left today')
    expect(tighterLeft({ weeklyLimit: 300, weekUsed: 100 })?.text).toBe('3h 20m left this week')
  })
  it('flags a spent budget and names its window', () => {
    expect(tighterLeft({ dailyLimit: 60, dailyUsed: 70, weeklyLimit: 300, weekUsed: 100 }))
      .toEqual({ text: 'Used up today', exhausted: true })
    expect(tighterLeft({ weeklyLimit: 300, weekUsed: 300 }))
      .toEqual({ text: 'Used up this week', exhausted: true })
  })
  it('returns null when no limit is set', () => {
    expect(tighterLeft({})).toBeNull()
  })
})
