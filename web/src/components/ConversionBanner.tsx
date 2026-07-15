import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '@/api/client'
import { useAuth } from '@/hooks/useAuth'

/**
 * #2137 (multi-tenant P5-6): the in-SPA conversion banner (design §5.4, A1). Shows app-wide for an
 * unconverted (`status='beta'`) household once the cohort flip window is open, carrying the flip
 * date and a one-click Checkout (founding promo pre-applied server-side). Dismissible per session
 * (sessionStorage) so it doesn't nag on every navigation but returns next login.
 *
 * Admin-only: the billing status route is admin-scoped, so the query is gated on `isAdmin` to avoid
 * a 403 for non-admins. During pure beta (window not open), on lapse/active, or on any load/error it
 * renders nothing — the banner is strictly the "window open + still on beta" nudge.
 */
const DISMISS_KEY = 'wh-conversion-banner-dismissed'

export function ConversionBanner() {
  const { isAdmin } = useAuth()
  const [dismissed, setDismissed] = useState(() => sessionStorage.getItem(DISMISS_KEY) === '1')
  const [redirecting, setRedirecting] = useState(false)

  const { data } = useQuery({
    queryKey: ['billing', 'status'],
    queryFn: () => api.billing.status(),
    enabled: isAdmin,
    // Billing state changes slowly; one fetch per session is plenty and shared with the billing page.
    staleTime: 5 * 60 * 1000,
    retry: false,
  })

  // Only for an unconverted household inside an open flip window.
  if (dismissed || !data || data.status !== 'beta' || !data.flipWindowOpen) return null

  function dismiss() {
    sessionStorage.setItem(DISMISS_KEY, '1')
    setDismissed(true)
  }

  async function subscribe() {
    setRedirecting(true)
    try {
      const { url } = await api.billing.checkout()
      window.location.href = url
    } catch {
      setRedirecting(false)
    }
  }

  const byDate = data.flipDate ? new Date(data.flipDate).toLocaleDateString() : null

  return (
    <div className="bg-brand-accent/10 border-b border-brand-accent/20 px-4 py-2 text-sm">
      <div className="max-w-7xl mx-auto flex items-center gap-3 flex-wrap">
        <span className="text-brand-ink">
          {byDate
            ? `Your free beta ends ${byDate}. Subscribe to keep enforcement on — you won't be charged until then.`
            : "Your free beta is ending soon. Subscribe to keep enforcement on — you won't be charged until the beta ends."}
        </span>
        <span className="flex items-center gap-2 ml-auto">
          <button
            type="button"
            disabled={redirecting}
            onClick={subscribe}
            className="bg-brand-accent hover:bg-brand-accent-dark disabled:opacity-50 text-white font-semibold px-3 py-1 rounded-lg transition-colors"
          >
            {redirecting ? 'Opening…' : 'Subscribe'}
          </button>
          <button
            type="button"
            aria-label="Dismiss"
            onClick={dismiss}
            className="text-brand-text-muted hover:text-brand-ink px-2 py-1 rounded"
          >
            ✕
          </button>
        </span>
      </div>
    </div>
  )
}
