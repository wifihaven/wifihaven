import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { withQuery } from '@/test/queryWrapper'
import type { SupportIdentityResponse } from '@/types/api'

const navigateMock = vi.fn()
const logoutMock = vi.fn()
let mockAuth: {
  username: string
  role: 'admin' | 'adult' | 'child' | null
  isAdmin: boolean
  isWriter: boolean
  logout: () => void
} = { username: 'alice', role: 'admin', isAdmin: true, isWriter: true, logout: logoutMock }

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return { ...actual, useNavigate: () => navigateMock }
})

vi.mock('@/hooks/useAuth', () => ({
  useAuth: () => mockAuth,
}))

// #2133: the operator nav item is driven by the isOperator API signal (useMe), not the JWT role.
let mockMe: { isOperator?: boolean } | undefined = undefined
// #2199 / #2429: Layout mounts SupportWidget + SupportFooter, both of which read the widget
// identity. Unset by default in tests (no widget, no support line).
let mockIdentity: SupportIdentityResponse | undefined = undefined
vi.mock('@/api/queries', () => ({
  useMe: () => ({ data: mockMe }),
  useSupportIdentity: () => ({ data: mockIdentity }),
}))

import { Layout } from './Layout'

beforeEach(() => {
  navigateMock.mockReset()
  logoutMock.mockReset()
  mockAuth = { username: 'alice', role: 'admin', isAdmin: true, isWriter: true, logout: logoutMock }
  mockMe = undefined
  mockIdentity = undefined
})

function renderLayout() {
  return render(
    withQuery(
      <MemoryRouter initialEntries={['/dashboard']}>
        <Routes>
          <Route element={<Layout />}>
            <Route path="/dashboard" element={<div>dash</div>} />
          </Route>
        </Routes>
      </MemoryRouter>,
    ),
  )
}

describe('Layout — primary nav', () => {
  it('renders the three primary items inline for admins', () => {
    renderLayout()
    for (const label of ['Dashboard', 'Devices', 'Profiles']) {
      expect(screen.getAllByRole('link', { name: new RegExp(label) }).length).toBeGreaterThan(0)
    }
  })

  it('drops the Screen Time entry (merged into /profiles in #972)', () => {
    renderLayout()
    expect(screen.queryByRole('link', { name: /Screen Time/ })).not.toBeInTheDocument()
  })

  it('renders the three primary items inline for non-admins', () => {
    mockAuth = { username: 'bob', role: 'child', isAdmin: false, isWriter: false, logout: logoutMock }
    renderLayout()
    for (const label of ['Dashboard', 'Devices', 'Profiles']) {
      expect(screen.getAllByRole('link', { name: new RegExp(label) }).length).toBeGreaterThan(0)
    }
  })

  it('does not render Usage inline in the desktop header', () => {
    renderLayout()
    // The Advanced button is collapsed by default, so Usage should not be present yet.
    expect(screen.queryByRole('link', { name: /Usage/ })).not.toBeInTheDocument()
  })
})

