import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { Device, ProfileDetail, User } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    profiles: {
      list: vi.fn(),
      create: vi.fn(),
      update: vi.fn(),
      delete: vi.fn(),
      pause: vi.fn(),
      setUsers: vi.fn(),
    },
    blocklists: {
      counts: vi.fn(),
    },
    devices: {
      list: vi.fn(),
    },
    users: {
      list: vi.fn(),
    },
  },
}))

vi.mock('@/hooks/useAuth', () => ({
  useAuth: () => mockAuth,
}))

import { api } from '@/api/client'
import { ProfilesPage } from './ProfilesPage'

let mockAuth = { isAdmin: true }

const kidsProfile: ProfileDetail = {
  profile: {
    id: 1,
    name: 'Kids',
    blockedCategories: ['adult', 'gambling'],
    extraBlocked: ['bad.com', 'evil.com'],
    extraAllowed: ['school.com'],
    paused: false,
  },
  schedules: [
    { id: 10, profileId: 1, name: 'Bedtime', days: ['mon', 'tue'], blockFrom: '21:00', blockUntil: '07:00' },
  ],
  timeLimit: { id: 5, profileId: 1, dailyMinutes: 120 },
  siteTimeLimits: [
    { id: 7, profileId: 1, domainPattern: 'youtube.com', dailyMinutes: 30, label: 'YouTube', exemptFromDaily: true },
  ],
}

const adultsProfile: ProfileDetail = {
  profile: {
    id: 2,
    name: 'Adults',
    blockedCategories: [],
    extraBlocked: [],
    extraAllowed: [],
    paused: true,
  },
  schedules: [],
  timeLimit: null,
  siteTimeLimits: [],
}

const phoneDevice: Device = {
  id: 100, mac: 'aa:bb:cc:dd:ee:01', name: 'Kid Phone', profileId: 1,
  profileName: 'Kids', lastSeenIp: null, lastSeenAt: null,
}
const tabletDevice: Device = {
  id: 101, mac: 'aa:bb:cc:dd:ee:02', name: 'Adult Tablet', profileId: 2,
  profileName: 'Adults', lastSeenIp: null, lastSeenAt: null,
}

const aliceUser: User = { id: 10, username: 'alice', role: 'child', profileIds: [1] }
const bobUser:   User = { id: 11, username: 'bob',   role: 'adult', profileIds: [2] }
const carolUser: User = { id: 12, username: 'carol', role: 'admin', profileIds: [1, 2] }

