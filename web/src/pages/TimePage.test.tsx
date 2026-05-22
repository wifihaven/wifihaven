import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import type {
  ProfileTimeStatus, ProfileTimeStatusWeek, ProfileTimeSummary, ProfileTimeSummaryWeek,
  UsageSeriesResponse,
} from '@/types/api'
import { withQuery } from '@/test/queryWrapper'

vi.mock('@/api/client', () => ({
  api: {
    time: {
      statusAll: vi.fn(),
      statusAllWeek: vi.fn(),
      summaryAll: vi.fn(),
      summaryAllWeek: vi.fn(),
      grantExtension: vi.fn(),
    },
    usage: {
      series: vi.fn(),
    },
  },
}))

vi.mock('@/hooks/useAuth', () => ({
  useAuth: () => mockAuth,
}))

import { api } from '@/api/client'
import { TimePage, formatMins, groupBucketsByLocalDay, localBucketOffsetMin } from './TimePage'

let mockAuth = { isAdmin: true }

const limited: ProfileTimeStatus = {
  profileId: 1,
  profileName: 'Kids',
  date: '2026-05-07',
  dailyLimitMins: 120,
  usedMins: 90,
  extensionMins: 0,
  remainingMins: 30,
  siteUsage: [
    { label: 'YouTube', domainPattern: 'youtube.com', limitMins: 30, usedMins: 30, remainingMins: 0 },
  ],
  devices: [{ deviceMac: 'aa:bb:cc:dd:ee:01', deviceName: "Kid's iPad", usedMins: 90 }],
  hostUsage: [
    { host: { type: 'fqdn', value: 'youtube.com' }, usedMins: 35 },
    { host: { type: 'fqdn', value: 'khan-academy.org' }, usedMins: 10 },
    { host: { type: 'ipv4', value: '192.0.2.1' }, usedMins: 5 },
  ],
}

const overLimit: ProfileTimeStatus = {
  profileId: 2,
  profileName: 'Teens',
  date: '2026-05-07',
  dailyLimitMins: 60,
  usedMins: 100,
  extensionMins: 30,
  remainingMins: 0,
  siteUsage: [],
  devices: [{ deviceMac: 'aa:bb:cc:dd:ee:02', deviceName: 'Phone', usedMins: 100 }],
  hostUsage: [],
}

const noLimit: ProfileTimeStatus = {
  profileId: 3,
  profileName: 'Adults',
  date: '2026-05-07',
  dailyLimitMins: null,
  usedMins: 0,
  extensionMins: 0,
  remainingMins: null,
  siteUsage: [],
  devices: [{ deviceMac: 'aa:bb:cc:dd:ee:03', deviceName: 'Laptop', usedMins: 0 }],
  hostUsage: [],
}

// #777 summary payload mirrors the daily card headlines.
const summaries: ProfileTimeSummary[] = [
  { profileId: 1, profileName: 'Kids',   date: '2026-05-07', dailyLimitMins: 120, usedMins: 90,  extensionMins: 0,  remainingMins: 30 },
  { profileId: 2, profileName: 'Teens',  date: '2026-05-07', dailyLimitMins: 60,  usedMins: 100, extensionMins: 30, remainingMins: 0 },
  { profileId: 3, profileName: 'Adults', date: '2026-05-07', dailyLimitMins: null,usedMins: 0,   extensionMins: 0,  remainingMins: null },
]

// #794: server now returns UTC-hour buckets. Pin each day's minutes onto a single early-UTC
// hour of that day so JS Date in the test environment (TZ=UTC under vitest) re-buckets cleanly
// back to the same local date. Totals sum to 210m as before.
const weekKids: ProfileTimeStatusWeek = {
  profileId: 1,
  profileName: 'Kids',
  from: '2026-05-14',
  to: '2026-05-20',
  dailyLimitMins: 120,
  totalMins: 210,
  perBucket: [
    { bucketStart: '2026-05-14T08:00:00Z', usedMins: 20 },
    { bucketStart: '2026-05-15T08:00:00Z', usedMins: 40 },
    { bucketStart: '2026-05-17T08:00:00Z', usedMins: 60 },
    { bucketStart: '2026-05-19T08:00:00Z', usedMins: 75 },
    { bucketStart: '2026-05-20T08:00:00Z', usedMins: 15 },
  ],
  devices: [{ deviceMac: 'aa:bb:cc:dd:ee:01', deviceName: "Kid's iPad", usedMins: 210 }],
  hostUsage: [
    { host: { type: 'fqdn', value: 'youtube.com' }, usedMins: 90 },
    { host: { type: 'fqdn', value: 'khan-academy.org' }, usedMins: 30 },
  ],
}

