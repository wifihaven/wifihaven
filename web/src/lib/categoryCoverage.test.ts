import { describe, it, expect } from 'vitest'
import { profileCoverage } from './categoryCoverage'

// #2824 — the operator believed `adult` was on their profile; it was not, and
// nothing in the UI said so (#2823). Coverage is computed here ONCE so the
// collapsed profile-card chip and the Blocklists coverage overview can never
// disagree about whether a profile is protected.
//
// The union with the household-global sentinel is NOT cosmetic: PolicyService
// unions the global sentinel's `blockedCategories` into every non-default-deny
// profile's enforced `blocklistIds`
// (api/src/policy/PolicyService.scala:769-771, #1771). A profile whose own list
// is empty but which inherits `adult` household-wide IS protected, so calling
// it "blocks nothing" would be a new instance of the bug this issue is about.
const p = (blockedCategories: string[], extra: Partial<{ defaultDeny: boolean; isGlobal: boolean }> = {}) =>
  ({ blockedCategories, defaultDeny: false, ...extra })

describe('profileCoverage (#2824)', () => {
  it('reports `none` only when NOTHING reaches the profile', () => {
    const c = profileCoverage(p([]), [])
    expect(c.kind).toBe('none')
    expect(c.effective).toEqual([])
  })

  it('reports `covered` from the profile\'s own categories, sorted and deduped', () => {
    const c = profileCoverage(p(['malware', 'adult', 'adult']), [])
    expect(c.kind).toBe('covered')
    expect(c.own).toEqual(['adult', 'malware'])
    expect(c.inherited).toEqual([])
    expect(c.effective).toEqual(['adult', 'malware'])
  })

  // The core correctness point: own-only coverage would false-alarm here.
  it('counts categories inherited from the household-global sentinel as coverage', () => {
    const c = profileCoverage(p([]), ['adult'])
    expect(c.kind).toBe('covered')
    expect(c.own).toEqual([])
    expect(c.inherited).toEqual(['adult'])
    expect(c.effective).toEqual(['adult'])
  })

  it('does not double-count a category assigned both on the profile and globally', () => {
    const c = profileCoverage(p(['adult', 'ads']), ['adult'])
    expect(c.own).toEqual(['ads', 'adult'])
    expect(c.inherited).toEqual([])
    expect(c.effective).toEqual(['ads', 'adult'])
  })

  // PolicyService.scala:770 skips the global union under defaultDeny because
  // block-all makes per-category drops redundant — the profile blocks strictly
  // MORE than any category list, so it is never "blocks nothing".
  it('reports `default-deny` for a block-all profile regardless of category lists', () => {
    expect(profileCoverage(p([], { defaultDeny: true }), []).kind).toBe('default-deny')
    expect(profileCoverage(p([], { defaultDeny: true }), ['adult']).kind).toBe('default-deny')
    expect(profileCoverage(p(['ads'], { defaultDeny: true }), []).kind).toBe('default-deny')
  })

  it('does not credit the global sentinel with inheriting from itself', () => {
    const c = profileCoverage(p(['adult'], { isGlobal: true }), ['adult'])
    expect(c.own).toEqual(['adult'])
    expect(c.inherited).toEqual([])
    expect(c.effective).toEqual(['adult'])
  })
})
