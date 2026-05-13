import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { CreateRouterResponse, RouterSummary } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    routers: {
      list: vi.fn(),
      create: vi.fn(),
      delete: vi.fn(),
    },
  },
}))

import { api } from '@/api/client'
import { RoutersPage } from './RoutersPage'

const existing: RouterSummary = {
  id: 'r1', name: 'home-gw', enrolled: false,
  lastSeenAt: null, lastEtag: null, createdAt: '2026-05-11T00:00:00Z',
  lastClockSkewSeconds: null,
}

const createResp: CreateRouterResponse = {
  routerId: 'r2',
  name: 'new-gw',
  enrollmentToken: 'tok_ABCDEFG_1234567890',
}

// Force the execCommand fallback path — that's what runs in the user's real deploy
// scenario (HTTP on a LAN IP, where navigator.clipboard is undefined and
// window.isSecureContext is false). Stubbing jsdom's real Clipboard instance
// from a test is brittle; testing the fallback path is what matters anyway,
// since it's the one that was silently failing before this fix.
function forceInsecureContext() {
  Object.defineProperty(globalThis, 'isSecureContext', {
    get: () => false, configurable: true,
  })
}

beforeEach(() => {
  vi.resetAllMocks()
  ;(api.routers.list as unknown as ReturnType<typeof vi.fn>).mockResolvedValue([existing])
  ;(api.routers.create as unknown as ReturnType<typeof vi.fn>).mockResolvedValue(createResp)
  forceInsecureContext()
})

async function openTokenDialog() {
  const user = userEvent.setup()
  render(<RoutersPage />)
  await screen.findByText('home-gw')
  await user.click(screen.getByRole('button', { name: /enroll router/i }))
  await user.type(screen.getByPlaceholderText('home-gw'), 'new-gw')
  await user.click(screen.getByRole('button', { name: /generate token/i }))
  await screen.findByText(/Save this token now/i)
  return user
}

describe('RoutersPage — copy to clipboard', () => {
  it('copies the enrollment token via execCommand fallback on HTTP/LAN deploys', async () => {
    const execCommand = vi.fn().mockReturnValue(true)
    Object.defineProperty(document, 'execCommand', {
      value: execCommand, configurable: true, writable: true,
    })

    const user = await openTokenDialog()
    await user.click(screen.getByRole('button', { name: /copy enrollment token/i }))

    expect(execCommand).toHaveBeenCalledWith('copy')
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /copy enrollment token/i }))
        .toHaveTextContent(/copied/i)
    })
  })

  it('shows a failure message when the copy fails so the user can fall back to manual select', async () => {
    Object.defineProperty(document, 'execCommand', {
      value: vi.fn().mockReturnValue(false),
      configurable: true, writable: true,
    })

    const user = await openTokenDialog()
    await user.click(screen.getByRole('button', { name: /copy enrollment token/i }))

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /copy enrollment token/i }))
        .toHaveTextContent(/copy failed/i)
    })
  })

  it('renders the token in a selectable readonly input as a manual-copy fallback', async () => {
    await openTokenDialog()
    const input = screen.getByDisplayValue(createResp.enrollmentToken) as HTMLInputElement
    expect(input.tagName).toBe('INPUT')
    expect(input.readOnly).toBe(true)
  })
})

// ── #312: router clock-skew banner ────────────────────────────────────────
describe('RoutersPage — clock skew banner', () => {
  const skewed = (s: number | null): RouterSummary => ({
    id: 'rs', name: 'skewed-gw', enrolled: true,
    lastSeenAt: '2026-05-13T12:00:00Z', lastEtag: 'sha256:x',
    createdAt: '2026-05-11T00:00:00Z', lastClockSkewSeconds: s,
  })

  it('shows a banner when |skew| > 60s with "ahead" for positive drift', async () => {
    (api.routers.list as unknown as ReturnType<typeof vi.fn>)
      .mockResolvedValue([skewed(180)])
    render(<RoutersPage />)
    await screen.findByText('skewed-gw')
    const banner = await screen.findByTestId('router-clock-skew-rs')
    expect(banner.textContent).toMatch(/180/)
    expect(banner.textContent).toMatch(/ahead/i)
  })

  it('shows "behind" for negative drift', async () => {
    (api.routers.list as unknown as ReturnType<typeof vi.fn>)
      .mockResolvedValue([skewed(-180)])
    render(<RoutersPage />)
    await screen.findByText('skewed-gw')
    const banner = await screen.findByTestId('router-clock-skew-rs')
    expect(banner.textContent).toMatch(/180/)
    expect(banner.textContent).toMatch(/behind/i)
  })

  it('does NOT show a banner when |skew| ≤ 60s', async () => {
    (api.routers.list as unknown as ReturnType<typeof vi.fn>)
      .mockResolvedValue([skewed(30)])
    render(<RoutersPage />)
    await screen.findByText('skewed-gw')
    expect(screen.queryByTestId('router-clock-skew-rs')).toBeNull()
  })

  it('does NOT show a banner when skew is null (never measured)', async () => {
    (api.routers.list as unknown as ReturnType<typeof vi.fn>)
      .mockResolvedValue([skewed(null)])
    render(<RoutersPage />)
    await screen.findByText('skewed-gw')
    expect(screen.queryByTestId('router-clock-skew-rs')).toBeNull()
  })
})
