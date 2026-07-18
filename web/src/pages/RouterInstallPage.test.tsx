import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'

// #2234 — the copy affordance delegates to the shared clipboard helper (secure-context
// Clipboard API + plain-http execCommand fallback). Mock it so we can assert the copy
// wiring without a real clipboard in jsdom.
vi.mock('@/lib/clipboard', () => ({
  copyToClipboard: vi.fn().mockResolvedValue(true),
}))

import { copyToClipboard } from '@/lib/clipboard'
import { RouterInstallPage, ROUTER_INSTALL_COMMAND } from './RouterInstallPage'

const copyMock = copyToClipboard as unknown as ReturnType<typeof vi.fn>

beforeEach(() => {
  vi.clearAllMocks()
})

function renderPage() {
  return render(
    <MemoryRouter>
      <RouterInstallPage />
    </MemoryRouter>,
  )
}

describe('RouterInstallPage — #2234 post-registration router-install page', () => {
  it('shows the real OpenWRT install one-liner (verified against openwrt/install.sh)', () => {
    renderPage()
    // The verified one-liner uses uclient-fetch (present on stock OpenWRT) to fetch and
    // pipe install.sh — NOT curl (which may not be installed on the router yet).
    expect(ROUTER_INSTALL_COMMAND).toContain('uclient-fetch')
    expect(ROUTER_INSTALL_COMMAND).toContain('openwrt/install.sh')
    expect(screen.getByText(ROUTER_INSTALL_COMMAND)).toBeInTheDocument()
  })

  it('copies the install command via the shared clipboard helper', async () => {
    renderPage()
    fireEvent.click(screen.getByTestId('copy-install-command'))
    await waitFor(() => expect(copyMock).toHaveBeenCalledWith(ROUTER_INSTALL_COMMAND))
  })

  it('lists suggested OpenWRT hardware including the reference router (Flint 2)', () => {
    renderPage()
    // The Flint 2 is the documented reference hardware (docs/install-flint2.md).
    expect(screen.getByText(/Flint 2/i)).toBeInTheDocument()
    // The OpenWRT flashing prerequisite must be called out.
    expect(screen.getByText(/Prerequisite/i)).toBeInTheDocument()
    expect(screen.getAllByText(/OpenWRT/i).length).toBeGreaterThan(0)
  })

  it('deep-links to the routers add-router dialog to mint the enrollment token', () => {
    renderPage()
    const link = screen.getByRole('link', { name: /enrollment token|routers|enroll/i })
    // #2235: ?add=1 auto-opens the enroll dialog so there are no extra clicks.
    expect(link).toHaveAttribute('href', '/routers?add=1')
  })
})
