import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import type { Device, ProfileDetail } from '@/types/api'
import { withQuery } from '@/test/queryWrapper'

vi.mock('@/api/client', () => ({
  api: {
    devices: {
      list: vi.fn(),
      upsert: vi.fn(),
      patch: vi.fn(),
      delete: vi.fn(),
    },
    profiles: {
      list: vi.fn(),
    },
    alerts: {
      list: vi.fn(),
      approve: vi.fn(),
      deny: vi.fn(),
      createAccessRequest: vi.fn(),
    },
    household: {
      get: vi.fn(),
    },
  },
}))

vi.mock('@/hooks/useAuth', () => ({
  useAuth: () => mockAuth,
}))

import { api } from '@/api/client'
import { DevicesPage } from './DevicesPage'

function renderPage(initialEntries: string[] = ['/devices']) {
  return render(withQuery(
    <MemoryRouter initialEntries={initialEntries}>
      <DevicesPage />
    </MemoryRouter>,
  ))
}

let mockAuth = { isAdmin: true }

const ipad: Device = {
  id: 1, mac: 'aa:bb:cc:dd:ee:01', name: "Kid's iPad",
  profileId: 1, profileName: 'Kids',
  lastSeenIp: null, lastSeenAt: null,
}
const laptop: Device = {
  id: 2, mac: 'aa:bb:cc:dd:ee:02', name: 'Work Laptop',
  profileId: 2, profileName: null,
  lastSeenIp: null, lastSeenAt: null,
}

const kidsProfile: ProfileDetail = {
  profile: { id: 1, name: 'Kids', blockedCategories: [], paused: false, failureMode: 'block-all', crossDeviceOverlapMode: 'sum' },
  schedules: [], timeLimit: null,
}
const adultsProfile: ProfileDetail = {
  profile: { id: 2, name: 'Adults', blockedCategories: [], paused: false, failureMode: 'last-known-good', crossDeviceOverlapMode: 'sum' },
  schedules: [], timeLimit: null,
}