describe('Layout — Settings dropdown', () => {
  it('reveals all settings items for admins when opened', async () => {
    const user = userEvent.setup()
    renderLayout()
    await user.click(screen.getByRole('button', { name: /Advanced/ }))
    const menu = screen.getByRole('menu')
    expect(within(menu).getByRole('menuitem', { name: /Usage/ })).toBeInTheDocument()
    expect(within(menu).getByRole('menuitem', { name: /Traffic Reports/ })).toBeInTheDocument()
    expect(within(menu).getByRole('menuitem', { name: /Connection Events/ })).toBeInTheDocument()
    expect(within(menu).getByRole('menuitem', { name: /Users/ })).toBeInTheDocument()
    expect(within(menu).getByRole('menuitem', { name: /Routers/ })).toBeInTheDocument()
    expect(within(menu).getByRole('menuitem', { name: /Settings/ })).toBeInTheDocument()
  })

  it('shows only Usage in the dropdown for non-admins', async () => {
    mockAuth = { username: 'bob', role: 'child', isAdmin: false, isWriter: false, logout: logoutMock }
    const user = userEvent.setup()
    renderLayout()
    await user.click(screen.getByRole('button', { name: /Advanced/ }))
    const menu = screen.getByRole('menu')
    expect(within(menu).getByRole('menuitem', { name: /Usage/ })).toBeInTheDocument()
    expect(within(menu).queryByRole('menuitem', { name: /Users/ })).not.toBeInTheDocument()
    expect(within(menu).queryByRole('menuitem', { name: /Routers/ })).not.toBeInTheDocument()
    expect(within(menu).queryByRole('menuitem', { name: /Settings/ })).not.toBeInTheDocument()
  })

  // #2133 — the operator beta-request queue is gated on the isOperator API signal.
  it('hides Beta Requests for an admin who is NOT an operator', async () => {
    mockMe = { isOperator: false }
    const user = userEvent.setup()
    renderLayout()
    await user.click(screen.getByRole('button', { name: /Advanced/ }))
    const menu = screen.getByRole('menu')
    expect(within(menu).queryByRole('menuitem', { name: /Beta Requests/ })).not.toBeInTheDocument()
  })

  it('shows Beta Requests only when the API reports the caller is an operator', async () => {
    mockMe = { isOperator: true }
    const user = userEvent.setup()
    renderLayout()
    await user.click(screen.getByRole('button', { name: /Advanced/ }))
    const menu = screen.getByRole('menu')
    expect(within(menu).getByRole('menuitem', { name: /Beta Requests/ })).toBeInTheDocument()
  })
})

describe('Layout — mobile bottom nav', () => {
  it('renders exactly 3 cells regardless of role', () => {
    const { container, unmount } = renderLayout()
    const bottomNav = container.querySelector('nav.md\\:hidden')
    expect(bottomNav).not.toBeNull()
    expect(bottomNav!.querySelectorAll('a').length).toBe(3)
    unmount()

    mockAuth = { username: 'bob', role: 'child', isAdmin: false, isWriter: false, logout: logoutMock }
    const { container: c2 } = renderLayout()
    const bottomNav2 = c2.querySelector('nav.md\\:hidden')
    expect(bottomNav2!.querySelectorAll('a').length).toBe(3)
  })
})

describe('Layout — mobile drawer', () => {
  it('includes settings items in the flat drawer list for admins', async () => {
    const user = userEvent.setup()
    renderLayout()
    await user.click(screen.getByRole('button', { name: /Open menu/ }))
    // Drawer + bottom-nav both render links. With drawer open, Users + Usage should appear.
    expect(screen.getAllByRole('link', { name: /Users/ }).length).toBeGreaterThan(0)
    expect(screen.getAllByRole('link', { name: /Usage/ }).length).toBeGreaterThan(0)
  })
})

describe('Layout — logout', () => {
  it('calls logout and navigates to /login', async () => {
    const user = userEvent.setup()
    renderLayout()
    await user.click(screen.getByRole('button', { name: /Logout/ }))
    expect(logoutMock).toHaveBeenCalled()
    expect(navigateMock).toHaveBeenCalledWith('/login')
  })
})

describe('Layout — header', () => {
  it('renders username and role', () => {
    renderLayout()
    expect(screen.getByText(/alice · admin/)).toBeInTheDocument()
  })
})

