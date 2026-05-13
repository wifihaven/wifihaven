import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { DashboardStats, QueryLog } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    logs: {
      stats: vi.fn(),
      query: vi.fn(),
    },
  },
}))

import { api } from '@/api/client'
import { DashboardPage } from './DashboardPage'

const stats: DashboardStats = {
  totalToday: 1234,
  blockedToday: 56,
  totalHour: 78,
  blockedHour: 9,
  topBlocked: [
    { domain: 'evil.com', count: 42 },
    { domain: 'ads.example', count: 17 },
  ],
  perDevice: [
    { mac: 'aa:bb:cc:dd:ee:01', deviceName: "Kid's iPad", total: 500, blocked: 20 },
  ],
}

const recent: QueryLog = {
  id: 1, mac: 'aa:bb:cc:dd:ee:01', deviceName: "Kid's iPad",
  profileId: 1, profileName: 'Kids',
  domain: 'example.com', qtype: 1, blocked: false, reason: '',
  location: 'home', ts: '2026-05-07T10:15:30Z', type: 'dns_allow',
}

beforeEach(() => {
  vi.resetAllMocks()
  ;(api.logs.stats as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(stats)
  ;(api.logs.query as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([recent])
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
    (api.logs.stats as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ ...stats, topBlocked: [] })
    render(<DashboardPage />)
    expect(await screen.findByText(/No blocked queries yet/)).toBeInTheDocument()
  })
})
