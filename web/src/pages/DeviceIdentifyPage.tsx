import { Link, useParams } from 'react-router-dom'
import { useDeviceIdentify } from '@/api/queries'
import { httpStatusOf } from '@/api/httpError'
import { HostCell } from '@/components/HostCell'
import { EmptyState } from '@/components/EmptyState'
import { PageLoader } from './DashboardPage'
import type { DeviceIdentity, DeviceVendor } from '@/types/api'

// #2926 — "What is this?" for a device, reached from an unmanaged row on /devices.
//
// Visibility only: nothing on this page writes. The device's DHCP name, its MAC vendor (or
// "private address" for a randomized MAC), and every destination it tried to reach — blocked
// attempts included — so the operator can work out what it is before enrolling it.

function ago(iso: string): string {
  const s = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000)
  const n = (v: number, unit: string) => `${v} ${unit}${v === 1 ? '' : 's'} ago`
  if (s < 60) return 'just now'
  if (s < 3600) return n(Math.round(s / 60), 'minute')
  if (s < 86_400) return n(Math.round(s / 3600), 'hour')
  return n(Math.round(s / 86_400), 'day')
}

function when(iso: string): string {
  return new Date(iso).toLocaleString()
}

function VendorHint({ vendor }: { vendor: DeviceVendor }) {
  if (vendor.kind === 'private') {
    return (
      <span data-testid="identify-vendor">
        Private address
        <span className="block text-xs text-brand-text-muted">
          The device is using a randomized MAC, so the address says nothing about who made it.
        </span>
      </span>
    )
  }
  if (vendor.kind === 'vendor' && vendor.name) {
    return <span data-testid="identify-vendor">{vendor.name}</span>
  }
  return (
    <span data-testid="identify-vendor">
      Unknown
      <span className="block text-xs text-brand-text-muted">The MAC prefix isn't in the vendor registry.</span>
    </span>
  )
}

// #2927: the stored DHCP hostname is authoritative when present. Without it (a device that
// sent none since it was stored separately) fall back to `sentName`, which is only `name` when
// that is not our placeholder, and say where it came from.
function nameHint(d: DeviceIdentity): string {
  if (d.dhcpHostname) {
    return d.dhcpHostname === d.name
      ? 'The name the device sent over DHCP.'
      : `The name the device sent over DHCP. It's called "${d.name}" here.`
  }
  if (d.sentName) {
    return 'Taken from the name the device sent over DHCP when it was first seen, unless someone renamed it.'
  }
  return "It didn't send a name over DHCP. Phones using a private address often don't."
}

function IdentityCard({ d }: { d: DeviceIdentity }) {
  return (
    <div className="bg-white rounded-2xl border border-brand-border p-5">
      <dl className="grid grid-cols-1 sm:grid-cols-2 gap-x-6 gap-y-4 text-sm">
        <div>
          <dt className="text-xs uppercase tracking-wider text-brand-text-muted">Name it sent</dt>
          <dd className="text-brand-ink font-medium" data-testid="identify-name">
            {d.dhcpHostname ?? d.sentName ?? 'None'}
            <span className="block text-xs text-brand-text-muted font-normal">{nameHint(d)}</span>
          </dd>
        </div>
        <div>
          <dt className="text-xs uppercase tracking-wider text-brand-text-muted">Made by</dt>
          <dd className="text-brand-ink font-medium"><VendorHint vendor={d.vendor} /></dd>
        </div>
        <div>
          <dt className="text-xs uppercase tracking-wider text-brand-text-muted">First seen</dt>
          <dd className="text-brand-ink" data-testid="identify-first-seen">
            {d.firstSeenAt
              ? <>{ago(d.firstSeenAt)} <span className="text-xs text-brand-text-muted">({when(d.firstSeenAt)})</span></>
              : 'Unknown'}
            <span className="block text-xs text-brand-text-muted">There's no history from before then.</span>
          </dd>
        </div>
        <div>
          <dt className="text-xs uppercase tracking-wider text-brand-text-muted">Last address</dt>
          <dd className="text-brand-ink font-mono">{d.lastSeenIp ?? 'Unknown'}</dd>
        </div>
      </dl>
    </div>
  )
}

