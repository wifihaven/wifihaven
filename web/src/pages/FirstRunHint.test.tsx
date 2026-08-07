import { describe, it, expect, beforeEach, vi } from 'vitest'
import { act, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { withQuery } from '@/test/queryWrapper'
import type { Device, HouseholdSettings, RouterSummary } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    routers: { list: vi.fn() },
    devices: { list: vi.fn() },
    household: { get: vi.fn() },
  },
}))

import { api } from '@/api/client'
import { useDevices, useHouseholdSettings, useRouters } from '@/api/queries'
import { FirstRunHint } from './DashboardPage'

// A settle that is DATA-dependent, not call-dependent. `expect(mock).toHaveBeenCalled()`
// is true on the first render tick — the queryFn fires during mount, long before
// react-query delivers data — so asserting the banner's ABSENCE after it only proves
// the component was still in its loading early-return. This probe renders once all
// three queries have actually resolved, so `findByTestId('loaded')` puts the component
// in the state under test before we assert that nothing rendered.
function Loaded() {
  const routers = useRouters()
  const devices = useDevices()
  const household = useHouseholdSettings()
  const settled = (q: { isPending: boolean; isError: boolean }) => !q.isPending || q.isError
  if (!settled(routers) || !settled(devices) || !settled(household)) return null
  return <span data-testid="loaded" />
}

const listRouters = api.routers.list as unknown as ReturnType<typeof vi.fn>
const listDevices = api.devices.list as unknown as ReturnType<typeof vi.fn>
const getHousehold = api.household.get as unknown as ReturnType<typeof vi.fn>

const router = (over: Partial<RouterSummary> = {}): RouterSummary => ({
  id: 'r1', name: 'home-gw', enrolled: false,
  lastSeenAt: null, lastEtag: null, createdAt: '2026-05-11T00:00:00Z',
  agentVersion: null,
  ...over,
})

const CONNECTED = router({ enrolled: true, lastSeenAt: '2026-05-12T00:00:00Z' })

// NOTE: this factory writes `profileId: null` explicitly, which is NOT how the API
// encodes an unassigned device (the key is omitted — see lib/devices.test.ts). Both
// shapes are unmanaged; `isUnmanaged` is pinned against the real wire shape there,
// so these fixtures stay readable without re-proving it.
const device = (over: Partial<Device> = {}): Device => ({
  id: 1, mac: 'aa:bb:cc:dd:ee:01', name: 'iPad', profileId: 7, profileName: 'Kids',
  lastSeenIp: '192.168.1.20', lastSeenAt: '2026-05-12T00:00:00Z',
  ...over,
})

const household = (policy: 'allow' | 'block'): HouseholdSettings => ({
  dailyResetTime: '00:00:00',
  dailyResetTz: 'America/Los_Angeles',
  heartbeatFilter: { enabled: false, bytesThreshold: 1024, heartbeatHostPatterns: [] },
  unmanagedMacPolicy: { policy, blockPage: true },
  blockEncryptedDns: false,
  ambientGateEnabled: false,
  ambientIsolationMaxHosts: 2,
  ambientMinIsolatedDays: 3,
  ambientLearningWindowDays: 14,
  notifyEmail: null,
})

beforeEach(() => {
  vi.resetAllMocks()
  // Default every test to the finished-onboarding shape; each test overrides
  // only the axis it exercises.
  listRouters.mockResolvedValue([CONNECTED])
  listDevices.mockResolvedValue([device()])
  getHousehold.mockResolvedValue(household('block'))
})

function renderHint() {
  return render(withQuery(
    <MemoryRouter>
      <FirstRunHint />
      <Loaded />
    </MemoryRouter>,
  ))
}

// Assert the banner is absent only AFTER every query has resolved.
async function expectNoBannerOnceLoaded() {
  await screen.findByTestId('loaded')
  expect(screen.queryByTestId('first-run-hint')).not.toBeInTheDocument()
}

describe('FirstRunHint — #2252 router-onboarding-aware welcome banner', () => {
  it('shows the "Set up your router" CTA (to /router-setup) when no router is enrolled yet', async () => {
    listRouters.mockResolvedValue([])
    renderHint()

    const hint = await screen.findByTestId('first-run-hint')
    expect(hint).toHaveAttribute('data-state', 'none')
    // #2234: the none-state CTA now points at the /router-setup guide (hardware + install
    // command), which in turn links to /routers to mint the token.
    const cta = screen.getByRole('link', { name: /set up your router/i })
    expect(cta).toHaveAttribute('href', '/router-setup')
    expect(screen.queryByText(/waiting for your router/i)).not.toBeInTheDocument()
  })

  it('shows "Waiting for your router to connect" when an enrollment exists but no router has connected', async () => {
    // A `routers` row is minted at token creation with a null lastSeenAt; registration
    // (completeEnrollment) stamps it, so a never-seen router is a pending enrollment, not
    // "connected".
    listRouters.mockResolvedValue([router({ lastSeenAt: null })])
    renderHint()

    const hint = await screen.findByTestId('first-run-hint')
    expect(hint).toHaveAttribute('data-state', 'pending')
    expect(screen.getByText(/waiting for your router to connect/i)).toBeInTheDocument()
    // NOT the enroll CTA — the admin already enrolled.
    expect(screen.queryByRole('link', { name: /set up your router/i })).not.toBeInTheDocument()
    expect(screen.getByRole('link', { name: /view install instructions/i })).toHaveAttribute('href', '/router-setup')
  })

  it('renders nothing once a router has checked in and the policy is closed', async () => {
    listRouters.mockResolvedValue([router({ lastSeenAt: '2026-05-12T00:00:00Z', enrolled: true })])
    // Stated at the call site rather than inherited from the beforeEach default: the
    // closed policy is load-bearing for this assertion, not incidental setup.
    getHousehold.mockResolvedValue(household('block'))
    renderHint()

    await expectNoBannerOnceLoaded()
  })

  it('treats a household as connected when ANY router has been seen', async () => {
    listRouters.mockResolvedValue([
      router({ id: 'r1', lastSeenAt: null }),
      // A seen router is always enrolled — both are stamped by completeEnrollment.
      router({ id: 'r2', enrolled: true, lastSeenAt: '2026-05-12T00:00:00Z' }),
    ])
    getHousehold.mockResolvedValue(household('block'))
    renderHint()

    await expectNoBannerOnceLoaded()
  })

  it('does not flash a banner while the routers query is still loading (loading-states rule)', () => {
    listRouters.mockReturnValue(new Promise(() => {}))
    renderHint()
    expect(screen.queryByTestId('first-run-hint')).not.toBeInTheDocument()
  })
})

