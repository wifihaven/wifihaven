import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import type { Alert } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    alerts: {
      list: vi.fn(),
      approve: vi.fn(),
      deny: vi.fn(),
      createAccessRequest: vi.fn(),
    },
  },
}))

import { api } from '@/api/client'
import { AccessRequestsBanner, NewDevicesHint } from './AlertsPanel'
import { withQuery } from '@/test/queryWrapper'

function accessReq(
  id: number,
  requestKind: NonNullable<Alert['requestKind']>,
  host = 'youtube.com',
): Alert {
  return {
    id,
    kind: 'access_request',
    status: 'pending',
    mac: 'aa:bb:cc:11:22:33',
    deviceName: 'kid-laptop',
    profileId: 1,
    profileName: 'Kids',
    host,
    requestKind,
    note: null,
    grantedMinutes: null,
    createdAt: new Date(Date.now() - 60_000).toISOString(),
    decidedAt: null,
    decidedBy: null,
  }
}

function newDevice(id: number, mac: string): Alert {
  return {
    id,
    kind: 'new_device',
    status: 'pending',
    mac,
    deviceName: `device-${mac.slice(-2)}`,
    profileId: null,
    profileName: null,
    host: null,
    requestKind: null,
    note: null,
    grantedMinutes: null,
    createdAt: new Date().toISOString(),
    decidedAt: null,
    decidedBy: null,
  }
}

function renderPanels() {
  return render(
    <MemoryRouter>
      {withQuery(<>
        <NewDevicesHint />
        <AccessRequestsBanner />
      </>)}
    </MemoryRouter>,
  )
}

describe('AlertsPanel — access_request additions (#960)', () => {
  beforeEach(() => vi.restoreAllMocks())

  it('renders nothing when no access_request rows are pending', async () => {
    vi.mocked(api.alerts.list).mockResolvedValue([newDevice(1, 'aa:bb:cc:01:02:03')])
    renderPanels()
    // new-device hint shows up; access-request banner does not.
    expect(await screen.findByTestId('dashboard-new-devices-hint')).toBeInTheDocument()
    expect(screen.queryByTestId('access-requests-banner')).not.toBeInTheDocument()
  })

  it('shows the access-requests banner when access_request rows are pending', async () => {
    vi.mocked(api.alerts.list).mockResolvedValue([accessReq(1, 'extension')])
    renderPanels()
    expect(await screen.findByTestId('access-requests-banner')).toBeInTheDocument()
  })

  it('opens a review modal with approve / deny actions when clicked', async () => {
    vi.mocked(api.alerts.list).mockResolvedValue([accessReq(7, 'exemption', 'foo.com')])
    vi.mocked(api.alerts.approve).mockResolvedValue({} as never)
    renderPanels()
    fireEvent.click(await screen.findByTestId('access-requests-banner'))
    expect(await screen.findByTestId('access-requests-modal')).toBeInTheDocument()
    expect(screen.getByTestId('access-request-7')).toHaveTextContent(/foo\.com/)

    fireEvent.click(screen.getByTestId('access-request-approve-7'))
    await waitFor(() => expect(api.alerts.approve).toHaveBeenCalledTimes(1))
    expect(api.alerts.approve).toHaveBeenCalledWith(7, {})
  })

  it('sends user-edited minutes when approving an extension', async () => {
    vi.mocked(api.alerts.list).mockResolvedValue([accessReq(9, 'extension')])
    vi.mocked(api.alerts.approve).mockResolvedValue({} as never)
    renderPanels()
    fireEvent.click(await screen.findByTestId('access-requests-banner'))
    const input = await screen.findByLabelText(/Minutes/i)
    fireEvent.change(input, { target: { value: '15' } })
    fireEvent.click(screen.getByTestId('access-request-approve-9'))
    await waitFor(() =>
      expect(api.alerts.approve).toHaveBeenCalledWith(9, { minutes: 15 }),
    )
  })

  it('denies via the deny button', async () => {
    vi.mocked(api.alerts.list).mockResolvedValue([accessReq(5, 'unpause')])
    vi.mocked(api.alerts.deny).mockResolvedValue({} as never)
    renderPanels()
    fireEvent.click(await screen.findByTestId('access-requests-banner'))
    fireEvent.click(await screen.findByTestId('access-request-deny-5'))
    await waitFor(() => expect(api.alerts.deny).toHaveBeenCalledWith(5))
  })
})
