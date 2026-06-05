import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'

vi.mock('@/api/client', () => ({
  api: {
    global: {
      get: vi.fn(),
      addAllow: vi.fn(),
      removeAllow: vi.fn(),
      addBlock: vi.fn(),
      removeBlock: vi.fn(),
      setBlocklists: vi.fn(),
      setFlags: vi.fn(),
    },
    blocklists: {
      list: vi.fn(),
    },
  },
}))

import { api } from '@/api/client'
import type { BlocklistSummary, GlobalPolicyView } from '@/types/api'
import { GlobalPolicyPage } from './GlobalPolicyPage'
import { withQuery } from '@/test/queryWrapper'

const VIEW: GlobalPolicyView = {
  allow: [
    {
      host: 'ocsp.example.com',
      reason: 'PKI',
      addedBy: 'admin',
      addedAt: '2026-06-01 14:00:00+00',
      removedBy: null,
      removedAt: null,
    },
    {
      host: 'old.example.com',
      reason: null,
      addedBy: 'admin',
      addedAt: '2026-05-01 10:00:00+00',
      removedBy: 'admin',
      removedAt: '2026-05-02 10:00:00+00',
    },
  ],
  blocks: [
    {
      host: 'malware.example.com',
      reason: 'operator',
      addedBy: 'admin',
      addedAt: '2026-06-01 14:00:00+00',
      removedBy: null,
      removedAt: null,
    },
  ],
  blocklistIds: ['ads'],
  blocked: false,
  blockReason: null,
  blockIpOnly: false,
}

const BLOCKLISTS: BlocklistSummary[] = [
  { id: 'ads', name: 'Ads', bundled: true, hostCount: 100 },
  { id: 'adult', name: 'Adult', bundled: true, hostCount: 200 },
]

const fn = (m: unknown) => m as unknown as ReturnType<typeof vi.fn>

beforeEach(() => {
  vi.resetAllMocks()
  fn(api.global.get).mockResolvedValue(VIEW)
  fn(api.blocklists.list).mockResolvedValue(BLOCKLISTS)
  fn(api.global.addAllow).mockResolvedValue(undefined)
  fn(api.global.removeAllow).mockResolvedValue(undefined)
  fn(api.global.setBlocklists).mockResolvedValue(undefined)
  fn(api.global.setFlags).mockResolvedValue(undefined)
})

describe('GlobalPolicyPage (#1320)', () => {
  it('renders the global allow + block audit view', async () => {
    render(withQuery(<GlobalPolicyPage />))

    expect(await screen.findByTestId('global-allow-row-ocsp.example.com')).toBeInTheDocument()
    // active allow shows the host + audit reason + who added it
    const row = screen.getByTestId('global-allow-row-ocsp.example.com')
    expect(row).toHaveTextContent('ocsp.example.com')
    expect(row).toHaveTextContent('PKI')
    expect(row).toHaveTextContent('admin')
    // soft-deleted entry is in history, not the active list
    expect(screen.queryByTestId('global-allow-row-old.example.com')).not.toBeInTheDocument()
    expect(screen.getByTestId('global-allow-history-toggle')).toHaveTextContent('1 removed')
    // global block surfaced
    expect(screen.getByTestId('global-block-row-malware.example.com')).toBeInTheDocument()
  })

  it('adds an always-allow host via the API', async () => {
    const user = userEvent.setup()
    render(withQuery(<GlobalPolicyPage />))
    await screen.findByTestId('global-allow-card')

    await user.type(screen.getByTestId('global-allow-host-input'), 'captive.apple.com')
    await user.type(screen.getByTestId('global-allow-reason-input'), 'captive portal')
    await user.click(screen.getByTestId('global-allow-add'))

    await waitFor(() =>
      expect(api.global.addAllow).toHaveBeenCalledWith({
        host: 'captive.apple.com',
        reason: 'captive portal',
      }),
    )
  })

  it('removes an active allow host via the API', async () => {
    const user = userEvent.setup()
    render(withQuery(<GlobalPolicyPage />))
    await screen.findByTestId('global-allow-row-ocsp.example.com')

    await user.click(screen.getByTestId('global-allow-remove-ocsp.example.com'))
    await waitFor(() =>
      expect(api.global.removeAllow).toHaveBeenCalledWith('ocsp.example.com'),
    )
  })

  it('toggles a global category blocklist via the API', async () => {
    const user = userEvent.setup()
    render(withQuery(<GlobalPolicyPage />))
    await screen.findByTestId('global-category-adult')

    // 'ads' already selected; clicking 'adult' should set both.
    await user.click(screen.getByTestId('global-category-adult'))
    await waitFor(() =>
      expect(api.global.setBlocklists).toHaveBeenCalledWith(['ads', 'adult']),
    )
  })

  it('enables network lockdown via the flags API', async () => {
    const user = userEvent.setup()
    render(withQuery(<GlobalPolicyPage />))
    await screen.findByTestId('global-flags-card')

    await user.click(screen.getByTestId('global-lockdown-toggle'))
    await waitFor(() =>
      expect(api.global.setFlags).toHaveBeenCalledWith({
        blocked: true,
        blockReason: 'Manual',
        blockIpOnly: false,
      }),
    )
  })
})
