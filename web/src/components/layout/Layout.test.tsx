import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Routes, Route } from 'react-router-dom'

const navigateMock = vi.fn()
const logoutMock = vi.fn()
let mockAuth: {
  username: string
  role: 'admin' | 'adult' | 'child' | null
  isAdmin: boolean
  logout: () => void
} = { username: 'alice', role: 'admin', isAdmin: true, logout: logoutMock }

vi.mock('react-router-dom', async () => {
  const actual = await vi.importActual<typeof import('react-router-dom')>('react-router-dom')
  return { ...actual, useNavigate: () => navigateMock }
})

vi.mock('@/hooks/useAuth', () => ({
  useAuth: () => mockAuth,
}))

import { Layout } from './Layout'

beforeEach(() => {
  navigateMock.mockReset()
  logoutMock.mockReset()
  mockAuth = { username: 'alice', role: 'admin', isAdmin: true, logout: logoutMock }
})

function renderLayout() {
  return render(
    <MemoryRouter initialEntries={['/dashboard']}>
      <Routes>
        <Route element={<Layout />}>
          <Route path="/dashboard" element={<div>dash</div>} />
        </Route>
      </Routes>
    </MemoryRouter>
  )
}

describe('Layout — primary nav', () => {
  it('renders the four primary items inline for admins', () => {
    renderLayout()
    for (const label of ['Dashboard', 'Devices', 'Profiles', 'Screen Time']) {
      expect(screen.getAllByRole('link', { name: new RegExp(label) }).length).toBeGreaterThan(0)
    }
  })

  it('renders the four primary items inline for non-admins', () => {
    mockAuth = { username: 'bob', role: 'child', isAdmin: false, logout: logoutMock }
    renderLayout()
    for (const label of ['Dashboard', 'Devices', 'Profiles', 'Screen Time']) {
      expect(screen.getAllByRole('link', { name: new RegExp(label) }).length).toBeGreaterThan(0)
    }
  })

  it('does not render Usage inline in the desktop header', () => {
    renderLayout()
    // The Advanced button is collapsed by default, so Usage should not be present yet.
    expect(screen.queryByRole('link', { name: /^Usage$/ })).not.toBeInTheDocument()
  })
})

describe('Layout — Settings dropdown', () => {
  it('reveals all settings items for admins when opened', async () => {
    const user = userEvent.setup()
    renderLayout()
    await user.click(screen.getByRole('button', { name: /Advanced/ }))
    const menu = screen.getByRole('menu')
    expect(within(menu).getByRole('menuitem', { name: /^Usage$/ })).toBeInTheDocument()
    expect(within(menu).getByRole('menuitem', { name: /Traffic Reports/ })).toBeInTheDocument()
    expect(within(menu).getByRole('menuitem', { name: /Connection Events/ })).toBeInTheDocument()
    expect(within(menu).getByRole('menuitem', { name: /Users/ })).toBeInTheDocument()
    expect(within(menu).getByRole('menuitem', { name: /Routers/ })).toBeInTheDocument()
    expect(within(menu).getByRole('menuitem', { name: /Settings/ })).toBeInTheDocument()
  })

  it('shows only Usage in the dropdown for non-admins', async () => {
    mockAuth = { username: 'bob', role: 'child', isAdmin: false, logout: logoutMock }
    const user = userEvent.setup()
    renderLayout()
    await user.click(screen.getByRole('button', { name: /Advanced/ }))
    const menu = screen.getByRole('menu')
    expect(within(menu).getByRole('menuitem', { name: /^Usage$/ })).toBeInTheDocument()
    expect(within(menu).queryByRole('menuitem', { name: /Users/ })).not.toBeInTheDocument()
    expect(within(menu).queryByRole('menuitem', { name: /Routers/ })).not.toBeInTheDocument()
    expect(within(menu).queryByRole('menuitem', { name: /Settings/ })).not.toBeInTheDocument()
  })
})

describe('Layout — mobile bottom nav', () => {
  it('renders exactly 4 cells regardless of role', () => {
    const { container, unmount } = renderLayout()
    const bottomNav = container.querySelector('nav.md\\:hidden')
    expect(bottomNav).not.toBeNull()
    expect(bottomNav!.querySelectorAll('a').length).toBe(4)
    unmount()

    mockAuth = { username: 'bob', role: 'child', isAdmin: false, logout: logoutMock }
    const { container: c2 } = renderLayout()
    const bottomNav2 = c2.querySelector('nav.md\\:hidden')
    expect(bottomNav2!.querySelectorAll('a').length).toBe(4)
  })
})

describe('Layout — mobile drawer', () => {
  it('includes settings items in the flat drawer list for admins', async () => {
    const user = userEvent.setup()
    renderLayout()
    await user.click(screen.getByRole('button', { name: /Open menu/ }))
    // Drawer + bottom-nav both render links. With drawer open, Users + Usage should appear.
    expect(screen.getAllByRole('link', { name: /Users/ }).length).toBeGreaterThan(0)
    expect(screen.getAllByRole('link', { name: /^Usage$/ }).length).toBeGreaterThan(0)
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
