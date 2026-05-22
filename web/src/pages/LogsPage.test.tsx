import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import type { ConnectionEventAggRow, Device, ProfileDetail, QueryLog, Session, SessionPage } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    sessions: { list:  vi.fn() },
    logs:     { query: vi.fn(), series: vi.fn() },
    devices:  { list:  vi.fn() },
    profiles: { list:  vi.fn() },
  },
}))

import { api } from '@/api/client'
import { LogsPage } from './LogsPage'

const session1: Session = {
  mac: 'aa:bb:cc:dd:ee:01',
  deviceName: "Kid's iPad",
  profileId: 1,
  profileName: 'Kids',
  host: { type: 'fqdn', value: 'youtube.com' },
  routerId: 'r-1',
  date: '2026-05-12',
  startedAt: '2026-05-12T14:30:00Z',
  endedAt:   '2026-05-12T14:40:00Z',
  durationSeconds: 600,
  bytesIn: 12_000_000,
  bytesOut: 500_000,
  periodCount: 2,
}
const session2: Session = {
  mac: 'aa:bb:cc:dd:ee:02',
  deviceName: 'Phone',
  profileId: 1,
  profileName: 'Kids',
  host: { type: 'fqdn', value: 'tiktok.com' },
  routerId: 'r-1',
  date: '2026-05-12',
  startedAt: '2026-05-12T13:00:00Z',
  endedAt:   '2026-05-12T13:05:00Z',
  durationSeconds: 180,
  bytesIn: 1_500_000,
  bytesOut: 90_000,
  periodCount: 1,
}
const page: SessionPage = { sessions: [session1, session2], nextCursor: null }

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
  ;(api.sessions.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(page)
  ;(api.logs.query    as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([log1])
  ;(api.logs.series   as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
  ;(api.devices.list  as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(devices)
  ;(api.profiles.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(profileDetails)
})

describe('LogsPage — Sessions tab (default)', () => {
  it('renders Sessions tab by default and calls api.sessions.list', async () => {
    renderAt()
    expect(await screen.findByText('youtube.com')).toBeInTheDocument()
    expect(screen.getByText('tiktok.com')).toBeInTheDocument()
    expect(api.sessions.list).toHaveBeenCalledWith({
      host: undefined,
      deviceId: undefined,
      profileId: undefined,
      hours: 24,
      limit: 100,
    })
    expect(api.logs.query).not.toHaveBeenCalled()
  })

  it('shows device name, profile, duration, host for each session', async () => {
    renderAt()
    await screen.findByText('youtube.com')
    // "Kid's iPad" and "Phone" appear in both the session table and the device-filter
    // dropdown, so use findAllByText.
    expect((await screen.findAllByText("Kid's iPad")).length).toBeGreaterThan(0)
    expect(screen.getAllByText('Phone').length).toBeGreaterThan(0)
    expect(screen.getByText('10m')).toBeInTheDocument()
    expect(screen.getByText('3m')).toBeInTheDocument()
  })

  it('typing into the host input refetches with the host filter', async () => {
    const user = userEvent.setup()
    renderAt()
    await screen.findByText('youtube.com')
    await user.type(screen.getByTestId('sessions-filter-host'), 'youtube')
    await waitFor(() => {
      expect(api.sessions.list).toHaveBeenLastCalledWith({
        host: 'youtube',
        deviceId: undefined,
        profileId: undefined,
        hours: 24,
        limit: 100,
      })
    })
  })

  it('shows empty state when no sessions', async () => {
    (api.sessions.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      sessions: [], nextCursor: null,
    } satisfies SessionPage)
    renderAt()
    expect(await screen.findByText('No sessions found.')).toBeInTheDocument()
  })
})

describe('LogsPage — Connection events tab', () => {
  it('clicking Connection events tab calls api.logs.query and renders connection-event rows', async () => {
    const user = userEvent.setup()
    renderAt()
    await screen.findByText('youtube.com')
    await user.click(screen.getByTestId('logs-tab-raw'))
    expect(await screen.findByText('example.com')).toBeInTheDocument()
    expect(api.logs.query).toHaveBeenCalled()
  })

  it('renders the "Connection events" tab label', async () => {
    renderAt()
    expect(await screen.findByRole('tab', { name: 'Connection events' })).toBeInTheDocument()
  })

  it('Connection events shows empty state with its own copy', async () => {
    (api.logs.query as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
    const user = userEvent.setup()
    renderAt()
    await screen.findByText('youtube.com')
    await user.click(screen.getByTestId('logs-tab-raw'))
    expect(await screen.findByText('No events found.')).toBeInTheDocument()
  })

  it('Raw events Time column renders in viewer local time (not UTC slice)', async () => {
    const user = userEvent.setup()
    renderAt()
    await screen.findByText('youtube.com')
    await user.click(screen.getByTestId('logs-tab-raw'))
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
    await screen.findByText('youtube.com')
    await user.click(screen.getByTestId('logs-tab-raw'))
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
    // The aggregated row renders
    expect(await screen.findByTestId('ce-agg-table')).toBeInTheDocument()
    expect(screen.getByText('12')).toBeInTheDocument()
    expect(screen.getByText('3')).toBeInTheDocument()
  })

  it('switching back to Raw reverts to /api/logs and stops calling /series', async () => {
    const user = userEvent.setup()
    renderAt()
    await screen.findByText('youtube.com')
    await user.click(screen.getByTestId('logs-tab-raw'))
    await user.click(screen.getByTestId('ce-bucket-10m'))
    await waitFor(() => expect(api.logs.series).toHaveBeenCalled())

    const seriesCallsBefore = (api.logs.series as ReturnType<typeof vi.fn>).mock.calls.length
    await user.click(screen.getByTestId('ce-bucket-off'))
    await screen.findByTestId('ce-raw-table')
    // No new /series calls after switching back
    expect((api.logs.series as ReturnType<typeof vi.fn>).mock.calls.length).toBe(seriesCallsBefore)
  })

  it('Group-by selector is hidden in Raw mode and visible when bucketed', async () => {
    const user = userEvent.setup()
    renderAt()
    await screen.findByText('youtube.com')
    await user.click(screen.getByTestId('logs-tab-raw'))
    expect(screen.queryByTestId('ce-groupby')).not.toBeInTheDocument()
    await user.click(screen.getByTestId('ce-bucket-1d'))
    expect(await screen.findByTestId('ce-groupby')).toBeInTheDocument()
  })

  it('apex and app group-by options are disabled (gated)', async () => {
    const user = userEvent.setup()
    renderAt()
    await screen.findByText('youtube.com')
    await user.click(screen.getByTestId('logs-tab-raw'))
    await user.click(screen.getByTestId('ce-bucket-1h'))
    const sel = await screen.findByTestId('ce-groupby') as HTMLSelectElement
    const apex = sel.querySelector('option[value="apex"]') as HTMLOptionElement
    const app  = sel.querySelector('option[value="app"]')  as HTMLOptionElement
    expect(apex.disabled).toBe(true)
    expect(app.disabled).toBe(true)
  })
})

describe('LogsPage — click-through to device / profile (#298)', () => {
  it('Sessions tab: device cell links to /devices?mac=...', async () => {
    renderAt()
    const link = await screen.findByTestId('logs-device-link-aa:bb:cc:dd:ee:01')
    expect(link).toHaveAttribute('href', '/devices?mac=aa%3Abb%3Acc%3Add%3Aee%3A01')
    expect(link).toHaveTextContent("Kid's iPad")
  })

  it('Sessions tab: profile cell links to /profiles?id=...', async () => {
    renderAt()
    // Both fixture sessions share profile 1, so two links carry this testid.
    const links = await screen.findAllByTestId('logs-profile-link-1')
    expect(links[0]).toHaveAttribute('href', '/profiles?id=1')
    expect(links[0]).toHaveTextContent('Kids')
  })

  it('Connection events tab: device cell links to /devices?mac=...', async () => {
    const user = userEvent.setup()
    renderAt()
    await screen.findByText('youtube.com')
    await user.click(screen.getByTestId('logs-tab-raw'))
    const link = await screen.findByTestId('logs-device-link-aa:bb:cc:dd:ee:01')
    expect(link).toHaveAttribute('href', '/devices?mac=aa%3Abb%3Acc%3Add%3Aee%3A01')
  })

  it('Sessions tab: unrecognized MAC (no deviceName) renders MAC as plain text — no link', async () => {
    const unknownSession: Session = {
      ...session1,
      mac: 'fa:fa:fa:fa:fa:fa',
      deviceName: null,
      profileId: null,
      profileName: null,
      host: { type: 'fqdn', value: 'unknown.example' },
    }
    ;(api.sessions.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      sessions: [unknownSession], nextCursor: null,
    } satisfies SessionPage)
    renderAt()
    await screen.findByText('unknown.example')
    expect(screen.queryByTestId('logs-device-link-fa:fa:fa:fa:fa:fa')).not.toBeInTheDocument()
    expect(screen.getByText('fa:fa:fa:fa:fa:fa')).toBeInTheDocument()
  })
})

describe('LogsPage — Sessions tab timestamps', () => {
  it('Started column renders session start in viewer local time', async () => {
    renderAt()
    await screen.findByText('youtube.com')
    const expected = new Date(session1.startedAt).toLocaleTimeString([], {
      hour: '2-digit', minute: '2-digit',
    })
    expect(screen.getAllByText(expected).length).toBeGreaterThan(0)
  })
})

describe('LogsPage — device/profile filters (#342)', () => {
  it('selecting a device refetches sessions with that deviceId', async () => {
    const user = userEvent.setup()
    renderAt()
    await screen.findByText('youtube.com')
    await waitFor(() => expect((api.devices.list as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(0))
    await user.selectOptions(screen.getByTestId('logs-filter-device'), '10')
    await waitFor(() => {
      expect(api.sessions.list).toHaveBeenLastCalledWith(expect.objectContaining({
        deviceId: 10,
      }))
    })
  })

  it('selecting a profile applies to both Sessions and Connection events tabs', async () => {
    const user = userEvent.setup()
    renderAt()
    await screen.findByText('youtube.com')
    await waitFor(() => expect((api.profiles.list as ReturnType<typeof vi.fn>).mock.calls.length).toBeGreaterThan(0))
    await user.selectOptions(screen.getByTestId('logs-filter-profile'), '1')
    await waitFor(() => {
      expect(api.sessions.list).toHaveBeenLastCalledWith(expect.objectContaining({ profileId: 1 }))
    })
    await user.click(screen.getByTestId('logs-tab-raw'))
    await waitFor(() => {
      expect(api.logs.query).toHaveBeenLastCalledWith(expect.objectContaining({ profileId: 1 }))
    })
  })

  it('loading a URL with ?deviceId=10&profileId=1 starts in the filtered state', async () => {
    renderAt('/logs?deviceId=10&profileId=1')
    await screen.findByText('youtube.com')
    await waitFor(() => {
      expect(api.sessions.list).toHaveBeenLastCalledWith(expect.objectContaining({
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
    await screen.findByText('youtube.com')
    const clear = await screen.findByTestId('logs-filter-clear')
    await user.click(clear)
    await waitFor(() => {
      expect(api.sessions.list).toHaveBeenLastCalledWith(expect.objectContaining({
        deviceId: undefined,
        profileId: undefined,
      }))
    })
  })

  it('filtered empty state shows a "Clear filters" link that resets', async () => {
    (api.sessions.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      sessions: [], nextCursor: null,
    } satisfies SessionPage)
    const user = userEvent.setup()
    renderAt('/logs?deviceId=10')
    expect(await screen.findByText(/no matching sessions/i)).toBeInTheDocument()
    const link = screen.getByTestId('logs-empty-clear')
    await user.click(link)
    await waitFor(() => {
      expect(api.sessions.list).toHaveBeenLastCalledWith(expect.objectContaining({ deviceId: undefined }))
    })
  })
})
