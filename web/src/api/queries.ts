// #803: hooks that wrap the hot read endpoints in TanStack Query so SPA
// navigations don't refetch within the per-endpoint stale window and a
// slow backend shows last-known-good while the refetch runs.
//
// Mutations live alongside their existing imperative call-sites; this
// module exposes an `invalidate` helper plus the key factory so the
// pages can invalidate the right keys onSuccess.
import { useQuery, useQueryClient, type UseQueryOptions } from '@tanstack/react-query'
import { api } from '@/api/client'
import { qk } from '@/api/queryKeys'
import type {
  Alert, BetaRequestStatus, BetaRequestSummary, BlocklistSummary, DashboardNow, Device, HouseholdSettings, MeResponse, NamedSchedule, ProfileDetail,
  ProfileTimeStatus,
  ProfileAppWeeklyUsage,
  ProfileTimeStatusWeek, ProfileTimeSummary, ProfileTimeSummaryWeek, ProfileUsageByApp,
  QueryLog, RouterSummary, UsageConfig, UsageSeriesResponse,
  SupportIdentityResponse,
  PressMessage,
} from '@/types/api'

const MIN = 60_000

// #1338: "Most recently blocked" dashboard panel — newest-first, blocked-only,
// un-aggregated recent drops. Capped small; the full history lives on the
// Connection Events page.
export const RECENT_BLOCKED_LIMIT = 20
// The DISPLAY window. "Recent" = the trailing 15 minutes: the panel answers "what just
// got dropped", not "what was dropped today" (the 24h aggregate is "Top Blocked").
export const RECENT_BLOCKED_WINDOW_MS = 15 * 60_000
// The server-side FETCH width, in the integer hours /api/logs takes. Wider than the
// display window on purpose: it is what lets the panel say "there were blocks, just not
// in the display window" instead of rendering the same empty state as a household that
// has never been blocked.
export const RECENT_BLOCKED_FETCH_HOURS = 1
// Human-readable forms of the two spans above, so the panel can NAME them. Derived here
// rather than hardcoded in the UI copy, so widening either constant cannot leave the copy
// asserting a span the fetch no longer uses (#2601). `recentBlockedFetchLabel` is a pure
// function rather than an inline ternary so BOTH arms are reachable and unit-testable —
// against the constant the plural arm would otherwise be dead code by construction.
// Both arms are covered in DashboardPage.test.tsx ("recentBlockedFetchLabel covers both
// arms"); if that test goes, this claim stops being falsifiable.
export const RECENT_BLOCKED_WINDOW_LABEL = `last ${RECENT_BLOCKED_WINDOW_MS / 60_000} min`
export function recentBlockedFetchLabel(hours: number): string {
  return hours === 1 ? 'the past hour' : `the past ${hours} hours`
}
export const RECENT_BLOCKED_FETCH_LABEL = recentBlockedFetchLabel(RECENT_BLOCKED_FETCH_HOURS)

// Per-endpoint stale times (#803).
const STALE = {
  dashboardNow: 5_000,
  recentBlocked: 5_000,
  timeStatusToday: 30_000,
  timeStatusPast: 5 * MIN,
  timeStatusWeek: 5 * MIN,
  profiles: 5 * MIN,
  devices: 5 * MIN,
  // #2252 — shorter than profiles/devices so the onboarding banner flips promptly once the
  // router registers, without polling as hot as the live NOW surfaces (5s).
  routers: 30_000,
} as const

