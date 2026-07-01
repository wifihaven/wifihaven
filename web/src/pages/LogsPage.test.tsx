import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useLocation } from 'react-router-dom'
import type { ConnectionEventAggRow, Device, ProfileDetail, QueryLog } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    logs:     { query: vi.fn(), series: vi.fn() },
    devices:  { list:  vi.fn() },
    profiles: { list:  vi.fn() },
    apps:     { list:  vi.fn() },
    auth:     { me:    vi.fn() },
  },
}))

import { api } from '@/api/client'
import { LogsPage } from './LogsPage'
import { withQuery } from '@/test/queryWrapper'
import { AuthProvider } from '@/hooks/useAuth'

const log1: QueryLog = {
  id: 1, mac: 'aa:bb:cc:dd:ee:01', deviceName: "Kid's iPad",
  profileId: 1, profileName: 'Kids',
  host: { type: 'fqdn', value: 'example.com' }, qtype: 1, blocked: false, reason: { kind: 'allow' },
  location: 'home', ts: '2026-05-12T10:15:30Z',
}

const aggRow: ConnectionEventAggRow = {
  groups: { domain: 'youtube.com' },
  windowStart: '2026-05-22T14:00:00Z',
  countSucceeded: 12,
  countBlocked: 3,
  lastSeen: '2026-05-22T14:30:00Z',
  topDevice: "Kid's iPad",
  distinctDevices: 2,
  distinctProfiles: 1,
  distinctDomains: 1,
}

const devices: Device[] = [
  { id: 10, mac: 'aa:bb:cc:dd:ee:01', name: "Kid's iPad", profileId: 1, profileName: 'Kids', lastSeenIp: null, lastSeenAt: null },
]
const profileDetails: ProfileDetail[] = [
  { profile: { id: 1, name: 'Kids', blockedCategories: [], paused: false, failureMode: 'block-all', crossDeviceOverlapMode: 'sum', pauseMode: 'soft', defaultDeny: false }, timeLimit: null },
]

// Surfaces the current URL search string so tests can assert query-param
// persistence under MemoryRouter (which doesn't touch window.location).
function LocationProbe() {
  const loc = useLocation()
  return <div data-testid="location-search">{loc.search}</div>
}

function renderAt(path = '/usage/events') {
  // #2069: LogsPage now reads `useDataScope` (→ `useMe` via React Query), so the
  // harness must provide a QueryClient. `useMe` stays disabled here (no auth
  // provider ⇒ not a child), so no `/api/me` fetch is issued.
  return render(
    withQuery(
      <MemoryRouter initialEntries={[path]}>
        <LogsPage />
        <LocationProbe />
      </MemoryRouter>,
    ),
  )
}

// #951 — calendar popover seeds on "now"; click prev/next month until the
// header shows the target.
async function navigateToMonth(targetYear: number, targetMonth0: number) {
  const target = new Date(targetYear, targetMonth0, 1)
  for (let i = 0; i < 60; i++) {
    const popover = screen.getByTestId('jump-to-date-popover')
    const header  = popover.querySelector('.uppercase')?.textContent ?? ''
    const probe   = new Date(`${header} 1`)
    if (!Number.isNaN(probe.getTime())
        && probe.getFullYear() === target.getFullYear()
        && probe.getMonth() === target.getMonth()) return
    const goPrev = probe.getTime() > target.getTime()
    await userEvent.click(screen.getByTestId(goPrev ? 'jump-to-date-prev-month' : 'jump-to-date-next-month'))
  }
  throw new Error(`navigateToMonth: gave up trying to reach ${targetYear}-${targetMonth0 + 1}`)
}

