// #1973 (SPA-ws S5): the React hook layer (design §3.1) — proves each push patches the
// SAME React Query key the matching GET populates, that the live indicator flips on
// ready, and that polling pauses while the socket is live. Driven by the real
// SpaWsClient over a mock socket so the wire→cache path is exercised end to end.
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, act } from '@testing-library/react'
import { type ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

vi.mock('@/api/client', () => ({
  api: { dashboard: { now: vi.fn().mockResolvedValue({ asOf: 'x', profiles: [] }) } },
}))

import { SpaWsClient, type WsSocketLike } from '@/api/wsClient'
import { WsProvider, useWsLive, useWsNow, useWsRecentBlocked, useWsTrafficUsage } from './useWs'
import { useDashboardNow, qk } from '@/api/queries'
import { AuthProvider } from '@/hooks/useAuth'
import type { DashboardNow, QueryLog, TrafficUsageResponse } from '@/types/api'

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
    heartbeatMs: 100000,
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

beforeEach(() => {
  MockSocket.instances = []
  localStorage.clear()
})
afterEach(() => {
  vi.useRealTimers()
})

describe('useWsLive (§6.2)', () => {
  it('flips to live on ready', () => {
    const { client, wrapper } = setup()
    const { result } = renderHook(() => useWsLive(), { wrapper })
    expect(result.current).toBe('offline')
    goLive(client)
    expect(result.current).toBe('live')
  })

  it('is offline with no provider', () => {
    const { result } = renderHook(() => useWsLive())
    expect(result.current).toBe('offline')
  })
})

describe('useWsNow (§3.1)', () => {
  it('replaces the dashboard-now cache on push', () => {
    const { qc, client, wrapper } = setup()
    renderHook(() => useWsNow(), { wrapper })
    goLive(client)
    const body: DashboardNow = {
      asOf: '2026-06-26T10:00:00Z',
      profiles: [{ id: 1, name: 'Kids', paused: false, activeDevices: [{ id: 1, name: 'iPad', mac: 'a', lastSeenSeconds: 5, topHosts: [] }] }],
    }
    act(() => last().emit({ op: 'now', payload: body }))
    expect(qc.getQueryData(qk.dashboardNow())).toEqual(body)
  })
})

describe('useWsRecentBlocked (§3.1)', () => {
  const row = (id: number): QueryLog => ({
    id, mac: null, deviceName: null, profileId: null, profileName: null,
    host: { type: 'fqdn', value: `h${id}.com` }, qtype: 1, blocked: true, reason: { kind: 'manual' }, location: null,
    ts: '2026-06-26T10:00:00Z',
  })

  it('prepends + dedups pushed head rows into the recentBlocked cache', () => {
    const { qc, client, wrapper } = setup()
    qc.setQueryData(qk.recentBlocked(), [row(2), row(1)])
    renderHook(() => useWsRecentBlocked(), { wrapper })
    goLive(client)
    act(() => last().emit({ op: 'connectionEvents', payload: [row(3), row(2)] }))
    const cached = qc.getQueryData<QueryLog[]>(qk.recentBlocked())
    expect(cached?.map(r => r.id)).toEqual([3, 2, 1])
  })
})

describe('useWsTrafficUsage (§3.1/#747)', () => {
  it('merges the live-edge bucket into the series cache and subscribes with the bucket', () => {
    const { qc, client, wrapper } = setup()
    const { result } = renderHook(() => useWsTrafficUsage('1m'), { wrapper })
    goLive(client)
    // subscribed with groupBy:profile + bucket
    const sub = last().frames().find(f => f.op === 'subscribe' && f.payload.topic === 'trafficUsage')
    expect(sub.payload.params).toEqual({ groupBy: ['profile'], bucket: '1m' })

    const push: TrafficUsageResponse = {
      bucket: '1m', groupBy: ['profile'], from: 'a', to: 'b', tz: 'UTC', rawRows: [],
      aggregateRows: [
        { groups: { profile: 'Kids' }, windowStart: '2026-06-26T10:05:00Z', windowEnd: '2026-06-26T10:06:00Z', totalBytesIn: 600, totalBytesOut: 0, totalSeconds: 60 },
      ],
    }
    act(() => last().emit({ op: 'trafficUsage', payload: push }))
    const key = qk.trafficUsageLive({ groupBy: ['profile'], bucket: '1m' })
    expect(qc.getQueryData<TrafficUsageResponse>(key)?.aggregateRows).toHaveLength(1)
    // hook surfaces the derived overall rate (600 bytes / 60s = 10 B/s)
    expect(result.current.live).toBe(true)
    expect(result.current.overall.bytesInPerSec).toBe(10)
  })

  it('re-subscribes (unsubscribe+subscribe) when the bucket selector changes', () => {
    const { client, wrapper } = setup()
    const { result } = renderHook(() => useWsTrafficUsage('1m'), { wrapper })
    goLive(client)
    act(() => result.current.setBucket('10m'))
    const ops = last().frames().map(f => `${f.op}:${f.payload?.topic ?? ''}:${f.payload?.params?.bucket ?? ''}`)
    const i1 = ops.indexOf('subscribe:trafficUsage:1m')
    const iU = ops.indexOf('unsubscribe:trafficUsage:')
    const i2 = ops.indexOf('subscribe:trafficUsage:10m')
    expect(i1).toBeGreaterThanOrEqual(0)
    expect(iU).toBeGreaterThan(i1)
    expect(i2).toBeGreaterThan(iU)
  })
})

describe('polling pauses while live (§3.3)', () => {
  it('gates refetchInterval off when wsLive', async () => {
    vi.useFakeTimers()
    const { api } = await import('@/api/client')
    const nowSpy = api.dashboard.now as unknown as ReturnType<typeof vi.fn>
    const { client, wrapper } = setup()
    // a component that gates the poll on the live indicator, exactly like NowSection
    const { result } = renderHook(
      () => {
        const live = useWsLive() === 'live'
        useDashboardNow({ refetchInterval: live ? false : 5_000 })
        return live
      },
      { wrapper },
    )
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    expect(nowSpy).toHaveBeenCalledTimes(1) // initial fetch
    // not live yet → polls
    await act(async () => { await vi.advanceTimersByTimeAsync(5_000) })
    expect(nowSpy).toHaveBeenCalledTimes(2)
    // go live → polling pauses
    act(() => { client.start(); last().open(); last().emit({ op: 'ready', payload: {} }) })
    expect(result.current).toBe(true)
    await act(async () => { await vi.advanceTimersByTimeAsync(20_000) })
    expect(nowSpy).toHaveBeenCalledTimes(2) // no further polls while live
  })
})
