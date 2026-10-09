// #2850 (epic #2841, design docs/design/shared-devices.md §10, §15 Q2/Q4/Q5/Q5a): the dashboard's
// "Shared devices" card. Loading → loaded (a checked-out device is a real loaded state, not a
// placeholder), check in / check out per role, the profile picker only when there is more than one
// profile to choose from, a readable message per rejection code, and the `sharedDevices` ws push
// updating the card. Driven by the real SpaWsClient over a mock socket.
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { QueryClientProvider } from '@tanstack/react-query'
import type { MeResponse, ProfileDetail, SharedDevice } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    sharedDevices: { list: vi.fn(), checkIn: vi.fn(), checkOut: vi.fn() },
    profiles: { list: vi.fn() },
    auth: { me: vi.fn() },
    devices: { list: vi.fn() },
    dashboard: { now: vi.fn() },
    time: { summaryAll: vi.fn() },
  },
}))

import { api } from '@/api/client'
import { HttpError } from '@/api/httpError'
import { SpaWsClient, type WsSocketLike } from '@/api/wsClient'
import { AuthProvider } from '@/hooks/useAuth'
import { WsProvider } from '@/hooks/useWs'
import { makeTestQueryClient } from '@/test/queryWrapper'
import { SharedDevicesCard } from './SharedDevicesCard'

class MockSocket implements WsSocketLike {
  static instances: MockSocket[] = []
  sent: string[] = []
  onopen: ((ev?: unknown) => void) | null = null
  onclose: ((ev: { code: number; reason?: string }) => void) | null = null
  onmessage: ((ev: { data: string }) => void) | null = null
  onerror: ((ev?: unknown) => void) | null = null
  constructor(public url: string) {
    MockSocket.instances.push(this)
  }
  send(d: string) {
    this.sent.push(d)
  }
  close() {
    this.onclose?.({ code: 1006 })
  }
  open() {
    this.onopen?.()
  }
  emit(frame: Record<string, unknown>) {
    this.onmessage?.({ data: JSON.stringify(frame) })
  }
  frames() {
    return this.sent.map(s => JSON.parse(s))
  }
}
const lastSocket = () => MockSocket.instances[MockSocket.instances.length - 1]

const mocked = <T,>(f: T) => f as unknown as ReturnType<typeof vi.fn>

const profile = (id: number, name: string): ProfileDetail => ({
  profile: { id, name, blockedCategories: [], paused: false, failureMode: 'block-all', crossDeviceOverlapMode: 'sum', pauseMode: 'soft', defaultDeny: false },
  timeLimit: null,
})
const kids = profile(1, 'Kids')
const teens = profile(2, 'Teens')
const adults = profile(3, 'Adults')

const IPAD = 'aa:bb:cc:dd:ee:01'
const checkedOutIpad: SharedDevice = { mac: IPAD, name: 'Family iPad', holder: null }
const ipadHeldByTeens: SharedDevice = {
  mac: IPAD,
  name: 'Family iPad',
  holder: { profileId: 2, profileName: 'Teens', since: '2026-10-09T15:42:00Z', checkedInBy: 'sam' },
}
const ipadHeldByKids: SharedDevice = {
  ...ipadHeldByTeens,
  holder: { profileId: 1, profileName: 'Kids', since: '2026-10-09T15:42:00Z', checkedInBy: 'alex' },
}

function signIn(role: 'admin' | 'adult' | 'child', me: Partial<MeResponse> = {}) {
  localStorage.setItem('token', 'jwt')
  localStorage.setItem('username', 'alex')
  localStorage.setItem('role', role)
  mocked(api.auth.me).mockResolvedValue({ username: 'alex', role, profileIds: [], ...me })
}

function renderCard({ path = '/dashboard', client }: { path?: string; client?: SpaWsClient } = {}) {
  const qc = makeTestQueryClient()
  const ui = (
    <MemoryRouter initialEntries={[path]}>
      <SharedDevicesCard />
    </MemoryRouter>
  )
  return {
    qc,
    ...render(
      <QueryClientProvider client={qc}>
        <AuthProvider>{client ? <WsProvider client={client}>{ui}</WsProvider> : ui}</AuthProvider>
      </QueryClientProvider>,
    ),
  }
}

