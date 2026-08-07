import { describe, it, expect, beforeEach, vi } from 'vitest'
import { act, renderHook } from '@testing-library/react'

vi.mock('@/api/client', () => ({
  api: {
    auth: {
      login: vi.fn(),
    },
  },
}))

import { api } from '@/api/client'
import { readMustChangePassword, setMustChangePassword } from '@/api/mustChangePassword'
import { withQuery } from '@/test/queryWrapper'
import { AuthProvider, useAuth } from './useAuth'

// #2603: AuthProvider clears the query cache on every identity change, so it now requires
// a QueryClientProvider above it — as it has in the real app since main.tsx was written.
function wrapper({ children }: { children: React.ReactNode }) {
  return withQuery(<AuthProvider>{children}</AuthProvider>)
}

beforeEach(() => {
  localStorage.clear()
  vi.resetAllMocks()
})

describe('useAuth — initial state', () => {
  it('reads token, username, and role from localStorage', () => {
    localStorage.setItem('token', 't')
    localStorage.setItem('username', 'alice')
    localStorage.setItem('role', 'admin')
    const { result } = renderHook(() => useAuth(), { wrapper })
    expect(result.current.token).toBe('t')
    expect(result.current.username).toBe('alice')
    expect(result.current.role).toBe('admin')
    expect(result.current.isAuthenticated).toBe(true)
    expect(result.current.isAdmin).toBe(true)
    expect(result.current.isWriter).toBe(true)
    expect(result.current.isChild).toBe(false)
  })

  it('treats an invalid role value as null', () => {
    localStorage.setItem('role', 'wizard')
    const { result } = renderHook(() => useAuth(), { wrapper })
    expect(result.current.role).toBeNull()
  })

  it('isAuthenticated is false when no token', () => {
    const { result } = renderHook(() => useAuth(), { wrapper })
    expect(result.current.isAuthenticated).toBe(false)
  })
})

// #2522 — the two capabilities the SPA must keep apart. `isAdmin` answers "may this session
// administer the ACCOUNT?" (who exists, who pays, what hardware is enrolled, the kill-switch);
// `isWriter` answers "may this session EDIT POLICY?" (profiles, schedules, blocklists, apps,
// household settings). They mirror `requireAdmin` / `requireWriter` in api/src/routes/Routes.scala
// one-for-one, so an affordance gated on the wrong one either 403s or leaks.
describe('useAuth — capability flags (#2522)', () => {
  it('admin holds BOTH capabilities', () => {
    localStorage.setItem('role', 'admin')
    const { result } = renderHook(() => useAuth(), { wrapper })
    expect(result.current.isAdmin).toBe(true)
    expect(result.current.isWriter).toBe(true)
    expect(result.current.isChild).toBe(false)
  })

  it('adult may edit policy but may NOT administer the account', () => {
    localStorage.setItem('role', 'adult')
    const { result } = renderHook(() => useAuth(), { wrapper })
    expect(result.current.isAdmin).toBe(false)
    expect(result.current.isWriter).toBe(true)
    expect(result.current.isChild).toBe(false)
  })

  it('child holds neither capability', () => {
    localStorage.setItem('role', 'child')
    const { result } = renderHook(() => useAuth(), { wrapper })
    expect(result.current.isAdmin).toBe(false)
    expect(result.current.isWriter).toBe(false)
    expect(result.current.isChild).toBe(true)
  })

  it('an unauthenticated session holds neither capability', () => {
    const { result } = renderHook(() => useAuth(), { wrapper })
    expect(result.current.isAdmin).toBe(false)
    expect(result.current.isWriter).toBe(false)
  })
})

