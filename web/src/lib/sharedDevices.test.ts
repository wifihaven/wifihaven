// #2850: the readable message for each shared-device rejection code the API returns (#2848).
import { describe, it, expect } from 'vitest'
import { HttpError } from '@/api/httpError'
import { possessive, sharedDeviceErrorMessage } from './sharedDevices'

const conflict = (body: object) => new HttpError(409, JSON.stringify(body))

describe('sharedDeviceErrorMessage (#2850)', () => {
  it('held: someone else is in a session on it', () => {
    expect(sharedDeviceErrorMessage(conflict({ error: 'held' }), { action: 'check-in' }))
      .toBe('Someone else is using this device right now. You can start yours when their session ends.')
  })

  it('not_linked on start vs end', () => {
    // The 403 reaches the SPA as ForbiddenError, which carries `status` and the body as message.
    const forbidden = Object.assign(new Error('{"error":"not_linked"}'), { status: 403 })
    expect(sharedDeviceErrorMessage(forbidden, { action: 'check-in' }))
      .toBe("You can only start a session on your own profile.")
    expect(sharedDeviceErrorMessage(forbidden, { action: 'check-out' }))
      .toBe("Only someone on this session's profile, or a parent, can end it.")
  })

  it.each([
    ['Paused', "Kids is paused, so a session can't start right now."],
    ['Schedule', "Kids is outside its allowed time, so a session can't start right now."],
    ['TimeLimit', "Kids is out of time for today, so a session can't start."],
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
    ).toBe("This profile is paused, so a session can't start right now.")
  })

  it('not_shared / not_held / device_shared', () => {
    expect(sharedDeviceErrorMessage(conflict({ error: 'not_shared' }), { action: 'check-in' }))
      .toBe("This device isn't shared any more.")
    expect(sharedDeviceErrorMessage(conflict({ error: 'not_held' }), { action: 'check-out' }))
      .toBe('This session has already ended.')
    expect(sharedDeviceErrorMessage(conflict({ error: 'device_shared' }), { action: 'check-in' }))
      .toBe("This device is shared, so it can't be assigned to a profile. Start a session on it instead.")
  })

  it('an unrecognised failure falls back to a retry message', () => {
    expect(sharedDeviceErrorMessage(new HttpError(500, 'boom'), { action: 'check-in' }))
      .toBe("Couldn't start the session. Try again.")
    expect(sharedDeviceErrorMessage(new Error('offline'), { action: 'check-out' }))
      .toBe("Couldn't end the session. Try again.")
  })
})

// #2906: "In use: Teens' session", "End Alex's session".
describe('possessive (#2906)', () => {
  it.each([
    ['Alex', "Alex's"],
    ['Teens', "Teens'"],
    ['KIDS', "KIDS'"],
  ])('%s -> %s', (name, out) => {
    expect(possessive(name)).toBe(out)
  })
})
