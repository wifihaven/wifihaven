import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, act, waitFor } from '@testing-library/react'
import type { DashboardNow, DashboardStats, QueryLog } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    logs: {
      stats: vi.fn(),
      query: vi.fn(),
    },
    dashboard: {
      now: vi.fn(),
    },
  },
}))

import { api } from '@/api/client'
import { DashboardPage, NowSection } from './DashboardPage'

const stats: DashboardStats = {
  totalToday: 1234,
  blockedToday: 56,
  totalHour: 78,
  blockedHour: 9,
  topBlocked: [
    { host: { type: 'fqdn', value: 'evil.com' }, count: 42 },
    { host: { type: 'fqdn', value: 'ads.example' }, count: 17 },
  ],
  perDevice: [
    { mac: 'aa:bb:cc:dd:ee:01', deviceName: "Kid's iPad", total: 500, blocked: 20 },
  ],
}

const recent: QueryLog = {
  id: 1, mac: 'aa:bb:cc:dd:ee:01', deviceName: "Kid's iPad",
  profileId: 1, profileName: 'Kids',
  host: { type: 'fqdn', value: 'example.com' }, qtype: 1, blocked: false, reason: '',
  location: 'home', ts: '2026-05-07T10:15:30Z',
}

const emptyNow: DashboardNow = { asOf: '2026-05-13T10:00:00Z', profiles: [] }

const liveNow: DashboardNow = {
  asOf: '2026-05-13T10:00:00Z',
  profiles: [
    {
      id: 1,
      name: 'Kids',
      paused: false,
      activeDevices: [
        {
          id: 10,
          name: 'iPhone',
          mac: 'aa:bb:cc:dd:ee:01',
          lastSeenSeconds: 30,
          topHosts: [
            { host: { type: 'fqdn', value: 'youtube.com' }, activeSeconds: 840 },
            { host: { type: 'fqdn', value: 'tiktok.com' }, activeSeconds: 120 },
          ],
          currentSession: {
            host: { type: 'fqdn', value: 'youtube.com' },
            startedAt: '2026-05-13T09:46:00Z',
            durationSeconds: 840,
          },
        },
      ],
    },
    { id: 2, name: 'Adults', paused: false, activeDevices: [] },
  ],
}

const mockStats = () => api.logs.stats as unknown as ReturnType<typeof vi.fn>
const mockQuery = () => api.logs.query as unknown as ReturnType<typeof vi.fn>
const mockNow   = () => api.dashboard.now as unknown as ReturnType<typeof vi.fn>

beforeEach(() => {
  vi.resetAllMocks()
  mockStats().mockResolvedValue(stats)
  mockQuery().mockResolvedValue([recent])
  mockNow().mockResolvedValue(emptyNow)
})

describe('DashboardPage', () => {
  it('renders stat cards, top-blocked, per-device, and recent queries', async () => {
    render(<DashboardPage />)
    expect(await screen.findByText('1234')).toBeInTheDocument()
    expect(screen.getByText('56')).toBeInTheDocument()
    expect(screen.getByText('78')).toBeInTheDocument()
    expect(screen.getByText('9')).toBeInTheDocument()
    expect(screen.getByText('evil.com')).toBeInTheDocument()
    expect(screen.getByText('42')).toBeInTheDocument()
    expect(screen.getAllByText("Kid's iPad").length).toBeGreaterThan(0)
    expect(screen.getByText('500 queries')).toBeInTheDocument()
    expect(screen.getByText('20 blocked')).toBeInTheDocument()
    expect(screen.getByText('example.com')).toBeInTheDocument()

    expect(api.logs.query).toHaveBeenCalledWith({ limit: 30 })
  })

  it('shows empty state when no blocked queries', async () => {
    mockStats().mockResolvedValue({ ...stats, topBlocked: [] })
    render(<DashboardPage />)
    expect(await screen.findByText(/No blocked queries yet/)).toBeInTheDocument()
  })

  it('recent activity Time column renders in viewer local time (not UTC slice)', async () => {
    render(<DashboardPage />)
    await screen.findByText('example.com')
    const expected = new Date(recent.ts).toLocaleTimeString()
    expect(screen.getByText(expected)).toBeInTheDocument()
  })

  it('renders Now section above stat cards', async () => {
    mockNow().mockResolvedValue(liveNow)
    render(<DashboardPage />)
    await screen.findByText('1234')
    await waitFor(() => expect(screen.getByTestId('now-profile-1')).toBeInTheDocument())
    const nowHeading = screen.getByText('Now')
    const statsCard  = screen.getByText('Queries today')
    const comparison = nowHeading.compareDocumentPosition(statsCard)
    expect(comparison & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })
})

describe('NowSection', () => {
  it('renders one card per profile in id order, idle profiles dimmed', async () => {
    mockNow().mockResolvedValue(liveNow)
    render(<NowSection />)
    const kids   = await screen.findByTestId('now-profile-1')
    const adults = screen.getByTestId('now-profile-2')
    expect(kids).toBeInTheDocument()
    expect(adults).toHaveClass('opacity-60')
    expect(screen.getByText(/No activity in the last 5 minutes/)).toBeInTheDocument()
  })

  it('shows device name, last-seen, current session, and top hosts', async () => {
    mockNow().mockResolvedValue(liveNow)
    render(<NowSection />)
    await screen.findByTestId('now-device-aa:bb:cc:dd:ee:01')
    expect(screen.getByText('iPhone')).toBeInTheDocument()
    expect(screen.getByText('30s ago')).toBeInTheDocument()
    expect(screen.getByText(/watching/)).toBeInTheDocument()
    expect(screen.getAllByText('youtube.com').length).toBeGreaterThan(0)
    expect(screen.getByText('tiktok.com')).toBeInTheDocument()
    expect(screen.getAllByText('14m').length).toBeGreaterThan(0)
  })

  it('renders Paused badge when profile is paused', async () => {
    mockNow().mockResolvedValue({
      ...liveNow,
      profiles: [{ ...liveNow.profiles[0], paused: true }],
    })
    render(<NowSection />)
    expect(await screen.findByText('Paused')).toBeInTheDocument()
  })

  it('polls /dashboard/now every 10s', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      mockNow().mockResolvedValue(emptyNow)
      render(<NowSection />)
      await waitFor(() => expect(mockNow()).toHaveBeenCalledTimes(1))
      await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
      expect(mockNow()).toHaveBeenCalledTimes(2)
      await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
      expect(mockNow()).toHaveBeenCalledTimes(3)
    } finally {
      vi.useRealTimers()
    }
  })

  it('keeps previous data on poll failure', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      mockNow().mockResolvedValueOnce(liveNow).mockRejectedValue(new Error('boom'))
      render(<NowSection />)
      await waitFor(() => expect(screen.getByTestId('now-profile-1')).toBeInTheDocument())
      await act(async () => { await vi.advanceTimersByTimeAsync(10_000) })
      expect(screen.getByTestId('now-profile-1')).toBeInTheDocument()
      expect(screen.getByText('iPhone')).toBeInTheDocument()
    } finally {
      vi.useRealTimers()
    }
  })

  it('renders empty-profiles message when API returns no profiles', async () => {
    mockNow().mockResolvedValue(emptyNow)
    render(<NowSection />)
    expect(await screen.findByText(/No profiles configured yet/)).toBeInTheDocument()
  })
})