beforeEach(() => {
  vi.resetAllMocks()
  ;(api.logs.query    as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ rows: [log1], nextCursor: null })
  ;(api.logs.series   as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ rows: [aggRow], nextCursor: null })
  ;(api.devices.list  as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(devices)
  ;(api.profiles.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(profileDetails)
  // #769: default to "one app exists" so groupBy=app doesn't trip the empty-state.
  ;(api.apps.list     as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([
    { app: { id: 1, name: 'YouTube', slug: 'youtube' } },
  ])
})

describe('LogsPage (Connection Events) — raw view', () => {
  it('calls api.logs.query and renders rows including device + profile', async () => {
    renderAt()
    expect(await screen.findByText('example.com')).toBeInTheDocument()
    expect(api.logs.query).toHaveBeenCalled()
    expect(screen.getByText("Kid's iPad")).toBeInTheDocument()
    expect(screen.getAllByText('Kids').length).toBeGreaterThan(0)
  })

  // #861 — verify low-priority columns carry responsive `hidden` classes so
  // the table fits at phone (~375px) and tablet (~768px) widths.
  it('hides low-priority raw-view columns on narrow viewports', async () => {
    renderAt()
    await screen.findByText('example.com')
    expect(screen.getByRole('columnheader', { name: /^Profile/ }).className).toMatch(/hidden md:table-cell/)
    expect(screen.getByRole('columnheader', { name: 'Reason' }).className).toMatch(/hidden sm:table-cell/)
    expect(screen.getByRole('columnheader', { name: 'Location' }).className).toMatch(/hidden lg:table-cell/)
  })

  it('shows empty state when no events', async () => {
    (api.logs.query as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ rows: [], nextCursor: null })
    renderAt()
    expect(await screen.findByText('No events in window.')).toBeInTheDocument()
  })

  // #968: while a fetch is in flight, the empty-state copy must not appear
  // above the loading spinner. Gate empty-state on !loading.
  it('does not render empty-state copy while loading', async () => {
    let resolve: (v: { rows: QueryLog[]; nextCursor: string | null }) => void = () => {}
    ;(api.logs.query as unknown as ReturnType<typeof vi.fn>).mockReturnValueOnce(
      new Promise<{ rows: QueryLog[]; nextCursor: string | null }>(r => { resolve = r }),
    )
    renderAt()
    await waitFor(() => expect(api.logs.query).toHaveBeenCalled())
    expect(screen.getByTestId('loading')).toBeInTheDocument()
    expect(screen.queryByText('No events in window.')).not.toBeInTheDocument()
    resolve({ rows: [], nextCursor: null })
    await waitFor(() => expect(screen.getByText('No events in window.')).toBeInTheDocument())
    expect(screen.queryByTestId('loading')).not.toBeInTheDocument()
  })
})

