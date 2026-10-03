// #2824 — make per-profile blocked-category coverage legible WITHOUT expanding a
// profile card, so a profile that blocks nothing is obvious on sight.
//
// Why this exists: the operator believed `adult` was enabled on their own profile.
// It was not, and they found out when adult pop-up sites appeared (#2823). At that
// moment one profile blocked NOTHING while the kid profiles blocked nine
// categories each, and nothing in the UI said so — category assignment renders
// only inside an expanded card (`CategoriesSubsection`), and cards are
// collapse-by-default (#972).
//
// Two surfaces, one source (`lib/categoryCoverage`):
//
//   - `ProfileCoverageChip` sits in the COLLAPSED profile-card summary row next to
//     the pause chip. It answers "is this profile unprotected?" at a glance.
//   - `CategoryCoverageOverview` is a read-only profiles x categories grid on the
//     Blocklists page. It answers "which profiles block `adult`?" directly.
//
// #1473 removed the blocklist x profile TOGGLE matrix from the Blocklists page,
// making the profile card the first-class editing surface; that page's own header
// now defines it as "the read-only catalog". The overview below is read-only — no
// inputs, no toggles, nothing that writes policy — so that decision stands. Edits
// still happen on the profile card; each row links there.

import { Link } from 'react-router-dom'
import { useBlocklists, useGlobalProfile, useProfiles } from '@/api/queries'
import { httpStatusOf } from '@/api/httpError'
import { globalContribution, profileCoverage, type CoverageKind, type ProfileCoverage } from '@/lib/categoryCoverage'
import { EmptyState } from '@/components/EmptyState'
import { Skeleton } from '@/components/Skeleton'
import type { Profile } from '@/types/api'

/**
 * How much the caller knows about the household-global layer, which is half of
 * every profile's coverage answer (PolicyService.scala:769-771, #1771).
 *
 * `absent` is NOT an error: `GET /api/profiles/global` 404s for a household with
 * no sentinel row, and an empty global layer is a perfectly computable one.
 * `error` means coverage is unknowable and must say so rather than guess.
 */
export type GlobalLayer =
  | { state: 'pending' }
  | { state: 'error' }
  | { state: 'ready'; categories: readonly string[] }

/**
 * Resolve the global layer from a `useGlobalProfile()` query result. Shared by
 * both surfaces so neither can decide on its own that a failed read means
 * "nothing is blocked".
 */
export function globalLayerFrom(q: {
  isPending: boolean
  isError: boolean
  error: unknown
  data?: { profile: Profile } | undefined
}): GlobalLayer {
  if (q.isError) {
    // 404 = this household has no global sentinel (V65..V73 provisioning gap,
    // see V73__profiles_is_global_per_household.sql). That is an EMPTY layer.
    return httpStatusOf(q.error) === 404 ? { state: 'ready', categories: [] } : { state: 'error' }
  }
  if (q.isPending) return { state: 'pending' }
  // `globalContribution`, not the raw list: a default-deny sentinel contributes
  // NOTHING to other profiles (PolicyService.scala:737-741 + :1518-1526), and
  // that state is reachable from the sentinel's own card. Crediting its
  // categories would be a false all-clear.
  return { state: 'ready', categories: globalContribution(q.data?.profile) }
}

// Deliberately the same chip geometry as the pause chip in the summary row
// (`text-xs px-2 py-1 rounded-lg border`), so #2764's ~42px row density is
// unchanged — this adds a sibling chip, not a second line.
const CHIP_BASE = 'text-xs px-2 py-1 rounded-lg border whitespace-nowrap'

// The "blocks nothing" alarm styling, named once — the chip's filled variant and
// the grid's text-only cell must not drift apart.
const TEXT_ALARM = 'text-red-700 font-semibold'

const KIND_CLASS: Record<CoverageTone | 'unknown', string> = {
  // The point of the whole issue: "blocks nothing" is a loud, distinct state, not
  // the absence of chips. Red matches the other "this needs attention now" chip
  // on this row (time-exceeded).
  'none':         `bg-red-500/10 border-red-500/40 ${TEXT_ALARM}`,
  'covered':      'bg-brand-alt text-brand-text border-brand-border-strong',
  'default-deny': 'bg-brand-accent/10 text-brand-accent border-brand-accent/20',
  // The sentinel IS the household-wide layer; carrying no categories there is the
  // ordinary state, so alarming would train the operator to ignore the alarm.
  'none-global':  'bg-brand-alt text-brand-text-muted border-brand-border',
  'unknown':      'bg-amber-500/10 text-amber-700 border-amber-500/20',
}

