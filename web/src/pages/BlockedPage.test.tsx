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
    mockBlockedInfo({ blocked: true, reasonClass: 'time_limit', profileName: 'Kids' })
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

  // #2847: a shared device nobody has checked in. The Check in action itself is #2850.
  it('renders shared-device copy when API returns reasonClass=checked_out', async () => {
    mockBlockedInfo({ blocked: true, reasonClass: 'checked_out' })
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'example.com' })
    await waitFor(() =>
      expect(screen.getByText(/shared device.*check it in/i)).toBeInTheDocument(),
    )
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
    mockBlockedInfo({ blocked: true, reasonClass: 'time_limit', profileName: 'Kids' })
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'youtube.com' })
    expect(screen.getByText('youtube.com')).toBeInTheDocument()
  })
})

describe('BlockedPage — unrecognised reasonClass (#2846)', () => {
  // The API can ship a reasonClass this SPA build predates. It must still render the generic
  // blocked copy and every ask-a-parent option, not a blank body or the raw class string.
  // #2867: the payload carries a profileName. Without one no request could be granted, so the
  // page hides the CTAs regardless of class (covered below); this test is about the class only.
  it('renders generic blocked copy and all CTAs for a reasonClass it does not know', async () => {
    mockBlockedInfo({ blocked: true, reasonClass: 'not_yet_known_reason', profileName: 'Kids' })
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'example.com' })
    await waitFor(() => expect(screen.getByText('Access blocked.')).toBeInTheDocument())
    expect(screen.queryByText(/not_yet_known_reason/)).not.toBeInTheDocument()
    expect(screen.getByTestId('ask-parent-extension')).toBeInTheDocument()
    expect(screen.getByTestId('ask-parent-exemption')).toBeInTheDocument()
    expect(screen.getByTestId('ask-parent-unpause')).toBeInTheDocument()
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
    mockBlockedInfo({ blocked: true, reasonClass: 'time_limit', profileName: 'Kids' })
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'youtube.com' })
    await waitFor(() => expect(screen.getByTestId('ask-parent')).toBeInTheDocument())
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
  })

  it('shows an extension CTA when API returns reasonClass=time_limit', async () => {
    mockBlockedInfo({ blocked: true, reasonClass: 'time_limit', profileName: 'Kids' })
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'youtube.com' })
    await waitFor(() =>
      expect(screen.getByTestId('ask-parent-extension')).toBeInTheDocument(),
    )
  })

  it('shows an exemption CTA when API returns reasonClass=extra_blocked', async () => {
    mockBlockedInfo({ blocked: true, reasonClass: 'extra_blocked', profileName: 'Kids' })
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'foo.com' })
    await waitFor(() =>
      expect(screen.getByTestId('ask-parent-exemption')).toBeInTheDocument(),
    )
  })

  it('shows unpause + extension CTAs when API returns reasonClass=paused', async () => {
    mockBlockedInfo({ blocked: true, reasonClass: 'paused', profileName: 'Kids' })
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'example.com' })
    await waitFor(() => expect(screen.getByTestId('ask-parent-unpause')).toBeInTheDocument())
    expect(screen.getByTestId('ask-parent-extension')).toBeInTheDocument()
  })

  // #2847: a checked-out shared device has no profile, so none of the access-request kinds can be
  // granted (AlertRoutes rejects extension / exemption / unpause without a profile). Offer none;
  // the Check in action is #2850.
  it('offers no ask-a-parent CTAs when API returns reasonClass=checked_out', async () => {
    mockBlockedInfo({ blocked: true, reasonClass: 'checked_out' })
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'example.com' })
    await waitFor(() => expect(screen.getByText(/shared device/i)).toBeInTheDocument())
    expect(screen.queryByTestId('ask-parent')).not.toBeInTheDocument()
  })

  // #2850: the Check in action. It leads to the dashboard's shared-devices card with this device
  // pre-selected (design §15 Q1: the MAC only pre-selects; the check-in itself is the normal flow).
  // An unauthenticated child goes through login first and comes back to it (RequireAuth/LoginPage).
  it('offers a Check in action for reasonClass=checked_out that leads to the check-in flow', async () => {
    mockBlockedInfo({ blocked: true, reasonClass: 'checked_out' })
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'example.com' })
    const link = await screen.findByTestId('block-checkin')
    expect(link).toHaveTextContent('Check in')
    expect(link).toHaveAttribute('href', `/dashboard?checkin=${encodeURIComponent('aa:bb:cc:11:22:33')}`)
  })

  it('offers no Check in action for other reasons', async () => {
    mockBlockedInfo({ blocked: true, reasonClass: 'paused', profileName: 'Kids' })
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'example.com' })
    await waitFor(() => expect(screen.getByText(/profile is paused/i)).toBeInTheDocument())
    expect(screen.queryByTestId('block-checkin')).not.toBeInTheDocument()
  })

  // #2867: a device with no profile under an unmanaged-device `block` policy. The API reports it
  // as the generic `blocked` class with no profileName; the access request would be stored without
  // a profile, and AlertRoutes rejects approving any kind without one. Offer none, and tell the
  // child what would actually help.
  it('offers no ask-a-parent CTAs when the blocked device has no profile', async () => {
    mockBlockedInfo({ blocked: true, reasonClass: 'blocked' })
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'example.com' })
    await waitFor(() =>
      expect(screen.getByText(/ask a parent to set up this device/i)).toBeInTheDocument(),
    )
    expect(screen.queryByTestId('ask-parent')).not.toBeInTheDocument()
  })

  // #2867: the gate is the missing profile, not the class — a known class with no profile is
  // just as ungrantable.
  it('offers no ask-a-parent CTAs for a known reasonClass when there is no profile', async () => {
    mockBlockedInfo({ blocked: true, reasonClass: 'extra_blocked' })
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'example.com' })
    await waitFor(() => expect(screen.getByText(/blocked by your parent/i)).toBeInTheDocument())
    expect(screen.queryByTestId('ask-parent')).not.toBeInTheDocument()
  })

  // #2867: the profiled counterpart — the same generic class with a profile still gets every CTA.
  it('offers all ask-a-parent CTAs for the generic blocked class when the device has a profile', async () => {
    mockBlockedInfo({ blocked: true, reasonClass: 'blocked', profileName: 'Kids' })
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'example.com' })
    await waitFor(() => expect(screen.getByTestId('ask-parent-extension')).toBeInTheDocument())
    expect(screen.getByTestId('ask-parent-exemption')).toBeInTheDocument()
    expect(screen.getByTestId('ask-parent-unpause')).toBeInTheDocument()
    expect(screen.queryByText(/set up this device/i)).not.toBeInTheDocument()
  })

  // #2867 keeps #2847's copy: a checked-out shared device has no profile either, but its fix is
  // checking it in, not setting it up.
  it('keeps the shared-device copy, not the set-up copy, for reasonClass=checked_out', async () => {
    mockBlockedInfo({ blocked: true, reasonClass: 'checked_out' })
    renderBlocked({ mac: 'aa:bb:cc:11:22:33', host: 'example.com' })
    await waitFor(() => expect(screen.getByText(/shared device/i)).toBeInTheDocument())
    expect(screen.queryByText(/set up this device/i)).not.toBeInTheDocument()
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
      mockBlockedInfo({ blocked: true, reasonClass: 'time_limit', profileName: 'Kids' })
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
      mockBlockedInfo({ blocked: true, reasonClass: 'time_limit', profileName: 'Kids' })
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
