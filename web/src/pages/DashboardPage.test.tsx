import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, act, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import type { DashboardNow, DashboardStats, QueryLog, TrafficUsageAggregateRow } from '@/types/api'
import type { WsTrafficUsage } from '@/hooks/useWs'
import type { BandwidthRate } from '@/api/wsCache'

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
    usage: {
      traffic: vi.fn(),
    },
    auth: {
      me: vi.fn(),
    },
    // #2133: FirstRunHint reads these to decide whether the household is empty.
    devices: {
      list: vi.fn(),
    },
    profiles: {
      list: vi.fn(),
    },
    // #2252: FirstRunHint reads the enrolled routers to pick its onboarding state.
    routers: {
      list: vi.fn(),
    },
  },
}))

// #2056: control the single page-wide bandwidth source so the KPI Bandwidth tile and the NOW
// card ▲▼ can be asserted to read the SAME window. All other useWs hooks stay real (no-ops
// without a WsProvider), exactly as the pre-#2056 tests relied on.
const ZERO_RATE: BandwidthRate = { bytesInPerSec: 0, bytesOutPerSec: 0, bytesPerSec: 0 }
const idleBandwidth: WsTrafficUsage = {
  live: false, loading: false, bucket: '1m', setBucket: vi.fn(),
  rows: [], overall: ZERO_RATE, rate: () => ZERO_RATE,
}
const mockUseWsTrafficUsage = vi.fn<() => WsTrafficUsage>(() => idleBandwidth)
vi.mock('@/hooks/useWs', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/hooks/useWs')>()),
  useWsTrafficUsage: () => mockUseWsTrafficUsage(),
}))

import { api } from '@/api/client'
import { DashboardPage, NowSection, RecentlyBlockedSection } from './DashboardPage'
import { withQuery, makeTestQueryClient } from '@/test/queryWrapper'
import { recentBlockedFetchLabel } from '@/api/queries'
import { AuthProvider } from '@/hooks/useAuth'

