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

beforeEach(() => {
  vi.resetAllMocks()
  ;(api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([youtube, reddit])
  ;(api.profiles.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([kidsProfile, adultsProfile])
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