/**
 * The ONE place a coverage result becomes something an operator reads. Both
 * surfaces call it, so the chip and the grid cannot disagree about which state a
 * profile is in — the computation being single-sourced is not enough if the
 * presentation re-derives the same four branches twice.
 */
export type CoverageTone = CoverageKind | 'none-global'

export function coverageTone(c: ProfileCoverage, isGlobal: boolean): CoverageTone {
  return c.kind === 'none' && isGlobal ? 'none-global' : c.kind
}

/**
 * `total` (the catalog size) switches the covered label between the chip's
 * "9 categories" and the grid's "9 of 10"; everything else is identical.
 */
export function coverageLabel(
  c: ProfileCoverage,
  isGlobal: boolean,
  total?: number,
): string {
  if (c.kind === 'default-deny') return 'Blocks all'
  if (c.effective.length === 0) return isGlobal ? 'No categories' : '⚠ Blocks nothing'
  if (total != null) return `${c.effective.length} of ${total}`
  return `${c.effective.length} ${c.effective.length === 1 ? 'category' : 'categories'}`
}

function coverageTitle(c: ProfileCoverage, isGlobal: boolean): string {
  if (c.kind === 'default-deny') {
    return 'Default-deny: everything is blocked except this profile’s allowed apps and hosts.'
  }
  if (c.effective.length === 0) {
    return isGlobal
      ? 'Nothing is blocked household-wide. (Default-deny on this profile does not apply ' +
        'household-wide either — it contributes no categories to other profiles.) ' +
        'Profiles can still block categories of their own.'
      : 'This profile blocks no content categories. Expand the card to assign some.'
  }
  const own = c.own.length > 0 ? `On this profile: ${c.own.join(', ')}.` : ''
  const inh = c.inherited.length > 0 ? ` Household-wide: ${c.inherited.join(', ')}.` : ''
  return `${own}${inh}`.trim()
}

/**
 * Category coverage for one profile, rendered in the COLLAPSED card summary.
 *
 * Coverage is derived from `profile.blockedCategories` — the card's own already-
 * loaded data — plus the global layer, so the only query that can be pending here
 * is the global one, and that renders a skeleton rather than a false "nothing".
 */
export function ProfileCoverageChip({
  profile, global,
}: {
  profile: Profile
  global: GlobalLayer
}) {
  const isGlobal = profile.isGlobal === true
  // The sentinel's own categories ARE the global layer, so its chip never waits
  // on (or is blocked by) the global query — if this row rendered, that query
  // resolved.
  const layer: GlobalLayer = isGlobal ? { state: 'ready', categories: [] } : global

  if (layer.state === 'pending') {
    return (
      <Skeleton
        className="h-6 w-24"
        testId={`profile-coverage-loading-${profile.id}`}
        label="Loading blocked categories…"
      />
    )
  }

  if (layer.state === 'error') {
    return (
      <span
        data-testid={`profile-coverage-${profile.id}`}
        data-coverage="unknown"
        title="Could not read the household-wide category layer, so coverage for this profile is unknown."
        className={`${CHIP_BASE} ${KIND_CLASS.unknown}`}
      >
        Coverage unknown
      </span>
    )
  }

  const c = profileCoverage(profile, layer.categories)
  const tone = coverageTone(c, isGlobal)
  const label = coverageLabel(c, isGlobal)
  // At phone width the summary row cannot carry the full label alongside the
  // pause chip, the pause button and Delete — it overlaps the profile name. Drop
  // to a glyph/number there; the colour still carries the alarm, and the full
  // label stays the accessible name (the short form is aria-hidden).
  const short =
    c.kind === 'default-deny' ? 'All'
      : c.effective.length === 0 ? (isGlobal ? '0' : '⚠')
        : String(c.effective.length)

  return (
    <span
      data-testid={`profile-coverage-${profile.id}`}
      data-coverage={tone}
      title={coverageTitle(c, isGlobal)}
      role="img"
      aria-label={`Blocked categories: ${label}`}
      className={`${CHIP_BASE} ${KIND_CLASS[tone]}`}
    >
      <span className="sm:hidden" aria-hidden="true">{short}</span>
      <span className="hidden sm:inline">{label}</span>
    </span>
  )
}

