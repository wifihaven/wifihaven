import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { BillingStatusResponse } from '@/types/api'

vi.mock('@/api/client', () => ({
  api: {
    billing: {
      status: vi.fn(),
      checkout: vi.fn(),
    },
  },
}))

vi.mock('@/hooks/useAuth', () => ({
  useAuth: () => ({ isAdmin: mockIsAdmin }),
}))

import { api } from '@/api/client'
import { ConversionBanner } from './ConversionBanner'
import { withQuery } from '@/test/queryWrapper'

let mockIsAdmin = true

function billing(over: Partial<BillingStatusResponse>): BillingStatusResponse {
  return {
    status: 'beta',
    founding: true,
    priceId: null,
    currentPeriodEnd: null,
    lapsedAt: null,
    flipWindowOpen: false,
    flipDate: null,
    ...over,
  }
}

describe('ConversionBanner', () => {
  beforeEach(() => {
    mockIsAdmin = true
    sessionStorage.clear()
    vi.clearAllMocks()
  })

  it('shows for an unconverted household inside an open flip window, with the flip date', async () => {
    vi.mocked(api.billing.status).mockResolvedValue(
      billing({ status: 'beta', flipWindowOpen: true, flipDate: '2026-09-01T00:00:00Z' }),
    )
    render(withQuery(<ConversionBanner />))
    await waitFor(() => expect(screen.getByText(/free beta ends/i)).toBeInTheDocument())
    expect(screen.getByRole('button', { name: /subscribe/i })).toBeInTheDocument()
  })

  it('renders nothing during pure beta (window not open)', async () => {
    vi.mocked(api.billing.status).mockResolvedValue(billing({ status: 'beta', flipWindowOpen: false }))
    const { container } = render(withQuery(<ConversionBanner />))
    // let the query settle
    await waitFor(() => expect(api.billing.status).toHaveBeenCalled())
    expect(container).toBeEmptyDOMElement()
  })

  it('renders nothing for an active household', async () => {
    vi.mocked(api.billing.status).mockResolvedValue(billing({ status: 'active', flipWindowOpen: true }))
    const { container } = render(withQuery(<ConversionBanner />))
    await waitFor(() => expect(api.billing.status).toHaveBeenCalled())
    expect(container).toBeEmptyDOMElement()
  })

  it('dismisses per session and does not re-render after dismiss', async () => {
    vi.mocked(api.billing.status).mockResolvedValue(
      billing({ status: 'beta', flipWindowOpen: true, flipDate: '2026-09-01T00:00:00Z' }),
    )
    render(withQuery(<ConversionBanner />))
    await waitFor(() => expect(screen.getByText(/free beta ends/i)).toBeInTheDocument())
    await userEvent.click(screen.getByRole('button', { name: /dismiss/i }))
    await waitFor(() => expect(screen.queryByText(/free beta ends/i)).not.toBeInTheDocument())
    expect(sessionStorage.getItem('wh-conversion-banner-dismissed')).toBe('1')
  })

  it('does not query billing for a non-admin', async () => {
    mockIsAdmin = false
    vi.mocked(api.billing.status).mockResolvedValue(billing({ status: 'beta', flipWindowOpen: true }))
    const { container } = render(withQuery(<ConversionBanner />))
    expect(container).toBeEmptyDOMElement()
    expect(api.billing.status).not.toHaveBeenCalled()
  })
})
