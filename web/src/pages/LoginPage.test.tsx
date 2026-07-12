import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'

const navigateMock = vi.fn()
const loginMock = vi.fn()

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return { ...actual, useNavigate: () => navigateMock }
})

vi.mock('@/hooks/useAuth', () => ({
  useAuth: () => ({ login: loginMock }),
}))

import { LoginPage } from './LoginPage'

beforeEach(() => {
  navigateMock.mockReset()
  loginMock.mockReset()
})

function renderLogin() {
  return render(
    <MemoryRouter>
      <LoginPage />
    </MemoryRouter>
  )
}

describe('LoginPage', () => {
  it('logs in and navigates to /dashboard on success', async () => {
    loginMock.mockResolvedValue({ mustChangePassword: false })
    const user = userEvent.setup()
    renderLogin()

    await user.type(screen.getByPlaceholderText('admin'), 'alice')
    await user.type(screen.getByPlaceholderText('••••••••'), 'secret123')
    await user.click(screen.getByRole('button', { name: /Sign in/ }))

    // #2140: no household entered → undefined third arg (server resolves the default household).
    await waitFor(() => expect(loginMock).toHaveBeenCalledWith('alice', 'secret123', undefined))
    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/dashboard'))
  })

  it('#2140: passes the household slug when the "Different household?" field is filled', async () => {
    loginMock.mockResolvedValue({ mustChangePassword: false })
    const user = userEvent.setup()
    renderLogin()

    await user.type(screen.getByPlaceholderText('admin'), 'alice')
    await user.type(screen.getByPlaceholderText('••••••••'), 'secret123')
    // The field is collapsed by default (no cookie) — reveal it, then enter a slug.
    await user.click(screen.getByRole('button', { name: /Different household\?/ }))
    await user.type(screen.getByPlaceholderText('your-household'), 'smith-family')
    await user.click(screen.getByRole('button', { name: /Sign in/ }))

    await waitFor(() =>
      expect(loginMock).toHaveBeenCalledWith('alice', 'secret123', 'smith-family'),
    )
  })

  it('shows an error message when login fails', async () => {
    loginMock.mockRejectedValue(new Error('nope'))
    const user = userEvent.setup()
    renderLogin()

    await user.type(screen.getByPlaceholderText('admin'), 'alice')
    await user.type(screen.getByPlaceholderText('••••••••'), 'wrong')
    await user.click(screen.getByRole('button', { name: /Sign in/ }))

    expect(await screen.findByText(/Invalid username or password/)).toBeInTheDocument()
    expect(navigateMock).not.toHaveBeenCalled()
  })
})