beforeEach(() => {
  vi.resetAllMocks()
  mockAuth = { isAdmin: true }
  ;(api.profiles.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([kidsProfile, adultsProfile])
  ;(api.blocklists.counts as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([
    { category: 'adult', count: 1000 },
    { category: 'gambling', count: 500 },
    { category: 'social', count: 200 },
  ])
  ;(api.profiles.create as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 99 })
  ;(api.profiles.update as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
  ;(api.profiles.delete as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
  ;(api.profiles.pause as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ paused: true })
  ;(api.profiles.setUsers as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
  ;(api.devices.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([phoneDevice, tabletDevice])
  ;(api.users.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([aliceUser, bobUser, carolUser])
})

describe('ProfilesPage — list', () => {
  it('renders profile names, paused badge, blocked categories, schedules, site limits, and daily limit', async () => {
    render(<ProfilesPage />)
    const kidsCard = await screen.findByTestId('profile-card-1')
    expect(within(kidsCard).getByText('Kids')).toBeInTheDocument()
    expect(screen.getByText('Adults')).toBeInTheDocument()
    expect(screen.getByText('Paused')).toBeInTheDocument()
    // 'adult' (the blocked category) lives inside the Kids card; the bob/admin role
    // badges read 'adult' too, so scope this lookup to the Kids card.
    expect(within(kidsCard).getByText('adult')).toBeInTheDocument()
    expect(within(kidsCard).getByText('gambling')).toBeInTheDocument()
    expect(screen.getByText('Bedtime')).toBeInTheDocument()
    expect(screen.getByText('21:00 → 07:00')).toBeInTheDocument()
    expect(screen.getByText('YouTube')).toBeInTheDocument()
    expect(screen.getByText('30m · youtube.com')).toBeInTheDocument()
    expect(screen.getByText('120 min')).toBeInTheDocument()
  })
})

describe('ProfilesPage — pause / delete', () => {
  it('clicking Pause/Resume calls api.profiles.pause and reloads', async () => {
    const user = userEvent.setup()
    render(<ProfilesPage />)
    const kidsCard = await screen.findByTestId('profile-card-1')
    await user.click(within(kidsCard).getByRole('button', { name: /Pause/ }))
    await waitFor(() => expect(api.profiles.pause).toHaveBeenCalledWith(1))
    await waitFor(() => expect(api.profiles.list).toHaveBeenCalledTimes(2))
  })

  it('confirms then calls api.profiles.delete', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    render(<ProfilesPage />)
    const kidsCard = await screen.findByTestId('profile-card-1')
    await user.click(within(kidsCard).getByRole('button', { name: /^Delete$/ }))
    expect(confirmSpy).toHaveBeenCalled()
    await waitFor(() => expect(api.profiles.delete).toHaveBeenCalledWith(1))
    confirmSpy.mockRestore()
  })

  it('does not delete when confirm is cancelled', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const user = userEvent.setup()
    render(<ProfilesPage />)
    const kidsCard = await screen.findByTestId('profile-card-1')
    await user.click(within(kidsCard).getByRole('button', { name: /^Delete$/ }))
    expect(api.profiles.delete).not.toHaveBeenCalled()
    confirmSpy.mockRestore()
  })
})

describe('ProfilesPage — create', () => {
  it('shows validation error when name is empty', async () => {
    const user = userEvent.setup()
    render(<ProfilesPage />)
    await screen.findByText('Kids')
    await user.click(screen.getByRole('button', { name: /\+ New Profile/ }))
    await user.click(screen.getByRole('button', { name: /^Save$/ }))
    expect(await screen.findByText(/Name is required/i)).toBeInTheDocument()
    expect(api.profiles.create).not.toHaveBeenCalled()
  })

  it('fills the editor and calls api.profiles.create with the expected body', async () => {
    const user = userEvent.setup()
    render(<ProfilesPage />)
    await screen.findByText('Kids')
    await user.click(screen.getByRole('button', { name: /\+ New Profile/ }))

    await user.type(screen.getByPlaceholderText('Kids'), 'Teens')

    // toggle category "social"
    await user.click(screen.getByRole('button', { name: 'social' }))

    // extra blocked / allowed (split lines, trim)
    const blockedTa = screen.getAllByPlaceholderText('One domain per line')[0]
    const allowedTa = screen.getAllByPlaceholderText('One domain per line')[1]
    await user.type(blockedTa, '  tiktok.com  \n  insta.com  ')
    await user.type(allowedTa, 'khan.org')

    // daily limit
    await user.type(screen.getByPlaceholderText('Leave blank for unlimited'), '90')

    // add schedule (default Bedtime, all 7 days, 21:00 → 07:00); toggle "sun" off
    await user.click(screen.getByRole('button', { name: /\+ Add schedule/ }))
    await user.click(screen.getByRole('button', { name: 'sun' }))

    // add site limit
    await user.click(screen.getByRole('button', { name: /\+ Add site limit/ }))
    await user.type(screen.getByPlaceholderText('YouTube'), 'YouTube')
    await user.type(screen.getByPlaceholderText('youtube.com'), 'youtube.com')

    await user.click(screen.getByRole('button', { name: /^Save$/ }))

    await waitFor(() => expect(api.profiles.create).toHaveBeenCalledTimes(1))
    expect(api.profiles.create).toHaveBeenCalledWith({
      name: 'Teens',
      blockedCategories: ['social'],
      extraBlocked: ['tiktok.com', 'insta.com'],
      extraAllowed: ['khan.org'],
      paused: false,
      timeLimit: 90,
      schedules: [
        { name: 'Bedtime', days: ['mon', 'tue', 'wed', 'thu', 'fri', 'sat'], blockFrom: '21:00', blockUntil: '07:00' },
      ],
      siteTimeLimits: [
        { label: 'YouTube', domainPattern: 'youtube.com', dailyMinutes: 30, exemptFromDaily: true },
      ],
    })
    await waitFor(() => expect(api.profiles.list).toHaveBeenCalledTimes(2))
  })
})

describe('ProfilesPage — edit', () => {
  it('pre-fills editor from selected profile and calls api.profiles.update', async () => {
    const user = userEvent.setup()
    render(<ProfilesPage />)
    const kidsCard = await screen.findByTestId('profile-card-1')
    await user.click(within(kidsCard).getByRole('button', { name: /^Edit$/ }))

    expect(screen.getByDisplayValue('Kids')).toBeInTheDocument()
    expect(screen.getByDisplayValue('120')).toBeInTheDocument()
    expect(screen.getByDisplayValue('school.com')).toBeInTheDocument()

    // #265: Paused checkbox helper text must describe *all internet traffic*, not just DNS.
    expect(
      screen.getByText(/blocks all internet traffic for devices on this profile/i),
    ).toBeInTheDocument()
    expect(screen.queryByText(/blocks all DNS/i)).not.toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /^Save$/ }))

    await waitFor(() => expect(api.profiles.update).toHaveBeenCalledTimes(1))
    expect(api.profiles.update).toHaveBeenCalledWith(1, {
      name: 'Kids',
      blockedCategories: ['adult', 'gambling'],
      extraBlocked: ['bad.com', 'evil.com'],
      extraAllowed: ['school.com'],
      paused: false,
      timeLimit: 120,
      schedules: [
        { name: 'Bedtime', days: ['mon', 'tue'], blockFrom: '21:00', blockUntil: '07:00' },
      ],
      siteTimeLimits: [
        { domainPattern: 'youtube.com', dailyMinutes: 30, label: 'YouTube', exemptFromDaily: true },
      ],
    })
  })
})

