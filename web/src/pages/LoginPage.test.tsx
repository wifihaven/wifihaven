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

import { LoginPage, composeIdentifier, householdHint } from './LoginPage'

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

// #2220: the pure cookie-aware guidance helper. Copy accuracy matters — it must NOT
// hard-require the default/primary household to prepend a slug (a bare username still
// resolves to the default household server-side).
describe('householdHint', () => {
  it('with a cookie slug: tells the user to sign in with just their username, and surfaces the slug', () => {
    const hint = householdHint('smith-family')
    expect(hint.kind).toBe('cookie')
    if (hint.kind === 'cookie') expect(hint.slug).toBe('smith-family')
    expect(hint.text.toLowerCase()).toContain('just')
    expect(hint.text.toLowerCase()).toContain('username')
  })
  it('with no cookie: hints (not requires) household-name/username for named households', () => {
    const hint = householdHint(null)
    expect(hint.kind).toBe('no-cookie')
    expect(hint.text.toLowerCase()).toContain('household-name/username')
    // Must be a soft hint ("if you belong to a named household"), never a hard "you must prepend":
    // the default/primary household signs in with a bare username.
    expect(hint.text.toLowerCase()).toContain('if you belong')
    expect(hint.text.toLowerCase()).not.toContain('must')
  })
  it('treats a blank/whitespace cookie slug as no cookie', () => {
    expect(householdHint('   ').kind).toBe('no-cookie')
  })
})

describe('LoginPage', () => {
  it('#2220: with a wh_household cookie, shows the "just your username" guidance and the slug', () => {
    document.cookie = `${HOUSEHOLD_COOKIE_NAME}=smith-family; Path=/`
    renderLogin()
    const hint = screen.getByTestId('household-hint')
    expect(hint.textContent?.toLowerCase()).toContain('just')
    expect(hint.textContent?.toLowerCase()).toContain('username')
    expect(hint.textContent).toContain('smith-family')
  })

  it('#2220: with NO cookie, shows the named-household hint without hard-requiring a prefix', () => {
    renderLogin()
    const hint = screen.getByTestId('household-hint')
    expect(hint.textContent).toContain('household-name/username')
    expect(hint.textContent?.toLowerCase()).toContain('if you belong')
    expect(hint.textContent?.toLowerCase()).not.toContain('must')
  })

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
