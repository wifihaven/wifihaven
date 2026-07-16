import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { withQuery } from '@/test/queryWrapper'
import type { RouterSummary } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    routers: { list: vi.fn() },
  },
}))

import { api } from '@/api/client'
import { FirstRunHint } from './DashboardPage'

const listRouters = api.routers.list as unknown as ReturnType<typeof vi.fn>

const router = (over: Partial<RouterSummary> = {}): RouterSummary => ({
  id: 'r1', name: 'home-gw', enrolled: false,
  lastSeenAt: null, lastEtag: null, createdAt: '2026-05-11T00:00:00Z',
  agentVersion: null,
  ...over,
})

beforeEach(() => {
  vi.resetAllMocks()
})

function renderHint() {
  return render(withQuery(
    <MemoryRouter>
      <FirstRunHint />
    </MemoryRouter>,
  ))
}

describe('FirstRunHint — #2252 router-onboarding-aware welcome banner', () => {
  it('shows the "Enroll a router" CTA when no router is enrolled yet', async () => {
    listRouters.mockResolvedValue([])
    renderHint()

    const hint = await screen.findByTestId('first-run-hint')
    expect(hint).toHaveAttribute('data-state', 'none')
    const cta = screen.getByRole('link', { name: /enroll a router/i })
    expect(cta).toHaveAttribute('href', '/routers')
    expect(screen.queryByText(/waiting for your router/i)).not.toBeInTheDocument()
  })

  it('shows "Waiting for your router to connect" when an enrollment exists but no router has connected', async () => {
    // A `routers` row is minted at token creation; lastSeenAt stays null until the agent's
    // first policy fetch, so a never-seen router is a pending enrollment, not "connected".
    listRouters.mockResolvedValue([router({ lastSeenAt: null })])
    renderHint()

    const hint = await screen.findByTestId('first-run-hint')
    expect(hint).toHaveAttribute('data-state', 'pending')
    expect(screen.getByText(/waiting for your router to connect/i)).toBeInTheDocument()
    // NOT the enroll CTA — the admin already enrolled.
    expect(screen.queryByRole('link', { name: /enroll a router/i })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: /view router setup/i })).toHaveAttribute('href', '/routers')
  })

  it('renders nothing once a router has checked in (lastSeenAt set)', async () => {
    listRouters.mockResolvedValue([router({ lastSeenAt: '2026-05-12T00:00:00Z', enrolled: true })])
    renderHint()

    await waitFor(() => expect(listRouters).toHaveBeenCalled())
    expect(screen.queryByTestId('first-run-hint')).not.toBeInTheDocument()
  })

  it('treats a household as connected when ANY router has been seen', async () => {
    listRouters.mockResolvedValue([
      router({ id: 'r1', lastSeenAt: null }),
      router({ id: 'r2', lastSeenAt: '2026-05-12T00:00:00Z' }),
    ])
    renderHint()

    await waitFor(() => expect(listRouters).toHaveBeenCalled())
    expect(screen.queryByTestId('first-run-hint')).not.toBeInTheDocument()
  })

  it('does not flash a banner while the routers query is still loading (loading-states rule)', () => {
    listRouters.mockReturnValue(new Promise(() => {}))
    renderHint()
    expect(screen.queryByTestId('first-run-hint')).not.toBeInTheDocument()
  })
})
