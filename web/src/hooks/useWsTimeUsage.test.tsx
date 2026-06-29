// #1974 (SPA-ws S6a): the live time-usage hooks (design §3.1/§3.3). Proves the `timeStatus` push
// patches the time-status caches off the one pushed body, the `appUsage` push patches ONLY the
// live today-window key (past windows immutable), the disconnected fallback poll pauses while the
// `timeStatus` push streams (the adaptive ladder it replaced was retired in #1976/S7), and
// expanding/collapsing a card subscribes/unsubscribes `appUsage`.
// Driven by the real SpaWsClient over a mock socket so the wire→cache path is exercised end to end.
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { type ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

vi.mock('@/api/client', () => ({
  api: {
    time: { summaryAll: vi.fn().mockResolvedValue([]) },
    profiles: { usageByApp: vi.fn().mockResolvedValue({ profileId: 1, profileName: 'Kids', from: 'x', to: 'x', apps: [], orphanHosts: [] }) },
  },
}))

import { SpaWsClient, type WsSocketLike } from '@/api/wsClient'
import { WsProvider, useWsTopicLive, useWsTimeStatus, useWsAppUsage } from './useWs'
import { useTimeStatusSummary, qk, TIME_STATUS_FALLBACK_REFETCH_MS } from '@/api/queries'
import { AuthProvider } from '@/hooks/useAuth'
import type { ProfileTimeStatus, ProfileUsageByApp } from '@/types/api'

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
const last = () => MockSocket.instances[MockSocket.instances.length - 1]

function makeQc(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false, refetchOnWindowFocus: false, refetchOnReconnect: false, staleTime: 60_000, gcTime: Infinity },
    },
  })
}

function setup() {
  const qc = makeQc()
  const client = new SpaWsClient({
    apiBaseUrl: 'https://api.x',
    origin: 'https://app.x',
    getToken: () => 'jwt',
    socketFactory: url => new MockSocket(url),
    setCookie: () => {},
    clearCookie: () => {},
    invalidateQuery: key => qc.invalidateQueries({ queryKey: key as readonly unknown[] }),
    // Huge so the timer advances below never trip the heartbeat's missed-pong reconnect
    // (which would clear acks and break the streaming-state assertion). Heartbeat is not under test.
    heartbeatMs: 100_000_000,
  })
  const wrapper = ({ children }: { children: ReactNode }) => (
    <QueryClientProvider client={qc}>
      <AuthProvider>
        <WsProvider client={client}>{children}</WsProvider>
      </AuthProvider>
    </QueryClientProvider>
  )
  return { qc, client, wrapper }
}

function goLive(client: SpaWsClient) {
  act(() => client.start())
  act(() => last().open())
  act(() => last().emit({ op: 'ready', payload: { role: 'admin', serverTime: 't' } }))
}

const tsRow = (id: number, name: string): ProfileTimeStatus => ({
  profileId: id,
  profileName: name,
  date: '2026-06-26',
  dailyLimitMins: 120,
  usedMins: 30,
  extensionMins: 0,
  remainingMins: 90,
  appUsage: [],
  devices: [],
  hostUsage: [],
})

beforeEach(() => {
  MockSocket.instances = []
  localStorage.clear()
})
afterEach(() => {
  vi.useRealTimers()
  vi.clearAllMocks()
})

describe('useWsTimeStatus (§3.1)', () => {
  it('patches the today + summary + per-profile-today caches off the one pushed body', () => {
    const { qc, client, wrapper } = setup()
    renderHook(() => useWsTimeStatus(), { wrapper })
    goLive(client)
    const rows = [tsRow(1, 'Kids'), tsRow(2, 'Teens')]
    act(() => last().emit({ op: 'timeStatus', payload: rows }))

    expect(qc.getQueryData(qk.timeStatusToday())).toEqual(rows)
    // projected summary subset (no appUsage/devices/hostUsage)
    expect(qc.getQueryData(qk.timeStatusSummaryToday())).toEqual([
      { profileId: 1, profileName: 'Kids', date: '2026-06-26', dailyLimitMins: 120, usedMins: 30, extensionMins: 0, remainingMins: 90 },
      { profileId: 2, profileName: 'Teens', date: '2026-06-26', dailyLimitMins: 120, usedMins: 30, extensionMins: 0, remainingMins: 90 },
    ])
    // per-profile-today caches (the expanded-card detail key)
    expect(qc.getQueryData(qk.timeStatusProfileToday(1))).toEqual(rows[0])
    expect(qc.getQueryData(qk.timeStatusProfileToday(2))).toEqual(rows[1])
  })
})

