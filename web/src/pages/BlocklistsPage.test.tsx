import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import type { BlocklistSummary, Profile, ProfileDetail } from '@/types/api'
import { withQuery } from '@/test/queryWrapper'

vi.mock('@/api/client', () => ({
  api: {
    blocklists: {
      list: vi.fn(),
      hosts: vi.fn(),
    },
    // #2824 — the read-only coverage overview reads profiles (and the
    // household-global sentinel) so the operator can see which profiles block
    // which categories without expanding seven profile cards. READ only: #1473's
    // removal of the toggle matrix stands.
    profiles: {
      list: vi.fn(),
      getGlobal: vi.fn(),
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

function profileRow(over: Partial<Profile>): ProfileDetail {
  return {
    profile: {
      id: 1, name: 'Kids', blockedCategories: [], paused: false,
      failureMode: 'last-known-good', crossDeviceOverlapMode: 'sum', pauseMode: 'soft',
      defaultDeny: false, ...over,
    },
    scheduleIds: [], timeLimit: null,
  } as unknown as ProfileDetail
}

const kidsRow   = profileRow({ id: 1, name: 'Kids',   blockedCategories: ['adult', 'ads'] })
const sameerRow = profileRow({ id: 2, name: 'Sameer', blockedCategories: [] })
const globalRow = profileRow({ id: 99, name: 'Household', isGlobal: true, blockedCategories: [] })

beforeEach(() => {
  vi.resetAllMocks()
  ;(api.blocklists.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([adult, ads, custom])
  ;(api.blocklists.hosts as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ id: 'adult', hosts: [] })
  ;(api.profiles.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([kidsRow, sameerRow])
  ;(api.profiles.getGlobal as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(globalRow)
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
  it('does not render the "Enabled for" profile toggles', async () => {
    renderPage()
    await screen.findByTestId('blocklist-adult')
    expect(screen.queryByText(/Enabled for/i)).not.toBeInTheDocument()
    // The description routes operators to the profile card for assignment.
    // (#2824 added a read-only coverage section that also says "profile", so
    // match the page description itself rather than any mention of the word.)
    expect(screen.getByText(/Assign a category to a profile from/i)).toBeInTheDocument()
  })

  // #2824 added a read-only coverage view here. #1473's boundary is that this
  // page is not an EDITING surface — so the coverage section carries no controls
  // that could write policy.
  it('keeps the coverage view read-only — no assignment controls', async () => {
    renderPage()
    const table = await screen.findByTestId('category-coverage-table')
    expect(within(table).queryAllByRole('checkbox')).toHaveLength(0)
    expect(table.querySelectorAll('input, select, textarea')).toHaveLength(0)
    expect(api.profiles.list).toHaveBeenCalled()
  })
})

// #2824 — answering "which profiles block adult?" used to mean expanding every
// profile card one at a time, and a profile blocking NOTHING was
// indistinguishable from a protected one.
describe('BlocklistsPage — category coverage overview (#2824)', () => {
  it('renders a profiles x categories coverage grid', async () => {
    renderPage()
    const table = await screen.findByTestId('category-coverage-table')
    expect(within(table).getByTestId('category-coverage-row-1')).toBeInTheDocument()
    expect(within(table).getByTestId('category-coverage-row-2')).toBeInTheDocument()
    expect(screen.getByTestId('category-coverage-cell-1-adult')).toHaveAttribute('data-coverage', 'own')
    expect(screen.getByTestId('category-coverage-cell-2-adult')).toHaveAttribute('data-coverage', 'none')
  })

  it('calls out the profile that blocks nothing', async () => {
    renderPage()
    await screen.findByTestId('category-coverage-table')
    const count = screen.getByTestId('category-coverage-count-2')
    expect(count).toHaveAttribute('data-coverage', 'none')
    expect(count).toHaveTextContent(/blocks nothing/i)
  })
})
