// #2492 — first-login forced password change must not redirect-loop.
//
// The API 403s EVERY authenticated route except POST /auth/change-password while
// must_change_password is set (Routes.scala `requireAuth`). /account renders inside the
// authenticated Layout, which itself calls /api/me (and /api/alerts via AlertsNotifier) —
// so a blanket `window.location.href = '/account'` on that 403 reloads the page the user
// is already on, which re-mounts the layout, which 403s again: an infinite full-page
// reload loop. That is the observed "page flashes and the password can never be changed".
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { api, isForbiddenError, isCurrentPasswordIncorrect } from './client'
import { readMustChangePassword, setMustChangePassword } from './mustChangePassword'

function forbiddenPasswordChange(): Response {
  return {
    ok: false,
    status: 403,
    headers: new Headers(),
    text: () => Promise.resolve('{"error":"password_change_required"}'),
    json: () => Promise.resolve({ error: 'password_change_required' }),
  } as unknown as Response
}

let originalLocation: Location

function stubLocation(pathname: string) {
  Object.defineProperty(window, 'location', {
    value: { href: '', pathname },
    writable: true,
  })
}

beforeEach(() => {
  localStorage.clear()
  localStorage.setItem('token', 'tok')
  originalLocation = window.location
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue(forbiddenPasswordChange()))
})

afterEach(() => {
  Object.defineProperty(window, 'location', { value: originalLocation, writable: true })
  vi.restoreAllMocks()
  localStorage.clear()
})

describe('password_change_required 403 (#2492)', () => {
  it('does NOT reload the page when the user is already on /account', async () => {
    stubLocation('/account')

    await expect(api.auth.me()).rejects.toThrow()

    // A hard navigation to the page we are already on is a full reload — the loop.
    expect(window.location.href).toBe('')
  })

  it('redirects to /account once when the user is somewhere else', async () => {
    stubLocation('/dashboard')

    await expect(api.auth.me()).rejects.toThrow()

    expect(window.location.href).toBe('/account')
  })

  // The browser treats /account and /account/ as the same page, so an exact compare would
  // re-enter the hard navigation and reinstate the loop on a trailing-slash URL.
  it('treats a trailing-slash /account/ as already being on the page', async () => {
    stubLocation('/account/')

    await expect(api.auth.me()).rejects.toThrow()

    expect(window.location.href).toBe('')
  })

  it('is a typed forbidden error so React Query never hot-retries it (#2069)', async () => {
    stubLocation('/account')

    const err = await api.auth.me().catch((e: unknown) => e)

    expect(isForbiddenError(err)).toBe(true)
  })

  it('persists the must-change flag so a page reload does not forget the forced-change state', async () => {
    stubLocation('/dashboard')

    await expect(api.auth.me()).rejects.toThrow()

    expect(readMustChangePassword()).toBe(true)
  })

})

// #2492: the change-password route answers 401 for BOTH a wrong current password and a dead
// token, so the two are told apart by the response body — branching on the route alone would
// report an expired session as a wrong password and leave the user re-typing forever.
describe('401 handling on the change-password route (#2492)', () => {
  function stub401(body: string) {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      headers: new Headers(),
      text: () => Promise.resolve(body),
    } as unknown as Response))
  }

  it('a wrong current password keeps the session so the page can show the error', async () => {
    stubLocation('/account')
    setMustChangePassword(true)
    stub401('Current password incorrect')

    const err = await api.auth.changePassword('wrong', 'NewPassword123').catch((e: unknown) => e)

    expect(isCurrentPasswordIncorrect(err)).toBe(true)
    expect(window.location.href).toBe('')
    expect(localStorage.getItem('token')).toBe('tok')
    expect(readMustChangePassword()).toBe(true)
  })

  it('an expired token on the SAME route still signs the user out', async () => {
    stubLocation('/account')
    setMustChangePassword(true)
    stub401('Token expired')

    const err = await api.auth.changePassword('right', 'NewPassword123').catch((e: unknown) => e)

    expect(isCurrentPasswordIncorrect(err)).toBe(false)
    expect(window.location.href).toBe('/login')
    expect(localStorage.getItem('token')).toBeNull()
    expect(readMustChangePassword()).toBe(false)
  })

  // The session is gone, so the forced-change state goes with it — otherwise the next
  // AuthProvider mount comes up with the flag set and no session behind it.
  it('a 401 on any other route clears the flag along with the token', async () => {
    stubLocation('/account')
    setMustChangePassword(true)
    stub401('Unauthorised')

    await expect(api.auth.me()).rejects.toThrow()

    expect(window.location.href).toBe('/login')
    expect(readMustChangePassword()).toBe(false)
    expect(localStorage.getItem('token')).toBeNull()
  })
})
