import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import type { UsageSeriesResponse } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    usage: {
      series: vi.fn(),
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
})
