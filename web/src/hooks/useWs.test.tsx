// #1973 (SPA-ws S5): the React hook layer (design §3.1) — proves each push patches the
// SAME React Query key the matching GET populates, that the live indicator flips on
// ready, and that polling pauses while the socket is live. Driven by the real
// SpaWsClient over a mock socket so the wire→cache path is exercised end to end.
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { type ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

vi.mock('@/api/client', () => ({
  api: {
    dashboard: { now: vi.fn().mockResolvedValue({ asOf: 'x', profiles: [] }) },
    profiles: { list: vi.fn().mockResolvedValue([]) },
    devices: { list: vi.fn().mockResolvedValue([]) },
    // #2017: the dashboard gauge seeds its series with one GET on mount / bucket change.
    usage: {
      traffic: vi.fn().mockResolvedValue({
        bucket: '1m', groupBy: ['profile'], from: 'a', to: 'b', tz: 'UTC', rawRows: [], aggregateRows: [],
      }),
    },
  },
}))

import { SpaWsClient, type WsSocketLike } from '@/api/wsClient'
import {
  WsProvider,
  useWsLive,
  useWsTopicLive,
  useWsNow,
  useWsRecentBlocked,
  useWsTrafficUsage,
  useWsTrafficUsageLiveEdge,
  useWsConnectionEventsLiveEdge,
  useWsStale,
} from './useWs'
import { useDashboardNow, useProfiles, useDevices, qk } from '@/api/queries'
import { AuthProvider } from '@/hooks/useAuth'
import type { DashboardNow, QueryLog, TrafficUsageAggregateRow, TrafficUsageResponse } from '@/types/api'

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
  it('merges the live-edge bucket into the series cache and subscribes with the bucket', async () => {
    const { qc, client, wrapper } = setup()
    const { result } = renderHook(() => useWsTrafficUsage('1m'), { wrapper })
    goLive(client)
    // subscribed with groupBy:profile + bucket
    const sub = last().frames().find(f => f.op === 'subscribe' && f.payload.topic === 'trafficUsage')
    expect(sub.payload.params).toEqual({ groupBy: ['profile'], bucket: '1m' })
    // server accepts the subscription → topic streams (drives the `live` flag)
    act(() => last().emit({ op: 'ack', payload: { topic: 'trafficUsage', status: 'ok' } }))

    const push: TrafficUsageResponse = {
      bucket: '1m', groupBy: ['profile'], from: 'a', to: 'b', tz: 'UTC', rawRows: [],
      aggregateRows: [
        { groups: { profile: 'Kids' }, windowStart: '2026-06-26T10:05:00Z', windowEnd: '2026-06-26T10:06:00Z', totalBytesIn: 600, totalBytesOut: 0, totalSeconds: 60 },
      ],
    }
    act(() => last().emit({ op: 'trafficUsage', payload: push }))
    const key = qk.trafficUsageLive({ groupBy: ['profile'], bucket: '1m' })
    expect(qc.getQueryData<TrafficUsageResponse>(key)?.aggregateRows).toHaveLength(1)
    // hook surfaces the derived overall rate (600 bytes / 60s = 10 B/s). #2017: the gauge now
    // reads via a `useQuery` observer (GET-seeded), which re-renders one tick after the push's
    // setQueryData (vs the old synchronous cache read) — so await the derived value.
    expect(result.current.live).toBe(true)
    await waitFor(() => expect(result.current.overall.bytesInPerSec).toBe(10))
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

// ── #2017: the dashboard gauge seeds via a GET so it shows data instantly ─────────────
//
// Regression: S5 (#1973) shipped the ws-only series with NO initial GET, so the gauge was
// blank ("No live traffic right now") for up to ~70s after each load / bucket switch —
// until the first usage-ingest push. The design (§3.1/§5.3) loads the window via the GET
// and lets the socket push only the live edge. These prove the one-shot seed.
describe('useWsTrafficUsage seeds via GET (#2017)', () => {
  // newest-first head-bucket response (the GET orders windowStart DESC).
  const seedResp = (
    bucket: TrafficUsageResponse['bucket'],
    rows: { profile: string; windowStart: string; bytesIn?: number; bytesOut?: number }[],
  ): TrafficUsageResponse => ({
    bucket, groupBy: ['profile'], from: 'a', to: 'b', tz: 'UTC', rawRows: [],
    aggregateRows: rows.map(r => ({
      groups: { profile: r.profile },
      windowStart: r.windowStart,
      windowEnd: '2026-06-26T10:06:00Z',
      totalBytesIn: r.bytesIn ?? 0,
      totalBytesOut: r.bytesOut ?? 0,
      totalSeconds: 60,
    })),
  })

  it('shows the seeded head-bucket rate immediately, before any trafficUsage push', async () => {
    const { api } = await import('@/api/client')
    const trafficSpy = api.usage.traffic as unknown as ReturnType<typeof vi.fn>
    trafficSpy.mockClear()
    trafficSpy.mockResolvedValue(seedResp('1m', [{ profile: 'Kids', windowStart: '2026-06-26T10:05:00Z', bytesIn: 600 }]))
    const { client, wrapper } = setup()
    const { result } = renderHook(() => useWsTrafficUsage('1m'), { wrapper })
    goLive(client)
    act(() => last().emit({ op: 'ack', payload: { topic: 'trafficUsage', status: 'ok' } }))

    // The series populates from the GET alone — NO ws push emitted.
    await waitFor(() => expect(result.current.rows).toHaveLength(1))
    expect(result.current.live).toBe(true)
    expect(result.current.overall.bytesInPerSec).toBe(10) // 600 bytes / 60s

    // One GET for the current head window: groupBy:profile, the selected bucket, a from/to span.
    expect(trafficSpy).toHaveBeenCalledTimes(1)
    const arg = trafficSpy.mock.calls[0][0]
    expect(arg.bucket).toBe('1m')
    expect(arg.groupBy).toEqual(['profile'])
    expect(typeof arg.from).toBe('string')
    expect(typeof arg.to).toBe('string')
    expect(new Date(arg.from).getTime()).toBeLessThanOrEqual(new Date(arg.to).getTime())
  })

  it('merges a later ws push on top of the GET seed (mergeHeadBucket)', async () => {
    const { api } = await import('@/api/client')
    const trafficSpy = api.usage.traffic as unknown as ReturnType<typeof vi.fn>
    trafficSpy.mockClear()
    trafficSpy.mockResolvedValue(seedResp('1m', [{ profile: 'Kids', windowStart: '2026-06-26T10:05:00Z', bytesIn: 600 }]))
    const { client, wrapper } = setup()
    const { result } = renderHook(() => useWsTrafficUsage('1m'), { wrapper })
    goLive(client)
    act(() => last().emit({ op: 'ack', payload: { topic: 'trafficUsage', status: 'ok' } }))
    await waitFor(() => expect(result.current.rows).toHaveLength(1))
    expect(result.current.overall.bytesInPerSec).toBe(10) // seed: 600 / 60s

    // A push for the SAME head window with higher cumulative bytes replaces the head in place.
    act(() => last().emit({ op: 'trafficUsage', payload: seedResp('1m', [{ profile: 'Kids', windowStart: '2026-06-26T10:05:00Z', bytesIn: 1200 }]) }))
    await waitFor(() => expect(result.current.overall.bytesInPerSec).toBe(20)) // 1200 / 60s — push merged on the seed
    expect(result.current.rows).toHaveLength(1)
  })

  it('re-seeds via a fresh GET when the bucket changes', async () => {
    const { api } = await import('@/api/client')
    const trafficSpy = api.usage.traffic as unknown as ReturnType<typeof vi.fn>
    trafficSpy.mockClear()
    trafficSpy.mockImplementation((p: { bucket: TrafficUsageResponse['bucket'] }) =>
      Promise.resolve(seedResp(p.bucket, [{ profile: 'Kids', windowStart: '2026-06-26T10:05:00Z', bytesIn: 600 }])))
    const { client, wrapper } = setup()
    const { result } = renderHook(() => useWsTrafficUsage('1m'), { wrapper })
    goLive(client)
    await waitFor(() => expect(trafficSpy).toHaveBeenCalledTimes(1))
    expect(trafficSpy.mock.calls[0][0].bucket).toBe('1m')

    act(() => result.current.setBucket('10m'))
    await waitFor(() => expect(trafficSpy).toHaveBeenCalledTimes(2))
    expect(trafficSpy.mock.calls[1][0].bucket).toBe('10m')

    // Returning to a previously-selected bucket re-seeds too (gcTime:0 drops the switched-away
    // series, so the gauge never shows a stale head window from minutes ago — review SHOULD-FIX).
    act(() => result.current.setBucket('1m'))
    await waitFor(() => expect(trafficSpy).toHaveBeenCalledTimes(3))
    expect(trafficSpy.mock.calls[2][0].bucket).toBe('1m')
  })
})

// ── #2040: the gauge shows the most-recent COMPLETE bucket + renders aggregated raw ───────────────
//
// Old behavior requested `[floor(now), now)` and read the NEWEST window: empty for 1m/raw (data
// lands in the prior period), partial/understated for 10m+ (in-progress cell). These prove the
// gauge now seeds a wider window and renders the most-recent COMPLETE bucket at full width.
describe('useWsTrafficUsage shows the most-recent COMPLETE bucket (#2040)', () => {
  const FUTURE = '2099-01-01T00:00:00Z' // an in-progress bucket: windowEnd not yet elapsed
  const aggRow = (
    profile: string,
    windowStart: string,
    windowEnd: string,
    bytesIn = 0,
    bytesOut = 0,
  ): TrafficUsageAggregateRow => ({
    groups: { profile }, windowStart, windowEnd,
    totalBytesIn: bytesIn, totalBytesOut: bytesOut, totalSeconds: 60,
  })
  const resp = (
    bucket: TrafficUsageResponse['bucket'],
    rows: TrafficUsageAggregateRow[],
  ): TrafficUsageResponse => ({
    bucket, groupBy: ['profile'], from: 'a', to: 'b', tz: 'UTC', rawRows: [], aggregateRows: rows,
  })

  it('skips the in-progress newest bucket and renders the prior COMPLETE one', async () => {
    const { api } = await import('@/api/client')
    const trafficSpy = api.usage.traffic as unknown as ReturnType<typeof vi.fn>
    trafficSpy.mockClear()
    // Newest window is still in progress (windowEnd in the future); the prior 1m bucket is complete.
    trafficSpy.mockResolvedValue(resp('1m', [
      aggRow('Kids', '2026-06-26T10:06:00Z', FUTURE, 99999),
      aggRow('Kids', '2026-06-26T10:05:00Z', '2026-06-26T10:06:00Z', 600),
    ]))
    const { client, wrapper } = setup()
    const { result } = renderHook(() => useWsTrafficUsage('1m'), { wrapper })
    goLive(client)
    act(() => last().emit({ op: 'ack', payload: { topic: 'trafficUsage', status: 'ok' } }))

    await waitFor(() => expect(result.current.rows).toHaveLength(1))
    expect(result.current.rows[0].windowStart).toBe('2026-06-26T10:05:00Z')
    expect(result.current.overall.bytesInPerSec).toBe(10) // 600 / 60s — the complete bucket, not 99999
  })

  it('seeds a window wide enough (>= 2 bucket widths) to contain the complete bucket', async () => {
    const { api } = await import('@/api/client')
    const trafficSpy = api.usage.traffic as unknown as ReturnType<typeof vi.fn>
    trafficSpy.mockClear()
    trafficSpy.mockResolvedValue(resp('10m', []))
    const { client, wrapper } = setup()
    renderHook(() => useWsTrafficUsage('10m'), { wrapper })
    goLive(client)
    await waitFor(() => expect(trafficSpy).toHaveBeenCalledTimes(1))
    const { from, to } = trafficSpy.mock.calls[0][0]
    // 10m bucket → lookback = max(2×600s, 10min) = 20 min.
    expect(new Date(to).getTime() - new Date(from).getTime()).toBeGreaterThanOrEqual(20 * 60 * 1000)
  })

  it('derives the 10m rate over the FULL bucket width (bytes / 600), not elapsed time', async () => {
    const { api } = await import('@/api/client')
    const trafficSpy = api.usage.traffic as unknown as ReturnType<typeof vi.fn>
    trafficSpy.mockClear()
    // A complete 10m bucket carrying 6000 bytes in → 6000 / 600s = 10 B/s.
    trafficSpy.mockResolvedValue(resp('10m', [
      aggRow('Kids', '2026-06-26T10:00:00Z', '2026-06-26T10:10:00Z', 6000),
    ]))
    const { client, wrapper } = setup()
    const { result } = renderHook(() => useWsTrafficUsage('10m'), { wrapper })
    goLive(client)
    act(() => last().emit({ op: 'ack', payload: { topic: 'trafficUsage', status: 'ok' } }))
    await waitFor(() => expect(result.current.rows).toHaveLength(1))
    expect(result.current.overall.bytesInPerSec).toBe(10)
  })

  it('renders raw from aggregated rows over the row real period span', async () => {
    const { api } = await import('@/api/client')
    const trafficSpy = api.usage.traffic as unknown as ReturnType<typeof vi.fn>
    trafficSpy.mockClear()
    // raw aggregate: a 60s ingest period carrying 600 bytes in → 600 / 60s = 10 B/s.
    trafficSpy.mockResolvedValue(resp('raw', [
      aggRow('Kids', '2026-06-26T10:05:00Z', '2026-06-26T10:06:00Z', 600),
    ]))
    const { client, wrapper } = setup()
    const { result } = renderHook(() => useWsTrafficUsage('raw'), { wrapper })
    goLive(client)
    act(() => last().emit({ op: 'ack', payload: { topic: 'trafficUsage', status: 'ok' } }))
    await waitFor(() => expect(result.current.rows).toHaveLength(1))
    expect(result.current.overall.bytesInPerSec).toBe(10)
  })
})

// ── #2041: loading state, not a 0 flash ───────────────────────────────────────────────────────────
describe('useWsTrafficUsage loading state (#2041)', () => {
  it('is loading until the seed resolves, then not loading once data is present', async () => {
    const { api } = await import('@/api/client')
    const trafficSpy = api.usage.traffic as unknown as ReturnType<typeof vi.fn>
    trafficSpy.mockClear()
    let resolveSeed: (r: TrafficUsageResponse) => void = () => {}
    trafficSpy.mockImplementation(() => new Promise<TrafficUsageResponse>(res => { resolveSeed = res }))
    const { client, wrapper } = setup()
    const { result } = renderHook(() => useWsTrafficUsage('1m'), { wrapper })
    goLive(client)

    // Seed in flight, no push yet → loading (the gauge shows a skeleton, never "0 B/s").
    await waitFor(() => expect(result.current.loading).toBe(true))
    expect(result.current.rows).toHaveLength(0)

    // Seed resolves with no recent traffic → no longer loading; rows empty = genuinely idle.
    act(() => resolveSeed({
      bucket: '1m', groupBy: ['profile'], from: 'a', to: 'b', tz: 'UTC', rawRows: [], aggregateRows: [],
    }))
    await waitFor(() => expect(result.current.loading).toBe(false))
    expect(result.current.rows).toHaveLength(0)
  })
})

describe('polling pauses only while the topic streams (§3.3)', () => {
  it('pauses the poll once the `now` subscription is acked, not on bare socket liveness', async () => {
    vi.useFakeTimers()
    const { api } = await import('@/api/client')
    const nowSpy = api.dashboard.now as unknown as ReturnType<typeof vi.fn>
    const { client, wrapper } = setup()
    // mirrors NowSection: subscribe `now` + gate the poll on the topic actually streaming
    const { result } = renderHook(
      () => {
        const streaming = useWsTopicLive('now')
        useWsNow()
        useDashboardNow({ refetchInterval: streaming ? false : 5_000 })
        return streaming
      },
      { wrapper },
    )
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    expect(nowSpy).toHaveBeenCalledTimes(1) // initial fetch
    // socket live but not yet acked → still polling
    act(() => { client.start(); last().open(); last().emit({ op: 'ready', payload: {} }) })
    expect(result.current).toBe(false)
    await act(async () => { await vi.advanceTimersByTimeAsync(5_000) })
    expect(nowSpy).toHaveBeenCalledTimes(2)
    // server acks the subscription → topic streaming → polling pauses
    act(() => { last().emit({ op: 'ack', payload: { topic: 'now', status: 'ok' } }) })
    expect(result.current).toBe(true)
    await act(async () => { await vi.advanceTimersByTimeAsync(20_000) })
    expect(nowSpy).toHaveBeenCalledTimes(2) // no further polls while streaming
  })

  it('keeps polling when the subscription is REJECTED (role can not see the topic)', async () => {
    vi.useFakeTimers()
    const { api } = await import('@/api/client')
    const nowSpy = api.dashboard.now as unknown as ReturnType<typeof vi.fn>
    const { client, wrapper } = setup()
    const { result } = renderHook(
      () => {
        const streaming = useWsTopicLive('now')
        useWsNow()
        useDashboardNow({ refetchInterval: streaming ? false : 5_000 })
        return streaming
      },
      { wrapper },
    )
    await act(async () => { await vi.advanceTimersByTimeAsync(0) })
    act(() => { client.start(); last().open(); last().emit({ op: 'ready', payload: {} }) })
    // socket live but the server rejects the subscription → must NOT pause the poll
    act(() => { last().emit({ op: 'ack', payload: { topic: 'now', status: 'reject', reason: 'forbidden' } }) })
    expect(result.current).toBe(false)
    const before = nowSpy.mock.calls.length
    await act(async () => { await vi.advanceTimersByTimeAsync(5_000) })
    expect(nowSpy.mock.calls.length).toBe(before + 1) // still polling despite a live socket
  })
})

// ── #1975 (S6b): stream the Traffic Usage + Connection Events PAGES ──────────────────
//
// Same topics as the dashboard, the page's own filters (§1.4 / §0.3 one stream, many
// consumers). The page keeps loading history via its GET; the live edge merges into the
// page's component state (not React Query), so these hooks forward the parsed body to a
// setter the page owns.

const aggRow = (over: Partial<TrafficUsageAggregateRow> & { windowStart: string }): TrafficUsageAggregateRow => ({
  groups: {}, windowEnd: over.windowStart, totalBytesIn: 0, totalBytesOut: 0, totalSeconds: 0, ...over,
})
const trafficPush = (rows: TrafficUsageAggregateRow[]): TrafficUsageResponse => ({
  bucket: '1h', groupBy: ['profile'], from: 'a', to: 'b', tz: 'UTC', rawRows: [], aggregateRows: rows,
})

describe('useWsTrafficUsageLiveEdge (#1975 S6b §3.1)', () => {
  it('subscribes with the page params and merges the head bucket by windowStart, history untouched', () => {
    const { client, wrapper } = setup()
    // paged history loaded by the GET (newest-first); the page owns it in component state.
    let rows: TrafficUsageAggregateRow[] = [
      aggRow({ windowStart: '2026-06-26T10:05:00Z', totalBytesIn: 100 }),
      aggRow({ windowStart: '2026-06-26T10:04:00Z', totalBytesIn: 999 }),
    ]
    const setRows = (fn: (prev: TrafficUsageAggregateRow[]) => TrafficUsageAggregateRow[]) => {
      rows = fn(rows)
    }
    renderHook(
      () => useWsTrafficUsageLiveEdge(
        { groupBy: ['profile'], bucket: '1h', macs: ['aa'], profileIds: [3], until: null }, setRows,
      ),
      { wrapper },
    )
    goLive(client)
    const sub = last().frames().find(f => f.op === 'subscribe' && f.payload.topic === 'trafficUsage')
    expect(sub.payload.params).toEqual({ groupBy: ['profile'], bucket: '1h', macs: ['aa'], profileIds: [3] })

    act(() => last().emit({ op: 'trafficUsage', payload: trafficPush([aggRow({ windowStart: '2026-06-26T10:05:00Z', totalBytesIn: 250 })]) }))
    // head replaced, older paged bucket untouched
    expect(rows.map(r => r.windowStart)).toEqual(['2026-06-26T10:05:00Z', '2026-06-26T10:04:00Z'])
    expect(rows[0].totalBytesIn).toBe(250)
    expect(rows[1].totalBytesIn).toBe(999)
  })

  it('does not subscribe in raw mode (push carries aggregate rows, not per-host rawRows)', () => {
    const { client, wrapper } = setup()
    renderHook(
      () => useWsTrafficUsageLiveEdge({ groupBy: [], bucket: 'raw', macs: [], profileIds: [], until: null }, () => {}),
      { wrapper },
    )
    goLive(client)
    expect(last().frames().find(f => f.op === 'subscribe' && f.payload.topic === 'trafficUsage')).toBeUndefined()
  })

  it('does not subscribe while anchored to a past window (until set — no live edge)', () => {
    const { client, wrapper } = setup()
    renderHook(
      () => useWsTrafficUsageLiveEdge(
        { groupBy: ['profile'], bucket: '1h', macs: [], profileIds: [], until: '2026-01-01T00:00:00Z' }, () => {},
      ),
      { wrapper },
    )
    goLive(client)
    expect(last().frames().find(f => f.op === 'subscribe' && f.payload.topic === 'trafficUsage')).toBeUndefined()
  })

  it('re-subscribes (unsubscribe+subscribe) when the bucket changes', () => {
    const { client, wrapper } = setup()
    const { rerender } = renderHook(
      ({ bucket }: { bucket: '1h' | '10m' }) =>
        useWsTrafficUsageLiveEdge({ groupBy: ['profile'], bucket, macs: [], profileIds: [], until: null }, () => {}),
      { wrapper, initialProps: { bucket: '1h' as '1h' | '10m' } },
    )
    goLive(client)
    act(() => rerender({ bucket: '10m' }))
    const ops = last().frames().map(f => `${f.op}:${f.payload?.topic ?? ''}:${f.payload?.params?.bucket ?? ''}`)
    const i1 = ops.indexOf('subscribe:trafficUsage:1h')
    const iU = ops.indexOf('unsubscribe:trafficUsage:')
    const i2 = ops.indexOf('subscribe:trafficUsage:10m')
    expect(i1).toBeGreaterThanOrEqual(0)
    expect(iU).toBeGreaterThan(i1)
    expect(i2).toBeGreaterThan(iU)
  })
})

describe('useWsConnectionEventsLiveEdge (#1975 S6b §3.1)', () => {
  const row = (id: number): QueryLog => ({
    id, mac: null, deviceName: null, profileId: null, profileName: null,
    host: { type: 'fqdn', value: `h${id}.com` }, qtype: 1, blocked: true, reason: { kind: 'manual' }, location: null,
    ts: '2026-06-26T10:00:00Z',
  })

  it('subscribes with the page filters and prepends + dedups the live head without truncating history', () => {
    const { client, wrapper } = setup()
    // a long paged history loaded by the GET
    let logs: QueryLog[] = Array.from({ length: 30 }, (_, i) => row(30 - i)) // ids 30..1
    const setLogs = (fn: (prev: QueryLog[]) => QueryLog[]) => { logs = fn(logs) }
    renderHook(
      () => useWsConnectionEventsLiveEdge(
        { blocked: true, macs: ['aa'], profileIds: [3], until: null }, setLogs,
      ),
      { wrapper },
    )
    goLive(client)
    const sub = last().frames().find(f => f.op === 'subscribe' && f.payload.topic === 'connectionEvents')
    expect(sub.payload.params).toEqual({ blocked: true, macs: ['aa'], profileIds: [3] })

    // push overlaps the GET boundary (id 30 already present) → dedup
    act(() => last().emit({ op: 'connectionEvents', payload: [row(32), row(31), row(30)] }))
    expect(logs.slice(0, 3).map(r => r.id)).toEqual([32, 31, 30])
    expect(logs).toHaveLength(32) // 30 history + 2 genuinely new, history not truncated
    expect(logs[logs.length - 1].id).toBe(1)
  })

  it('omits absent filters from the subscription params (All status / no device / no profile)', () => {
    const { client, wrapper } = setup()
    renderHook(
      () => useWsConnectionEventsLiveEdge({ blocked: undefined, macs: [], profileIds: [], until: null }, () => {}),
      { wrapper },
    )
    goLive(client)
    const sub = last().frames().find(f => f.op === 'subscribe' && f.payload.topic === 'connectionEvents')
    expect(sub.payload.params).toEqual({})
  })

  it('does not subscribe while anchored to a past window (until set)', () => {
    const { client, wrapper } = setup()
    renderHook(
      () => useWsConnectionEventsLiveEdge({ blocked: true, macs: [], profileIds: [], until: '2026-01-01T00:00:00Z' }, () => {}),
      { wrapper },
    )
    goLive(client)
    expect(last().frames().find(f => f.op === 'subscribe' && f.payload.topic === 'connectionEvents')).toBeUndefined()
  })

  it('re-subscribes when the status filter changes', () => {
    const { client, wrapper } = setup()
    const { rerender } = renderHook(
      ({ blocked }: { blocked: boolean | undefined }) =>
        useWsConnectionEventsLiveEdge({ blocked, macs: [], profileIds: [], until: null }, () => {}),
      { wrapper, initialProps: { blocked: true as boolean | undefined } },
    )
    goLive(client)
    act(() => rerender({ blocked: undefined }))
    const ops = last().frames()
      .filter(f => (f.op === 'subscribe' || f.op === 'unsubscribe') && f.payload?.topic === 'connectionEvents')
      .map(f => `${f.op}:${JSON.stringify(f.payload?.params ?? null)}`)
    expect(ops).toEqual([
      'subscribe:{"blocked":true}',
      'unsubscribe:null',
      'subscribe:{}',
    ])
  })
})

describe('useWsStale (#1975 S6b §3.2)', () => {
  it('invalidates only the mapped, mounted query on stale{topic}', async () => {
    const { api } = await import('@/api/client')
    const profilesSpy = api.profiles.list as unknown as ReturnType<typeof vi.fn>
    const devicesSpy = api.devices.list as unknown as ReturnType<typeof vi.fn>
    profilesSpy.mockClear(); devicesSpy.mockClear()
    const { client, wrapper } = setup()
    renderHook(() => { useWsStale(); useProfiles(); useDevices() }, { wrapper })
    await waitFor(() => expect(profilesSpy).toHaveBeenCalledTimes(1))
    await waitFor(() => expect(devicesSpy).toHaveBeenCalledTimes(1))
    goLive(client)
    // a profiles mutation in ANOTHER tab → stale{profiles}: only the profiles query refetches
    act(() => last().emit({ op: 'stale', payload: { topic: 'profiles' } }))
    await waitFor(() => expect(profilesSpy).toHaveBeenCalledTimes(2))
    expect(devicesSpy).toHaveBeenCalledTimes(1) // devices query left alone
  })

  it('ignores an unknown stale topic', async () => {
    const { api } = await import('@/api/client')
    const profilesSpy = api.profiles.list as unknown as ReturnType<typeof vi.fn>
    profilesSpy.mockClear()
    const { client, wrapper } = setup()
    renderHook(() => { useWsStale(); useProfiles() }, { wrapper })
    await waitFor(() => expect(profilesSpy).toHaveBeenCalledTimes(1))
    goLive(client)
    act(() => last().emit({ op: 'stale', payload: { topic: 'somethingElse' } }))
    await new Promise(r => setTimeout(r, 20))
    expect(profilesSpy).toHaveBeenCalledTimes(1) // no refetch
  })
})
