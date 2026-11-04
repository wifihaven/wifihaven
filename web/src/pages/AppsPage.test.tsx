import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AppDetail, ProfileDetail } from '@/types/api'
import { withQuery } from '@/test/queryWrapper'

// #1798 — AppsPage is read-only: app definitions are authored via the built-in
// template library, not the SPA. The page lists apps + hosts so operators can
// see what they're assigning; the create/edit/delete client wrappers are gone.
vi.mock('@/api/client', () => ({
  api: {
    apps: {
      list: vi.fn(),
    },
    profiles: {
      list: vi.fn(),
    },
    devices: {
      list: vi.fn(),
    },
    blocklists: {
      list: vi.fn(),
    },
    appGroups: {
      list: vi.fn(),
      create: vi.fn(),
      update: vi.fn(),
    },
  },
}))

import { api } from '@/api/client'
import { AppsPage } from './AppsPage'

function makeProfile(id: number, name: string): ProfileDetail {
  return {
    profile: {
      id, name,
      blockedCategories: [],
      paused: false,
      failureMode: 'last-known-good',
      crossDeviceOverlapMode: 'sum',
      pauseMode: 'soft',
      defaultDeny: false,
    },
    timeLimit: null,
  }
}

const kidsProfile = makeProfile(1, 'Kids')
const adultsProfile = makeProfile(2, 'Adults')

function makeApp(over: Partial<AppDetail['app']> & { id: number; name: string; slug: string }): AppDetail {
  return {
    app: {
      id: over.id,
      name: over.name,
      slug: over.slug,
      templateId: over.templateId ?? null,
      icon: over.icon ?? null,
      iconType: over.iconType,
      createdAt: '2026-05-01T00:00:00Z',
    },
    hosts: [],
    assignments: [],
  }
}

const youtube: AppDetail = {
  ...makeApp({ id: 10, name: 'YouTube', slug: 'youtube', icon: '📺', iconType: 'emoji' }),
  hosts: ['youtube.com', 'googlevideo.com'],
  assignments: [
    { id: 100, appId: 10, profileId: 1, mode: 'time_limited', dailyMinutes: 60, exemptFromDaily: true },
  ],
}

const reddit: AppDetail = {
  ...makeApp({ id: 11, name: 'Reddit', slug: 'reddit' }),
  hosts: ['reddit.com'],
  assignments: [],
}

// #1983 — Gimkit's host is also on the "games" blocklist (the #1980 case).
const gimkit: AppDetail = {
  ...makeApp({ id: 12, name: 'Gimkit', slug: 'gimkit' }),
  hosts: ['gimkit.com', 'fine.com'],
  assignments: [],
  blocklisted: [{ host: 'gimkit.com', blocklists: ['games'] }],
}

