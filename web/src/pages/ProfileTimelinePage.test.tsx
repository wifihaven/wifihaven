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

function renderPage(initialEntries: string[] = [`/time/${PID}/timeline`]) {
  return render(
    <MemoryRouter initialEntries={initialEntries}>
      <Routes>
        <Route path="/time/:profileId/timeline" element={<ProfileTimelinePage />} />
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
    renderPage([`/time/${PID}/timeline?date=2026-05-20`])
    await waitFor(() => expect(mock).toHaveBeenCalled())
    expect(mock.mock.calls[0][0]).toMatchObject({ profileId: PID, date: '2026-05-20' })
  })

  it('renders profile name and empty state', async () => {
    (api.usage.series as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(
      emptyResponse('2026-05-20'),
    )
    renderPage([`/time/${PID}/timeline?date=2026-05-20`])
    await waitFor(() =>
      expect(screen.getByTestId('profile-timeline-empty')).toBeInTheDocument(),
    )
    expect(screen.getByTestId('profile-timeline-name')).toHaveTextContent('Kids')
  })

  it('renders host stack by default, switches to device stack on toggle', async () => {
    (api.usage.series as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(
      richResponse('2026-05-20'),
    )
    renderPage([`/time/${PID}/timeline?date=2026-05-20`])
    await waitFor(() =>
      expect(screen.getByTestId('profile-timeline-chart')).toBeInTheDocument(),
    )
    // Default: host stack visible, top-hosts list shown.
    expect(screen.getByTestId('profile-timeline-host-youtube.com')).toBeInTheDocument()
    expect(screen.queryByTestId('profile-timeline-device-aa:bb:cc:dd:ee:01')).toBeNull()

    fireEvent.click(screen.getByTestId('profile-timeline-stack-device'))

    // After toggle: device list visible, host list gone.
    await waitFor(() =>
      expect(screen.getByTestId('profile-timeline-device-aa:bb:cc:dd:ee:01')).toBeInTheDocument(),
    )
    expect(screen.queryByTestId('profile-timeline-host-youtube.com')).toBeNull()
  })

  it('reads stackBy=device from URL', async () => {
    (api.usage.series as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(
      richResponse('2026-05-20'),
    )
    renderPage([`/time/${PID}/timeline?date=2026-05-20&stackBy=device`])
    await waitFor(() =>
      expect(screen.getByTestId('profile-timeline-device-aa:bb:cc:dd:ee:01')).toBeInTheDocument(),
    )
  })
})
