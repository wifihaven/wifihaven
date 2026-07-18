import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Routes, Route, Navigate } from 'react-router-dom'

// #2296: the Press log is gated behind RequireOperator (household-1 admin, via the isOperator API
// signal) — identical to the beta-request queue. This mirrors App.test.tsx's approach: re-implement
// the small routing fragment and drive it off a mocked useMe, so the gate itself is under test.
vi.mock('@/api/queries', () => ({ useMe: vi.fn() }))

import { useMe } from '@/api/queries'

const useMeMock = useMe as unknown as ReturnType<typeof vi.fn>

function RequireOperator({ children }: { children: React.ReactNode }) {
  const { data, isPending } = useMe()
  if (isPending) return null
  return data?.isOperator ? <>{children}</> : <Navigate to="/dashboard" replace />
}

function TestApp() {
  return (
    <Routes>
      <Route path="/dashboard" element={<div>Dashboard stub</div>} />
      <Route
        path="/press"
        element={
          <RequireOperator>
            <div data-testid="press-page">Press page</div>
          </RequireOperator>
        }
      />
    </Routes>
  )
}

function renderAt() {
  return render(
    <MemoryRouter initialEntries={['/press']}>
      <TestApp />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.resetAllMocks()
})

describe('Press log access gate (#2296)', () => {
  it('renders the Press page for a household-1 operator', async () => {
    useMeMock.mockReturnValue({ data: { isOperator: true }, isPending: false })
    renderAt()
    expect(await screen.findByTestId('press-page')).toBeInTheDocument()
  })

  it('redirects a non-operator (e.g. an hh≠1 admin) away from /press', async () => {
    useMeMock.mockReturnValue({ data: { isOperator: false }, isPending: false })
    renderAt()
    await waitFor(() => {
      expect(screen.queryByTestId('press-page')).not.toBeInTheDocument()
    })
    expect(screen.getByText('Dashboard stub')).toBeInTheDocument()
  })

  it('renders nothing while /me is in flight (no flash-redirect)', () => {
    useMeMock.mockReturnValue({ data: undefined, isPending: true })
    renderAt()
    expect(screen.queryByTestId('press-page')).not.toBeInTheDocument()
    expect(screen.queryByText('Dashboard stub')).not.toBeInTheDocument()
  })
})
