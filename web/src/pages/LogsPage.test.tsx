import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import type { ConnectionEventAggRow, Device, ProfileDetail, QueryLog } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    logs:     { query: vi.fn(), series: vi.fn(), macDrops: vi.fn() },
    devices:  { list:  vi.fn() },
    profiles: { list:  vi.fn() },
    apps:     { list:  vi.fn() },
  },
}))

import { api } from '@/api/client'
import { LogsPage } from './LogsPage'

const log1: QueryLog = {
  id: 1, mac: 'aa:bb:cc:dd:ee:01', deviceName: "Kid's iPad",
  profileId: 1, profileName: 'Kids',
  host: { type: 'fqdn', value: 'example.com' }, qtype: 1, blocked: false, reason: '',
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
  { profile: { id: 1, name: 'Kids', blockedCategories: [], paused: false, failureMode: 'block-all', crossDeviceOverlapMode: 'sum' }, schedules: [], timeLimit: null },
]

function renderAt(path = '/usage/events') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <LogsPage />
    </MemoryRouter>,
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
  // #1103: default to "no drops" so the panel stays hidden in unrelated tests.
  ;(api.logs.macDrops as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ rows: [] })
})

describe('LogsPage — MAC drops panel (#1103)', () => {
  it('renders a visible signal when /api/mac-drops/series returns drops while connection_events is empty', async () => {
    // The bug we're guarding: kid-iPad is firewall-blocked, so dnsmasq
    // never sees its traffic. `/api/connection-events/series` (and /api/logs)
    // return nothing for that MAC, but `/api/mac-drops/series` reports the
    // drops. The SPA must still surface a "blocked: paused" signal.
    (api.logs.query    as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ rows: [], nextCursor: null })
    ;(api.logs.series   as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ rows: [], nextCursor: null })
    ;(api.logs.macDrops as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      rows: [
        {
          windowStart: '2026-05-22T14:00:00Z',
          mac: 'aa:bb:cc:dd:ee:01',
          reason: 'Paused',
          packets: 42,
          bytes: 9001,
        },
      ],
    })
    renderAt()
    expect(await screen.findByTestId('mac-drops-panel')).toBeInTheDocument()
    expect(api.logs.macDrops).toHaveBeenCalled()
    const reasonChip = await screen.findByTestId('mac-drops-reason-aa:bb:cc:dd:ee:01')
    expect(reasonChip.textContent).toMatch(/blocked:\s*paused/)
  })

  it('stays hidden when there are no MAC drops', async () => {
    (api.logs.macDrops as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ rows: [] })
    renderAt()
    // Wait for the page to settle, then assert the panel isn't rendered.
    await screen.findByText('example.com')
    expect(screen.queryByTestId('mac-drops-panel')).not.toBeInTheDocument()
  })
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
