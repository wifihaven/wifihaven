// #803: hooks that wrap the hot read endpoints in TanStack Query so SPA
// navigations don't refetch within the per-endpoint stale window and a
// slow backend shows last-known-good while the refetch runs.
//
// Mutations live alongside their existing imperative call-sites; this
// module exposes an `invalidate` helper plus the key factory so the
// pages can invalidate the right keys onSuccess.
import { useQuery, useQueryClient, type UseQueryOptions } from '@tanstack/react-query'
import { api } from '@/api/client'
import type {
  Alert, BlocklistSummary, DashboardNow, Device, GlobalPolicyView, HouseholdSettings, NamedSchedule, ProfileDetail,
  ProfileTimeStatus,
  ProfileAppWeeklyUsage,
  ProfileTimeStatusWeek, ProfileTimeSummary, ProfileTimeSummaryWeek, ProfileUsageByApp,
  QueryLog, UsageConfig, UsageSeriesResponse,
} from '@/types/api'

const MIN = 60_000

// #1338: "Most recently blocked" dashboard panel — newest-first, blocked-only,
// un-aggregated recent drops. Capped small; the full history lives on the
// Connection Events page.
export const RECENT_BLOCKED_LIMIT = 20
// "Recent" = the trailing 15 minutes: the panel answers "what just got dropped",
// not "what was dropped today" (the 24h aggregate is "Top Blocked"). /api/logs
// only takes integer `hours`, so we fetch a 1h window for headroom and trim to
// the 15-min window client-side.
export const RECENT_BLOCKED_WINDOW_MS = 15 * 60_000

// Per-endpoint stale times (#803).
const STALE = {
  dashboardNow: 5_000,
  recentBlocked: 5_000,
  timeStatusToday: 30_000,
  timeStatusPast: 5 * MIN,
  timeStatusWeek: 5 * MIN,
  profiles: 5 * MIN,
  devices: 5 * MIN,
} as const

export const qk = {
  profiles: () => ['profiles'] as const,
  devices: () => ['devices'] as const,
  globalPolicy: () => ['global', 'policy'] as const,
  alerts: (includeAll: boolean) => ['alerts', includeAll] as const,
  schedules: () => ['schedules'] as const,
  dashboardNow: () => ['dashboard', 'now'] as const,
  recentBlocked: () => ['dashboard', 'recent-blocked'] as const,
  timeStatusToday: () => ['time', 'status', 'today'] as const,
  timeStatusDate: (date: string) => ['time', 'status', 'date', date] as const,
  timeStatusWeek: (to?: string, bucketOffsetMin?: number) =>
    ['time', 'status', 'week', to ?? 'current', bucketOffsetMin ?? 0] as const,
  // #777 — per-profile detail (today/week) cached on first expand so collapse-then-
  // re-expand within the same page mount doesn't refetch.
  timeStatusProfileToday: (profileId: number) =>
    ['time', 'status', 'today', 'profile', profileId] as const,
  timeStatusProfileWeek: (profileId: number, to: string | undefined, bucketOffsetMin: number) =>
    ['time', 'status', 'week', to ?? 'current', bucketOffsetMin, 'profile', profileId] as const,
  timeStatusSummaryToday: () => ['time', 'status', 'summary', 'today'] as const,
  timeStatusSummaryWeek: (to?: string) => ['time', 'status', 'summary', 'week', to ?? 'current'] as const,
  // #776 — hourly chart on the Today card.
  // #1079 — groupBy is part of the cache key so the by-app axis doesn't
  // share a cache slot with the legacy host axis.
  usageSeriesProfile: (profileId: number, date: string, tz: string, groupBy?: string) =>
    ['usage', 'series', 'profile', profileId, date, tz, groupBy ?? 'host'] as const,
  // #1061 — per-app time-used breakdown for one profile over [from,to].
  profileUsageByApp: (profileId: number, from: string, to: string) =>
    ['profiles', profileId, 'usage-by-app', from, to] as const,
  // #1089 — per-app engaged-minutes summed over the trailing 7-day window
  // ending at `to`. Aggregates FROM `app_used_daily`, so by construction it
  // tracks the daily rollup the per-app cap reads.
  profileAppWeekly: (profileId: number, to?: string) =>
    ['profiles', profileId, 'usage', 'app', 'weekly', to ?? 'current'] as const,
}

type QueryOpts<T> = Omit<UseQueryOptions<T, Error, T, readonly unknown[]>, 'queryKey' | 'queryFn'>

export function useProfiles(opts?: QueryOpts<ProfileDetail[]>) {
  return useQuery({
    queryKey: qk.profiles(),
    queryFn: () => api.profiles.list(),
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
    queryKey: ['usage', 'config'] as const,
    queryFn: () => api.usage.config(),
    staleTime: 60 * MIN,
    ...opts,
  })
}