function Traffic({ d }: { d: DeviceIdentity }) {
  if (d.destinations.length === 0) {
    return (
      <div className="bg-white rounded-2xl border border-brand-border">
        <EmptyState
          data-testid="identify-no-traffic"
          title={`No connection attempts recorded in the last ${d.windowDays} days.`}
          hint="The device hasn't tried to reach anything we could see in that time, or it has been offline."
        />
      </div>
    )
  }
  return (
    <div className="space-y-4">
      {d.guess && (
        <div
          data-testid="identify-guess"
          className="bg-amber-500/10 border border-amber-500/20 text-amber-800 rounded-2xl px-5 py-3 text-sm"
        >
          <span className="font-semibold">Best guess:</span> most of its traffic ({d.guess.sharePct}% of attempts) goes
          to {d.guess.appName}. This is only a guess from its traffic, and nothing has been changed because of it.
        </div>
      )}

      <p className="text-xs text-brand-text-muted" data-testid="identify-summary">
        Last {d.windowDays} days: {d.hostCount} destinations, {d.totalAllowed} allowed and {d.totalBlocked} blocked
        attempts. Blocked counts are a minimum, because the router records at most a few blocked attempts per
        destination per minute.
      </p>

      {d.apps.length > 0 && (
        <div className="bg-white rounded-2xl border border-brand-border overflow-hidden">
          <h2 className="px-5 pt-4 pb-2 text-sm font-semibold text-brand-text uppercase tracking-wider">Recognized apps</h2>
          <ul>
            {d.apps.map(a => (
              <li key={a.slug} data-testid={`identify-app-${a.slug}`} className="flex items-center gap-4 px-5 py-2 border-t border-brand-border text-sm">
                <span className="flex-1 text-brand-ink">{a.name}</span>
                <span className="text-brand-text-muted">{a.hosts} {a.hosts === 1 ? 'host' : 'hosts'}</span>
                <span className="text-brand-text">{a.allowed} allowed</span>
                <span className="text-red-700">{a.blocked} blocked</span>
              </li>
            ))}
          </ul>
        </div>
      )}

      <div className="bg-white rounded-2xl border border-brand-border overflow-x-auto">
        <table className="w-full text-sm" data-testid="identify-destinations">
          <thead>
            <tr className="text-left text-xs uppercase tracking-wider text-brand-text-muted">
              <th className="px-5 py-3 font-semibold">Destination</th>
              <th className="px-3 py-3 font-semibold">App</th>
              <th className="px-3 py-3 font-semibold text-right">Allowed</th>
              <th className="px-3 py-3 font-semibold text-right">Blocked</th>
              <th className="px-3 py-3 font-semibold">First seen</th>
              <th className="px-5 py-3 font-semibold">Last seen</th>
            </tr>
          </thead>
          <tbody>
            {d.destinations.map(r => (
              <tr key={`${r.host.type}:${r.host.value}`} className="border-t border-brand-border">
                <td className="px-5 py-2 max-w-xs truncate"><HostCell host={r.host} /></td>
                <td className="px-3 py-2 text-brand-text">{r.app?.name ?? ''}</td>
                <td className="px-3 py-2 text-right tabular-nums">{r.allowed}</td>
                <td className={`px-3 py-2 text-right tabular-nums ${r.blocked > 0 ? 'text-red-700' : ''}`}>{r.blocked}</td>
                <td className="px-3 py-2 whitespace-nowrap text-brand-text-muted" title={when(r.firstSeen)}>{ago(r.firstSeen)}</td>
                <td className="px-5 py-2 whitespace-nowrap text-brand-text-muted" title={when(r.lastSeen)}>{ago(r.lastSeen)}</td>
              </tr>
            ))}
          </tbody>
        </table>
        {d.truncated && (
          <p className="px-5 py-3 text-xs text-brand-text-muted border-t border-brand-border">
            Showing the {d.destinations.length} busiest of {d.hostCount} destinations.
          </p>
        )}
      </div>
    </div>
  )
}

export function DeviceIdentifyPage() {
  const { mac = '' } = useParams<{ mac: string }>()
  const q = useDeviceIdentify(mac)

  return (
    <div className="space-y-6">
      <div>
        <Link to="/devices" className="text-sm text-brand-accent hover:underline">← Devices</Link>
        <h1 className="text-xl font-bold text-brand-ink mt-2">What is this device?</h1>
        <p className="text-xs text-brand-text-muted font-mono">{mac}</p>
      </div>

      {q.isPending ? (
        <PageLoader />
      ) : q.isError ? (
        <div role="alert" data-testid="identify-error" className="bg-red-500/10 border border-red-500/20 text-red-700 rounded-2xl px-5 py-4 text-sm">
          {httpStatusOf(q.error) === 404
            ? 'This device was not found in your household.'
            : "Couldn't load this device's traffic."}
          <button onClick={() => q.refetch()} className="ml-3 underline">Try again</button>
        </div>
      ) : (
        <>
          <IdentityCard d={q.data} />
          <Traffic d={q.data} />
        </>
      )}
    </div>
  )
}
