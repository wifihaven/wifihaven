import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import type { TrafficUsageResponse } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    usage:    { traffic: vi.fn() },
    devices:  { list:    vi.fn() },
    profiles: { list:    vi.fn() },
  },
}))

import { api } from '@/api/client'
import { TrafficUsagePage } from './TrafficUsagePage'

const rawResp: TrafficUsageResponse = {
  bucket: 'raw',
  from: '2026-05-21T00:00:00Z',
  to: '2026-05-22T00:00:00Z',
  tz: 'UTC',
  rawRowLimit: 100,
  rawRowsTruncated: false,
  rawRows: [
    {
      mac: 'aa:bb:cc:dd:ee:01',
      deviceName: "Kid's iPad",
      profileId: 1,
      profileName: 'Kids',
      host: { type: 'fqdn', value: 'youtube.com' },
      bytesIn: 12345,
      bytesOut: 678,
      activeSeconds: 300,
      periodStart: '2026-05-21T14:00:00Z',
      periodEnd: '2026-05-21T14:05:00Z',
    },
  ],
  aggregateRows: [],
}

const aggResp: TrafficUsageResponse = {
  bucket: '1h',
  groupBy: ['domain'],
  from: '2026-05-21T00:00:00Z',
  to: '2026-05-22T00:00:00Z',
  tz: 'UTC',
  rawRows: [],
  aggregateRows: [
    {
      groups: { domain: 'youtube.com' },
      windowStart: '2026-05-21T14:00:00Z',
      windowEnd: '2026-05-21T15:00:00Z',
      totalBytesIn: 99000,
      totalBytesOut: 1200,
      totalSeconds: 600,
      distinctDevices: 2,
      distinctProfiles: 1,
      distinctDomains: 1,
    },
  ],
}

function renderPage() {
  return render(
    <MemoryRouter>
      <TrafficUsagePage />
    </MemoryRouter>,
  )
}

