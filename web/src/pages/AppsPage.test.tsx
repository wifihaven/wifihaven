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
      recentApexes: vi.fn(),
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
    },
    schedules: [],
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
  ;(api.devices.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([
    { id: 1, mac: 'aa:bb:cc:dd:ee:01', name: 'iPad', profileId: 1, profileName: 'Kids',
      lastSeenIp: '192.168.1.10', lastSeenAt: '2026-05-24T18:00:00Z' },
  ])
  ;(api.apps.recentApexes as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
    deviceMac: 'aa:bb:cc:dd:ee:01',
    deviceName: 'iPad',
    windowDays: 7,
    items: [
      { apex: 'googlevideo.com', bytes: 50_000_000, hits: 7,
        subdomains: ['r1.googlevideo.com', 'r2.googlevideo.com'] },
      { apex: 'youtube.com', bytes: 8_000_000, hits: 4,
        subdomains: ['m.youtube.com', 'www.youtube.com'] },
    ],
  })
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
      screen.getByPlaceholderText(/^example\.com$/i),
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
        iconType: 'emoji',
        templateId: null,
      })
      expect(api.apps.setHosts).toHaveBeenCalledWith(10, ['youtube.com', '*.ytimg.com'])
    })
  })
})

describe('AppsPage — icon picker (#1004)', () => {
  it('emoji tab: typed value is sent with iconType=emoji on create', async () => {
    (api.apps.create as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      ...makeApp({ id: 20, name: 'Discord', slug: 'discord' }),
    })
    const user = userEvent.setup()
    render(withQuery(<AppsPage />))
    await screen.findByText('YouTube')
    await user.click(screen.getByRole('button', { name: /new app/i }))
    await user.type(screen.getByPlaceholderText('YouTube'), 'Discord')
    await user.type(screen.getByTestId('icon-picker-emoji-input'), '💬')
    await user.click(screen.getByRole('button', { name: /create app/i }))
    await waitFor(() => {
      expect(api.apps.create).toHaveBeenCalledWith({
        name: 'Discord',
        icon: '💬',
        iconType: 'emoji',
        hosts: [],
      })
    })
  })

  it('URL tab: pasted URL is sent with iconType=url on edit', async () => {
    const user = userEvent.setup()
    render(withQuery(<AppsPage />))
    await user.click(await screen.findByRole('button', { name: /youtube/i }))
    await user.click(screen.getByTestId('icon-picker-tab-url'))
    const urlInput = screen.getByTestId('icon-picker-url-input') as HTMLInputElement
    await user.clear(urlInput)
    await user.type(urlInput, 'https://example.com/icon.png')
    await user.click(screen.getByRole('button', { name: /^save$/i }))
    await waitFor(() => {
      expect(api.apps.update).toHaveBeenCalledWith(10, {
        name: 'YouTube',
        icon: 'https://example.com/icon.png',
        iconType: 'url',
        templateId: null,
      })
    })
  })

  it('Favicon tab: picking a host saves DuckDuckGo URL with iconType=url', async () => {
    const user = userEvent.setup()
    render(withQuery(<AppsPage />))
    await user.click(await screen.findByRole('button', { name: /youtube/i }))
    await user.click(screen.getByTestId('icon-picker-tab-favicon'))
    await user.click(screen.getByTestId('icon-picker-favicon-youtube.com'))
    await user.click(screen.getByRole('button', { name: /^save$/i }))
    await waitFor(() => {
      expect(api.apps.update).toHaveBeenCalledWith(10, {
        name: 'YouTube',
        icon: 'https://icons.duckduckgo.com/ip3/youtube.com.ico',
        iconType: 'url',
        templateId: null,
      })
    })
  })

  it('Switching tabs preserves the name field', async () => {
    const user = userEvent.setup()
    render(withQuery(<AppsPage />))
    await screen.findByText('YouTube')
    await user.click(screen.getByRole('button', { name: /new app/i }))
    await user.type(screen.getByPlaceholderText('YouTube'), 'Discord')
    await user.click(screen.getByTestId('icon-picker-tab-url'))
    await user.click(screen.getByTestId('icon-picker-tab-emoji'))
    expect((screen.getByPlaceholderText('YouTube') as HTMLInputElement).value).toBe('Discord')
  })
})

