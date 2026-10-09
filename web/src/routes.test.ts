// #2850: the login return path. `?next=` is attacker-settable, so every branch of the in-app-path
// guard is pinned here: a regression in any one of them would turn login into an open redirect.
import { describe, it, expect } from 'vitest'
import { loginUrlReturningTo, safeReturnPath } from './routes'

describe('safeReturnPath (#2850)', () => {
  it('accepts an in-app path with its query', () => {
    expect(safeReturnPath('/dashboard?checkin=aa%3Abb')).toBe('/dashboard?checkin=aa%3Abb')
  })

  it.each([
    ['an absolute URL', 'https://evil.example/x'],
    ['a protocol-relative URL', '//evil.example/x'],
    ['a backslash host', '/\\evil.example/x'],
    ['a relative path', 'dashboard'],
    ['the login page itself', '/login'],
    ['the login page with a query', '/login?next=%2Fdashboard'],
    ['an empty string', ''],
  ])('rejects %s', (_label, from) => {
    expect(safeReturnPath(from)).toBeNull()
  })

  it('rejects a non-string', () => {
    expect(safeReturnPath(null)).toBeNull()
    expect(safeReturnPath({ pathname: '/dashboard' })).toBeNull()
  })
})

describe('loginUrlReturningTo (#2850)', () => {
  it('carries the page in ?next=', () => {
    expect(loginUrlReturningTo('/dashboard', '?checkin=aa%3Abb'))
      .toBe(`/login?next=${encodeURIComponent('/dashboard?checkin=aa%3Abb')}`)
  })

  it('sends / and the change-password page to a bare login', () => {
    expect(loginUrlReturningTo('/')).toBe('/login')
    expect(loginUrlReturningTo('/account')).toBe('/login')
  })

  it('keeps a safe ?next= when already on the login page', () => {
    expect(loginUrlReturningTo('/login', '?next=%2Fdashboard')).toBe('/login?next=%2Fdashboard')
  })

  it.each([
    ['a protocol-relative next', '?next=%2F%2Fevil.example%2Fx'],
    ['a backslash-host next', `?next=${encodeURIComponent('/\\evil.example/x')}`],
    ['an absolute next', '?next=https%3A%2F%2Fevil.example'],
  ])('drops %s on the login page', (_label, search) => {
    expect(loginUrlReturningTo('/login', search)).toBe('/login')
  })

  it.each([
    ['//evil.example/x'],
    ['/\\evil.example/x'],
  ])('never builds ?next= from a current path of %s', pathname => {
    expect(loginUrlReturningTo(pathname)).toBe('/login')
  })
})
