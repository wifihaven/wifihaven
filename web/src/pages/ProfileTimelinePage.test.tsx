import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { UsageSeriesResponse } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    usage: {
      series: vi.fn(),
    },
  },
}))

vi.mock('recharts', () => {
  const Pass = ({ children }: { children?: React.ReactNode }) => <div>{children}</div>
  return {
    Bar: () => null,
    BarChart: Pass,
    CartesianGrid: () => null,
    Legend: () => null,
    ResponsiveContainer: Pass,
    Tooltip: () => null,
    XAxis: () => null,
    YAxis: () => null,
  }
})

import { api } from '@/api/client'
import { ProfileTimelinePage } from './ProfileTimelinePage'

const PID = 7

function renderPage(initialEntries: string[] = [`/profiles/${PID}/timeline`]) {
  return render(
    <MemoryRouter initialEntries={initialEntries}>
      <Routes>
        <Route path="/profiles/:profileId/timeline" element={<ProfileTimelinePage />} />
      </Routes>
    </MemoryRouter>,
  )
}

function emptyResponse(date: string): UsageSeriesResponse {
  return {
    profileId: PID,
    profileName: 'Kids',
    date,
    tz: 'UTC',
    topHosts: [],
    buckets: Array.from({ length: 24 }, (_, h) => ({
      hour: h, totalMins: 0, perHost: [], otherMins: 0,
    })),
    topDevices: [],
    bucketsByDevice: Array.from({ length: 24 }, (_, h) => ({
      hour: h, totalMins: 0, perDevice: [], otherMins: 0,
    })),
  }
}

function richResponse(date: string): UsageSeriesResponse {
  return {
    profileId: PID,
    profileName: 'Kids',
    date,
    tz: 'UTC',
    topHosts: [
      { host: { type: 'fqdn', value: 'youtube.com' }, dayMins: 30 },
      { host: { type: 'fqdn', value: 'google.com' }, dayMins: 15 },
    ],
    buckets: Array.from({ length: 24 }, (_, h) => ({
      hour: h,
      totalMins: h === 14 ? 45 : 0,
      perHost: h === 14 ? [
        { host: { type: 'fqdn', value: 'youtube.com' }, mins: 30 },
        { host: { type: 'fqdn', value: 'google.com' }, mins: 15 },
      ] : [],
      otherMins: 0,
    })),
    topDevices: [
      { deviceMac: 'aa:bb:cc:dd:ee:01', deviceName: "Kid's iPad", dayMins: 30 },
      { deviceMac: 'aa:bb:cc:dd:ee:02', deviceName: "Kid's iPhone", dayMins: 15 },
    ],
    bucketsByDevice: Array.from({ length: 24 }, (_, h) => ({
      hour: h,
      totalMins: h === 14 ? 45 : 0,
      perDevice: h === 14 ? [
        { deviceMac: 'aa:bb:cc:dd:ee:01', deviceName: "Kid's iPad", mins: 30 },
        { deviceMac: 'aa:bb:cc:dd:ee:02', deviceName: "Kid's iPhone", mins: 15 },
      ] : [],
      otherMins: 0,
    })),
  }
}

