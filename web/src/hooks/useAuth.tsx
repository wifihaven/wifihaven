import React, { createContext, useContext, useState, useCallback } from 'react'
import { api } from '@/api/client'
import { setHouseholdCookie } from '@/api/householdCookie'
import { readMustChangePassword, setMustChangePassword } from '@/api/mustChangePassword'
import type { UserRole } from '@/types/api'

interface AuthState {
  token: string | null
  username: string | null
  role: UserRole | null
  // #586: mirrors the server's must_change_password flag. True immediately
  // after login when the server sends mustChangePassword:true. Cleared
  // (set to false) by the web after a successful password change.
  // #2492: persisted (see @/api/mustChangePassword) so it survives a page load —
  // the transport also sets it when the API 403s password_change_required.
  mustChangePassword: boolean
}

interface AuthContextValue extends AuthState {
  // Returns { mustChangePassword } so callers can redirect before the React
  // state update is applied (#586).
  // #2164: `identifier` is the single login string (email / slug/username / bare). LoginPage has
  // already composed a bare username into `slug/username` via the cookie before calling this.
  login: (
    identifier: string,
    password: string,
  ) => Promise<{ mustChangePassword: boolean }>
  logout: () => void
  isAdmin: boolean
  isAdult: boolean      // admin or adult — can edit linked profiles
  isChild: boolean
  isAuthenticated: boolean
}

const AuthContext = createContext<AuthContextValue | null>(null)

function readRole(): UserRole | null {
  const r = localStorage.getItem('role')
  if (r === 'admin' || r === 'adult' || r === 'child') return r
  return null
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [state, setState] = useState<AuthState>(() => ({
    token:               localStorage.getItem('token'),
    username:            localStorage.getItem('username'),
    role:                readRole(),
    // #2492: restored from storage so a reload (or the transport's redirect to /account)
    // doesn't forget that a forced change is pending. The API still enforces it via 403 on
    // the first authenticated call; that handler writes the same flag.
    mustChangePassword:  readMustChangePassword(),
  }))

  const login = useCallback(async (identifier: string, password: string) => {
    const resp = await api.auth.login(identifier, password)
    // #2164: remember the household slug the SERVER resolved (a UX hint only) so a later bare-username
    // login on this browser can be client-composed into `slug/username` (design §4 form 3). The
    // server never reads this cookie. `default`/self-hosted resolves to slug "default"; a household
    // with no slug yet returns undefined, which clears the cookie.
    setHouseholdCookie(resp.householdSlug)
    localStorage.setItem('token', resp.token)
    localStorage.setItem('username', resp.username)
    localStorage.setItem('role', resp.role)
    const mcp = resp.mustChangePassword ?? false
    // #2492: the server's answer is authoritative — a normal login also CLEARS any stale
    // persisted flag left behind by a previous session on this browser.
    setMustChangePassword(mcp)
    setState({
      token:              resp.token,
      username:           resp.username,
      role:               resp.role,
      mustChangePassword: mcp,
    })
    // Return the flag so the caller (LoginPage) can redirect synchronously
    // before the React state update propagates (#586).
    return { mustChangePassword: mcp }
  }, [])

  const logout = useCallback(() => {
    localStorage.removeItem('token')
    localStorage.removeItem('username')
    localStorage.removeItem('role')
    setMustChangePassword(false)
    setState({ token: null, username: null, role: null, mustChangePassword: false })
  }, [])

  const isAdmin = state.role === 'admin'
  const isAdult = state.role === 'admin' || state.role === 'adult'
  const isChild = state.role === 'child'

  return (
    <AuthContext.Provider value={{
      ...state,
      login,
      logout,
      isAdmin,
      isAdult,
      isChild,
      isAuthenticated: !!state.token,
    }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth must be used within AuthProvider')
  return ctx
}

// Non-throwing variant for infrastructure hooks that may run outside a provider
// (e.g. `useDataScope`, rendered by page-level unit tests that mount a page bare).
// Returns null when there is no AuthProvider above; callers pick a safe default.
export function useAuthOptional(): AuthContextValue | null {
  return useContext(AuthContext)
}
