// #2850 (epic #2841, design docs/design/shared-devices.md §9/§10): SPA helpers for shared devices.
//
// The API refuses a check-in / check-out with a JSON body `{"error": <code>}` (and `reason` on
// `profile_blocked`), see `SharedDeviceRoutes.scala`. `req` surfaces that body as the thrown
// error's `message` (an `HttpError` for the 409s, a `ForbiddenError` for the 403), so the code is
// read back from the message here rather than from the error's class.
import type { SharedDeviceErrorCode } from '@/types/api'

export type SharedDeviceAction = 'check-in' | 'check-out'

// Keyed by the `SharedDeviceErrorCode` union, so adding a code there fails to compile here until it
// is listed.
const KNOWN: Record<SharedDeviceErrorCode, true> = {
  held: true,
  not_linked: true,
  profile_blocked: true,
  not_shared: true,
  not_held: true,
  device_shared: true,
}

interface ParsedRejection {
  code: SharedDeviceErrorCode
  reason?: string
}

function parseRejection(e: unknown): ParsedRejection | null {
  if (!(e instanceof Error)) return null
  try {
    const body = JSON.parse(e.message) as { error?: unknown; reason?: unknown }
    const code = body?.error
    if (typeof code !== 'string' || !Object.prototype.hasOwnProperty.call(KNOWN, code)) return null
    return {
      code: code as SharedDeviceErrorCode,
      reason: typeof body.reason === 'string' ? body.reason : undefined,
    }
  } catch {
    return null
  }
}

/** The message a person sees when a shared-device action is refused or fails. */
export function sharedDeviceErrorMessage(
  e: unknown,
  ctx: { action: SharedDeviceAction; profileName?: string },
): string {
  const parsed = parseRejection(e)
  const profile = ctx.profileName ?? 'This profile'
  switch (parsed?.code) {
    case 'held':
      return 'Someone else has this device checked in. They need to check it out first.'
    case 'not_linked':
      return ctx.action === 'check-in'
        ? "You can only check in on a profile you're linked to."
        : 'Only the person who checked it in, or a parent, can check it out.'
    case 'profile_blocked':
      // Design §15 Q3: check-in is refused while the profile is Paused / Schedule / TimeLimit.
      switch (parsed.reason) {
        case 'Paused':
          return `${profile} is paused, so it can't check in right now.`
        case 'Schedule':
          return `${profile} is outside its allowed time, so it can't check in right now.`
        case 'TimeLimit':
          return `${profile} is out of time for today, so it can't check in.`
        default:
          return `${profile} is blocked right now, so it can't check in.`
      }
    case 'not_shared':
      return "This device isn't shared any more."
    case 'not_held':
      return 'This device is already checked out.'
    case 'device_shared':
      return "This device is shared, so it can't be assigned to a profile. Check it in instead."
    default:
      return ctx.action === 'check-in' ? "Couldn't check in. Try again." : "Couldn't check out. Try again."
  }
}

/** "3:42 PM" for a check-in that started today, "Oct 8, 3:42 PM" otherwise. */
export function formatSince(iso: string, now: Date = new Date()): string {
  const d = new Date(iso)
  const time = d.toLocaleTimeString([], { hour: 'numeric', minute: '2-digit' })
  if (d.toDateString() === now.toDateString()) return time
  return `${d.toLocaleDateString([], { month: 'short', day: 'numeric' })}, ${time}`
}