describe('LogsPage — aggregation', () => {
  // #917: default state has no toggles on — one row per time bucket.
  it('switching to 1h triggers /series with groupBy=[] by default', async () => {
    renderAt()
    await screen.findByText('example.com')
    await userEvent.click(screen.getByTestId('bucket-1h'))
    await waitFor(() => {
      expect(api.logs.series).toHaveBeenLastCalledWith(expect.objectContaining({
        bucket: '1h',
        groupBy: [],
      }))
    })
    expect(await screen.findByTestId('ce-agg-table')).toBeInTheDocument()
  })

  // #917: strictly additive — toggling a column on adds rows; toggling off
  // removes them. We can go all the way back to []. Never the inverse.
  it('clicking the Device column header strictly adds device to groupBy', async () => {
    renderAt()
    await screen.findByText('example.com')
    await userEvent.click(screen.getByTestId('bucket-1h'))
    await waitFor(() => expect(api.logs.series).toHaveBeenCalled())
    await userEvent.click(screen.getByTestId('ce-group-device'))
    await waitFor(() => {
      const calls = (api.logs.series as ReturnType<typeof vi.fn>).mock.calls
      expect(calls[calls.length - 1][0].groupBy).toEqual(['device'])
    })
    // Toggling off returns to [] (no implicit "must keep one" guard).
    await userEvent.click(screen.getByTestId('ce-group-device'))
    await waitFor(() => {
      const calls = (api.logs.series as ReturnType<typeof vi.fn>).mock.calls
      expect(calls[calls.length - 1][0].groupBy).toEqual([])
    })
  })

  it('initializes groupBy from URL (?groupBy=device&groupBy=domain)', async () => {
    renderAt('/usage/events?groupBy=device&groupBy=domain')
    await screen.findByText('example.com')
    await userEvent.click(screen.getByTestId('bucket-1h'))
    await waitFor(() => {
      const calls = (api.logs.series as ReturnType<typeof vi.fn>).mock.calls
      expect(calls[calls.length - 1][0].groupBy.sort()).toEqual(['device', 'domain'])
    })
  })

  // #769: when groupBy=app is active but no apps exist, render the empty-state
  // instead of the aggregate table. The link points the operator at /apps.
  it('renders empty-state when groupBy=app but household has no apps', async () => {
    (api.apps.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
    renderAt('/usage/events?groupBy=app')
    await screen.findByText('example.com')
    await userEvent.click(screen.getByTestId('bucket-1h'))
    expect(await screen.findByTestId('ce-app-empty')).toBeInTheDocument()
    expect(screen.queryByTestId('ce-agg-table')).not.toBeInTheDocument()
  })

  // #769: with apps defined the toggle is functional and the response's
  // appName/appIcon are surfaced in the row.
  it('renders app icon + display name when groupBy=app and rows arrive', async () => {
    (api.logs.series as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      rows: [
        {
          ...aggRow,
          groups: { app: 'youtube' },
          appId: 1,
          appName: 'YouTube',
          appIcon: '📺',
        },
      ],
      nextCursor: null,
    })
    renderAt('/usage/events?groupBy=app')
    await screen.findByText('example.com')
    await userEvent.click(screen.getByTestId('bucket-1h'))
    expect(await screen.findByText('YouTube')).toBeInTheDocument()
    expect(screen.getByText('📺')).toBeInTheDocument()
  })
})

describe('LogsPage — infinite scroll (#862)', () => {
  it('end-of-stream marker appears when nextCursor is null', async () => {
    renderAt()
    expect(await screen.findByText('example.com')).toBeInTheDocument()
    // First (and only) page has nextCursor=null → end-of-stream rendered.
    expect(await screen.findByTestId('end-of-stream')).toBeInTheDocument()
  })

  it('scroll triggers a cursor-bearing fetch when more pages remain', async () => {
    const queryMock = api.logs.query as unknown as ReturnType<typeof vi.fn>
    const log2 = { ...log1, id: 2, host: { type: 'fqdn', value: 'older-page.com' } }
    // Default falls back to the second page so any extra triggers (from
    // accumulated IO callbacks across re-renders) stay deterministic.
    queryMock
      .mockReset()
      .mockResolvedValueOnce({ rows: [log1], nextCursor: 'cursor-1' })
      .mockResolvedValue({ rows: [log2], nextCursor: null })

    renderAt()
    await screen.findByText('example.com')
    expect(queryMock.mock.calls[0][0].cursor).toBeUndefined()

    // @ts-expect-error — test helper from setup.ts
    globalThis.__triggerIntersection()

    // Some cursor-carrying call must have fired with our cursor.
    await waitFor(() =>
      expect(
        queryMock.mock.calls.some(c => c[0]?.cursor === 'cursor-1'),
      ).toBe(true),
    )
    expect((await screen.findAllByText('older-page.com')).length).toBeGreaterThan(0)
    expect(await screen.findByTestId('end-of-stream')).toBeInTheDocument()
  })

})

