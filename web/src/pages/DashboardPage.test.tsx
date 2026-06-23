import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, act, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import type { DashboardNow, DashboardStats, QueryLog } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    logs: {
      stats: vi.fn(),
      query: vi.fn(),
    },
    dashboard: {
      now: vi.fn(),
    },
    alerts: {
      list: vi.fn(),
    },
  },
}))

import { api } from '@/api/client'
import { DashboardPage, NowSection, RecentlyBlockedSection } from './DashboardPage'
import { withQuery } from '@/test/queryWrapper'

const stats: DashboardStats = {
  totalToday: 1234,
  blockedToday: 56,
  totalHour: 78,
  blockedHour: 9,
  topBlocked: [
    { host: { type: 'fqdn', value: 'evil.com' }, count: 42 },
    { host: { type: 'fqdn', value: 'ads.example' }, count: 17 },
  ],
  perDevice: [
    { mac: 'aa:bb:cc:dd:ee:01', deviceName: "Kid's iPad", total: 500, blocked: 20 },
  ],
}

const recent: QueryLog = {
  id: 1, mac: 'aa:bb:cc:dd:ee:01', deviceName: "Kid's iPad",
  profileId: 1, profileName: 'Kids',
  host: { type: 'fqdn', value: 'example.com' }, qtype: 1, blocked: false, reason: { kind: 'allow' },
  location: 'home', ts: '2026-05-07T10:15:30Z',
}

// #1338: a recent connection-layer drop, returned by /api/logs?blocked=true.
// ts must be inside the panel's 15-min recency window, so derive it from now.
const recentBlockedTs = () => new Date(Date.now() - 12_000).toISOString() // 12s ago
const blockedRow: QueryLog = {
  id: 99, mac: 'aa:bb:cc:dd:ee:01', deviceName: "Kid's iPad",
  profileId: 1, profileName: 'Kids',
  host: { type: 'fqdn', value: 'connectivitycheck.gstatic.com' }, qtype: 1,
  blocked: true, reason: { kind: 'category', slug: 'ads' },
  location: 'home', ts: recentBlockedTs(),
}

const emptyNow: DashboardNow = { asOf: '2026-05-13T10:00:00Z', profiles: [] }

const liveNow: DashboardNow = {
  asOf: '2026-05-13T10:00:00Z',
  profiles: [
    {
      id: 1,
      name: 'Kids',
      paused: false,
      activeDevices: [
        {
          id: 10,
          name: 'iPhone',
          mac: 'aa:bb:cc:dd:ee:01',
          lastSeenSeconds: 30,
          topHosts: [
            { host: { type: 'fqdn', value: 'youtube.com' }, activeSeconds: 840 },
            { host: { type: 'fqdn', value: 'tiktok.com' }, activeSeconds: 120 },
          ],
          nowActivity: {
            topHost: { type: 'fqdn', value: 'youtube.com' },
            minutes: 25,
          },
        },
      ],
    },
    { id: 2, name: 'Adults', paused: false, activeDevices: [] },
  ],
}

const mockStats = () => api.logs.stats as unknown as ReturnType<typeof vi.fn>
const mockQuery = () => api.logs.query as unknown as ReturnType<typeof vi.fn>
const mockNow   = () => api.dashboard.now as unknown as ReturnType<typeof vi.fn>

const mockAlerts = () => api.alerts.list as unknown as ReturnType<typeof vi.fn>

beforeEach(() => {
  vi.resetAllMocks()
  mockStats().mockResolvedValue(stats)
  // #1338: the "Most recently blocked" panel hits api.logs.query with
  // blocked=true; serve the blocked fixture for it. The unfiltered (allow) branch
  // is retained only as a guard — #1833 dropped the firehose that consumed it.
  mockQuery().mockImplementation((params?: { blocked?: boolean }) =>
    Promise.resolve(
      params?.blocked
        ? { rows: [blockedRow], nextCursor: null }
        : { rows: [recent], nextCursor: null },
    ),
  )
  mockNow().mockResolvedValue(emptyNow)
  mockAlerts().mockResolvedValue([])
})