describe('ProfilesPage — role gating', () => {
  it('hides admin-only buttons for non-admins', async () => {
    mockAuth = { isAdmin: false }
    render(<ProfilesPage />)
    await screen.findByText('Kids')
    expect(screen.queryByRole('button', { name: /\+ New Profile/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Pause/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Edit$/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Delete$/ })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Edit users/ })).not.toBeInTheDocument()
  })
})

describe('ProfilesPage — devices section', () => {
  it('renders devices grouped under their profile', async () => {
    render(<ProfilesPage />)
    const kidsCard = await screen.findByTestId('profile-card-1')
    const adultsCard = screen.getByTestId('profile-card-2')

    expect(within(kidsCard).getByTestId('profile-device-100')).toHaveTextContent('Kid Phone')
    expect(within(kidsCard).getByTestId('profile-device-100')).toHaveTextContent('aa:bb:cc:dd:ee:01')
    expect(within(kidsCard).queryByTestId('profile-device-101')).not.toBeInTheDocument()

    expect(within(adultsCard).getByTestId('profile-device-101')).toHaveTextContent('Adult Tablet')
  })
})

describe('ProfilesPage — linked users section', () => {
  it('renders linked users for each profile (admin view)', async () => {
    render(<ProfilesPage />)
    const kidsCard = await screen.findByTestId('profile-card-1')
    const adultsCard = screen.getByTestId('profile-card-2')

    expect(within(kidsCard).getByTestId('profile-user-10')).toHaveTextContent('alice')
    expect(within(kidsCard).getByTestId('profile-user-12')).toHaveTextContent('carol')
    expect(within(kidsCard).queryByTestId('profile-user-11')).not.toBeInTheDocument()

    expect(within(adultsCard).getByTestId('profile-user-11')).toHaveTextContent('bob')
    expect(within(adultsCard).getByTestId('profile-user-12')).toHaveTextContent('carol')
  })

  it('hides the linked-users section entirely for non-admins', async () => {
    mockAuth = { isAdmin: false }
    render(<ProfilesPage />)
    await screen.findByText('Kids')
    expect(screen.queryByTestId('profile-users-1')).not.toBeInTheDocument()
    expect(screen.queryByTestId('profile-users-2')).not.toBeInTheDocument()
    expect(api.users.list).not.toHaveBeenCalled()
  })

  it('admin clicks Edit users → modal opens with current users pre-checked → Save calls api.profiles.setUsers', async () => {
    const user = userEvent.setup()
    render(<ProfilesPage />)
    const kidsCard = await screen.findByTestId('profile-card-1')
    await user.click(within(kidsCard).getByRole('button', { name: /Edit users/ }))

    const modal = await screen.findByTestId('edit-users-modal')
    // Pre-checked: alice (id 10) and carol (id 12) on Kids
    expect(within(modal).getByTestId('user-pick-10').textContent).toMatch(/✓/)
    expect(within(modal).getByTestId('user-pick-12').textContent).toMatch(/✓/)
    expect(within(modal).getByTestId('user-pick-11').textContent).not.toMatch(/✓/)

    // Toggle alice off, bob on.
    await user.click(within(modal).getByTestId('user-pick-10'))
    await user.click(within(modal).getByTestId('user-pick-11'))

    await user.click(within(modal).getByRole('button', { name: /^Save$/ }))

    await waitFor(() => expect(api.profiles.setUsers).toHaveBeenCalledTimes(1))
    const call = (api.profiles.setUsers as unknown as ReturnType<typeof vi.fn>).mock.calls[0]
    expect(call[0]).toBe(1)
    expect([...call[1]].sort((a, b) => a - b)).toEqual([11, 12])
  })
})
