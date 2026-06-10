import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'

import { BlockedPage } from './BlockedPage'
import { api } from '@/api/client'

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

describe('BlockedPage — ask-a-parent CTA (#960)', () => {
  it('still has no parent-login dialog (no kid-side credentials)', () => {
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'youtube.com', reason: 'TimeLimit' })
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('shows an extension CTA for TimeLimit blocks', () => {
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'youtube.com', reason: 'TimeLimit' })
    expect(screen.getByTestId('ask-parent-extension')).toBeInTheDocument()
  })

  it('shows an exemption CTA for ExtraBlocked / category blocks', () => {
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'foo.com', reason: 'ExtraBlocked' })
    expect(screen.getByTestId('ask-parent-exemption')).toBeInTheDocument()
  })

  it('shows unpause + extension CTAs for Paused', () => {
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'example.com', reason: 'Paused' })
    expect(screen.getByTestId('ask-parent-unpause')).toBeInTheDocument()
    expect(screen.getByTestId('ask-parent-extension')).toBeInTheDocument()
  })

  it('falls back to the static instruction when the mac param is missing', () => {
    // The block-page redirect always supplies mac=, but be tolerant: when it
    // is missing we cannot identify the kid's profile, so hide the CTA and
    // render the older static message instead of posting an anonymous row.
    renderBlocked({ host: 'youtube.com', reason: 'TimeLimit' })
    expect(screen.queryByTestId('ask-parent')).not.toBeInTheDocument()
    expect(screen.getByText(/ask a parent/i)).toBeInTheDocument()
  })

  describe('posting the request', () => {
    beforeEach(() => vi.restoreAllMocks())

    it('POSTs the kid-known (mac, host, kind) and shows a confirmation', async () => {
      const create = vi
        .spyOn(api.alerts, 'createAccessRequest')
        .mockResolvedValue({} as never)
      renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'youtube.com', reason: 'TimeLimit' })
      fireEvent.click(screen.getByTestId('ask-parent-extension'))
      await waitFor(() => expect(create).toHaveBeenCalledTimes(1))
      expect(create).toHaveBeenCalledWith({
        mac: 'aa:bb:cc:11:22:33',
        host: 'youtube.com',
        kind: 'extension',
        note: undefined,
      })
      expect(await screen.findByTestId('ask-parent-sent')).toBeInTheDocument()
    })

    it('surfaces a network error inline without leaving the CTA disabled forever', async () => {
      vi.spyOn(api.alerts, 'createAccessRequest').mockRejectedValue(new Error('offline'))
      renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'youtube.com', reason: 'TimeLimit' })
      fireEvent.click(screen.getByTestId('ask-parent-extension'))
      expect(await screen.findByTestId('ask-parent-error')).toHaveTextContent('offline')
    })
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

  // #335: a restricted kid sees today's used / cap / remaining on the block
  // page so they understand *why* their time is gone. Hidden when no cap is
  // configured so unenrolled / adult-profile cases stay clean.
  it("shows today's usage when the API includes used/cap minutes", async () => {
    mockBlockedInfo({
      blocked: true,
      reasonClass: 'time_limit',
      profileName: 'Kids',
      usedMinutes: 90,
      dailyLimitMinutes: 120,
      extensionMinutes: 0,
      remainingMinutes: 30,
    })
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'youtube.com' })
    await waitFor(() => expect(screen.getByTestId('block-usage')).toBeInTheDocument())
    expect(screen.getByTestId('block-usage-used')).toHaveTextContent('Used: 90 / 120 min')
    expect(screen.getByTestId('block-usage-remaining')).toHaveTextContent('30 min left')
  })

  it('hides the usage panel when the API has no cap (unenrolled / no daily limit)', async () => {
    mockBlockedInfo({ blocked: false })
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'example.com' })
    await waitFor(() =>
      expect(screen.getByText(/not blocked for this device/i)).toBeInTheDocument(),
    )
    expect(screen.queryByTestId('block-usage')).not.toBeInTheDocument()
  })
})