describe('DashboardPage', () => {
  it('renders stat cards, top-blocked, and per-device with connection-events copy', async () => {
    render(withQuery(<MemoryRouter><DashboardPage /></MemoryRouter>))
    expect(await screen.findByText('1234')).toBeInTheDocument()
    expect(screen.getByText('56')).toBeInTheDocument()
    expect(screen.getByText('78')).toBeInTheDocument()
    expect(screen.getByText('9')).toBeInTheDocument()
    expect(screen.getByText('evil.com')).toBeInTheDocument()
    expect(screen.getByText('42')).toBeInTheDocument()
    expect(screen.getAllByText("Kid's iPad").length).toBeGreaterThan(0)
    expect(screen.getByText('500 events')).toBeInTheDocument()
    expect(screen.getByText('20 blocked')).toBeInTheDocument()
  })

  it('drops the unfiltered Recent Queries firehose (#823): no inline log table or query() call', async () => {
    render(withQuery(<MemoryRouter><DashboardPage /></MemoryRouter>))
    await screen.findByText('1234')
    // The un-aggregated allow row used only by the dropped firehose must not render.
    expect(screen.queryByText('example.com')).not.toBeInTheDocument()
    expect(screen.queryByText(/Recent Queries/)).not.toBeInTheDocument()
    // One fewer network call on load: the page fetches only stats, never the firehose query.
    expect(api.logs.stats).toHaveBeenCalledTimes(1)
    expect(mockQuery()).not.toHaveBeenCalledWith({ limit: 30 })
  })

  it('uses "connection events" terminology, never "queries" (#299)', async () => {
    render(withQuery(<MemoryRouter><DashboardPage /></MemoryRouter>))
    await screen.findByText('1234')
    expect(document.body.textContent).not.toMatch(/queries/i)
  })

  it('shows empty state when no blocked connection events', async () => {
    mockStats().mockResolvedValue({ ...stats, topBlocked: [] })
    render(withQuery(<MemoryRouter><DashboardPage /></MemoryRouter>))
    expect(await screen.findByText(/No blocked connection events yet/)).toBeInTheDocument()
  })

  it('renders Now section above stat cards', async () => {
    mockNow().mockResolvedValue(liveNow)
    render(withQuery(<MemoryRouter><DashboardPage /></MemoryRouter>))
    await screen.findByText('1234')
    await waitFor(() => expect(screen.getByTestId('now-profile-1')).toBeInTheDocument())
    const nowHeading = screen.getByText('Now')
    const statsCard  = screen.getByText('Events today')
    const comparison = nowHeading.compareDocumentPosition(statsCard)
    expect(comparison & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('renders the Most Recently Blocked panel above the Now section (#1338)', async () => {
    render(withQuery(<MemoryRouter><DashboardPage /></MemoryRouter>))
    const blocked = await screen.findByTestId('recently-blocked-section')
    const now     = screen.getByTestId('now-section')
    const cmp     = blocked.compareDocumentPosition(now)
    expect(cmp & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(await screen.findByText('connectivitycheck.gstatic.com')).toBeInTheDocument()
  })
})

describe('RecentlyBlockedSection (#1338)', () => {
  it('renders blocked rows: host, device + profile, and reason', async () => {
    render(withQuery(<MemoryRouter><RecentlyBlockedSection /></MemoryRouter>))
    expect(await screen.findByText('connectivitycheck.gstatic.com')).toBeInTheDocument()
    expect(screen.getByText(/Kid's iPad · Kids/)).toBeInTheDocument()
    expect(screen.getByText('category: ads')).toBeInTheDocument()
    // Reuses the blocked=true read, capped to 20 (RECENT_BLOCKED_LIMIT), 1h fetch
    // window (trimmed to 15 min client-side).
    expect(api.logs.query).toHaveBeenCalledWith({ blocked: true, limit: 20, hours: 1 })
  })

  it('drops blocks older than the 15-min recency window (#1338)', async () => {
    // A real-but-stale block (e.g. ~11h ago, the beacons.gcp.gvt2.com case) must
    // not masquerade as "recently blocked".
    mockQuery().mockResolvedValue({
      rows: [{ ...blockedRow, ts: new Date(Date.now() - 11 * 3600_000).toISOString() }],
      nextCursor: null,
    })
    render(withQuery(<MemoryRouter><RecentlyBlockedSection /></MemoryRouter>))
    expect(await screen.findByText(/Nothing blocked recently/)).toBeInTheDocument()
    expect(screen.queryByText('connectivitycheck.gstatic.com')).not.toBeInTheDocument()
  })

  it('links to the full Connection Events page', async () => {
    render(withQuery(<MemoryRouter><RecentlyBlockedSection /></MemoryRouter>))
    await screen.findByText('connectivitycheck.gstatic.com')
    expect(screen.getByText(/View all/).closest('a')).toHaveAttribute('href', '/usage/events')
  })

  it('shows an empty state when nothing is blocked', async () => {
    mockQuery().mockResolvedValue({ rows: [], nextCursor: null })
    render(withQuery(<MemoryRouter><RecentlyBlockedSection /></MemoryRouter>))
    expect(await screen.findByText(/Nothing blocked recently/)).toBeInTheDocument()
  })

  it('polls and updates the list on refetch', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      mockQuery()
        .mockResolvedValueOnce({ rows: [blockedRow], nextCursor: null })
        .mockResolvedValue({
          rows: [{ ...blockedRow, id: 100, host: { type: 'fqdn', value: 'ocsp.apple.com' } }],
          nextCursor: null,
        })
      render(withQuery(<MemoryRouter><RecentlyBlockedSection /></MemoryRouter>))
      await screen.findByText('connectivitycheck.gstatic.com')
      await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
      await waitFor(() => expect(screen.getByText('ocsp.apple.com')).toBeInTheDocument())
    } finally {
      vi.useRealTimers()
    }
  })
})

describe('NowSection', () => {
  it('renders one card per profile in id order, idle profiles dimmed', async () => {
    mockNow().mockResolvedValue(liveNow)
    render(withQuery(<NowSection />))
    const kids   = await screen.findByTestId('now-profile-1')
    const adults = screen.getByTestId('now-profile-2')
    expect(kids).toBeInTheDocument()
    expect(adults).toHaveClass('opacity-60')
    expect(screen.getByText(/No activity in the last 5 minutes/)).toBeInTheDocument()
  })

  it('shows device name, last-seen, and top hosts', async () => {
    mockNow().mockResolvedValue(liveNow)
    render(withQuery(<NowSection />))
    await screen.findByTestId('now-device-aa:bb:cc:dd:ee:01')
    expect(screen.getByText('iPhone')).toBeInTheDocument()
    expect(screen.getByText('30s ago')).toBeInTheDocument()
    expect(screen.getAllByText('youtube.com').length).toBeGreaterThan(0)
    expect(screen.getByText('tiktok.com')).toBeInTheDocument()
    expect(screen.getByText('14m')).toBeInTheDocument()
  })

  it('renders "watching X · Nm" activity line for an active device', async () => {
    mockNow().mockResolvedValue(liveNow)
    render(withQuery(<NowSection />))
    await screen.findByTestId('now-device-aa:bb:cc:dd:ee:01')
    expect(screen.getByText(/watching/)).toBeInTheDocument()
    expect(screen.getByText(/· 25m/)).toBeInTheDocument()
  })

  it('renders "(active)" fallback when nowActivity is null', async () => {
    mockNow().mockResolvedValue({
      ...liveNow,
      profiles: [
        {
          ...liveNow.profiles[0],
          activeDevices: [{ ...liveNow.profiles[0].activeDevices[0], nowActivity: null }],
        },
        liveNow.profiles[1],
      ],
    })
    render(withQuery(<NowSection />))
    await screen.findByTestId('now-device-aa:bb:cc:dd:ee:01')
    expect(screen.getByText('(active)')).toBeInTheDocument()
    expect(screen.queryByText(/watching/)).not.toBeInTheDocument()
  })

  it('omits the minutes suffix when minutes is null (single-bucket signal)', async () => {
    mockNow().mockResolvedValue({
      ...liveNow,
      profiles: [
        {
          ...liveNow.profiles[0],
          activeDevices: [{
            ...liveNow.profiles[0].activeDevices[0],
            nowActivity: {
              topHost: { type: 'fqdn', value: 'youtube.com' },
              minutes: null,
            },
          }],
        },
        liveNow.profiles[1],
      ],
    })
    render(withQuery(<NowSection />))
    await screen.findByTestId('now-device-aa:bb:cc:dd:ee:01')
    expect(screen.getByText(/watching/)).toBeInTheDocument()
    expect(screen.queryByText(/·\s*\d+m/)).not.toBeInTheDocument()
  })

  it('renders Paused badge when profile is paused', async () => {
    mockNow().mockResolvedValue({
      ...liveNow,
      profiles: [{ ...liveNow.profiles[0], paused: true }],
    })
    render(withQuery(<NowSection />))
    expect(await screen.findByText('Paused')).toBeInTheDocument()
  })

  it('polls /dashboard/now every 10s', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      mockNow().mockResolvedValue(emptyNow)
      render(withQuery(<NowSection />))
      await waitFor(() => expect(mockNow()).toHaveBeenCalledTimes(1))
      await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
      expect(mockNow()).toHaveBeenCalledTimes(2)
      await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
      expect(mockNow()).toHaveBeenCalledTimes(3)
    } finally {
      vi.useRealTimers()
    }
  })

  it('keeps previous data on poll failure', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      mockNow().mockResolvedValueOnce(liveNow).mockRejectedValue(new Error('boom'))
      render(withQuery(<NowSection />))
      await waitFor(() => expect(screen.getByTestId('now-profile-1')).toBeInTheDocument())
      await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
      expect(screen.getByTestId('now-profile-1')).toBeInTheDocument()
      expect(screen.getByText('iPhone')).toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('renders empty-profiles message when API returns no profiles', async () => {
    mockNow().mockResolvedValue(emptyNow)
    render(withQuery(<NowSection />))
    expect(await screen.findByText(/No profiles configured yet/)).toBeInTheDocument()
  })
})
