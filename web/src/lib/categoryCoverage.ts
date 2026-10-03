// #2824 — category coverage, computed in ONE place.
//
// TODO: not implemented yet (red step).

export type CoverageKind = 'default-deny' | 'covered' | 'none'

export interface ProfileCoverage {
  kind: CoverageKind
  own: string[]
  inherited: string[]
  effective: string[]
}

export function profileCoverage(
  _profile: { blockedCategories: string[]; defaultDeny: boolean; isGlobal?: boolean },
  _globalCategories: readonly string[],
): ProfileCoverage {
  return { kind: 'none', own: [], inherited: [], effective: [] }
}
