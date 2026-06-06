import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import type { BlocklistSummary } from '@/types/api'
import { withQuery } from '@/test/queryWrapper'

vi.mock('@/api/client', () => ({
  api: {
    blocklists: {
      list: vi.fn(),
      hosts: vi.fn(),
    },
  },
}))

import { api } from '@/api/client'
import { BlocklistsPage } from './BlocklistsPage'

function renderPage() {
  return render(withQuery(
    <MemoryRouter>
      <BlocklistsPage />
    </MemoryRouter>,
  ))
}

// Two bundled lists (out of alpha order) + one operator list, so the
// bundled-first-then-alpha sort is observable.
const adult: BlocklistSummary = {
  id: 'adult', name: 'Adult content', description: 'Adult sites',
  bundled: true, source: null, hostCount: 1234, lastBuiltAt: null,
}
const ads: BlocklistSummary = {
  id: 'ads', name: 'Ads & trackers', description: null,
  bundled: true, source: null, hostCount: 50, lastBuiltAt: null,
}
const custom: BlocklistSummary = {
  id: 'custom', name: 'Custom', description: null,
  bundled: false, source: 'operator', hostCount: 3, lastBuiltAt: null,
}

beforeEach(() => {
  vi.resetAllMocks()
  ;(api.blocklists.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([adult, ads, custom])
  ;(api.blocklists.hosts as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'adult', hosts: [] })
})

describe('BlocklistsPage — catalog (#958)', () => {
  it('renders one card per blocklist with name, id, host count, and bundled badge', async () => {
    renderPage()
    const card = await screen.findByTestId('blocklist-adult')
    expect(within(card).getByText('Adult content')).toBeInTheDocument()
    expect(within(card).getByText('adult')).toBeInTheDocument()
    expect(within(card).getByText('Adult sites')).toBeInTheDocument()
    expect(within(card).getByText(/1,234 hosts/)).toBeInTheDocument()
    expect(within(card).getByText('bundled')).toBeInTheDocument()

    // Operator list has no "bundled" badge but does surface its source.
    const customCard = screen.getByTestId('blocklist-custom')
    expect(within(customCard).queryByText('bundled')).not.toBeInTheDocument()
    expect(within(customCard).getByText(/operator/)).toBeInTheDocument()
  })

  it('sorts bundled lists first (alphabetical), then non-bundled', async () => {
    renderPage()
    await screen.findByTestId('blocklist-adult')
    const cards = screen.getAllByTestId(/^blocklist-/)
    // ads + adult (bundled, alpha) before custom (non-bundled).
    expect(cards.map(c => c.getAttribute('data-testid'))).toEqual([
      'blocklist-ads', 'blocklist-adult', 'blocklist-custom',
    ])
  })

  it('surfaces an error banner when the catalog fails to load', async () => {
    (api.blocklists.list as unknown as ReturnType<typeof vi.fn>).mockRejectedValue(new Error('boom'))
    renderPage()
    expect(await screen.findByText('boom')).toBeInTheDocument()
  })

  it('shows an empty state when there are no blocklists', async () => {
    (api.blocklists.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
    renderPage()
    expect(await screen.findByText('No blocklists available.')).toBeInTheDocument()
  })
})

describe('BlocklistsPage — host disclosure', () => {
  it('"View hosts" fetches and lists the hosts, then "Hide hosts" collapses them', async () => {
    (api.blocklists.hosts as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({
      id: 'custom', hosts: ['a.example', 'b.example'],
    })
    const user = userEvent.setup()
    renderPage()
    const card = await screen.findByTestId('blocklist-custom')

    await user.click(within(card).getByRole('button', { name: 'View hosts' }))
    expect(await within(card).findByText('a.example')).toBeInTheDocument()
    expect(within(card).getByText('b.example')).toBeInTheDocument()
    expect(api.blocklists.hosts).toHaveBeenCalledWith('custom')

    await user.click(within(card).getByRole('button', { name: 'Hide hosts' }))
    expect(within(card).queryByText('a.example')).not.toBeInTheDocument()
  })

  it('paginates host lists longer than one page', async () => {
    const hosts = Array.from({ length: 120 }, (_, i) => `host-${i}.example`)
    ;(api.blocklists.hosts as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'adult', hosts })
    const user = userEvent.setup()
    renderPage()
    const card = await screen.findByTestId('blocklist-adult')

    await user.click(within(card).getByRole('button', { name: 'View hosts' }))
    // First page: host-0..host-49 visible, host-50 not.
    expect(await within(card).findByText('host-0.example')).toBeInTheDocument()
    expect(within(card).getByText('host-49.example')).toBeInTheDocument()
    expect(within(card).queryByText('host-50.example')).not.toBeInTheDocument()
    expect(within(card).getByText(/1–50.*of 120/)).toBeInTheDocument()

    await user.click(within(card).getByRole('button', { name: 'Next' }))
    expect(await within(card).findByText('host-50.example')).toBeInTheDocument()
    expect(within(card).queryByText('host-0.example')).not.toBeInTheDocument()
  })
})

// #1473 — assigning a category to a profile moved to the profile card's
// inline editor. The Blocklists page is now the read-only catalog: it must
// not render the old blocklist×profile toggle matrix.
describe('BlocklistsPage — no per-profile assignment matrix (#1473)', () => {
  it('does not render the "Enabled for" profile toggles or call profiles', async () => {
    renderPage()
    await screen.findByTestId('blocklist-adult')
    expect(screen.queryByText(/Enabled for/i)).not.toBeInTheDocument()
    // The description routes operators to the profile card for assignment.
    expect(screen.getByText(/profile/i)).toBeInTheDocument()
  })
})
