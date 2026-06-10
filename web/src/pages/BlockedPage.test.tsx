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

// After #1615 the API (GET /api/blocked) is the only source of body copy and
// CTA kinds. The URL `?reason=` param is ignored. These tests mock the API
// directly via global fetch (the api client wraps fetch).
function mockBlockedInfo(body: object) {
  globalThis.fetch = vi.fn().mockResolvedValue({
    ok: true,
    status: 200,
    headers: new Headers(),
    json: () => Promise.resolve(body),
    text: () => Promise.resolve(JSON.stringify(body)),
  } as unknown as Response)
}

function mockBlockedInfoReject() {
  globalThis.fetch = vi.fn().mockRejectedValue(new Error('offline'))
}

let originalFetch: typeof fetch

beforeEach(() => {
  originalFetch = globalThis.fetch
})
afterEach(() => {
  globalThis.fetch = originalFetch
  vi.restoreAllMocks()
})

describe('BlockedPage — API-driven reason copy (#1615)', () => {
  it('renders paused copy when API returns reasonClass=paused', async () => {
    mockBlockedInfo({ blocked: true, reasonClass: 'paused' })
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'example.com' })
    await waitFor(() => expect(screen.getByText(/profile is paused/i)).toBeInTheDocument())
  })

  it('renders schedule copy when API returns reasonClass=schedule', async () => {
    mockBlockedInfo({ blocked: true, reasonClass: 'schedule' })
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'example.com' })
    await waitFor(() => expect(screen.getByText(/outside allowed time/i)).toBeInTheDocument())
  })

  it('renders out-of-time copy when API returns reasonClass=time_limit', async () => {
    mockBlockedInfo({ blocked: true, reasonClass: 'time_limit' })
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'youtube.com' })
    await waitFor(() => expect(screen.getByText(/out of time today/i)).toBeInTheDocument())
  })

  it('renders app-time-limit copy when API returns reasonClass=app_time_limit', async () => {
    mockBlockedInfo({ blocked: true, reasonClass: 'app_time_limit' })
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'youtube.com' })
    await waitFor(() => expect(screen.getByText(/out of time on this app/i)).toBeInTheDocument())
  })

  it('renders extra-blocked copy when API returns reasonClass=extra_blocked', async () => {
    mockBlockedInfo({ blocked: true, reasonClass: 'extra_blocked' })
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'example.com' })
    await waitFor(() => expect(screen.getByText(/blocked by your parent/i)).toBeInTheDocument())
  })

  it('renders category copy with category name from the API', async () => {
    mockBlockedInfo({ blocked: true, reasonClass: 'category', categoryName: 'Ads' })
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'ads.example.com' })
    await waitFor(() => expect(screen.getByText(/blocked category: ads/i)).toBeInTheDocument())
  })

  it('renders the profile name when present in the payload', async () => {
    mockBlockedInfo({ blocked: true, reasonClass: 'paused', profileName: 'Kids' })
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'example.com' })
    await waitFor(() => expect(screen.getByText(/for kids/i)).toBeInTheDocument())
  })

  it('shows the blocked hostname prominently', async () => {
    mockBlockedInfo({ blocked: true, reasonClass: 'time_limit' })
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'youtube.com' })
    expect(screen.getByText('youtube.com')).toBeInTheDocument()
  })
})

