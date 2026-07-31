import { describe, it, expect, beforeEach, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'

vi.mock('@/api/client', () => ({
  api: { auth: { me: vi.fn() } },
}))

import { api } from '@/api/client'
import { AuthProvider } from './useAuth'
import { useDataScope } from './useDataScope'
import { withQuery } from '@/test/queryWrapper'

const meSpy = api.auth.me as unknown as ReturnType<typeof vi.fn>

function loginAs(role: 'admin' | 'adult' | 'child') {
  localStorage.setItem('token', 'tok')
  localStorage.setItem('username', role === 'child' ? 'octavius' : 'alice')
  localStorage.setItem('role', role)
}

function wrapper({ children }: { children: ReactNode }) {
  return withQuery(<AuthProvider>{children}</AuthProvider>)
}

describe('useDataScope (#2069)', () => {
  beforeEach(() => {
    localStorage.clear()
    meSpy.mockReset()
  })

  it('admin: no client scoping, and does not fetch /api/me', async () => {
    loginAs('admin')
    const { result } = renderHook(() => useDataScope(), { wrapper })
    expect(result.current.isAdmin).toBe(true)
    expect(result.current.childProfileIds).toBeNull() // null = unscoped
    expect(result.current.scopeLoading).toBe(false)
    // give any (mis)fired query a chance — /api/me must NOT be called for an admin.
    await new Promise(r => setTimeout(r, 0))
    expect(meSpy).not.toHaveBeenCalled()
  })

  it('adult: treated as unscoped (server serves adults unscoped)', async () => {
    loginAs('adult')
    const { result } = renderHook(() => useDataScope(), { wrapper })
    expect(result.current.isWriter).toBe(true)
    expect(result.current.isChild).toBe(false)
    expect(result.current.childProfileIds).toBeNull()
    await new Promise(r => setTimeout(r, 0))
    expect(meSpy).not.toHaveBeenCalled()
  })

  it('child: fetches /api/me and exposes the linked profile ids', async () => {
    loginAs('child')
    meSpy.mockResolvedValue({ username: 'octavius', role: 'child', profileIds: [7, 3] })
    const { result } = renderHook(() => useDataScope(), { wrapper })
    // before /me resolves, a child is still loading its scope (hold the request).
    expect(result.current.isChild).toBe(true)
    expect(result.current.childProfileIds).toBeUndefined()
    expect(result.current.scopeLoading).toBe(true)

    await waitFor(() => expect(result.current.childProfileIds).toEqual([7, 3]))
    expect(result.current.scopeLoading).toBe(false)
    expect(meSpy).toHaveBeenCalledTimes(1)
  })

  // #2522: the scope hook exposes the same two capabilities `useAuth` derives, under the same
  // names, so a caller never has to guess which question it is asking.
  it('#2522: a child holds neither capability', async () => {
    loginAs('child')
    meSpy.mockResolvedValue({ username: 'octavius', role: 'child', profileIds: [] })
    const { result } = renderHook(() => useDataScope(), { wrapper })
    expect(result.current.isAdmin).toBe(false)
    expect(result.current.isWriter).toBe(false)
  })

  it('child with no linked profile: resolves to an empty scope (needs-linking)', async () => {
    loginAs('child')
    meSpy.mockResolvedValue({ username: 'octavius', role: 'child', profileIds: [] })
    const { result } = renderHook(() => useDataScope(), { wrapper })
    await waitFor(() => expect(result.current.childProfileIds).toEqual([]))
    expect(result.current.scopeLoading).toBe(false)
  })
})