describe('useAuth — login', () => {
  it('calls api.auth.login, persists to localStorage, and updates state', async () => {
    (api.auth.login as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      token: 'tok', username: 'alice', role: 'admin',
    })
    const { result } = renderHook(() => useAuth(), { wrapper })
    await act(async () => {
      await result.current.login('alice', 'pw')
    })
    // #2164: login takes a single already-composed identifier (no household arg).
    expect(api.auth.login).toHaveBeenCalledWith('alice', 'pw')
    expect(localStorage.getItem('token')).toBe('tok')
    expect(localStorage.getItem('username')).toBe('alice')
    expect(localStorage.getItem('role')).toBe('admin')
    expect(result.current.isAuthenticated).toBe(true)
    expect(result.current.isAdmin).toBe(true)
  })

  it('#2164: writes the server-resolved household slug to the wh_household cookie', async () => {
    (api.auth.login as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      token: 'tok', username: 'emma', role: 'child', householdSlug: 'smith-family',
    })
    const { result } = renderHook(() => useAuth(), { wrapper })
    await act(async () => {
      await result.current.login('smith-family/emma', 'pw')
    })
    expect(document.cookie).toContain('wh_household=smith-family')
    // Cleanup so the cookie doesn't leak into other tests.
    document.cookie = 'wh_household=; Path=/; Max-Age=0'
  })
})

describe('useAuth — logout', () => {
  it('clears localStorage and resets state', () => {
    localStorage.setItem('token', 't')
    localStorage.setItem('username', 'alice')
    localStorage.setItem('role', 'admin')
    const { result } = renderHook(() => useAuth(), { wrapper })
    act(() => { result.current.logout() })
    expect(localStorage.getItem('token')).toBeNull()
    expect(localStorage.getItem('username')).toBeNull()
    expect(localStorage.getItem('role')).toBeNull()
    expect(result.current.isAuthenticated).toBe(false)
    expect(result.current.token).toBeNull()
  })
})

// #2492: the forced-change state must survive a page reload. It used to live only in React
// state, so any reload (including the 403 handler's own hard navigation) forgot it: the banner
// vanished, RequirePwChanged stopped gating, and AccountPage's post-change
// `navigate('/dashboard')` never fired because it is conditional on the flag.
describe('useAuth — mustChangePassword persistence (#2492)', () => {
  it('restores the flag from storage on a fresh mount (page reload)', () => {
    localStorage.setItem('token', 't')
    localStorage.setItem('role', 'child')
    setMustChangePassword(true)
    const { result } = renderHook(() => useAuth(), { wrapper })
    expect(result.current.mustChangePassword).toBe(true)
  })

  it('login persists the server flag', async () => {
    (api.auth.login as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      token: 'tok', username: 'emma', role: 'child', mustChangePassword: true,
    })
    const { result } = renderHook(() => useAuth(), { wrapper })
    await act(async () => { await result.current.login('emma', 'pw') })
    expect(result.current.mustChangePassword).toBe(true)
    expect(readMustChangePassword()).toBe(true)
  })

  it('a normal (non-first) login clears any stale persisted flag', async () => {
    setMustChangePassword(true)
    ;(api.auth.login as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      token: 'tok', username: 'alice', role: 'admin', mustChangePassword: false,
    })
    const { result } = renderHook(() => useAuth(), { wrapper })
    await act(async () => { await result.current.login('alice', 'pw') })
    expect(result.current.mustChangePassword).toBe(false)
    expect(readMustChangePassword()).toBe(false)
  })

  it('logout clears the persisted flag', () => {
    localStorage.setItem('token', 't')
    setMustChangePassword(true)
    const { result } = renderHook(() => useAuth(), { wrapper })
    act(() => { result.current.logout() })
    expect(readMustChangePassword()).toBe(false)
  })
})

describe('useAuth — outside provider', () => {
  it('throws when used outside AuthProvider', () => {
    const orig = console.error
    console.error = () => {}
    expect(() =>
      renderHook(() => useAuth(), {
        wrapper: ({ children }: { children: React.ReactNode }) => withQuery(children),
      }),
    ).toThrow(/AuthProvider/)
    console.error = orig
  })
})
