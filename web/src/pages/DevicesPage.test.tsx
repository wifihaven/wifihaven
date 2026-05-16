import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import type { Device, ProfileDetail } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    devices: {
      list: vi.fn(),
      upsert: vi.fn(),
      delete: vi.fn(),
    },
    profiles: {
      list: vi.fn(),
    },
  },
}))

vi.mock('@/hooks/useAuth', () => ({
  useAuth: () => mockAuth,
}))

import { api } from '@/api/client'
import { DevicesPage } from './DevicesPage'

function renderPage(initialEntries: string[] = ['/devices']) {
  return render(
    <MemoryRouter initialEntries={initialEntries}>
      <DevicesPage />
    </MemoryRouter>,
  )
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
  profile: { id: 1, name: 'Kids', blockedCategories: [], extraBlocked: [], extraAllowed: [], paused: false, failureMode: 'block-all' },
  schedules: [], timeLimit: null, siteTimeLimits: [],
}
const adultsProfile: ProfileDetail = {
  profile: { id: 2, name: 'Adults', blockedCategories: [], extraBlocked: [], extraAllowed: [], paused: false, failureMode: 'last-known-good' },
  schedules: [], timeLimit: null, siteTimeLimits: [],
}

beforeEach(() => {
  vi.resetAllMocks()
  mockAuth = { isAdmin: true }
  ;(api.devices.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([ipad, laptop])
  ;(api.profiles.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([kidsProfile, adultsProfile])
  ;(api.devices.upsert as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 99 })
  ;(api.devices.delete as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
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
