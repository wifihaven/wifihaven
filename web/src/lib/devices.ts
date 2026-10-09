import type { Device } from '@/types/api'

// #2621 — the single definition of "this device is unmanaged".
//
// A device is unmanaged when it has appeared on the network but belongs to no
// profile. That is the condition the household's `unmanagedMacPolicy` acts on:
// under `block`, `PolicyService` ships these MACs to the router as
// `blocked = true` with reason `Unmanaged` (api/src/policy/PolicyService.scala).
//
// The comparison is LOOSE (`== null`) and must stay loose. `Device.profileId` is
// `Option[ProfileId]` on the server (shared/src/Models.scala), the route encodes
// the list with a derived zio-json codec (`visible.toJson`, api/src/routes/Routes.scala),
// and zio-json OMITS a `None` field rather than emitting an explicit null. The
// golden contract fixture pins that behaviour — the profileless device in
// contract/api-to-router/policy_snapshot.json carries no `profileId` key at all.
// So an unassigned device reaches the SPA as `{id, mac, name}` and `profileId`
// reads back `undefined`, NOT `null`, and a strict `=== null` never fires.
//
// That is not hypothetical. On main the check was hand-written as four expressions
// across three sites: the Devices page's managed/unmanaged split (strict, both
// halves), the Profiles page's per-profile grouping (loose), and its add-device
// picker (loose). Only the loose ones worked — the strict split left the Unmanaged
// Devices section permanently empty AND listed unassigned devices as managed, since
// `undefined !== null` is true. Collapsing them here fixes the strict site. The `web/src/types/api.ts` declaration still says `number | null`, which
// is what let the strict version look correct. Tracked as #2623; until the type
// admits `undefined`, this function is the only thing standing between us and a
// silently empty unmanaged list.
//
// #2850: a shared device is never unmanaged. Checked out, it has no profile on the wire either, but
// the router blocks it as `CheckedOut` under any household policy (#2847), `unmanagedMacPolicy`
// does not apply to it, and assigning it a profile is a 409 `device_shared`. So it must not land in
// the Devices page's "Unmanaged Devices" section or the Profiles page's add-device picker.
export function isUnmanaged(d: Device): boolean {
  return d.profileId == null && d.shared !== true
}

// A type predicate, not just a boolean: narrowing to `profileId: number` lets call
// sites index the field without an `as number` assertion. That matters for #2623 —
// when `types/api.ts` widens `profileId` to admit `undefined`, a cast would keep
// compiling and keep lying, where this narrowing is re-checked by the compiler.
//
// #2850: "managed" means "on a profile right now". A checked-out shared device is neither managed
// nor unmanaged; a checked-in one is managed by its holder.
export function isManaged(d: Device): d is Device & { profileId: number } {
  return d.profileId != null
}
