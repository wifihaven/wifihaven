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
  useTimeStatusProfileToday, useInvalidators, qk, TIME_STATUS_REFETCH_MS,
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
// own). Each 'today' hook must carry a bounded refetchInterval; the immutable
// 'past'/'week' hooks must NOT poll.
describe('time-used live polling (#1871)', () => {
  it('exposes a bounded refetch interval constant', () => {
    expect(TIME_STATUS_REFETCH_MS).toBeGreaterThan(0)
    expect(TIME_STATUS_REFETCH_MS).toBeLessThanOrEqual(30_000)
  })

  // Drive a hook with fake timers and prove it refetches after the interval.
  // freshClient's long staleTime means an interval-driven refetch is the ONLY
  // thing that can produce a second network call, so a missing/false
  // refetchInterval makes the second assertion fail.
  async function expectLivePolling(
    render: () => void,
    fetchSpy: ReturnType<typeof vi.fn>,
  ): Promise<void> {
    vi.useFakeTimers()
    try {
      render()
      // Let the initial fetch resolve.
      await vi.advanceTimersByTimeAsync(0)
      expect(fetchSpy).toHaveBeenCalledTimes(1)
      // One interval later, it polls again on its own.
      await vi.advanceTimersByTimeAsync(TIME_STATUS_REFETCH_MS)
      expect(fetchSpy).toHaveBeenCalledTimes(2)
    } finally {
      vi.useRealTimers()
    }
  }

  it('useTimeStatusToday refetches on the live interval', async () => {
    const wrapper = makeWrapper(freshClient())
    await expectLivePolling(
      () => renderHook(() => useTimeStatusToday(), { wrapper }),
      api.time.statusAll as unknown as ReturnType<typeof vi.fn>,
    )
  })

  it('useTimeStatusSummary refetches on the live interval', async () => {
    const wrapper = makeWrapper(freshClient())
    await expectLivePolling(
      () => renderHook(() => useTimeStatusSummary(), { wrapper }),
      api.time.summaryAll as unknown as ReturnType<typeof vi.fn>,
    )
  })

  it('useTimeStatusProfileToday refetches on the live interval', async () => {
    const wrapper = makeWrapper(freshClient())
    await expectLivePolling(
      () => renderHook(() => useTimeStatusProfileToday(1), { wrapper }),
      api.time.statusAll as unknown as ReturnType<typeof vi.fn>,
    )
  })
})
