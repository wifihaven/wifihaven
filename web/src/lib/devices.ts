import type { Device } from '@/types/api'

// #2621 — the single definition of "this device is unmanaged".
//
// A device is unmanaged when it has appeared on the network but belongs to no
// profile. That is the condition the household's `unmanagedMacPolicy` acts on:
// under `block`, `PolicyService` ships these MACs to the router as
// `blocked = true` with reason `Unmanaged` (api/src/policy/PolicyService.scala).
//
// The predicate was written out by hand in three places before this — the
// Devices page's Unmanaged Devices split, the Profiles page's device grouping,
// and the dashboard's onboarding banner — with the Profiles copy using a loose
// `== null` where the others used strict. They agree today only because
// `profileId` is `number | null` and never `undefined`; that is a coincidence of
// the current wire shape, not something either check was asserting. One
// exported function so a change to what "unmanaged" means lands in one place.
export function isUnmanaged(d: Device): boolean {
  return d.profileId === null
}

export function isManaged(d: Device): boolean {
  return !isUnmanaged(d)
}
