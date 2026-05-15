import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { QueryLog, Session, SessionPage } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    sessions: {
      list: vi.fn(),
    },
    logs: {
      query: vi.fn(),
    },
  },
}))

import { api } from '@/api/client'
import { LogsPage } from './LogsPage'

const session1: Session = {
  mac: 'aa:bb:cc:dd:ee:01',
  deviceName: "Kid's iPad",
  profileId: 1,
  profileName: 'Kids',
  host: { type: 'fqdn', value: 'youtube.com' },
  routerId: 'r-1',
  date: '2026-05-12',
  startedAt: '2026-05-12T14:30:00Z',
  endedAt:   '2026-05-12T14:40:00Z',
  durationSeconds: 600,
  bytesIn: 12_000_000,
  bytesOut: 500_000,
  periodCount: 2,
}
const session2: Session = {
  mac: 'aa:bb:cc:dd:ee:02',
  deviceName: 'Phone',
  profileId: 1,
  profileName: 'Kids',
  host: { type: 'fqdn', value: 'tiktok.com' },
  routerId: 'r-1',
  date: '2026-05-12',
  startedAt: '2026-05-12T13:00:00Z',
  endedAt:   '2026-05-12T13:05:00Z',
  durationSeconds: 180,
  bytesIn: 1_500_000,
  bytesOut: 90_000,
  periodCount: 1,
}
const page: SessionPage = { sessions: [session1, session2], nextCursor: null }

const log1: QueryLog = {
  id: 1, mac: 'aa:bb:cc:dd:ee:01', deviceName: "Kid's iPad",
  profileId: 1, profileName: 'Kids',
  host: { type: 'fqdn', value: 'example.com' }, qtype: 1, blocked: false, reason: '',
  location: 'home', ts: '2026-05-12T10:15:30Z',
}

beforeEach(() => {
  vi.resetAllMocks()
  ;(api.sessions.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(page)
  ;(api.logs.query as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([log1])
})

describe('LogsPage — Sessions tab (default)', () => {
  it('renders Sessions tab by default and calls api.sessions.list', async () => {
    render(<LogsPage />)
    expect(await screen.findByText('youtube.com')).toBeInTheDocument()
    expect(screen.getByText('tiktok.com')).toBeInTheDocument()
    expect(api.sessions.list).toHaveBeenCalledWith({
      host: undefined,
      hours: 24,
      limit: 100,
    })
    expect(api.logs.query).not.toHaveBeenCalled()
  })

  it('shows device name, profile, duration, host for each session', async () => {
    render(<LogsPage />)
    expect(await screen.findByText("Kid's iPad")).toBeInTheDocument()
    expect(screen.getByText('Phone')).toBeInTheDocument()
    // 600s → "10m", 180s → "3m"
    expect(screen.getByText('10m')).toBeInTheDocument()
    expect(screen.getByText('3m')).toBeInTheDocument()
  })

  it('typing into the host input refetches with the host filter', async () => {
    const user = userEvent.setup()
    render(<LogsPage />)
    await screen.findByText('youtube.com')
    await user.type(screen.getByTestId('sessions-filter-host'), 'youtube')
    await waitFor(() => {
      expect(api.sessions.list).toHaveBeenLastCalledWith({
        host: 'youtube',
        hours: 24,
        limit: 100,
      })
    })
  })

  it('shows empty state when no sessions', async () => {
    (api.sessions.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      sessions: [], nextCursor: null,
    } satisfies SessionPage)
    render(<LogsPage />)
    expect(await screen.findByText('No sessions found.')).toBeInTheDocument()
  })
})

describe('LogsPage — Connection events tab', () => {
  it('clicking Connection events tab calls api.logs.query and renders connection-event rows', async () => {
    const user = userEvent.setup()
    render(<LogsPage />)
    await screen.findByText('youtube.com')
    await user.click(screen.getByTestId('logs-tab-raw'))
    expect(await screen.findByText('example.com')).toBeInTheDocument()
    expect(api.logs.query).toHaveBeenCalled()
  })

  it('renders the "Connection events" tab label', async () => {
    render(<LogsPage />)
    expect(await screen.findByRole('tab', { name: 'Connection events' })).toBeInTheDocument()
  })

  it('Connection events shows empty state with its own copy', async () => {
    (api.logs.query as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
    const user = userEvent.setup()
    render(<LogsPage />)
    await screen.findByText('youtube.com')
    await user.click(screen.getByTestId('logs-tab-raw'))
    expect(await screen.findByText('No events found.')).toBeInTheDocument()
  })

  it('Raw events Time column renders in viewer local time (not UTC slice)', async () => {
    const user = userEvent.setup()
    render(<LogsPage />)
    await screen.findByText('youtube.com')
    await user.click(screen.getByTestId('logs-tab-raw'))
    await screen.findByText('example.com')
    const expected = new Date(log1.ts).toLocaleTimeString()
    expect(screen.getByText(expected)).toBeInTheDocument()
  })
})

describe('LogsPage — Sessions tab timestamps', () => {
  it('Started column renders session start in viewer local time', async () => {
    render(<LogsPage />)
    await screen.findByText('youtube.com')
    const expected = new Date(session1.startedAt).toLocaleTimeString([], {
      hour: '2-digit', minute: '2-digit',
    })
    expect(screen.getAllByText(expected).length).toBeGreaterThan(0)
  })
})