beforeEach(() => {
  vi.resetAllMocks()
  mockAuth = { isAdmin: true }
  ;(api.devices.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([ipad, laptop])
  ;(api.profiles.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([kidsProfile, adultsProfile])
  ;(api.devices.upsert as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 99 })
  ;(api.devices.delete as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
  ;(api.devices.patch as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
  ;(api.alerts.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
  ;(api.alerts.approve as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
  ;(api.household.get as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
    dailyResetTime: '00:00',
    dailyResetTz: 'UTC',
    heartbeatFilter: { enabled: false, bytesThreshold: 0, heartbeatHostPatterns: [] },
    unmanagedMacPolicy: { policy: 'allow', blockPage: true },
  })
})

describe('DevicesPage — list', () => {
  it('renders names, MAC, and profile pills', async () => {
    renderPage()
    expect(await screen.findByText("Kid's iPad")).toBeInTheDocument()
    expect(screen.getByText('aa:bb:cc:dd:ee:01')).toBeInTheDocument()
    expect(screen.getByText('Kids')).toBeInTheDocument()
    expect(screen.getByText('No profile')).toBeInTheDocument()
  })
})

describe('DevicesPage — add', () => {
  it('opens modal with default profile, fills fields, and calls upsert', async () => {
    const user = userEvent.setup()
    renderPage()
    await screen.findByText("Kid's iPad")
    await user.click(screen.getByRole('button', { name: /\+ Add Device/ }))

    await user.type(screen.getByPlaceholderText('aa:bb:cc:dd:ee:ff'), 'aa:bb:cc:dd:ee:99')
    await user.type(screen.getByPlaceholderText("Kid's iPad"), 'New Phone')

    await user.selectOptions(screen.getByRole('combobox'), '2')

    await user.click(screen.getByRole('button', { name: /^Save$/ }))

    await waitFor(() =>
      expect(api.devices.upsert).toHaveBeenCalledWith({
        mac: 'aa:bb:cc:dd:ee:99',
        name: 'New Phone',
        profileId: 2,
      })
    )
    await waitFor(() => expect(api.devices.list).toHaveBeenCalledTimes(2))
  })
})

describe('DevicesPage — edit', () => {
  it('pre-fills modal with existing device values', async () => {
    const user = userEvent.setup()
    renderPage()
    const ipadRow = await screen.findByTestId('device-row-aa:bb:cc:dd:ee:01')
    await user.click(within(ipadRow).getByRole('button', { name: /Edit/ }))

    expect(screen.getByDisplayValue('aa:bb:cc:dd:ee:01')).toBeInTheDocument()
    expect(screen.getByDisplayValue("Kid's iPad")).toBeInTheDocument()
  })
})

describe('DevicesPage — delete', () => {
  it('confirms then calls api.devices.delete', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    renderPage()
    const ipadRow = await screen.findByTestId('device-row-aa:bb:cc:dd:ee:01')
    await user.click(within(ipadRow).getByRole('button', { name: /Remove/ }))
    expect(confirmSpy).toHaveBeenCalled()
    await waitFor(() => expect(api.devices.delete).toHaveBeenCalledWith('aa:bb:cc:dd:ee:01'))
    confirmSpy.mockRestore()
  })

  it('does not call delete when confirm is cancelled', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const user = userEvent.setup()
    renderPage()
    const ipadRow = await screen.findByTestId('device-row-aa:bb:cc:dd:ee:01')
    await user.click(within(ipadRow).getByRole('button', { name: /Remove/ }))
    expect(api.devices.delete).not.toHaveBeenCalled()
    confirmSpy.mockRestore()
  })
})

describe('DevicesPage — highlight from ?mac= (#298)', () => {
  it('rings the matching device row when ?mac=... is set', async () => {
    // jsdom doesn't implement scrollIntoView — stub to avoid a runtime throw.
    HTMLElement.prototype.scrollIntoView = vi.fn()
    renderPage(['/devices?mac=aa:bb:cc:dd:ee:01'])
    const row = await screen.findByTestId('device-row-aa:bb:cc:dd:ee:01')
    await waitFor(() => expect(row.className).toContain('ring-emerald-500'))
  })

  it('does not ring any row when ?mac= is not set', async () => {
    renderPage()
    const row = await screen.findByTestId('device-row-aa:bb:cc:dd:ee:01')
    expect(row.className).not.toContain('ring-emerald-500')
  })
})

describe('DevicesPage — role gating', () => {
  it('hides admin-only buttons for non-admins', async () => {
    mockAuth = { isAdmin: false }
    renderPage()
    await screen.findByText("Kid's iPad")
    expect(screen.queryByRole('button', { name: /\+ Add Device/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Edit/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Remove/ })).not.toBeInTheDocument()
  })
})

describe('DevicesPage — new-device alerts banner (#711)', () => {
  const alert = {
    id: 42,
    kind: 'new_device' as const,
    status: 'pending' as const,
    mac: 'aa:bb:cc:99:99:99',
    deviceName: 'device-999999',
    profileId: null,
    profileName: null,
    host: null,
    requestKind: null,
    note: null,
    grantedMinutes: null,
    createdAt: '2026-05-22T12:00:00Z',
    decidedAt: null,
    decidedBy: null,
  }

  it('does not render banner when there are no pending alerts', async () => {
    renderPage()
    await screen.findByText("Kid's iPad")
    expect(screen.queryByTestId('new-device-alerts-banner')).not.toBeInTheDocument()
  })

  it('renders the banner with the MAC + Dismiss when an alert is pending', async () => {
    (api.alerts.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([alert])
    renderPage()
    const banner = await screen.findByTestId('new-device-alerts-banner')
    expect(within(banner).getByText('aa:bb:cc:99:99:99')).toBeInTheDocument()
    expect(within(banner).getByRole('button', { name: /Dismiss/ })).toBeInTheDocument()
  })

  it('clicks Dismiss → calls api.alerts.approve(id) and refetches', async () => {
    (api.alerts.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([alert])
    const user = userEvent.setup()
    renderPage()
    await screen.findByTestId('new-device-alerts-banner')
    await user.click(screen.getByRole('button', { name: /Dismiss/ }))
    await waitFor(() => expect(api.alerts.approve).toHaveBeenCalledWith(42))
    // banner refetch invoked
    await waitFor(() => expect(api.alerts.list).toHaveBeenCalledTimes(2))
  })

  it('non-admins see the banner but no Dismiss button', async () => {
    mockAuth = { isAdmin: false }
    ;(api.alerts.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([alert])
    renderPage()
    const banner = await screen.findByTestId('new-device-alerts-banner')
    expect(within(banner).getByText('aa:bb:cc:99:99:99')).toBeInTheDocument()
    expect(within(banner).queryByRole('button', { name: /Dismiss/ })).not.toBeInTheDocument()
  })

  it('shows "Enable browser notifications" when Notification.permission is default', async () => {
    class FakeN { static permission: NotificationPermission = 'default'; static requestPermission = vi.fn(async () => 'granted' as NotificationPermission); constructor() {} }
    // @ts-expect-error inject
    window.Notification = FakeN
    ;(api.alerts.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([alert])
    renderPage()
    await screen.findByTestId('new-device-alerts-banner')
    const btn = await screen.findByTestId('enable-notifications-btn')
    const user = userEvent.setup()
    await user.click(btn)
    expect(FakeN.requestPermission).toHaveBeenCalled()
    // @ts-expect-error cleanup
    delete window.Notification
  })

  describe('inline edit on click (#1052)', () => {
    it('clicking the alert row opens the inline editor with the MAC visible', async () => {
      (api.alerts.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([alert])
      const user = userEvent.setup()
      renderPage()
      await screen.findByTestId('new-device-alerts-banner')
      await user.click(screen.getByTestId(`new-device-alert-row-${alert.mac}`))
      const editor = await screen.findByTestId('new-device-alert-editor')
      expect(within(editor).getByText(alert.mac)).toBeInTheDocument()
      expect(within(editor).getByDisplayValue('device-999999')).toBeInTheDocument()
    })

    it('Save calls PATCH /devices then approve(id) in that order', async () => {
      (api.alerts.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([alert])
      const order: string[] = []
      ;(api.devices.patch as unknown as ReturnType<typeof vi.fn>).mockImplementation(async () => {
        order.push('patch')
      })
      ;(api.alerts.approve as unknown as ReturnType<typeof vi.fn>).mockImplementation(async () => {
        order.push('approve')
      })

      const user = userEvent.setup()
      renderPage()
      await screen.findByTestId('new-device-alerts-banner')
      await user.click(screen.getByTestId(`new-device-alert-row-${alert.mac}`))
      const editor = await screen.findByTestId('new-device-alert-editor')

      const nameInput = within(editor).getByDisplayValue('device-999999')
      await user.clear(nameInput)
      await user.type(nameInput, 'Living Room TV')
      await user.selectOptions(within(editor).getByTestId('new-device-alert-profile'), '2')

      await user.click(within(editor).getByRole('button', { name: /^Save$/ }))

      await waitFor(() =>
        expect(api.devices.patch).toHaveBeenCalledWith(alert.mac, {
          name: 'Living Room TV',
          profileId: 2,
        })
      )
      await waitFor(() => expect(api.alerts.approve).toHaveBeenCalledWith(alert.id))
      expect(order).toEqual(['patch', 'approve'])
    })

    it('Cancel closes editor without dismissing the alert', async () => {
      (api.alerts.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([alert])
      const user = userEvent.setup()
      renderPage()
      await screen.findByTestId('new-device-alerts-banner')
      await user.click(screen.getByTestId(`new-device-alert-row-${alert.mac}`))
      await screen.findByTestId('new-device-alert-editor')
      await user.click(screen.getByRole('button', { name: /^Cancel$/ }))
      await waitFor(() =>
        expect(screen.queryByTestId('new-device-alert-editor')).not.toBeInTheDocument()
      )
      expect(api.devices.patch).not.toHaveBeenCalled()
      expect(api.alerts.approve).not.toHaveBeenCalled()
    })

    it('saving without picking a profile still succeeds (profileId optional, #841)', async () => {
      (api.alerts.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([alert])
      const user = userEvent.setup()
      renderPage()
      await screen.findByTestId('new-device-alerts-banner')
      await user.click(screen.getByTestId(`new-device-alert-row-${alert.mac}`))
      const editor = await screen.findByTestId('new-device-alert-editor')

      const nameInput = within(editor).getByDisplayValue('device-999999')
      await user.clear(nameInput)
      await user.type(nameInput, 'Mystery Device')
      // leave profile select at default '— No profile —'
      await user.click(within(editor).getByRole('button', { name: /^Save$/ }))

      await waitFor(() =>
        expect(api.devices.patch).toHaveBeenCalledWith(alert.mac, { name: 'Mystery Device' })
      )
      await waitFor(() => expect(api.alerts.approve).toHaveBeenCalledWith(alert.id))
    })
  })

  it('hides "Enable browser notifications" when permission is already granted', async () => {
    class FakeN { static permission: NotificationPermission = 'granted'; static requestPermission = vi.fn(); constructor() {} }
    // @ts-expect-error inject
    window.Notification = FakeN
    ;(api.alerts.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([alert])
    renderPage()
    await screen.findByTestId('new-device-alerts-banner')
    expect(screen.queryByTestId('enable-notifications-btn')).not.toBeInTheDocument()
    // @ts-expect-error cleanup
    delete window.Notification
  })
})
