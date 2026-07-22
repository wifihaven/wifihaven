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

  it('makes clear EVERY router (incl. GL.iNet) needs a vanilla-OpenWRT flash — not just mainline', () => {
    // #2364 — the old copy said GL.iNet "ships an OpenWRT-based firmware out of the box", which
    // wrongly implied those boxes were ready to go. Stock GL firmware is NOT supported (#2304/
    // #2363); it still needs a vanilla-OpenWRT flash. The prerequisite must say so.
    renderPage()
    // Appears in the prerequisite banner and reinforced on each router card.
    expect(screen.getAllByText(/flash vanilla OpenWRT first/i).length).toBeGreaterThan(0)
    // Must not resurrect the misleading "ships an OpenWRT-based firmware out of the box; other
    // hardware needs a flash" framing that implied GL.iNet boxes were ready as-is.
    expect(
      screen.queryByText(/ships an OpenWRT-based firmware\s+out of the box; other hardware/i),
    ).not.toBeInTheDocument()
  })

  it('links each router to its per-router flash guide on the marketing site', () => {
    // #2364 — the guides live on wifihaven.net/install/* (mirroring docs/install-*.md) and are
    // linked from every card so the operator can actually flash the box.
    renderPage()
    const links = screen.getAllByRole('link', { name: /Flash .* install guide/i })
    expect(links.length).toBe(3)
    for (const link of links) {
      expect(link.getAttribute('href')).toMatch(/^https:\/\/wifihaven\.net\/install\//)
      expect(link).toHaveAttribute('target', '_blank')
      expect(link.getAttribute('rel')).toContain('noopener')
    }
    // The three specific per-router guide slugs.
    const hrefs = links.map(l => l.getAttribute('href'))
    expect(hrefs).toEqual(
      expect.arrayContaining([
        'https://wifihaven.net/install/flint2',
        'https://wifihaven.net/install/flint',
        'https://wifihaven.net/install/wax206',
      ]),
    )
  })

  it('links each suggested router to Amazon with the wifihaven-20 affiliate tag', () => {
    // #2301 — every router card carries a "View on Amazon" link. Amazon Associates requires
    // the tag on every link; rel="sponsored" flags the paid relationship.
    renderPage()
    const links = screen.getAllByRole('link', { name: /View on Amazon/i })
    expect(links.length).toBeGreaterThan(0)
    for (const link of links) {
      const href = link.getAttribute('href') ?? ''
      expect(href).toContain('tag=wifihaven-20')
      expect(href).toMatch(/^https:\/\/www\.amazon\.com\//)
      expect(link).toHaveAttribute('target', '_blank')
      expect(link.getAttribute('rel')).toContain('sponsored')
      expect(link.getAttribute('rel')).toContain('noopener')
    }
  })

  it('shows the Amazon Associates affiliate disclosure', () => {
    // #2301 — a visible disclosure near the links is an Associates requirement.
    renderPage()
    expect(
      screen.getByText(/We may earn a small commission from these links, at no cost to you/i),
    ).toBeInTheDocument()
  })

  it('no longer lists the discontinued Belkin RT3200 / Linksys E8450', () => {
    // #2301 — dropped as no longer reliably available on Amazon; replaced by the Netgear
    // WAX206 (same MediaTek MT7622 mainline-OpenWRT platform).
    renderPage()
    expect(screen.queryByText(/RT3200|E8450/i)).not.toBeInTheDocument()
    expect(screen.getByText(/WAX206/i)).toBeInTheDocument()
  })

  it('deep-links to the routers add-router dialog to mint the enrollment token', () => {
    renderPage()
    const link = screen.getByRole('link', { name: /enrollment token|routers|enroll/i })
    // #2235: ?add=1 auto-opens the enroll dialog so there are no extra clicks.
    expect(link).toHaveAttribute('href', '/routers?add=1')
  })
})
