// #803: cache-behaviour tests for the SWR query hooks. These exercise the
// QueryClient surface directly so the assertions don't get tangled in a
// specific page's rendering. The wrapper uses a deliberately non-zero
// stale time to prove the cache is consulted before another network hit.
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { type ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

vi.mock('@/api/client', () => ({
  api: {
    profiles: { list: vi.fn() },
    devices: { list: vi.fn() },
    time: { statusAll: vi.fn(), statusAllWeek: vi.fn(), summaryAll: vi.fn() },
    dashboard: { now: vi.fn() },
  },
}))

import { api } from '@/api/client'
import {
  useProfiles, useDevices, useTimeStatusToday, useTimeStatusSummary,
  useTimeStatusProfileToday, useInvalidators, qk,
  timeStatusRefetchMs, TIME_STATUS_REFETCH_MAX_MS,
} from './queries'

function makeWrapper(client: QueryClient) {
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
}

function freshClient(staleMs = 60_000): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        retry: false,
        refetchOnWindowFocus: false,
        refetchOnReconnect: false,
        refetchOnMount: true,
        staleTime: staleMs,
      },
    },
  })
}

beforeEach(() => {
  vi.resetAllMocks()
  ;(api.profiles.list  as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
  ;(api.devices.list   as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
  ;(api.time.statusAll as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
  ;(api.time.summaryAll as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([])
  ;(api.dashboard.now  as unknown as ReturnType<typeof vi.fn>).mockResolvedValue({ asOf: '', profiles: [] })
})

describe('SWR caching (#803)', () => {
  it('two consecutive useProfiles renders within stale-time share one fetch', async () => {
    const qc = freshClient()
    const wrapper = makeWrapper(qc)
    const first = renderHook(() => useProfiles(), { wrapper })
    await waitFor(() => expect(first.result.current.isSuccess).toBe(true))
    expect(api.profiles.list).toHaveBeenCalledTimes(1)

    const second = renderHook(() => useProfiles(), { wrapper })
    await waitFor(() => expect(second.result.current.isSuccess).toBe(true))
    expect(api.profiles.list).toHaveBeenCalledTimes(1)
  })

  it('invalidators.deviceMutated forces the next render to refetch', async () => {
    const qc = freshClient()
    const wrapper = makeWrapper(qc)
    const devicesHook  = renderHook(() => useDevices(), { wrapper })
    const invalidHook  = renderHook(() => useInvalidators(), { wrapper })
    await waitFor(() => expect(devicesHook.result.current.isSuccess).toBe(true))
    expect(api.devices.list).toHaveBeenCalledTimes(1)

    await act(async () => { await invalidHook.result.current.deviceMutated() })
    await waitFor(() => expect(api.devices.list).toHaveBeenCalledTimes(2))
  })

  it('invalidators.profileMutated invalidates time/status queries', async () => {
    const qc = freshClient()
    const wrapper = makeWrapper(qc)
    const timeHook    = renderHook(() => useTimeStatusToday(), { wrapper })
    const invalidHook = renderHook(() => useInvalidators(), { wrapper })
    await waitFor(() => expect(timeHook.result.current.isSuccess).toBe(true))
    expect(api.time.statusAll).toHaveBeenCalledTimes(1)

    await act(async () => { await invalidHook.result.current.profileMutated() })
    await waitFor(() => expect(api.time.statusAll).toHaveBeenCalledTimes(2))
  })

  it('keeps last-known-good data across a refetch (no flicker to undefined)', async () => {
    const qc = freshClient(0)
    const wrapper = makeWrapper(qc)
    ;(api.profiles.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([
      { profile: { id: 1, name: 'Kids', blockedCategories: [], paused: false, failureMode: 'block-all' as const, crossDeviceOverlapMode: 'sum' as const }, timeLimit: null },
    ])
    const hook = renderHook(() => useProfiles(), { wrapper })
    await waitFor(() => expect(hook.result.current.isSuccess).toBe(true))
    expect(hook.result.current.data?.[0]?.profile.name).toBe('Kids')

    await act(async () => { await qc.invalidateQueries({ queryKey: qk.profiles() }) })
    expect(api.profiles.list).toHaveBeenCalledTimes(2)
    // After the invalidation completes, data is still present — the
    // hook never exposes `undefined` for a query that has loaded once.
    expect(hook.result.current.data?.[0]?.profile.name).toBe('Kids')
  })
})

// #1871: the live time-used surfaces must poll in the foreground so the
// displayed "minutes left" can't lag enforcement (the router blocks within
// seconds of the cap, but a query with only a staleTime never refetches on its
// own). The cadence is ADAPTIVE — fast near a profile's cap, slow with lots of
// time left. The immutable 'past'/'week' hooks must NOT poll.
describe('time-used adaptive poll cadence (#1871)', () => {
  // The ladder the operator asked for: ≤5m → 10s, ≤10m → 1m, ≤30m → 5m,
  // beyond that (or no daily limit) → the 5m baseline.
  it('maps remaining minutes to the poll interval', () => {
    expect(timeStatusRefetchMs(0)).toBe(10_000)
    expect(timeStatusRefetchMs(3)).toBe(10_000)
    expect(timeStatusRefetchMs(5)).toBe(10_000)
    expect(timeStatusRefetchMs(6)).toBe(60_000)
    expect(timeStatusRefetchMs(10)).toBe(60_000)
    expect(timeStatusRefetchMs(11)).toBe(300_000)
    expect(timeStatusRefetchMs(30)).toBe(300_000)
    expect(timeStatusRefetchMs(31)).toBe(TIME_STATUS_REFETCH_MAX_MS)
    expect(timeStatusRefetchMs(120)).toBe(TIME_STATUS_REFETCH_MAX_MS)
  })

  it('treats no-daily-limit and past-cap edges sensibly', () => {
    // No limit in force (null/undefined remaining) → slow baseline, not a tight poll.
    expect(timeStatusRefetchMs(null)).toBe(TIME_STATUS_REFETCH_MAX_MS)
    expect(timeStatusRefetchMs(undefined)).toBe(TIME_STATUS_REFETCH_MAX_MS)
    // At/over the cap (a blocked profile) → fastest bucket, so a time extension
    // or unblock surfaces within seconds.
    expect(timeStatusRefetchMs(-5)).toBe(10_000)
  })

  // Behavioural: with fake timers, a hook fetched with a near-cap profile polls
  // again after the fast 10s bucket. freshClient's long staleTime means an
  // interval-driven refetch is the ONLY thing that can produce a second network
  // call, so a missing/false refetchInterval makes the assertion fail.
  const nearCap = [{ profileId: 1, dailyLimitMins: 60, usedMins: 58, remainingMins: 2 }]

  async function expectFastPoll(
    seed: (v: unknown) => void,
    render: () => void,
    fetchSpy: ReturnType<typeof vi.fn>,
  ): Promise<void> {
    seed(nearCap)
    vi.useFakeTimers()
    try {
      render()
      await vi.advanceTimersByTimeAsync(0) // initial fetch resolves
      expect(fetchSpy).toHaveBeenCalledTimes(1)
      await vi.advanceTimersByTimeAsync(10_000) // one fast-bucket interval
      expect(fetchSpy).toHaveBeenCalledTimes(2)
    } finally {
      vi.useRealTimers()
    }
  }

  it('useTimeStatusToday polls fast near the cap', async () => {
    const statusAll = api.time.statusAll as unknown as ReturnType<typeof vi.fn>
    const wrapper = makeWrapper(freshClient())
    await expectFastPoll(
      v => statusAll.mockResolvedValue(v),
      () => renderHook(() => useTimeStatusToday(), { wrapper }),
      statusAll,
    )
  })

  it('useTimeStatusSummary polls fast near the cap', async () => {
    const summaryAll = api.time.summaryAll as unknown as ReturnType<typeof vi.fn>
    const wrapper = makeWrapper(freshClient())
    await expectFastPoll(
      v => summaryAll.mockResolvedValue(v),
      () => renderHook(() => useTimeStatusSummary(), { wrapper }),
      summaryAll,
    )
  })

  it('useTimeStatusProfileToday polls fast near the cap', async () => {
    const statusAll = api.time.statusAll as unknown as ReturnType<typeof vi.fn>
    const wrapper = makeWrapper(freshClient())
    await expectFastPoll(
      // single-row shape (the hook takes rows[0])
      v => statusAll.mockResolvedValue(v),
      () => renderHook(() => useTimeStatusProfileToday(1), { wrapper }),
      statusAll,
    )
  })

  // Adaptivity: with lots of time left, the hook does NOT poll on the fast
  // cadence — it stays quiet until the slow baseline elapses.
  it('backs off to the slow baseline with lots of time left', async () => {
    const statusAll = api.time.statusAll as unknown as ReturnType<typeof vi.fn>
    statusAll.mockResolvedValue([
      { profileId: 1, dailyLimitMins: 240, usedMins: 10, remainingMins: 230 },
    ])
    const wrapper = makeWrapper(freshClient())
    vi.useFakeTimers()
    try {
      renderHook(() => useTimeStatusToday(), { wrapper })
      await vi.advanceTimersByTimeAsync(0)
      expect(statusAll).toHaveBeenCalledTimes(1)
      // Fast-bucket interval elapses — still no refetch, because we're far from the cap.
      await vi.advanceTimersByTimeAsync(60_000)
      expect(statusAll).toHaveBeenCalledTimes(1)
      // The slow baseline elapses — now it polls.
      await vi.advanceTimersByTimeAsync(TIME_STATUS_REFETCH_MAX_MS)
      expect(statusAll).toHaveBeenCalledTimes(2)
    } finally {
      vi.useRealTimers()
    }
  })
})
