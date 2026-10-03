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

/**
 * What the household-global sentinel actually contributes to other profiles.
 *
 * NOT simply its `blockedCategories`. The sentinel's contribution is
 * `computeRulesFor(sentinel)` with `blocked` / `blockReason` stripped
 * (`PolicyService.scala:737-741`, #1771), and `computeBlockRules` returns
 * `blocklistIds = Nil` for a `defaultDeny` profile (`PolicyService.scala:1518-1526`,
 * #1318). So a sentinel switched to default-deny contributes **nothing**: its
 * categories reach no profile, and the stripped `blocked` means it cannot
 * block-all the household either.
 *
 * That state is reachable — `PATCH /api/profiles/{id}` rejects only
 * `paused` / `pauseMode` / `timeLimit` on the sentinel (`Routes.scala:802-815`),
 * and `ProfilesPage` renders `DefaultDenySubsection` on the sentinel's card with
 * no `isGlobal` gate. Reading the raw list would credit every profile with
 * coverage nothing enforces: the false all-clear this whole surface exists to
 * prevent.
 */
export function globalContribution(
  sentinel: Pick<CoverageInput, 'blockedCategories' | 'defaultDeny'> | undefined,
): string[] {
  if (!sentinel || sentinel.defaultDeny) return []
  return [...new Set(sentinel.blockedCategories)].sort()
}

export function profileCoverage(
  profile: CoverageInput,
  globalCategories: readonly string[],
): ProfileCoverage {
  // The sentinel is scored by what it CONTRIBUTES, never by the per-profile
  // rules — `default-deny` on the sentinel is not a household-wide block-all
  // (PolicyService strips `blocked`), so it must never render as "Blocks all".
  if (profile.isGlobal === true) {
    const own = globalContribution(profile)
    return { kind: own.length > 0 ? 'covered' : 'none', own, inherited: [], effective: own }
  }

  const own = [...new Set(profile.blockedCategories)].sort()
  const ownSet = new Set(own)
  // PolicyService.scala:770 skips the global union under defaultDeny, where it
  // would be redundant anyway. (The sentinel itself returned above.)
  const inherits = !profile.defaultDeny
  const inherited = inherits
    ? [...new Set(globalCategories)].filter(c => !ownSet.has(c)).sort()
    : []
  const effective = [...own, ...inherited].sort()
  const kind: CoverageKind = profile.defaultDeny
    ? 'default-deny'
    : effective.length > 0 ? 'covered' : 'none'
  return { kind, own, inherited, effective }
}
