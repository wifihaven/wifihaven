// #2603 (SECURITY): the react-query cache must not survive an identity change.
//
// The QueryClient is a process-wide singleton (`main.tsx` creates one for the page's
// lifetime) and, before this fix, `logout` cleared localStorage but left every cached
// response in place. No query key carried a household discriminator either, so two
// households collided on IDENTICAL keys — `['devices']`, `['profiles']`,
// `['dashboard','recent-blocked',null]`. Household B's session then read household A's
// rows straight out of the cache.
//
// The window is the per-endpoint staleTime (`STALE` in queries.ts): while a query is
// FRESH react-query does not revalidate at all, so `devices`/`profiles` (5 min) rendered
// another tenant's inventory for five minutes with no self-correction, and
// `recentBlocked` (5 s) for a frame. Both are asserted here — a test written only against
// the 5 s surface could pass on timing alone.
//
// The tests deliberately leave household B's fetches PENDING (never resolved). That makes
// the assertion deterministic rather than a race: after the switch, anything rendered can
// only have come from the cache. With the cache cleared there is nothing to render, so the
// surfaces are `isPending` and show a loading state (docs/process/loading-states.md) — not
// household A's rows, and not a real-looking empty list.
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import { QueryClientProvider } from '@tanstack/react-query'

vi.mock('@/api/client', () => ({
  api: {
    auth:     { login: vi.fn() },
    devices:  { list: vi.fn() },
    profiles: { list: vi.fn() },
    logs:     { query: vi.fn() },
  },
  // queryClient.ts imports this for its retry predicate.
  isForbiddenError: () => false,
}))

import { api } from '@/api/client'
import { createQueryClient } from '@/api/queryClient'
import { qk, useDevices, useProfiles, useRecentBlocked } from '@/api/queries'
import { AuthProvider, useAuth } from '@/hooks/useAuth'

type Mock = ReturnType<typeof vi.fn>

// A JWT-shaped token whose payload carries the `hh` household claim, matching what
// AuthService mints (jwt-scala merges the `JwtContent` fields — role/tv/hh — into the
// payload alongside the registered sub/iat/exp claims).
function tokenFor(hh: number, sub: string): string {
  const seg = (o: unknown) =>
    btoa(JSON.stringify(o)).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
  return [
    seg({ alg: 'HS256', typ: 'JWT' }),
    seg({ sub, role: 'admin', tv: 0, hh, iat: 0, exp: 9_999_999_999 }),
    'signature',
  ].join('.')
}

const TOKEN_A = tokenFor(1, 'alice')
const TOKEN_B = tokenFor(2, 'bob')

const DEVICE_A = { id: 1, mac: 'aa:aa:aa:aa:aa:aa', name: 'household-A-ipad' }
const PROFILE_A = { profile: { id: 1, name: 'household-A-kid' }, timeLimit: null }
const BLOCKED_A = {
  id: 1,
  mac: 'aa:aa:aa:aa:aa:aa',
  deviceName: 'household-A-ipad',
  host: 'a.example.com',
  ts: new Date().toISOString(),
  blocked: true,
}

const A_STRINGS = [DEVICE_A.name, PROFILE_A.profile.name, BLOCKED_A.host]

// Every render's visible text, so the assertions can rule out a TRANSIENT wrong-tenant
// paint. A test that only inspects the settled DOM passes while the bug is fully present
// on the 5 s surfaces, because there the bad render is replaced by the refetch.
//
// The frame log alone is NOT sufficient either, and the inverse trap is easy to fall into:
// with the cache uncleared nothing invalidates, so the component does not re-render at all
// after the switch and the post-switch slice is EMPTY — a frame-only assertion passes
// vacuously against the live bug. Both checks are needed: the settled DOM catches the
// persistent case (devices/profiles, 5 min), the frame log catches the transient one
// (recentBlocked, 5 s).
let renderLog: string[] = []

