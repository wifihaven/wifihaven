// #2824 — "which categories does this profile actually block?", computed in ONE
// place (AGENTS.md#single-source-of-truth) so the collapsed profile-card chip and
// the Blocklists coverage overview can never disagree about whether a profile is
// protected. #2823 is what disagreement costs: the operator believed `adult` was
// on their profile, it was not, and the first feedback was adult pop-ups.
//
// The union with the household-global sentinel is load-bearing, not cosmetic.
// `PolicyService` unions the global sentinel's `blockedCategories` into every
// non-default-deny profile's enforced `blocklistIds`
// (api/src/policy/PolicyService.scala:769-771, #1771), so a profile whose own
// list is empty but which inherits `adult` household-wide IS protected. Reading
// own-categories-only would flag it as "blocks nothing" — a false alarm, and a
// false alarm is how an operator learns to ignore the one alarm that matters.
//
// Default-deny is the other case the own-list reading gets wrong in the opposite
// direction. `computeBlockRules` collapses a default-deny profile to block-all
// with `blocklistIds = Nil` (PolicyService.scala:1518-1526, #1318): its category
// list is empty *because* it blocks strictly more than any category list could.

/**
 * `default-deny` — blocks everything by default; categories are moot.
 * `covered`      — at least one category reaches this profile.
 * `none`         — nothing reaches it. The state #2823 was sitting in.
 */
export type CoverageKind = 'default-deny' | 'covered' | 'none'

export interface ProfileCoverage {
  kind: CoverageKind
  /** Categories assigned on the profile itself, sorted. */
  own: string[]
  /** Categories reaching the profile ONLY via the household-global layer, sorted. */
  inherited: string[]
  /** `own` ∪ `inherited`, sorted — what actually enforces. */
  effective: string[]
}

type CoverageInput = {
  blockedCategories: string[]
  defaultDeny: boolean
  isGlobal?: boolean
}

export function profileCoverage(
  profile: CoverageInput,
  globalCategories: readonly string[],
): ProfileCoverage {
  const own = [...new Set(profile.blockedCategories)].sort()
  const ownSet = new Set(own)
  // The sentinel does not inherit from itself; and PolicyService.scala:770 skips
  // the global union under defaultDeny, where it would be redundant anyway.
  const inherits = profile.isGlobal !== true && !profile.defaultDeny
  const inherited = inherits
    ? [...new Set(globalCategories)].filter(c => !ownSet.has(c)).sort()
    : []
  const effective = [...own, ...inherited].sort()
  const kind: CoverageKind = profile.defaultDeny
    ? 'default-deny'
    : effective.length > 0 ? 'covered' : 'none'
  return { kind, own, inherited, effective }
}
