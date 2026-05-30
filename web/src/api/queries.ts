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
  Alert, DashboardNow, Device, HouseholdSettings, ProfileDetail, ProfileTimeStatus,
  ProfileTimeStatusWeek, ProfileTimeSummary, ProfileTimeSummaryWeek, ProfileUsageByApp,
  UsageSeriesResponse,
} from '@/types/api'

const MIN = 60_000

// Per-endpoint stale times (#803).
const STALE = {
  dashboardNow: 5_000,
  timeStatusToday: 30_000,
  timeStatusPast: 5 * MIN,
  timeStatusWeek: 5 * MIN,
  profiles: 5 * MIN,
  devices: 5 * MIN,
} as const

export const qk = {
  profiles: () => ['profiles'] as const,
  devices: () => ['devices'] as const,
  alerts: (includeAll: boolean) => ['alerts', includeAll] as const,
  dashboardNow: () => ['dashboard', 'now'] as const,
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

// Centralised invalidation helpers used by mutation onSuccess handlers.
// Editing a profile or device can change schedule/limit-derived screen-time
// rendering, so we invalidate the time/status keys too.
export function useInvalidators() {
  const qc = useQueryClient()
  return {
    profiles: () => qc.invalidateQueries({ queryKey: qk.profiles() }),
    devices: () => qc.invalidateQueries({ queryKey: qk.devices() }),
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
