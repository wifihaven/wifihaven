import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { ProfileTimeStatus } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    time: {
      statusAll: vi.fn(),
      grantExtension: vi.fn(),
    },
  },
}))

vi.mock('@/hooks/useAuth', () => ({
  useAuth: () => mockAuth,
}))

import { api } from '@/api/client'
import { TimePage } from './TimePage'

let mockAuth = { isAdmin: true }

const limited: ProfileTimeStatus = {
  profileId: 1,
  profileName: "Kid's iPad",
  date: '2026-05-07',
  dailyLimitMins: 120,
  usedMins: 90,
  extensionMins: 0,
  remainingMins: 30,
  siteUsage: [
    { label: 'YouTube', domainPattern: 'youtube.com', limitMins: 30, usedMins: 30, remainingMins: 0 },
  ],
  devices: [{ deviceMac: 'aa:bb:cc:dd:ee:01', deviceName: "Kid's iPad", usedMins: 90 }],
}

const overLimit: ProfileTimeStatus = {
  profileId: 2,
  profileName: 'Phone',
  date: '2026-05-07',
  dailyLimitMins: 60,
  usedMins: 100,
  extensionMins: 30,
  remainingMins: 0,
  siteUsage: [],
  devices: [{ deviceMac: 'aa:bb:cc:dd:ee:02', deviceName: 'Phone', usedMins: 100 }],
}

const noLimit: ProfileTimeStatus = {
  profileId: 3,
  profileName: 'Laptop',
  date: '2026-05-07',
  dailyLimitMins: null,
  usedMins: 0,
  extensionMins: 0,
  remainingMins: null,
  siteUsage: [],
  devices: [{ deviceMac: 'aa:bb:cc:dd:ee:03', deviceName: 'Laptop', usedMins: 0 }],
}

beforeEach(() => {
  vi.resetAllMocks()
  mockAuth = { isAdmin: true }
  ;(api.time.statusAll as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([limited, overLimit, noLimit])
  ;(api.time.grantExtension as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 1, grantedMinutes: 45 })
})

describe('TimePage — list', () => {
  it('renders cards with usage, remaining, extensions, and site usage', async () => {
    render(<TimePage />)
    expect(await screen.findByText("Kid's iPad")).toBeInTheDocument()
    expect(screen.getByText('90m used')).toBeInTheDocument()
    expect(screen.getByText('30m left')).toBeInTheDocument()
    expect(screen.getByText('YouTube')).toBeInTheDocument()
    // over limit card
    expect(screen.getByText('Phone')).toBeInTheDocument()
    expect(screen.getAllByText('Limit reached').length).toBeGreaterThan(0)
    expect(screen.getByText('+30m extended')).toBeInTheDocument()
    // no-limit card
    expect(screen.getByText('Laptop')).toBeInTheDocument()
    expect(screen.getByText(/No time limit set/)).toBeInTheDocument()
  })
})

describe('TimePage — grant extension', () => {
  it('opens modal, picks 45m preset, types note, and calls grantExtension', async () => {
    const user = userEvent.setup()
    render(<TimePage />)
    await screen.findByText("Kid's iPad")

    // Click first "+ Time" button (Kid's iPad)
    const grantButtons = screen.getAllByRole('button', { name: /\+ Time/ })
    await user.click(grantButtons[0])

    await user.click(screen.getByRole('button', { name: '45m' }))
    await user.type(
      screen.getByPlaceholderText(/Homework finished/),
      'great job',
    )
    await user.click(screen.getByRole('button', { name: /Grant 45m/ }))

    await waitFor(() =>
      expect(api.time.grantExtension).toHaveBeenCalledWith({
        profileId: 1,
        extraMinutes: 45,
        note: 'great job',
      })
    )
    // reload
    await waitFor(() => expect(api.time.statusAll).toHaveBeenCalledTimes(2))
  })

  it('passes null note when blank', async () => {
    const user = userEvent.setup()
    render(<TimePage />)
    await screen.findByText("Kid's iPad")
    const grantButtons = screen.getAllByRole('button', { name: /\+ Time/ })
    await user.click(grantButtons[0])
    await user.click(screen.getByRole('button', { name: /Grant 30m/ }))
    await waitFor(() =>
      expect(api.time.grantExtension).toHaveBeenCalledWith({
        profileId: 1,
        extraMinutes: 30,
        note: null,
      })
    )
  })
})

describe('TimePage — role gating', () => {
  it('hides "+ Time" button for non-admins', async () => {
    mockAuth = { isAdmin: false }
    render(<TimePage />)
    await screen.findByText("Kid's iPad")
    expect(screen.queryByRole('button', { name: /\+ Time/ })).not.toBeInTheDocument()
  })
})
