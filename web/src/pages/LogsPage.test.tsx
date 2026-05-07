import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { QueryLog } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    logs: {
      query: vi.fn(),
    },
  },
}))

import { api } from '@/api/client'
import { LogsPage } from './LogsPage'

const log1: QueryLog = {
  id: 1, mac: 'aa:bb:cc:dd:ee:01', deviceName: "Kid's iPad",
  profileId: 1, profileName: 'Kids',
  domain: 'example.com', qtype: 1, blocked: false, reason: '',
  location: 'home', ts: '2026-05-07T10:15:30Z',
}
const log2: QueryLog = {
  id: 2, mac: 'aa:bb:cc:dd:ee:02', deviceName: 'Phone',
  profileId: 1, profileName: 'Kids',
  domain: 'evil.com', qtype: 1, blocked: true, reason: 'category:adult',
  location: 'home', ts: '2026-05-07T10:16:00Z',
}

beforeEach(() => {
  vi.resetAllMocks()
  ;(api.logs.query as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([log1, log2])
})

describe('LogsPage — list', () => {
  it('renders rows from api.logs.query', async () => {
    render(<LogsPage />)
    expect(await screen.findByText('example.com')).toBeInTheDocument()
    expect(screen.getByText('evil.com')).toBeInTheDocument()
    expect(screen.getByText('✗ blocked')).toBeInTheDocument()
    expect(screen.getByText('✓ ok')).toBeInTheDocument()
    expect(screen.getByText('category:adult')).toBeInTheDocument()
  })

  it('renders empty state when no logs', async () => {
    (api.logs.query as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
    render(<LogsPage />)
    expect(await screen.findByText('No logs found.')).toBeInTheDocument()
  })

  it('initial fetch passes default params (no domain, no blocked filter)', async () => {
    render(<LogsPage />)
    await screen.findByText('example.com')
    expect(api.logs.query).toHaveBeenCalledWith({
      domain: undefined,
      blocked: undefined,
      limit: 200,
    })
  })
})

describe('LogsPage — filters', () => {
  it('typing into the domain input refetches with the domain filter', async () => {
    const user = userEvent.setup()
    render(<LogsPage />)
    await screen.findByText('example.com')
    await user.type(screen.getByPlaceholderText(/Filter by domain/), 'foo.com')
    await waitFor(() => {
      expect(api.logs.query).toHaveBeenLastCalledWith({
        domain: 'foo.com',
        blocked: undefined,
        limit: 200,
      })
    })
  })

  it('selecting "Blocked only" sends blocked: true', async () => {
    const user = userEvent.setup()
    render(<LogsPage />)
    await screen.findByText('example.com')
    await user.selectOptions(screen.getByRole('combobox'), 'true')
    await waitFor(() => {
      expect(api.logs.query).toHaveBeenLastCalledWith({
        domain: undefined,
        blocked: true,
        limit: 200,
      })
    })
  })

  it('selecting "Allowed only" sends blocked: false', async () => {
    const user = userEvent.setup()
    render(<LogsPage />)
    await screen.findByText('example.com')
    await user.selectOptions(screen.getByRole('combobox'), 'false')
    await waitFor(() => {
      expect(api.logs.query).toHaveBeenLastCalledWith({
        domain: undefined,
        blocked: false,
        limit: 200,
      })
    })
  })
})