const weekSummaries: ProfileTimeSummaryWeek[] = [
  { profileId: 1, profileName: 'Kids',   from: '2026-05-14', to: '2026-05-20', dailyLimitMins: 120, totalMins: 210 },
  { profileId: 2, profileName: 'Teens',  from: '2026-05-14', to: '2026-05-20', dailyLimitMins: 60,  totalMins: 50 },
]

// #776: hourly chart on Today card consumes `/api/usage/series?profileId=…`.
// Pin a small response with two non-zero hours for the limited profile so the
// chart renders, and zero-usage for the other profiles so we can also exercise
// the empty state.
const seriesLimited: UsageSeriesResponse = {
  profileId: 1,
  profileName: 'Kids',
  date: '2026-05-07',
  tz: 'UTC',
  topHosts: [],
  buckets: Array.from({ length: 24 }, (_, h) => ({
    hour: h,
    totalMins: h === 9 ? 30 : h === 14 ? 60 : 0,
    perHost: [],
    otherMins: 0,
  })),
}
const seriesEmpty = (profileId: number): UsageSeriesResponse => ({
  profileId,
  profileName: '',
  date: '2026-05-07',
  tz: 'UTC',
  topHosts: [],
  buckets: Array.from({ length: 24 }, (_, h) => ({
    hour: h, totalMins: 0, perHost: [], otherMins: 0,
  })),
})

