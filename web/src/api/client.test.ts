// #1099 — the batched per-profile series binding. Asserts the wire contract
// the API expects: profileIds serialized comma-separated as a single
// `profileId=` param (parseMultiProfileIdParam splits on comma and only reads
// the first occurrence), plus pass-through of date/tz/topN/groupBy.
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { api, isCanceledError } from './client'
import { httpStatusOf } from './httpError'
import { apiHealth } from './apiHealth'
import type { UsageSeriesBatchResponse } from '@/types/api'

function okJson(body: unknown): Response {
  return {
    ok: true,
    status: 200,
    headers: new Headers({ 'content-length': '2' }),
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
  } as unknown as Response
}

describe('usage.seriesBatch (#1099)', () => {
  beforeEach(() => {
    localStorage.setItem('token', 'tok')
  })
  afterEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
  })

  it('serializes profileIds comma-separated into a single profileId param', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okJson({ series: [] }))
    vi.stubGlobal('fetch', fetchMock)

    await api.usage.seriesBatch({ profileIds: [3, 4, 7], date: '2025-05-12', tz: 'America/New_York' })

    const url = new URL(fetchMock.mock.calls[0][0] as string, 'http://localhost')
    expect(url.pathname).toBe('/api/usage/series/batch')
    expect(url.searchParams.get('profileId')).toBe('3,4,7')
    expect(url.searchParams.getAll('profileId')).toHaveLength(1)
    expect(url.searchParams.get('date')).toBe('2025-05-12')
    expect(url.searchParams.get('tz')).toBe('America/New_York')
  })

  it('forwards topN and groupBy when present', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okJson({ series: [] }))
    vi.stubGlobal('fetch', fetchMock)

    await api.usage.seriesBatch({ profileIds: [1], topN: 50, groupBy: 'app' })

    const url = new URL(fetchMock.mock.calls[0][0] as string, 'http://localhost')
    expect(url.searchParams.get('topN')).toBe('50')
    expect(url.searchParams.get('groupBy')).toBe('app')
  })

  it('parses the batch response body', async () => {
    const body: UsageSeriesBatchResponse = {
      series: [
        {
          profileId: 3,
          profileName: 'Kids',
          date: '2025-05-12',
          tz: 'UTC',
          topHosts: [{ host: { type: 'fqdn', value: 'youtube.com' }, dayMins: 10 }],
          buckets: [],
        },
      ],
    }
    const fetchMock = vi.fn().mockResolvedValue(okJson(body))
    vi.stubGlobal('fetch', fetchMock)

    const res = await api.usage.seriesBatch({ profileIds: [3] })
    expect(res.series).toHaveLength(1)
    expect(res.series[0].profileId).toBe(3)
    expect(res.series[0].topHosts[0].host.value).toBe('youtube.com')
  })
})

// #1299 — the Profiles "+Time" grant didn't refresh the used/cap bar until a
// force-reload. Root cause: today-mode time-status GETs are served with
// `Cache-Control: max-age=30`, and `req()` did not opt out of the browser HTTP
// cache, so React Query's post-mutation refetch was answered from cache with a
// stale `extensionMins`. Every API call must send `cache: 'no-store'` so the
// browser cache can never shadow a fresh read; React Query stays the only cache.
describe('req opts every call out of the browser HTTP cache (#1299)', () => {
  beforeEach(() => {
    localStorage.setItem('token', 'tok')
  })
  afterEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
  })

  it('GET reads pass cache: no-store to fetch (the time-status summary path)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okJson([]))
    vi.stubGlobal('fetch', fetchMock)

    await api.time.summaryAll()

    const opts = fetchMock.mock.calls[0][1] as RequestInit
    expect(opts.cache).toBe('no-store')
  })

  it('mutations also pass cache: no-store (the +Time grant path)', async () => {
    const fetchMock = vi.fn().mockResolvedValue(okJson({ id: 1, grantedMinutes: 30 }))
    vi.stubGlobal('fetch', fetchMock)

    await api.time.grantExtension({ profileId: 1, extraMinutes: 30, note: null })

    const opts = fetchMock.mock.calls[0][1] as RequestInit
    expect(opts.cache).toBe('no-store')
  })
})