// ── Read-only coverage overview (Blocklists page) ─────────────────────────────

const CELL_GLYPH: Record<'own' | 'inherited' | 'default-deny' | 'none', string> = {
  'own':          '✓',
  'inherited':    '✓',
  'default-deny': '✓',
  'none':         '·',
}

const CELL_CLASS: Record<'own' | 'inherited' | 'default-deny' | 'none', string> = {
  'own':          'text-red-700 font-semibold',
  'inherited':    'text-brand-text-muted',
  'default-deny': 'text-brand-accent',
  'none':         'text-brand-border-strong',
}

const CELL_TITLE: Record<'own' | 'inherited' | 'default-deny' | 'none', string> = {
  'own':          'Blocked by this profile',
  'inherited':    'Blocked household-wide',
  'default-deny': 'Blocked by default-deny (everything is blocked)',
  'none':         'Not blocked',
}

function CoverageRow({ profile, global, categoryIds, isGlobalRow }: {
  profile: Profile
  global: readonly string[]
  categoryIds: string[]
  isGlobalRow: boolean
}) {
  const c = profileCoverage(profile, global)
  const ownSet = new Set(c.own)
  const inheritedSet = new Set(c.inherited)
  const countTone = coverageTone(c, isGlobalRow)
  const countLabel = coverageLabel(c, isGlobalRow, categoryIds.length)

  return (
    <tr
      data-testid={isGlobalRow ? 'category-coverage-row-global' : `category-coverage-row-${profile.id}`}
      className={isGlobalRow ? 'bg-brand-alt/40' : ''}
    >
      <th scope="row" className="text-left font-medium text-brand-ink px-3 py-1.5 whitespace-nowrap">
        {isGlobalRow ? (
          <span title="Applies to every profile that is not in default-deny mode.">
            Household-wide
          </span>
        ) : (
          // Read-only view; the editing surface stays on the profile card (#1473).
          // `?id=` scrolls to and expands that card (#298).
          <Link to={`/profiles?id=${profile.id}`} className="text-brand-accent hover:underline">
            {profile.name}
          </Link>
        )}
      </th>
      <td
        data-testid={isGlobalRow ? 'category-coverage-count-global' : `category-coverage-count-${profile.id}`}
        data-coverage={countTone}
        className={`px-3 py-1.5 whitespace-nowrap text-xs ${
          countTone === 'none' ? TEXT_ALARM : 'text-brand-text-muted'
        }`}
      >
        {countLabel}
      </td>
      {categoryIds.map(id => {
        const tone = c.kind === 'default-deny' ? 'default-deny'
          : ownSet.has(id) ? 'own'
            : inheritedSet.has(id) ? 'inherited'
              : 'none'
        return (
          <td
            key={id}
            data-testid={isGlobalRow
              ? `category-coverage-cell-global-${id}`
              : `category-coverage-cell-${profile.id}-${id}`}
            data-coverage={tone}
            title={CELL_TITLE[tone]}
            className={`text-center px-2 py-1.5 ${CELL_CLASS[tone]}`}
          >
            {CELL_GLYPH[tone]}
          </td>
        )
      })}
    </tr>
  )
}

/**
 * Profiles x categories, read-only. Answers "which profiles block `adult`?" and
 * "which profile blocks nothing?" in one glance, which previously meant expanding
 * every profile card one at a time.
 *
 * Loading and error are rendered as themselves. An empty grid here would read as
 * "nothing is blocked anywhere" — the exact misreading #2823 came from — so the
 * grid is NOT rendered until every input has actually loaded.
 */
