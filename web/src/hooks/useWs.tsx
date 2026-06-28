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
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { api } from '@/api/client'
import { qk, RECENT_BLOCKED_LIMIT, useInvalidators } from '@/api/queries'
import { useAuth } from '@/hooks/useAuth'
import { SpaWsClient, type SpaTopicName, type WsStatus } from '@/api/wsClient'
import {
  bucketSeconds,
  headBucketRows,
  mergeAggregateHeadRows,
  mergeHeadBucket,
  overallRate,
  prependHead,
  projectTimeStatusToSummary,
  rateFor,
  type BandwidthRate,
} from '@/api/wsCache'
import type {
  DashboardNow,
  ProfileTimeStatus,
  ProfileUsageByApp,
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

/**
 * `timeStatus` (§3.1, class 2): the server pushes the whole `/api/time/status`
 * `ProfileTimeStatus[]` body whenever a profile's used/remaining minutes move (usage credit /
 * #1849 boundary tick / +Time grant). We patch every cache the live screen-time surface reads off
 * the ONE pushed body (SSOT — no recompute):
 *   - `timeStatusToday()` (the full list, design §3.1),
 *   - `timeStatusSummaryToday()` (the lighter /profiles collapsed shape, projected — so its
 *     adaptive ladder can pause while the push is live, §3.3, without the bars freezing),
 *   - `timeStatusProfileToday(pid)` per row (the expanded per-profile detail).
 * Refetched once on reconnect via the summary key (the surface the page actively renders).
 */
export function useWsTimeStatus(): void {
  const qc = useQueryClient()
  useWsSubscription(
    'timeStatus',
    undefined,
    payload => {
      const rows = (payload as ProfileTimeStatus[]) ?? []
      qc.setQueryData(qk.timeStatusToday(), rows)
      qc.setQueryData(qk.timeStatusSummaryToday(), projectTimeStatusToSummary(rows))
      for (const r of rows) qc.setQueryData(qk.timeStatusProfileToday(r.profileId), r)
    },
    qk.timeStatusSummaryToday(),
  )
}

/**
 * `appUsage{profileId}` (§3.1, class 2): subscribed while a profile card is expanded
 * (subscription = mounted UI, §1.4). The server pushes the per-app `/api/profiles/{id}/usage-by-app`
 * body for TODAY; we patch ONLY the live today-window key `profileUsageByApp(profileId, from, to)`
 * — past windows are immutable, so the push never touches them (§3.1). `from`/`to` are the card's
 * today window (the same local-today the page's query uses); the server computes its body for the
 * household-local today, which the browser's local-today maps to, so the patch lands on the key the
 * breakdown renders. Refetched once on reconnect.
 *
 * v1 LIMITATION: the transport holds ONE subscription per topic (wsClient.subs), so with several
 * profile cards expanded at once only the most-recently-mounted card's `appUsage` streams (and a
 * collapse can drop a sibling's). It degrades gracefully — each card still shows its initial
 * `useProfileUsageByApp` fetch (no wrong data), and live-updates resume when a single card is open.
 * Multi-subscription-per-topic is the SharedWorker direction (design §6.4/S8).
 */
export function useWsAppUsage(profileId: number, from: string, to: string, enabled = true): void {
  const qc = useQueryClient()
  const key = qk.profileUsageByApp(profileId, from, to)
  useWsSubscription(
    'appUsage',
    { profileId },
    payload => qc.setQueryData(key, payload as ProfileUsageByApp),
    key,
    enabled,
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
 * series by `windowStart` (§3.1).
 *
 * #2017: the series is GET-seeded then ws-kept-live, per the design (§3.1/§5.3 — "the
 * gauge loads its window via the existing GET … the socket pushes only the live edge").
 * On mount and on every bucket change (the cache key changes → a fresh query) we issue
 * ONE `GET /api/usage/traffic` for the current head window, so the gauge shows data
 * instantly instead of blank until the first usage-ingest push (~up to ~70s). The `useQuery`
 * observer writes — and re-renders on — the SAME `qk.trafficUsageLive(key)` the push patches,
 * so the push's `mergeHeadBucket` keeps it live on top of the seed. This is a one-shot seed,
 * NOT a poll (class-1 keeps no poll fallback, §3.3): `staleTime: Infinity` + no
 * `refetchInterval` — it fetches on mount / key change only, and a reconnect invalidates the
 * key (refetchKey) for a single reseed.
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

  // The GET seed (#2017). A real query observer (not a passive cache read) so it fetches on
  // mount / key change AND receives the push's `setQueryData` cache updates on the same key.
  const { data } = useQuery({
    queryKey: key,
    queryFn: async () => {
      const { from, to } = headWindow(bucket)
      const body = await api.usage.traffic({ groupBy, bucket, from, to })
      // A push can write the live edge while the GET is in flight; keep it on top so a
      // late-resolving seed never clobbers fresher live data (the push's head ≥ the GET's
      // for the same window). mergeHeadBucket keeps the GET's history, the push's head.
      const live = qc.getQueryData<TrafficUsageResponse>(key)
      return live ? mergeHeadBucket(body, live) : body
    },
    staleTime: Infinity,
    refetchInterval: false,
  })

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
 * The current head-bucket window `[floor(now, bucket), now]` for the seed GET (#2017).
 * Flooring `from` to the bucket boundary makes the GET return exactly the current (head)
 * window, so `headBucketRows` lands on it and the rate is right. `raw` has no fixed width —
 * we floor to the nominal ~60s edge (`bucketSeconds('raw')`), giving the most-recent slice.
 */
function headWindow(bucket: TrafficUsageBucket): { from: string; to: string } {
  const nowMs = Date.now()
  const secs = bucketSeconds(bucket)
  const fromMs = Math.floor(nowMs / 1000 / secs) * secs * 1000
  return { from: new Date(fromMs).toISOString(), to: new Date(nowMs).toISOString() }
}

// ── #1975 (S6b): stream the live edge of the Traffic Usage + Connection Events PAGES ──
//
// Same topics as the dashboard, the page's own filters (§1.4 / §0.3 — one stream, many
// consumers). Unlike the dashboard, these pages don't hold their data in React Query —
// they page history themselves (initial window + cursor paging, #862) and accumulate it
// in component state. So the live edge merges into a SETTER the page owns, via the pure
// wsCache helpers, rather than into a query cache. The history GET is untouched; the
// socket only keeps the newest slice fresh (§3.1). Neither page polls (no refetchInterval
// to pause, §3.3) — the live edge IS the freshness.

export interface TrafficUsageLiveArgs {
  groupBy: TrafficUsageGroupBy[]
  bucket: TrafficUsageBucket
  macs: string[]
  profileIds: number[]
  /** The right-edge anchor; non-null means a past window is pinned → no live edge. */
  until: string | null
}

/**
 * Subscribe `trafficUsage` with the Traffic Usage page's CURRENT params and merge the
 * pushed head bucket into the page's aggregate-row state by `windowStart` (§3.1). The
 * page passes its own `setAggRows`. Re-subscribes whenever groupBy / bucket / filters
 * change (the params change → `useWsSubscription` sends unsubscribe-then-subscribe).
 *
 * Streaming is gated to the AGGREGATED view anchored at "now": the push carries
 * `aggregateRows` (one point per group per ingest period, design §1.3) — the page's `raw`
 * bucket renders per-host `rawRows`, a different shape the live edge can't merge into; and
 * a pinned `until` window is history, with no live edge to stream. Returns whether the
 * topic is actually streaming (acked) for an optional "Live" indicator.
 */
export function useWsTrafficUsageLiveEdge(
  args: TrafficUsageLiveArgs,
  setRows: (fn: (prev: TrafficUsageAggregateRow[]) => TrafficUsageAggregateRow[]) => void,
): boolean {
  const enabled = args.until == null && args.bucket !== 'raw'
  const params = useMemo(
    () => ({
      groupBy: args.groupBy,
      bucket: args.bucket,
      ...(args.macs.length ? { macs: args.macs } : {}),
      ...(args.profileIds.length ? { profileIds: args.profileIds } : {}),
    }),
    [args.groupBy, args.bucket, args.macs, args.profileIds],
  )
  const setRowsRef = useRef(setRows)
  setRowsRef.current = setRows
  useWsSubscription(
    'trafficUsage',
    params,
    payload => {
      const rows = (payload as TrafficUsageResponse).aggregateRows ?? []
      if (rows.length) setRowsRef.current(prev => mergeAggregateHeadRows(prev, rows))
    },
    undefined,
    enabled,
  )
  const streaming = useWsTopicLive('trafficUsage')
  return streaming && enabled
}

export interface ConnectionEventsLiveArgs {
  /** undefined = no status filter (All); the GET's `blocked` param. */
  blocked: boolean | undefined
  macs: string[]
  profileIds: number[]
  /** Non-null means a past window is pinned → no live edge. */
  until: string | null
}

/**
 * Subscribe `connectionEvents` with the Connection Events page's CURRENT filters and
 * PREPEND the pushed head rows (dedup by id) onto the page's raw-log state (§3.1). The
 * page passes its own `setLogs`. No cap — `prependHead` keeps the paged history intact
 * (the page pages older rows on scroll). Re-subscribes when the filters change. Gated to
 * the live ("now") window; a pinned `until` is history. Returns whether the topic streams.
 */
export function useWsConnectionEventsLiveEdge(
  args: ConnectionEventsLiveArgs,
  setRows: (fn: (prev: QueryLog[]) => QueryLog[]) => void,
): boolean {
  const enabled = args.until == null
  const params = useMemo(
    () => ({
      ...(args.blocked !== undefined ? { blocked: args.blocked } : {}),
      ...(args.macs.length ? { macs: args.macs } : {}),
      ...(args.profileIds.length ? { profileIds: args.profileIds } : {}),
    }),
    [args.blocked, args.macs, args.profileIds],
  )
  const setRowsRef = useRef(setRows)
  setRowsRef.current = setRows
  useWsSubscription(
    'connectionEvents',
    params,
    payload => {
      const rows = (payload as QueryLog[]) ?? []
      if (rows.length) setRowsRef.current(prev => prependHead(prev, rows))
    },
    undefined,
    enabled,
  )
  const streaming = useWsTopicLive('connectionEvents')
  return streaming && enabled
}

// ── #1975 (S6b §3.2): class-(3) `stale` → invalidate the mapped, mounted query ────────
//
// Occasionally-changing resources (alerts / profiles / devices / schedules) stay on
// request/response; when ANOTHER operator/tab mutates one, the server pushes a contentless
// `stale{topic}` nudge and we `invalidateQueries` the mapped key (reusing the existing
// `qk` / `useInvalidators` — the GET stays the sole producer, and per-recipient authz
// comes free). `invalidateQueries` only refetches MOUNTED queries, so a `stale` for a
// closed view costs nothing. The topic→invalidator map below is the ONE place the mapping
// lives. Mounted once globally (WsStaleInvalidator, App.tsx).
export function useWsStale(): void {
  const inv = useInvalidators()
  // `useWsSubscription` captures the latest onPush closure each render, so we read the
  // current `inv` directly and build the (tiny) topic→invalidator map on the rare push.
  useWsSubscription('stale', undefined, payload => {
    const topic = (payload as { topic?: unknown })?.topic
    if (typeof topic !== 'string') return
    // The single topic→key mapping (§3.2). A bounded enum mirroring the server's `stale`
    // topics; an unknown topic is ignored (forward-compat).
    const map: Record<string, () => void> = {
      alerts: () => void inv.alerts(),
      profiles: () => void inv.profiles(),
      devices: () => void inv.devices(),
      schedules: () => void inv.schedules(),
    }
    map[topic]?.()
  })
}

/**
 * Mounts the global `stale` subscription (§3.2). Rendered inside `WsProvider` (App.tsx),
 * alongside `AlertsNotifier`, so class-(3) invalidation is live for the whole session —
 * it only refetches whatever class-(3) view happens to be mounted.
 */
export function WsStaleInvalidator(): null {
  useWsStale()
  return null
}
