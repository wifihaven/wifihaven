// #1191 — global API-unreachable detector + banner.
//
// When the API returns 5xx or a network/timeout error, the SPA must surface
// a global banner instead of letting pages render empty lists (which look
// indistinguishable from "no data" — the operator's #1191 incident report).
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, act, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

import { apiHealth } from './apiHealth'
import { ApiUnreachableBanner } from '@/components/ApiUnreachableBanner'
import { api } from './client'

function renderBanner(client = new QueryClient()) {
  return render(
    <QueryClientProvider client={client}>
      <ApiUnreachableBanner />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  apiHealth.reset()
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('apiHealth store', () => {
  it('starts healthy and turns unhealthy after a reported failure', () => {
    expect(apiHealth.snapshot().unreachable).toBe(false)
    apiHealth.reportFailure('5xx')
    expect(apiHealth.snapshot().unreachable).toBe(true)
  })

  it('clears on reported success', () => {
    apiHealth.reportFailure('network')
    expect(apiHealth.snapshot().unreachable).toBe(true)
    apiHealth.reportSuccess()
    expect(apiHealth.snapshot().unreachable).toBe(false)
  })

  it('notifies subscribers when state flips', () => {
    const cb = vi.fn()
    const unsub = apiHealth.subscribe(cb)
    apiHealth.reportFailure('5xx')
    expect(cb).toHaveBeenCalled()
    unsub()
  })
})

describe('ApiUnreachableBanner', () => {
  it('is hidden when API is healthy', () => {
    renderBanner()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('renders a red banner with a Retry button when API is unreachable', () => {
    renderBanner()
    act(() => { apiHealth.reportFailure('5xx') })
    const banner = screen.getByRole('alert')
    expect(banner).toBeInTheDocument()
    expect(banner.textContent).toMatch(/can't reach the API/i)
    expect(screen.getByRole('button', { name: /retry/i })).toBeInTheDocument()
  })

  it('Retry triggers queryClient.invalidateQueries and clears the banner on next success', async () => {
    const qc = new QueryClient()
    const invalidate = vi.spyOn(qc, 'invalidateQueries')
    renderBanner(qc)
    act(() => { apiHealth.reportFailure('5xx') })
    expect(screen.getByRole('alert')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /retry/i }))
    expect(invalidate).toHaveBeenCalled()
    act(() => { apiHealth.reportSuccess() })
    await waitFor(() => expect(screen.queryByRole('alert')).toBeNull())
  })
})

describe('client.req integration with apiHealth', () => {
  function mockFetchOnce(status: number, body = '{}') {
    const headers = new Headers({ 'content-type': 'application/json' })
    return vi.spyOn(globalThis, 'fetch').mockResolvedValueOnce(
      new Response(body, { status, headers }),
    )
  }

  it('reports failure when the API returns 5xx', async () => {
    mockFetchOnce(502, 'bad gateway')
    await expect(api.routers.list()).rejects.toThrow()
    expect(apiHealth.snapshot().unreachable).toBe(true)
  })

  it('reports failure on network error', async () => {
    vi.spyOn(globalThis, 'fetch').mockRejectedValueOnce(new TypeError('Failed to fetch'))
    await expect(api.routers.list()).rejects.toThrow()
    expect(apiHealth.snapshot().unreachable).toBe(true)
  })

  it('reports success and clears prior failure when the API returns 200', async () => {
    apiHealth.reportFailure('5xx')
    mockFetchOnce(200, '[]')
    await api.routers.list()
    expect(apiHealth.snapshot().unreachable).toBe(false)
  })

  it('does not flip to unreachable on 4xx (API is alive)', async () => {
    // 4xx other than the special 401/403 paths is a client-side error; the
    // API is reachable and answering. Don't trigger the banner.
    mockFetchOnce(400, 'bad request')
    await expect(api.routers.list()).rejects.toThrow()
    expect(apiHealth.snapshot().unreachable).toBe(false)
  })
})
