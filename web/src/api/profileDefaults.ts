import type { UpsertProfileRequest } from '@/types/api'

// #978 / #2367 — the single source of truth for the "safe-by-default" shape a
// newly created profile boots in (LastKnownGood failover, Sum overlap, no
// blocked categories, no schedules, no daily cap). Both creation surfaces —
// the Profiles page (`ProfilesPage.saveNew`) and the inline "＋ New profile…"
// creator (`InlineProfileCreator`, mounted by both the Add-Device modal and the
// device row editor, #2560) — build their create payload here so the paths
// can't drift. Optional fields (`pauseMode`, `defaultDeny`)
// are intentionally omitted and fall to the server's create defaults.
export function newProfileDefaults(name: string): UpsertProfileRequest {
  return {
    name,
    blockedCategories: [],
    paused: false,
    timeLimit: null,
    failureMode: 'last-known-good',
    crossDeviceOverlapMode: 'sum',
  }
}
