import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { HOUSEHOLD_COOKIE_NAME } from '@/api/householdCookie'

const navigateMock = vi.fn()
const loginMock = vi.fn()

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return { ...actual, useNavigate: () => navigateMock }
})

vi.mock('@/hooks/useAuth', () => ({
  useAuth: () => ({ login: loginMock }),
}))

import { LoginPage, composeIdentifier } from './LoginPage'

function clearHouseholdCookie() {
  document.cookie = `${HOUSEHOLD_COOKIE_NAME}=; Path=/; Max-Age=0`
}

beforeEach(() => {
  navigateMock.mockReset()
  loginMock.mockReset()
  clearHouseholdCookie()
})

afterEach(() => {
  clearHouseholdCookie()
})

function renderLogin() {
  return render(
    <MemoryRouter>
      <LoginPage />
    </MemoryRouter>
  )
}

// #2164: the pure identifier-composition helper — the heart of the client-side household resolution.
describe('composeIdentifier', () => {
  it('posts an email verbatim (never composed)', () => {
    expect(composeIdentifier('alice@example.com', 'smith-family')).toBe('alice@example.com')
  })
  it('posts an explicit slug/username verbatim', () => {
    expect(composeIdentifier('smith-family/emma', 'other')).toBe('smith-family/emma')
  })
  it('prepends the cookie slug to a bare username', () => {
    expect(composeIdentifier('emma', 'smith-family')).toBe('smith-family/emma')
  })
  it('leaves a bare username bare when there is no cookie (→ server default household)', () => {
    expect(composeIdentifier('admin', null)).toBe('admin')
  })
  it('trims whitespace before deciding', () => {
    expect(composeIdentifier('  emma  ', 'smith-family')).toBe('smith-family/emma')
  })
})

describe('LoginPage', () => {
  it('logs in and navigates to /dashboard on success', async () => {
    loginMock.mockResolvedValue({ mustChangePassword: false })
    const user = userEvent.setup()
    renderLogin()

    await user.type(screen.getByPlaceholderText(/you@example.com/), 'alice')
    await user.type(screen.getByPlaceholderText('••••••••'), 'secret123')
    await user.click(screen.getByRole('button', { name: /Sign in/ }))

    // #2164: no cookie → bare username posted as-is (server resolves the default household).
    await waitFor(() => expect(loginMock).toHaveBeenCalledWith('alice', 'secret123'))
    await waitFor(() => expect(navigateMock).toHaveBeenCalledWith('/dashboard'))
  })

  it('#2164: composes a bare username with the wh_household cookie slug before posting', async () => {
    document.cookie = `${HOUSEHOLD_COOKIE_NAME}=smith-family; Path=/`
    loginMock.mockResolvedValue({ mustChangePassword: false })
    const user = userEvent.setup()
    renderLogin()

    await user.type(screen.getByPlaceholderText(/you@example.com/), 'emma')
    await user.type(screen.getByPlaceholderText('••••••••'), 'secret123')
    await user.click(screen.getByRole('button', { name: /Sign in/ }))

    await waitFor(() => expect(loginMock).toHaveBeenCalledWith('smith-family/emma', 'secret123'))
  })

  it('#2164: posts an email verbatim even with a cookie present', async () => {
    document.cookie = `${HOUSEHOLD_COOKIE_NAME}=smith-family; Path=/`
    loginMock.mockResolvedValue({ mustChangePassword: false })
    const user = userEvent.setup()
    renderLogin()

    await user.type(screen.getByPlaceholderText(/you@example.com/), 'alice@example.com')
    await user.type(screen.getByPlaceholderText('••••••••'), 'secret123')
    await user.click(screen.getByRole('button', { name: /Sign in/ }))

    await waitFor(() => expect(loginMock).toHaveBeenCalledWith('alice@example.com', 'secret123'))
  })

  it('shows an error message when login fails', async () => {
    loginMock.mockRejectedValue(new Error('nope'))
    const user = userEvent.setup()
    renderLogin()

    await user.type(screen.getByPlaceholderText(/you@example.com/), 'alice')
    await user.type(screen.getByPlaceholderText('••••••••'), 'wrong')
    await user.click(screen.getByRole('button', { name: /Sign in/ }))

    expect(await screen.findByText(/Invalid email\/username or password/)).toBeInTheDocument()
    expect(navigateMock).not.toHaveBeenCalled()
  })
})
