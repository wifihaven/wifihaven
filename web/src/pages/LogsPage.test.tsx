import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import type { ConnectionEventAggRow, Device, ProfileDetail, QueryLog } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    logs:     { query: vi.fn(), series: vi.fn() },
    devices:  { list:  vi.fn() },
    profiles: { list:  vi.fn() },
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
  { profile: { id: 1, name: 'Kids', blockedCategories: [], extraBlocked: [], extraAllowed: [], paused: false, failureMode: 'block-all', crossDeviceOverlapMode: 'sum' }, schedules: [], timeLimit: null, siteTimeLimits: [] },
]

function renderAt(path = '/usage/events') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <LogsPage />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.resetAllMocks()
  ;(api.logs.query    as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([log1])
  ;(api.logs.series   as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([aggRow])
  ;(api.devices.list  as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(devices)
  ;(api.profiles.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(profileDetails)
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
    (api.logs.query as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
    renderAt()
    expect(await screen.findByText('No events in window.')).toBeInTheDocument()
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
})
