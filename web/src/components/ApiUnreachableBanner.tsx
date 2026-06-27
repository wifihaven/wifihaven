import { useSyncExternalStore } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { apiHealth } from '@/api/apiHealth'
import { useWsLive } from '@/hooks/useWs'

// #1191 — page-level banner shown at the top of the app shell when the SPA
// can't reach the API. Replaces the silent "empty list" failure mode where
// a 502 looked identical to "no data exists."
//
// One Retry button → global invalidate, no auto-retry loop. In-flight
// mutations (autosave forms — feedback_autosave_default.md) are left
// untouched; they own their own retry/failure UI.
//
// #1973 (SPA-ws S5, design §6.2): the ws liveness lets us draw a distinction the
// silent poll can't — degraded vs down. REST polls failing (apiHealth.unreachable)
// is genuinely DOWN → the red banner. But the socket merely reconnecting while REST
// polls still succeed is DEGRADED, not down → a softer "Reconnecting live updates…"
// notice (the dashboard has fallen back to polling, §3.3, so data is still fresh-ish).
export function ApiUnreachableBanner() {
  const snap = useSyncExternalStore(apiHealth.subscribe, apiHealth.snapshot, apiHealth.snapshot)
  const wsStatus = useWsLive()
  const qc = useQueryClient()

  // Down trumps degraded: if REST itself is unreachable, the ws being down is moot.
  if (snap.unreachable) {
    return (
      <div
        role="alert"
        aria-live="assertive"
        className="bg-red-600 text-white text-sm"
        data-testid="api-unreachable-banner"
      >
        <div className="max-w-7xl mx-auto px-4 py-2 flex flex-wrap items-center gap-3 justify-between">
          <div>
            <strong className="font-semibold">WifiHaven can't reach the API right now.</strong>
            {' '}
            <span className="opacity-90">
              Showing cached data where available.
            </span>
          </div>
          <button
            type="button"
            onClick={() => qc.invalidateQueries()}
            className="bg-white text-red-700 font-semibold px-3 py-1 rounded-md hover:bg-red-50 transition-colors"
          >
            Retry
          </button>
        </div>
      </div>
    )
  }

  // Degraded: live updates paused but REST still answering — soft, non-blocking notice.
  if (wsStatus === 'reconnecting') {
    return (
      <div
        role="status"
        aria-live="polite"
        className="bg-amber-100 text-amber-800 text-sm border-b border-amber-200"
        data-testid="api-degraded-banner"
      >
        <div className="max-w-7xl mx-auto px-4 py-1.5">
          <span className="font-medium">Reconnecting live updates…</span>
          {' '}
          <span className="opacity-90">Data is refreshing on a slower fallback.</span>
        </div>
      </div>
    )
  }

  return null
}