function Panels() {
  const devices = useDevices()
  const profiles = useProfiles()
  const blocked = useRecentBlocked(null)
  const text = [
    devices.isPending ? 'devices:loading' : (devices.data ?? []).map(d => d.name).join(','),
    profiles.isPending ? 'profiles:loading' : (profiles.data ?? []).map(p => p.profile.name).join(','),
    // #2601 widened useRecentBlocked's projection to { rows, olderCount, ... } so the panel
    // can tell "no blocks in the window" from "no blocks at all". The identity assertion is
    // unchanged: it still reads the hosts out of the cache entry for this household.
    blocked.isPending ? 'blocked:loading' : (blocked.data?.rows ?? []).map(r => r.host).join(','),
  ].join(' | ')
  renderLog.push(text)
  return <div data-testid="panels">{text}</div>
}

let doLogin: (identifier: string) => Promise<void>
let doLogout: () => void

// Mirrors App.tsx: the data surfaces live behind the authenticated route tree, so a logout
// unmounts them and the next login mounts them FRESH. That remount is where the bug bites
// — a newly mounted `useQuery` reads whatever sits under its key, which is the previous
// tenant's response. Gating on `isAuthenticated` keeps this harness faithful to that.
function Session() {
  const { login, logout, isAuthenticated } = useAuth()
  doLogin = async (identifier: string) => { await login(identifier, 'pw') }
  doLogout = logout
  return isAuthenticated ? <Panels /> : <div data-testid="panels">logged out</div>
}

let queryClient: ReturnType<typeof createQueryClient>

function renderHarness() {
  queryClient = createQueryClient()
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthProvider>
        <Session />
      </AuthProvider>
    </QueryClientProvider>,
  )
}

/** Log out and back in as `identifier`, as two separate commits — as the real app does. */
async function switchTo(identifier: string) {
  await act(async () => { doLogout() })
  await act(async () => { await doLogin(identifier) })
}

/** Arm household B's reads to hang forever, so nothing new can resolve into the DOM. */
function armPendingFetches() {
  const never = () => new Promise(() => {})
  ;(api.devices.list as Mock).mockImplementation(never)
  ;(api.profiles.list as Mock).mockImplementation(never)
  ;(api.logs.query as Mock).mockImplementation(never)
}

/**
 * Assert household A's data is absent from the CURRENT DOM and from every frame rendered
 * since `switchAt`. See the `renderLog` note above for why both halves are load-bearing.
 */
function expectNoHouseholdALeak(switchAt: number) {
  const settled = screen.getByTestId('panels').textContent ?? ''
  const framesAfterSwitch = renderLog.slice(switchAt)
  for (const s of A_STRINGS) {
    expect(settled, `household A's "${s}" is still rendered after the switch`).not.toContain(s)
    const leaked = framesAfterSwitch.filter(frame => frame.includes(s))
    expect(leaked, `household A's "${s}" rendered in ${leaked.length} frame(s) after the switch`)
      .toEqual([])
  }
  // Render-independent: whatever the components happened to paint, household A's responses
  // must not still be sitting in the cache for a later mount to pick up.
  const cached = JSON.stringify(queryClient.getQueryCache().getAll().map(q => q.state.data))
  for (const s of A_STRINGS) {
    expect(cached, `household A's "${s}" is still in the query cache`).not.toContain(s)
  }
}

beforeEach(() => {
  localStorage.clear()
  document.cookie = 'wh_household=; Path=/; Max-Age=0'
  vi.resetAllMocks()
  renderLog = []
  ;(api.devices.list as Mock).mockResolvedValue([DEVICE_A])
  ;(api.profiles.list as Mock).mockResolvedValue([PROFILE_A])
  ;(api.logs.query as Mock).mockResolvedValue({ rows: [BLOCKED_A], nextCursor: null })
  ;(api.auth.login as Mock).mockResolvedValue({
    token: TOKEN_B, username: 'bob', role: 'admin', householdSlug: 'household-b',
  })
})

