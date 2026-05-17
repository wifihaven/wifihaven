import React, { createContext, useContext, useState, useCallback } from 'react'
import { api } from '@/api/client'
import type { UserRole } from '@/types/api'

interface AuthState {
  token: string | null
  username: string | null
  role: UserRole | null
  // #586: mirrors the server's must_change_password flag. True immediately
  // after login when the server sends mustChangePassword:true. Cleared
  // (set to false) by the web after a successful password change.
  mustChangePassword: boolean
}

interface AuthContextValue extends AuthState {
  // Returns { mustChangePassword } so callers can redirect before the React
  // state update is applied (#586).
  login: (username: string, password: string) => Promise<{ mustChangePassword: boolean }>
  logout: () => void
  clearMustChangePassword: () => void
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
    // mustChangePassword is not persisted across page reloads: after a reload
    // the API will enforce the flag via 403 on the first authenticated call,
    // which the client handles via the normal 403 handler in client.ts.
    mustChangePassword:  false,
  }))

  const login = useCallback(async (username: string, password: string) => {
    const resp = await api.auth.login(username, password)
    localStorage.setItem('token', resp.token)
    localStorage.setItem('username', resp.username)
    localStorage.setItem('role', resp.role)
    const mcp = resp.mustChangePassword ?? false
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
    setState({ token: null, username: null, role: null, mustChangePassword: false })
  }, [])

  // Called by AccountPage after a successful password change to allow navigation.
  const clearMustChangePassword = useCallback(() => {
    setState(prev => ({ ...prev, mustChangePassword: false }))
  }, [])

  const isAdmin = state.role === 'admin'
  const isAdult = state.role === 'admin' || state.role === 'adult'
  const isChild = state.role === 'child'

  return (
    <AuthContext.Provider value={{
      ...state,
      login,
      logout,
      clearMustChangePassword,
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
