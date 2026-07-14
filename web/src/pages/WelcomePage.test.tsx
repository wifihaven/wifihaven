import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { HOUSEHOLD_COOKIE_NAME, getHouseholdCookie } from '@/api/householdCookie'

const navigateMock = vi.fn()
const loginMock = vi.fn()

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return { ...actual, useNavigate: () => navigateMock }
})

vi.mock('@/hooks/useAuth', () => ({
  useAuth: () => ({ login: loginMock }),
}))

vi.mock('@/api/client', () => ({
  api: { beta: { accept: vi.fn() } },
}))

import { api } from '@/api/client'
import { WelcomePage, MIN_PASSWORD_LENGTH } from './WelcomePage'

const acceptMock = api.beta.accept as unknown as ReturnType<typeof vi.fn>

function clearHouseholdCookie() {
  document.cookie = `${HOUSEHOLD_COOKIE_NAME}=; Path=/; Max-Age=0`
}

beforeEach(() => {
  vi.resetAllMocks()
  clearHouseholdCookie()
})

afterEach(() => {
  clearHouseholdCookie()
})

function renderWelcome(search = '?token=invite-abc') {
  return render(
    <MemoryRouter initialEntries={[`/welcome${search}`]}>
      <WelcomePage />
    </MemoryRouter>,
  )
}

const strongPw = 'a'.repeat(MIN_PASSWORD_LENGTH)

describe('WelcomePage — /welcome invite accept', () => {
  it('accepts the invite, sets the household cookie, auto-logs-in, and redirects to the dashboard', async () => {
    acceptMock.mockResolvedValue({ householdId: 7, slug: 'smith-family', username: 'admin' })
    loginMock.mockResolvedValue({ mustChangePassword: false })
    const user = userEvent.setup()
    renderWelcome()

    await user.type(screen.getByLabelText(/^password/i), strongPw)
    await user.type(screen.getByLabelText(/confirm password/i), strongPw)
    await user.click(screen.getByRole('button', { name: /create my household/i }))

    await waitFor(() => expect(acceptMock).toHaveBeenCalledWith('invite-abc', strongPw))
    // Cookie set to the returned slug BEFORE auto-login.
    await waitFor(() => expect(getHouseholdCookie()).toBe('smith-family'))
    // Auto-login uses the slug/username composite (path 2) with the just-set password.
    expect(loginMock).toHaveBeenCalledWith('smith-family/admin', strongPw)
    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/dashboard'))
  })

  it('rejects a password shorter than the policy minimum without calling the API', async () => {
    const user = userEvent.setup()
    renderWelcome()

    await user.type(screen.getByLabelText(/^password/i), 'short')
    await user.type(screen.getByLabelText(/confirm password/i), 'short')
    await user.click(screen.getByRole('button', { name: /create my household/i }))

    // Specific — the static "At least 12 characters." hint also matches loosely.
    expect(await screen.findByText(/password must be at least \d+ characters/i)).toBeInTheDocument()
    expect(acceptMock).not.toHaveBeenCalled()
  })

  it('flags mismatched passwords without calling the API', async () => {
    const user = userEvent.setup()
    renderWelcome()

    await user.type(screen.getByLabelText(/^password/i), strongPw)
    await user.type(screen.getByLabelText(/confirm password/i), strongPw + 'x')
    await user.click(screen.getByRole('button', { name: /create my household/i }))

    expect(await screen.findByText(/do not match/i)).toBeInTheDocument()
    expect(acceptMock).not.toHaveBeenCalled()
  })

  it('shows an invalid/expired message when accept fails and does not redirect', async () => {
    acceptMock.mockRejectedValue(new Error('invalid or expired invite'))
    const user = userEvent.setup()
    renderWelcome()

    await user.type(screen.getByLabelText(/^password/i), strongPw)
    await user.type(screen.getByLabelText(/confirm password/i), strongPw)
    await user.click(screen.getByRole('button', { name: /create my household/i }))

    expect(await screen.findByText(/invalid or has expired/i)).toBeInTheDocument()
    expect(navigateMock).not.toHaveBeenCalled()
  })

  it('when accept succeeds but auto-login fails, keeps the cookie and points to email sign-in', async () => {
    acceptMock.mockResolvedValue({ householdId: 7, slug: 'smith-family', username: 'admin' })
    loginMock.mockRejectedValue(new Error('network'))
    const user = userEvent.setup()
    renderWelcome()

    await user.type(screen.getByLabelText(/^password/i), strongPw)
    await user.type(screen.getByLabelText(/confirm password/i), strongPw)
    await user.click(screen.getByRole('button', { name: /create my household/i }))

    expect(await screen.findByText(/automatic sign-in failed/i)).toBeInTheDocument()
    // The cookie was set before the failed login, so a future login lands in the right household.
    expect(getHouseholdCookie()).toBe('smith-family')
    expect(navigateMock).not.toHaveBeenCalled()
  })

  it('prompts for a valid invite link when the token is missing', () => {
    renderWelcome('')
    expect(screen.getByText(/invite link required/i)).toBeInTheDocument()
  })
})
