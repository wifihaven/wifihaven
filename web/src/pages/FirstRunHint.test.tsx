import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { withQuery } from '@/test/queryWrapper'
import type { Device, ProfileDetail } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    devices: { list: vi.fn() },
    profiles: { list: vi.fn() },
  },
}))

import { api } from '@/api/client'
import { FirstRunHint } from './DashboardPage'

const listDevices  = api.devices.list as unknown as ReturnType<typeof vi.fn>
const listProfiles = api.profiles.list as unknown as ReturnType<typeof vi.fn>

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

describe('FirstRunHint — first-run empty-household onboarding', () => {
  it('shows the router-enrollment CTA when the household has zero devices AND zero profiles', async () => {
    listDevices.mockResolvedValue([])
    listProfiles.mockResolvedValue([])
    renderHint()

    expect(await screen.findByTestId('first-run-hint')).toBeInTheDocument()
    const cta = screen.getByRole('link', { name: /enroll a router/i })
    expect(cta).toHaveAttribute('href', '/routers')
  })

  it('renders nothing once the household has any device', async () => {
    listDevices.mockResolvedValue([{ mac: 'aa:bb:cc:dd:ee:ff' } as Device])
    listProfiles.mockResolvedValue([])
    renderHint()

    // Let both queries settle, then assert the hint never appears.
    await waitFor(() => expect(listDevices).toHaveBeenCalled())
    await waitFor(() => expect(listProfiles).toHaveBeenCalled())
    expect(screen.queryByTestId('first-run-hint')).not.toBeInTheDocument()
  })

  it('renders nothing once the household has any profile', async () => {
    listDevices.mockResolvedValue([])
    listProfiles.mockResolvedValue([{ id: 1 } as unknown as ProfileDetail])
    renderHint()

    await waitFor(() => expect(listProfiles).toHaveBeenCalled())
    expect(screen.queryByTestId('first-run-hint')).not.toBeInTheDocument()
  })

  it('does not flash the CTA while the queries are still loading (loading-states rule)', () => {
    listDevices.mockReturnValue(new Promise(() => {}))
    listProfiles.mockReturnValue(new Promise(() => {}))
    renderHint()
    expect(screen.queryByTestId('first-run-hint')).not.toBeInTheDocument()
  })
})