// #2621 — onboarding does not end when the router connects. A connected household
// still has every device unassigned and the unmanaged-device policy at its `allow`
// default, so nothing on the network is actually governed. The banner carries the
// operator through the last two steps: assign devices, then close the policy.
describe('FirstRunHint — #2621 post-connection onboarding steps', () => {
  it('tells the operator to assign devices while any device has no profile', async () => {
    getHousehold.mockResolvedValue(household('allow'))
    listDevices.mockResolvedValue([device({ profileId: 7 }), device({ id: 2, mac: 'aa:bb:cc:dd:ee:02', profileId: null, profileName: null })])
    renderHint()

    const hint = await screen.findByTestId('first-run-hint')
    expect(hint).toHaveAttribute('data-state', 'devices')
    expect(screen.getByRole('link', { name: /assign your devices/i })).toHaveAttribute('href', '/devices')
    // Not the close-the-policy step — that one is premature while devices are unassigned.
    expect(screen.queryByRole('link', { name: /block unmanaged devices/i })).not.toBeInTheDocument()
  })

  it('tells the operator to close the policy once every device is assigned and the policy is still allow', async () => {
    getHousehold.mockResolvedValue(household('allow'))
    listDevices.mockResolvedValue([device({ profileId: 7 })])
    renderHint()

    const hint = await screen.findByTestId('first-run-hint')
    expect(hint).toHaveAttribute('data-state', 'policy')
    expect(screen.getByRole('link', { name: /block unmanaged devices/i })).toHaveAttribute('href', '/admin')
  })

  it('renders nothing once the policy is set to block, even with devices still unassigned', async () => {
    // Closing the policy is the operator's explicit decision that onboarding is done.
    // A later unassigned device is handled by enforcement, not by re-nagging here.
    getHousehold.mockResolvedValue(household('block'))
    listDevices.mockResolvedValue([device({ profileId: null, profileName: null })])
    renderHint()

    await expectNoBannerOnceLoaded()
  })

  it('shows the devices step for a household that has no devices at all yet', async () => {
    // Router just connected, nothing has generated traffic. "Assign your devices" is
    // the honest next step; "close the policy" would be wrong — there is nothing enrolled.
    getHousehold.mockResolvedValue(household('allow'))
    listDevices.mockResolvedValue([])
    renderHint()

    const hint = await screen.findByTestId('first-run-hint')
    expect(hint).toHaveAttribute('data-state', 'devices')
  })

  // Loading-states rule (#1098): a loading `[]` must not read as "no unmanaged devices"
  // and flash the close-the-policy step at someone mid-onboarding. One case per query,
  // because a guard deleted for just one of them would otherwise slip through.
  //
  // The never-resolving query means the `Loaded` probe never appears either, so these
  // settle on the call and then drain the microtask queue — enough for the OTHER two
  // queries' data to land, which is exactly the state that would flash a banner if the
  // missing guard let `?? []` be read as real data.
  it.each([
    ['devices',  () => listDevices.mockReturnValue(new Promise(() => {}))],
    ['settings', () => getHousehold.mockReturnValue(new Promise(() => {}))],
  ])('does not flash a post-connection step while %s is still loading', async (_which, stall) => {
    getHousehold.mockResolvedValue(household('allow'))
    listDevices.mockResolvedValue([device({ profileId: 7 })])
    stall()
    renderHint()

    await waitFor(() => expect(listRouters).toHaveBeenCalled())
    await act(async () => { await Promise.resolve() })
    expect(screen.queryByTestId('first-run-hint')).not.toBeInTheDocument()
  })

  // An ERROR must behave like pending, not fall through to a state conclusion. Without
  // the `isError` half of each guard, a rejected query leaves `data` undefined and
  // `?? []` reads as real data — the same #1098 failure by a different route.
  // `queryWrapper` sets `retry: false`, so a rejection settles immediately.
  it.each([
    ['routers',  () => listRouters.mockRejectedValue(new Error('boom'))],
    ['devices',  () => listDevices.mockRejectedValue(new Error('boom'))],
    ['settings', () => getHousehold.mockRejectedValue(new Error('boom'))],
  ])('renders nothing when the %s query errors', async (_which, fail) => {
    getHousehold.mockResolvedValue(household('allow'))
    listDevices.mockResolvedValue([device({ profileId: 7 })])
    fail()
    renderHint()

    await expectNoBannerOnceLoaded()
  })

  it('keeps the router steps ahead of the policy step', async () => {
    // An un-connected router with an open policy is still state 1/2 — telling someone
    // to close a policy before their router works would be nonsense.
    listRouters.mockResolvedValue([])
    getHousehold.mockResolvedValue(household('allow'))
    listDevices.mockResolvedValue([])
    renderHint()

    const hint = await screen.findByTestId('first-run-hint')
    expect(hint).toHaveAttribute('data-state', 'none')
  })
})
