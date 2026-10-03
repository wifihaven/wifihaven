import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import type { BlocklistSummary, Profile, ProfileDetail } from '@/types/api'
import { withQuery } from '@/test/queryWrapper'

vi.mock('@/api/client', () => ({
  api: {
    profiles: { list: vi.fn(), getGlobal: vi.fn() },
    blocklists: { list: vi.fn() },
  },
}))

import { api } from '@/api/client'
import { CategoryCoverageOverview, ProfileCoverageChip } from './CategoryCoverage'

const mock = (f: unknown) => f as unknown as ReturnType<typeof vi.fn>

function profile(over: Partial<Profile> = {}): Profile {
  return {
    id: 1, name: 'Kids', blockedCategories: [], paused: false,
    failureMode: 'LastKnownGood', crossDeviceOverlapMode: 'Sum', pauseMode: 'soft',
    defaultDeny: false, ...over,
  }
}

function detail(p: Profile): ProfileDetail {
  return { profile: p, schedules: [], timeLimit: null, scheduleIds: [] } as unknown as ProfileDetail
}

const cat = (id: string, name: string): BlocklistSummary => ({
  id, name, description: null, bundled: true, source: null, hostCount: 1, lastBuiltAt: null,
})

// ── The collapsed profile-card chip ───────────────────────────────────────────
//
// #2824: the whole point is that this is visible WITHOUT expanding the card, and
// that "blocks nothing" is an explicit, visually distinct state rather than the
// absence of chips.
describe('ProfileCoverageChip (#2824)', () => {
  function renderChip(p: Profile, globalCategories: string[], status: 'pending' | 'error' | 'ready' = 'ready') {
    return render(
      <ProfileCoverageChip profile={p} globalCategories={globalCategories} globalStatus={status} />,
    )
  }

  it('renders an explicit "blocks nothing" state, not empty space', () => {
    renderChip(profile(), [])
    const chip = screen.getByTestId('profile-coverage-1')
    expect(chip).toHaveAttribute('data-coverage', 'none')
    expect(chip).toHaveTextContent(/blocks nothing/i)
  })

  it('renders the count of blocked categories when the profile is covered', () => {
    renderChip(profile({ blockedCategories: ['ads', 'adult', 'malware'] }), [])
    const chip = screen.getByTestId('profile-coverage-1')
    expect(chip).toHaveAttribute('data-coverage', 'covered')
    expect(chip).toHaveTextContent('3')
  })

  // PolicyService.scala:769-771 (#1771) unions the global sentinel's categories
  // into every non-default-deny profile, so this profile IS protected.
  it('does NOT cry "blocks nothing" when coverage comes from the household-global layer', () => {
    renderChip(profile(), ['adult'])
    const chip = screen.getByTestId('profile-coverage-1')
    expect(chip).toHaveAttribute('data-coverage', 'covered')
    expect(chip).not.toHaveTextContent(/blocks nothing/i)
  })

  it('reports a default-deny profile as blocking everything, never as blocking nothing', () => {
    renderChip(profile({ defaultDeny: true }), [])
    const chip = screen.getByTestId('profile-coverage-1')
    expect(chip).toHaveAttribute('data-coverage', 'default-deny')
    expect(chip).not.toHaveTextContent(/blocks nothing/i)
  })

  // The required loading-state guarantee (AGENTS.md#loading-states): a pending
  // household-global fetch must not render as "blocks nothing", because the
  // global layer is half of the coverage answer.
  it('shows a skeleton while the household-global layer is still loading', () => {
    renderChip(profile(), [], 'pending')
    expect(screen.getByTestId('profile-coverage-loading-1')).toBeInTheDocument()
    expect(screen.queryByText(/blocks nothing/i)).not.toBeInTheDocument()
  })

  it('shows an explicit unknown state when the household-global layer failed to load', () => {
    renderChip(profile(), [], 'error')
    const chip = screen.getByTestId('profile-coverage-1')
    expect(chip).toHaveAttribute('data-coverage', 'unknown')
    expect(chip).toHaveTextContent(/unknown/i)
    expect(screen.queryByText(/blocks nothing/i)).not.toBeInTheDocument()
  })

  // The global sentinel IS the household-wide layer; having no categories on it
  // is the ordinary state, so alarming there would train the operator to ignore
  // the alarm that matters.
  it('does not raise the alarm on the household-global sentinel itself', () => {
    renderChip(profile({ id: 99, isGlobal: true }), [])
    const chip = screen.getByTestId('profile-coverage-99')
    expect(chip).toHaveAttribute('data-coverage', 'none-global')
    expect(chip).not.toHaveTextContent(/blocks nothing/i)
  })
})

