import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'

vi.mock('@/api/client', () => ({
  api: {
    auth: {
      login: vi.fn(),
    },
    time: {
      statusDevice: vi.fn(),
      grantExtension: vi.fn(),
    },
  },
}))

import { api } from '@/api/client'
import { BlockedPage } from './BlockedPage'

const mockLogin        = api.auth.login as unknown as ReturnType<typeof vi.fn>
const mockStatusDevice = api.time.statusDevice as unknown as ReturnType<typeof vi.fn>
const mockGrant        = api.time.grantExtension as unknown as ReturnType<typeof vi.fn>

function renderBlocked(params: Record<string, string>) {
  const search = '?' + new URLSearchParams(params).toString()
  return render(
    <MemoryRouter initialEntries={[`/blocked${search}`]}>
      <BlockedPage />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.resetAllMocks()
})

describe('BlockedPage — reason display', () => {
  // Reason strings here mirror MacBlockReason wire format
  // (shared/src/Models.scala: Paused / Schedule / TimeLimit / Manual).
  it('shows paused-profile copy for reason=Paused (#437)', () => {
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'example.com', reason: 'Paused' })
    expect(screen.getByText(/profile is paused/i)).toBeInTheDocument()
  })

  it('shows schedule end time for reason=Schedule with until param', () => {
    renderBlocked({
      mac: 'aa:bb:cc:11:22:33',
      host: 'example.com',
      reason: 'Schedule',
      until: '07:00',
    })
    expect(screen.getByText(/07:00/)).toBeInTheDocument()
  })

  it('shows daily limit message for reason=TimeLimit', () => {
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'youtube.com', reason: 'TimeLimit' })
    expect(screen.getByText(/daily/i)).toBeInTheDocument()
    expect(screen.getByText(/screen time/i)).toBeInTheDocument()
  })

  it('shows manual-block copy for reason=Manual', () => {
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'example.com', reason: 'Manual' })
    expect(screen.getByText(/blocked by a parent/i)).toBeInTheDocument()
  })

  it('shows category message for reason=category:ads', () => {
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'ads.example.com', reason: 'category:ads' })
    expect(screen.getByText(/blocked category/i)).toBeInTheDocument()
  })

  it('shows a non-empty fallback for unknown reason instead of leaving it blank (#437)', () => {
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'example.com', reason: 'whatever' })
    expect(screen.getByText(/access blocked/i)).toBeInTheDocument()
  })

  it('shows the blocked hostname prominently', () => {
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'youtube.com', reason: 'TimeLimit' })
    expect(screen.getByText('youtube.com')).toBeInTheDocument()
  })
})

describe('BlockedPage — request extension flow', () => {
  it('shows "Request extension" button', () => {
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'youtube.com', reason: 'time_limit' })
    expect(screen.getByRole('button', { name: /request extension/i })).toBeInTheDocument()
  })

  it('clicking "Request extension" opens a login dialog', async () => {
    const user = userEvent.setup()
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'youtube.com', reason: 'time_limit' })

    await user.click(screen.getByRole('button', { name: /request extension/i }))

    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByLabelText(/username/i)).toBeInTheDocument()
    expect(screen.getByLabelText(/password/i)).toBeInTheDocument()
  })

  it('successful parent login then grants extension and shows confirmation', async () => {
    mockLogin.mockResolvedValue({ token: 'parent-token', username: 'mom', role: 'admin' })
    mockStatusDevice.mockResolvedValue({ profileId: 7, profileName: 'Kids', usedMins: 60, extensionMins: 0, remainingMins: 60, dailyLimitMins: 120, siteUsage: [], devices: [] })
    mockGrant.mockResolvedValue({ id: 1, grantedMinutes: 30 })

    const user = userEvent.setup()
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'youtube.com', reason: 'time_limit' })

    // Open login dialog
    await user.click(screen.getByRole('button', { name: /request extension/i }))

    // Fill credentials
    await user.type(screen.getByLabelText(/username/i), 'mom')
    await user.type(screen.getByLabelText(/password/i), 'secret')
    await user.click(screen.getByRole('button', { name: /^log in$/i }))

    await waitFor(() => expect(mockLogin).toHaveBeenCalledWith('mom', 'secret'))

    // After login, grant extension form is shown
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /grant/i })).toBeInTheDocument(),
    )

    // Submit the extension
    await user.click(screen.getByRole('button', { name: /grant/i }))

    await waitFor(() =>
      expect(mockGrant).toHaveBeenCalledWith(
        expect.objectContaining({ profileId: 7 }),
      ),
    )

    // Confirmation shown
    await waitFor(() => expect(screen.getByText(/extension granted/i)).toBeInTheDocument())
  })

  it('login error shows error message in dialog', async () => {
    mockLogin.mockRejectedValue(new Error('Invalid credentials'))

    const user = userEvent.setup()
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'youtube.com', reason: 'time_limit' })

    await user.click(screen.getByRole('button', { name: /request extension/i }))
    await user.type(screen.getByLabelText(/username/i), 'wrong')
    await user.type(screen.getByLabelText(/password/i), 'bad')
    await user.click(screen.getByRole('button', { name: /^log in$/i }))

    await waitFor(() =>
      expect(screen.getByText(/invalid credentials/i)).toBeInTheDocument(),
    )
  })

  it('can dismiss login dialog with cancel button', async () => {
    const user = userEvent.setup()
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'youtube.com', reason: 'time_limit' })

    await user.click(screen.getByRole('button', { name: /request extension/i }))
    expect(screen.getByRole('dialog')).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /cancel/i }))
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })
})
