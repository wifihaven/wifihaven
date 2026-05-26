import { useEffect, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api } from '@/api/client'
import type { BlockedInfoResponse } from '@/types/api'

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
        <p className="text-gray-500 text-sm">Ask a parent to adjust your settings.</p>
      </div>
    </div>
  )
}
