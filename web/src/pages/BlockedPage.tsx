import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api } from '@/api/client'
import type { AccessRequestKind, BlockedInfoResponse } from '@/types/api'

// #959: kid-side block page.
//
// Flow: router DNATs blocked traffic to app.wifihaven.net (#944 has the SPA
// hosts in extraAllowed so this resolves), redirecting to /blocked?mac=...&host=....
// (#1841/#1832: app.wifihaven.net is the canonical app host. Routers enrolled
// before the rename DNAT to the apex wifihaven.net, which serves a /blocked
// compat shim (#1842) that 302-redirects to app.wifihaven.net/blocked,
// preserving the query string.)
// We call GET /api/blocked to resolve the reason class and render kid-friendly
// copy per the #952 design doc Q4 decisions.
//
// Granularity (Q4):
//   - category   → "Blocked: <category name>"
//   - paused     → "Your profile is paused"
//   - schedule   → "Outside allowed time"     (no end time leak)
//   - time_limit → "Out of time today"        (no minute counts)
//   - app_time_limit → "Out of time on this app"
//   - extra_blocked → "Blocked by your parent"
//
// #1615: the API is the only source of body copy and CTA kinds. The router
// still appends `?reason=` to the redirect URL until PR2 (#1617), but the SPA
// ignores it — it's kid-supplied input and the API is canonical.

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
    case 'app_time_limit':
      return 'Out of time on this app today.'
    case 'extra_blocked':
      return 'Blocked by your parent.'
    default:
      return 'Access blocked.'
  }
}

export function BlockedPage() {
  const [params] = useSearchParams()
  const host = params.get('host') ?? ''
  const mac  = params.get('mac') ?? ''

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

  const body =
    info != null   ? copyFor(info)
    : error        ? 'Access blocked.'
    : mac && host  ? '…'
    :                'Access blocked.'

  const profileLine = info?.blocked && info.profileName
    ? `for ${info.profileName}`
    : null

  return (
    <div className="min-h-screen bg-brand-surface flex items-center justify-center p-4">
      <div className="w-full max-w-sm space-y-6 text-center">
        <div className="space-y-2">
          <div className="text-4xl font-bold text-red-700">Blocked</div>
          {host && <div className="text-lg font-mono text-brand-ink">{host}</div>}
          <p className="text-brand-text text-sm">{body}</p>
          {profileLine && <p className="text-brand-text-muted text-xs">{profileLine}</p>}
        </div>
        {info && <UsageToday info={info} />}
        {mac && host
          ? <AskParent mac={mac} host={host} info={info} />
          : <p className="text-brand-text-muted text-sm">Ask a parent to adjust your settings.</p>
        }
      </div>
    </div>
  )
}

/**
 * #335: today's screen-time summary for the device's profile. The block page is
 * the kid's only authenticated-free window into their own state, so we surface
 * used / cap / extension / remaining here. Values come from the API
 * (BlockedInfoResponse), which sources them from the canonical
 * TimeStatusService.todaysState — same primitive that drives the snapshot's
 * TimeLimit decision. We render nothing if no daily cap is configured for the
 * profile (or the MAC is unenrolled).
 */
function UsageToday({ info }: { info: BlockedInfoResponse }) {
  const used = info.usedMinutes ?? null
  const cap = info.dailyLimitMinutes ?? null
  const ext = info.extensionMinutes ?? 0
  const remaining = info.remainingMinutes ?? null
  if (cap == null || used == null) return null
  return (
    <div
      data-testid="block-usage"
      className="bg-white border border-brand-border rounded-2xl px-4 py-3 text-sm text-brand-text text-left space-y-1"
    >
      <div className="font-medium text-brand-ink">Time today</div>
      <div data-testid="block-usage-used">Used: {used} / {cap} min{ext > 0 ? ` (+${ext} extension)` : ''}</div>
      {remaining != null && (
        <div data-testid="block-usage-remaining">
          {remaining > 0 ? `${remaining} min left` : 'No time left today'}
        </div>
      )}
    </div>
  )
}

/**
 * #960: kid-side CTA. Maps the API `reasonClass` to the request kinds that
 * make sense for that reason — e.g. `time_limit` offers only "ask for more
 * time", a category block offers only "ask to unblock this site". POSTs to
 * the public `/api/access-requests` endpoint; no kid-side credentials.
 */
function offeredKindsFor(info: BlockedInfoResponse | null): AccessRequestKind[] {
  const cls = info?.blocked ? info.reasonClass : null
  if (cls === 'paused')          return ['unpause', 'extension']
  if (cls === 'schedule')        return ['unpause', 'extension']
  if (cls === 'time_limit')      return ['extension']
  if (cls === 'app_time_limit')  return ['extension', 'exemption']
  if (cls === 'category')        return ['exemption']
  if (cls === 'extra_blocked')   return ['exemption']
  // API in flight or unknown class — offer everything so the kid still has a
  // way through.
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
}: {
  mac: string
  host: string
  info: BlockedInfoResponse | null
}) {
  const kinds = offeredKindsFor(info)
  const [note, setNote] = useState('')
  const [sending, setSending] = useState<AccessRequestKind | null>(null)
  const [sent, setSent] = useState(false)
  const [error, setError] = useState<string | null>(null)

  if (sent) {
    return (
      <div
        data-testid="ask-parent-sent"
        className="bg-brand-accent/10 border border-brand-accent/30 rounded-2xl px-4 py-3 text-sm text-brand-accent"
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
        className="w-full bg-white border border-brand-border rounded-lg px-3 py-2 text-sm text-brand-ink placeholder-brand-text-muted resize-none"
      />
      <div className="space-y-2">
        {kinds.map(k => (
          <button
            key={k}
            type="button"
            onClick={() => ask(k)}
            disabled={sending !== null}
            data-testid={`ask-parent-${k}`}
            className="w-full bg-brand-accent-dark hover:bg-brand-accent-dark disabled:opacity-50 text-brand-ink text-sm font-medium px-4 py-2 rounded-lg transition-colors"
          >
            {sending === k ? 'Asking…' : kindLabel(k)}
          </button>
        ))}
      </div>
      {error && (
        <p className="text-xs text-red-700" data-testid="ask-parent-error">
          {error}
        </p>
      )}
    </div>
  )
}