// #2047 — a request the *caller* cancels (the Traffic Usage page superseding its
// in-flight load on a bucket switch / filter change) must be distinguished from
// the 10s timeout abort: a cancellation is NOT an API-health signal, so it must
// never trip the unreachable banner, and it surfaces as a typed cancellation the
// caller swallows rather than the cryptic "signal is aborted without reason".
describe('req caller-cancellation is not an API-down signal (#2047)', () => {
  beforeEach(() => {
    localStorage.setItem('token', 'tok')
    apiHealth.reset()
  })
  afterEach(() => {
    vi.restoreAllMocks()
    localStorage.clear()
    apiHealth.reset()
  })

  it('forwards the caller signal and reports a cancellation, not unreachable', async () => {
    // Real fetch semantics: a fetch given an already-aborted signal rejects with
    // an AbortError DOMException.
    const fetchMock = vi.fn().mockRejectedValue(
      new DOMException('signal is aborted without reason', 'AbortError'),
    )
    vi.stubGlobal('fetch', fetchMock)

    const controller = new AbortController()
    controller.abort()

    const p = api.usage.traffic({ bucket: '1m', signal: controller.signal })
    await expect(p).rejects.toSatisfy(isCanceledError)
    // The banner must stay clear — the caller cancelled, the API is fine.
    expect(apiHealth.snapshot().unreachable).toBe(false)
  })

  it('still reports a genuine 10s timeout as unreachable (not a cancellation)', async () => {
    vi.useFakeTimers()
    // A fetch that never resolves until its own (timeout) signal aborts it.
    const fetchMock = vi.fn(
      (_url: string, opts: RequestInit) =>
        new Promise<Response>((_res, rej) => {
          opts.signal?.addEventListener('abort', () =>
            rej(new DOMException('signal is aborted without reason', 'AbortError')),
          )
        }),
    )
    vi.stubGlobal('fetch', fetchMock)

    const p = api.usage.traffic({ bucket: '1m' })
    const assertion = expect(p).rejects.toThrow()
    await vi.advanceTimersByTimeAsync(10_001)
    await assertion
    // No caller signal → the abort is the timeout → genuine unreachable.
    expect(apiHealth.snapshot().unreachable).toBe(true)
    expect(apiHealth.snapshot().reason).toBe('timeout')
    vi.useRealTimers()
  })
})

// #2824 — the production behaviour behind the category-coverage view's "is this
// household's global layer ABSENT, or did the read FAIL?" distinction. A 404 from
// `GET /api/profiles/global` is an absent sentinel (true for households
// provisioned between V65 and V73) and resolves to an empty, computable layer;
// anything else makes coverage unknowable. Without a status on the thrown error
// that distinction has to string-match a response body. Asserted here because
// every OTHER test duck-types `status` onto a plain Error, so reverting this to
// `throw new Error(...)` would leave the whole suite green while regressing every
// such household to "Coverage unknown".
describe('req carries the HTTP status on a rejected response (#2824)', () => {
  function errorResponse(status: number, body: string): Response {
    return {
      ok: false,
      status,
      // Distinct from the `HTTP <status>` fallback under test, so the assertion
      // can tell the two apart.
      statusText: 'Server-supplied status text',
      headers: new Headers(),
      text: () => Promise.resolve(body),
    } as unknown as Response
  }

  it('rejects a 404 with the status and the body as the message', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(errorResponse(404, 'Global profile not seeded')))
    const err = await api.profiles.getGlobal().then(() => null, (e: unknown) => e)
    expect(err).toBeInstanceOf(Error)
    expect(httpStatusOf(err)).toBe(404)
    expect((err as Error).message).toBe('Global profile not seeded')
  })

  it('carries the status on a 5xx too, so it is distinguishable from the 404', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(errorResponse(500, 'boom')))
    const err = await api.profiles.getGlobal().then(() => null, (e: unknown) => e)
    expect(httpStatusOf(err)).toBe(500)
  })

  it('falls back to a status-derived message when the body is empty', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(errorResponse(409, '')))
    const err = await api.profiles.getGlobal().then(() => null, (e: unknown) => e)
    expect(httpStatusOf(err)).toBe(409)
    expect((err as Error).message).toBe('HTTP 409')
  })
})
