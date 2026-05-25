import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import type { Device, ProfileDetail, User } from '@/types/api'
import { withQuery } from '@/test/queryWrapper'

vi.mock('@/api/client', () => ({
  api: {
    profiles: {
      list: vi.fn(),
      create: vi.fn(),
      update: vi.fn(),
      delete: vi.fn(),
      setUsers: vi.fn(),
    },
    blocklists: {
      list: vi.fn(),
    },
    devices: {
      list: vi.fn(),
    },
    users: {
      list: vi.fn(),
    },
    household: {
      get: vi.fn(),
    },
    apps: {
      list: vi.fn(),
      setPolicy: vi.fn(),
      deletePolicy: vi.fn(),
    },
  },
}))

vi.mock('@/hooks/useAuth', () => ({
  useAuth: () => mockAuth,
}))

import { api } from '@/api/client'
import { ProfilesPage } from './ProfilesPage'

function renderPage(initialEntries: string[] = ['/profiles']) {
  return render(withQuery(
    <MemoryRouter initialEntries={initialEntries}>
      <ProfilesPage />
    </MemoryRouter>,
  ))
}

let mockAuth = { isAdmin: true }

const kidsProfile: ProfileDetail = {
  profile: {
    id: 1,
    name: 'Kids',
    blockedCategories: ['adult', 'gambling'],
    extraBlocked: ['bad.com', 'evil.com'],
    extraAllowed: ['school.com'],
    paused: false,
    failureMode: 'block-all',
    crossDeviceOverlapMode: 'sum',
  },
  schedules: [
    { id: 10, profileId: 1, name: 'Bedtime', days: ['mon', 'tue'], startLocal: '21:00', endLocal: '07:00', tz: 'UTC' },
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
    failureMode: 'last-known-good',
    crossDeviceOverlapMode: 'sum',
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
  ;(api.blocklists.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([
    { id: 'adult', name: 'Adult', bundled: true, hostCount: 1000 },
    { id: 'gambling', name: 'Gambling', bundled: true, hostCount: 500 },
    { id: 'social', name: 'Social', bundled: true, hostCount: 200 },
  ])
  ;(api.profiles.create as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 99 })
  ;(api.profiles.update as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
  ;(api.profiles.delete as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
  ;(api.profiles.setUsers as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
  ;(api.devices.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([phoneDevice, tabletDevice])
  ;(api.users.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([aliceUser, bobUser, carolUser])
  ;(api.household.get as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
    dailyResetTime: '00:00',
    dailyResetTz: 'America/Los_Angeles',
  })
  ;(api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
  ;(api.apps.setPolicy as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
  ;(api.apps.deletePolicy as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(undefined)
})

describe('ProfilesPage — list', () => {
  it('renders profile names, paused badge, blocked categories, schedules, site limits, and daily limit', async () => {
    renderPage()
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
  // #406: pause is now an explicit PUT with the full profile + paused=!current.
  // The previous POST /pause endpoint was a server-side toggle (race-prone).
  it('clicking Pause calls api.profiles.update with paused=true and reloads', async () => {
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await user.click(within(kidsCard).getByRole('button', { name: /Pause/ }))
    await waitFor(() =>
      expect(api.profiles.update).toHaveBeenCalledWith(
        1,
        expect.objectContaining({ paused: true, name: 'Kids' }),
      ),
    )
    await waitFor(() => expect(api.profiles.list).toHaveBeenCalledTimes(2))
  })

  it('clicking Resume calls api.profiles.update with paused=false', async () => {
    const user = userEvent.setup()
    renderPage()
    const adultsCard = await screen.findByTestId('profile-card-2')
    await user.click(within(adultsCard).getByRole('button', { name: /Resume/ }))
    await waitFor(() =>
      expect(api.profiles.update).toHaveBeenCalledWith(
        2,
        expect.objectContaining({ paused: false, name: 'Adults' }),
      ),
    )
  })

  it('confirms then calls api.profiles.delete', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true)
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await user.click(within(kidsCard).getByRole('button', { name: /^Delete$/ }))
    expect(confirmSpy).toHaveBeenCalled()
    await waitFor(() => expect(api.profiles.delete).toHaveBeenCalledWith(1))
    confirmSpy.mockRestore()
  })

  it('does not delete when confirm is cancelled', async () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false)
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await user.click(within(kidsCard).getByRole('button', { name: /^Delete$/ }))
    expect(api.profiles.delete).not.toHaveBeenCalled()
    confirmSpy.mockRestore()
  })
})

describe('ProfilesPage — create', () => {
  it('shows validation error when name is empty', async () => {
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('Kids')
    await user.click(screen.getByRole('button', { name: /\+ New Profile/ }))
    await user.click(screen.getByRole('button', { name: /^Save$/ }))
    expect(await screen.findByText(/Name is required/i)).toBeInTheDocument()
    expect(api.profiles.create).not.toHaveBeenCalled()
  })

  it('fills the editor and calls api.profiles.create with the expected body', async () => {
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('Kids')
    await user.click(screen.getByRole('button', { name: /\+ New Profile/ }))

    await user.type(screen.getByPlaceholderText('Kids'), 'Teens')

    // toggle category "social" (label uses display name)
    await user.click(screen.getByRole('button', { name: 'Social' }))

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
        { name: 'Bedtime', days: ['mon', 'tue', 'wed', 'thu', 'fri', 'sat'], startLocal: '21:00', endLocal: '07:00', tz: 'America/Los_Angeles' },
      ],
      siteTimeLimits: [
        { label: 'YouTube', domainPattern: 'youtube.com', dailyMinutes: 30, exemptFromDaily: true },
      ],
      // #385: form default for a brand-new profile is LastKnownGood
      // (matches DB column default; UI copy steers admins towards BlockAll
      // for child profiles).
      failureMode: 'last-known-good',
      // #751: form default for a brand-new profile is Sum (matches DB
      // column default; admins opt in to Dedup explicitly when one
      // profile represents one human with multiple devices).
      crossDeviceOverlapMode: 'sum',
    })
    await waitFor(() => expect(api.profiles.list).toHaveBeenCalledTimes(2))
  })
})

describe('ProfilesPage — edit', () => {
  it('pre-fills editor from selected profile and calls api.profiles.update', async () => {
    const user = userEvent.setup()
    renderPage()
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
        { name: 'Bedtime', days: ['mon', 'tue'], startLocal: '21:00', endLocal: '07:00', tz: 'UTC' },
      ],
      siteTimeLimits: [
        { domainPattern: 'youtube.com', dailyMinutes: 30, label: 'YouTube', exemptFromDaily: true },
      ],
      // #385: edit preserves the existing failureMode unless changed.
      failureMode: 'block-all',
      // #751: edit round-trips the existing crossDeviceOverlapMode.
      crossDeviceOverlapMode: 'sum',
    })
  })
})