// #1871 / #1976 (SPA-ws S7) — the live time-used surfaces must stay fresh so the displayed
// "minutes left" can't lag enforcement (the router blocks within seconds of the cap, but a query
// with only a staleTime never refetches on its own). Freshness is now driven by the S6a
// `timeStatus` push (design §3.1) whenever the socket is live — and the hooks below PAUSE polling
// in that case (`wsLive ? false : …`, §3.3). The adaptive refetch ladder this replaced
// (`TIME_STATUS_REFETCH_LADDER`, keyed on the most-urgent profile's remaining minutes) existed
// ONLY because there was no push to deliver near-cap urgency promptly; with the push proven it is
// retired (#1976/S7).
//
// What remains is the DISCONNECTED fallback: while the push is down (`wsLive === false`) the live
// surfaces poll at this single FLAT cadence so a near-cap "minutes left" can't lag enforcement
// during a socket outage. It is a constant — no longer adaptive — because the push (not a poll) is
// the freshness when connected, so the fallback only has to keep the degraded mode honest.
// Foreground-only (`refetchIntervalInBackground: false`) so hidden tabs don't poll.
//
// This is the ONE source for the live-surface disconnected fallback cadence (§0.1 — NOW,
// Recently-Blocked, time-status): the dashboard NOW / Recently-Blocked hooks (`DashboardPage`) and
// the time-status hooks below all reference it, so every live surface degrades to the same cadence
// by construction rather than by hand-synced literals.
export const LIVE_SURFACE_FALLBACK_REFETCH_MS = 10_000

// #2603: the key factory lives in its own leaf module (api/queryKeys.ts) so a component
// that needs only a KEY doesn't have to import this hooks module. Re-exported here because
// most call sites (and the ws layer) already import `qk` from '@/api/queries'.
export { qk } from '@/api/queryKeys'

type QueryOpts<T> = Omit<UseQueryOptions<T, Error, T, readonly unknown[]>, 'queryKey' | 'queryFn'>

// #2069 — the authenticated caller's own identity: role + the profile ids they
// are linked to. A non-admin (child) must scope every data request to these
// profile ids because the API serves non-admins only when scoped (an unscoped
// `/api/usage/traffic` is a 403 for a child). Cached indefinitely — a session's
// role/linkage doesn't change under it — and only fetched when a caller needs
// it (see `useDataScope`, which enables it for non-admins).
export function useMe(opts?: QueryOpts<MeResponse>) {
  return useQuery({
    queryKey: qk.me(),
    queryFn: () => api.auth.me(),
    staleTime: Infinity,
    ...opts,
  })
}

// #2199 (support intake B): the server-signed Plain widget identity for the authed
// admin. Cached for the session (staleTime: Infinity) — the household + entitlement
// don't change under a live session, and the widget only needs to boot once. Enabled
// by the caller only for admins (the API 403s non-admins). When the widget is
// unconfigured the response is `{configured:false}` and the caller renders nothing.
export function useSupportIdentity(opts?: QueryOpts<SupportIdentityResponse>) {
  return useQuery({
    queryKey: qk.supportIdentity(),
    queryFn: () => api.support.identity(),
    staleTime: Infinity,
    ...opts,
  })
}

// #2133 (multi-tenant P5-3): the operator's beta-request review queue. Only a
// household-1 admin (`isOperator`) can read it — the API 403s everyone else — so
// the caller enables this query off `useMe().data?.isOperator`.
export function useBetaRequests(
  status?: BetaRequestStatus,
  opts?: QueryOpts<BetaRequestSummary[]>,
) {
  return useQuery({
    queryKey: qk.betaRequests(status),
    queryFn: () => api.beta.operatorList(status),
    staleTime: 30_000,
    ...opts,
  })
}

// #2296 (press correspondence log): the operator's read of the recorded press channel. Only a
// household-1 admin can read it — the API 404s any other household and 403s a non-admin — so the
// caller (route/nav) is gated on `useMe().data?.isOperator`, mirroring the beta-request queue.
export function usePressMessages(opts?: QueryOpts<PressMessage[]>) {
  return useQuery({
    queryKey: qk.pressMessages(),
    queryFn: () => api.press.messages(),
    staleTime: 30_000,
    ...opts,
  })
}

export function useProfiles(opts?: QueryOpts<ProfileDetail[]>) {
  return useQuery({
    queryKey: qk.profiles(),
    queryFn: () => api.profiles.list(),
    staleTime: STALE.profiles,
    ...opts,
  })
}

