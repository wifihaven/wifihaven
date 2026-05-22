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

  it('switching bucket to 1h preserves filters and shows aggregate table with default groupBy=domain', async () => {
    const trafficMock = api.usage.traffic as ReturnType<typeof vi.fn>
    trafficMock.mockResolvedValueOnce(rawResp).mockResolvedValueOnce(aggResp)
    renderPage()
    await waitFor(() => expect(api.usage.traffic).toHaveBeenCalledTimes(1))
    const firstCall = (api.usage.traffic as ReturnType<typeof vi.fn>).mock.calls[0][0]

    await userEvent.click(screen.getByTestId('bucket-1h'))
    await waitFor(() => expect(api.usage.traffic).toHaveBeenCalledTimes(2))
    const secondCall = (api.usage.traffic as ReturnType<typeof vi.fn>).mock.calls[1][0]

    expect(secondCall.mac).toBe(firstCall.mac)
    expect(secondCall.profileId).toBe(firstCall.profileId)
    // `from`/`to` are computed at fetch-time from new Date() so they differ
    // between calls; the bucket switch is what matters here.
    expect(secondCall.bucket).toBe('1h')
    expect(secondCall.groupBy).toEqual(['domain'])

    await waitFor(() => expect(screen.getByTestId('aggregate-table')).toBeInTheDocument())
    expect(screen.getByText('youtube.com')).toBeInTheDocument()
  })

  it('clicking a column header toggles it into the groupBy set', async () => {
    const trafficMock = api.usage.traffic as ReturnType<typeof vi.fn>
    trafficMock
      .mockResolvedValueOnce(rawResp)
      .mockResolvedValueOnce(aggResp)
      .mockResolvedValueOnce({ ...aggResp, groupBy: ['device', 'domain'] })
    renderPage()
    await waitFor(() => expect(api.usage.traffic).toHaveBeenCalledTimes(1))
    await userEvent.click(screen.getByTestId('bucket-1h'))
    await waitFor(() => expect(api.usage.traffic).toHaveBeenCalledTimes(2))

    await userEvent.click(screen.getByTestId('traffic-group-device'))
    await waitFor(() => expect(api.usage.traffic).toHaveBeenCalledTimes(3))
    const lastCall = (api.usage.traffic as ReturnType<typeof vi.fn>).mock.calls[2][0]
    expect(lastCall.groupBy?.sort()).toEqual(['device', 'domain'])
  })

  it('surfaces server error', async () => {
    const trafficMock = api.usage.traffic as ReturnType<typeof vi.fn>
    trafficMock.mockRejectedValueOnce(new Error('window_too_large'))
    renderPage()
    await waitFor(() => expect(screen.getByTestId('error')).toHaveTextContent('window_too_large'))
  })
})
