import { useSyncExternalStore } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { apiHealth } from '@/api/apiHealth'

// #1191 — page-level banner shown at the top of the app shell when the SPA
// can't reach the API. Replaces the silent "empty list" failure mode where
// a 502 looked identical to "no data exists."
//
// One Retry button → global invalidate, no auto-retry loop. In-flight
// mutations (autosave forms — feedback_autosave_default.md) are left
// untouched; they own their own retry/failure UI.
export function ApiUnreachableBanner() {
  const snap = useSyncExternalStore(apiHealth.subscribe, apiHealth.snapshot, apiHealth.snapshot)
  const qc = useQueryClient()
  if (!snap.unreachable) return null

  function onRetry() {
    qc.invalidateQueries()
  }

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
          onClick={onRetry}
          className="bg-white text-red-700 font-semibold px-3 py-1 rounded-md hover:bg-red-50 transition-colors"
        >
          Retry
        </button>
      </div>
    </div>
  )
}