describe('TrafficUsagePage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    ;(api.devices.list as ReturnType<typeof vi.fn>).mockResolvedValue([])
    ;(api.profiles.list as ReturnType<typeof vi.fn>).mockResolvedValue([])
    ;(api.usage.traffic as ReturnType<typeof vi.fn>).mockResolvedValue(rawResp)
  })

  it('loads raw view by default and renders rows', async () => {
    renderPage()
    await waitFor(() => expect(api.usage.traffic).toHaveBeenCalled())
    expect(screen.getByTestId('raw-table')).toBeInTheDocument()
    expect(screen.getByText("Kid's iPad")).toBeInTheDocument()
    expect(screen.getByText('youtube.com')).toBeInTheDocument()
  })

  it('disabled 1m bucket button does not dispatch a request', async () => {
    renderPage()
    await waitFor(() => expect(api.usage.traffic).toHaveBeenCalledTimes(1))
    const btn = screen.getByTestId('bucket-1m')
    expect(btn).toBeDisabled()
    await userEvent.click(btn, { pointerEventsCheck: 0 })
    expect(api.usage.traffic).toHaveBeenCalledTimes(1)
  })

  // #917: default groupBy is [] — no implicit dimension.
  it('switching bucket to 1h preserves filters and shows aggregate table with default groupBy=[]', async () => {
    const trafficMock = api.usage.traffic as ReturnType<typeof vi.fn>
    const aggEmptyResp: TrafficUsageResponse = {
      ...aggResp,
      groupBy: [],
      aggregateRows: [{ ...aggResp.aggregateRows[0], groups: {} }],
    }
    trafficMock.mockResolvedValueOnce(rawResp).mockResolvedValueOnce(aggEmptyResp)
    renderPage()
    await waitFor(() => expect(api.usage.traffic).toHaveBeenCalledTimes(1))
    const firstCall = (api.usage.traffic as ReturnType<typeof vi.fn>).mock.calls[0][0]

    await userEvent.click(screen.getByTestId('bucket-1h'))
    await waitFor(() => expect(api.usage.traffic).toHaveBeenCalledTimes(2))
    const secondCall = (api.usage.traffic as ReturnType<typeof vi.fn>).mock.calls[1][0]

    expect(secondCall.macs).toEqual(firstCall.macs)
    expect(secondCall.profileIds).toEqual(firstCall.profileIds)
    // `from`/`to` are computed at fetch-time from new Date() so they differ
    // between calls; the bucket switch is what matters here.
    expect(secondCall.bucket).toBe('1h')
    expect(secondCall.groupBy).toEqual([])

    await waitFor(() => expect(screen.getByTestId('aggregate-table')).toBeInTheDocument())
  })

  // #917: toggling a column on adds rows; toggling off removes them. Toggling
  // *all* off lands back in the strictly-aggregate default.
  it('clicking a column header toggles it into the groupBy set, additively', async () => {
    const trafficMock = api.usage.traffic as ReturnType<typeof vi.fn>
    const aggEmptyResp: TrafficUsageResponse = {
      ...aggResp,
      groupBy: [],
      aggregateRows: [{ ...aggResp.aggregateRows[0], groups: {} }],
    }
    trafficMock
      .mockResolvedValueOnce(rawResp)        // initial raw
      .mockResolvedValueOnce(aggEmptyResp)   // bucket=1h, groupBy=[]
      .mockResolvedValueOnce(aggResp)        // + domain toggle
      .mockResolvedValueOnce({ ...aggResp, groupBy: ['domain', 'device'] }) // + device toggle
      .mockResolvedValueOnce(aggEmptyResp)   // - domain - device (back to empty)
    renderPage()
    await waitFor(() => expect(api.usage.traffic).toHaveBeenCalledTimes(1))
    await userEvent.click(screen.getByTestId('bucket-1h'))
    await waitFor(() => expect(api.usage.traffic).toHaveBeenCalledTimes(2))

    // Adds the first dimension.
    await userEvent.click(screen.getByTestId('traffic-group-domain'))
    await waitFor(() => expect(api.usage.traffic).toHaveBeenCalledTimes(3))
    expect((api.usage.traffic as ReturnType<typeof vi.fn>).mock.calls[2][0].groupBy).toEqual(['domain'])

    // Adds the second dimension.
    await userEvent.click(screen.getByTestId('traffic-group-device'))
    await waitFor(() => expect(api.usage.traffic).toHaveBeenCalledTimes(4))
    expect((api.usage.traffic as ReturnType<typeof vi.fn>).mock.calls[3][0].groupBy?.sort())
      .toEqual(['device', 'domain'])

    // Toggling them all back off returns to the empty default — the prior
    // implementation kept at least one on; #917 makes "no toggles" valid.
    await userEvent.click(screen.getByTestId('traffic-group-domain'))
    await waitFor(() => expect(api.usage.traffic).toHaveBeenCalledTimes(5))
    await userEvent.click(screen.getByTestId('traffic-group-device'))
    await waitFor(() => expect(api.usage.traffic).toHaveBeenCalledTimes(6))
    expect((api.usage.traffic as ReturnType<typeof vi.fn>).mock.calls[5][0].groupBy).toEqual([])
  })

  // #917: URL state. groupBy round-trips as repeated ?groupBy= params so
  // links restore the operator's drill-in.
  it('initializes groupBy from URL query params (?groupBy=device&groupBy=domain)', async () => {
    const trafficMock = api.usage.traffic as ReturnType<typeof vi.fn>
    trafficMock.mockResolvedValue(aggResp)
    render(
      <MemoryRouter initialEntries={['/?groupBy=device&groupBy=domain']}>
        <TrafficUsagePage />
      </MemoryRouter>,
    )
    await waitFor(() => expect(api.usage.traffic).toHaveBeenCalledTimes(1))
    // Default bucket is raw — switch to 1h to surface the groupBy in the call.
    await userEvent.click(screen.getByTestId('bucket-1h'))
    await waitFor(() => expect(api.usage.traffic).toHaveBeenCalledTimes(2))
    const aggCall = (api.usage.traffic as ReturnType<typeof vi.fn>).mock.calls[1][0]
    expect(aggCall.groupBy?.sort()).toEqual(['device', 'domain'])
  })

  it('#865 selecting two devices in the header filter posts macs as an array and shows chips', async () => {
    const devices = [
      { id: 1, mac: 'aa:bb:cc:dd:ee:01', name: "Kid's iPad", profileId: 1, profileName: 'Kids', lastSeenIp: null, lastSeenAt: null },
      { id: 2, mac: 'aa:bb:cc:dd:ee:02', name: 'Adult Laptop', profileId: 2, profileName: 'Adults', lastSeenIp: null, lastSeenAt: null },
    ]
    ;(api.devices.list as ReturnType<typeof vi.fn>).mockResolvedValue(devices)
    const trafficMock = api.usage.traffic as ReturnType<typeof vi.fn>
    trafficMock.mockResolvedValue(rawResp)
    renderPage()
    await waitFor(() => expect(api.usage.traffic).toHaveBeenCalledTimes(1))

    // Open the device-header filter popover and select both devices via the
    // "all" affordance — exercises the multi-value wire shape in one click
    // without depending on popover lifecycle between separate checkbox clicks.
    await userEvent.click(screen.getByTestId('traffic-filter-device'))
    await userEvent.click(screen.getByTestId('traffic-filter-device-all'))

    await waitFor(() => {
      const calls = trafficMock.mock.calls
      const last = calls[calls.length - 1][0]
      expect(last.macs?.slice().sort()).toEqual(['aa:bb:cc:dd:ee:01', 'aa:bb:cc:dd:ee:02'])
    })
    // Chip summary in the shelf renders the active filters.
    expect(screen.getByTestId('chip-mac-aa:bb:cc:dd:ee:01')).toBeInTheDocument()
    expect(screen.getByTestId('chip-mac-aa:bb:cc:dd:ee:02')).toBeInTheDocument()
  })

  // #861 — verify low-priority columns carry responsive `hidden` classes so
  // the table fits at phone (~375px) and tablet (~768px) widths. jsdom doesn't
  // do real layout, so we assert on classnames rather than measuring overflow.
  // #865 widened the column headers with funnel popover buttons, so the
  // accessible name picks up the funnel glyph — match by regex.
  it('hides low-priority raw-table columns on narrow viewports', async () => {
    renderPage()
    await waitFor(() => expect(screen.getByTestId('raw-table')).toBeInTheDocument())
    const profile = screen.getByRole('columnheader', { name: /^Profile/ })
    expect(profile.className).toMatch(/hidden md:table-cell/)
    const outbound = screen.getByRole('columnheader', { name: 'Outbound' })
    expect(outbound.className).toMatch(/hidden sm:table-cell/)
  })

  it('surfaces server error', async () => {
    const trafficMock = api.usage.traffic as ReturnType<typeof vi.fn>
    trafficMock.mockRejectedValueOnce(new Error('window_too_large'))
    renderPage()
    await waitFor(() => expect(screen.getByTestId('error')).toHaveTextContent('window_too_large'))
  })
})
