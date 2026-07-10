// #2140 (multi-tenant P5-8): the wh_household UX-hint cookie helper (design §4). Asserts the
// pre-fill contract — a long-lived Path=/ SameSite=Lax cookie that remembers the last-used household
// slug, round-trips through get, and clears on a blank slug. A fake cookie jar (with a settable
// backing value) is injected so the exact attribute string AND the read path are both observable.
import { describe, it, expect } from 'vitest'
import {
  getHouseholdCookie,
  setHouseholdCookie,
  HOUSEHOLD_COOKIE_NAME,
  HOUSEHOLD_COOKIE_MAX_AGE_SECONDS,
} from './householdCookie'

// A jar whose `get cookie` reflects the last non-clearing write, so get/set round-trips are testable.
function fakeJar(initial = ''): { writes: string[]; get cookie(): string; set cookie(v: string) } {
  const writes: string[] = []
  let value = initial
  return {
    writes,
    get cookie() {
      return value
    },
    set cookie(v: string) {
      writes.push(v)
      // Emulate the browser: parse `name=val; attrs...`, updating the single stored cookie.
      const [pair] = v.split(';')
      const eq = pair.indexOf('=')
      const name = pair.slice(0, eq).trim()
      const val = pair.slice(eq + 1)
      if (name === HOUSEHOLD_COOKIE_NAME) value = val ? `${name}=${val}` : ''
    },
  }
}

describe('setHouseholdCookie (#2140 §4)', () => {
  it('stores the slug Path=/; SameSite=Lax with an ~never-expiring Max-Age', () => {
    const jar = fakeJar()
    setHouseholdCookie('smith-family', jar)
    const c = jar.writes[0]
    expect(c).toContain(`${HOUSEHOLD_COOKIE_NAME}=smith-family`)
    expect(c).toContain('Path=/')
    expect(c).toContain('SameSite=Lax')
    expect(c).toContain(`Max-Age=${HOUSEHOLD_COOKIE_MAX_AGE_SECONDS}`)
    // ~10 years — effectively never expires.
    expect(HOUSEHOLD_COOKIE_MAX_AGE_SECONDS).toBeGreaterThan(60 * 60 * 24 * 365 * 9)
  })

  it('is a UX hint, not Secure/HttpOnly (survives http self-hosted; readable by JS)', () => {
    const jar = fakeJar()
    setHouseholdCookie('smith-family', jar)
    const c = jar.writes[0]
    expect(c).not.toContain('Secure')
    expect(c).not.toContain('HttpOnly')
  })

  it('url-encodes the slug value', () => {
    const jar = fakeJar()
    setHouseholdCookie('a b', jar)
    expect(jar.writes[0]).toContain(`${HOUSEHOLD_COOKIE_NAME}=a%20b`)
  })

  it('clears the cookie (Max-Age=0, same Path) when the slug is blank', () => {
    const jar = fakeJar()
    setHouseholdCookie('   ', jar)
    const c = jar.writes[0]
    expect(c).toContain(`${HOUSEHOLD_COOKIE_NAME}=`)
    expect(c).toContain('Path=/')
    expect(c).toContain('Max-Age=0')
  })

  it('clears the cookie when the slug is null/undefined', () => {
    const jar = fakeJar()
    setHouseholdCookie(undefined, jar)
    expect(jar.writes[0]).toContain('Max-Age=0')
  })
})

describe('getHouseholdCookie (#2140 §4)', () => {
  it('returns null when unset', () => {
    expect(getHouseholdCookie(fakeJar())).toBeNull()
  })

  it('round-trips a slug written by setHouseholdCookie', () => {
    const jar = fakeJar()
    setHouseholdCookie('smith-family', jar)
    expect(getHouseholdCookie(jar)).toBe('smith-family')
  })

  it('decodes an encoded slug', () => {
    const jar = fakeJar(`${HOUSEHOLD_COOKIE_NAME}=a%20b`)
    expect(getHouseholdCookie(jar)).toBe('a b')
  })

  it('finds the cookie among others', () => {
    const jar = fakeJar(`other=1; ${HOUSEHOLD_COOKIE_NAME}=jones; wh_ws=xyz`)
    expect(getHouseholdCookie(jar)).toBe('jones')
  })

  it('returns null after a clear', () => {
    const jar = fakeJar()
    setHouseholdCookie('jones', jar)
    setHouseholdCookie('', jar)
    expect(getHouseholdCookie(jar)).toBeNull()
  })
})
