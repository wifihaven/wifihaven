import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api } from '@/api/client'
import type { AccessRequestKind, BlockedInfoResponse } from '@/types/api'

// #959: kid-side block page.
//
// Flow: router DNATs blocked traffic to wifihaven.net (#944 has the SPA hosts
// in extraAllowed so this resolves), redirecting to /blocked?mac=...&host=....
// We call GET /api/blocked to resolve the reason class and render kid-friendly
// copy per the #952 design doc Q4 decisions.
//
// Granularity (Q4):
//   - category   → "Blocked: <category name>"
//   - paused     → "Your profile is paused"
//   - schedule   → "Outside allowed time"     (no end time leak)
//   - time_limit → "Out of time today"        (no minute counts)
//   - site_time_limit → "Out of time on this site"
//   - extra_blocked → "Blocked by your parent"
//
// Fallback: if `reason` is supplied as a query param (older router serving the
// MacBlockReason string directly, pre-API path), we still render the legacy
// copy so a stale OpenWRT agent doesn't show a blank page during rollout.

function copyFor(info: BlockedInfoResponse): string {
  if (!info.blocked) return 'This page is not blocked for this device.'
  switch (info.reasonClass) {
    case 'category':
      return info.categoryName
        ? `Blocked category: ${info.categoryName}.`
        : 'This site is in a blocked category.'
    case 'paused':
      return 'Your profile is paused.'
    case 'schedule':
      return 'Outside allowed time.'
    case 'time_limit':
      return 'Out of time today.'
    case 'site_time_limit':
      return 'Out of time on this site today.'
    case 'extra_blocked':
      return 'Blocked by your parent.'
    default:
      return 'Access blocked.'
  }
}

function legacyCopy(reason: string, until?: string | null): string {
  if (reason === 'Paused') return 'Your profile is paused.'
  if (reason === 'Schedule') return until ? `This is scheduled quiet time until ${until}.` : 'Outside allowed time.'
  if (reason === 'TimeLimit') return 'Out of time today.'
  if (reason === 'Manual') return 'This device has been blocked by a parent.'
  if (reason === 'ExtraBlocked') return 'This site is blocked by the household.'
  // #961 — unmanaged-MAC fallthrough. Sent by the router when an HTTP/80
  // request originates from a MAC not enrolled in any profile and the
  // household policy is `block`.
  if (reason === 'device_not_enrolled') return 'This device is not enrolled. Ask the household admin to add it.'
  if (reason.startsWith('site_time_limit')) return 'Out of time on this site today.'
  if (reason.startsWith('category:')) return `Blocked category: ${reason.slice('category:'.length)}.`
  if (reason === 'extra_blocked') return 'Blocked by your parent.'
  return 'Access blocked.'
}

export function BlockedPage() {
  const [params] = useSearchParams()
  const host       = params.get('host') ?? ''
  const mac        = params.get('mac') ?? ''
  const legacyReason = params.get('reason') ?? ''
  const legacyUntil  = params.get('until')

  const [info, setInfo] = useState<BlockedInfoResponse | null>(null)
  const [error, setError] = useState<boolean>(false)

  useEffect(() => {
    if (!mac || !host) return
    let cancelled = false
    api.blocked
      .info(mac, host)
      .then(r => { if (!cancelled) setInfo(r) })
      .catch(() => { if (!cancelled) setError(true) })
    return () => { cancelled = true }
  }, [mac, host])

  // Prefer the API result when it arrives. Until then (or on error), fall back
  // to the legacy `reason` query param the router still sends so a stale
  // router agent or a slow API doesn't show a blank page.
  const body =
    info != null && info.blocked ? copyFor(info)
    : legacyReason                ? legacyCopy(legacyReason, legacyUntil)
    : info != null                ? copyFor(info) // info.blocked === false
    : error                       ? 'Access blocked.'
    : mac && host                 ? '…'
    :                               'Access blocked.'

  const profileLine = info?.blocked && info.profileName
    ? `for ${info.profileName}`
    : null

  return (
    <div className="min-h-screen bg-gray-950 flex items-center justify-center p-4">
      <div className="w-full max-w-sm space-y-6 text-center">
        <div className="space-y-2">
          <div className="text-4xl font-bold text-red-500">Blocked</div>
          {host && <div className="text-lg font-mono text-white">{host}</div>}
          <p className="text-gray-400 text-sm">{body}</p>
          {profileLine && <p className="text-gray-500 text-xs">{profileLine}</p>}
        </div>
        {mac && host
          ? <AskParent mac={mac} host={host} info={info} legacyReason={legacyReason} />
          : <p className="text-gray-500 text-sm">Ask a parent to adjust your settings.</p>
        }
      </div>
    </div>
  )
}

