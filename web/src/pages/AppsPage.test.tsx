import { describe, it, expect, beforeEach, vi } from 'vitest'
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { AppDetail, ProfileDetail } from '@/types/api'
import { withQuery } from '@/test/queryWrapper'

vi.mock('@/api/client', () => ({
  api: {
    apps: {
      list: vi.fn(),
      create: vi.fn(),
      patch: vi.fn(),
      delete: vi.fn(),
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

function patchMock() {
  return api.apps.patch as unknown as ReturnType<typeof vi.fn>
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
  patchMock().mockResolvedValue(undefined)
})

describe('AppsPage — list', () => {
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
})

describe('AppsPage — create flow (stays explicit, no autosave)', () => {
  it('round-trips name + hosts through POST /apps and reloads the list; never PATCHes', async () => {
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
    // The create flow must never autosave.
    expect(patchMock()).not.toHaveBeenCalled()
  })

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
    expect(patchMock()).not.toHaveBeenCalled()
  })
})

describe('AppsPage — inline edit autosave (#1003)', () => {
  async function expandYouTube(user: ReturnType<typeof userEvent.setup>) {
    await user.click(await screen.findByRole('button', { name: /youtube/i }))
    // Inline editor surfaces, no Save button.
    await screen.findByTestId('app-name-input-10')
    expect(screen.queryByRole('button', { name: /^save$/i })).not.toBeInTheDocument()
  }

  it('renaming autosaves via PATCH after debounce — no Save button; Unsaved then Saved just now', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    try {
      render(withQuery(<AppsPage />))
      await expandYouTube(user)
      const nameInput = screen.getByTestId('app-name-input-10') as HTMLInputElement
      // Set atomically: char-by-char typing under fake timers lets the 700ms
      // debounce fire on a transient partial value, producing extra PATCHes.
      fireEvent.change(nameInput, { target: { value: 'YT' } })

      // Pre-debounce: dirty, no save yet.
      expect(patchMock()).not.toHaveBeenCalled()
      expect(screen.getByTestId('app-name-status-10')).toHaveAttribute('data-status', 'dirty')
      expect(screen.getByTestId('app-name-status-10')).toHaveTextContent(/unsaved/i)

      await vi.advanceTimersByTimeAsync(700)

      expect(patchMock()).toHaveBeenCalledTimes(1)
      expect(patchMock()).toHaveBeenLastCalledWith(10, { name: 'YT' })

      await waitFor(() => {
        const status = screen.getByTestId('app-name-status-10')
        expect(status).toHaveAttribute('data-status', 'saved')
        expect(status).toHaveTextContent(/saved just now/i)
      })
    } finally {
      vi.useRealTimers()
    }
  })

  it('changing the icon autosaves via PATCH with icon + iconType', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    try {
      render(withQuery(<AppsPage />))
      await expandYouTube(user)
      await user.click(screen.getByTestId('icon-picker-tab-url'))
      const urlInput = screen.getByTestId('icon-picker-url-input') as HTMLInputElement
      fireEvent.change(urlInput, { target: { value: 'https://example.com/icon.png' } })

      await vi.advanceTimersByTimeAsync(700)

      expect(patchMock()).toHaveBeenLastCalledWith(10, {
        icon: 'https://example.com/icon.png',
        iconType: 'url',
      })
    } finally {
      vi.useRealTimers()
    }
  })

  it('adding a host autosaves the full host list via PATCH (full-replace)', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    try {
      render(withQuery(<AppsPage />))
      await expandYouTube(user)
      await user.type(screen.getByPlaceholderText(/^example\.com$/i), '*.ytimg.com')
      await user.click(screen.getByRole('button', { name: 'Add' }))

      await vi.advanceTimersByTimeAsync(700)

      expect(patchMock()).toHaveBeenLastCalledWith(10, {
        hosts: ['youtube.com', 'googlevideo.com', '*.ytimg.com'],
      })
    } finally {
      vi.useRealTimers()
    }
  })

  it('removing a host autosaves the reduced host list via PATCH', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    try {
      render(withQuery(<AppsPage />))
      await expandYouTube(user)
      await user.click(screen.getByRole('button', { name: /remove googlevideo\.com/i }))

      await vi.advanceTimersByTimeAsync(700)

      expect(patchMock()).toHaveBeenLastCalledWith(10, { hosts: ['youtube.com'] })
    } finally {
      vi.useRealTimers()
    }
  })

  it('save failure shows inline error + Retry; dirty value stays; Retry re-sends', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    try {
      patchMock().mockRejectedValueOnce(new Error('boom'))
      render(withQuery(<AppsPage />))
      await expandYouTube(user)
      const nameInput = screen.getByTestId('app-name-input-10') as HTMLInputElement
      fireEvent.change(nameInput, { target: { value: 'YT' } })

      await vi.advanceTimersByTimeAsync(700)

      await waitFor(() => {
        expect(screen.getByTestId('app-name-status-10')).toHaveAttribute('data-status', 'error')
      })
      // Dirty value stays in the form.
      expect((screen.getByTestId('app-name-input-10') as HTMLInputElement).value).toBe('YT')

      // Retry re-sends the same PATCH and succeeds.
      patchMock().mockResolvedValueOnce(undefined)
      await user.click(screen.getByRole('button', { name: /retry/i }))
      await waitFor(() => {
        expect(patchMock()).toHaveBeenLastCalledWith(10, { name: 'YT' })
        expect(screen.getByTestId('app-name-status-10')).toHaveAttribute('data-status', 'saved')
      })
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('AppsPage — icon picker on inline edit (#1004)', () => {
  it('Favicon tab: picking a host autosaves DuckDuckGo URL with iconType=url', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
    try {
      render(withQuery(<AppsPage />))
      await user.click(await screen.findByRole('button', { name: /youtube/i }))
      await screen.findByTestId('app-name-input-10')
      await user.click(screen.getByTestId('icon-picker-tab-favicon'))
      await user.click(screen.getByTestId('icon-picker-favicon-youtube.com'))
      await vi.advanceTimersByTimeAsync(700)
      expect(patchMock()).toHaveBeenLastCalledWith(10, {
        icon: 'https://icons.duckduckgo.com/ip3/youtube.com.ico',
        iconType: 'url',
      })
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('AppsPage — recent-activity picker on inline edit (#766)', () => {
  it('opens picker from the inline editor and appends apexes to existing chips', async () => {
    const user = userEvent.setup()
    render(withQuery(<AppsPage />))
    await user.click(await screen.findByRole('button', { name: /reddit/i }))
    await screen.findByTestId('app-name-input-11')
    await user.click(screen.getByRole('button', { name: /pick from recent activity/i }))
    await waitFor(() => expect(api.apps.recentApexes).toHaveBeenCalled())
    await user.click(await screen.findByLabelText('Select googlevideo.com'))
    await user.click(screen.getByTestId('picker-add-button'))
    const chips = await screen.findByTestId('hosts-chips')
    expect(within(chips).getByText('googlevideo.com')).toBeInTheDocument()
    expect(within(chips).getByText('reddit.com')).toBeInTheDocument()
  })
})

describe('AppsPage — delete flow (inline)', () => {
  it('warns when the app has profile assignments and names them', async () => {
    const user = userEvent.setup()
    render(withQuery(<AppsPage />))
    await user.click(await screen.findByRole('button', { name: /youtube/i }))
    await screen.findByTestId('app-name-input-10')
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
    await screen.findByTestId('app-name-input-11')
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
    await user.click(screen.getByRole('button', { name: /^cancel$/i }))
    await user.click(await screen.findByRole('button', { name: /youtube/i }))
    const hostInput = screen.getByPlaceholderText(/^example\.com$/i) as HTMLInputElement
    expect(hostInput.placeholder).not.toContain('*.')
    expect(screen.queryByText(/\*\.example\.com/i)).not.toBeInTheDocument()
  })
})

describe('AppsPage — Escape closes create modal (#1008)', () => {
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
    await waitFor(() => {
      expect(screen.queryByText(/top apexes a device hit/i)).not.toBeInTheDocument()
    })
    expect(screen.getByRole('button', { name: /create app/i })).toBeInTheDocument()
  })
})