// #1773 — the household-global sentinel profile (#1771). Hidden from
// `GET /api/profiles`, fetched via `/api/profiles/global` so the SPA can edit
// it through the same per-profile editor preset to its id. Server route is
// #2522: `requireWriter` (admin or adult); callers MUST gate the hook with `enabled: isWriter`
// to avoid firing a fetch the server will refuse — a child would otherwise see the query stuck
// in an error state.
export function useGlobalProfile(opts?: QueryOpts<ProfileDetail>) {
  return useQuery({
    queryKey: qk.profilesGlobal(),
    queryFn: () => api.profiles.getGlobal(),
    staleTime: STALE.profiles,
    ...opts,
  })
}

// #1743: bucket → grain mapping the API emits from BucketPolicy. Cached for an
// hour because it's effectively a constant; the SPA falls back to the locally
// shipped defaults when the fetch hasn't completed (or fails) so the
// date-picker isn't blocked by network.
export function useUsageConfig(opts?: QueryOpts<UsageConfig>) {
  return useQuery({
    queryKey: qk.usageConfig(),
    queryFn: () => api.usage.config(),
    staleTime: 60 * MIN,
    // Effectively constant — survive page-mount churn without re-fetching.
    gcTime: Infinity,
    ...opts,
  })
}

export function useHouseholdSettings(opts?: QueryOpts<HouseholdSettings>) {
  return useQuery({
    queryKey: qk.householdSettings(),
    queryFn: () => api.household.get(),
    staleTime: 5 * MIN,
    ...opts,
  })
}

export function useDevices(opts?: QueryOpts<Device[]>) {
  return useQuery({
    queryKey: qk.devices(),
    queryFn: () => api.devices.list(),
    staleTime: STALE.devices,
    ...opts,
  })
}

// #2252 — enrolled routers (GET /api/admin/routers), admin-only. The dashboard
// first-run banner reads this to distinguish three onboarding states: no router
// enrolled yet, an enrollment created but the router hasn't connected, or a
// router that has checked in. The route is admin-only, so only mount callers in
// an admin context (FirstRunHint renders behind `scope.isAdmin`); a non-admin
// caller would otherwise fire a fetch the server refuses and get stuck in error.
export function useRouters(opts?: QueryOpts<RouterSummary[]>) {
  return useQuery({
    queryKey: qk.routers(),
    queryFn: () => api.routers.list(),
    staleTime: STALE.routers,
    ...opts,
  })
}

// #1473 — the blocklist catalog (GET /api/blocklists), shared by the
// Blocklists matrix page and the inline blocked-categories editor on the
// profile card. Cached so N profile cards don't each fire their own fetch.
export function useBlocklists(opts?: QueryOpts<BlocklistSummary[]>) {
  return useQuery({
    queryKey: qk.blocklists(),
    queryFn: () => api.blocklists.list(),
    staleTime: 5 * MIN,
    ...opts,
  })
}

// #1069 — household named schedules (GET /api/schedules), shared by the
// Schedules management page and the reusable SchedulePicker dropdown embedded
// in profile / per-app / blocklist edit forms. Cached so the N pickers on a
// page don't each fetch. Low-churn admin data; pages invalidate on edit.
export function useNamedSchedules(opts?: QueryOpts<NamedSchedule[]>) {
  return useQuery({
    queryKey: qk.schedules(),
    queryFn: () => api.schedules.list(),
    staleTime: 5 * MIN,
    ...opts,
  })
}

// Pending alerts feed (#711 formerly polled /api/device-alerts; refactor reads
// /api/alerts). Refetched on a 30s interval so banners reflect a freshly-raised
// alert without a manual reload. Components filter by `kind` as needed.
export function useAlerts(includeAll = false, opts?: QueryOpts<Alert[]>) {
  return useQuery({
    queryKey: qk.alerts(includeAll),
    queryFn: () => api.alerts.list(includeAll),
    staleTime: 30_000,
    refetchInterval: 30_000,
    refetchIntervalInBackground: false,
    ...opts,
  })
}

export function useDashboardNow(opts?: QueryOpts<DashboardNow>) {
  return useQuery({
    queryKey: qk.dashboardNow(),
    queryFn: () => api.dashboard.now(),
    staleTime: STALE.dashboardNow,
    refetchInterval: 10_000,
    refetchIntervalInBackground: false,
    ...opts,
  })
}