beforeEach(() => {
  vi.resetAllMocks()
  localStorage.clear()
  mockAuth = { isAdmin: true }
  // Per-profile detail mocks: the lazy fetch passes profileId and the server returns a
  // single-element array.
  ;(api.time.statusAll as unknown as ReturnType<typeof vi.fn>).mockImplementation(
    (_date?: string, profileId?: number) => {
      const all = [limited, overLimit, noLimit]
      const row = profileId === undefined ? all : all.filter(s => s.profileId === profileId)
      return Promise.resolve(row)
    },
  )
  ;(api.time.statusAllWeek as unknown as ReturnType<typeof vi.fn>).mockImplementation(
    (_to?: string, _bucketOffsetMin?: number, profileId?: number) => {
      if (profileId === 1 || profileId === undefined) return Promise.resolve([weekKids])
      return Promise.resolve([])
    },
  )
  ;(api.time.summaryAll as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(summaries)
  ;(api.time.summaryAllWeek as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(weekSummaries)
  ;(api.time.grantExtension as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 1, grantedMinutes: 45 })
  ;(api.usage.series as unknown as ReturnType<typeof vi.fn>).mockImplementation(
    ({ profileId }: { profileId: number }) =>
      Promise.resolve(profileId === 1 ? seriesLimited : seriesEmpty(profileId)),
  )
})

describe('TimePage — accordion (#777)', () => {
  it('renders the lightweight summary for every profile with no detail fetches', async () => {
    // Start with the first profile already expanded (auto-expand default).
    // The other two stay collapsed → no per-profile detail fetch for them.
    render(withQuery(<MemoryRouter><TimePage /></MemoryRouter>))
    await screen.findByTestId('time-row-1')
    expect(screen.getByTestId('time-row-2')).toBeInTheDocument()
    expect(screen.getByTestId('time-row-3')).toBeInTheDocument()
    // Page-load fetch: one summary call. Not the heavyweight per-profile endpoint.
    expect(api.time.summaryAll).toHaveBeenCalledTimes(1)
    // Wait for the auto-expanded first profile to finish loading.
    await screen.findByTestId('time-card-1')
    // Only the auto-expanded profile (id=1) fired the detail fetch.
    expect(api.time.statusAll).toHaveBeenCalledTimes(1)
    expect(api.time.statusAll).toHaveBeenCalledWith(undefined, 1)
  })

  it('expanding a collapsed profile triggers its per-profile fetch; collapsing then re-expanding does NOT refetch', async () => {
    const user = userEvent.setup()
    render(withQuery(<MemoryRouter><TimePage /></MemoryRouter>))
    await screen.findByTestId('time-row-2')
    // Auto-expand fired once for profile 1.
    await waitFor(() => expect(api.time.statusAll).toHaveBeenCalledTimes(1))

    // Expand profile 2 — fires its detail fetch.
    await user.click(screen.getByTestId('time-row-toggle-2'))
    await screen.findByTestId('time-card-2')
    expect(api.time.statusAll).toHaveBeenCalledTimes(2)
    expect(api.time.statusAll).toHaveBeenLastCalledWith(undefined, 2)

    // Collapse and re-expand profile 2 — TanStack cache satisfies the second view.
    await user.click(screen.getByTestId('time-row-toggle-2'))
    expect(screen.queryByTestId('time-card-2')).not.toBeInTheDocument()
    await user.click(screen.getByTestId('time-row-toggle-2'))
    await screen.findByTestId('time-card-2')
    expect(api.time.statusAll).toHaveBeenCalledTimes(2)
  })

  it('headline number renders from the summary (no detail required)', async () => {
    render(withQuery(<MemoryRouter><TimePage /></MemoryRouter>))
    await screen.findByTestId('time-row-3')
    // noLimit row stays collapsed — only the summary's "0m used" surfaces.
    const row3 = screen.getByTestId('time-row-3')
    expect(row3).toHaveTextContent('Adults')
    expect(row3).toHaveTextContent('0m used')
  })
})

describe('TimePage — expanded card content', () => {
  it('renders devices, hosts, and site usage inside the expanded profile', async () => {
    render(withQuery(<MemoryRouter><TimePage /></MemoryRouter>))
    // Profile 1 is auto-expanded → detail card surfaces.
    expect(await screen.findByText("Kid's iPad")).toBeInTheDocument()
    // "1:30 used" appears once in the summary header and once in the expanded card.
    expect(screen.getAllByText('1:30 used').length).toBeGreaterThanOrEqual(1)
    // "30m left" similarly appears in both header and card.
    expect(screen.getAllByText('30m left').length).toBeGreaterThanOrEqual(1)
    expect(screen.getByText('YouTube')).toBeInTheDocument()
    expect(screen.getByTestId('time-host-1-youtube.com')).toHaveTextContent('youtube.com')
    expect(screen.getByTestId('time-host-1-youtube.com')).toHaveTextContent('35m')
  })

  it('over-limit and no-limit details render when expanded', async () => {
    const user = userEvent.setup()
    render(withQuery(<MemoryRouter><TimePage /></MemoryRouter>))
    await screen.findByTestId('time-row-2')
    await user.click(screen.getByTestId('time-row-toggle-2'))
    await user.click(screen.getByTestId('time-row-toggle-3'))
    await screen.findByText('Phone')
    expect(screen.getAllByText('Limit reached').length).toBeGreaterThan(0)
    expect(screen.getByText('+30m extended')).toBeInTheDocument()
    expect(screen.getByText('Laptop')).toBeInTheDocument()
    expect(screen.getByText(/No time limit set/)).toBeInTheDocument()
  })
})

describe('TimePage — today hourly chart (#776)', () => {
  it('renders an hourly chart inside the auto-expanded Today card', async () => {
    render(withQuery(<MemoryRouter><TimePage /></MemoryRouter>))
    expect(await screen.findByTestId('time-today-chart-1')).toBeInTheDocument()
    // /api/usage/series is fetched lazily for the expanded profile only.
    await waitFor(() => expect(api.usage.series).toHaveBeenCalledWith(
      expect.objectContaining({ profileId: 1, date: '2026-05-07' }),
    ))
  })

  it('falls back to a sensible empty state when expanding a profile with no usage today', async () => {
    const user = userEvent.setup()
    render(withQuery(<MemoryRouter><TimePage /></MemoryRouter>))
    await screen.findByTestId('time-row-3')
    await user.click(screen.getByTestId('time-row-toggle-3'))
    // Profile 3 (Adults) returned all-zero buckets — empty state, not a blank chart.
    await waitFor(() =>
      expect(screen.getByTestId('time-today-chart-empty-3')).toHaveTextContent(/No usage/i),
    )
  })

  it('does not fetch hourly series until a profile row is expanded', async () => {
    const user = userEvent.setup()
    render(withQuery(<MemoryRouter><TimePage /></MemoryRouter>))
    await screen.findByTestId('time-row-2')
    // Auto-expand of profile 1 triggers one usage.series call; collapsed profiles 2/3 do not.
    await waitFor(() => expect(api.usage.series).toHaveBeenCalledTimes(1))
    expect(api.usage.series).toHaveBeenCalledWith(expect.objectContaining({ profileId: 1 }))
    // Toggling to Week never fetches usage.series at all.
    ;(api.usage.series as unknown as ReturnType<typeof vi.fn>).mockClear()
    await user.click(screen.getByTestId('time-window-week'))
    await screen.findByTestId('time-week-row-1')
    expect(api.usage.series).not.toHaveBeenCalled()
  })
})

describe('TimePage — grant extension', () => {
  it('opens modal, picks 45m preset, types note, and calls grantExtension', async () => {
    const user = userEvent.setup()
    render(withQuery(<MemoryRouter><TimePage /></MemoryRouter>))
    await screen.findByTestId('time-card-1')

    const grantButtons = screen.getAllByRole('button', { name: /\+ Time/ })
    await user.click(grantButtons[0])

    await user.click(screen.getByRole('button', { name: '45m' }))
    await user.type(
      screen.getByPlaceholderText(/Homework finished/),
      'great job',
    )
    await user.click(screen.getByRole('button', { name: /Grant 45m/ }))

    await waitFor(() =>
      expect(api.time.grantExtension).toHaveBeenCalledWith({
        profileId: 1,
        extraMinutes: 45,
        note: 'great job',
      })
    )
    // After mutation: summary + any active detail queries refetch.
    await waitFor(() => expect(api.time.summaryAll).toHaveBeenCalledTimes(2))
  })

  it('passes null note when blank', async () => {
    const user = userEvent.setup()
    render(withQuery(<MemoryRouter><TimePage /></MemoryRouter>))
    await screen.findByTestId('time-card-1')
    const grantButtons = screen.getAllByRole('button', { name: /\+ Time/ })
    await user.click(grantButtons[0])
    await user.click(screen.getByRole('button', { name: /Grant 30m/ }))
    await waitFor(() =>
      expect(api.time.grantExtension).toHaveBeenCalledWith({
        profileId: 1,
        extraMinutes: 30,
        note: null,
      })
    )
  })
})

describe('TimePage — week toggle (#723)', () => {
  it('defaults to Today and only fetches the today summary', async () => {
    render(withQuery(<MemoryRouter><TimePage /></MemoryRouter>))
    await screen.findByTestId('time-row-1')
    expect(api.time.summaryAll).toHaveBeenCalledTimes(1)
    expect(api.time.summaryAllWeek).not.toHaveBeenCalled()
  })

  it('clicking Week fetches the weekly summary and lazy-loads the detail of the auto-expanded row', async () => {
    const user = userEvent.setup()
    render(withQuery(<MemoryRouter><TimePage /></MemoryRouter>))
    await screen.findByTestId('time-row-1')
    await user.click(screen.getByTestId('time-window-week'))
    await screen.findByTestId('time-week-row-1')
    expect(api.time.summaryAllWeek).toHaveBeenCalledTimes(1)
    // Today rows are gone
    expect(screen.queryByTestId('time-row-1')).not.toBeInTheDocument()
    // Auto-expand of the first week row → weekly detail loaded.
    expect(await screen.findByTestId('time-week-card-1')).toBeInTheDocument()
    expect(screen.getByText('3:30 used this week')).toBeInTheDocument()
    expect(screen.getByTestId('time-week-chart-1')).toBeInTheDocument()
    expect(screen.getByTestId('time-week-host-1-youtube.com')).toHaveTextContent('1:30')
    expect(screen.getByTestId('time-week-device-link-aa:bb:cc:dd:ee:01'))
      .toHaveAttribute('href', '/devices/aa%3Abb%3Acc%3Add%3Aee%3A01/timeline')
  })

  it('toggling back to Today serves the cached summary without refetching (#803)', async () => {
    const user = userEvent.setup()
    render(withQuery(<MemoryRouter><TimePage /></MemoryRouter>))
    await screen.findByTestId('time-row-1')
    await user.click(screen.getByTestId('time-window-week'))
    await screen.findByTestId('time-week-row-1')
    await user.click(screen.getByTestId('time-window-today'))
    await screen.findByTestId('time-row-1')
    expect(api.time.summaryAll).toHaveBeenCalledTimes(1)
    expect(api.time.summaryAllWeek).toHaveBeenCalledTimes(1)
  })
})

describe('localBucketOffsetMin (#794)', () => {
  it('returns 0 for whole-hour zones', () => {
    const d = new Date('2026-05-21T12:00:00Z')
    expect(localBucketOffsetMin(d)).toBe(0)
  })
  it('snaps to the nearest 15-min multiple', () => {
    expect([0, 15, 30, 45]).toContain(localBucketOffsetMin())
  })
})

describe('groupBucketsByLocalDay (#794)', () => {
  it('rolls UTC hours into 7 contiguous local-day buckets ending at `to`', () => {
    const out = groupBucketsByLocalDay(
      [
        { bucketStart: '2026-05-14T08:00:00Z', usedMins: 20 },
        { bucketStart: '2026-05-17T08:00:00Z', usedMins: 60 },
        { bucketStart: '2026-05-20T08:00:00Z', usedMins: 15 },
      ],
      '2026-05-20',
    )
    expect(out.map(r => r.date)).toEqual([
      '2026-05-14', '2026-05-15', '2026-05-16',
      '2026-05-17', '2026-05-18', '2026-05-19', '2026-05-20',
    ])
    expect(out.find(r => r.date === '2026-05-14')!.usedMins).toBe(20)
    expect(out.find(r => r.date === '2026-05-17')!.usedMins).toBe(60)
    expect(out.find(r => r.date === '2026-05-20')!.usedMins).toBe(15)
    expect(out.find(r => r.date === '2026-05-18')!.usedMins).toBe(0)
  })
  it('accumulates multiple UTC hours into the same local day', () => {
    const out = groupBucketsByLocalDay(
      [
        { bucketStart: '2026-05-20T08:00:00Z', usedMins: 5 },
        { bucketStart: '2026-05-20T14:00:00Z', usedMins: 25 },
        { bucketStart: '2026-05-20T20:00:00Z', usedMins: 10 },
      ],
      '2026-05-20',
    )
    expect(out.find(r => r.date === '2026-05-20')!.usedMins).toBe(40)
  })
})

describe('formatMins (#791)', () => {
  it('renders sub-60m values as "Xm"', () => {
    expect(formatMins(0)).toBe('0m')
    expect(formatMins(13)).toBe('13m')
    expect(formatMins(59)).toBe('59m')
  })
  it('renders 60m+ as "H:MM"', () => {
    expect(formatMins(60)).toBe('1:00')
    expect(formatMins(195)).toBe('3:15')
    expect(formatMins(621)).toBe('10:21')
    expect(formatMins(260)).toBe('4:20')
  })
  it('coerces non-finite / negative to "0m"', () => {
    expect(formatMins(NaN)).toBe('0m')
    expect(formatMins(-5)).toBe('0m')
    expect(formatMins(Infinity)).toBe('0m')
  })
})

describe('TimePage — role gating', () => {
  it('hides "+ Time" button for non-admins', async () => {
    mockAuth = { isAdmin: false }
    render(withQuery(<MemoryRouter><TimePage /></MemoryRouter>))
    await screen.findByTestId('time-card-1')
    expect(screen.queryByRole('button', { name: /\+ Time/ })).not.toBeInTheDocument()
  })
})