describe('AppsPage — recent-activity picker (#766)', () => {
  it('opens picker from the create flow, multi-selects apexes, and appends them to the hosts input', async () => {
    (api.apps.create as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      ...makeApp({ id: 13, name: 'YT', slug: 'yt' }),
      hosts: ['youtube.com', 'googlevideo.com'],
    })
    const user = userEvent.setup()
    render(withQuery(<AppsPage />))
    await screen.findByText('YouTube')
    await user.click(screen.getByRole('button', { name: /new app/i }))
    await user.type(screen.getByPlaceholderText('YouTube'), 'YT')
    await user.click(screen.getByRole('button', { name: /pick from recent activity/i }))
    await screen.findByText(/top apexes a device hit/i)
    await waitFor(() => expect(api.apps.recentApexes).toHaveBeenCalled())
    await user.click(await screen.findByLabelText('Select youtube.com'))
    await user.click(screen.getByLabelText('Select googlevideo.com'))
    await user.click(screen.getByTestId('picker-add-button'))
    const textarea = screen.getByPlaceholderText(/youtube\.com/i) as HTMLTextAreaElement
    await waitFor(() => {
      expect(textarea.value).toMatch(/youtube\.com/)
      expect(textarea.value).toMatch(/googlevideo\.com/)
    })
  })

  it('opens picker from the edit flow and appends apexes to existing chips', async () => {
    const user = userEvent.setup()
    render(withQuery(<AppsPage />))
    await user.click(await screen.findByRole('button', { name: /reddit/i }))
    await user.click(screen.getByRole('button', { name: /pick from recent activity/i }))
    await waitFor(() => expect(api.apps.recentApexes).toHaveBeenCalled())
    await user.click(await screen.findByLabelText('Select googlevideo.com'))
    await user.click(screen.getByTestId('picker-add-button'))
    const chips = await screen.findByTestId('hosts-chips')
    expect(within(chips).getByText('googlevideo.com')).toBeInTheDocument()
    expect(within(chips).getByText('reddit.com')).toBeInTheDocument()
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

describe('AppsPage — host input copy (#1006)', () => {
  it('does not surface the redundant *.example.com wildcard in placeholders or help text', async () => {
    const user = userEvent.setup()
    render(withQuery(<AppsPage />))
    await user.click(await screen.findByRole('button', { name: /new app/i }))
    const textarea = screen.getByPlaceholderText(/youtube\.com/i) as HTMLTextAreaElement
    expect(textarea.placeholder).not.toContain('*.')
    // Open edit modal too — checks the single-host input placeholder.
    await user.click(screen.getByRole('button', { name: /^cancel$/i }))
    await user.click(await screen.findByRole('button', { name: /youtube/i }))
    const hostInput = screen.getByPlaceholderText(/^example\.com$/i) as HTMLInputElement
    expect(hostInput.placeholder).not.toContain('*.')
    // No visible UI string should mention *.example.com.
    expect(screen.queryByText(/\*\.example\.com/i)).not.toBeInTheDocument()
  })
})

describe('AppsPage — Escape closes modals (#1008)', () => {
  it('closes the create modal on Escape', async () => {
    const user = userEvent.setup()
    render(withQuery(<AppsPage />))
    await screen.findByText('YouTube')
    await user.click(screen.getByRole('button', { name: /new app/i }))
    expect(screen.getByRole('button', { name: /create app/i })).toBeInTheDocument()
    await user.keyboard('{Escape}')
    await waitFor(() => {
      expect(screen.queryByRole('button', { name: /create app/i })).not.toBeInTheDocument()
    })
  })

  it('Escape on a nested picker closes only the picker, not the underlying create modal', async () => {
    const user = userEvent.setup()
    render(withQuery(<AppsPage />))
    await screen.findByText('YouTube')
    await user.click(screen.getByRole('button', { name: /new app/i }))
    await user.click(screen.getByRole('button', { name: /pick from recent activity/i }))
    await screen.findByText(/top apexes a device hit/i)
    await user.keyboard('{Escape}')
    // Picker closes but the underlying create modal stays open.
    await waitFor(() => {
      expect(screen.queryByText(/top apexes a device hit/i)).not.toBeInTheDocument()
    })
    expect(screen.getByRole('button', { name: /create app/i })).toBeInTheDocument()
  })
})
