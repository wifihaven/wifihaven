import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, act, waitFor, within } from '@testing-library/react'
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

// #1834: a snapshot exercising the status-first KPI derivation — `deriveNowKpis`
// counts every active device as "Online now" and the active devices of *paused*
// profiles as "Blocked now". Kids is paused with 1 active device (blocked); Adults
// is active with 2. So onlineNow = 3, blockedNow = 1.
const kpiNow: DashboardNow = {
  asOf: '2026-05-13T10:00:00Z',
  profiles: [
    {
      id: 1,
      name: 'Kids',
      paused: true,
      activeDevices: [
        { id: 10, name: 'iPhone', mac: 'aa:bb:cc:dd:ee:01', lastSeenSeconds: 30, topHosts: [] },
      ],
    },
    {
      id: 2,
      name: 'Adults',
      paused: false,
      activeDevices: [
        { id: 20, name: 'MacBook', mac: 'aa:bb:cc:dd:ee:02', lastSeenSeconds: 10, topHosts: [] },
        { id: 21, name: 'iPad', mac: 'aa:bb:cc:dd:ee:03', lastSeenSeconds: 45, topHosts: [] },
      ],
    },
  ],
}

const mockStats = () => api.logs.stats as unknown as ReturnType<typeof vi.fn>
const mockQuery = () => api.logs.query as unknown as ReturnType<typeof vi.fn>
const mockNow   = () => api.dashboard.now as unknown as ReturnType<typeof vi.fn>

const mockAlerts = () => api.alerts.list as unknown as ReturnType<typeof vi.fn>

beforeEach(() => {
  vi.resetAllMocks()
  mockStats().mockResolvedValue(stats)
  // #1833: the unfiltered "Recent Queries" firehose is gone; only the
  // "Most recently blocked" panel hits api.logs.query (blocked=true).
  mockQuery().mockImplementation((params?: { blocked?: boolean }) =>
    Promise.resolve(
      params?.blocked
        ? { rows: [blockedRow], nextCursor: null }
        : { rows: [], nextCursor: null },
    ),
  )
  mockNow().mockResolvedValue(emptyNow)
  mockAlerts().mockResolvedValue([])
})

describe('DashboardPage', () => {
  it('renders the 1h KPI tiles, top-blocked, and per-device', async () => {
    render(withQuery(<MemoryRouter><DashboardPage /></MemoryRouter>))
    expect(await screen.findByText('78')).toBeInTheDocument()  // Events (1h)
    expect(screen.getByText('9')).toBeInTheDocument()          // Blocked (1h)
    expect(screen.getByText('evil.com')).toBeInTheDocument()
    expect(screen.getByText('42')).toBeInTheDocument()
    expect(screen.getAllByText("Kid's iPad").length).toBeGreaterThan(0)
    expect(screen.getByText('500 events')).toBeInTheDocument()
    expect(screen.getByText('20 blocked')).toBeInTheDocument()
  })

  it('restates the KPI tiles status-first: Online now / Blocked now / Events (1h) / Blocked (1h) (#1834)', async () => {
    mockNow().mockResolvedValue(kpiNow)
    render(withQuery(<MemoryRouter><DashboardPage /></MemoryRouter>))
    const strip = await screen.findByTestId('kpi-strip')
    for (const label of ['Online now', 'Blocked now', 'Events (1h)', 'Blocked (1h)']) {
      expect(within(strip).getByText(label)).toBeInTheDocument()
    }
    // Online now / Blocked now derive from the NOW snapshot via deriveNowKpis
    // (onlineNow = 3 active devices, blockedNow = 1 active device in a paused profile).
    await waitFor(() => expect(within(strip).getByText('3')).toBeInTheDocument())
    expect(within(strip).getByText('1')).toBeInTheDocument()
    expect(within(strip).getByText('78')).toBeInTheDocument()  // Events (1h) = stats.totalHour
    expect(within(strip).getByText('9')).toBeInTheDocument()   // Blocked (1h) = stats.blockedHour
  })

  it('drops the cumulative "today" KPI tiles from the dashboard (#1834)', async () => {
    render(withQuery(<MemoryRouter><DashboardPage /></MemoryRouter>))
    await screen.findByTestId('kpi-strip')
    expect(screen.queryByText('Events today')).not.toBeInTheDocument()
    expect(screen.queryByText('Blocked today')).not.toBeInTheDocument()
  })

  it('does not render the unfiltered Recent Queries firehose (#823)', async () => {
    render(withQuery(<MemoryRouter><DashboardPage /></MemoryRouter>))
    await screen.findByText('1234')
    expect(screen.queryByText('Recent Queries')).not.toBeInTheDocument()
    // The firehose's unfiltered fetch (one fewer network call on load) is gone.
    expect(api.logs.query).not.toHaveBeenCalledWith({ limit: 30 })
  })

  it('uses connection-event terminology, never "queries" (#299)', async () => {
    render(withQuery(<MemoryRouter><DashboardPage /></MemoryRouter>))
    await screen.findByTestId('kpi-strip')
    expect(screen.getByText('Events (1h)')).toBeInTheDocument()
    expect(screen.queryByText(/queries/i)).not.toBeInTheDocument()
  })

  it('shows empty state when no blocked events', async () => {
    mockStats().mockResolvedValue({ ...stats, topBlocked: [] })
    render(withQuery(<MemoryRouter><DashboardPage /></MemoryRouter>))
    expect(await screen.findByText(/No blocked events yet/)).toBeInTheDocument()
  })

  it('renders the KPI strip above the NOW section (#1834)', async () => {
    mockNow().mockResolvedValue(liveNow)
    render(withQuery(<MemoryRouter><DashboardPage /></MemoryRouter>))
    const strip = await screen.findByTestId('kpi-strip')
    const now   = await screen.findByTestId('now-section')
    const comparison = strip.compareDocumentPosition(now)
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