// ── The read-only coverage overview on the Blocklists page ────────────────────
describe('CategoryCoverageOverview (#2824)', () => {
  const kids   = profile({ id: 1, name: 'Kids', blockedCategories: ['ads', 'adult'] })
  const sameer = profile({ id: 2, name: 'Sameer', blockedCategories: [] })
  const global = profile({ id: 99, name: 'Household', isGlobal: true, blockedCategories: ['malware'] })

  function renderOverview() {
    return render(withQuery(
      <MemoryRouter><CategoryCoverageOverview /></MemoryRouter>,
    ))
  }

  beforeEach(() => {
    vi.resetAllMocks()
    mock(api.blocklists.list).mockResolvedValue([cat('ads', 'Ads'), cat('adult', 'Adult'), cat('malware', 'Malware')])
    mock(api.profiles.list).mockResolvedValue([detail(kids), detail(sameer)])
    mock(api.profiles.getGlobal).mockResolvedValue(detail(global))
  })

  it('shows every profile against every category without expanding anything', async () => {
    renderOverview()
    const table = await screen.findByTestId('category-coverage-table')
    for (const id of ['ads', 'adult', 'malware']) {
      expect(within(table).getByTestId(`category-coverage-col-${id}`)).toBeInTheDocument()
    }
    expect(within(table).getByTestId('category-coverage-row-1')).toBeInTheDocument()
    expect(within(table).getByTestId('category-coverage-row-2')).toBeInTheDocument()

    expect(screen.getByTestId('category-coverage-cell-1-adult')).toHaveAttribute('data-coverage', 'own')
    expect(screen.getByTestId('category-coverage-cell-2-adult')).toHaveAttribute('data-coverage', 'none')
    // `malware` is on the global sentinel, so it reaches both profiles.
    expect(screen.getByTestId('category-coverage-cell-1-malware')).toHaveAttribute('data-coverage', 'inherited')
    expect(screen.getByTestId('category-coverage-cell-2-malware')).toHaveAttribute('data-coverage', 'inherited')
  })

  it('calls out a profile that blocks nothing of its own as an explicit state', async () => {
    mock(api.profiles.getGlobal).mockResolvedValue(detail(profile({ id: 99, name: 'Household', isGlobal: true })))
    renderOverview()
    await screen.findByTestId('category-coverage-table')
    const cell = screen.getByTestId('category-coverage-count-2')
    expect(cell).toHaveAttribute('data-coverage', 'none')
    expect(cell).toHaveTextContent(/blocks nothing/i)
  })

  it('renders a skeleton — never an empty grid — while the queries are pending', () => {
    mock(api.profiles.list).mockReturnValue(new Promise(() => {}))
    renderOverview()
    expect(screen.getByTestId('category-coverage-loading')).toBeInTheDocument()
    expect(screen.queryByTestId('category-coverage-table')).not.toBeInTheDocument()
    expect(screen.queryByText(/blocks nothing/i)).not.toBeInTheDocument()
  })

  it('renders an error affordance — never an empty grid — when a query fails', async () => {
    mock(api.profiles.list).mockRejectedValue(new Error('profiles boom'))
    renderOverview()
    expect(await screen.findByTestId('category-coverage-error')).toHaveTextContent(/profiles boom/)
    expect(screen.queryByTestId('category-coverage-table')).not.toBeInTheDocument()
    expect(screen.queryByText(/blocks nothing/i)).not.toBeInTheDocument()
  })

  // #1473 deliberately removed the blocklist x profile TOGGLE matrix from this
  // page. A read-only coverage view is compatible with that; a second editing
  // surface is not.
  it('is strictly read-only — no checkboxes, no toggles (#1473)', async () => {
    renderOverview()
    const table = await screen.findByTestId('category-coverage-table')
    expect(within(table).queryAllByRole('checkbox')).toHaveLength(0)
    expect(within(table).queryAllByRole('button')).toHaveLength(0)
    expect(table.querySelectorAll('input, select, textarea')).toHaveLength(0)
  })
})