describe('useWsAppUsage (§3.1)', () => {
  const body = (pid: number, used: number): ProfileUsageByApp => ({
    profileId: pid,
    profileName: 'Kids',
    from: '2026-06-26',
    to: '2026-06-26',
    apps: [{ appId: 5, appName: 'YouTube', appIcon: null, appIconType: null, proportionalSeconds: used, presenceSeconds: used, hosts: [] }],
    orphanHosts: [],
  })

  it('patches ONLY the live today-window key and leaves past-window keys untouched', () => {
    const { qc, client, wrapper } = setup()
    const today = '2026-06-26'
    const past = '2026-06-20'
    // Pre-seed an immutable past window + a stale today entry.
    const pastBody = body(1, 999)
    qc.setQueryData(qk.profileUsageByApp(1, past, past), pastBody)
    qc.setQueryData(qk.profileUsageByApp(1, today, today), body(1, 1))

    renderHook(() => useWsAppUsage(1, today, today, true), { wrapper })
    goLive(client)
    act(() => last().emit({ op: 'ack', payload: { topic: 'appUsage', status: 'ok' } }))
    const push = body(1, 300)
    act(() => last().emit({ op: 'appUsage', payload: push }))

    expect(qc.getQueryData(qk.profileUsageByApp(1, today, today))).toEqual(push)
    // past window is immutable — the push must never touch it
    expect(qc.getQueryData(qk.profileUsageByApp(1, past, past))).toEqual(pastBody)
  })

  it('subscribes appUsage{profileId} on mount and unsubscribes on unmount (= card expand/collapse)', () => {
    const { client, wrapper } = setup()
    const { unmount } = renderHook(() => useWsAppUsage(7, '2026-06-26', '2026-06-26', true), { wrapper })
    goLive(client)
    const sub = last().frames().find(f => f.op === 'subscribe' && f.payload?.topic === 'appUsage')
    expect(sub?.payload?.params).toEqual({ profileId: 7 })
    // collapsing the card unmounts the component → the subscription effect cleanup unsubscribes
    act(() => unmount())
    const unsub = last().frames().find(f => f.op === 'unsubscribe' && f.payload?.topic === 'appUsage')
    expect(unsub).toBeTruthy()
  })

  it('does NOT subscribe while disabled (collapsed card / past window)', () => {
    const { client, wrapper } = setup()
    renderHook(() => useWsAppUsage(7, '2026-06-26', '2026-06-26', false), { wrapper })
    goLive(client)
    const sub = last().frames().find(f => f.op === 'subscribe' && f.payload?.topic === 'appUsage')
    expect(sub).toBeUndefined()
  })
})

describe('disconnected fallback poll pauses while timeStatus streams (§3.3)', () => {
  it('pauses the summary poll once the timeStatus subscription is acked, resumes if never acked', async () => {
    vi.useFakeTimers()
    const { api } = await import('@/api/client')
    const summarySpy = api.time.summaryAll as unknown as ReturnType<typeof vi.fn>
    const { client, wrapper } = setup()
    // mirrors ProfilesPage: subscribe timeStatus + gate the summary ladder on the topic streaming.
    const { result } = renderHook(
      () => {
        const live = useWsTopicLive('timeStatus')
        useWsTimeStatus()
        useTimeStatusSummary({ wsLive: live })
        return live
      },
      { wrapper },
    )
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    expect(summarySpy).toHaveBeenCalledTimes(1) // initial fetch

    // socket live but not yet acked → the flat disconnected fallback still runs (#1976/S7)
    act(() => { client.start(); last().open(); last().emit({ op: 'ready', payload: {} }) })
    expect(result.current).toBe(false)
    await act(async () => { await vi.advanceTimersByTimeAsync(TIME_STATUS_FALLBACK_REFETCH_MS) })
    expect(summarySpy).toHaveBeenCalledTimes(2)

    // server acks the subscription → topic streaming → fallback poll dormant
    act(() => { last().emit({ op: 'ack', payload: { topic: 'timeStatus', status: 'ok' } }) })
    expect(result.current).toBe(true)
    await act(async () => { await vi.advanceTimersByTimeAsync(TIME_STATUS_FALLBACK_REFETCH_MS * 100) })
    expect(summarySpy).toHaveBeenCalledTimes(2) // no further polls while streaming
  })
})
