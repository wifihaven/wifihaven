import { useState } from 'react'
import { useQuery } from '@tanstack/react-query'
import { api } from '@/api/client'
import { qk } from '@/api/queryKeys'
import type { BillingStatus } from '@/types/api'

/**
 * #2135 (multi-tenant P5-5): the minimal billing page. Shows current plan/status, surfaces the
 * founding price, and links out to the two hosted-Stripe surfaces — Checkout (convert / recover) and
 * the Customer Portal (cancel / update card). Near-zero UI: the actual billing forms live on Stripe.
 *
 * On lapse there is NO read-only gating anywhere (design §5.3) — the household keeps its network and
 * dashboards; it just loses enforcement until it converts/recovers. This page frames it that way.
 */
export function BillingPage() {
  const { data, isPending, isError, error } = useQuery({
    queryKey: qk.billingStatus(),
    queryFn: () => api.billing.status(),
  })
  const [redirecting, setRedirecting] = useState<'checkout' | 'portal' | null>(null)
  const [actionError, setActionError] = useState('')

  async function go(kind: 'checkout' | 'portal') {
    setActionError('')
    setRedirecting(kind)
    try {
      const { url } = kind === 'checkout' ? await api.billing.checkout() : await api.billing.portal()
      window.location.href = url
    } catch (e) {
      setRedirecting(null)
      setActionError(e instanceof Error ? e.message : 'Could not start the billing session')
    }
  }

  return (
    <div className="space-y-6 max-w-xl">
      <h1 className="text-xl font-bold text-brand-ink">Billing</h1>

      <section className="bg-white rounded-2xl border border-brand-border p-5">
        <h2 className="text-sm font-semibold text-brand-text uppercase tracking-wider mb-4">
          Subscription
        </h2>

        {/* Loading-states rule (#loading-states): distinguish loading / error / loaded — never render
            a real-looking placeholder while pending. */}
        {isPending ? (
          <div className="animate-pulse space-y-2" aria-label="Loading billing status">
            <div className="h-4 bg-brand-alt rounded w-1/2" />
            <div className="h-4 bg-brand-alt rounded w-1/3" />
          </div>
        ) : isError ? (
          <div className="bg-red-500/10 border border-red-500/20 rounded-lg px-4 py-3 text-red-700 text-sm">
            Could not load billing status{error instanceof Error ? `: ${error.message}` : ''}.
          </div>
        ) : (
          <dl className="text-sm space-y-2">
            <div className="flex justify-between">
              <dt className="text-brand-text-muted">Status</dt>
              <dd className="font-mono">
                <StatusBadge status={data.status} />
              </dd>
            </div>
            <div className="flex justify-between">
              <dt className="text-brand-text-muted">Plan</dt>
              <dd className="text-brand-ink">
                {data.status === 'free_forever'
                  ? 'Free — not billed'
                  : data.founding
                    ? 'Founding — 40% off, forever'
                    : 'Standard — $10/mo or $96/yr'}
              </dd>
            </div>
            {data.currentPeriodEnd && (
              <div className="flex justify-between">
                <dt className="text-brand-text-muted">Renews</dt>
                <dd className="text-brand-ink">{new Date(data.currentPeriodEnd).toLocaleDateString()}</dd>
              </div>
            )}
          </dl>
        )}
      </section>

      {/* #2356: a free_forever household is never billed — no Subscribe / Manage-billing CTA (they
          have no Stripe customer, so the portal would 409). Render a clean "not billed" panel
          instead. This also fixes the old "billing not configured" error on hh1's Manage-billing
          click, since that button no longer renders. */}
      {!isPending && !isError && data.status === 'free_forever' && (
        <section className="bg-white rounded-2xl border border-brand-border p-5">
          <p className="text-sm text-brand-text-muted">
            This household is on a <span className="font-semibold text-brand-ink">Free — not billed</span>{' '}
            plan. There's nothing to pay and no subscription to manage — content filtering and limits
            stay fully enforced.
          </p>
        </section>
      )}

      {!isPending && !isError && data.status !== 'free_forever' && (
        <section className="bg-white rounded-2xl border border-brand-border p-5 space-y-4">
          {data.status === 'lapsed' && (
            <div className="bg-amber-500/10 border border-amber-500/30 rounded-xl px-4 py-3 text-amber-700 text-sm">
              Your subscription has lapsed. Your network keeps running and your history is intact, but
              content filtering and limits are paused until you resubscribe.
            </div>
          )}

          {/* #2137 (A1): the Subscribe CTA is HIDDEN during pure beta (clock not yet started). It
              shows only once the flip window is open, or for a lapsed household re-subscribing. An
              active household gets the portal. */}
          {data.status === 'active' ? (
            <>
              <p className="text-sm text-brand-text-muted">
                Manage your card, view invoices, or cancel in the Stripe customer portal.
              </p>
              <button
                type="button"
                disabled={redirecting !== null}
                onClick={() => go('portal')}
                className="bg-brand-accent hover:bg-brand-accent-dark disabled:opacity-50 text-white font-semibold px-4 py-2 rounded-xl transition-colors"
              >
                {redirecting === 'portal' ? 'Opening…' : 'Manage billing'}
              </button>
            </>
          ) : data.status === 'lapsed' || data.flipWindowOpen ? (
            <>
              <p className="text-sm text-brand-text-muted">
                {data.flipDate && data.status === 'beta'
                  ? `Your free beta ends ${new Date(data.flipDate).toLocaleDateString()}. Subscribe now to keep enforcement on — you won't be charged until then.`
                  : data.founding
                    ? 'Subscribe now to lock in the founding price — 40% off, forever.'
                    : 'Subscribe to keep content filtering and limits enforced.'}
              </p>
              <button
                type="button"
                disabled={redirecting !== null}
                onClick={() => go('checkout')}
                className="bg-brand-accent hover:bg-brand-accent-dark disabled:opacity-50 text-white font-semibold px-4 py-2 rounded-xl transition-colors"
              >
                {redirecting === 'checkout' ? 'Opening…' : 'Subscribe'}
              </button>
            </>
          ) : (
            /* Pure beta — no payment CTA yet (A1). Just reassure. */
            <p className="text-sm text-brand-text-muted">
              You're in the free beta{data.flipDate ? ` through ${new Date(data.flipDate).toLocaleDateString()}` : ''}
              . We'll let you know before anything changes — no payment needed yet.
            </p>
          )}

          {actionError && (
            <div className="bg-red-500/10 border border-red-500/20 rounded-lg px-4 py-3 text-red-700 text-sm">
              {actionError}
            </div>
          )}
        </section>
      )}
    </div>
  )
}

function StatusBadge({ status }: { status: BillingStatus }) {
  const styles: Record<BillingStatus, string> = {
    beta: 'bg-blue-500/10 text-blue-700 border-blue-500/20',
    active: 'bg-brand-accent/10 text-brand-accent border-brand-accent/20',
    lapsed: 'bg-amber-500/10 text-amber-700 border-amber-500/30',
    // #2356: free_forever — a calm "not billed" badge, styled like the emerald "all good" tone.
    free_forever: 'bg-emerald-500/10 text-emerald-700 border-emerald-500/20',
  }
  // #2356: render the underscore status as friendly text.
  const label = status === 'free_forever' ? 'free' : status
  return (
    <span className={`inline-block px-2 py-0.5 rounded border text-xs font-semibold ${styles[status]}`}>
      {label}
    </span>
  )
}