describe('LogsPage — status filter (#1432)', () => {
  it('defaults to All — /api/logs called without a blocked filter, funnel inactive', async () => {
    const queryMock = api.logs.query as unknown as ReturnType<typeof vi.fn>
    renderAt()
    await waitFor(() => expect(queryMock).toHaveBeenCalled())
    expect(queryMock.mock.calls[0][0].blocked).toBeUndefined()
    // The status filter is a funnel on the Status column header, like the
    // device/profile filters — inactive (no count badge) by default.
    expect(screen.getAllByTestId('ce-filter-status').length).toBeGreaterThan(0)
    expect(screen.queryByTestId('ce-filter-status-count')).not.toBeInTheDocument()
  })

  it('checking Blocked in the Status header filter sets ?status=blocked and passes blocked=true', async () => {
    const queryMock = api.logs.query as unknown as ReturnType<typeof vi.fn>
    // server filters; mock returns only blocked rows when asked for blocked-only
    const blockedLog: QueryLog = {
      ...log1, id: 2, host: { type: 'fqdn', value: 'pornhub.com' },
      blocked: true, reason: { kind: 'category', slug: 'adult' },
    }
    queryMock.mockImplementation((p: { blocked?: boolean }) =>
      Promise.resolve({ rows: p.blocked ? [blockedLog] : [log1, blockedLog], nextCursor: null }),
    )
    renderAt()
    await screen.findByText('example.com')

    await userEvent.click(screen.getByTestId('ce-filter-status'))
    await userEvent.click(screen.getByTestId('ce-filter-status-opt-blocked'))
    await waitFor(() => {
      const last = queryMock.mock.calls[queryMock.mock.calls.length - 1][0]
      expect(last.blocked).toBe(true)
    })
    // URL persists the selection.
    await waitFor(() =>
      expect(screen.getByTestId('location-search').textContent).toContain('status=blocked'),
    )
    // only the blocked row renders
    expect(await screen.findByText('pornhub.com')).toBeInTheDocument()
    await waitFor(() => expect(screen.queryByText('example.com')).not.toBeInTheDocument())
  })

  it('checking Allowed passes blocked=false to /api/logs', async () => {
    const queryMock = api.logs.query as unknown as ReturnType<typeof vi.fn>
    renderAt()
    await screen.findByText('example.com')
    await userEvent.click(screen.getByTestId('ce-filter-status'))
    await userEvent.click(screen.getByTestId('ce-filter-status-opt-allowed'))
    await waitFor(() => {
      const last = queryMock.mock.calls[queryMock.mock.calls.length - 1][0]
      expect(last.blocked).toBe(false)
    })
  })

  it('checking both Blocked and Allowed clears the filter (Blocked OR Allowed = all)', async () => {
    const queryMock = api.logs.query as unknown as ReturnType<typeof vi.fn>
    renderAt('/usage/events?status=blocked')
    await screen.findByText('example.com')
    await userEvent.click(screen.getByTestId('ce-filter-status'))
    await userEvent.click(screen.getByTestId('ce-filter-status-opt-allowed'))
    await waitFor(() => {
      const last = queryMock.mock.calls[queryMock.mock.calls.length - 1][0]
      expect(last.blocked).toBeUndefined()
    })
  })

  it('initializes from ?status=blocked and passes blocked=true on first fetch', async () => {
    const queryMock = api.logs.query as unknown as ReturnType<typeof vi.fn>
    renderAt('/usage/events?status=blocked')
    await waitFor(() => expect(queryMock).toHaveBeenCalled())
    expect(queryMock.mock.calls[0][0].blocked).toBe(true)
    // funnel shows its active count badge
    expect(screen.getByTestId('ce-filter-status-count')).toBeInTheDocument()
  })

  it('aggregated view also passes the blocked filter to /connection-events/series', async () => {
    const seriesMock = api.logs.series as unknown as ReturnType<typeof vi.fn>
    renderAt('/usage/events?status=blocked')
    await userEvent.click(screen.getByTestId('bucket-1h'))
    await waitFor(() => expect(seriesMock).toHaveBeenCalled())
    expect(seriesMock.mock.calls[0][0].blocked).toBe(true)
  })
})

