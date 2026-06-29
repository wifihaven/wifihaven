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
  LIVE_SURFACE_FALLBACK_REFETCH_MS,
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

// #1976 (SPA-ws S7): the adaptive refetch ladder (TIME_STATUS_REFETCH_LADDER) is RETIRED.
// The S6a `timeStatus` push (§3.1) drives freshness whenever the socket is live, so the
// near-cap fast-poll the ladder existed for is redundant. What remains is a FLAT disconnected
// fallback: while `wsLive` is false the live time-used hooks poll at a constant
// LIVE_SURFACE_FALLBACK_REFETCH_MS so a near-cap "minutes left" can't lag enforcement during a
// socket outage (#1871). While `wsLive` is true the poll is paused entirely (the push is the
// freshness). The immutable 'past'/'week' hooks never poll.
describe('time-used disconnected fallback poll (#1976 — adaptive ladder retired)', () => {
  // Behavioural: freshClient's long staleTime means an interval-driven refetch is the ONLY
  // thing that can produce a second network call, so a missing/false refetchInterval makes the
  // assertion fail. The fallback is now FLAT — remaining-minutes no longer change the cadence.
  const nearCap = [{ profileId: 1, dailyLimitMins: 60, usedMins: 58, remainingMins: 2 }]
  const farFromCap = [{ profileId: 1, dailyLimitMins: 240, usedMins: 10, remainingMins: 230 }]

  async function expectFlatPoll(
    seed: (v: unknown) => void,
    rows: unknown,
    render: () => void,
    fetchSpy: ReturnType<typeof vi.fn>,
  ): Promise<void> {
    seed(rows)
    vi.useFakeTimers()
    try {
      render()
      await vi.advanceTimersByTimeAsync(0) // initial fetch resolves
      expect(fetchSpy).toHaveBeenCalledTimes(1)
      await vi.advanceTimersByTimeAsync(LIVE_SURFACE_FALLBACK_REFETCH_MS) // one flat interval
      expect(fetchSpy).toHaveBeenCalledTimes(2)
    } finally {
      vi.useRealTimers()
    }
  }

  it('useTimeStatusToday polls at the flat fallback when disconnected — near cap', async () => {
    const statusAll = api.time.statusAll as unknown as ReturnType<typeof vi.fn>
    const wrapper = makeWrapper(freshClient())
    await expectFlatPoll(
      v => statusAll.mockResolvedValue(v), nearCap,
      () => renderHook(() => useTimeStatusToday(), { wrapper }),
      statusAll,
    )
  })

  // No backoff anymore: far-from-cap polls at the SAME flat cadence as near-cap. Pre-S7 the
  // ladder backed this case off to a 5m baseline; that adaptivity is gone (the push handles
  // near-cap urgency when live, so the disconnected fallback is a single flat cadence).
  it('useTimeStatusToday polls at the flat fallback when disconnected — far from cap (no backoff)', async () => {
    const statusAll = api.time.statusAll as unknown as ReturnType<typeof vi.fn>
    const wrapper = makeWrapper(freshClient())
    await expectFlatPoll(
      v => statusAll.mockResolvedValue(v), farFromCap,
      () => renderHook(() => useTimeStatusToday(), { wrapper }),
      statusAll,
    )
  })

  it('useTimeStatusSummary polls at the flat fallback when disconnected', async () => {
    const summaryAll = api.time.summaryAll as unknown as ReturnType<typeof vi.fn>
    const wrapper = makeWrapper(freshClient())
    await expectFlatPoll(
      v => summaryAll.mockResolvedValue(v), nearCap,
      () => renderHook(() => useTimeStatusSummary(), { wrapper }),
      summaryAll,
    )
  })

  it('useTimeStatusProfileToday polls at the flat fallback when disconnected', async () => {
    const statusAll = api.time.statusAll as unknown as ReturnType<typeof vi.fn>
    const wrapper = makeWrapper(freshClient())
    await expectFlatPoll(
      // single-row shape (the hook takes rows[0])
      v => statusAll.mockResolvedValue(v), nearCap,
      () => renderHook(() => useTimeStatusProfileToday(1), { wrapper }),
      statusAll,
    )
  })

  // The push being live (`wsLive: true`) pauses the fallback entirely (§3.3) — no poll at all,
  // even sitting right at a cap. The push, not a poll, is the freshness while connected.
  it('pauses the fallback poll while the timeStatus push is live (wsLive)', async () => {
    const statusAll = api.time.statusAll as unknown as ReturnType<typeof vi.fn>
    statusAll.mockResolvedValue(nearCap)
    const wrapper = makeWrapper(freshClient())
    vi.useFakeTimers()
    try {
      renderHook(() => useTimeStatusToday({ wsLive: true }), { wrapper })
      await vi.advanceTimersByTimeAsync(0)
      expect(statusAll).toHaveBeenCalledTimes(1) // initial fetch only
      await vi.advanceTimersByTimeAsync(LIVE_SURFACE_FALLBACK_REFETCH_MS * 5)
      expect(statusAll).toHaveBeenCalledTimes(1) // push drives freshness; no poll
    } finally {
      vi.useRealTimers()
    }
  })
})