describe('ProfilesPage — cross-device overlap toggle (#751)', () => {
  it('round-trips the dedup selection to api.profiles.update', async () => {
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await user.click(within(kidsCard).getByRole('button', { name: /^Edit$/ }))

    expect(screen.getByTestId('profile-overlap-mode-sum')).toBeChecked()
    expect(screen.getByTestId('profile-overlap-mode-dedup')).not.toBeChecked()

    await user.click(screen.getByTestId('profile-overlap-mode-dedup'))
    expect(screen.getByTestId('profile-overlap-mode-dedup')).toBeChecked()

    await user.click(screen.getByRole('button', { name: /^Save$/ }))

    await waitFor(() => expect(api.profiles.update).toHaveBeenCalledTimes(1))
    expect(api.profiles.update).toHaveBeenCalledWith(
      1,
      expect.objectContaining({ crossDeviceOverlapMode: 'dedup' }),
    )
  })
})

describe('ProfilesPage — role gating', () => {
  it('hides admin-only buttons for non-admins', async () => {
    mockAuth = { isAdmin: false }
    renderPage()
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
    renderPage()
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
    renderPage()
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
    renderPage()
    await screen.findByText('Kids')
    expect(screen.queryByTestId('profile-users-1')).not.toBeInTheDocument()
    expect(screen.queryByTestId('profile-users-2')).not.toBeInTheDocument()
    expect(api.users.list).not.toHaveBeenCalled()
  })

  it('admin clicks Edit users → modal opens with current users pre-checked → Save calls api.profiles.setUsers', async () => {
    const user = userEvent.setup()
    renderPage()
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

describe('ProfilesPage — #385 failureMode (three modes)', () => {
  it('edit form pre-fills failureMode from the profile (BlockAll for Kids)', async () => {
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await user.click(within(kidsCard).getByRole('button', { name: /^Edit$/ }))
    const blockAll      = screen.getByTestId('profile-failure-mode-block-all')       as HTMLInputElement
    const lastKnownGood = screen.getByTestId('profile-failure-mode-last-known-good') as HTMLInputElement
    const allowAll      = screen.getByTestId('profile-failure-mode-allow-all')       as HTMLInputElement
    expect(blockAll.checked).toBe(true)
    expect(lastKnownGood.checked).toBe(false)
    expect(allowAll.checked).toBe(false)
  })

  it('edit form pre-fills failureMode from the profile (LastKnownGood for Adults)', async () => {
    const user = userEvent.setup()
    renderPage()
    const adultsCard = await screen.findByTestId('profile-card-2')
    await user.click(within(adultsCard).getByRole('button', { name: /^Edit$/ }))
    const lastKnownGood = screen.getByTestId('profile-failure-mode-last-known-good') as HTMLInputElement
    const blockAll      = screen.getByTestId('profile-failure-mode-block-all')       as HTMLInputElement
    expect(lastKnownGood.checked).toBe(true)
    expect(blockAll.checked).toBe(false)
  })

  it('selecting AllowAll and saving sends the new value', async () => {
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await user.click(within(kidsCard).getByRole('button', { name: /^Edit$/ }))
    await user.click(screen.getByTestId('profile-failure-mode-allow-all'))
    await user.click(screen.getByRole('button', { name: /^Save$/ }))
    await waitFor(() => expect(api.profiles.update).toHaveBeenCalledTimes(1))
    const call = (api.profiles.update as unknown as ReturnType<typeof vi.fn>).mock.calls[0]
    expect(call[1].failureMode).toBe('allow-all')
  })

  it('selecting LastKnownGood and saving sends the new value', async () => {
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await user.click(within(kidsCard).getByRole('button', { name: /^Edit$/ }))
    await user.click(screen.getByTestId('profile-failure-mode-last-known-good'))
    await user.click(screen.getByRole('button', { name: /^Save$/ }))
    await waitFor(() => expect(api.profiles.update).toHaveBeenCalledTimes(1))
    const call = (api.profiles.update as unknown as ReturnType<typeof vi.fn>).mock.calls[0]
    expect(call[1].failureMode).toBe('last-known-good')
  })

  it('new profile form defaults to LastKnownGood (column default)', async () => {
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('Kids')
    await user.click(screen.getByRole('button', { name: /\+ New Profile/ }))
    const lastKnownGood = screen.getByTestId('profile-failure-mode-last-known-good') as HTMLInputElement
    expect(lastKnownGood.checked).toBe(true)
  })

  it('renders explanatory copy for each of the three modes', async () => {
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await user.click(within(kidsCard).getByRole('button', { name: /^Edit$/ }))
    expect(
      screen.getByText(/drop all forwarded traffic for this profile's devices/i),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/keep enforcing the cached snapshot exactly/i),
    ).toBeInTheDocument()
    expect(
      screen.getByText(/clear every block for this profile's devices/i),
    ).toBeInTheDocument()
  })
})

describe('ProfilesPage — highlight from ?id= (#298)', () => {
  it('rings the matching profile card when ?id=... is set', async () => {
    HTMLElement.prototype.scrollIntoView = vi.fn()
    renderPage(['/profiles?id=1'])
    const card = await screen.findByTestId('profile-card-1')
    await waitFor(() => expect(card.className).toContain('ring-emerald-500'))
  })

  it('does not ring any card when ?id= is not set', async () => {
    renderPage()
    const card = await screen.findByTestId('profile-card-1')
    expect(card.className).not.toContain('ring-emerald-500')
  })
})

describe('ProfilesPage — apps section (#767)', () => {
  const youtube = {
    app: { id: 50, name: 'YouTube', slug: 'youtube', templateId: null, icon: '📺', createdAt: '2026-01-01' },
    hosts: ['youtube.com'],
    assignments: [] as { id: number; appId: number; profileId: number; mode: 'blocked'|'allowed'|'time_limited'; dailyMinutes: number|null; exemptFromDaily: boolean }[],
  }
  const tiktok = {
    app: { id: 51, name: 'TikTok', slug: 'tiktok', templateId: null, icon: '🎵', createdAt: '2026-01-01' },
    hosts: ['tiktok.com', 'www.tiktok.com'],
    assignments: [
      { id: 1, appId: 51, profileId: 1, mode: 'blocked' as const, dailyMinutes: null, exemptFromDaily: true },
    ],
  }

  it('empty state with link to /apps when no apps exist', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await user.click(within(kidsCard).getByRole('button', { name: /^Edit$/ }))
    const section = await screen.findByTestId('apps-section')
    expect(within(section).getByText(/No apps yet/i)).toBeInTheDocument()
    expect(within(section).getByTestId('apps-section-empty-link')).toHaveAttribute('href', '/apps')
  })

  it('renders one row per app and reflects current assignment', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([youtube, tiktok])
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await user.click(within(kidsCard).getByRole('button', { name: /^Edit$/ }))
    await screen.findByTestId('app-row-50')
    expect(screen.getByTestId('app-row-51')).toBeInTheDocument()
    // TikTok is currently blocked for profile 1; the block button shows checked state.
    const tiktokBlock = screen.getByTestId('app-row-51-block')
    expect(tiktokBlock.textContent).toMatch(/✓/)
  })

  it('clicking block calls setPolicy with mode=blocked', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([youtube])
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await user.click(within(kidsCard).getByRole('button', { name: /^Edit$/ }))
    await user.click(await screen.findByTestId('app-row-50-block'))
    await waitFor(() =>
      expect(api.apps.setPolicy).toHaveBeenCalledWith(50, 1, { mode: 'blocked', dailyMinutes: null }),
    )
  })

  it('clicking allow calls setPolicy with mode=allowed', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([youtube])
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await user.click(within(kidsCard).getByRole('button', { name: /^Edit$/ }))
    await user.click(await screen.findByTestId('app-row-50-allow'))
    await waitFor(() =>
      expect(api.apps.setPolicy).toHaveBeenCalledWith(50, 1, { mode: 'allowed', dailyMinutes: null }),
    )
  })

  it('time-limit requires positive minutes; rejects empty', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([youtube])
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await user.click(within(kidsCard).getByRole('button', { name: /^Edit$/ }))
    await user.click(await screen.findByTestId('app-row-50-time-limit'))
    expect(api.apps.setPolicy).not.toHaveBeenCalled()
    expect(await screen.findByTestId('app-row-50-error')).toHaveTextContent(/minutes > 0/i)
  })

  it('time-limit with minutes calls setPolicy with mode=time_limited + dailyMinutes', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([youtube])
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await user.click(within(kidsCard).getByRole('button', { name: /^Edit$/ }))
    const input = await screen.findByTestId('app-row-50-minutes')
    await user.type(input, '45')
    await user.click(screen.getByTestId('app-row-50-time-limit'))
    await waitFor(() =>
      expect(api.apps.setPolicy).toHaveBeenCalledWith(50, 1, { mode: 'time_limited', dailyMinutes: 45 }),
    )
  })

  it('clearing an assigned app calls deletePolicy', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([tiktok])
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await user.click(within(kidsCard).getByRole('button', { name: /^Edit$/ }))
    await user.click(await screen.findByTestId('app-row-51-clear'))
    await waitFor(() =>
      expect(api.apps.deletePolicy).toHaveBeenCalledWith(51, 1),
    )
  })

  it('for a brand-new profile, shows save-first hint instead of rows', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([youtube])
    const user = userEvent.setup()
    renderPage()
    await screen.findByText('Kids')
    await user.click(screen.getByRole('button', { name: /\+ New Profile/ }))
    const section = await screen.findByTestId('apps-section')
    expect(within(section).getByText(/Save this profile first to assign apps/i)).toBeInTheDocument()
    expect(screen.queryByTestId('app-row-50')).not.toBeInTheDocument()
  })

  it('Manage apps link points at /apps', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([youtube])
    const user = userEvent.setup()
    renderPage()
    const kidsCard = await screen.findByTestId('profile-card-1')
    await user.click(within(kidsCard).getByRole('button', { name: /^Edit$/ }))
    expect(await screen.findByTestId('apps-section-manage-link')).toHaveAttribute('href', '/apps')
  })
})
