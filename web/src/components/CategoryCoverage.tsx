// #2824 — not implemented yet (red step).
import type { Profile } from '@/types/api'

export type GlobalCoverageStatus = 'pending' | 'error' | 'ready'

export function ProfileCoverageChip(_props: {
  profile: Profile
  globalCategories: readonly string[]
  globalStatus: GlobalCoverageStatus
}) {
  return null
}

export function CategoryCoverageOverview() {
  return null
}
