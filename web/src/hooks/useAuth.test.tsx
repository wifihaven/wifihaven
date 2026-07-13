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
import { AuthProvider, useAuth } from './useAuth'

function wrapper({ children }: { children: React.ReactNode }) {
  return <AuthProvider>{children}</AuthProvider>
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
    expect(result.current.isAdult).toBe(true)
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

describe('useAuth — derived role flags', () => {
  it('adult is isAdult but not isAdmin', () => {
    localStorage.setItem('role', 'adult')
    const { result } = renderHook(() => useAuth(), { wrapper })
    expect(result.current.isAdmin).toBe(false)
    expect(result.current.isAdult).toBe(true)
    expect(result.current.isChild).toBe(false)
  })

  it('child is isChild only', () => {
    localStorage.setItem('role', 'child')
    const { result } = renderHook(() => useAuth(), { wrapper })
    expect(result.current.isAdmin).toBe(false)
    expect(result.current.isAdult).toBe(false)
    expect(result.current.isChild).toBe(true)
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

describe('useAuth — outside provider', () => {
  it('throws when used outside AuthProvider', () => {
    const orig = console.error
    console.error = () => {}
    expect(() => renderHook(() => useAuth())).toThrow(/AuthProvider/)
    console.error = orig
  })
})
