// #2603 (SECURITY): direct coverage of the scope derivation. `queryCacheIdentity.test.tsx`
// exercises only the happy path (a well-formed token → `hh:<n>`) via `qk`. The branch that
// actually matters for the invariant is the FALLBACK: two sessions whose tokens we cannot
// decode must still land on DIFFERENT scopes. If that collapsed onto a shared `anon`, the
// defence-in-depth layer would be reopening the very collision it exists to prevent.
import { describe, it, expect, beforeEach } from 'vitest'
import { currentQueryScope, householdScopeOf } from './queryScope'

/** A JWT-shaped token; `payload` is spliced in verbatim as the middle segment. */
function jwt(payload: unknown): string {
  const seg = (o: unknown) =>
    btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  return `${seg({ alg: 'HS256', typ: 'JWT' })}.${seg(payload)}.signature`
}

describe('householdScopeOf', () => {
  it('reads the household from a well-formed token', () => {
    // The shape AuthService mints: jwt-scala merges JwtContent (role/tv/hh) into the
    // payload alongside the registered claims (api/src/auth/AuthService.scala).
    expect(householdScopeOf(jwt({ sub: 'alice', role: 'admin', tv: 0, hh: 1 }))).toBe('hh:1')
    expect(householdScopeOf(jwt({ sub: 'bob', role: 'admin', tv: 0, hh: 2 }))).toBe('hh:2')
  })

  it('decodes base64url padding and the -/_ alphabet', () => {
    // `hh` far enough into a payload that the base64 contains both `-`/`_` substitutions
    // and needs padding stripped; a naive atob() would throw and silently fall back.
    const token = jwt({ sub: 'a~b?c>d', role: 'admin', tv: 0, hh: 42, note: 'ÿÿ>>>???' })
    expect(householdScopeOf(token)).toBe('hh:42')
  })

  it('is `anon` only when there is no token at all', () => {
    expect(householdScopeOf(null)).toBe('anon')
    expect(householdScopeOf(undefined)).toBe('anon')
    expect(householdScopeOf('')).toBe('anon')
  })

  // The security-relevant branch. Every one of these must be session-DISTINCT, never a
  // shared bucket — that is the whole point of the fallback.
  it('gives two undecodable tokens two DIFFERENT scopes, never a shared bucket', () => {
    const cases: [string, string][] = [
      ['not-a-jwt-at-all', 'also-not-a-jwt'],                       // no segments
      ['header.@@@not-base64@@@.sig', 'header.###nope###.sig'],     // payload won't decode
      [jwt({ sub: 'alice' }), jwt({ sub: 'bob' })],                 // valid JSON, no `hh`
      [jwt({ hh: null }), jwt({ hh: { nested: 1 } })],              // `hh` of the wrong type
    ]
    for (const [a, b] of cases) {
      const [scopeA, scopeB] = [householdScopeOf(a), householdScopeOf(b)]
      expect(scopeA).toMatch(/^tok:/)
      expect(scopeB).toMatch(/^tok:/)
      expect(scopeA).not.toBe('anon')
      expect(scopeA).not.toBe(scopeB)
    }
  })

  it('is stable for one token — the scope cannot churn under a live session', () => {
    const token = jwt({ sub: 'alice', role: 'admin', tv: 0, hh: 7 })
    expect(householdScopeOf(token)).toBe(householdScopeOf(token))
    const malformed = 'header.@@@.sig'
    expect(householdScopeOf(malformed)).toBe(householdScopeOf(malformed))
  })
})

describe('currentQueryScope', () => {
  beforeEach(() => localStorage.clear())

  it('tracks localStorage, which is the one source of truth for the session', () => {
    // Memoised on the token string with no setter to call, so there is no second piece of
    // state a future login path could forget to update.
    expect(currentQueryScope()).toBe('anon')
    localStorage.setItem('token', jwt({ sub: 'alice', hh: 1 }))
    expect(currentQueryScope()).toBe('hh:1')
    localStorage.setItem('token', jwt({ sub: 'bob', hh: 2 }))
    expect(currentQueryScope()).toBe('hh:2')
    localStorage.removeItem('token')
    expect(currentQueryScope()).toBe('anon')
  })
})