beforeEach(() => {
  vi.resetAllMocks()
  ;(api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([youtube, reddit])
  ;(api.profiles.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([kidsProfile, adultsProfile])
  ;(api.blocklists.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([
    { id: 'games', name: 'Games', bundled: true, hostCount: 1 },
  ])
  ;(api.appGroups.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
  ;(api.devices.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([
    { id: 1, mac: 'aa:bb:cc:dd:ee:01', name: 'iPad', profileId: 1, profileName: 'Kids',
      lastSeenIp: '192.168.1.10', lastSeenAt: '2026-05-24T18:00:00Z' },
  ])
})

describe('AppsPage — read-only list', () => {
  it('renders apps with host counts and profile-assignment counts', async () => {
    render(withQuery(<AppsPage />))
    await screen.findByText('YouTube')
    expect(screen.getByText('Reddit')).toBeInTheDocument()
    const ytRow = screen.getByRole('button', { name: /youtube/i })
    expect(within(ytRow).getByText(/2 hosts/i)).toBeInTheDocument()
    expect(within(ytRow).getByText(/1 profile/i)).toBeInTheDocument()
    const rdRow = screen.getByRole('button', { name: /reddit/i })
    expect(within(rdRow).getByText(/1 host/i)).toBeInTheDocument()
    expect(within(rdRow).getByText(/no profiles/i)).toBeInTheDocument()
  })

  it('warns on apps whose hosts are on a blocklist and names the list when expanded', async () => {
    const user = userEvent.setup()
    ;(api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([gimkit, reddit])
    render(withQuery(<AppsPage />))
    const gkRow = await screen.findByRole('button', { name: /gimkit/i })
    // Row carries a warning badge…
    expect(within(gkRow).getByTestId('app-blocklist-badge')).toBeInTheDocument()
    // …and Reddit (no overlap) does not.
    const rdRow = screen.getByRole('button', { name: /reddit/i })
    expect(within(rdRow).queryByTestId('app-blocklist-badge')).not.toBeInTheDocument()
    // Expanding lists the offending host + the named blocklist.
    await user.click(gkRow)
    const detail = await screen.findByTestId('app-blocklist-detail')
    expect(within(detail).getByText('gimkit.com')).toBeInTheDocument()
    expect(within(detail).getByText('Games')).toBeInTheDocument()
  })

  it('shows an empty-state when there are no apps', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
    render(withQuery(<AppsPage />))
    await screen.findByText(/no apps yet/i)
  })

  it('expands a row to show its hosts and assigned profiles, read-only', async () => {
    const user = userEvent.setup()
    render(withQuery(<AppsPage />))
    await user.click(await screen.findByRole('button', { name: /youtube/i }))
    const chips = await screen.findByTestId('hosts-chips')
    expect(within(chips).getByText('youtube.com')).toBeInTheDocument()
    expect(within(chips).getByText('googlevideo.com')).toBeInTheDocument()
    expect(screen.getByText(/used by/i)).toBeInTheDocument()
    expect(screen.getAllByText(/Kids/).length).toBeGreaterThan(0)
  })

  it('exposes no app-definition editing surface (create / edit / delete)', async () => {
    render(withQuery(<AppsPage />))
    await screen.findByText('YouTube')
    expect(screen.queryByRole('button', { name: /new app/i })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /delete app/i })).not.toBeInTheDocument()
    // No edit wrappers remain on the client surface either.
    expect((api.apps as Record<string, unknown>).create).toBeUndefined()
    expect((api.apps as Record<string, unknown>).patch).toBeUndefined()
    expect((api.apps as Record<string, unknown>).delete).toBeUndefined()
  })

  it('expanding a row shows no editing inputs', async () => {
    const user = userEvent.setup()
    render(withQuery(<AppsPage />))
    await user.click(await screen.findByRole('button', { name: /reddit/i }))
    await screen.findByTestId('hosts-chips')
    expect(screen.queryByTestId('app-name-input-11')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Add' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /pick from recent activity/i })).not.toBeInTheDocument()
  })
})

// #2974 — household groups and their members are editable from Manage apps as well as from a
// profile's Groups row, with the same searchable picker.
describe('AppsPage — groups', () => {
  const fn = (f: unknown) => f as ReturnType<typeof vi.fn>

  it('lists household groups with their member counts', async () => {
    fn(api.appGroups.list).mockResolvedValue([{ id: 7, name: 'Homework', memberAppIds: [11] }])
    render(withQuery(<AppsPage />))
    const row = await screen.findByTestId('apps-group-7')
    expect(row).toHaveTextContent('Homework')
    expect(within(row).getByTestId('apps-group-7-members-toggle')).toHaveTextContent('1 app')
  })

  it('shows a loading state, not an empty list, while groups load', async () => {
    fn(api.appGroups.list).mockReturnValue(new Promise(() => {}))
    render(withQuery(<AppsPage />))
    expect(await screen.findByTestId('apps-groups-loading')).toBeInTheDocument()
    expect(screen.queryByTestId('apps-groups-empty')).not.toBeInTheDocument()
  })

  it('shows an error when groups fail to load', async () => {
    fn(api.appGroups.list).mockRejectedValue(new Error('boom'))
    render(withQuery(<AppsPage />))
    expect(await screen.findByTestId('apps-groups-error')).toBeInTheDocument()
  })

  it('adds an app to a group from the picker', async () => {
    const user = userEvent.setup()
    fn(api.appGroups.list).mockResolvedValue([{ id: 7, name: 'Homework', memberAppIds: [] }])
    fn(api.appGroups.update).mockResolvedValue({ id: 7, name: 'Homework', memberAppIds: [11] })
    render(withQuery(<AppsPage />))
    const toggle = await screen.findByTestId('apps-group-7-members-toggle')
    expect(toggle).toHaveTextContent('No apps yet. Add apps')
    await user.click(toggle)
    await user.type(await screen.findByTestId('apps-group-7-member-filter'), 'red')
    await user.click(screen.getByTestId('apps-group-7-member-pick-11'))
    expect(api.appGroups.update).toHaveBeenCalledWith(7, { memberAppIds: [11] })
  })

  it('creates a new group and opens its member picker', async () => {
    const user = userEvent.setup()
    let created = false
    fn(api.appGroups.create).mockImplementation(async () => {
      created = true
      return { id: 9, name: 'Homework', memberAppIds: [] }
    })
    fn(api.appGroups.list).mockImplementation(async () =>
      created ? [{ id: 9, name: 'Homework', memberAppIds: [] }] : [])
    render(withQuery(<AppsPage />))
    expect(await screen.findByTestId('apps-groups-empty')).toBeInTheDocument()
    await user.click(screen.getByTestId('apps-groups-new'))
    await user.type(screen.getByTestId('apps-groups-new-name'), 'Homework{Enter}')
    expect(api.appGroups.create).toHaveBeenCalledWith({ name: 'Homework' })
    expect(await screen.findByTestId('apps-group-9-member-picker')).toBeInTheDocument()
  })

  it('does not claim every app is already in a group when the apps failed to load', async () => {
    const user = userEvent.setup()
    ;(api.apps.list as unknown as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('boom'))
    fn(api.appGroups.list).mockResolvedValue([{ id: 7, name: 'Homework', memberAppIds: [] }])
    render(withQuery(<AppsPage />))
    await user.click(await screen.findByTestId('apps-group-7-members-toggle'))
    expect(await screen.findByTestId('apps-group-7-apps-error')).toBeInTheDocument()
    expect(screen.queryByTestId('apps-group-7-member-picker-empty')).not.toBeInTheDocument()
  })

  it('dismisses the new-group name input on Escape', async () => {
    const user = userEvent.setup()
    render(withQuery(<AppsPage />))
    await user.click(await screen.findByTestId('apps-groups-new'))
    await user.keyboard('{Escape}')
    expect(screen.queryByTestId('apps-groups-new-name')).not.toBeInTheDocument()
    expect(screen.getByTestId('apps-groups-new')).toBeInTheDocument()
  })
})