describe('ProfileTimelinePage', () => {
  beforeEach(() => {
    localStorage.setItem('token', 'fake')
    vi.clearAllMocks()
  })

  it('passes profileId (not mac) to the usage API', async () => {
    const mock = api.usage.series as unknown as ReturnType<typeof vi.fn>
    mock.mockResolvedValue(emptyResponse('2026-05-20'))
    renderPage([`/profiles/${PID}/timeline?date=2026-05-20`])
    await waitFor(() => expect(mock).toHaveBeenCalled())
    expect(mock.mock.calls[0][0]).toMatchObject({ profileId: PID, date: '2026-05-20' })
  })

  it('renders profile name and empty state', async () => {
    (api.usage.series as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(
      emptyResponse('2026-05-20'),
    )
    renderPage([`/profiles/${PID}/timeline?date=2026-05-20`])
    await waitFor(() =>
      expect(screen.getByTestId('profile-timeline-empty')).toBeInTheDocument(),
    )
    expect(screen.getByTestId('profile-timeline-name')).toHaveTextContent('Kids')
  })

  it('defaults to Total (no drill-down): chart visible, devices listed, no hosts panel', async () => {
    (api.usage.series as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(
      richResponse('2026-05-20'),
    )
    renderPage([`/profiles/${PID}/timeline?date=2026-05-20`])
    await waitFor(() =>
      expect(screen.getByTestId('profile-timeline-chart')).toBeInTheDocument(),
    )
    // Total default: device list still visible (useful for drill-in), but the
    // host breakdown panel is hidden because the chart is not stacked by host.
    expect(screen.getByTestId('profile-timeline-stack-total')).toHaveAttribute('aria-selected', 'true')
    expect(screen.getByTestId('profile-timeline-device-aa:bb:cc:dd:ee:01')).toBeInTheDocument()
    expect(screen.queryByTestId('profile-timeline-host-youtube.com')).toBeNull()
  })

  it('toggles between Total and Device; updates the visible breakdown', async () => {
    (api.usage.series as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(
      richResponse('2026-05-20'),
    )
    renderPage([`/profiles/${PID}/timeline?date=2026-05-20`])
    await waitFor(() =>
      expect(screen.getByTestId('profile-timeline-chart')).toBeInTheDocument(),
    )
    expect(screen.getByTestId('profile-timeline-device-aa:bb:cc:dd:ee:01')).toBeInTheDocument()

    fireEvent.click(screen.getByTestId('profile-timeline-stack-device'))
    await waitFor(() =>
      expect(screen.getByTestId('profile-timeline-device-aa:bb:cc:dd:ee:01')).toBeInTheDocument(),
    )
  })

  // #1079 — unified by-app axis. Host toggle gone; App renders the mixed
  // app + non-app-host series; 'Other' only when long tail exceeds top-N.
  it('#1079: stack-by toggle is Total/Device/App; Host is removed', async () => {
    (api.usage.series as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(
      richResponse('2026-05-20'),
    )
    renderPage([`/profiles/${PID}/timeline?date=2026-05-20`])
    await waitFor(() =>
      expect(screen.getByTestId('profile-timeline-stack-total')).toBeInTheDocument(),
    )
    expect(screen.queryByTestId('profile-timeline-stack-host')).toBeNull()
    expect(screen.getByTestId('profile-timeline-stack-device')).toBeInTheDocument()
    expect(screen.getByTestId('profile-timeline-stack-app')).toBeInTheDocument()
  })

  it('#1079: App view renders app + non-app-host entries from topEntries', async () => {
    const resp: UsageSeriesResponse = {
      ...richResponse('2026-05-20'),
      topEntries: [
        { entity: { kind: 'app',  id: 'youtube',  name: 'YouTube', appId: 1 }, dayMins: 25 },
        { entity: { kind: 'host', id: 'drop.com', name: 'drop.com', host: { type: 'fqdn', value: 'drop.com' } }, dayMins: 10 },
      ],
      bucketsByEntry: Array.from({ length: 24 }, (_, h) => ({
        hour: h,
        totalMins: h === 14 ? 35 : 0,
        perEntity: h === 14 ? [
          { entity: { kind: 'app',  id: 'youtube',  name: 'YouTube', appId: 1 }, mins: 25 },
          { entity: { kind: 'host', id: 'drop.com', name: 'drop.com', host: { type: 'fqdn', value: 'drop.com' } }, mins: 10 },
        ] : [],
        otherMins: 0,
      })),
    }
    ;(api.usage.series as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(resp)
    renderPage([`/profiles/${PID}/timeline?date=2026-05-20`])
    await waitFor(() =>
      expect(screen.getByTestId('profile-timeline-stack-app')).toBeInTheDocument(),
    )
    fireEvent.click(screen.getByTestId('profile-timeline-stack-app'))
    await waitFor(() =>
      expect(screen.getByTestId('profile-timeline-entry-app-youtube')).toBeInTheDocument(),
    )
    expect(screen.getByTestId('profile-timeline-entry-host-drop.com')).toBeInTheDocument()
  })

  it('#1079: App view shows Other only when long tail exceeds top-N', async () => {
    const resp: UsageSeriesResponse = {
      ...richResponse('2026-05-20'),
      topEntries: [
        { entity: { kind: 'host', id: 'drop.com', name: 'drop.com', host: { type: 'fqdn', value: 'drop.com' } }, dayMins: 5 },
      ],
      bucketsByEntry: Array.from({ length: 24 }, (_, h) => ({
        hour: h,
        totalMins: h === 14 ? 5 : 0,
        perEntity: h === 14
          ? [{ entity: { kind: 'host', id: 'drop.com', name: 'drop.com', host: { type: 'fqdn', value: 'drop.com' } }, mins: 5 }]
          : [],
        otherMins: 0,
      })),
    }
    ;(api.usage.series as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(resp)
    renderPage([`/profiles/${PID}/timeline?date=2026-05-20&stackBy=app`])
    await waitFor(() =>
      expect(screen.getByTestId('profile-timeline-entry-host-drop.com')).toBeInTheDocument(),
    )
    // No long tail in this fixture → no Other entry in the legend.
    expect(screen.queryByTestId('profile-timeline-entry-other')).toBeNull()
  })

  it('reads stackBy=device from URL', async () => {
    (api.usage.series as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(
      richResponse('2026-05-20'),
    )
    renderPage([`/profiles/${PID}/timeline?date=2026-05-20&stackBy=device`])
    await waitFor(() =>
      expect(screen.getByTestId('profile-timeline-device-aa:bb:cc:dd:ee:01')).toBeInTheDocument(),
    )
  })
})
