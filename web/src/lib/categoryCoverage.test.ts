import { describe, it, expect } from 'vitest'
import { globalContribution, profileCoverage } from './categoryCoverage'

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

// #2824 review BLOCKER — the sentinel's CONTRIBUTION is not its category list.
// `globalRulesResolved` is `computeRulesFor(sentinel)` with `blocked` /
// `blockReason` stripped (api/src/policy/PolicyService.scala:737-741, #1771), and
// `computeBlockRules` returns `blocklistIds = Nil` for a default-deny profile
// (:1518-1526, #1318). So a sentinel switched to default-deny contributes NOTHING:
// its categories reach no profile, and the stripped `blocked` means it cannot
// block the household either. That state is reachable — PATCH rejects only
// paused/pauseMode/timeLimit on the sentinel (Routes.scala:802-815) and
// ProfilesPage renders DefaultDenySubsection on its card with no isGlobal gate.
describe('globalContribution — a default-deny sentinel contributes nothing (#2824)', () => {
  it('is the sentinel\'s categories when it is not default-deny', () => {
    expect(globalContribution({ blockedCategories: ['malware', 'ads'], defaultDeny: false }))
      .toEqual(['ads', 'malware'])
  })

  it('is EMPTY when the sentinel is default-deny', () => {
    expect(globalContribution({ blockedCategories: ['adult'], defaultDeny: true })).toEqual([])
  })

  it('is empty when there is no sentinel at all', () => {
    expect(globalContribution(undefined)).toEqual([])
  })

  // The false all-clear this surface exists to prevent: a profile whose only
  // coverage came from a now-default-deny sentinel must read as unprotected.
  it('leaves a profile covered only by a default-deny sentinel reading "blocks nothing"', () => {
    const sentinel = { blockedCategories: ['adult'], defaultDeny: true }
    const c = profileCoverage(p([]), globalContribution(sentinel))
    expect(c.kind).toBe('none')
    expect(c.effective).toEqual([])
  })

  // …and the sentinel's own row must not claim to block everything household-wide,
  // because PolicyService refuses to fold its `blocked` into anything.
  it('never scores the sentinel itself as default-deny/block-all', () => {
    const c = profileCoverage(p(['adult'], { isGlobal: true, defaultDeny: true }), [])
    expect(c.kind).toBe('none')
    expect(c.own).toEqual([])
    expect(c.effective).toEqual([])
  })
})
