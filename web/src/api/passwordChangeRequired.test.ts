// #2492 — first-login forced password change must not redirect-loop.
//
// The API 403s EVERY authenticated route except POST /auth/change-password while
// must_change_password is set (Routes.scala `requireAuth`). /account renders inside the
// authenticated Layout, which itself calls /api/me (and /api/alerts via AlertsNotifier) —
// so a blanket `window.location.href = '/account'` on that 403 reloads the page the user
// is already on, which re-mounts the layout, which 403s again: an infinite full-page
// reload loop. That is the observed "page flashes and the password can never be changed".
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { api, isForbiddenError } from './client'
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

  // A wrong CURRENT password answers 401 too (Routes.scala maps InvalidCredentials →
  // Unauthorized). Signing the user out there stranded a first-login user at the login page
  // after a typo, holding only the old password, and hid AccountPage's error message.
  it('a 401 from change-password keeps the session so the caller can show the error', async () => {
    stubLocation('/account')
    setMustChangePassword(true)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      headers: new Headers(),
      text: () => Promise.resolve('Invalid credentials'),
    } as unknown as Response))

    await expect(api.auth.changePassword('wrong', 'NewPassword123')).rejects.toThrow(/401/)

    expect(window.location.href).toBe('')
    expect(localStorage.getItem('token')).toBe('tok')
    expect(readMustChangePassword()).toBe(true)
  })

  // The session is gone, so the forced-change state goes with it — otherwise the next
  // AuthProvider mount comes up with the flag set and no session behind it.
  it('a 401 clears the persisted flag along with the token', async () => {
    stubLocation('/account')
    setMustChangePassword(true)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
      ok: false,
      status: 401,
      headers: new Headers(),
      text: () => Promise.resolve('Unauthorised'),
    } as unknown as Response))

    await expect(api.auth.me()).rejects.toThrow()

    expect(readMustChangePassword()).toBe(false)
    expect(localStorage.getItem('token')).toBeNull()
  })
})
