import { useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '@/api/client'
import { useAlerts, useInvalidators } from '@/api/queries'
import type { Alert } from '@/types/api'
import { useEscapeClose } from '@/hooks/useEscapeClose'

// Dashboard hints backed by the unified `/api/alerts` feed.
//   - NewDevicesHint (#711)         — yellow strip linking to /devices
//   - AccessRequestsBanner (#960)   — green strip opening a review modal
// Both filter the same query result by `kind`.

function relativeTime(iso: string): string {
  const t = new Date(iso).getTime()
  const seconds = Math.max(0, Math.round((Date.now() - t) / 1000))
  if (seconds < 60) return `${seconds}s ago`
  const m = Math.round(seconds / 60)
  if (m < 60) return `${m}m ago`
  const h = Math.round(m / 60)
  if (h < 24) return `${h}h ago`
  const d = Math.round(h / 24)
  return `${d}d ago`
}

export function NewDevicesHint() {
  const { data = [] } = useAlerts()
  const newDevices = data.filter(a => a.kind === 'new_device')
  if (newDevices.length === 0) return null
  return (
    <Link
      data-testid="dashboard-new-devices-hint"
      to="/devices"
      className="block bg-amber-500/5 border border-amber-500/30 rounded-2xl px-5 py-3 text-sm text-amber-800 hover:bg-amber-500/10 transition-colors"
    >
      {newDevices.length === 1
        ? '1 new device on the network — review on the Devices page →'
        : `${newDevices.length} new devices on the network — review on the Devices page →`}
    </Link>
  )
}

export function AccessRequestsBanner() {
  const { data = [] } = useAlerts()
  const requests = data.filter(a => a.kind === 'access_request')
  const [open, setOpen] = useState(false)

  if (requests.length === 0) return null

  return (
    <>
      <button
        type="button"
        data-testid="access-requests-banner"
        onClick={() => setOpen(true)}
        className="block w-full text-left bg-brand-accent/5 border border-brand-accent/30 rounded-2xl px-5 py-3 text-sm text-brand-accent hover:bg-brand-accent-dark/10 transition-colors"
      >
        {requests.length === 1
          ? '1 request from a household member — review →'
          : `${requests.length} requests from household members — review →`}
      </button>
      {open && <AccessRequestsModal requests={requests} onClose={() => setOpen(false)} />}
    </>
  )
}

function kindLabel(rk: Alert['requestKind']): string {
  switch (rk) {
    case 'extension': return 'More screen time'
    case 'exemption': return 'Unblock a site'
    case 'unpause':   return 'Unpause profile'
    default:          return 'Request'
  }
}

function kindDescription(a: Alert): string {
  switch (a.requestKind) {
    case 'extension':
      return `Wants more time today on the ${a.profileName ?? 'their'} profile.`
    case 'exemption':
      return `Wants access to ${a.host ?? '(?)'}.`
    case 'unpause':
      return `Wants the ${a.profileName ?? 'their'} profile unpaused.`
    default:
      return ''
  }
}

function AccessRequestsModal({
  requests,
  onClose,
}: {
  requests: Alert[]
  onClose: () => void
}) {
  useEscapeClose(onClose)
  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-label="Access requests"
      data-testid="access-requests-modal"
      className="fixed inset-0 z-50 bg-black/70 flex items-start justify-center p-4 overflow-y-auto"
      onClick={(e) => { if (e.target === e.currentTarget) onClose() }}
    >
      <div className="w-full max-w-xl bg-white border border-brand-border rounded-2xl p-5 mt-12 space-y-4">
        <div className="flex items-center justify-between">
          <h2 className="text-base font-semibold text-brand-ink">Pending requests</h2>
          <button
            type="button"
            onClick={onClose}
            className="text-brand-text-muted hover:text-brand-ink text-sm"
          >
            Close
          </button>
        </div>
        <ul className="space-y-3">
          {requests.map(r => <AccessRequestRow key={r.id} request={r} />)}
        </ul>
      </div>
    </div>
  )
}

function AccessRequestRow({ request }: { request: Alert }) {
  const inv = useInvalidators()
  const [busy, setBusy] = useState<'approve' | 'deny' | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [minutes, setMinutes] = useState<number>(30)

  const approve = async () => {
    setBusy('approve')
    setError(null)
    try {
      await api.alerts.approve(
        request.id,
        request.requestKind === 'extension' ? { minutes } : {},
      )
      await Promise.all([
        inv.alerts(),
        inv.profileMutated(),
      ])
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setBusy(null)
    }
  }

  const deny = async () => {
    setBusy('deny')
    setError(null)
    try {
      await api.alerts.deny(request.id)
      await inv.alerts()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
      setBusy(null)
    }
  }

  return (
    <li
      data-testid={`access-request-${request.id}`}
      className="bg-brand-surface/50 border border-brand-border rounded-xl p-4 space-y-3"
    >
      <div className="flex items-baseline justify-between gap-2">
        <div>
          <p className="text-sm font-semibold text-brand-ink">{kindLabel(request.requestKind)}</p>
          <p className="text-xs text-brand-text">{kindDescription(request)}</p>
        </div>
        <p className="text-xs text-brand-text-muted shrink-0">{relativeTime(request.createdAt)}</p>
      </div>
      <p className="text-xs text-brand-text-muted font-mono">
        {request.deviceName ?? request.mac}
        {request.profileName && ` · ${request.profileName}`}
      </p>
      {request.note && (
        <p className="text-xs text-brand-text italic border-l-2 border-brand-border-strong pl-2">
          "{request.note}"
        </p>
      )}
      {request.requestKind === 'extension' && (
        <label className="block text-xs text-brand-text">
          Minutes:
          <input
            type="number"
            min={1}
            max={480}
            value={minutes}
            onChange={(e) => setMinutes(Math.max(1, Number(e.target.value) || 0))}
            className="ml-2 w-20 bg-white border border-brand-border-strong rounded px-2 py-1 text-sm text-brand-ink"
          />
        </label>
      )}
      {error && <p className="text-xs text-red-700">{error}</p>}
      <div className="flex gap-2">
        <button
          type="button"
          onClick={approve}
          disabled={busy !== null}
          data-testid={`access-request-approve-${request.id}`}
          className="flex-1 bg-brand-accent-dark hover:bg-brand-accent-dark disabled:opacity-50 text-brand-ink text-sm font-medium px-3 py-2 rounded-lg transition-colors"
        >
          {busy === 'approve' ? 'Approving…' : 'Approve'}
        </button>
        <button
          type="button"
          onClick={deny}
          disabled={busy !== null}
          data-testid={`access-request-deny-${request.id}`}
          className="flex-1 bg-brand-alt hover:bg-brand-alt disabled:opacity-50 text-brand-ink text-sm font-medium px-3 py-2 rounded-lg transition-colors"
        >
          {busy === 'deny' ? 'Denying…' : 'Deny'}
        </button>
      </div>
    </li>
  )
}
