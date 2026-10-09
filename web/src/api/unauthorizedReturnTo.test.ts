// #2850 review: a 401 (an expired token, say) sends the browser to /login with a full-page redirect,
// which loses router state. The page the visitor was on rides `?next=` so login can return there:
// the block page's Check in link (/dashboard?checkin=<mac>) is the case that needs it.
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { api } from './client'

let originalLocation: Location

function stubLocation(pathname: string, search = '') {
  Object.defineProperty(window, 'location', {
    value: { href: '', pathname, search },
    writable: true,
  })
}

beforeEach(() => {
  localStorage.clear()
  localStorage.setItem('token', 'tok')
  originalLocation = window.location
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({
    ok: false,
    status: 401,
    headers: new Headers(),
    text: () => Promise.resolve('Token expired'),
  } as unknown as Response))
})

afterEach(() => {
  Object.defineProperty(window, 'location', { value: originalLocation, writable: true })
  vi.unstubAllGlobals()
})

describe('401 redirect carries the return path (#2850)', () => {
  it('sends the visitor to /login?next=<path and query>', async () => {
    stubLocation('/dashboard', '?checkin=aa%3Abb')
    await expect(api.auth.me()).rejects.toThrow()
    expect(window.location.href).toBe(`/login?next=${encodeURIComponent('/dashboard?checkin=aa%3Abb')}`)
  })

  it('does not point login back at itself', async () => {
    stubLocation('/login')
    await expect(api.auth.me()).rejects.toThrow()
    expect(window.location.href).toBe('/login')
  })
})
