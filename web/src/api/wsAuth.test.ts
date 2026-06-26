// #1969 (SPA-ws S2): the wh_ws upgrade-cookie helper (design §2.1/§4.2). Asserts the
// scoping that IS the security boundary — Path=/api/ws, SameSite=Strict, Secure, a
// short Max-Age, and the registrable-domain vs host-only split derived from the API
// base URL. A fake cookie jar is injected so the exact attribute string is observable
// (jsdom silently drops Secure/cross-Domain cookies, which would hide the contract).
import { describe, it, expect } from 'vitest'
import {
  setWsAuthCookie,
  clearWsAuthCookie,
  wsCookieDomain,
  WS_COOKIE_MAX_AGE_SECONDS,
} from './wsAuth'

function fakeJar(): { writes: string[]; get cookie(): string; set cookie(v: string) } {
  const writes: string[] = []
  return {
    writes,
    get cookie() {
      return ''
    },
    set cookie(v: string) {
      writes.push(v)
    },
  }
}

describe('setWsAuthCookie (#1969 §4.2)', () => {
  it('sets the wh_ws cookie scoped Path=/api/ws; SameSite=Strict; Secure with a short Max-Age', () => {
    const jar = fakeJar()
    setWsAuthCookie('jwt-token-abc', jar)

    expect(jar.writes).toHaveLength(1)
    const c = jar.writes[0]
    expect(c).toMatch(/^wh_ws=jwt-token-abc(;|$)/)
    expect(c).toContain('Path=/api/ws')
    expect(c).toContain('SameSite=Strict')
    expect(c).toContain('Secure')
    expect(c).toContain(`Max-Age=${WS_COOKIE_MAX_AGE_SECONDS}`)
  })

  it('is a no-op when the jwt is missing (an unauthenticated SPA never opens the socket)', () => {
    const jar = fakeJar()
    setWsAuthCookie(null, jar)
    setWsAuthCookie(undefined, jar)
    setWsAuthCookie('', jar)
    expect(jar.writes).toHaveLength(0)
  })

  it('clears the cookie with the same Path and Max-Age=0', () => {
    const jar = fakeJar()
    clearWsAuthCookie(jar)
    expect(jar.writes).toHaveLength(1)
    const c = jar.writes[0]
    expect(c).toMatch(/^wh_ws=(;|$)/)
    expect(c).toContain('Path=/api/ws')
    expect(c).toContain('Max-Age=0')
  })
})

describe('wsCookieDomain (#1969 §2.1/§8)', () => {
  it('scopes to the registrable domain for a cloud api subdomain (reaches api. from app.)', () => {
    expect(wsCookieDomain('https://api.wifihaven.net')).toBe('wifihaven.net')
    expect(wsCookieDomain('https://api-staging.wifihaven.net')).toBe('wifihaven.net')
  })

  it('is host-only (empty) for the self-hosted same-origin build', () => {
    // Empty base ⇒ same-origin self-hosted ⇒ host-only.
    expect(wsCookieDomain('')).toBe('')
    // localhost / IPs / single-label hosts are always host-only.
    expect(wsCookieDomain('http://localhost:8080')).toBe('')
    expect(wsCookieDomain('http://192.168.1.1')).toBe('')
  })
})