// #1338: live feed of the most recent connection-layer drops (blocked-only,
// newest-first, trailing RECENT_BLOCKED_WINDOW_MS). Reuses the existing /api/logs read
// with blocked=true; the route already orders ts DESC and honours the limit, and
// applies the same JWT/household scoping every other dashboard read uses. We fetch
// RECENT_BLOCKED_FETCH_HOURS (the integer-hours param) and trim to
// RECENT_BLOCKED_WINDOW_MS client-side so a stale block doesn't masquerade as recent.
// Name spans by their constant rather than restating the number: prose is where a span
// literal goes stale unnoticed when one of them widens (#2601). Polls on the same
// 10s cadence as the "now" snapshot so a just-now block surfaces immediately.
// NB a row here is a real traffic-layer drop, not a DNS event (DNS always
// resolves) — see memory/blocking_is_traffic_layer_not_dns.md.
// #2062: an optional `mac` narrows the feed to one device (the "All devices ▾" quick filter),
// threaded into BOTH the cache key and the /api/logs `mac` filter so the fallback poll narrows
// server-side, matching the narrowed live subscription (useWsRecentBlocked).
// #2601: `select` returns the trimmed rows AND how many fetched rows fell OUTSIDE the
// window. Without that count the panel cannot tell "this household has never been
// blocked" from "there were blocks, just older than the window" — and it rendered
// both as the same bare empty state while prod was dropping Google Drive traffic. The
// query CACHE still holds the raw `QueryLog[]` the ws push prepends into (wsCache
// `prependHead`); only this consumer-facing projection changes shape.
export interface RecentBlocked {
  rows: QueryLog[]
  /**
   * Fetched blocked rows older than RECENT_BLOCKED_WINDOW_MS but inside the
   * RECENT_BLOCKED_FETCH_HOURS fetch.
   */
  olderCount: number
  /**
   * The fetch came back at RECENT_BLOCKED_LIMIT, so `olderCount` is a floor, not a
   * total — the household may have many more older blocks than the page carries.
   * Callers must not present `olderCount` as an absolute count when this is true.
   */
  olderCountTruncated: boolean
}

// The fetch returns QueryLog[] and `select` projects it to RecentBlocked, so callers
// tune everything EXCEPT select (which this hook owns).
type RecentBlockedOpts = Omit<
  UseQueryOptions<QueryLog[], Error, RecentBlocked, readonly unknown[]>,
  'queryKey' | 'queryFn' | 'select'
>

export function useRecentBlocked(mac: string | null = null, opts?: RecentBlockedOpts) {
  return useQuery({
    queryKey: qk.recentBlocked(mac),
    queryFn: () =>
      api.logs
        .query({
          blocked: true,
          limit: RECENT_BLOCKED_LIMIT,
          hours: RECENT_BLOCKED_FETCH_HOURS,
          ...(mac ? { macs: [mac] } : {}),
        })
        .then(p => p.rows),
    select: (rows: QueryLog[]): RecentBlocked => {
      const cutoff = Date.now() - RECENT_BLOCKED_WINDOW_MS
      const recent = rows.filter(r => new Date(r.ts).getTime() >= cutoff)
      return {
        rows: recent,
        olderCount: rows.length - recent.length,
        // Reads the CACHE, which useWsRecentBlocked also caps at RECENT_BLOCKED_LIMIT
        // (useWs.tsx -> wsCache.prependHead's slice). So the cache can saturate at the cap
        // from pushed rows even when the original fetch returned fewer, and this flag goes
        // true where a fresh fetch would have left it false. That is WHY the flag exists;
        // what callers owe it is stated once, on its own declaration above — not restated
        // here, where a second copy would drift from the first.
        //
        // Deliberately no claim about when a refetch heals the divergence: whether one runs
        // at all is the caller's choice, so any bound stated here would describe config this
        // hook does not own.
        olderCountTruncated: rows.length >= RECENT_BLOCKED_LIMIT,
      }
    },
    staleTime: STALE.recentBlocked,
    refetchInterval: 10_000,
    refetchIntervalInBackground: false,
    ...opts,
  })
}

