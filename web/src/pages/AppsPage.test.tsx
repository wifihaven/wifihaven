import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AppDetail, ProfileDetail } from '@/types/api'
import { withQuery } from '@/test/queryWrapper'

vi.mock('@/api/client', () => ({
  api: {
    apps: {
      list: vi.fn(),
      create: vi.fn(),
      update: vi.fn(),
      delete: vi.fn(),
      setHosts: vi.fn(),
    },
    profiles: {
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
      extraBlocked: [],
      extraAllowed: [],
      paused: false,
      failureMode: 'last-known-good',
      crossDeviceOverlapMode: 'sum',
    },
    schedules: [],
    timeLimit: null,
    siteTimeLimits: [],
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
      createdAt: '2026-05-01T00:00:00Z',
    },
    hosts: [],
    assignments: [],
  }
}

const youtube: AppDetail = {
  ...makeApp({ id: 10, name: 'YouTube', slug: 'youtube', icon: '📺' }),
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
})

describe('AppsPage — list', () => {
  it('renders apps with host counts and profile-assignment counts', async () => {
    render(withQuery(<AppsPage />))
    await screen.findByText('YouTube')
    expect(screen.getByText('Reddit')).toBeInTheDocument()
    // YouTube row: 2 hosts, 1 profile
    const ytRow = screen.getByRole('button', { name: /youtube/i })
    expect(within(ytRow).getByText(/2 hosts/i)).toBeInTheDocument()
    expect(within(ytRow).getByText(/1 profile/i)).toBeInTheDocument()
    // Reddit row: 1 host, no profiles
    const rdRow = screen.getByRole('button', { name: /reddit/i })
    expect(within(rdRow).getByText(/1 host/i)).toBeInTheDocument()
    expect(within(rdRow).getByText(/no profiles/i)).toBeInTheDocument()
  })

  it('shows an empty-state when there are no apps', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
    render(withQuery(<AppsPage />))
    await screen.findByText(/no apps yet/i)
  })
})

describe('AppsPage — create flow', () => {
  it('round-trips name + hosts through POST /apps and reloads the list', async () => {
    (api.apps.create as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      ...makeApp({ id: 12, name: 'Discord', slug: 'discord' }),
      hosts: ['discord.com'],
    })
    const user = userEvent.setup()
    render(withQuery(<AppsPage />))
    await screen.findByText('YouTube')
    await user.click(screen.getByRole('button', { name: /new app/i }))
    await user.type(screen.getByPlaceholderText('YouTube'), 'Discord')
    await user.type(
      screen.getByPlaceholderText(/youtube\.com/i),
      'discord.com, *.discordapp.net',
    )
    // After create, the list will include the new app
    ;(api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([
      youtube, reddit,
      { ...makeApp({ id: 12, name: 'Discord', slug: 'discord' }), hosts: ['discord.com'] },
    ])
    await user.click(screen.getByRole('button', { name: /create app/i }))
    await waitFor(() => {
      expect(api.apps.create).toHaveBeenCalledWith({
        name: 'Discord',
        icon: undefined,
        hosts: ['discord.com', '*.discordapp.net'],
      })
    })
    await screen.findByText('Discord')
  })
})

describe('AppsPage — edit flow', () => {
  it('renders existing hosts as chips and round-trips edits via PUT /apps + PUT /apps/:id/hosts', async () => {
    const user = userEvent.setup()
    render(withQuery(<AppsPage />))
    await user.click(await screen.findByRole('button', { name: /youtube/i }))
    // Existing host chips
    expect(screen.getByText('youtube.com')).toBeInTheDocument()
    expect(screen.getByText('googlevideo.com')).toBeInTheDocument()
    // Remove a host
    await user.click(screen.getByRole('button', { name: /remove googlevideo\.com/i }))
    expect(screen.queryByText('googlevideo.com')).not.toBeInTheDocument()
    // Add a new host via the Add button
    await user.type(
      screen.getByPlaceholderText(/example\.com or \*\.example\.com/i),
      '*.ytimg.com',
    )
    await user.click(screen.getByRole('button', { name: 'Add' }))
    expect(screen.getByText('*.ytimg.com')).toBeInTheDocument()
    // Rename
    const nameInput = screen.getByDisplayValue('YouTube') as HTMLInputElement
    await user.clear(nameInput)
    await user.type(nameInput, 'YT')
    await user.click(screen.getByRole('button', { name: /^save$/i }))
    await waitFor(() => {
      expect(api.apps.update).toHaveBeenCalledWith(10, {
        name: 'YT',
        icon: '📺',
        templateId: null,
      })
      expect(api.apps.setHosts).toHaveBeenCalledWith(10, ['youtube.com', '*.ytimg.com'])
    })
  })
})

describe('AppsPage — delete flow', () => {
  it('warns when the app has profile assignments and names them', async () => {
    const user = userEvent.setup()
    render(withQuery(<AppsPage />))
    await user.click(await screen.findByRole('button', { name: /youtube/i }))
    await user.click(screen.getByRole('button', { name: /delete app/i }))
    expect(screen.getByText(/assigned to/i)).toBeInTheDocument()
    expect(screen.getAllByText(/Kids/).length).toBeGreaterThan(0)
    await user.click(screen.getByRole('button', { name: /^delete$/i }))
    await waitFor(() => {
      expect(api.apps.delete).toHaveBeenCalledWith(10)
    })
  })

  it('shows a simpler confirm when the app has no assignments', async () => {
    const user = userEvent.setup()
    render(withQuery(<AppsPage />))
    await user.click(await screen.findByRole('button', { name: /reddit/i }))
    await user.click(screen.getByRole('button', { name: /delete app/i }))
    expect(screen.queryByText(/assigned to/i)).not.toBeInTheDocument()
    expect(screen.getByText(/cannot be undone/i)).toBeInTheDocument()
    await user.click(screen.getByRole('button', { name: /^delete$/i }))
    await waitFor(() => {
      expect(api.apps.delete).toHaveBeenCalledWith(11)
    })
  })
})
