import { describe, it, expect, vi } from 'vitest'
import { act, render, screen, renderHook } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, useNavigate } from 'react-router-dom'
import { NewBuildBanner, useCheckOnWsReconnect } from './NewBuildBanner'
import { createBuildChecker, type BuildChecker } from '@/lib/buildCheck'
import type { WsStatus } from '@/api/wsClient'

const RUNNING = '<script type="module" src="/assets/index-OLD.js"></script>'
const NEWER = '<script type="module" src="/assets/index-NEW.js"></script>'

function checkerServing(html: string): BuildChecker {
  return createBuildChecker({
    running: '/assets/index-OLD.js',
    fetchIndex: async () => new Response(html, { status: 200 }),
    log: { info: vi.fn(), warn: vi.fn(), error: vi.fn() },
  })
}

let navigateTo: (to: string) => void = () => {}
function NavHandle() {
  const navigate = useNavigate()
  navigateTo = navigate
  return null
}

function renderBanner(checker: BuildChecker, reload = vi.fn(), initial = '/dashboard') {
  render(
    <MemoryRouter initialEntries={[initial]}>
      <NavHandle />
      <NewBuildBanner checker={checker} reload={reload} />
    </MemoryRouter>,
  )
  return reload
}

describe('NewBuildBanner (#2903)', () => {
  it('renders nothing while the running bundle is current', async () => {
    const checker = checkerServing(RUNNING)
    renderBanner(checker)
    await act(() => checker.check())
    expect(screen.queryByTestId('new-build-banner')).toBeNull()
  })

  it('shows a non-blocking reload banner once a newer build is detected', async () => {
    const checker = checkerServing(NEWER)
    const reload = renderBanner(checker)
    await act(() => checker.check())
    const banner = screen.getByTestId('new-build-banner')
    expect(banner).toHaveAttribute('role', 'status')
    expect(banner).toHaveTextContent(/new version/i)
    await userEvent.click(screen.getByRole('button', { name: /reload/i }))
    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('reloads on the next page navigation once stale, so the new page loads the new bundle', async () => {
    const checker = checkerServing(NEWER)
    const reload = renderBanner(checker)
    await act(() => checker.check())
    expect(reload).not.toHaveBeenCalled()
    act(() => navigateTo('/devices'))
    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('does not reload on a query-string-only change (filters typed into the URL)', async () => {
    const checker = checkerServing(NEWER)
    const reload = renderBanner(checker)
    await act(() => checker.check())
    act(() => navigateTo('/dashboard?filter=abc'))
    expect(reload).not.toHaveBeenCalled()
  })

  it('does not reload on navigation while the bundle is current', async () => {
    const checker = checkerServing(RUNNING)
    const reload = renderBanner(checker)
    await act(() => checker.check())
    act(() => navigateTo('/devices'))
    expect(reload).not.toHaveBeenCalled()
  })

  it('does not show the banner when the check itself is broken (that goes to the console)', async () => {
    const checker = checkerServing('<html>maintenance</html>')
    renderBanner(checker)
    await act(() => checker.check())
    expect(checker.snapshot().status).toBe('error')
    expect(screen.queryByTestId('new-build-banner')).toBeNull()
  })

  it('checks when the tab becomes visible again', async () => {
    const checker = checkerServing(NEWER)
    const spy = vi.spyOn(checker, 'check')
    renderBanner(checker)
    expect(spy).not.toHaveBeenCalled()
    await act(async () => { document.dispatchEvent(new Event('visibilitychange')) })
    expect(spy).toHaveBeenCalled()
  })

  it('checks when the window regains focus', async () => {
    const checker = checkerServing(NEWER)
    const spy = vi.spyOn(checker, 'check')
    renderBanner(checker)
    await act(async () => { window.dispatchEvent(new Event('focus')) })
    expect(spy).toHaveBeenCalled()
  })
})

describe('useCheckOnWsReconnect (#2903)', () => {
  it('checks when the ws goes reconnecting → live, not on the first connect', () => {
    const check = vi.fn(async () => {})
    const { rerender } = renderHook(({ s }: { s: WsStatus }) => useCheckOnWsReconnect(s, check), {
      initialProps: { s: 'offline' as WsStatus },
    })
    rerender({ s: 'reconnecting' })
    rerender({ s: 'live' })
    expect(check).not.toHaveBeenCalled()

    rerender({ s: 'reconnecting' })
    rerender({ s: 'live' })
    expect(check).toHaveBeenCalledTimes(1)
  })
})
