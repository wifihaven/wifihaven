// #1973 (SPA-ws S5, design docs/design/spa-websocket.md §3): the React layer over the
// transport client (wsClient.ts) — the provider that owns one SpaWsClient per tab, the
// `useWsLive()` liveness hook, the generic subscription effect, and the dashboard's
// three live-surface hooks (now / connectionEvents / trafficUsage). The cache-patching
// lives here (the client stays framework-agnostic): each push handler patches the SAME
// React Query key the matching GET populates (§3.1), so the dashboard and the source
// page share one cache entry and a reconnect refetch reconciles against the GET.

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
  useSyncExternalStore,
  type ReactNode,
} from 'react'
import { useQueryClient, type QueryClient } from '@tanstack/react-query'
import { qk, RECENT_BLOCKED_LIMIT } from '@/api/queries'
import { useAuth } from '@/hooks/useAuth'
import { SpaWsClient, type SpaTopicName, type WsStatus } from '@/api/wsClient'
import {
  headBucketRows,
  mergeHeadBucket,
  overallRate,
  prependHead,
  rateFor,
  type BandwidthRate,
} from '@/api/wsCache'
import type {
  DashboardNow,
  QueryLog,
  TrafficUsageAggregateRow,
  TrafficUsageBucket,
  TrafficUsageGroupBy,
  TrafficUsageResponse,
} from '@/types/api'

const VITE_API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''

const WsContext = createContext<SpaWsClient | null>(null)

// A SPA with no provider mounted (or rendered outside it — e.g. a unit test) degrades to
// "offline": no subscriptions, polling stays the source of truth (§6.5 failure
// independence). These module-level stubs keep `useWsLive` hook-safe without a provider.
const offlineSubscribe = (): (() => void) => () => {}
const getOffline = (): WsStatus => 'offline'

/**
 * Owns one `SpaWsClient` for the tab (§6.4 — socket per tab). Starts the socket when
 * authenticated, stops it on logout/unmount. The client is created once (a ref) so its
 * connection survives re-renders; `start()` is idempotent.
 */
export function WsProvider({ children, client }: { children: ReactNode; client?: SpaWsClient }) {
  const qc = useQueryClient()
  const { token, logout } = useAuth()
  const clientRef = useRef<SpaWsClient | null>(client ?? null)
  if (!clientRef.current) {
    clientRef.current = new SpaWsClient({
      apiBaseUrl: VITE_API_BASE_URL,
      getToken: () => localStorage.getItem('token'),
      // Refetch a live query once on reconnect (§6.1) — the streams then resume on push.
      invalidateQuery: key => qc.invalidateQueries({ queryKey: key as readonly unknown[] }),
      // On `4401 token-expired` (§4.3): clear auth so the next REST call's 401 drives the
      // existing /login redirect (client.ts). v1 has no silent refresh.
      onTokenExpired: () => logout(),
    })
  }

  useEffect(() => {
    // A test-injected client owns its own lifecycle — the test drives start/stop.
    if (client) return
    const c = clientRef.current!
    if (token) c.start()
    else c.stop()
    return () => c.stop()
  }, [token, client])

  return <WsContext.Provider value={clientRef.current}>{children}</WsContext.Provider>
}

/** The `'live' | 'reconnecting' | 'offline'` indicator (§6.2). Provider-optional. */
export function useWsLive(): WsStatus {
  const client = useContext(WsContext)
  return useSyncExternalStore(
    client ? client.subscribeStatus : offlineSubscribe,
    client ? client.getStatus : getOffline,
  )
}

/**
 * Whether `topic` is actually STREAMING — the socket is live AND the server accepted this
 * connection's subscription (`ack:ok`). This is what a poll-pause gates on (§3.3), NOT bare
 * `useWsLive()`: a role-rejected topic (e.g. a Child subscribing `now`/`connectionEvents`,
 * `SpaTopic.visibleTo`) is live-socket but never pushed, so its query must keep polling.
 */
export function useWsTopicLive(topic: SpaTopicName): boolean {
  const client = useContext(WsContext)
  const subscribe = client ? client.subscribeStatus : offlineSubscribe
  const getSnapshot = useCallback(
    () => (client ? client.topicActive(topic) : false),
    [client, topic],
  )
  return useSyncExternalStore(subscribe, getSnapshot)
}

/**
 * Subscribe to a topic for the lifetime of the calling component (§1.4 — subscription
 * lifecycle = mounted UI). Re-subscribes when `params` change (the effect cleanup sends
 * `unsubscribe`, the re-run sends `subscribe` — the wire sees unsubscribe-then-subscribe,
 * which the server treats as a replace). A no-op without a provider.
 */
export function useWsSubscription(
  topic: SpaTopicName,
  params: unknown,
  onPush: (payload: unknown) => void,
  refetchKey?: unknown,
  enabled = true,
): void {
  const client = useContext(WsContext)
  const onPushRef = useRef(onPush)
  onPushRef.current = onPush
  const paramsKey = JSON.stringify(params ?? null)
  const refetchKeyJson = JSON.stringify(refetchKey ?? null)

  useEffect(() => {
    if (!client || !enabled) return
    const dispose = client.subscribe(topic, params, {
      onPush: p => onPushRef.current(p),
      refetchKey,
    })
    return dispose
    // Deliberately keyed on the SERIALIZED params/refetchKey (paramsKey/refetchKeyJson),
    // not their object identities — an identity-fresh-but-equal object must not churn the
    // subscription. The latest `params`/`refetchKey` values are captured in the closure.
  }, [client, topic, paramsKey, refetchKeyJson, enabled])
}

