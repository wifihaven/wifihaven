import { useEffect, useRef, useSyncExternalStore } from 'react'
import { useLocation } from 'react-router-dom'
import { getBuildChecker, type BuildChecker, type BuildCheckSnapshot } from '@/lib/buildCheck'
import { useWsLive } from '@/hooks/useWs'
import type { WsStatus } from '@/api/wsClient'

// #2903 — tells a tab that a newer SPA build is live. Two responses, no forced reload:
//
// - A banner with a Reload button, so a user looking at the page can pick up the new build now.
// - A full page load on the next change of route (pathname). The router has already moved to
//   the new URL and the page being left is unmounting anyway, so nothing typed into it survives
//   either way; reloading there costs the user nothing they would otherwise keep. Query-string
//   changes do not count, because some pages write filters into the URL as the user types.
//
// The page is never reloaded out from under the user while they stay on it.

/** Background cadence; focus, visibility and ws reconnect cover the common cases sooner. */
const BACKGROUND_CHECK_MS = 10 * 60_000

const CURRENT: BuildCheckSnapshot = { status: 'current' }
const noopSubscribe = () => () => {}

export function NewBuildBanner({
  checker: injected,
  reload = () => window.location.reload(),
}: {
  checker?: BuildChecker
  reload?: () => void
}) {
  const checker = injected ?? getBuildChecker()
  const snap = useSyncExternalStore(
    checker ? checker.subscribe : noopSubscribe,
    checker ? checker.snapshot : () => CURRENT,
  )
  const stale = snap.status === 'stale'

  useEffect(() => {
    if (!checker) return
    const onVisible = () => { if (document.visibilityState === 'visible') void checker.check() }
    const onFocus = () => { void checker.check() }
    const timer = window.setInterval(() => {
      if (document.visibilityState === 'visible') void checker.check(true)
    }, BACKGROUND_CHECK_MS)
    document.addEventListener('visibilitychange', onVisible)
    window.addEventListener('focus', onFocus)
    return () => {
      document.removeEventListener('visibilitychange', onVisible)
      window.removeEventListener('focus', onFocus)
      window.clearInterval(timer)
    }
  }, [checker])

  const { pathname } = useLocation()
  const lastPath = useRef(pathname)
  const reloadRef = useRef(reload)
  reloadRef.current = reload
  useEffect(() => {
    if (pathname === lastPath.current) return
    lastPath.current = pathname
    if (stale) reloadRef.current()
  }, [pathname, stale])

  if (!stale) return null
  return (
    <div
      role="status"
      aria-live="polite"
      // Fixed rather than in flow: inserting a bar above the page lets the browser's scroll
      // anchoring push it off-screen, and the Layout header already owns sticky top-0. Bottom
      // centre clears the mobile nav (bottom-0, ~4rem) and the support bubble (bottom-right).
      className="fixed inset-x-0 bottom-20 md:bottom-4 z-[60] flex justify-center px-4 pointer-events-none"
      data-testid="new-build-banner"
    >
      <div className="pointer-events-auto bg-brand-alt text-brand-ink text-sm border border-brand-border rounded-lg shadow-lg px-4 py-2 flex flex-wrap items-center gap-3">
        <span>A new version of WifiHaven is available. Reload to use it.</span>
        <button
          type="button"
          onClick={() => reload()}
          className="bg-brand-accent text-white font-semibold px-3 py-1 rounded-md hover:bg-brand-accent-dark transition-colors"
        >
          Reload
        </button>
      </div>
    </div>
  )
}

/**
 * Runs `check` when the ws comes back after a drop (reconnecting → live, having been live
 * before). A reconnect usually means the API redeployed, often alongside the SPA. The first
 * connect after page load is skipped: the page was just fetched, so it is current.
 */
export function useCheckOnWsReconnect(status: WsStatus, check: () => Promise<void>): void {
  const wasLive = useRef(false)
  const prev = useRef(status)
  const checkRef = useRef(check)
  checkRef.current = check
  useEffect(() => {
    if (status === 'live' && prev.current !== 'live' && wasLive.current) void checkRef.current()
    if (status === 'live') wasLive.current = true
    prev.current = status
  }, [status])
}

/** Mounted inside `WsProvider` (App.tsx) to wire the tab's checker to ws reconnects. */
export function BuildCheckOnWsReconnect(): null {
  const status = useWsLive()
  const checker = getBuildChecker()
  useCheckOnWsReconnect(status, () => checker?.check() ?? Promise.resolve())
  return null
}
