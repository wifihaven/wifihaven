import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import userEvent from '@testing-library/user-event'

// #2492: mock ONLY the network surface — the real `CurrentPasswordIncorrectError` and its
// predicate come through, so this test breaks if their semantics change rather than asserting
// against a local re-implementation.
vi.mock('@/api/client', async importOriginal => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: {
    auth: {
      changePassword: vi.fn(),
    },
  },
}))

vi.mock('@/hooks/useAuth', () => ({
  useAuth: () => mockAuth,
}))

import { api, CurrentPasswordIncorrectError } from '@/api/client'
import { AccountPage } from './AccountPage'

let mockAuth: {
  username: string
  isAdmin: boolean
  mustChangePassword: boolean
  logout: () => void
} = {
  username: 'alice',
  isAdmin: true,
  mustChangePassword: false,
  logout: vi.fn(),
}

beforeEach(() => {
  vi.resetAllMocks()
  mockAuth = {
    username: 'alice',
    isAdmin: true,
    mustChangePassword: false,
    logout: vi.fn(),
  }
  ;(api.auth.changePassword as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
})

function renderPage() {
  return render(
    <MemoryRouter>
      <AccountPage />
    </MemoryRouter>,
  )
}

async function fillFields(user: ReturnType<typeof userEvent.setup>, current: string, next: string, confirm: string) {
  const inputs = document.querySelectorAll('input[type="password"]')
  await user.type(inputs[0] as HTMLInputElement, current)
  await user.type(inputs[1] as HTMLInputElement, next)
  await user.type(inputs[2] as HTMLInputElement, confirm)
}

describe('AccountPage — role display', () => {
  it('shows admin role for admin user', () => {
    renderPage()
    expect(screen.getByText('alice')).toBeInTheDocument()
    expect(screen.getByText('admin')).toBeInTheDocument()
  })

  it('shows readonly role for non-admin user', () => {
    mockAuth = { username: 'bob', isAdmin: false, mustChangePassword: false, logout: vi.fn() }
    renderPage()
    expect(screen.getByText('readonly')).toBeInTheDocument()
  })
})

describe('AccountPage — must-change-password banner', () => {
  it('shows the banner when mustChangePassword is true', () => {
    mockAuth = { username: 'alice', isAdmin: true, mustChangePassword: true, logout: vi.fn() }
    renderPage()
    expect(screen.getByText(/Password change required/i)).toBeInTheDocument()
  })

  it('does not show the banner when mustChangePassword is false', () => {
    renderPage()
    expect(screen.queryByText(/Password change required/i)).not.toBeInTheDocument()
  })

  // #2492: the rotation revokes this session's JWT server-side (#2080), so a successful change
  // signs the user out (which also clears the must-change flag) and sends them back to /login.
  it('signs the user out after a successful forced change', async () => {
    const logout = vi.fn()
    mockAuth = { username: 'alice', isAdmin: true, mustChangePassword: true, logout }
    const user = userEvent.setup()
    renderPage()
    await fillFields(user, 'oldpass12', 'newpass34', 'newpass34')
    await user.click(screen.getByRole('button', { name: /Update password/ }))
    await waitFor(() => expect(logout).toHaveBeenCalled())
  })
})

describe('AccountPage — password change', () => {
  // #2492: success no longer leaves the user on this page — the rotation revokes the session's
  // JWT (#2080), so the page signs out and hands off to /login, which carries the confirmation.
  // AccountPage.firstLogin.test.tsx pins the redirect end-to-end through the real AuthProvider.
  it('calls api.auth.changePassword and signs out on success', async () => {
    const user = userEvent.setup()
    renderPage()
    await fillFields(user, 'oldpass12', 'newpass34', 'newpass34')
    await user.click(screen.getByRole('button', { name: /Update password/ }))

    await waitFor(() => expect(api.auth.changePassword).toHaveBeenCalledWith('oldpass12', 'newpass34'))
    expect(mockAuth.logout).toHaveBeenCalled()
  })

  it('rejects when new and confirm do not match', async () => {
    const user = userEvent.setup()
    renderPage()
    await fillFields(user, 'oldpass12', 'newpass34', 'different')
    await user.click(screen.getByRole('button', { name: /Update password/ }))
    expect(await screen.findByText(/do not match/i)).toBeInTheDocument()
    expect(api.auth.changePassword).not.toHaveBeenCalled()
  })

  it('rejects when new password is too short', async () => {
    const user = userEvent.setup()
    renderPage()
    await fillFields(user, 'oldpass12', 'short', 'short')
    await user.click(screen.getByRole('button', { name: /Update password/ }))
    expect(await screen.findByText(/at least 8 characters/i)).toBeInTheDocument()
    expect(api.auth.changePassword).not.toHaveBeenCalled()
  })

  it('rejects when new password equals current', async () => {
    const user = userEvent.setup()
    renderPage()
    await fillFields(user, 'samesame12', 'samesame12', 'samesame12')
    await user.click(screen.getByRole('button', { name: /Update password/ }))
    expect(await screen.findByText(/differ from the current/i)).toBeInTheDocument()
    expect(api.auth.changePassword).not.toHaveBeenCalled()
  })

  it('maps the typed wrong-current-password error to "Current password is incorrect"', async () => {
    (api.auth.changePassword as unknown as ReturnType<typeof vi.fn>).mockRejectedValue(new CurrentPasswordIncorrectError('Current password incorrect'))
    const user = userEvent.setup()
    renderPage()
    await fillFields(user, 'oldpass12', 'newpass34', 'newpass34')
    await user.click(screen.getByRole('button', { name: /Update password/ }))
    expect(await screen.findByText(/Current password is incorrect/)).toBeInTheDocument()
  })

  it('shows raw error for other failures', async () => {
    (api.auth.changePassword as unknown as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('Server exploded'))
    const user = userEvent.setup()
    renderPage()
    await fillFields(user, 'oldpass12', 'newpass34', 'newpass34')
    await user.click(screen.getByRole('button', { name: /Update password/ }))
    expect(await screen.findByText('Server exploded')).toBeInTheDocument()
  })
})