/**
 * #960: kid-side CTA. Maps the block reason (API `reasonClass` first, legacy
 * `reason` query param as fallback) to the request kinds that make sense for
 * that reason — e.g. `time_limit` offers only "ask for more time", a category
 * block offers only "ask to unblock this site". POSTs to the public
 * `/api/access-requests` endpoint; no kid-side credentials.
 */
function offeredKindsFor(info: BlockedInfoResponse | null, legacy: string): AccessRequestKind[] {
  const cls = info?.blocked ? info.reasonClass : null
  if (cls === 'paused')          return ['unpause', 'extension']
  if (cls === 'schedule')        return ['unpause', 'extension']
  if (cls === 'time_limit')      return ['extension']
  if (cls === 'site_time_limit') return ['extension', 'exemption']
  if (cls === 'category')        return ['exemption']
  if (cls === 'extra_blocked')   return ['exemption']
  // Fall back to the legacy reason string for stale router agents.
  if (legacy === 'Paused')                 return ['unpause', 'extension']
  if (legacy === 'Schedule')               return ['unpause', 'extension']
  if (legacy === 'TimeLimit')              return ['extension']
  if (legacy === 'ExtraBlocked')           return ['exemption']
  if (legacy === 'Manual')                 return ['exemption']
  if (legacy === 'extra_blocked')          return ['exemption']
  if (legacy.startsWith('category:'))      return ['exemption']
  if (legacy.startsWith('site_time_limit'))return ['extension', 'exemption']
  // Unknown reason — offer everything so the kid still has a way through.
  return ['extension', 'exemption', 'unpause']
}

function kindLabel(k: AccessRequestKind): string {
  switch (k) {
    case 'extension': return 'Ask for more time'
    case 'exemption': return 'Ask to unblock this site'
    case 'unpause':   return 'Ask to unpause'
  }
}

function AskParent({
  mac,
  host,
  info,
  legacyReason,
}: {
  mac: string
  host: string
  info: BlockedInfoResponse | null
  legacyReason: string
}) {
  const kinds = offeredKindsFor(info, legacyReason)
  const [note, setNote] = useState('')
  const [sending, setSending] = useState<AccessRequestKind | null>(null)
  const [sent, setSent] = useState(false)
  const [error, setError] = useState<string | null>(null)

  if (sent) {
    return (
      <div
        data-testid="ask-parent-sent"
        className="bg-emerald-500/10 border border-emerald-500/30 rounded-2xl px-4 py-3 text-sm text-emerald-200"
      >
        Sent. A parent will review and decide.
      </div>
    )
  }

  const ask = async (kind: AccessRequestKind) => {
    setSending(kind)
    setError(null)
    try {
      await api.alerts.createAccessRequest({
        mac,
        host,
        kind,
        note: note.trim() ? note.trim() : undefined,
      })
      setSent(true)
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setSending(null)
    }
  }

  return (
    <div className="space-y-3" data-testid="ask-parent">
      <textarea
        value={note}
        onChange={(e) => setNote(e.target.value.slice(0, 280))}
        placeholder="Optional: tell them why (280 chars max)"
        rows={2}
        className="w-full bg-gray-900 border border-gray-800 rounded-lg px-3 py-2 text-sm text-white placeholder-gray-600 resize-none"
      />
      <div className="space-y-2">
        {kinds.map(k => (
          <button
            key={k}
            type="button"
            onClick={() => ask(k)}
            disabled={sending !== null}
            data-testid={`ask-parent-${k}`}
            className="w-full bg-emerald-600 hover:bg-emerald-500 disabled:opacity-50 text-white text-sm font-medium px-4 py-2 rounded-lg transition-colors"
          >
            {sending === k ? 'Asking…' : kindLabel(k)}
          </button>
        ))}
      </div>
      {error && (
        <p className="text-xs text-red-400" data-testid="ask-parent-error">
          {error}
        </p>
      )}
    </div>
  )
}