// The two fixes are independent, and each on its own satisfies the leak assertions above —
// so those alone would let either regress silently. These pin them separately.
describe('#2603 — the two fixes, pinned independently', () => {
  it('logout empties the cache outright', async () => {
    localStorage.setItem('token', TOKEN_A)
    localStorage.setItem('username', 'alice')
    localStorage.setItem('role', 'admin')
    renderHarness()
    await waitFor(() => expect(screen.getByTestId('panels')).toHaveTextContent(DEVICE_A.name))
    expect(queryClient.getQueryCache().getAll().length).toBeGreaterThan(0)

    await act(async () => { doLogout() })

    // Not "stale", not "scoped away" — gone. `logout` must not leave a session's responses
    // behind for anything to read, whatever the keys look like.
    expect(queryClient.getQueryCache().getAll()).toEqual([])
  })

  it('query keys carry the household, so two households cannot collide on one key', () => {
    localStorage.setItem('token', TOKEN_A)
    const asA = [qk.devices(), qk.profiles(), qk.recentBlocked(null), qk.householdSettings()]
    localStorage.setItem('token', TOKEN_B)
    const asB = [qk.devices(), qk.profiles(), qk.recentBlocked(null), qk.householdSettings()]

    for (let i = 0; i < asA.length; i++) {
      expect(JSON.stringify(asA[i])).not.toEqual(JSON.stringify(asB[i]))
    }
    // Prefix-match invalidation still has to work: `qk.profiles()` must remain a prefix of
    // the sentinel key it is relied on to bust (#1773).
    localStorage.setItem('token', TOKEN_A)
    expect(qk.profilesGlobal().slice(0, qk.profiles().length)).toEqual([...qk.profiles()])
  })
})

describe('#2603 — the query cache does not survive a household switch', () => {
  it('household A rows are never rendered after logging in as household B', async () => {
    localStorage.setItem('token', TOKEN_A)
    localStorage.setItem('username', 'alice')
    localStorage.setItem('role', 'admin')
    renderHarness()

    // Household A's session paints its own rows.
    await waitFor(() => {
      expect(screen.getByTestId('panels')).toHaveTextContent(DEVICE_A.name)
      expect(screen.getByTestId('panels')).toHaveTextContent(PROFILE_A.profile.name)
      expect(screen.getByTestId('panels')).toHaveTextContent(BLOCKED_A.host)
    })

    armPendingFetches()
    const switchAt = renderLog.length
    await switchTo('household-b/bob')

    // Nothing household A owned may appear after the switch — not on the 5-minute
    // devices/profiles surfaces, not for a single frame on the 5-second blocked feed.
    expectNoHouseholdALeak(switchAt)
  })

  it('the surfaces are pending after the switch, so a page shows a loading state', async () => {
    localStorage.setItem('token', TOKEN_A)
    localStorage.setItem('username', 'alice')
    localStorage.setItem('role', 'admin')
    renderHarness()
    await waitFor(() => expect(screen.getByTestId('panels')).toHaveTextContent(DEVICE_A.name))

    armPendingFetches()
    await switchTo('household-b/bob')

    // docs/process/loading-states.md: an un-resolved query must read as LOADING. An empty
    // device list here would be a real-looking "this household has no devices".
    const panels = screen.getByTestId('panels')
    expect(panels).toHaveTextContent('devices:loading')
    expect(panels).toHaveTextContent('profiles:loading')
    expect(panels).toHaveTextContent('blocked:loading')
  })

  it('logging back in as household A does not resurrect the pre-logout cache', async () => {
    localStorage.setItem('token', TOKEN_A)
    localStorage.setItem('username', 'alice')
    localStorage.setItem('role', 'admin')
    renderHarness()
    await waitFor(() => expect(screen.getByTestId('panels')).toHaveTextContent(DEVICE_A.name))

    // Same household, fresh session: the cache still must not be served from the previous
    // session's entries. A re-login is an identity event even when the identity matches.
    armPendingFetches()
    ;(api.auth.login as Mock).mockResolvedValue({
      token: TOKEN_A, username: 'alice', role: 'admin', householdSlug: 'household-a',
    })
    const switchAt = renderLog.length
    await switchTo('household-a/alice')

    expectNoHouseholdALeak(switchAt)
  })
})
