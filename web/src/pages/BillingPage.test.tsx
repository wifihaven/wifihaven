import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import userEvent from '@testing-library/user-event'
import { withQuery } from '@/test/queryWrapper'

vi.mock('@/api/client', () => ({
  api: {
    billing: {
      status: vi.fn(),
      checkout: vi.fn(),
      portal: vi.fn(),
    },
  },
}))

import { api } from '@/api/client'
import { BillingPage } from './BillingPage'
import type { BillingStatusResponse } from '@/types/api'

const statusMock = api.billing.status as unknown as ReturnType<typeof vi.fn>
const checkoutMock = api.billing.checkout as unknown as ReturnType<typeof vi.fn>
const portalMock = api.billing.portal as unknown as ReturnType<typeof vi.fn>

function billing(over: Partial<BillingStatusResponse> = {}): BillingStatusResponse {
  return {
    status: 'beta',
    founding: true,
    priceId: null,
    currentPeriodEnd: null,
    lapsedAt: null,
    ...over,
  }
}

function renderPage() {
  return render(withQuery(<MemoryRouter><BillingPage /></MemoryRouter>))
}

beforeEach(() => {
  vi.resetAllMocks()
})

describe('BillingPage', () => {
  it('shows a loading state before status resolves (no fake 0/placeholder)', () => {
    statusMock.mockReturnValue(new Promise(() => {})) // never resolves
    renderPage()
    expect(screen.getByLabelText(/Loading billing status/i)).toBeInTheDocument()
  })

  it('shows an error affordance when the status query fails', async () => {
    statusMock.mockRejectedValue(new Error('boom'))
    renderPage()
    expect(await screen.findByText(/Could not load billing status/i)).toBeInTheDocument()
  })

  it('surfaces the founding plan and a Subscribe button for a beta household in the flip window', async () => {
    // #2137 (A1): the Subscribe CTA shows once the flip window is open.
    statusMock.mockResolvedValue(billing({ status: 'beta', founding: true, flipWindowOpen: true }))
    renderPage()
    expect(await screen.findByText(/Founding — 40% off, forever/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Subscribe/ })).toBeInTheDocument()
  })

  it('a pure-beta household (flip window not open) sees NO Subscribe CTA (A1)', async () => {
    statusMock.mockResolvedValue(billing({ status: 'beta', founding: true, flipWindowOpen: false }))
    renderPage()
    expect(await screen.findByText(/free beta/i)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Subscribe/ })).not.toBeInTheDocument()
  })

  it('checkout redirects the browser to the returned Stripe URL', async () => {
    statusMock.mockResolvedValue(billing({ status: 'beta', flipWindowOpen: true }))
    checkoutMock.mockResolvedValue({ url: 'https://checkout.stripe.test/sess_1' })
    const original = window.location
    // jsdom: make location.href assignable + observable
    Object.defineProperty(window, 'location', { value: { href: '' }, writable: true })
    const user = userEvent.setup()
    renderPage()
    await user.click(await screen.findByRole('button', { name: /Subscribe/ }))
    await waitFor(() => expect(window.location.href).toBe('https://checkout.stripe.test/sess_1'))
    Object.defineProperty(window, 'location', { value: original, writable: true })
  })

  it('an active household sees Manage billing (portal) instead of Subscribe', async () => {
    statusMock.mockResolvedValue(billing({ status: 'active', founding: false, currentPeriodEnd: '2026-08-01T00:00:00Z' }))
    portalMock.mockResolvedValue({ url: 'https://portal.stripe.test/cus_1' })
    renderPage()
    expect(await screen.findByRole('button', { name: /Manage billing/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /^Subscribe$/ })).not.toBeInTheDocument()
  })

  it('a lapsed household sees the lapse notice and a Subscribe button (no gating language)', async () => {
    statusMock.mockResolvedValue(billing({ status: 'lapsed', founding: true, lapsedAt: '2026-07-01T00:00:00Z' }))
    renderPage()
    expect(await screen.findByText(/Your subscription has lapsed/i)).toBeInTheDocument()
    expect(screen.getByText(/network keeps running/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /Subscribe/ })).toBeInTheDocument()
  })
})
