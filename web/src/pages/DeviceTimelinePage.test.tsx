import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { DeviceTimeStatusWeek, UsageSeriesResponse } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    usage: {
      series: vi.fn(),
    },
    time: {
      statusDeviceWeek: vi.fn(),
    },
  },
}))

// Recharts pulls in ResizeObserver / SVG bits jsdom doesn't fully implement.
// Stub the chart primitives — DeviceTimelinePage's own logic is what we want
// to test (data wiring, empty state, navigation), not Recharts itself.
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
import { DeviceTimelinePage } from './DeviceTimelinePage'

const MAC = 'aa:bb:cc:dd:ee:01'

function renderPage(initialEntries: string[] = [`/devices/${MAC}/timeline`]) {
  return render(
    <MemoryRouter initialEntries={initialEntries}>
      <Routes>
        <Route path="/devices/:mac/timeline" element={<DeviceTimelinePage />} />
      </Routes>
    </MemoryRouter>,
  )
}

function emptyDayResponse(date: string): UsageSeriesResponse {
  return {
    deviceMac: MAC,
    deviceName: "Kid's iPad",
    date,
    tz: 'UTC',
    topHosts: [],
    buckets: Array.from({ length: 24 }, (_, hour) => ({
      hour, totalMins: 0, perHost: [], otherMins: 0,
    })),
  }
}

function richResponse(date: string): UsageSeriesResponse {
  return {
    deviceMac: MAC,
    deviceName: "Kid's iPad",
    date,
    tz: 'America/Los_Angeles',
    topHosts: [
      { host: { type: 'fqdn', value: 'youtube.com' },  dayMins: 30 },
      { host: { type: 'ipv4', value: '170.114.4.226' }, dayMins: 4 },
    ],
    buckets: Array.from({ length: 24 }, (_, hour) => {
      if (hour === 14) {
        return {
          hour,
          totalMins: 10,
          perHost: [
            { host: { type: 'fqdn', value: 'youtube.com' }, mins: 7 },
            { host: { type: 'ipv4', value: '170.114.4.226' }, mins: 2 },
          ],
          otherMins: 1,
        }
      }
      return { hour, totalMins: 0, perHost: [], otherMins: 0 }
    }),
  }
}

beforeEach(() => {
  vi.resetAllMocks()
})

describe('DeviceTimelinePage', () => {
  it('renders empty state when no usage recorded', async () => {
    (api.usage.series as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(
      emptyDayResponse('2026-05-20'),
    )
    renderPage([`/devices/${MAC}/timeline?date=2026-05-20`])
    await waitFor(() =>
      expect(screen.getByTestId('device-timeline-empty')).toBeInTheDocument(),
    )
    expect(screen.getByTestId('device-timeline-name')).toHaveTextContent("Kid's iPad")
  })

  it('renders chart and top-host list with FQDN + bare-IP rows', async () => {
    (api.usage.series as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(
      richResponse('2026-05-20'),
    )
    renderPage([`/devices/${MAC}/timeline?date=2026-05-20`])
    await waitFor(() =>
      expect(screen.getByTestId('device-timeline-chart')).toBeInTheDocument(),
    )
    // Per-host legend rows render for both FQDN and IP.
    expect(screen.getByTestId('device-timeline-host-youtube.com')).toBeInTheDocument()
    const ipRow = screen.getByTestId('device-timeline-host-170.114.4.226')
    expect(ipRow).toBeInTheDocument()
    // #718: IP rows are de-emphasized via italic styling so the FQDN gap shows.
    expect(ipRow.querySelector('.italic')).not.toBeNull()
  })

  it('uses ?date= from URL when present', async () => {
    const mock = api.usage.series as unknown as ReturnType<typeof vi.fn>
    mock.mockResolvedValue(emptyDayResponse('2026-05-15'))
    renderPage([`/devices/${MAC}/timeline?date=2026-05-15`])
    await waitFor(() => expect(mock).toHaveBeenCalled())
    expect(mock.mock.calls[0][0]).toMatchObject({ mac: MAC, date: '2026-05-15' })
  })

  describe('week toggle (#723)', () => {
    function richWeek(to: string): DeviceTimeStatusWeek {
      return {
        deviceMac: MAC,
        deviceName: "Kid's iPad",
        from: '2026-05-14',
        to,
        profileName: 'Kids',
        profileId: 1,
        dailyLimitMins: 120,
        totalMins: 65,
        // #794: UTC-hour buckets — SPA re-buckets to local days. Hours under TZ=UTC (vitest
        // default) map 1:1 back to the original calendar days here.
        perHour: [
          { hourStart: '2026-05-14T08:00:00Z', usedMins: 10 },
          { hourStart: '2026-05-15T08:00:00Z', usedMins: 20 },
          { hourStart: '2026-05-17T08:00:00Z', usedMins: 5 },
          { hourStart: '2026-05-19T08:00:00Z', usedMins: 15 },
          { hourStart: '2026-05-20T08:00:00Z', usedMins: 15 },
        ],
        hostUsage: [
          { host: { type: 'fqdn', value: 'youtube.com' }, usedMins: 40 },
          { host: { type: 'fqdn', value: 'khan-academy.org' }, usedMins: 25 },
        ],
      }
    }

    it('clicking Week swaps to the per-device weekly endpoint and renders', async () => {
      const seriesMock = api.usage.series as unknown as ReturnType<typeof vi.fn>
      const weekMock = api.time.statusDeviceWeek as unknown as ReturnType<typeof vi.fn>
      seriesMock.mockResolvedValue(emptyDayResponse('2026-05-20'))
      weekMock.mockResolvedValue(richWeek('2026-05-20'))

      const user = userEvent.setup()
      renderPage([`/devices/${MAC}/timeline?date=2026-05-20`])
      await waitFor(() => expect(seriesMock).toHaveBeenCalled())
      await user.click(screen.getByTestId('device-timeline-window-week'))

      await waitFor(() => expect(weekMock).toHaveBeenCalled())
      // Date picker doubles as the `to` anchor in week mode.
      expect(weekMock.mock.calls[0]).toEqual([MAC, '2026-05-20'])
      expect(await screen.findByTestId('device-timeline-week-chart')).toBeInTheDocument()
      // #791: 65m -> "1:05"
      expect(screen.getByText(/1:05 total/)).toBeInTheDocument()
      // Top-host list re-renders with weekly per-host totals.
      expect(screen.getByTestId('device-timeline-host-youtube.com')).toHaveTextContent('40m')
    })

    it('week mode renders empty state when the trailing window has no usage', async () => {
      const seriesMock = api.usage.series as unknown as ReturnType<typeof vi.fn>
      const weekMock = api.time.statusDeviceWeek as unknown as ReturnType<typeof vi.fn>
      seriesMock.mockResolvedValue(emptyDayResponse('2026-05-20'))
      weekMock.mockResolvedValue({
        ...richWeek('2026-05-20'),
        totalMins: 0,
        perHour: [],
        hostUsage: [],
      })
      renderPage([`/devices/${MAC}/timeline?date=2026-05-20&window=week`])
      expect(await screen.findByTestId('device-timeline-week-empty')).toBeInTheDocument()
    })
  })
})