// #2429: the in-product support affordance at the bottom of the shell. Three invariants: admin-only,
// the address comes from the API (environment signal — never hardcoded in the SPA), and the
// "click the chat icon" half appears ONLY when the Plain widget is actually rendering.
describe('Layout — support affordance (#2429)', () => {
  const identified: SupportIdentityResponse = {
    configured: true,
    appId: 'plainApp_123',
    email: 'alice@example.com',
    emailHash: 'deadbeefhash',
    tenantIdentifier: '7',
    fullName: 'The Test Family',
    plan: 'beta',
    founding: true,
    supportEmail: 'support@staging.wifihaven.net',
  }

  // Widget dark (unconfigured / unidentifiable caller) but the inbox is still reachable.
  const darkWidget: SupportIdentityResponse = {
    configured: false,
    appId: null,
    email: null,
    emailHash: null,
    tenantIdentifier: null,
    fullName: null,
    plan: null,
    founding: null,
    supportEmail: 'support@wifihaven.net',
  }

  it('shows the email link AND the chat-icon wording for an admin with the widget live', () => {
    mockIdentity = identified
    renderLayout()
    const link = screen.getByRole('link', { name: 'support@staging.wifihaven.net' })
    expect(link).toHaveAttribute('href', 'mailto:support@staging.wifihaven.net')
    expect(screen.getByText(/click the chat icon for support/)).toBeInTheDocument()
  })

  it('degrades to email-only when the chat widget is dark — never promises a missing icon', () => {
    mockIdentity = darkWidget
    renderLayout()
    // The address is the one the API reported for THIS environment, not a hardcoded default.
    const link = screen.getByRole('link', { name: 'support@wifihaven.net' })
    expect(link).toHaveAttribute('href', 'mailto:support@wifihaven.net')
    expect(screen.queryByText(/chat icon/)).not.toBeInTheDocument()
    expect(screen.getByText(/for support/)).toBeInTheDocument()
  })

  it('shows nothing for non-admins even when the identity payload is present', () => {
    mockIdentity = identified
    mockAuth = { username: 'bob', role: 'child', isAdmin: false, isWriter: false, logout: logoutMock }
    renderLayout()
    expect(screen.queryByText(/for support/)).not.toBeInTheDocument()
    expect(
      screen.queryByRole('link', { name: /support@staging\.wifihaven\.net/ }),
    ).not.toBeInTheDocument()
  })

  it('shows nothing when the deployment publishes no support address (self-hosted)', () => {
    mockIdentity = { ...darkWidget, supportEmail: null }
    renderLayout()
    expect(screen.queryByText(/for support/)).not.toBeInTheDocument()
  })
})

// #2522 — the nav is the first place the capability split is visible. An adult must SEE every
// policy-editing surface (Apps / Blocklists / Schedules / Settings) and must NOT see any
// account-management one (Users / Routers / Billing) — those routes still answer 403.
describe('Layout — Settings dropdown, adult capability (#2522)', () => {
  const adult = { username: 'dana', role: 'adult' as const, isAdmin: false, isWriter: true, logout: logoutMock }

  async function openMenu() {
    const user = userEvent.setup()
    renderLayout()
    await user.click(screen.getByRole('button', { name: /Advanced/ }))
    return screen.getByRole('menu')
  }

  it('shows the policy-editing items to an adult', async () => {
    mockAuth = adult
    const menu = await openMenu()
    expect(within(menu).getByRole('menuitem', { name: /Apps/ })).toBeInTheDocument()
    expect(within(menu).getByRole('menuitem', { name: /Blocklists/ })).toBeInTheDocument()
    expect(within(menu).getByRole('menuitem', { name: /Schedules/ })).toBeInTheDocument()
    expect(within(menu).getByRole('menuitem', { name: /Settings/ })).toBeInTheDocument()
  })

  it('hides every account-management item from an adult', async () => {
    mockAuth = adult
    const menu = await openMenu()
    expect(within(menu).queryByRole('menuitem', { name: /Users/ })).not.toBeInTheDocument()
    expect(within(menu).queryByRole('menuitem', { name: /Routers/ })).not.toBeInTheDocument()
    expect(within(menu).queryByRole('menuitem', { name: /Billing/ })).not.toBeInTheDocument()
  })

  it('still hides the policy-editing items from a child', async () => {
    mockAuth = { username: 'bob', role: 'child', isAdmin: false, isWriter: false, logout: logoutMock }
    const menu = await openMenu()
    expect(within(menu).queryByRole('menuitem', { name: /Apps/ })).not.toBeInTheDocument()
    expect(within(menu).queryByRole('menuitem', { name: /Blocklists/ })).not.toBeInTheDocument()
    expect(within(menu).queryByRole('menuitem', { name: /Schedules/ })).not.toBeInTheDocument()
    expect(within(menu).queryByRole('menuitem', { name: /Settings/ })).not.toBeInTheDocument()
  })
})