describe('LogsPage — jump-to-date (#951)', () => {
  it('setting the picker re-anchors `until` on /api/logs and updates the URL', async () => {
    const queryMock = api.logs.query as unknown as ReturnType<typeof vi.fn>
    renderAt()
    await waitFor(() => expect(queryMock).toHaveBeenCalledTimes(1))
    expect(queryMock.mock.calls[0][0].until).toBeUndefined()

    // #951: open calendar popover, click May 21 2026, set time 14:00, apply.
    await userEvent.click(screen.getByTestId('jump-to-date-trigger'))
    await navigateToMonth(2026, 4) // 0-indexed: May = 4
    await userEvent.click(screen.getByTestId('jump-to-date-day-2026-05-21'))
    const hh = screen.getByTestId('jump-to-date-hh') as HTMLInputElement
    const mm = screen.getByTestId('jump-to-date-mm') as HTMLInputElement
    await userEvent.clear(hh); await userEvent.type(hh, '14')
    await userEvent.clear(mm); await userEvent.type(mm, '00')
    await userEvent.click(screen.getByTestId('jump-to-date-apply'))

    await waitFor(() => expect(queryMock).toHaveBeenCalledTimes(2))
    const anchored = queryMock.mock.calls[queryMock.mock.calls.length - 1][0]
    expect(anchored.until).toBe(new Date(2026, 4, 21, 14, 0, 0, 0).toISOString())
    // URL round-trip is covered by the next test (reading ?until= back in).

    await userEvent.click(screen.getByTestId('jump-to-date-now'))
    await waitFor(() => expect(queryMock).toHaveBeenCalledTimes(3))
    const cleared = queryMock.mock.calls[queryMock.mock.calls.length - 1][0]
    expect(cleared.until).toBeUndefined()
  })

  it('initializes `until` from ?until= and passes it to /api/logs', async () => {
    const queryMock = api.logs.query as unknown as ReturnType<typeof vi.fn>
    const seed = '2026-05-21T14:00:00.000Z'
    renderAt(`/usage/events?until=${encodeURIComponent(seed)}`)
    await waitFor(() => expect(queryMock).toHaveBeenCalledTimes(1))
    expect(queryMock.mock.calls[0][0].until).toBe(seed)
  })

  it('aggregated view also passes `until` to /connection-events/series', async () => {
    const seriesMock = api.logs.series as unknown as ReturnType<typeof vi.fn>
    const seed = '2026-05-21T14:00:00.000Z'
    renderAt(`/usage/events?until=${encodeURIComponent(seed)}`)
    await userEvent.click(screen.getByTestId('bucket-1h'))
    await waitFor(() => expect(seriesMock).toHaveBeenCalled())
    expect(seriesMock.mock.calls[0][0].until).toBe(seed)
  })
})

// ── #2069: a CHILD only gets the raw `/api/logs` view ──
//
// The aggregated `/connection-events/series` is admin/adult-only; a child hitting it 403s.
// So a child is offered ONLY the raw bucket, and the aggregated series is never requested.
// (Raw `/api/logs` is child-safe: the server post-filters it to the child's visible profiles.)
describe('LogsPage child scoping (#2069)', () => {
  function renderAsChild(path = '/usage/events') {
    localStorage.setItem('token', 'tok')
    localStorage.setItem('username', 'octavius')
    localStorage.setItem('role', 'child')
    ;(api.auth.me as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      username: 'octavius', role: 'child', profileIds: [1],
    })
    return render(
      withQuery(
        <AuthProvider>
          <MemoryRouter initialEntries={[path]}>
            <LogsPage />
          </MemoryRouter>
        </AuthProvider>,
      ),
    )
  }

  afterEach(() => localStorage.clear())

  it('offers only the raw bucket and never calls /connection-events/series', async () => {
    renderAsChild()
    // raw view loads (child-safe /api/logs)…
    await waitFor(() => expect(api.logs.query).toHaveBeenCalled())
    // …and the aggregated bucket options are not offered.
    expect(screen.getByTestId('bucket-raw')).toBeInTheDocument()
    expect(screen.queryByTestId('bucket-1m')).not.toBeInTheDocument()
    expect(screen.queryByTestId('bucket-1h')).not.toBeInTheDocument()
    // the admin/adult-only aggregate series is never requested.
    expect(api.logs.series).not.toHaveBeenCalled()
  })
})