/**
 * `now` (§3.1, class 2): the server pushes the whole `DashboardNow` body; we replace the
 * dashboard-now cache so the existing `useDashboardNow` consumers (and the derived
 * Online/Blocked KPIs) update sub-second. Refetched once on reconnect.
 */
export function useWsNow(): void {
  const qc = useQueryClient()
  useWsSubscription(
    'now',
    undefined,
    payload => qc.setQueryData(qk.dashboardNow(), payload as DashboardNow),
    qk.dashboardNow(),
  )
}

/**
 * `connectionEvents{blocked:true}` (§3.1, class 1): the server pushes the new head rows;
 * we prepend them (bounded, dedup by id) into the dashboard's "Recently Blocked" cache —
 * the same `recentBlocked()` key `useRecentBlocked` reads. Refetched once on reconnect.
 */
export function useWsRecentBlocked(): void {
  const qc = useQueryClient()
  useWsSubscription(
    'connectionEvents',
    { blocked: true },
    payload => {
      const rows = (payload as QueryLog[]) ?? []
      qc.setQueryData(qk.recentBlocked(), (prev: QueryLog[] | undefined) =>
        prependHead(prev, rows, RECENT_BLOCKED_LIMIT),
      )
    },
    qk.recentBlocked(),
  )
}

export interface WsTrafficUsage {
  live: boolean
  bucket: TrafficUsageBucket
  setBucket: (b: TrafficUsageBucket) => void
  /** The head (current) bucket's per-profile rows (one per profile, groupBy:profile). */
  rows: TrafficUsageAggregateRow[]
  /** The household total rate (sum of the per-profile head rows). */
  overall: BandwidthRate
  /** Per-row B/s derivation helper (server stays the source of bytes; §1.3). */
  rate: (row: TrafficUsageAggregateRow) => BandwidthRate
}

/**
 * `trafficUsage{groupBy:profile, bucket}` (§3.1, class 1): the live bandwidth gauge.
 * Subscribes with the chosen window (bucket); re-subscribes when the selector changes
 * (#747). The push carries only the live-edge bucket — we MERGE it into the cached
 * series by `windowStart` (§3.1). The series is ws-only (no poll fallback, §3.3): it
 * lives purely in the React Query cache, written by the push and read passively here, so
 * it shows "—" (empty rows) until the first push lands and while disconnected.
 */
export function useWsTrafficUsage(initialBucket: TrafficUsageBucket = '1m'): WsTrafficUsage {
  const qc = useQueryClient()
  const [bucket, setBucket] = useState<TrafficUsageBucket>(initialBucket)
  const groupBy = useMemo<TrafficUsageGroupBy[]>(() => ['profile'], [])
  const params = useMemo(() => ({ groupBy, bucket }), [groupBy, bucket])
  const key = qk.trafficUsageLive(params)
  // "live" here = trafficUsage actually streaming (acked), not bare socket liveness — so a
  // role that can't see trafficUsage shows "—" rather than a misleading "live" with no data.
  const streaming = useWsTopicLive('trafficUsage')

  useWsSubscription(
    'trafficUsage',
    params,
    payload => {
      const body = payload as TrafficUsageResponse
      qc.setQueryData(key, (prev: TrafficUsageResponse | undefined) => mergeHeadBucket(prev, body))
    },
    key,
  )

  // Passive cache reader: never fetches (§3.3 — no poll fallback for the live rate), just
  // re-renders when the push writes the series cache. A disabled `useQuery` observer does
  // NOT get cache-update notifications, so subscribe to the cache directly.
  const data = useCachedQueryData<TrafficUsageResponse>(qc, key)
  const rows = headBucketRows(data)
  return {
    live: streaming,
    bucket,
    setBucket,
    rows,
    overall: overallRate(rows, bucket),
    rate: (row: TrafficUsageAggregateRow) => rateFor(row, bucket),
  }
}

/**
 * Subscribe to a single React Query key's cached data WITHOUT fetching — re-renders when
 * a `setQueryData` writes it (a disabled `useQuery` observer doesn't receive those). Used
 * for the ws-only trafficUsage series, which has no GET fallback (§3.3).
 */
function useCachedQueryData<T>(qc: QueryClient, key: readonly unknown[]): T | undefined {
  const keyHash = JSON.stringify(key)
  const subscribe = useCallback((cb: () => void) => qc.getQueryCache().subscribe(cb), [qc])
  // Re-read when the key changes (keyHash), not on every render. `key` is intentionally
  // not a dep — keyHash is its serialized identity.
  const getSnapshot = useCallback(() => qc.getQueryData<T>(key), [qc, keyHash])
  return useSyncExternalStore(subscribe, getSnapshot)
}