const stats: DashboardStats = {
  totalToday: 1234,
  blockedToday: 56,
  totalHour: 78,
  blockedHour: 9,
  topBlocked: [
    { host: { type: 'fqdn', value: 'evil.com' }, count: 42 },
    { host: { type: 'fqdn', value: 'ads.example' }, count: 17 },
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

// #2073 — a second recent drop under a DIFFERENT profile, to pin the per-profile grouping.
const blockedRow2: QueryLog = {
  id: 100, mac: 'aa:bb:cc:dd:ee:02', deviceName: 'Dad Laptop',
  profileId: 2, profileName: 'Adults',
  host: { type: 'fqdn', value: 'doubleclick.net' }, qtype: 1,
  blocked: true, reason: { kind: 'manual' },
  location: 'home', ts: recentBlockedTs(),
}

const emptyNow: DashboardNow = { asOf: '2026-05-13T10:00:00Z', profiles: [] }

// #2062 — the Recently-Blocked device filter draws its options from the NOW snapshot's known
// devices; this snapshot names the two devices the blocked rows above belong to.
const nowWithDevices: DashboardNow = {
  asOf: '2026-05-13T10:00:00Z',
  profiles: [
    { id: 1, name: 'Kids', paused: false, activeDevices: [
      { id: 10, name: "Kid's iPad", mac: 'aa:bb:cc:dd:ee:01', lastSeenSeconds: 30, topHosts: [] },
    ] },
    { id: 2, name: 'Adults', paused: false, activeDevices: [
      { id: 20, name: 'Dad Laptop', mac: 'aa:bb:cc:dd:ee:02', lastSeenSeconds: 20, topHosts: [] },
    ] },
  ],
}

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

// ── #1835 fixtures: device ranking, top-N cap, idle collapse ──────────────────
type Dev = DashboardNow['profiles'][number]['activeDevices'][number]
const dev = (name: string, lastSeenSeconds: number, activeSeconds: number): Dev => ({
  id: Number(name.replace(/\D/g, '')) || name.charCodeAt(0),
  name,
  mac: `aa:bb:cc:dd:ff:${name.slice(0, 2)}`,
  lastSeenSeconds,
  topHosts: activeSeconds > 0
    ? [{ host: { type: 'fqdn', value: `${name.toLowerCase()}.example` }, activeSeconds }]
    : [],
})

// An 11-device "Family" card. Ranked by active-seconds DESC, the top 3 are
// Streamer (900) · Browser (600) · Music (300). "Recent" is the *newest* device
// (lastSeenSeconds 1) but has the LOWEST active-seconds (5) — so a recency rank
// would float it to the top, while the active-seconds rank (#1835/Q6) sinks it
// behind the expander. That contrast is the regression this fixture pins.
const familyNow: DashboardNow = {
  asOf: '2026-05-13T10:00:00Z',
  profiles: [
    {
      id: 5,
      name: 'Family',
      paused: false,
      activeDevices: [
        dev('Recent', 1, 5),
        dev('Streamer', 50, 900),
        dev('Browser', 40, 600),
        dev('Music', 45, 300),
        dev('Dev05', 60, 250),
        dev('Dev06', 60, 240),
        dev('Dev07', 60, 230),
        dev('Dev08', 60, 220),
        dev('Dev09', 60, 210),
        dev('Dev10', 60, 200),
        dev('Dev11', 60, 190),
      ],
    },
  ],
}

// One active profile + three idle profiles (one paused). Idle = zero active
// devices; the paused-and-idle profile lives in the idle group too, tagged paused
// when expanded.
const idleNow: DashboardNow = {
  asOf: '2026-05-13T10:00:00Z',
  profiles: [
    {
      id: 1,
      name: 'Kids',
      paused: false,
      activeDevices: [dev('iPhone', 30, 120)],
    },
    { id: 2, name: 'Adults', paused: false, activeDevices: [] },
    { id: 3, name: 'Guest', paused: true, activeDevices: [] },
    { id: 4, name: 'IoT', paused: false, activeDevices: [] },
  ],
}

// Fresh row (30s) vs materially-stale row (120s, >60s vs the snapshot). Only the
// stale row keeps its inline "Xs ago" flag (#825).
const staleNow: DashboardNow = {
  asOf: '2026-05-13T10:00:00Z',
  profiles: [
    {
      id: 1,
      name: 'Kids',
      paused: false,
      activeDevices: [dev('Fresh', 30, 120), dev('Stale', 120, 90)],
    },
  ],
}

const mockStats = () => api.logs.stats as unknown as ReturnType<typeof vi.fn>
const mockQuery = () => api.logs.query as unknown as ReturnType<typeof vi.fn>
const mockNow   = () => api.dashboard.now as unknown as ReturnType<typeof vi.fn>

const mockAlerts = () => api.alerts.list as unknown as ReturnType<typeof vi.fn>

beforeEach(() => {
  vi.resetAllMocks()
  // #1835: expander/idle-collapse state is per-session in sessionStorage; reset
  // it between tests so one test's expansion doesn't leak into the next.
  sessionStorage.clear()
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
  ;(api.usage.traffic as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
    bucket: '1m', from: '', to: '', tz: 'UTC', rawRows: [], aggregateRows: [],
  })
  mockUseWsTrafficUsage.mockReturnValue(idleBandwidth)
  // #2133: default to a NON-empty household so FirstRunHint stays hidden in the
  // existing dashboard tests; the first-run behaviour is covered in FirstRunHint.test.
  ;(api.devices.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([{ id: 1 }])
  ;(api.profiles.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([{ id: 1 }])
  // #2252: default to a router that has already connected (lastSeenAt set) so the onboarding
  // banner stays hidden here; its state machine is covered in FirstRunHint.test.
  ;(api.routers.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([
    { id: 'r1', name: 'gw', enrolled: true, lastSeenAt: '2026-05-12T00:00:00Z', lastEtag: null, createdAt: '2026-05-11T00:00:00Z', agentVersion: null },
  ])
})

// #2056: a live per-profile bandwidth head row (groupBy:profile keys the profile name in
// `groups.profile`). rateFor divides bytes-over-bucket, so the displayed ▲▼ depends on the
// window — the lever the "global selector governs ▲▼" assertion pulls.
const profileRow = (profile: string, bytesIn: number, bytesOut: number): TrafficUsageAggregateRow => ({
  groups: { profile }, windowStart: 'w', windowEnd: 'w',
  totalBytesIn: bytesIn, totalBytesOut: bytesOut, totalSeconds: 60,
})
const liveBandwidth = (overrides: Partial<WsTrafficUsage> = {}): WsTrafficUsage => ({
  ...idleBandwidth, live: true, ...overrides,
})

describe('DashboardPage', () => {
  it('renders the status-first 1h KPI tiles (#1834)', async () => {
    render(withQuery(<MemoryRouter><DashboardPage /></MemoryRouter>))
    expect(await screen.findByText('78')).toBeInTheDocument()  // Events (1h)
    expect(screen.getByText('9')).toBeInTheDocument()          // Blocked (1h)
  })

  // ── #2073: the "Blocking activity (24h)" panel is removed from the dashboard ──
  it('does NOT render the "Blocking activity (24h)" panel (#2073)', async () => {
    render(withQuery(<MemoryRouter><DashboardPage /></MemoryRouter>))
    await screen.findByTestId('kpi-strip')
    expect(screen.queryByText('Blocking activity (24h)')).not.toBeInTheDocument()
    // …and its ranked-host body (the topBlocked shape) no longer appears anywhere.
    expect(screen.queryByText('evil.com')).not.toBeInTheDocument()
    expect(screen.queryByText('ads.example')).not.toBeInTheDocument()
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
    await screen.findByTestId('kpi-strip')
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

  it('renders the KPI strip above the NOW section (#1834)', async () => {
    mockNow().mockResolvedValue(liveNow)
    render(withQuery(<MemoryRouter><DashboardPage /></MemoryRouter>))
    const strip = await screen.findByTestId('kpi-strip')
    const now   = await screen.findByTestId('now-section')
    const comparison = strip.compareDocumentPosition(now)
    expect(comparison & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  // ── #1837 (#1098): per-section skeletons — NOW paints independent of stats ──
  it('paints NOW independently — does not gate the whole page on stats (#1837/#1098)', async () => {
    // stats never resolves; the page must NOT block on it (the old page-level
    // PageLoader gated the whole render on Promise resolution).
    mockStats().mockReturnValue(new Promise<DashboardStats>(() => {}))
    mockNow().mockResolvedValue(liveNow)
    render(withQuery(<MemoryRouter><DashboardPage /></MemoryRouter>))
    // NOW + its live card paint without waiting on stats.
    expect(await screen.findByTestId('now-section')).toBeInTheDocument()
    expect(await screen.findByTestId('now-profile-1')).toBeInTheDocument()
  })

  it('shows a skeleton (never "0") on the stats-backed 1h tiles until stats resolves (#1837)', async () => {
    mockStats().mockReturnValue(new Promise<DashboardStats>(() => {}))
    mockNow().mockResolvedValue(kpiNow)
    render(withQuery(<MemoryRouter><DashboardPage /></MemoryRouter>))
    const strip = await screen.findByTestId('kpi-strip')
    // Live KPIs derived from the NOW snapshot paint immediately (onlineNow=3).
    await waitFor(() => expect(within(strip).getByText('3')).toBeInTheDocument())
    // The stats-backed Events(1h)/Blocked(1h) tiles render a skeleton, not "0".
    expect(within(strip).queryByText('0')).not.toBeInTheDocument()
    expect(within(strip).getAllByTestId('stat-skeleton').length).toBeGreaterThan(0)
  })

  it('renders the Most Recently Blocked panel above the Now section (#1338)', async () => {
    render(withQuery(<MemoryRouter><DashboardPage /></MemoryRouter>))
    const blocked = await screen.findByTestId('recently-blocked-section')
    const now     = screen.getByTestId('now-section')
    const cmp     = blocked.compareDocumentPosition(now)
    expect(cmp & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(await screen.findByText('connectivitycheck.gstatic.com')).toBeInTheDocument()
  })

  // ── #2073 §9.1: section order — Recently Blocked leads, above KPI strip → NOW; no 24h panel ──
  it('orders the page banners → Recently Blocked → KPI strip → NOW, with no 24h panel (#2073)', async () => {
    mockNow().mockResolvedValue(liveNow)
    render(withQuery(<MemoryRouter><DashboardPage /></MemoryRouter>))
    const blocked = await screen.findByTestId('recently-blocked-section')
    const strip   = await screen.findByTestId('kpi-strip')
    const now     = await screen.findByTestId('now-section')
    // Recently Blocked is FIRST (rev-4 moved it to the top as the primary diagnostic),
    // then the KPI strip, then NOW. The below-fold 24h panel is gone (#2073).
    expect(blocked.compareDocumentPosition(strip) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(strip.compareDocumentPosition(now) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
    expect(screen.queryByText('Blocking activity (24h)')).not.toBeInTheDocument()
  })

  // ── #2056 §9.1: FIVE-tile KPI strip incl. the Bandwidth tile (selector now global, #2073) ──
  it('renders a 5-tile KPI strip including a Bandwidth tile (§9.1)', async () => {
    mockUseWsTrafficUsage.mockReturnValue(liveBandwidth({
      overall: { bytesInPerSec: 18 * 1024 * 1024, bytesOutPerSec: 2 * 1024 * 1024, bytesPerSec: 20 * 1024 * 1024 },
    }))
    render(withQuery(<MemoryRouter><DashboardPage /></MemoryRouter>))
    const strip = await screen.findByTestId('kpi-strip')
    for (const label of ['Online now', 'Blocked now', 'Events (1h)', 'Blocked (1h)', 'Bandwidth']) {
      expect(within(strip).getByText(label)).toBeInTheDocument()
    }
    // The Bandwidth tile carries the live ▲▼ rate; the standalone "Live Bandwidth" panel is gone.
    expect(within(strip).getByTestId('kpi-bandwidth-rate')).toBeInTheDocument()
    // #2073: the window selector no longer lives INSIDE the Bandwidth tile.
    expect(within(strip).queryByTestId('bucket-1m')).not.toBeInTheDocument()
    expect(screen.queryByText('Live Bandwidth')).not.toBeInTheDocument()
  })

  // ── #2073: the window/bucket selector is relocated to a clearly-global spot ──
  it('relocates the window selector to a global control, out of the Bandwidth tile (#2073)', async () => {
    mockUseWsTrafficUsage.mockReturnValue(liveBandwidth({ bucket: '1m' }))
    render(withQuery(<MemoryRouter><DashboardPage /></MemoryRouter>))
    // The global selector renders its live-window buttons in one clearly-labelled place…
    const selector = await screen.findByTestId('dashboard-window-selector')
    expect(within(selector).getByText(/Window/i)).toBeInTheDocument()
    for (const b of ['raw', '1m', '10m', '1h']) {
      expect(within(selector).getByTestId(`bucket-${b}`)).toBeInTheDocument()
    }
    // …the 12h/1d/1w history buckets do NOT appear on the live control.
    expect(within(selector).queryByTestId('bucket-1d')).not.toBeInTheDocument()
    // …and it is NOT inside the Bandwidth KPI tile.
    const bandwidth = screen.getByTestId('kpi-bandwidth')
    expect(within(bandwidth).queryByTestId('bucket-1m')).not.toBeInTheDocument()
    // It sits above the KPI strip, governing every rate below it.
    const strip = screen.getByTestId('kpi-strip')
    expect(selector.compareDocumentPosition(strip) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  // ── #2056 §8.5/#2041: the Bandwidth tile's three states (re-homed from BandwidthGauges) ──
  it('shows a loading skeleton on the Bandwidth tile — never "0" — while the rate loads (#2041)', async () => {
    mockUseWsTrafficUsage.mockReturnValue(liveBandwidth({ loading: true }))
    render(withQuery(<MemoryRouter><DashboardPage /></MemoryRouter>))
    const strip = await screen.findByTestId('kpi-strip')
    expect(within(strip).getByTestId('kpi-bandwidth-loading')).toBeInTheDocument()
    // Never a misleading measured zero while loading.
    expect(within(strip).queryByTestId('kpi-bandwidth-rate')).not.toBeInTheDocument()
    expect(within(within(strip).getByTestId('kpi-bandwidth')).queryByText(/0 B\/s|▲ 0 ▼ 0/)).not.toBeInTheDocument()
  })

  it('shows "—" (not ▲0 ▼0) on the Bandwidth tile when the socket is not live (#2041)', async () => {
    mockUseWsTrafficUsage.mockReturnValue(liveBandwidth({ live: false, loading: false }))
    render(withQuery(<MemoryRouter><DashboardPage /></MemoryRouter>))
    const strip = await screen.findByTestId('kpi-strip')
    expect(within(strip).getByTestId('kpi-bandwidth-idle')).toHaveTextContent('—')
    expect(within(strip).queryByTestId('kpi-bandwidth-rate')).not.toBeInTheDocument()
    // The (now global) selector still renders so the user can change the window even while paused.
    expect(within(screen.getByTestId('dashboard-window-selector')).getByTestId('bucket-1m')).toBeInTheDocument()
  })

  // ── #2056 §8.5: ONE window selector governs ▲▼ EVERYWHERE (global selection) ──
  it('drives both the KPI Bandwidth tile and the NOW card ▲▼ off the one shared window (§8.5)', async () => {
    const setBucket = vi.fn()
    mockNow().mockResolvedValue(liveNow) // a "Kids" profile card
    mockUseWsTrafficUsage.mockReturnValue(liveBandwidth({
      bucket: '1m',
      setBucket,
      rows: [profileRow('Kids', 8 * 1024 * 1024, 1024 * 1024)],
      overall: { bytesInPerSec: 8 * 1024 * 1024 / 60, bytesOutPerSec: 1024 * 1024 / 60, bytesPerSec: 0 },
    }))
    const user = userEvent.setup()
    render(withQuery(<MemoryRouter><DashboardPage /></MemoryRouter>))
    const strip = await screen.findByTestId('kpi-strip')
    // Both surfaces render a live ▲▼ rate from the SAME bandwidth source.
    expect(within(strip).getByTestId('kpi-bandwidth-rate')).toHaveTextContent('▲')
    const card = await screen.findByTestId('now-profile-1')
    expect(within(card).getByTestId('now-profile-rate-1')).toHaveTextContent('▲')
    // The global window selector is the single control — switching it re-subscribes the one
    // shared trafficUsage stream (the same setter the KPI tile AND the NOW cards' window read).
    const selector = screen.getByTestId('dashboard-window-selector')
    await user.click(within(selector).getByTestId('bucket-10m'))
    expect(setBucket).toHaveBeenCalledWith('10m')
  })
})

describe('RecentlyBlockedSection (#1338 / #2073 / #2062)', () => {
  it('renders blocked rows: device, host, and reason under a per-profile group (§9.2 shape)', async () => {
    render(withQuery(<MemoryRouter><RecentlyBlockedSection /></MemoryRouter>))
    expect(await screen.findByText('connectivitycheck.gstatic.com')).toBeInTheDocument()
    // §9.2 (#2073): the row is device · host · reason · time; the profile is the GROUP header now.
    expect(screen.getByText("Kid's iPad")).toBeInTheDocument()
    expect(screen.getByText(/Kids/)).toBeInTheDocument()
    expect(screen.getByText('category: ads')).toBeInTheDocument()
    // Reuses the blocked=true read, capped to 20 (RECENT_BLOCKED_LIMIT), 1h fetch
    // window (trimmed to 15 min client-side). No `macs` when unfiltered.
    expect(api.logs.query).toHaveBeenCalledWith({ blocked: true, limit: 20, hours: 1 })
  })

  // ── #2073: group the blocked rows under a per-profile header (the clickable deep-link) ──
  it('groups rows by profile; the profile header deep-links to /profiles?id=<profileId> (#2073)', async () => {
    mockQuery().mockResolvedValue({ rows: [blockedRow, blockedRow2], nextCursor: null })
    render(withQuery(<MemoryRouter><RecentlyBlockedSection /></MemoryRouter>))
    await screen.findByText('connectivitycheck.gstatic.com')
    // One group header per profile, each a link to that profile's editor (reusing the ?id=
    // scroll-to-highlight deep-link, #298) — NOT a per-row profile chip.
    const kids = screen.getByTestId('recently-blocked-group-1')
    expect(kids).toHaveAttribute('href', '/profiles?id=1')
    expect(kids).toHaveTextContent(/Kids/)
    const adults = screen.getByTestId('recently-blocked-group-2')
    expect(adults).toHaveAttribute('href', '/profiles?id=2')
    expect(adults).toHaveTextContent(/Adults/)
    // The two devices' drops land under their respective groups.
    expect(screen.getByText("Kid's iPad")).toBeInTheDocument()
    expect(screen.getByText('Dad Laptop')).toBeInTheDocument()
  })

  // ── #2073: the panel is compact — it scrolls WITHIN a fixed max-height, not down the page ──
  it('caps the panel height and scrolls internally (#2073)', async () => {
    render(withQuery(<MemoryRouter><RecentlyBlockedSection /></MemoryRouter>))
    await screen.findByText('connectivitycheck.gstatic.com')
    const scroller = screen.getByTestId('recently-blocked-scroll')
    expect(scroller.className).toMatch(/overflow-y-auto/)
    expect(scroller.className).toMatch(/max-h-/)
  })

  it('§9.2 — the device name keeps its own deep-link to the device-filtered blocked events (#2073)', async () => {
    render(withQuery(<MemoryRouter><RecentlyBlockedSection /></MemoryRouter>))
    await screen.findByText('connectivitycheck.gstatic.com')
    // blockedRow.mac = aa:bb:cc:dd:ee:01 → the Connection Events page reads ?status=blocked (the
    // verified param, NOT ?blocked=true) plus ?mac= for the device.
    const deviceLink = screen.getByText("Kid's iPad").closest('a')
    expect(deviceLink).toHaveAttribute(
      'href',
      '/usage/events?status=blocked&mac=aa%3Abb%3Acc%3Add%3Aee%3A01',
    )
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

  // ── #2073: "View all →" lands on Connection Events pre-filtered to blocked (verified param) ──
  it('links "View all" to the Connection Events page pre-filtered to blocked (#2073)', async () => {
    render(withQuery(<MemoryRouter><RecentlyBlockedSection /></MemoryRouter>))
    await screen.findByText('connectivitycheck.gstatic.com')
    // The page parses ?status=blocked (LogsPage.parseStatus), so that is the param — not ?blocked=.
    expect(screen.getByText(/View all/).closest('a')).toHaveAttribute('href', '/usage/events?status=blocked')
  })

  it('shows an empty state when nothing is blocked', async () => {
    mockQuery().mockResolvedValue({ rows: [], nextCursor: null })
    render(withQuery(<MemoryRouter><RecentlyBlockedSection /></MemoryRouter>))
    expect(await screen.findByText(/Nothing blocked recently/)).toBeInTheDocument()
  })

  // ── #2601: the panel must not let enforcement happen invisibly ───────────────
  //
  // Prod, 2026-08-06: the `sameer` profile was dropped from Google Drive by an
  // `ads` category rule matching a SHARED Google GFE address, and the operator
  // checked the panel right when it happened and read its silence as "you were
  // not blocked". Three things let that happen, each pinned below: the panel
  // never said its view was only 15 minutes wide; an empty view rendered the
  // same whether the household had older blocks or none at all; and it asserted
  // a clean negative during the ~26s the router's spool/flush/ws hops take to
  // deliver the drop, so "nothing blocked" was a claim about data it could not
  // yet have.
  // That 26s is a transport defect and is fixed separately in #2620; this file
  // only covers what the panel discloses.

  it('#2601 — names the 15-minute window so an empty panel is not read as "never blocked"', async () => {
    render(withQuery(<MemoryRouter><RecentlyBlockedSection /></MemoryRouter>))
    await screen.findByText('connectivitycheck.gstatic.com')
    expect(screen.getByTestId('recently-blocked-window-label')).toHaveTextContent(/15 min/i)
  })

  it('#2601 — says older blocks exist when every fetched row is outside the 15-min window', async () => {
    // Exactly the prod shape: real drops in the 1h fetch, none inside the trailing
    // 15 min. Rendering the bare "Nothing blocked recently" here is the defect —
    // it is indistinguishable from a household that has never been blocked.
    mockQuery().mockResolvedValue({
      rows: [{ ...blockedRow, ts: new Date(Date.now() - 40 * 60_000).toISOString() }],
      nextCursor: null,
    })
    render(withQuery(<MemoryRouter><RecentlyBlockedSection /></MemoryRouter>))
    const stale = await screen.findByTestId('recently-blocked-stale-hint')
    // The count is singular here, and it names the FETCH span, not the display window.
    // This literal is the real pin: spelled out independently of the constants, so
    // widening RECENT_BLOCKED_FETCH_HOURS fails here (and at the `hours: 1` call-shape
    // assertion above) rather than silently changing the copy. Asserting against
    // RECENT_BLOCKED_FETCH_LABEL instead would compare the constant to itself.
    expect(stale).toHaveTextContent(/^1 block in the past hour, outside this window\./)
    // Points at the wider view rather than dead-ending on an empty list.
    expect(stale.querySelector('a')).toHaveAttribute('href', '/usage/events?status=blocked')
    // The stale row itself still must NOT masquerade as recent (#1338 contract).
    expect(screen.queryByText('connectivitycheck.gstatic.com')).not.toBeInTheDocument()
  })

  it('#2601 — recentBlockedFetchLabel covers both arms, so widening the span reads right', () => {
    // The plural arm is unreachable through RECENT_BLOCKED_FETCH_HOURS while it is 1, so
    // it gets covered here directly rather than being dead code nobody has ever run.
    expect(recentBlockedFetchLabel(1)).toBe('the past hour')
    expect(recentBlockedFetchLabel(3)).toBe('the past 3 hours')
  })

  it('#2601 — reports a saturated page as a floor ("20+"), not as an exact total', async () => {
    // olderCount is bounded by RECENT_BLOCKED_LIMIT, so a household with hundreds of
    // older drops must not be told it had exactly 20. Claiming precision the payload
    // cannot support is the same class of defect as the empty state this panel is fixing.
    const stale = new Date(Date.now() - 40 * 60_000).toISOString()
    mockQuery().mockResolvedValue({
      rows: Array.from({ length: 20 }, (_, i) => ({ ...blockedRow, id: 900 + i, ts: stale })),
      nextCursor: null,
    })
    render(withQuery(<MemoryRouter><RecentlyBlockedSection /></MemoryRouter>))
    expect(await screen.findByTestId('recently-blocked-stale-hint')).toHaveTextContent(/^20\+ blocks/)
  })

  it('#2601 — a failed REFETCH keeps the rows already on screen, under an error strip', async () => {
    // react-query flips status to 'error' on a failed background refetch while `data`
    // stays populated. Blanking a panel that is currently showing real drops would make
    // enforcement invisible again — the exact failure this issue is about.
    mockQuery()
      .mockResolvedValueOnce({ rows: [blockedRow], nextCursor: null })
      .mockRejectedValue(new Error('boom'))
    const client = makeTestQueryClient()
    render(withQuery(<MemoryRouter><RecentlyBlockedSection /></MemoryRouter>, client))
    await screen.findByText('connectivitycheck.gstatic.com')
    await act(async () => { await client.refetchQueries({ queryKey: ['dashboard', 'recent-blocked', null] }) })
    expect(await screen.findByTestId('recently-blocked-error')).toBeInTheDocument()
    // The last successful read is still rendered.
    expect(screen.getByText('connectivitycheck.gstatic.com')).toBeInTheDocument()
  })

  it('#2601 — a genuinely empty 1h fetch keeps the plain empty state, with no stale hint', async () => {
    // The two empty-reasons stay distinct: nothing at all in the fetched hour is not the
    // same as "there were blocks, just older than the window". No ingest-lag caveat here:
    // the ~26s delay that made a just-now block invisible belongs in the agent's spool and
    // ws-drain path (#2620), not explained away in the copy.
    mockQuery().mockResolvedValue({ rows: [], nextCursor: null })
    render(withQuery(<MemoryRouter><RecentlyBlockedSection /></MemoryRouter>))
    expect(await screen.findByText(/Nothing blocked recently/)).toBeInTheDocument()
    expect(screen.queryByTestId('recently-blocked-stale-hint')).not.toBeInTheDocument()
  })

  it('#2601 — a failed query shows an error affordance, not a permanent "Loading…"', async () => {
    // react-query leaves `data` undefined on error as well as while pending, so the
    // old `const { data = null }` collapsed both into the loading branch and the panel
    // claimed to still be working forever. docs/process/loading-states.md §error.
    mockQuery().mockRejectedValue(new Error('boom'))
    render(withQuery(<MemoryRouter><RecentlyBlockedSection /></MemoryRouter>))
    expect(await screen.findByTestId('recently-blocked-error')).toBeInTheDocument()
    expect(screen.queryByText(/Loading recent blocks/)).not.toBeInTheDocument()
    expect(screen.queryByText(/Nothing blocked recently/)).not.toBeInTheDocument()
  })

  // ── #2062: the "All devices ▾" quick device filter narrows the (grouped) list by mac ──
  it('narrows the list to a device and re-fetches the fallback poll by mac (#2062)', async () => {
    mockNow().mockResolvedValue(nowWithDevices)
    mockQuery().mockImplementation((p?: { macs?: string[] }) =>
      Promise.resolve({
        rows: p?.macs?.length ? [blockedRow] : [blockedRow, blockedRow2],
        nextCursor: null,
      }),
    )
    const user = userEvent.setup()
    render(withQuery(<MemoryRouter><RecentlyBlockedSection /></MemoryRouter>))
    // Unfiltered: both devices' drops show.
    await screen.findByText('doubleclick.net')
    // Pick Kid's iPad from the device filter (options drawn from the NOW snapshot).
    const filter = await screen.findByTestId('recently-blocked-device-filter')
    await user.selectOptions(filter, 'aa:bb:cc:dd:ee:01')
    // The fallback poll re-fetches narrowed to that mac (the live subscription narrows too).
    await waitFor(() =>
      expect(api.logs.query).toHaveBeenCalledWith({ blocked: true, limit: 20, hours: 1, macs: ['aa:bb:cc:dd:ee:01'] }),
    )
    // The other device's drop drops out of the narrowed list.
    await waitFor(() => expect(screen.queryByText('doubleclick.net')).not.toBeInTheDocument())
    // View all carries the active device filter.
    expect(screen.getByText(/View all/).closest('a')).toHaveAttribute(
      'href', '/usage/events?status=blocked&mac=aa%3Abb%3Acc%3Add%3Aee%3A01',
    )
  })

  it('keeps the filtered device as a sticky option when it ages out of the NOW snapshot (#2062)', async () => {
    // NOW starts with the device, then the next snapshot drops it (idle > 5 min) — the filter
    // must stay applied and the control keep showing it, not snap back to "All devices".
    mockNow().mockResolvedValueOnce(nowWithDevices).mockResolvedValue(emptyNow)
    mockQuery().mockImplementation((p?: { macs?: string[] }) =>
      Promise.resolve({ rows: p?.macs?.length ? [blockedRow] : [blockedRow, blockedRow2], nextCursor: null }),
    )
    const user = userEvent.setup()
    render(withQuery(<MemoryRouter><RecentlyBlockedSection /></MemoryRouter>))
    const filter = await screen.findByTestId('recently-blocked-device-filter')
    await user.selectOptions(filter, 'aa:bb:cc:dd:ee:01')
    // The now-absent device is still a selectable option and remains selected.
    expect((filter as HTMLSelectElement).value).toBe('aa:bb:cc:dd:ee:01')
    expect(within(filter).getByRole('option', { name: "Kid's iPad" })).toBeInTheDocument()
  })

  it('shows a device-scoped empty state when the filtered device has no recent blocks (#2062)', async () => {
    mockNow().mockResolvedValue(nowWithDevices)
    mockQuery().mockImplementation((p?: { macs?: string[] }) =>
      Promise.resolve({ rows: p?.macs?.length ? [] : [blockedRow, blockedRow2], nextCursor: null }),
    )
    const user = userEvent.setup()
    render(withQuery(<MemoryRouter><RecentlyBlockedSection /></MemoryRouter>))
    await screen.findByText('doubleclick.net')
    await user.selectOptions(await screen.findByTestId('recently-blocked-device-filter'), 'aa:bb:cc:dd:ee:01')
    expect(await screen.findByText("Nothing blocked recently for Kid's iPad")).toBeInTheDocument()
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
  it('renders an active card for an active profile; idle profiles collapse below', async () => {
    mockNow().mockResolvedValue(liveNow)
    render(withQuery(<NowSection />))
    const kids = await screen.findByTestId('now-profile-1')
    expect(kids).toBeInTheDocument()
    // The idle "Adults" profile is no longer a top-level dimmed card — it lives in
    // the idle-collapse row (#820), so it isn't rendered as its own card by default.
    expect(screen.queryByTestId('now-profile-2')).not.toBeInTheDocument()
    const collapse = screen.getByTestId('now-idle-collapse')
    expect(within(collapse).getByText(/Idle \(1\)/)).toBeInTheDocument()
    expect(within(collapse).getByText(/Adults/)).toBeInTheDocument()
  })

  it('shows device name and top hosts; no per-row timestamp for a fresh row (#825)', async () => {
    mockNow().mockResolvedValue(liveNow)
    render(withQuery(<NowSection />))
    await screen.findByTestId('now-device-aa:bb:cc:dd:ee:01')
    expect(screen.getByText('iPhone')).toBeInTheDocument()
    // iPhone's lastSeenSeconds (30) is not materially stale vs the snapshot, so no
    // inline "Xs ago" — the single freshness pill in the header covers freshness.
    expect(screen.queryByText('30s ago')).not.toBeInTheDocument()
    expect(screen.getAllByText('youtube.com').length).toBeGreaterThan(0)
    expect(screen.getByText('tiktok.com')).toBeInTheDocument()
    expect(screen.getByText('14m')).toBeInTheDocument()
  })

  // ── #1835: top-N device cap (#819) ──────────────────────────────────────────
  it('caps an active card at the top-3 devices ranked by active-seconds, not recency (#819/Q6)', async () => {
    mockNow().mockResolvedValue(familyNow)
    render(withQuery(<NowSection />))
    const card = await screen.findByTestId('now-profile-5')
    // Top 3 by summed active-seconds: Streamer (900) · Browser (600) · Music (300).
    expect(within(card).getByText('Streamer')).toBeInTheDocument()
    expect(within(card).getByText('Browser')).toBeInTheDocument()
    expect(within(card).getByText('Music')).toBeInTheDocument()
    // "Recent" is the newest device but the least active — a recency rank would
    // surface it; the active-seconds rank keeps it hidden behind the expander.
    expect(within(card).queryByText('Recent')).not.toBeInTheDocument()
    expect(within(card).queryByText('Dev11')).not.toBeInTheDocument()
  })

  it('renders a "show 8 more" expander on an 11-device card and reveals the rest in place (#819)', async () => {
    const user = userEvent.setup()
    mockNow().mockResolvedValue(familyNow)
    render(withQuery(<NowSection />))
    const card = await screen.findByTestId('now-profile-5')
    const more = within(card).getByRole('button', { name: /show 8 more/i })
    expect(more).toBeInTheDocument()
    await user.click(more)
    // Expanding reveals the rest in the same card (no modal / new section).
    expect(within(card).getByText('Recent')).toBeInTheDocument()
    expect(within(card).getByText('Dev11')).toBeInTheDocument()
    expect(within(card).getByRole('button', { name: /show fewer/i })).toBeInTheDocument()
  })

  it('persists the card expander across remounts via sessionStorage (#819)', async () => {
    const user = userEvent.setup()
    mockNow().mockResolvedValue(familyNow)
    const client = makeTestQueryClient()
    const { unmount } = render(withQuery(<NowSection />, client))
    const card = await screen.findByTestId('now-profile-5')
    await user.click(within(card).getByRole('button', { name: /show 8 more/i }))
    expect(sessionStorage.getItem('wh.now.card.5')).toBe('1')
    // Remount (e.g. SPA navigation away and back) — expanded state survives.
    unmount()
    render(withQuery(<NowSection />, client))
    const reCard = await screen.findByTestId('now-profile-5')
    expect(within(reCard).getByText('Recent')).toBeInTheDocument()
    expect(within(reCard).getByRole('button', { name: /show fewer/i })).toBeInTheDocument()
  })

  // ── #1835: idle-profile collapse (#820) ─────────────────────────────────────
  it('collapses zero-active profiles into one row, expandable to dimmed cards with paused tags (#820)', async () => {
    const user = userEvent.setup()
    mockNow().mockResolvedValue(idleNow)
    render(withQuery(<NowSection />))
    await screen.findByTestId('now-profile-1') // active "Kids" card
    const collapse = screen.getByTestId('now-idle-collapse')
    // One ≤1-line row naming the three idle profiles.
    expect(within(collapse).getByText(/Idle \(3\)/)).toBeInTheDocument()
    for (const name of ['Adults', 'Guest', 'IoT']) {
      expect(within(collapse).getByText(new RegExp(name))).toBeInTheDocument()
    }
    // Collapsed: no dimmed cards yet.
    expect(screen.queryByTestId('now-profile-3')).not.toBeInTheDocument()
    // Expand in place → full dimmed cards; the paused-and-idle "Guest" keeps its tag.
    await user.click(within(collapse).getByRole('button'))
    const guest = await screen.findByTestId('now-profile-3')
    expect(guest).toHaveClass('opacity-60')
    expect(within(guest).getByText('Paused')).toBeInTheDocument()
    expect(screen.getByTestId('now-profile-2')).toHaveClass('opacity-60')
  })

  it('does not reorder active profiles when idle ones collapse (#820)', async () => {
    mockNow().mockResolvedValue(idleNow)
    render(withQuery(<NowSection />))
    const kids = await screen.findByTestId('now-profile-1')
    const collapse = screen.getByTestId('now-idle-collapse')
    // Active card precedes the idle-collapse row in document order.
    expect(kids.compareDocumentPosition(collapse) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('persists the idle-collapse expansion via sessionStorage (#820)', async () => {
    const user = userEvent.setup()
    mockNow().mockResolvedValue(idleNow)
    render(withQuery(<NowSection />))
    const collapse = await screen.findByTestId('now-idle-collapse')
    await user.click(within(collapse).getByRole('button'))
    expect(sessionStorage.getItem('wh.now.idle')).toBe('1')
  })

  // ── #1835: single freshness pill (#825) ─────────────────────────────────────
  it('shows exactly one freshness pill in the header, sourced from dataUpdatedAt (#825)', async () => {
    mockNow().mockResolvedValue(liveNow)
    render(withQuery(<NowSection />))
    const pill = await screen.findByTestId('now-freshness')
    expect(pill).toHaveTextContent(/updated .*ago/i)
    // It's the ONE freshness indicator — typical device rows carry no timestamp.
    expect(screen.getByTestId('now-device-aa:bb:cc:dd:ee:01')).toBeInTheDocument()
    expect(screen.queryByText('30s ago')).not.toBeInTheDocument()
  })

  it('still flags a materially-stale row (>60s vs snapshot) inline (#825)', async () => {
    mockNow().mockResolvedValue(staleNow)
    render(withQuery(<NowSection />))
    await screen.findByText('Stale')
    // The fresh row (30s) has no inline timestamp; the stale row (120s) does.
    expect(screen.getByText('2m ago')).toBeInTheDocument()
    expect(screen.queryByText('30s ago')).not.toBeInTheDocument()
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

  // ── #2056 §8.5: per-profile ▲▼ rate on the card header, driven by the global window ──
  it('renders the per-profile ▲▼ rate on the card header, keyed by profile name (§8.5)', async () => {
    mockNow().mockResolvedValue(liveNow) // a "Kids" profile card
    render(withQuery(<NowSection bandwidth={liveBandwidth({
      bucket: '1m',
      rows: [profileRow('Kids', 6 * 1024 * 1024, 1024 * 1024)],
    })} />))
    const card = await screen.findByTestId('now-profile-1')
    const rate = within(card).getByTestId('now-profile-rate-1')
    expect(rate).toHaveTextContent('▲')
    expect(rate).toHaveTextContent('▼')
  })

  it('shows "—" (not ▲0 ▼0) on the header when a profile has no live-edge rate (§8.5)', async () => {
    mockNow().mockResolvedValue(liveNow)
    // Live socket, but no row for "Kids" → unknown rate → em-dash, not a measured zero.
    render(withQuery(<NowSection bandwidth={liveBandwidth({ rows: [] })} />))
    const card = await screen.findByTestId('now-profile-1')
    const rate = within(card).getByTestId('now-profile-rate-1')
    expect(rate).toHaveTextContent('—')
    expect(rate).not.toHaveTextContent('▲')
  })

  it('re-derives the header ▲▼ when the global window changes (smaller rate at 10m than 1m) (§8.5)', async () => {
    mockNow().mockResolvedValue(liveNow)
    const rows = [profileRow('Kids', 600 * 1024 * 1024, 0)]
    const { rerender } = render(withQuery(<NowSection bandwidth={liveBandwidth({ bucket: '1m', rows })} />))
    const at1m = within(await screen.findByTestId('now-profile-1')).getByTestId('now-profile-rate-1').textContent
    rerender(withQuery(<NowSection bandwidth={liveBandwidth({ bucket: '10m', rows })} />))
    const at10m = within(await screen.findByTestId('now-profile-1')).getByTestId('now-profile-rate-1').textContent
    // Same bytes ÷ a 10× wider bucket ⇒ a visibly smaller rate: the NOW card reads the
    // page-wide window, not a per-card one.
    expect(at1m).not.toEqual(at10m)
  })

  // ── #2056 §9.3: IoT/appliance filtering — hidden unless anomalous ──
  it('hides a non-anomalous appliance device from the NOW card (§9.3)', async () => {
    mockNow().mockResolvedValue({
      asOf: '2026-05-13T10:00:00Z',
      profiles: [{
        id: 1, name: 'Family', paused: false,
        activeDevices: [
          dev('MacBook', 20, 300),
          { ...dev('Sonos', 30, 500), kind: 'appliance' },
        ],
      }],
    })
    render(withQuery(<NowSection />))
    const card = await screen.findByTestId('now-profile-1')
    expect(within(card).getByText('MacBook')).toBeInTheDocument()
    // The appliance is filtered out of the personal-device list (the §9.4 gate is OFF here
    // because the fixture supplies the classification the server will eventually send).
    expect(within(card).queryByText('Sonos')).not.toBeInTheDocument()
  })

  it('surfaces an appliance ONLY when its traffic is anomalous, flagged ⚠ unusual (§9.3)', async () => {
    mockNow().mockResolvedValue({
      asOf: '2026-05-13T10:00:00Z',
      profiles: [{
        id: 1, name: 'Family', paused: false,
        activeDevices: [
          dev('MacBook', 20, 300),
          { ...dev('B-Hyve', 30, 500), kind: 'appliance', anomalous: true },
        ],
      }],
    })
    render(withQuery(<NowSection />))
    const card = await screen.findByTestId('now-profile-1')
    // The anomalous appliance surfaces (likely-compromise signal), flagged as unusual.
    expect(within(card).getByText(/B-Hyve/)).toBeInTheDocument()
    expect(within(card).getByText(/unusual/)).toBeInTheDocument()
  })

  it('collapses a profile whose only activity is hidden appliance chatter into the idle row (§9.3)', async () => {
    mockNow().mockResolvedValue({
      asOf: '2026-05-13T10:00:00Z',
      profiles: [
        { id: 1, name: 'Kids', paused: false, activeDevices: [dev('iPhone', 30, 120)] },
        {
          id: 2, name: 'IoT', paused: false,
          activeDevices: [{ ...dev('Printer', 30, 60), kind: 'appliance' }],
        },
      ],
    })
    render(withQuery(<NowSection />))
    await screen.findByTestId('now-profile-1')
    // The IoT-only profile has no VISIBLE devices → it is idle, not an empty active card.
    expect(screen.queryByTestId('now-profile-2')).not.toBeInTheDocument()
    const collapse = screen.getByTestId('now-idle-collapse')
    expect(within(collapse).getByText(/Idle \(1\)/)).toBeInTheDocument()
    expect(within(collapse).getByText(/IoT/)).toBeInTheDocument()
  })
})

// ── #2069: a CHILD dashboard must not call admin-only `/api/stats` ──
//
// `/api/stats` is admin-only; a child hitting it 403s (the prod storm). So a child never
// requests it, and the stats-backed panels (the Events/Blocked (1h) tiles + the 24h Blocking
// activity panel) are dropped rather than skeleton-forever. The child-safe live surfaces
// (Online now / Blocked now from `/api/dashboard/now`) still render.
describe('DashboardPage child role (#2069)', () => {
  afterEach(() => localStorage.clear())

  function renderAsChild() {
    localStorage.setItem('token', 'tok')
    localStorage.setItem('username', 'octavius')
    localStorage.setItem('role', 'child')
    ;(api.auth.me as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      username: 'octavius', role: 'child', profileIds: [1],
    })
    return render(
      withQuery(<AuthProvider><MemoryRouter><DashboardPage /></MemoryRouter></AuthProvider>),
    )
  }

  it('never calls admin-only /api/stats and drops the stats-backed panels', async () => {
    renderAsChild()
    // the child-safe KPI strip still renders (Online now / Blocked now from /api/dashboard/now).
    const strip = await screen.findByTestId('kpi-strip')
    expect(within(strip).getByText('Online now')).toBeInTheDocument()
    expect(within(strip).getByText('Blocked now')).toBeInTheDocument()
    // admin-only /api/stats is never requested…
    expect(api.logs.stats).not.toHaveBeenCalled()
    // …and its panels/tiles are absent.
    expect(screen.queryByText('Events (1h)')).not.toBeInTheDocument()
    expect(screen.queryByText('Blocked (1h)')).not.toBeInTheDocument()
    expect(screen.queryByText('Blocking activity (24h)')).not.toBeInTheDocument()
  })
})

// #2522 — the dashboard mixes the two capabilities on one page. `/api/stats` moved to
// `requireWriter`, so an adult now gets the stats-backed KPI tiles; the first-run onboarding
// banner reads `GET /api/admin/routers`, which is still `requireAdmin`, so an adult must not
// mount it (it would fire a fetch the server refuses and stick in an error state).
describe('DashboardPage — capability split (#2522)', () => {
  function renderAs(role: 'admin' | 'adult' | 'child') {
    localStorage.setItem('token', 't')
    localStorage.setItem('username', role)
    localStorage.setItem('role', role)
    return render(withQuery(
      <AuthProvider>
        <MemoryRouter><DashboardPage /></MemoryRouter>
      </AuthProvider>,
    ))
  }

  beforeEach(() => {
    // No router enrolled → the onboarding banner WOULD render, for a caller allowed to ask.
    vi.mocked(api.routers.list).mockResolvedValue([])
  })

  it('serves the stats-backed KPI tiles to an adult (/api/stats is requireWriter)', async () => {
    renderAs('adult')
    expect(await screen.findByText('78')).toBeInTheDocument()
    expect(api.logs.stats).toHaveBeenCalled()
  })

  it('hides the first-run router banner from an adult, and never asks for the router list', async () => {
    renderAs('adult')
    await screen.findByTestId('kpi-strip')
    expect(screen.queryByTestId('first-run-hint')).not.toBeInTheDocument()
    expect(api.routers.list).not.toHaveBeenCalled()
  })

  it('still shows the first-run router banner to an admin', async () => {
    renderAs('admin')
    expect(await screen.findByTestId('first-run-hint')).toBeInTheDocument()
  })

  it('still keeps /api/stats away from a child', async () => {
    renderAs('child')
    await screen.findByTestId('kpi-strip')
    expect(api.logs.stats).not.toHaveBeenCalled()
  })
})