// #1974 (S6a) / #1976 (S7, §3.3): when the `timeStatus` push is live (`wsLive`), polling pauses —
// the push keeps the cache fresh. When the socket is down, the flat disconnected fallback
// (`LIVE_SURFACE_FALLBACK_REFETCH_MS`) resumes the instant the socket drops; the adaptive ladder it
// replaced was retired in S7 (the push delivers near-cap urgency when live). The caller passes
// `wsLive` from `useWsTopicLive('timeStatus')`.
export function useTimeStatusToday(opts?: QueryOpts<ProfileTimeStatus[]> & { wsLive?: boolean }) {
  const { wsLive, ...rest } = opts ?? {}
  return useQuery({
    queryKey: qk.timeStatusToday(),
    queryFn: () => api.time.statusAll(),
    staleTime: STALE.timeStatusToday,
    refetchInterval: wsLive ? false : LIVE_SURFACE_FALLBACK_REFETCH_MS,
    refetchIntervalInBackground: false,
    ...rest,
  })
}

export function useTimeStatusDate(date: string | undefined, opts?: QueryOpts<ProfileTimeStatus[]>) {
  return useQuery({
    queryKey: date ? qk.timeStatusDate(date) : qk.timeStatusToday(),
    queryFn: () => api.time.statusAll(date),
    staleTime: STALE.timeStatusPast,
    ...opts,
  })
}

export function useTimeStatusWeek(
  to?: string,
  bucketOffsetMin?: number,
  opts?: QueryOpts<ProfileTimeStatusWeek[]>,
) {
  return useQuery({
    queryKey: qk.timeStatusWeek(to, bucketOffsetMin),
    queryFn: () => api.time.statusAllWeek(to, bucketOffsetMin),
    staleTime: STALE.timeStatusWeek,
    ...opts,
  })
}

// #777 — summary endpoints: lightweight rollups for the collapsed accordion view.
export function useTimeStatusSummary(opts?: QueryOpts<ProfileTimeSummary[]> & { wsLive?: boolean }) {
  // #1974 (S6a) / #1976 (S7): pause the poll while the `timeStatus` push is live (§3.3) — the push
  // patches this (summary-projected) cache; on disconnect the flat fallback resumes.
  const { wsLive, ...rest } = opts ?? {}
  return useQuery({
    queryKey: qk.timeStatusSummaryToday(),
    queryFn: () => api.time.summaryAll(),
    staleTime: STALE.timeStatusToday,
    refetchInterval: wsLive ? false : LIVE_SURFACE_FALLBACK_REFETCH_MS,
    refetchIntervalInBackground: false,
    ...rest,
  })
}

export function useTimeStatusSummaryWeek(
  to?: string,
  opts?: QueryOpts<ProfileTimeSummaryWeek[]>,
) {
  return useQuery({
    queryKey: qk.timeStatusSummaryWeek(to),
    queryFn: () => api.time.summaryAllWeek(to),
    staleTime: STALE.timeStatusWeek,
    ...opts,
  })
}

// #777 — per-profile detail, fetched only when the accordion row is expanded.
// The query stays cached for the page mount so collapse + re-expand doesn't refetch.
export function useTimeStatusProfileToday(
  profileId: number,
  opts?: QueryOpts<ProfileTimeStatus | undefined> & { wsLive?: boolean },
) {
  // #1974 (S6a) / #1976 (S7): pause the poll while the `timeStatus` push is live (§3.3); the push
  // patches this per-profile-today key off the same body. On disconnect the flat fallback resumes.
  const { wsLive, ...rest } = opts ?? {}
  return useQuery({
    queryKey: qk.timeStatusProfileToday(profileId),
    queryFn: () => api.time.statusAll(undefined, profileId).then(rows => rows[0]),
    staleTime: STALE.timeStatusToday,
    refetchInterval: wsLive ? false : LIVE_SURFACE_FALLBACK_REFETCH_MS,
    refetchIntervalInBackground: false,
    ...rest,
  })
}

