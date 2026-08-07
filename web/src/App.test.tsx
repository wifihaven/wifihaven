import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'

// Stub heavy pages so this test only exercises routing/role gating.
vi.mock('@/pages/DashboardPage', () => ({
  DashboardPage: () => <div>Dashboard stub</div>,
  PageLoader: () => <div>loading…</div>,
}))
vi.mock('@/pages/DevicesPage',  () => ({ DevicesPage:  () => <div>Devices stub</div> }))
vi.mock('@/pages/ProfilesPage', () => ({ ProfilesPage: () => <div>Profiles stub</div> }))
vi.mock('@/pages/LogsPage',     () => ({ LogsPage:     () => <div>Logs stub</div> }))
vi.mock('@/pages/AccountPage',  () => ({ AccountPage:  () => <div>Account stub</div> }))
vi.mock('@/pages/LoginPage',    () => ({ LoginPage:    () => <div>Login stub</div> }))
vi.mock('@/pages/UsersPage',    () => ({ UsersPage:    () => <div data-testid="users-page">Users page</div> }))

import { AuthProvider } from '@/hooks/useAuth'
import { withQuery } from '@/test/queryWrapper'
// #2522: the gates are imported from App.tsx, not re-implemented here. A local copy would
// keep passing after the real guard drifted — which is exactly the failure mode this issue
// is about (the SPA claiming a boundary the API no longer enforces).
import { RequireAdmin, RequireWriter } from './App'

const GUARDED = <div data-testid="guarded">Guarded page</div>

function renderAt(role: 'admin' | 'adult' | 'child' | null, gate: 'admin' | 'writer') {
  localStorage.setItem('token', 't')
  localStorage.setItem('username', 'someone')
  if (role) localStorage.setItem('role', role)
  const Gate = gate === 'admin' ? RequireAdmin : RequireWriter
  // #2603: AuthProvider clears the query cache on identity change, so it needs a
  // QueryClientProvider above it — as main.tsx already gives it in the real app.
  return render(
    withQuery(
      <AuthProvider>
        <MemoryRouter initialEntries={['/guarded']}>
          <Routes>
            <Route path="/dashboard" element={<div>Dashboard stub</div>} />
            <Route path="/guarded" element={<Gate>{GUARDED}</Gate>} />
          </Routes>
        </MemoryRouter>
      </AuthProvider>,
    ),
  )
}

async function expectAllowed() {
  expect(await screen.findByTestId('guarded')).toBeInTheDocument()
}

async function expectRedirected() {
  await waitFor(() => {
    expect(screen.queryByTestId('guarded')).not.toBeInTheDocument()
  })
  expect(screen.getByText('Dashboard stub')).toBeInTheDocument()
}

beforeEach(() => {
  localStorage.clear()
})

// #2522 — the SPA's two route gates must mirror `requireAdmin` / `requireWriter` in
// api/src/routes/Routes.scala. `RequireAdmin` protects the ACCOUNT (users, billing, routers,
// the #2382 kill-switch); `RequireWriter` protects POLICY EDITING (apps, blocklists, schedules,
// household settings), which post-#2534 an adult may do.
describe('RequireAdmin — the account gate', () => {
  it('admits an admin', async () => {
    renderAt('admin', 'admin')
    await expectAllowed()
  })

  // The load-bearing negative: an over-broad capability swap would let an adult onto
  // /users, /routers, /billing — every one of which the API still 403s.
  it('redirects an adult away — account management is not parenting', async () => {
    renderAt('adult', 'admin')
    await expectRedirected()
  })

  it('redirects a child away', async () => {
    renderAt('child', 'admin')
    await expectRedirected()
  })
})

describe('RequireWriter — the policy-editing gate', () => {
  it('admits an admin', async () => {
    renderAt('admin', 'writer')
    await expectAllowed()
  })

  it('admits an adult', async () => {
    renderAt('adult', 'writer')
    await expectAllowed()
  })

  it('still refuses a child, exactly as the admin gate did', async () => {
    renderAt('child', 'writer')
    await expectRedirected()
  })

  it('refuses a session with no role at all', async () => {
    renderAt(null, 'writer')
    await expectRedirected()
  })
})
