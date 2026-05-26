import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'

import { BlockedPage } from './BlockedPage'

function renderBlocked(params: Record<string, string>) {
  const search = '?' + new URLSearchParams(params).toString()
  return render(
    <MemoryRouter initialEntries={[`/blocked${search}`]}>
      <BlockedPage />
    </MemoryRouter>,
  )
}

describe('BlockedPage — reason display', () => {
  // Reason strings here mirror MacBlockReason wire format
  // (shared/src/Models.scala: Paused / Schedule / TimeLimit / Manual).
  it('shows paused-profile copy for reason=Paused (#437)', () => {
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'example.com', reason: 'Paused' })
    expect(screen.getByText(/profile is paused/i)).toBeInTheDocument()
  })

  it('shows schedule end time for reason=Schedule with until param', () => {
    renderBlocked({
      mac: 'aa:bb:cc:11:22:33',
      host: 'example.com',
      reason: 'Schedule',
      until: '07:00',
    })
    expect(screen.getByText(/07:00/)).toBeInTheDocument()
  })

  // #959 Q4: don't leak minute counts — copy is just "out of time today".
  it('shows out-of-time copy for reason=TimeLimit', () => {
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'youtube.com', reason: 'TimeLimit' })
    expect(screen.getByText(/out of time today/i)).toBeInTheDocument()
  })

  it('shows manual-block copy for reason=Manual', () => {
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'example.com', reason: 'Manual' })
    expect(screen.getByText(/blocked by a parent/i)).toBeInTheDocument()
  })

  it('shows extra-blocked copy for reason=ExtraBlocked (#576)', () => {
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'example.com', reason: 'ExtraBlocked' })
    expect(screen.getByText(/blocked by the household/i)).toBeInTheDocument()
  })

  it('shows category message for reason=category:ads', () => {
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'ads.example.com', reason: 'category:ads' })
    expect(screen.getByText(/blocked category/i)).toBeInTheDocument()
  })

  it('shows a non-empty fallback for unknown reason instead of leaving it blank (#437)', () => {
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'example.com', reason: 'whatever' })
    expect(screen.getByText(/access blocked/i)).toBeInTheDocument()
  })

  it('shows the blocked hostname prominently', () => {
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'youtube.com', reason: 'TimeLimit' })
    expect(screen.getByText('youtube.com')).toBeInTheDocument()
  })
})

describe('BlockedPage — no request-extension UI (#577)', () => {
  it('does not show a "Request extension" button', () => {
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'youtube.com', reason: 'TimeLimit' })
    expect(screen.queryByRole('button', { name: /request extension/i })).not.toBeInTheDocument()
  })

  it('does not show a parent-login dialog', () => {
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'youtube.com', reason: 'TimeLimit' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('shows a static "ask a parent" instruction instead', () => {
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'youtube.com', reason: 'TimeLimit' })
    expect(screen.getByText(/ask a parent/i)).toBeInTheDocument()
  })
})

// #959: API-driven path overrides the legacy `reason` query param when the
// SPA can reach GET /api/blocked.
describe('BlockedPage — API-driven reason (#959)', () => {
  let originalFetch: typeof fetch

  beforeEach(() => {
    originalFetch = globalThis.fetch
  })
  afterEach(() => {
    globalThis.fetch = originalFetch
  })

  function mockBlockedInfo(body: object) {
    globalThis.fetch = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      headers: new Headers(),
      json: () => Promise.resolve(body),
      text: () => Promise.resolve(JSON.stringify(body)),
    } as unknown as Response)
  }

  it('renders category name from the API payload', async () => {
    mockBlockedInfo({ blocked: true, reasonClass: 'category', categoryName: 'Ads' })
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'ads.example.com' })
    await waitFor(() => expect(screen.getByText(/blocked category: ads/i)).toBeInTheDocument())
  })

  it('renders the profile name when present in the payload', async () => {
    mockBlockedInfo({ blocked: true, reasonClass: 'paused', profileName: 'Kids' })
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'example.com' })
    await waitFor(() => expect(screen.getByText(/for kids/i)).toBeInTheDocument())
  })

  it('schedule class never leaks an end time even if `until` is present in URL', async () => {
    mockBlockedInfo({ blocked: true, reasonClass: 'schedule' })
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'example.com', until: '07:00' })
    await waitFor(() => expect(screen.getByText(/outside allowed time/i)).toBeInTheDocument())
    expect(screen.queryByText(/07:00/)).not.toBeInTheDocument()
  })
})
