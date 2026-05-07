import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'

// Stub heavy pages so this test only exercises routing/role gating.
vi.mock('@/pages/DashboardPage', () => ({
  DashboardPage: () => <div>Dashboard stub</div>,
  PageLoader: () => <div>loading…</div>,
}))
vi.mock('@/pages/DevicesPage',  () => ({ DevicesPage:  () => <div>Devices stub</div> }))
vi.mock('@/pages/ProfilesPage', () => ({ ProfilesPage: () => <div>Profiles stub</div> }))
vi.mock('@/pages/TimePage',     () => ({ TimePage:     () => <div>Time stub</div> }))
vi.mock('@/pages/LogsPage',     () => ({ LogsPage:     () => <div>Logs stub</div> }))
vi.mock('@/pages/AccountPage',  () => ({ AccountPage:  () => <div>Account stub</div> }))
vi.mock('@/pages/LoginPage',    () => ({ LoginPage:    () => <div>Login stub</div> }))
vi.mock('@/pages/UsersPage',    () => ({ UsersPage:    () => <div data-testid="users-page">Users page</div> }))

import { AuthProvider } from '@/hooks/useAuth'
import { Routes, Route, Navigate } from 'react-router-dom'
import { useAuth } from '@/hooks/useAuth'
import { UsersPage } from '@/pages/UsersPage'

// We re-implement the small routing fragment here mirroring App.tsx's gates,
// because the real App.tsx wraps in BrowserRouter (no MemoryRouter override).
function RequireAdmin({ children }: { children: React.ReactNode }) {
  const { isAdmin } = useAuth()
  return isAdmin ? <>{children}</> : <Navigate to="/dashboard" replace />
}

function TestApp() {
  return (
    <Routes>
      <Route path="/dashboard" element={<div>Dashboard stub</div>} />
      <Route path="/users" element={<RequireAdmin><UsersPage /></RequireAdmin>} />
    </Routes>
  )
}

beforeEach(() => {
  localStorage.clear()
})

describe('admin route gating', () => {
  it('renders UsersPage when role=admin', async () => {
    localStorage.setItem('token', 't')
    localStorage.setItem('username', 'alice')
    localStorage.setItem('role', 'admin')
    render(
      <AuthProvider>
        <MemoryRouter initialEntries={['/users']}>
          <TestApp />
        </MemoryRouter>
      </AuthProvider>
    )
    expect(await screen.findByTestId('users-page')).toBeInTheDocument()
  })

  it('redirects non-admin away from /users', async () => {
    localStorage.setItem('token', 't')
    localStorage.setItem('username', 'bob')
    localStorage.setItem('role', 'child')
    render(
      <AuthProvider>
        <MemoryRouter initialEntries={['/users']}>
          <TestApp />
        </MemoryRouter>
      </AuthProvider>
    )
    await waitFor(() => {
      expect(screen.queryByTestId('users-page')).not.toBeInTheDocument()
    })
    expect(screen.getByText('Dashboard stub')).toBeInTheDocument()
  })
})
