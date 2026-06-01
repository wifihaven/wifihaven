// #1099 — the batched per-profile series binding. Asserts the wire contract
// the API expects: profileIds serialized comma-separated as a single
// `profileId=` param (parseMultiProfileIdParam splits on comma and only reads
// the first occurrence), plus pass-through of date/tz/topN/groupBy.
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { api } from './client'
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