describe('BlockedPage — URL reason param is ignored (#1615)', () => {
  it('IGNORES URL ?reason=Paused when API returns reasonClass=category', async () => {
    mockBlockedInfo({ blocked: true, reasonClass: 'category', categoryName: 'Ads' })
    renderBlocked({
      mac: 'aa:bb:cc:11:22:33',
      host: 'ads.example.com',
      reason: 'Paused',
    })
    await waitFor(() => expect(screen.getByText(/blocked category: ads/i)).toBeInTheDocument())
    expect(screen.queryByText(/profile is paused/i)).not.toBeInTheDocument()
  })

  it('renders neutral copy on API error, NOT the URL-supplied reason text', async () => {
    mockBlockedInfoReject()
    renderBlocked({
      mac: 'aa:bb:cc:11:22:33',
      host: 'example.com',
      reason: 'Paused',
    })
    await waitFor(() => expect(screen.getByText(/access blocked/i)).toBeInTheDocument())
    expect(screen.queryByText(/profile is paused/i)).not.toBeInTheDocument()
  })

  it('schedule class never leaks an end time even if `until` is present in URL', async () => {
    mockBlockedInfo({ blocked: true, reasonClass: 'schedule' })
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'example.com', until: '07:00' })
    await waitFor(() => expect(screen.getByText(/outside allowed time/i)).toBeInTheDocument())
    expect(screen.queryByText(/07:00/)).not.toBeInTheDocument()
  })
})

describe('BlockedPage — ask-a-parent CTA (#960)', () => {
  it('still has no parent-login dialog (no kid-side credentials)', async () => {
    mockBlockedInfo({ blocked: true, reasonClass: 'time_limit' })
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'youtube.com' })
    await waitFor(() => expect(screen.getByTestId('ask-parent')).toBeInTheDocument())
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('shows an extension CTA when API returns reasonClass=time_limit', async () => {
    mockBlockedInfo({ blocked: true, reasonClass: 'time_limit' })
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'youtube.com' })
    await waitFor(() =>
      expect(screen.getByTestId('ask-parent-extension')).toBeInTheDocument(),
    )
  })

  it('shows an exemption CTA when API returns reasonClass=extra_blocked', async () => {
    mockBlockedInfo({ blocked: true, reasonClass: 'extra_blocked' })
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'foo.com' })
    await waitFor(() =>
      expect(screen.getByTestId('ask-parent-exemption')).toBeInTheDocument(),
    )
  })

  it('shows unpause + extension CTAs when API returns reasonClass=paused', async () => {
    mockBlockedInfo({ blocked: true, reasonClass: 'paused' })
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'example.com' })
    await waitFor(() => expect(screen.getByTestId('ask-parent-unpause')).toBeInTheDocument())
    expect(screen.getByTestId('ask-parent-extension')).toBeInTheDocument()
  })

  it('falls back to the static instruction when the mac param is missing', () => {
    // The block-page redirect always supplies mac=, but be tolerant: when it
    // is missing we cannot identify the kid's profile, so hide the CTA and
    // render the older static message instead of posting an anonymous row.
    renderBlocked({ host: 'youtube.com' })
    expect(screen.queryByTestId('ask-parent')).not.toBeInTheDocument()
    expect(screen.getByText(/ask a parent/i)).toBeInTheDocument()
  })

  describe('posting the request', () => {
    it('POSTs the kid-known (mac, host, kind) and shows a confirmation', async () => {
      mockBlockedInfo({ blocked: true, reasonClass: 'time_limit' })
      const create = vi
        .spyOn(api.alerts, 'createAccessRequest')
        .mockResolvedValue({} as never)
      renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'youtube.com' })
      await waitFor(() =>
        expect(screen.getByTestId('ask-parent-extension')).toBeInTheDocument(),
      )
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
      mockBlockedInfo({ blocked: true, reasonClass: 'time_limit' })
      vi.spyOn(api.alerts, 'createAccessRequest').mockRejectedValue(new Error('offline'))
      renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'youtube.com' })
      await waitFor(() =>
        expect(screen.getByTestId('ask-parent-extension')).toBeInTheDocument(),
      )
      fireEvent.click(screen.getByTestId('ask-parent-extension'))
      expect(await screen.findByTestId('ask-parent-error')).toHaveTextContent('offline')
    })
  })
})
// #335: a restricted kid sees today's used / cap / remaining on the block
// page so they understand *why* their time is gone. Hidden when no cap is
// configured so unenrolled / adult-profile cases stay clean.
describe('BlockedPage — usage panel (#335)', () => {
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
