import { describe, it, expect } from 'vitest'
import type { Device } from '@/types/api'
import { isManaged, isUnmanaged } from './devices'

// The shape `GET /api/devices` ACTUALLY returns for a device with no profile.
// `Device.profileId` is `Option[ProfileId]` server-side and the route encodes with
// a derived zio-json codec, which omits a `None` field rather than emitting an
// explicit null — see contract/api-to-router/policy_snapshot.json, whose
// profileless device carries no `profileId` key. So the key is ABSENT, and
// `profileId` reads back `undefined`.
//
// This is the case a strict `=== null` misses, and it is the only case that
// matters: an unassigned device never arrives carrying a literal null.
const wireUnassigned = { id: 5, mac: 'aa:bb:cc:dd:ee:05', name: 'guest-phone' } as unknown as Device

const assigned: Device = {
  id: 1, mac: 'aa:bb:cc:dd:ee:01', name: 'iPad', profileId: 7, profileName: 'Kids',
  lastSeenIp: '192.168.1.20', lastSeenAt: '2026-05-12T00:00:00Z',
}

describe('isUnmanaged — #2621', () => {
  it('treats an omitted profileId (the real wire shape) as unmanaged', () => {
    expect(isUnmanaged(wireUnassigned)).toBe(true)
    expect(isManaged(wireUnassigned)).toBe(false)
  })

  it('treats an explicit null profileId as unmanaged', () => {
    // Not a shape the API produces, but the TS type still permits it and older
    // hand-written call sites assumed it.
    expect(isUnmanaged({ ...assigned, profileId: null, profileName: null })).toBe(true)
  })

  it('treats an assigned device as managed', () => {
    expect(isUnmanaged(assigned)).toBe(false)
    expect(isManaged(assigned)).toBe(true)
  })

  it('does not treat profile id 0 as unmanaged', () => {
    // Guards against a `!d.profileId` shortcut, which would misread a falsy id.
    expect(isUnmanaged({ ...assigned, profileId: 0 })).toBe(false)
  })
})

// #2850: a checked-out shared device has no profile on the wire either, but it is not unmanaged —
// the router blocks it as `CheckedOut` under any household policy (#2847), and assigning it a profile
// is a 409 `device_shared`. Counting it as unmanaged would list it under "Unmanaged Devices" and in
// the Profiles page's add-device picker.
describe('isUnmanaged — shared devices (#2850)', () => {
  it('does not treat a checked-out shared device as unmanaged', () => {
    const checkedOut = { ...wireUnassigned, shared: true } as Device
    expect(isUnmanaged(checkedOut)).toBe(false)
    // It has no profile right now, so it is not "managed" (assigned) either.
    expect(isManaged(checkedOut)).toBe(false)
  })

  it('a checked-in shared device reads as managed by its holder', () => {
    expect(isManaged({ ...assigned, shared: true })).toBe(true)
  })
})
