// #2492 — first-login forced password change, end to end through the REAL AuthProvider
// (AccountPage.test.tsx mocks useAuth, so it can't see the persisted-flag behaviour).
//
// Pins the two acceptance behaviours: a first-login user who lands on /account after a page
// reload still sees the forced-change UI, and a successful change clears the flag (in state
// AND in storage) and routes forward — no bounce back to /account.
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Routes, Route, useLocation } from 'react-router-dom'
import userEvent from '@testing-library/user-event'

vi.mock('@/api/client', () => ({
  api: { auth: { changePassword: vi.fn(), login: vi.fn() } },
}))

import { api } from '@/api/client'
import { readMustChangePassword, setMustChangePassword } from '@/api/mustChangePassword'
import { AuthProvider } from '@/hooks/useAuth'
import { withQuery } from '@/test/queryWrapper'
import { AccountPage } from './AccountPage'

beforeEach(() => {
  localStorage.clear()
  vi.resetAllMocks()
  ;(api.auth.changePassword as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
})

function LoginProbe() {
  const state = useLocation().state as { passwordChanged?: boolean } | null
  return <div>login page{state?.passwordChanged ? ' — password updated' : ''}</div>
}

function renderAccount() {
  // #2603: AuthProvider clears the query cache on identity change, so it needs a
  // QueryClientProvider above it — as main.tsx already gives it in the real app.
  return render(
    withQuery(
      <AuthProvider>
        <MemoryRouter initialEntries={['/account']}>
          <Routes>
            <Route path="/account" element={<AccountPage />} />
            <Route path="/login" element={<LoginProbe />} />
          </Routes>
        </MemoryRouter>
      </AuthProvider>,
    ),
  )
}

async function submitNewPassword() {
  const user = userEvent.setup()
  const inputs = document.querySelectorAll('input[type="password"]')
  await user.type(inputs[0] as HTMLInputElement, 'InitialPass2492')
  await user.type(inputs[1] as HTMLInputElement, 'BrandNewPass77')
  await user.type(inputs[2] as HTMLInputElement, 'BrandNewPass77')
  await user.click(screen.getByRole('button', { name: /Update password/i }))
}

describe('first-login password change (#2492)', () => {
  it('a reloaded first-login session still shows the forced-change banner', () => {
    localStorage.setItem('token', 't')
    localStorage.setItem('username', 'kid')
    localStorage.setItem('role', 'child')
    setMustChangePassword(true)

    renderAccount()

    expect(screen.getByText(/Password change required/i)).toBeInTheDocument()
  })

  // The rotation bumps token_version server-side (#2080), so the session's JWT is dead the
  // moment the change lands: routing into the app could only end in a 401 bounce. The user is
  // signed out cleanly and told to sign in again — and the forced-change gate is released.
  it('a successful change clears the flag and routes forward, with the re-login reason', async () => {
    localStorage.setItem('token', 't')
    localStorage.setItem('username', 'kid')
    localStorage.setItem('role', 'child')
    setMustChangePassword(true)

    renderAccount()
    await submitNewPassword()

    await waitFor(() =>
      expect(screen.getByText('login page — password updated')).toBeInTheDocument(),
    )
    expect(readMustChangePassword()).toBe(false)
    expect(localStorage.getItem('token')).toBeNull()
  })
})
