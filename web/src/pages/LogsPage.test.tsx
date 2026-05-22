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

const devices: Device[] = [
  { id: 10, mac: 'aa:bb:cc:dd:ee:01', name: "Kid's iPad", profileId: 1, profileName: 'Kids', lastSeenIp: null, lastSeenAt: null },
  { id: 11, mac: 'aa:bb:cc:dd:ee:02', name: 'Phone',      profileId: 2, profileName: 'Adults', lastSeenIp: null, lastSeenAt: null },
]
const profileDetails: ProfileDetail[] = [
  { profile: { id: 1, name: 'Kids',   blockedCategories: [], extraBlocked: [], extraAllowed: [], paused: false, failureMode: 'block-all' }, schedules: [], timeLimit: null, siteTimeLimits: [] },
  { profile: { id: 2, name: 'Adults', blockedCategories: [], extraBlocked: [], extraAllowed: [], paused: false, failureMode: 'allow-all' }, schedules: [], timeLimit: null, siteTimeLimits: [] },
]

function renderAt(path = '/logs') {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <LogsPage />
    </MemoryRouter>,
  )
}

beforeEach(() => {
  vi.resetAllMocks()
  ;(api.logs.query    as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([log1])
  ;(api.logs.series   as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
  ;(api.devices.list  as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(devices)
  ;(api.profiles.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(profileDetails)
})

describe('LogsPage — Connection events (raw)', () => {
  it('calls api.logs.query and renders connection-event rows', async () => {
    renderAt()
    expect(await screen.findByText('example.com')).toBeInTheDocument()
    expect(api.logs.query).toHaveBeenCalled()
  })

  it('shows empty state when no events', async () => {
    (api.logs.query as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
    renderAt()
    expect(await screen.findByText('No events found.')).toBeInTheDocument()
  })

  it('Time column renders in viewer local time (not UTC slice)', async () => {
    renderAt()
    await screen.findByText('example.com')
    const expected = new Date(log1.ts).toLocaleTimeString()
    expect(screen.getByText(expected)).toBeInTheDocument()
  })
})

describe('LogsPage — Connection events aggregation (#847)', () => {
  const aggRow: ConnectionEventAggRow = {
    group: 'youtube.com',
    windowStart: '2026-05-22 14:00:00',
    countSucceeded: 12,
    countBlocked: 3,
    lastSeen: '2026-05-22T14:30:00Z',
    topDevice: "Kid's iPad",
  }

  it('Raw is the default; clicking a bucket switches to /series with same filters', async () => {
    (api.logs.series as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([aggRow])
    const user = userEvent.setup()
    renderAt('/logs?profileId=1')
    await screen.findByText('example.com')
    // No series call while bucket=off
    expect(api.logs.series).not.toHaveBeenCalled()

    await user.click(screen.getByTestId('ce-bucket-1h'))
    await waitFor(() => {
      expect(api.logs.series).toHaveBeenLastCalledWith(expect.objectContaining({
        bucket: '1h',
        groupBy: 'domain',
        profileId: 1,
      }))
    })
    expect(await screen.findByTestId('ce-agg-table')).toBeInTheDocument()
    expect(screen.getByText('12')).toBeInTheDocument()
    expect(screen.getByText('3')).toBeInTheDocument()
  })

  it('switching back to Raw reverts to /api/logs and stops calling /series', async () => {
    const user = userEvent.setup()
    renderAt()
    await screen.findByText('example.com')
    await user.click(screen.getByTestId('ce-bucket-10m'))
    await waitFor(() => expect(api.logs.series).toHaveBeenCalled())

    const seriesCallsBefore = (api.logs.series as ReturnType<typeof vi.fn>).mock.calls.length
    await user.click(screen.getByTestId('ce-bucket-off'))
    await screen.findByTestId('ce-raw-table')
    expect((api.logs.series as ReturnType<typeof vi.fn>).mock.calls.length).toBe(seriesCallsBefore)
  })

  it('Group-by selector is hidden in Raw mode and visible when bucketed', async () => {
    const user = userEvent.setup()
    renderAt()
    await screen.findByText('example.com')
    expect(screen.queryByTestId('ce-groupby')).not.toBeInTheDocument()
    await user.click(screen.getByTestId('ce-bucket-1d'))
    expect(await screen.findByTestId('ce-groupby')).toBeInTheDocument()
  })

  it('apex and app group-by options are disabled (gated)', async () => {
    const user = userEvent.setup()
    renderAt()
    await screen.findByText('example.com')
    await user.click(screen.getByTestId('ce-bucket-1h'))
    const sel = await screen.findByTestId('ce-groupby') as HTMLSelectElement
    const apex = sel.querySelector('option[value="apex"]') as HTMLOptionElement
    const app  = sel.querySelector('option[value="app"]')  as HTMLOptionElement
    expect(apex.disabled).toBe(true)
    expect(app.disabled).toBe(true)
  })
})

describe('LogsPage — click-through to device (#298)', () => {
  it('device cell links to /devices?mac=...', async () => {
    renderAt()
    const link = await screen.findByTestId('logs-device-link-aa:bb:cc:dd:ee:01')
    expect(link).toHaveAttribute('href', '/devices?mac=aa%3Abb%3Acc%3Add%3Aee%3A01')
    expect(link).toHaveTextContent("Kid's iPad")
  })
})

describe('LogsPage — device/profile filters (#342)', () => {
  it('selecting a device refetches with that deviceId', async () => {
    const user = userEvent.setup()
    renderAt()
    await screen.findByText('example.com')
    await waitFor(() => expect((api.devices.list as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(0))
    await user.selectOptions(screen.getByTestId('logs-filter-device'), '10')
    await waitFor(() => {
      expect(api.logs.query).toHaveBeenLastCalledWith(expect.objectContaining({ deviceId: 10 }))
    })
  })

  it('selecting a profile refetches with that profileId', async () => {
    const user = userEvent.setup()
    renderAt()
    await screen.findByText('example.com')
    await waitFor(() => expect((api.profiles.list as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(0))
    await user.selectOptions(screen.getByTestId('logs-filter-profile'), '1')
    await waitFor(() => {
      expect(api.logs.query).toHaveBeenLastCalledWith(expect.objectContaining({ profileId: 1 }))
    })
  })

  it('loading a URL with ?deviceId=10&profileId=1 starts in the filtered state', async () => {
    renderAt('/logs?deviceId=10&profileId=1')
    await screen.findByText('example.com')
    await waitFor(() => {
      expect(api.logs.query).toHaveBeenLastCalledWith(expect.objectContaining({
        deviceId: 10,
        profileId: 1,
      }))
    })
    const deviceSelect = screen.getByTestId('logs-filter-device') as HTMLSelectElement
    const profileSelect = screen.getByTestId('logs-filter-profile') as HTMLSelectElement
    await waitFor(() => expect(deviceSelect.value).toBe('10'))
    expect(profileSelect.value).toBe('1')
  })

  it('clear button resets filters and removes them from the URL', async () => {
    const user = userEvent.setup()
    renderAt('/logs?deviceId=10&profileId=1')
    await screen.findByText('example.com')
    const clear = await screen.findByTestId('logs-filter-clear')
    await user.click(clear)
    await waitFor(() => {
      expect(api.logs.query).toHaveBeenLastCalledWith(expect.objectContaining({
        deviceId: undefined,
        profileId: undefined,
      }))
    })
  })

  it('filtered empty state shows a "Clear filters" link that resets', async () => {
    (api.logs.query as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
    const user = userEvent.setup()
    renderAt('/logs?deviceId=10')
    expect(await screen.findByText(/no matching events/i)).toBeInTheDocument()
    const link = screen.getByTestId('logs-empty-clear')
    await user.click(link)
    await waitFor(() => {
      expect(api.logs.query).toHaveBeenLastCalledWith(expect.objectContaining({ deviceId: undefined }))
    })
  })
})