export function CategoryCoverageOverview() {
  const profilesQuery = useProfiles()
  const globalQuery = useGlobalProfile()
  const blocklistsQuery = useBlocklists()

  const global = globalLayerFrom(globalQuery)

  const pending = profilesQuery.isPending || blocklistsQuery.isPending || global.state === 'pending'
  const failure = profilesQuery.isError ? profilesQuery.error
    : blocklistsQuery.isError ? blocklistsQuery.error
      : global.state === 'error' ? globalQuery.error
        : null

  // Error first: a failed read is not a loading state, and must not keep spinning.
  if (failure) {
    return (
      <Section>
        <p
          role="alert"
          data-testid="category-coverage-error"
          className="bg-red-500/10 border border-red-500/40 text-red-700 text-sm rounded-lg px-3 py-2"
        >
          Coverage unavailable — {failure instanceof Error ? failure.message : 'failed to load coverage'}
        </p>
      </Section>
    )
  }

  if (pending) {
    return (
      <Section>
        <div data-testid="category-coverage-loading" className="space-y-2">
          <Skeleton className="h-5 w-full" label="Loading category coverage…" />
          <Skeleton className="h-5 w-full" label="Loading category coverage…" />
          <Skeleton className="h-5 w-2/3" label="Loading category coverage…" />
        </div>
      </Section>
    )
  }

  const lists = blocklistsQuery.data ?? []
  const profiles = (profilesQuery.data ?? []).map(p => p.profile)
  const globalProfile = globalQuery.data?.profile
  const globalCategories = global.state === 'ready' ? global.categories : []

  // Bundled first (alphabetical), then operator/test lists — the same ordering as
  // the catalog below and the profile card's category editor, so all three read
  // consistently.
  const categoryIds = [...lists]
    .sort((a, b) => (a.bundled !== b.bundled ? (a.bundled ? -1 : 1) : a.id.localeCompare(b.id)))
    .map(b => b.id)

  if (categoryIds.length === 0 || profiles.length === 0) {
    return (
      <Section>
        <EmptyState
          variant="inline"
          data-testid="category-coverage-empty"
          title={categoryIds.length === 0 ? 'No categories in the catalog.' : 'No profiles yet.'}
        />
      </Section>
    )
  }

  return (
    <Section>
      <div className="overflow-x-auto">
        <table data-testid="category-coverage-table" className="text-sm border-collapse">
          <thead>
            <tr className="border-b border-brand-border">
              <th scope="col" className="text-left text-xs uppercase tracking-wider text-brand-text-muted px-3 py-2">
                Profile
              </th>
              <th scope="col" className="text-left text-xs uppercase tracking-wider text-brand-text-muted px-3 py-2">
                Coverage
              </th>
              {categoryIds.map(id => (
                <th
                  key={id}
                  scope="col"
                  data-testid={`category-coverage-col-${id}`}
                  className="px-2 py-2 text-xs font-mono font-normal text-brand-text-muted align-bottom"
                >
                  {/* Vertical so a dozen categories fit without a wall of text. */}
                  <span className="inline-block [writing-mode:vertical-rl] rotate-180 whitespace-nowrap">
                    {id}
                  </span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody className="divide-y divide-brand-border">
            {globalProfile && (
              <CoverageRow
                profile={globalProfile}
                global={globalCategories}
                categoryIds={categoryIds}
                isGlobalRow
              />
            )}
            {profiles.map(p => (
              <CoverageRow
                key={p.id}
                profile={p}
                global={globalCategories}
                categoryIds={categoryIds}
                isGlobalRow={false}
              />
            ))}
          </tbody>
        </table>
      </div>
      <p className="text-xs text-brand-text-muted mt-2">
        <span className="text-red-700 font-semibold">✓</span> blocked by the profile ·{' '}
        <span className="text-brand-text-muted">✓</span> blocked household-wide ·{' '}
        <span className="text-brand-border-strong">·</span> not blocked. Read-only — assign
        categories from the profile’s card on the{' '}
        <Link to="/profiles" className="text-brand-accent hover:underline">Profiles</Link> page.
      </p>
    </Section>
  )
}

function Section({ children }: { children: React.ReactNode }) {
  return (
    <section
      data-testid="category-coverage"
      className="bg-white border border-brand-border rounded-lg p-4"
    >
      <h2 className="text-base font-semibold text-brand-ink">Coverage by profile</h2>
      <p className="text-xs text-brand-text-muted mt-0.5 mb-3">
        Which profiles block which categories — including the ones that block nothing.
      </p>
      {children}
    </section>
  )
}