export function useHouseholdSettings(opts?: QueryOpts<HouseholdSettings>) {
  return useQuery({
    queryKey: ['household', 'settings'] as const,
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

// #1473 — the blocklist catalog (GET /api/blocklists), shared by the
// Blocklists matrix page and the inline blocked-categories editor on the
// profile card. Cached so N profile cards don't each fire their own fetch.
export function useBlocklists(opts?: QueryOpts<BlocklistSummary[]>) {
  return useQuery({
    queryKey: ['blocklists'] as const,
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
// newest-first, trailing 15 min). Reuses the existing /api/logs read with
// blocked=true; the route already orders ts DESC and honours the limit, and
// applies the same JWT/household scoping every other dashboard read uses. We
// fetch a 1h window (integer-hours param) and trim to RECENT_BLOCKED_WINDOW_MS
// client-side so a stale block doesn't masquerade as recent. Polls on the same
// 10s cadence as the "now" snapshot so a just-now block surfaces immediately.
// NB a row here is a real traffic-layer drop, not a DNS event (DNS always
// resolves) — see memory/blocking_is_traffic_layer_not_dns.md.
export function useRecentBlocked(opts?: QueryOpts<QueryLog[]>) {
  return useQuery({
    queryKey: qk.recentBlocked(),
    queryFn: () =>
      api.logs.query({ blocked: true, limit: RECENT_BLOCKED_LIMIT, hours: 1 }).then(p => p.rows),
    select: (rows: QueryLog[]) => {
      const cutoff = Date.now() - RECENT_BLOCKED_WINDOW_MS
      return rows.filter(r => new Date(r.ts).getTime() >= cutoff)
    },
    staleTime: STALE.recentBlocked,
    refetchInterval: 10_000,
    refetchIntervalInBackground: false,
    ...opts,
  })
}

export function useTimeStatusToday(opts?: QueryOpts<ProfileTimeStatus[]>) {
  return useQuery({
    queryKey: qk.timeStatusToday(),
    queryFn: () => api.time.statusAll(),
    staleTime: STALE.timeStatusToday,
    ...opts,
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
export function useTimeStatusSummary(opts?: QueryOpts<ProfileTimeSummary[]>) {
  return useQuery({
    queryKey: qk.timeStatusSummaryToday(),
    queryFn: () => api.time.summaryAll(),
    staleTime: STALE.timeStatusToday,
    ...opts,
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
  opts?: QueryOpts<ProfileTimeStatus | undefined>,
) {
  return useQuery({
    queryKey: qk.timeStatusProfileToday(profileId),
    queryFn: () => api.time.statusAll(undefined, profileId).then(rows => rows[0]),
    staleTime: STALE.timeStatusToday,
    ...opts,
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

// #1320 — household-global policy management + audit view. Low-churn admin
// data, so a generous stale window; the page invalidates on every edit.
export function useGlobalPolicy(opts?: QueryOpts<GlobalPolicyView>) {
  return useQuery({
    queryKey: qk.globalPolicy(),
    queryFn: () => api.global.get(),
    staleTime: 60_000,
    ...opts,
  })
}

// Centralised invalidation helpers used by mutation onSuccess handlers.
// Editing a profile or device can change schedule/limit-derived screen-time
// rendering, so we invalidate the time/status keys too.
export function useInvalidators() {
  const qc = useQueryClient()
  return {
    profiles: () => qc.invalidateQueries({ queryKey: qk.profiles() }),
    devices: () => qc.invalidateQueries({ queryKey: qk.devices() }),
    globalPolicy: () => qc.invalidateQueries({ queryKey: qk.globalPolicy() }),
    // #1069 — schedule edits change profile/app/blocklist downtime, so also
    // refresh anything whose rendering derives from an active window.
    schedules: () => Promise.all([
      qc.invalidateQueries({ queryKey: qk.schedules() }),
      qc.invalidateQueries({ queryKey: qk.profiles() }),
      qc.invalidateQueries({ queryKey: ['time', 'status'] }),
    ]),
    alerts: () => qc.invalidateQueries({ queryKey: ['alerts'] }),
    dashboardNow: () => qc.invalidateQueries({ queryKey: qk.dashboardNow() }),
    timeStatus: () => qc.invalidateQueries({ queryKey: ['time', 'status'] }),
    profileMutated: () => Promise.all([
      qc.invalidateQueries({ queryKey: qk.profiles() }),
      qc.invalidateQueries({ queryKey: qk.devices() }),
      qc.invalidateQueries({ queryKey: ['time', 'status'] }),
      qc.invalidateQueries({ queryKey: qk.dashboardNow() }),
    ]),
    deviceMutated: () => Promise.all([
      qc.invalidateQueries({ queryKey: qk.devices() }),
      qc.invalidateQueries({ queryKey: ['time', 'status'] }),
      qc.invalidateQueries({ queryKey: qk.dashboardNow() }),
    ]),
  }
}
