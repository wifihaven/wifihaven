// #2850: the readable message for each shared-device rejection code the API returns (#2848).
import { describe, it, expect } from 'vitest'
import { HttpError } from '@/api/httpError'
import { sharedDeviceErrorMessage } from './sharedDevices'

const conflict = (body: object) => new HttpError(409, JSON.stringify(body))

describe('sharedDeviceErrorMessage (#2850)', () => {
  it('held: someone else has it checked in', () => {
    expect(sharedDeviceErrorMessage(conflict({ error: 'held' }), { action: 'check-in' }))
      .toBe('Someone else has this device checked in. They need to check it out first.')
  })

  it('not_linked on check-in vs check-out', () => {
    // The 403 reaches the SPA as ForbiddenError, which carries `status` and the body as message.
    const forbidden = Object.assign(new Error('{"error":"not_linked"}'), { status: 403 })
    expect(sharedDeviceErrorMessage(forbidden, { action: 'check-in' }))
      .toBe("You can only check in on a profile you're linked to.")
    expect(sharedDeviceErrorMessage(forbidden, { action: 'check-out' }))
      .toBe('Only the person who checked it in, or a parent, can check it out.')
  })

  it.each([
    ['Paused', "Kids is paused, so it can't check in right now."],
    ['Schedule', "Kids is outside its allowed time, so it can't check in right now."],
    ['TimeLimit', "Kids is out of time for today, so it can't check in."],
  ])('profile_blocked with reason %s names the profile', (reason, message) => {
    expect(
      sharedDeviceErrorMessage(conflict({ error: 'profile_blocked', reason }), {
        action: 'check-in',
        profileName: 'Kids',
      }),
    ).toBe(message)
  })

  it('profile_blocked without a known profile name still reads', () => {
    expect(
      sharedDeviceErrorMessage(conflict({ error: 'profile_blocked', reason: 'Paused' }), { action: 'check-in' }),
    ).toBe("This profile is paused, so it can't check in right now.")
  })

  it('not_shared / not_held / device_shared', () => {
    expect(sharedDeviceErrorMessage(conflict({ error: 'not_shared' }), { action: 'check-in' }))
      .toBe("This device isn't shared any more.")
    expect(sharedDeviceErrorMessage(conflict({ error: 'not_held' }), { action: 'check-out' }))
      .toBe('This device is already checked out.')
    expect(sharedDeviceErrorMessage(conflict({ error: 'device_shared' }), { action: 'check-in' }))
      .toBe("This device is shared, so it can't be assigned to a profile. Check it in instead.")
  })

  it('an unrecognised failure falls back to a retry message', () => {
    expect(sharedDeviceErrorMessage(new HttpError(500, 'boom'), { action: 'check-in' }))
      .toBe("Couldn't check in. Try again.")
    expect(sharedDeviceErrorMessage(new Error('offline'), { action: 'check-out' }))
      .toBe("Couldn't check out. Try again.")
  })
})
