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
    topEntries: [],
    bucketsByEntry: Array.from({ length: 24 }, (_, hour) => ({
      hour, totalMins: 0, perEntity: [], otherMins: 0,
    })),
  }
}

function richResponse(date: string): UsageSeriesResponse {
  const ytEntity = { kind: 'app' as const, id: 'youtube', name: 'YouTube', appId: 1 }
  const ipEntity = {
    kind: 'host' as const, id: '170.114.4.226', name: '170.114.4.226',
    host: { type: 'ipv4' as const, value: '170.114.4.226' },
  }
  return {
    deviceMac: MAC,
    deviceName: "Kid's iPad",
    date,
    tz: 'America/Los_Angeles',
    topHosts: [],
    buckets: Array.from({ length: 24 }, (_, hour) => ({ hour, totalMins: 0, perHost: [], otherMins: 0 })),
    topEntries: [
      { entity: ytEntity, dayMins: 30 },
      { entity: ipEntity, dayMins: 4 },
    ],
    bucketsByEntry: Array.from({ length: 24 }, (_, hour) => {
      if (hour === 14) {
        return {
          hour,
          totalMins: 10,
          perEntity: [
            { entity: ytEntity, mins: 7 },
            { entity: ipEntity, mins: 2 },
          ],
          otherMins: 1,
        }
      }
      return { hour, totalMins: 0, perEntity: [], otherMins: 0 }
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

  it('#1079: renders chart and unified by-app entry list (app + non-app host)', async () => {
    (api.usage.series as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(
      richResponse('2026-05-20'),
    )
    renderPage([`/devices/${MAC}/timeline?date=2026-05-20`])
    await waitFor(() =>
      expect(screen.getByTestId('device-timeline-chart')).toBeInTheDocument(),
    )
    // App rolls up its hosts; non-app host shows individually.
    expect(screen.getByTestId('device-timeline-entry-app-youtube')).toBeInTheDocument()
    expect(screen.getByTestId('device-timeline-entry-host-170.114.4.226')).toBeInTheDocument()
  })

  it('uses ?date= from URL when present', async () => {
    const mock = api.usage.series as unknown as ReturnType<typeof vi.fn>
    mock.mockResolvedValue(emptyDayResponse('2026-05-15'))
    renderPage([`/devices/${MAC}/timeline?date=2026-05-15`])
    await waitFor(() => expect(mock).toHaveBeenCalled())
    expect(mock.mock.calls[0][0]).toMatchObject({ mac: MAC, date: '2026-05-15' })
  })

  describe('"Other" drill-in (#964)', () => {
    function mkEntry(id: string, dayMins: number) {
      return {
        entity: {
          kind: 'host' as const, id, name: id,
          host: { type: 'fqdn' as const, value: id },
        },
        dayMins,
      }
    }
    function withOtherResponse(date: string): UsageSeriesResponse {
      const top5 = Array.from({ length: 5 }, (_, i) => mkEntry(`top${i}.com`, 50 - i))
      return {
        deviceMac: MAC,
        deviceName: "Kid's iPad",
        date,
        tz: 'UTC',
        topHosts: [],
        buckets: Array.from({ length: 24 }, (_, hour) => ({ hour, totalMins: 0, perHost: [], otherMins: 0 })),
        topEntries: top5,
        bucketsByEntry: Array.from({ length: 24 }, (_, hour) => ({
          hour,
          totalMins: hour === 14 ? 50 : 0,
          perEntity: hour === 14 ? top5.map(e => ({ entity: e.entity, mins: 8 })) : [],
          otherMins: hour === 14 ? 10 : 0,
        })),
      }
    }
    function withAllHostsResponse(date: string): UsageSeriesResponse {
      const all = [
        ...Array.from({ length: 5 }, (_, i) => mkEntry(`top${i}.com`, 50 - i)),
        ...Array.from({ length: 4 }, (_, i) => mkEntry(`tail${i}.com`, 6 - i)),
      ]
      return {
        deviceMac: MAC,
        deviceName: "Kid's iPad",
        date,
        tz: 'UTC',
        topHosts: [],
        buckets: Array.from({ length: 24 }, (_, hour) => ({ hour, totalMins: 0, perHost: [], otherMins: 0 })),
        topEntries: all,
        bucketsByEntry: Array.from({ length: 24 }, (_, hour) => ({
          hour, totalMins: 0, perEntity: [], otherMins: 0,
        })),
      }
    }

    it('shows the affordance only when Other > 0 and opens a modal with the long-tail', async () => {
      const mock = api.usage.series as unknown as ReturnType<typeof vi.fn>
      mock
        .mockResolvedValueOnce(withOtherResponse('2026-05-20'))
        .mockResolvedValueOnce(withAllHostsResponse('2026-05-20'))

      const user = userEvent.setup()
      renderPage([`/devices/${MAC}/timeline?date=2026-05-20`])
      const button = await screen.findByTestId('device-timeline-other-button')

      await user.click(button)

      expect(screen.getByTestId('device-timeline-other-modal')).toBeInTheDocument()
      await waitFor(() => expect(mock).toHaveBeenCalledTimes(2))
      expect(mock.mock.calls[1][0]).toMatchObject({ mac: MAC, date: '2026-05-20', topN: 500 })

      await waitFor(() =>
        expect(screen.getByTestId('device-timeline-other-entry-host-tail0.com')).toBeInTheDocument(),
      )
      expect(screen.getByTestId('device-timeline-other-entry-host-tail3.com')).toBeInTheDocument()
      expect(screen.queryByTestId('device-timeline-other-entry-host-top0.com')).toBeNull()
    })

  })

  describe('background/infra suppression panel (#1507)', () => {
    function withSuppressedResponse(date: string): UsageSeriesResponse {
      return {
        deviceMac: MAC,
        deviceName: "Kid's iPad",
        date,
        tz: 'UTC',
        topHosts: [],
        buckets: Array.from({ length: 24 }, (_, hour) => ({ hour, totalMins: 0, perHost: [], otherMins: 0 })),
        topEntries: [
          {
            entity: { kind: 'host' as const, id: 'engaged.com', name: 'engaged.com',
              host: { type: 'fqdn' as const, value: 'engaged.com' } },
            dayMins: 5,
          },
        ],
        bucketsByEntry: Array.from({ length: 24 }, (_, hour) => ({ hour, totalMins: 0, perEntity: [], otherMins: 0 })),
        suppressedHosts: [
          { host: { type: 'fqdn', value: 'ocsp.apple.com' }, bytes: 100_000, buckets: 2, reason: 'infra' },
          { host: { type: 'fqdn', value: 'push.apple.com' }, bytes: 50_000, buckets: 1, reason: 'infra' },
        ],
      }
    }

    it('lists each suppressed host with formatted bytes; engaged surface unchanged', async () => {
      (api.usage.series as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(
        withSuppressedResponse('2026-05-20'),
      )
      renderPage([`/devices/${MAC}/timeline?date=2026-05-20`])
      await waitFor(() =>
        expect(screen.getByTestId('device-timeline-suppressed')).toBeInTheDocument(),
      )
      expect(screen.getByTestId('device-timeline-suppressed-ocsp.apple.com')).toBeInTheDocument()
      expect(screen.getByTestId('device-timeline-suppressed-push.apple.com')).toBeInTheDocument()
      // Bytes are surfaced — not asserting on the exact format string so the formatter can change,
      // just that the byte count is reachable somewhere in the row.
      expect(screen.getByTestId('device-timeline-suppressed-ocsp.apple.com').textContent ?? '')
        .toMatch(/100|KB|kB/)
      // Engaged group is still rendered separately from the suppressed group.
      expect(screen.getByTestId('device-timeline-entry-host-engaged.com')).toBeInTheDocument()
    })

    it('hides the panel when no suppressed hosts are reported', async () => {
      (api.usage.series as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(
        emptyDayResponse('2026-05-20'),
      )
      renderPage([`/devices/${MAC}/timeline?date=2026-05-20`])
      await waitFor(() =>
        expect(screen.getByTestId('device-timeline-empty')).toBeInTheDocument(),
      )
      expect(screen.queryByTestId('device-timeline-suppressed')).toBeNull()
    })

    it('hides the affordance when there is no Other bucket on any hour', async () => {
      (api.usage.series as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(
        emptyDayResponse('2026-05-20'),
      )
      renderPage([`/devices/${MAC}/timeline?date=2026-05-20`])
      await waitFor(() =>
        expect(screen.getByTestId('device-timeline-empty')).toBeInTheDocument(),
      )
      expect(screen.queryByTestId('device-timeline-other-button')).toBeNull()
    })
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
        perBucket: [
          { bucketStart: '2026-05-14T08:00:00Z', usedMins: 10 },
          { bucketStart: '2026-05-15T08:00:00Z', usedMins: 20 },
          { bucketStart: '2026-05-17T08:00:00Z', usedMins: 5 },
          { bucketStart: '2026-05-19T08:00:00Z', usedMins: 15 },
          { bucketStart: '2026-05-20T08:00:00Z', usedMins: 15 },
        ],
        hostUsage: [
          { host: { type: 'fqdn', value: 'youtube.com' }, usedMins: 40, proportionalMins: 38 },
          { host: { type: 'fqdn', value: 'khan-academy.org' }, usedMins: 25, proportionalMins: 23 },
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
      // Date picker doubles as the `to` anchor in week mode. The third arg is the local
      // bucket-offset minute (#794); under vitest's TZ=UTC env that snaps to 0.
      expect(weekMock.mock.calls[0]).toEqual([MAC, '2026-05-20', 0])
      expect(await screen.findByTestId('device-timeline-week-chart')).toBeInTheDocument()
      // #791: 65m -> "1:05"
      expect(screen.getByText(/1:05 total/)).toBeInTheDocument()
      // Top-host list re-renders with weekly per-host totals. The proportional number
      // is what we show first; bucket-presence comes after in parens (#715).
      expect(screen.getByTestId('device-timeline-host-youtube.com')).toHaveTextContent('38m')
      expect(screen.getByTestId('device-timeline-host-youtube.com')).toHaveTextContent('(40m)')
    })

    it('week mode renders empty state when the trailing window has no usage', async () => {
      const seriesMock = api.usage.series as unknown as ReturnType<typeof vi.fn>
      const weekMock = api.time.statusDeviceWeek as unknown as ReturnType<typeof vi.fn>
      seriesMock.mockResolvedValue(emptyDayResponse('2026-05-20'))
      weekMock.mockResolvedValue({
        ...richWeek('2026-05-20'),
        totalMins: 0,
        perBucket: [],
        hostUsage: [],
      })
      renderPage([`/devices/${MAC}/timeline?date=2026-05-20&window=week`])
      expect(await screen.findByTestId('device-timeline-week-empty')).toBeInTheDocument()
    })
  })
})
