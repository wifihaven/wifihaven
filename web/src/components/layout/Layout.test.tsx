import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
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

describe('Layout — nav visibility', () => {
  it('shows the Users link for admins', () => {
    renderLayout()
    // Both desktop and mobile bottom nav include "Users"
    expect(screen.getAllByRole('link', { name: /Users/ }).length).toBeGreaterThan(0)
    expect(screen.getAllByRole('link', { name: /Dashboard/ }).length).toBeGreaterThan(0)
  })

  it('hides the Users link for non-admins', () => {
    mockAuth = { username: 'bob', role: 'child', isAdmin: false, logout: logoutMock }
    renderLayout()
    expect(screen.queryByRole('link', { name: /Users/ })).not.toBeInTheDocument()
    expect(screen.getAllByRole('link', { name: /Dashboard/ }).length).toBeGreaterThan(0)
    expect(screen.getAllByRole('link', { name: /Devices/ }).length).toBeGreaterThan(0)
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