beforeEach(() => {
  vi.resetAllMocks()
  MockSocket.instances = []
  localStorage.clear()
  mocked(api.sharedDevices.checkIn).mockResolvedValue(undefined)
  mocked(api.sharedDevices.checkOut).mockResolvedValue(undefined)
  mocked(api.devices.list).mockResolvedValue([])
  mocked(api.dashboard.now).mockResolvedValue({ asOf: 'x', profiles: [] })
  mocked(api.time.summaryAll).mockResolvedValue([])
})
afterEach(() => {
  localStorage.clear()
})

describe('SharedDevicesCard — loading states (#2850)', () => {
  it('shows a skeleton while the list loads, then "Checked out" as a loaded state', async () => {
    signIn('child', { profileIds: [1] })
    let resolve!: (v: SharedDevice[]) => void
    mocked(api.sharedDevices.list).mockReturnValue(new Promise(r => { resolve = r }))
    mocked(api.profiles.list).mockResolvedValue([kids])
    renderCard()

    expect(screen.getByTestId('shared-devices-loading')).toBeInTheDocument()
    expect(screen.queryByText('Checked out')).not.toBeInTheDocument()

    await act(async () => resolve([checkedOutIpad]))
    expect(await screen.findByTestId(`shared-device-status-${IPAD}`)).toHaveTextContent('Checked out')
    expect(screen.queryByTestId('shared-devices-loading')).not.toBeInTheDocument()
  })

  it('shows an error affordance, not an empty card, when the list fails', async () => {
    signIn('child', { profileIds: [1] })
    mocked(api.sharedDevices.list).mockRejectedValue(new Error('boom'))
    mocked(api.profiles.list).mockResolvedValue([kids])
    renderCard()
    expect(await screen.findByTestId('shared-devices-error')).toHaveTextContent(/couldn't load shared devices/i)
  })

  it('renders nothing once loaded when the household has no shared devices', async () => {
    signIn('admin')
    mocked(api.sharedDevices.list).mockResolvedValue([])
    mocked(api.profiles.list).mockResolvedValue([kids])
    renderCard()
    await waitFor(() => expect(api.sharedDevices.list).toHaveBeenCalled())
    await waitFor(() => expect(screen.queryByTestId('shared-devices-loading')).not.toBeInTheDocument())
    expect(screen.queryByTestId('shared-devices-card')).not.toBeInTheDocument()
  })

  it('shows the holder, who checked it in, and since when', async () => {
    signIn('admin')
    mocked(api.sharedDevices.list).mockResolvedValue([ipadHeldByTeens])
    mocked(api.profiles.list).mockResolvedValue([kids, teens])
    renderCard()
    const status = await screen.findByTestId(`shared-device-status-${IPAD}`)
    expect(status).toHaveTextContent('Teens')
    expect(status).toHaveTextContent(/checked in by sam/i)
    expect(status).toHaveTextContent(/since/i)
  })
})

describe('SharedDevicesCard — actions error (#2850 review)', () => {
  it('says so, with Retry, when the profiles needed for the actions fail to load', async () => {
    signIn('child', { profileIds: [1] })
    mocked(api.sharedDevices.list).mockResolvedValue([checkedOutIpad])
    mocked(api.profiles.list).mockRejectedValueOnce(new Error('boom')).mockResolvedValue([kids])
    renderCard()
    const err = await screen.findByTestId('shared-devices-actions-error')
    expect(err).toHaveTextContent(/couldn't load your profiles/i)
    expect(screen.queryByTestId(`shared-device-checkin-${IPAD}`)).not.toBeInTheDocument()
    await userEvent.click(within(err).getByRole('button', { name: 'Retry' }))
    expect(await screen.findByTestId(`shared-device-checkin-${IPAD}`)).toBeInTheDocument()
  })
})

describe('SharedDevicesCard — child check in / check out (#2850)', () => {
  it('a child linked to one profile checks in with no picker', async () => {
    signIn('child', { profileIds: [1] })
    mocked(api.sharedDevices.list).mockResolvedValue([checkedOutIpad])
    mocked(api.profiles.list).mockResolvedValue([kids])
    renderCard()
    await userEvent.click(await screen.findByTestId(`shared-device-checkin-${IPAD}`))
    expect(screen.queryByTestId(`shared-device-picker-${IPAD}`)).not.toBeInTheDocument()
    await waitFor(() => expect(api.sharedDevices.checkIn).toHaveBeenCalledWith(IPAD, { profileId: 1 }))
  })

  it('a child linked to several profiles picks one first', async () => {
    signIn('child', { profileIds: [1, 2] })
    mocked(api.sharedDevices.list).mockResolvedValue([checkedOutIpad])
    mocked(api.profiles.list).mockResolvedValue([kids, teens])
    renderCard()
    await userEvent.click(await screen.findByTestId(`shared-device-checkin-${IPAD}`))
    expect(api.sharedDevices.checkIn).not.toHaveBeenCalled()
    const picker = screen.getByTestId(`shared-device-picker-${IPAD}`)
    await userEvent.selectOptions(within(picker).getByRole('combobox'), '2')
    await userEvent.click(within(picker).getByRole('button', { name: 'Check in' }))
    await waitFor(() => expect(api.sharedDevices.checkIn).toHaveBeenCalledWith(IPAD, { profileId: 2 }))
  })

  it('a child can check out a device held on their own profile, but not someone else\'s', async () => {
    signIn('child', { profileIds: [1] })
    mocked(api.sharedDevices.list).mockResolvedValue([ipadHeldByKids])
    mocked(api.profiles.list).mockResolvedValue([kids])
    renderCard()
    await userEvent.click(await screen.findByTestId(`shared-device-checkout-${IPAD}`))
    await waitFor(() => expect(api.sharedDevices.checkOut).toHaveBeenCalledWith(IPAD))
  })

  it('a child sees no check-out action on a device another profile holds', async () => {
    signIn('child', { profileIds: [1] })
    mocked(api.sharedDevices.list).mockResolvedValue([ipadHeldByTeens])
    mocked(api.profiles.list).mockResolvedValue([kids])
    renderCard()
    await screen.findByTestId(`shared-device-status-${IPAD}`)
    // The holder is visible; the actions wait on the child's linked profiles, then stay absent.
    await waitFor(() => expect(api.auth.me).toHaveBeenCalled())
    expect(screen.queryByTestId(`shared-device-checkout-${IPAD}`)).not.toBeInTheDocument()
    expect(screen.queryByTestId(`shared-device-checkin-${IPAD}`)).not.toBeInTheDocument()
  })

  it.each([
    ['held', 409, {}, 'Someone else has this device checked in. They need to check it out first.'],
    ['profile_blocked', 409, { reason: 'TimeLimit' }, "Kids is out of time for today, so it can't check in."],
    ['profile_blocked', 409, { reason: 'Schedule' }, "Kids is outside its allowed time, so it can't check in right now."],
    ['profile_blocked', 409, { reason: 'Paused' }, "Kids is paused, so it can't check in right now."],
    ['not_linked', 403, {}, "You can only check in on a profile you're linked to."],
    ['not_shared', 409, {}, "This device isn't shared any more."],
  ])('shows a readable message for a %s refusal', async (code, status, extra, message) => {
    signIn('child', { profileIds: [1] })
    mocked(api.sharedDevices.list).mockResolvedValue([checkedOutIpad])
    mocked(api.profiles.list).mockResolvedValue([kids])
    mocked(api.sharedDevices.checkIn).mockRejectedValue(
      new HttpError(status, JSON.stringify({ error: code, ...extra })),
    )
    renderCard()
    await userEvent.click(await screen.findByTestId(`shared-device-checkin-${IPAD}`))
    expect(await screen.findByTestId(`shared-device-error-${IPAD}`)).toHaveTextContent(message)
  })

  it('shows not_held on a check-out that lost the race', async () => {
    signIn('child', { profileIds: [1] })
    mocked(api.sharedDevices.list).mockResolvedValue([ipadHeldByKids])
    mocked(api.profiles.list).mockResolvedValue([kids])
    mocked(api.sharedDevices.checkOut).mockRejectedValue(new HttpError(409, '{"error":"not_held"}'))
    renderCard()
    await userEvent.click(await screen.findByTestId(`shared-device-checkout-${IPAD}`))
    expect(await screen.findByTestId(`shared-device-error-${IPAD}`)).toHaveTextContent('This device is already checked out.')
  })

  it('a child with no linked profile is told to ask a parent', async () => {
    signIn('child', { profileIds: [] })
    mocked(api.sharedDevices.list).mockResolvedValue([checkedOutIpad])
    mocked(api.profiles.list).mockResolvedValue([])
    renderCard()
    expect(await screen.findByTestId(`shared-device-no-profile-${IPAD}`)).toHaveTextContent(/ask a parent/i)
    expect(screen.queryByTestId(`shared-device-checkin-${IPAD}`)).not.toBeInTheDocument()
  })
})

describe('SharedDevicesCard — adults (#2850, design §15 Q5/Q5a)', () => {
  it('an adult checks a child in remotely on any household profile, through the picker', async () => {
    signIn('adult', { profileIds: [3] })
    mocked(api.sharedDevices.list).mockResolvedValue([checkedOutIpad])
    mocked(api.profiles.list).mockResolvedValue([kids, teens, adults])
    renderCard()
    await userEvent.click(await screen.findByTestId(`shared-device-checkin-${IPAD}`))
    const picker = screen.getByTestId(`shared-device-picker-${IPAD}`)
    const options = within(picker).getAllByRole('option').map(o => o.textContent)
    expect(options).toEqual(expect.arrayContaining(['Kids', 'Teens', 'Adults']))
    await userEvent.selectOptions(within(picker).getByRole('combobox'), '1')
    await userEvent.click(within(picker).getByRole('button', { name: 'Check in' }))
    await waitFor(() => expect(api.sharedDevices.checkIn).toHaveBeenCalledWith(IPAD, { profileId: 1 }))
  })

  it("an adult can force a check-out of someone else's check-in", async () => {
    signIn('admin', { profileIds: [3] })
    mocked(api.sharedDevices.list).mockResolvedValue([ipadHeldByTeens])
    mocked(api.profiles.list).mockResolvedValue([kids, teens, adults])
    renderCard()
    const btn = await screen.findByTestId(`shared-device-checkout-${IPAD}`)
    expect(btn).toHaveTextContent('Force check out')
    await userEvent.click(btn)
    await waitFor(() => expect(api.sharedDevices.checkOut).toHaveBeenCalledWith(IPAD))
  })

  it('an adult holding the device on their own profile sees a plain Check out', async () => {
    signIn('adult', { profileIds: [2] })
    mocked(api.sharedDevices.list).mockResolvedValue([ipadHeldByTeens])
    mocked(api.profiles.list).mockResolvedValue([kids, teens])
    renderCard()
    expect(await screen.findByTestId(`shared-device-checkout-${IPAD}`)).toHaveTextContent(/^Check out$/)
  })
})

describe('SharedDevicesCard — block-page hand-off (#2850)', () => {
  it('marks the device the block page came from', async () => {
    signIn('child', { profileIds: [1] })
    mocked(api.sharedDevices.list).mockResolvedValue([
      checkedOutIpad,
      { mac: 'aa:bb:cc:dd:ee:02', name: 'Den PC', holder: null },
    ])
    mocked(api.profiles.list).mockResolvedValue([kids])
    renderCard({ path: `/dashboard?checkin=${encodeURIComponent(IPAD)}` })
    const row = await screen.findByTestId(`shared-device-${IPAD}`)
    expect(within(row).getByText('This device')).toBeInTheDocument()
    expect(within(screen.getByTestId('shared-device-aa:bb:cc:dd:ee:02')).queryByText('This device')).not.toBeInTheDocument()
  })
})

describe('SharedDevicesCard — live updates (#2850)', () => {
  it('subscribes to sharedDevices and applies a push on top of the GET seed', async () => {
    signIn('child', { profileIds: [1] })
    mocked(api.sharedDevices.list).mockResolvedValue([checkedOutIpad])
    mocked(api.profiles.list).mockResolvedValue([kids])
    const client = new SpaWsClient({
      apiBaseUrl: 'https://api.x',
      origin: 'https://app.x',
      getToken: () => 'jwt',
      socketFactory: url => new MockSocket(url),
      setCookie: () => {},
      clearCookie: () => {},
      invalidateQuery: () => {},
      heartbeatMs: 100_000_000,
    })
    renderCard({ client })
    expect(await screen.findByTestId(`shared-device-status-${IPAD}`)).toHaveTextContent('Checked out')

    act(() => client.start())
    act(() => lastSocket().open())
    act(() => lastSocket().emit({ op: 'ready', payload: { role: 'child', serverTime: 't' } }))
    await waitFor(() =>
      expect(lastSocket().frames()).toEqual(
        expect.arrayContaining([
          expect.objectContaining({ op: 'subscribe', payload: expect.objectContaining({ topic: 'sharedDevices' }) }),
        ]),
      ),
    )

    act(() => lastSocket().emit({ op: 'sharedDevices', payload: [ipadHeldByKids] }))
    await waitFor(() => expect(screen.getByTestId(`shared-device-status-${IPAD}`)).toHaveTextContent('Kids'))
    expect(screen.getByTestId(`shared-device-status-${IPAD}`)).not.toHaveTextContent('Checked out')
    client.stop()
  })
})
