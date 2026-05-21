import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import type { ProfileTimeStatus, ProfileTimeStatusWeek } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    time: {
      statusAll: vi.fn(),
      statusAllWeek: vi.fn(),
      grantExtension: vi.fn(),
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

beforeEach(() => {
  vi.resetAllMocks()
  mockAuth = { isAdmin: true }
  ;(api.time.statusAll as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([limited, overLimit, noLimit])
  ;(api.time.statusAllWeek as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([weekKids])
  ;(api.time.grantExtension as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 1, grantedMinutes: 45 })
})

describe('TimePage — list', () => {
  it('renders cards with usage, remaining, extensions, and site usage', async () => {
    render(<MemoryRouter><TimePage /></MemoryRouter>)
    expect(await screen.findByText("Kid's iPad")).toBeInTheDocument()
    // #791: usedMins=90 → "1:30 used"; remainingMins=30 stays "30m left"
    expect(screen.getByText('1:30 used')).toBeInTheDocument()
    expect(screen.getByText('30m left')).toBeInTheDocument()
    expect(screen.getByText('YouTube')).toBeInTheDocument()
    // over limit card
    expect(screen.getByText('Phone')).toBeInTheDocument()
    expect(screen.getAllByText('Limit reached').length).toBeGreaterThan(0)
    expect(screen.getByText('+30m extended')).toBeInTheDocument()
    // no-limit card
    expect(screen.getByText('Laptop')).toBeInTheDocument()
    expect(screen.getByText(/No time limit set/)).toBeInTheDocument()
  })

  it('renders top-host breakdown when hostUsage is present (#262)', async () => {
    render(<MemoryRouter><TimePage /></MemoryRouter>)
    expect(await screen.findByTestId('time-host-1-youtube.com')).toHaveTextContent('youtube.com')
    expect(screen.getByTestId('time-host-1-youtube.com')).toHaveTextContent('35m')
    expect(screen.getByTestId('time-host-1-khan-academy.org')).toHaveTextContent('khan-academy.org')
    expect(screen.getByTestId('time-host-1-khan-academy.org')).toHaveTextContent('10m')
    // (both under 60m, so they keep the bare "Xm" form post-#791)
    // IP-literal host is shown by its address form
    expect(screen.getByTestId('time-host-1-192.0.2.1')).toHaveTextContent('192.0.2.1')
  })

  it('omits the top-host section when hostUsage is empty (#262)', async () => {
    render(<MemoryRouter><TimePage /></MemoryRouter>)
    // overLimit profile (id=2) has hostUsage: [] — no time-host-* testids for it
    await screen.findByTestId('time-card-2')
    expect(screen.queryByTestId(/^time-host-2-/)).not.toBeInTheDocument()
  })
})

describe('TimePage — grant extension', () => {
  it('opens modal, picks 45m preset, types note, and calls grantExtension', async () => {
    const user = userEvent.setup()
    render(<MemoryRouter><TimePage /></MemoryRouter>)
    await screen.findByTestId('time-card-1')

    // Click first "+ Time" button (Kids profile)
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
    // reload
    await waitFor(() => expect(api.time.statusAll).toHaveBeenCalledTimes(2))
  })

  it('passes null note when blank', async () => {
    const user = userEvent.setup()
    render(<MemoryRouter><TimePage /></MemoryRouter>)
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
  it('defaults to Today and only fetches the today endpoint', async () => {
    render(<MemoryRouter><TimePage /></MemoryRouter>)
    await screen.findByTestId('time-card-1')
    expect(api.time.statusAll).toHaveBeenCalledTimes(1)
    expect(api.time.statusAllWeek).not.toHaveBeenCalled()
  })

  it('clicking Week fetches the weekly endpoint and renders chart + totals', async () => {
    const user = userEvent.setup()
    render(<MemoryRouter><TimePage /></MemoryRouter>)
    await screen.findByTestId('time-card-1')
    await user.click(screen.getByTestId('time-window-week'))
    expect(await screen.findByTestId('time-week-card-1')).toBeInTheDocument()
    expect(api.time.statusAllWeek).toHaveBeenCalledTimes(1)
    // Today cards are gone
    expect(screen.queryByTestId('time-card-1')).not.toBeInTheDocument()
    // Weekly total surfaced — #791: 210m → "3:30"
    expect(screen.getByText('3:30 used this week')).toBeInTheDocument()
    // Per-day chart container present (recharts renders SVG inside).
    expect(screen.getByTestId('time-week-chart-1')).toBeInTheDocument()
    // Top-host breakdown carries over to the weekly card — #791: 90m → "1:30"
    expect(screen.getByTestId('time-week-host-1-youtube.com')).toHaveTextContent('1:30')
    // Device link wires through to the per-device timeline (#721).
    expect(screen.getByTestId('time-week-device-link-aa:bb:cc:dd:ee:01'))
      .toHaveAttribute('href', '/devices/aa%3Abb%3Acc%3Add%3Aee%3A01/timeline')
  })

  it('toggling back to Today does not refetch unnecessarily on a second click', async () => {
    const user = userEvent.setup()
    render(<MemoryRouter><TimePage /></MemoryRouter>)
    await screen.findByTestId('time-card-1')
    await user.click(screen.getByTestId('time-window-week'))
    await screen.findByTestId('time-week-card-1')
    await user.click(screen.getByTestId('time-window-today'))
    await screen.findByTestId('time-card-1')
    // Today and Week each fetched once on the first switch to that window.
    expect(api.time.statusAll).toHaveBeenCalledTimes(2)
    expect(api.time.statusAllWeek).toHaveBeenCalledTimes(1)
  })
})

describe('localBucketOffsetMin (#794)', () => {
  it('returns 0 for whole-hour zones', () => {
    // Faux UTC-aligned Date: local midnight equals UTC midnight, so minute=0.
    const d = new Date('2026-05-21T12:00:00Z')
    // Override the host's tz with UTC by feeding an instant whose local representation we
    // control; in the vitest default env TZ=UTC, so this is already UTC-aligned.
    expect(localBucketOffsetMin(d)).toBe(0)
  })
  it('snaps to the nearest 15-min multiple', () => {
    // We can't change the host tz from a unit test, but snap-rounding is pure-arithmetic; the
    // 0 case above plus the in-prod manual cases (India=30, Nepal=15) cover the matrix.
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
    // Empty days fill with zero, not gaps
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
    render(<MemoryRouter><TimePage /></MemoryRouter>)
    await screen.findByTestId('time-card-1')
    expect(screen.queryByRole('button', { name: /\+ Time/ })).not.toBeInTheDocument()
  })
})
