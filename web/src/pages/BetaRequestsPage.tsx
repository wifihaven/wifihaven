import { useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { api } from '@/api/client'
import { useBetaRequests, qk } from '@/api/queries'
import { copyToClipboard } from '@/lib/clipboard'
import type { ApproveBetaResponse, BetaRequestStatus } from '@/types/api'

// The status enum serializes capitalized on the wire ('Pending' | 'Approved' | 'Rejected').
const STATUS_TABS: { key: BetaRequestStatus; label: string }[] = [
  { key: 'Pending',  label: 'Pending' },
  { key: 'Approved', label: 'Approved' },
  { key: 'Rejected', label: 'Rejected' },
]

/**
 * #2133 (multi-tenant P5-3, design §3.2): the OPERATOR beta-request review queue (`/beta-requests`).
 *
 * Visible only to a household-1 admin — the route/nav is gated on the `isOperator` API signal
 * (`useMe`), not a hardcoded client check, and the API independently 403s a non-operator. Lists
 * pending/approved/rejected requests; approve provisions the household server-side and returns the
 * single-use invite URL, which we surface with copy-to-clipboard (no email transport is invented —
 * the operator sends it manually, design §3.3).
 */
export function BetaRequestsPage() {
  const [status, setStatus] = useState<BetaRequestStatus>('Pending')
  const { data, isPending, isError } = useBetaRequests(status)
  const qc = useQueryClient()

  // Per-request approve result (invite URL) keyed by request id, so the operator can copy it.
  const [approved, setApproved] = useState<Record<number, ApproveBetaResponse>>({})
  const [busyId, setBusyId] = useState<number | null>(null)
  const [actionError, setActionError] = useState<string | null>(null)
  const [copiedId, setCopiedId] = useState<number | null>(null)

  function invalidateAll() {
    for (const t of STATUS_TABS) qc.invalidateQueries({ queryKey: qk.betaRequests(t.key) })
  }

  async function approve(id: number) {
    setActionError(null)
    setBusyId(id)
    try {
      const resp = await api.beta.approve(id)
      setApproved(prev => ({ ...prev, [id]: resp }))
      invalidateAll()
    } catch {
      setActionError('Could not approve that request. Refresh and try again.')
    } finally {
      setBusyId(null)
    }
  }

  async function reject(id: number) {
    setActionError(null)
    setBusyId(id)
    try {
      await api.beta.reject(id)
      invalidateAll()
    } catch {
      setActionError('Could not reject that request. Refresh and try again.')
    } finally {
      setBusyId(null)
    }
  }

  async function copyInvite(id: number, url: string) {
    const ok = await copyToClipboard(url)
    if (ok) {
      setCopiedId(id)
      setTimeout(() => setCopiedId(c => (c === id ? null : c)), 2000)
    }
  }

  return (
    <div className="max-w-4xl mx-auto">
      <div className="mb-6">
        <h1 className="text-xl font-bold text-brand-ink">Beta requests</h1>
        <p className="text-brand-text-muted text-sm mt-1">
          Approve a request to provision its household and get a single-use invite link. Send the
          link to the requester yourself — we don't email it automatically.
        </p>
      </div>

      <div className="flex gap-1 mb-4 border-b border-brand-border">
        {STATUS_TABS.map(t => (
          <button
            key={t.key}
            type="button"
            onClick={() => setStatus(t.key)}
            className={`px-4 py-2 text-sm font-medium border-b-2 -mb-px transition-colors ${
              status === t.key
                ? 'border-brand-accent text-brand-accent'
                : 'border-transparent text-brand-text-muted hover:text-brand-ink'
            }`}
          >
            {t.label}
          </button>
        ))}
      </div>

      {actionError && (
        <div className="bg-red-500/10 border border-red-500/20 rounded-lg px-4 py-3 text-red-700 text-sm mb-4">
          {actionError}
        </div>
      )}

      {isPending ? (
        <div className="text-brand-text-muted text-sm py-12 text-center" role="status">
          Loading requests…
        </div>
      ) : isError ? (
        <div className="bg-red-500/10 border border-red-500/20 rounded-lg px-4 py-3 text-red-700 text-sm">
          Couldn't load beta requests. Refresh to try again.
        </div>
      ) : data.length === 0 ? (
        <div className="text-brand-text-muted text-sm py-12 text-center">
          No {status} requests.
        </div>
      ) : (
        <ul className="space-y-3">
          {data.map(r => (
            <li key={r.id} className="bg-white border border-brand-border rounded-xl p-4">
              <div className="flex items-start justify-between gap-4">
                <div className="min-w-0">
                  <div className="font-medium text-brand-ink truncate">{r.email}</div>
                  {r.name && <div className="text-sm text-brand-text">{r.name}</div>}
                  {r.note && <div className="text-sm text-brand-text-muted mt-1 whitespace-pre-wrap break-words">{r.note}</div>}
                  <div className="text-xs text-brand-text-muted mt-2">
                    Requested {new Date(r.requestedAt).toLocaleString()}
                    {r.decidedAt && ` · decided ${new Date(r.decidedAt).toLocaleString()}`}
                  </div>
                </div>
                {r.status === 'Pending' && (
                  <div className="flex gap-2 shrink-0">
                    <button
                      type="button"
                      onClick={() => approve(r.id)}
                      disabled={busyId === r.id}
                      className="bg-brand-accent hover:bg-brand-accent-dark disabled:opacity-50 text-white text-sm font-semibold px-3 py-1.5 rounded-lg transition-colors"
                    >
                      Approve
                    </button>
                    <button
                      type="button"
                      onClick={() => reject(r.id)}
                      disabled={busyId === r.id}
                      className="border border-brand-border-strong text-brand-text hover:text-brand-ink disabled:opacity-50 text-sm font-semibold px-3 py-1.5 rounded-lg transition-colors"
                    >
                      Reject
                    </button>
                  </div>
                )}
              </div>

              {approved[r.id] && (
                <div className="mt-3 bg-brand-surface border border-brand-border rounded-lg p-3">
                  <div className="text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-1">
                    Invite link — send this to the requester
                  </div>
                  <div className="flex items-center gap-2">
                    <code className="flex-1 min-w-0 truncate text-xs text-brand-ink bg-white border border-brand-border rounded px-2 py-1.5">
                      {approved[r.id].inviteUrl}
                    </code>
                    <button
                      type="button"
                      onClick={() => copyInvite(r.id, approved[r.id].inviteUrl)}
                      className="shrink-0 text-sm font-semibold text-brand-accent hover:underline px-2 py-1"
                    >
                      {copiedId === r.id ? 'Copied' : 'Copy'}
                    </button>
                  </div>
                  <div className="text-xs text-brand-text-muted mt-1">
                    Expires {new Date(approved[r.id].inviteExpiresAt).toLocaleString()}
                  </div>
                </div>
              )}
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
