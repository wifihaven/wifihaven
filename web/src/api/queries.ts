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
  DashboardNow, Device, ProfileDetail, ProfileTimeStatus, ProfileTimeStatusWeek,
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
  dashboardNow: () => ['dashboard', 'now'] as const,
  timeStatusToday: () => ['time', 'status', 'today'] as const,
  timeStatusDate: (date: string) => ['time', 'status', 'date', date] as const,
  timeStatusWeek: (to?: string, bucketOffsetMin?: number) =>
    ['time', 'status', 'week', to ?? 'current', bucketOffsetMin ?? 0] as const,
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

export function useDevices(opts?: QueryOpts<Device[]>) {
  return useQuery({
    queryKey: qk.devices(),
    queryFn: () => api.devices.list(),
    staleTime: STALE.devices,
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

// Centralised invalidation helpers used by mutation onSuccess handlers.
// Editing a profile or device can change schedule/limit-derived screen-time
// rendering, so we invalidate the time/status keys too.
export function useInvalidators() {
  const qc = useQueryClient()
  return {
    profiles: () => qc.invalidateQueries({ queryKey: qk.profiles() }),
    devices: () => qc.invalidateQueries({ queryKey: qk.devices() }),
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