export function useTimeStatusProfileWeek(
  profileId: number,
  to: string | undefined,
  bucketOffsetMin: number,
  opts?: QueryOpts<ProfileTimeStatusWeek | undefined>,
) {
  return useQuery({
    queryKey: qk.timeStatusProfileWeek(profileId, to, bucketOffsetMin),
    queryFn: () => api.time.statusAllWeek(to, bucketOffsetMin, profileId).then(rows => rows[0]),
    staleTime: STALE.timeStatusWeek,
    ...opts,
  })
}

// #776: hourly chart on the Today card. Same data path as ProfileTimelinePage
// (`/api/usage/series?profileId=…`) — per-card fetch keyed by profile + date.
export function useUsageSeriesProfileToday(
  profileId: number,
  date: string,
  tz: string,
  opts?: QueryOpts<UsageSeriesResponse> & { groupBy?: 'app' },
) {
  const { groupBy, ...rest } = opts ?? {}
  return useQuery({
    queryKey: qk.usageSeriesProfile(profileId, date, tz, groupBy),
    queryFn: () => api.usage.series({ profileId, date, tz, groupBy }),
    staleTime: STALE.timeStatusToday,
    ...rest,
  })
}

// #1061 — per-app rollup for the expanded profile card subsection.
export function useProfileUsageByApp(
  profileId: number,
  from: string,
  to: string,
  opts?: QueryOpts<ProfileUsageByApp>,
) {
  return useQuery({
    queryKey: qk.profileUsageByApp(profileId, from, to),
    queryFn: () => api.profiles.usageByApp(profileId, from, to),
    staleTime: STALE.timeStatusToday,
    ...opts,
  })
}

// #1089 — weekly per-app engaged-minutes, aggregated from `app_used_daily`.
export function useProfileAppWeekly(
  profileId: number,
  to?: string,
  opts?: QueryOpts<ProfileAppWeeklyUsage>,
) {
  return useQuery({
    queryKey: qk.profileAppWeekly(profileId, to),
    queryFn: () => api.profiles.appWeekly(profileId, to),
    staleTime: STALE.timeStatusWeek,
    ...opts,
  })
}

// Centralised invalidation helpers used by mutation onSuccess handlers.
// Editing a profile or device can change schedule/limit-derived screen-time
// rendering, so we invalidate the time/status keys too.
export function useInvalidators() {
  const qc = useQueryClient()
  return {
    // react-query invalidation is prefix-match, so `['profiles']` also busts
    // the sentinel key `['profiles', 'global']` (#1773) and any future
    // `['profiles', ...]` sub-keys.
    profiles: () => qc.invalidateQueries({ queryKey: qk.profiles() }),
    devices: () => qc.invalidateQueries({ queryKey: qk.devices() }),
    // #1069 — schedule edits change profile/app/blocklist downtime, so also
    // refresh anything whose rendering derives from an active window.
    schedules: () => Promise.all([
      qc.invalidateQueries({ queryKey: qk.schedules() }),
      qc.invalidateQueries({ queryKey: qk.profiles() }),
      qc.invalidateQueries({ queryKey: qk.timeStatusAll() }),
    ]),
    alerts: () => qc.invalidateQueries({ queryKey: qk.alertsAll() }),
    dashboardNow: () => qc.invalidateQueries({ queryKey: qk.dashboardNow() }),
    timeStatus: () => qc.invalidateQueries({ queryKey: qk.timeStatusAll() }),
    profileMutated: () => Promise.all([
      // `qk.profiles()` (= `['profiles']`) prefix-matches `['profiles', 'global']`
      // (#1773) too — react-query invalidation walks the prefix tree.
      qc.invalidateQueries({ queryKey: qk.profiles() }),
      qc.invalidateQueries({ queryKey: qk.devices() }),
      qc.invalidateQueries({ queryKey: qk.timeStatusAll() }),
      qc.invalidateQueries({ queryKey: qk.dashboardNow() }),
    ]),
    deviceMutated: () => Promise.all([
      qc.invalidateQueries({ queryKey: qk.devices() }),
      qc.invalidateQueries({ queryKey: qk.timeStatusAll() }),
      qc.invalidateQueries({ queryKey: qk.dashboardNow() }),
    ]),
  }
}
