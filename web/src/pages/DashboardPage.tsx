import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '@/api/client'
import { useDashboardNow, useRecentBlocked } from '@/api/queries'
import type {
  DashboardNowDevice,
  DashboardNowProfile,
  DashboardStats,
  QueryLog,
} from '@/types/api'
import { HostCell } from '@/components/HostCell'
import { EmptyState } from '@/components/EmptyState'
import { AccessRequestsBanner, NewDevicesHint } from '@/components/AlertsPanel'
import { blockReasonText } from '@/types/blockReason'

export function DashboardPage() {
  const [stats,  setStats]  = useState<DashboardStats | null>(null)
  const [logs,   setLogs]   = useState<QueryLog[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    Promise.all([api.logs.stats(), api.logs.query({ limit: 30 })])
      .then(([s, l]) => { setStats(s); setLogs(l.rows) })
      .finally(() => setLoading(false))
  }, [])

  if (loading) return <PageLoader />

  return (
    <div className="space-y-6">
      <h1 className="text-xl font-bold text-brand-ink">Dashboard</h1>

      <NewDevicesHint />

      <AccessRequestsBanner />

      <RecentlyBlockedSection />

      <NowSection />

      {stats && (
        <>
          <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
            <StatCard label="Queries today"  value={stats.totalToday}   accent="emerald" />
            <StatCard label="Blocked today"  value={stats.blockedToday} accent="red" />
            <StatCard label="Queries (1h)"   value={stats.totalHour}    accent="emerald" />
            <StatCard label="Blocked (1h)"   value={stats.blockedHour}  accent="yellow" />
          </div>

          <div className="grid md:grid-cols-2 gap-6">
            <section className="bg-white rounded-2xl border border-brand-border p-5">
              <h2 className="text-sm font-semibold text-brand-text uppercase tracking-wider mb-4">
                Top Blocked (24h)
              </h2>
              {stats.topBlocked.length === 0
                ? <EmptyState variant="inline" title="No blocked queries yet" />
                : stats.topBlocked.map(d => (
                    <div key={`${d.host.type}:${d.host.value}`} className="flex justify-between items-center py-2 border-b border-brand-border last:border-0">
                      <span className="font-mono text-sm text-brand-text truncate"><HostCell host={d.host} /></span>
                      <span className="text-red-700 font-mono text-sm ml-4 shrink-0">{d.count}</span>
                    </div>
                  ))
              }
            </section>

            <section className="bg-white rounded-2xl border border-brand-border p-5">
              <h2 className="text-sm font-semibold text-brand-text uppercase tracking-wider mb-4">
                Per Device (24h)
              </h2>
              {stats.perDevice.map(d => (
                <div key={d.mac} className="flex items-center gap-3 py-2 border-b border-brand-border last:border-0">
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-medium text-brand-ink truncate">{d.deviceName}</p>
                    <p className="text-xs text-brand-text-muted font-mono">{d.mac}</p>
                  </div>
                  <div className="text-right shrink-0">
                    <p className="text-sm text-brand-text">{d.total} queries</p>
                    <p className="text-xs text-red-700">{d.blocked} blocked</p>
                  </div>
                </div>
              ))}
            </section>
          </div>
        </>
      )}

      <section className="bg-white rounded-2xl border border-brand-border overflow-hidden">
        <div className="px-5 py-4 border-b border-brand-border">
          <h2 className="text-sm font-semibold text-brand-text uppercase tracking-wider">Recent Queries</h2>
        </div>
        <div className="overflow-x-auto">
          <LogTable logs={logs} />
        </div>
      </section>
    </div>
  )
}

// ── "Most recently blocked" — live diagnostic feed, polls every 10s ──────────
//
// #1338: newest-first, un-aggregated list of the connection-layer drops in the
// trailing 15 minutes, the recency complement to "Top Blocked". When a site/app
// suddenly stops working the operator wants to see *what just got dropped* (the gstatic /
// ocsp / akamai dependency, the over-broad blocklist hit) without digging into
// the Connection Events page. A row is a real traffic-layer drop — DNS always
// resolves (memory/blocking_is_traffic_layer_not_dns.md). Reuses the dashboard's
// React Query polling + JWT/household scoping via useRecentBlocked.

export function RecentlyBlockedSection() {
  const { data = null } = useRecentBlocked()

  return (
    <section data-testid="recently-blocked-section" className="bg-white rounded-2xl border border-brand-border overflow-hidden">
      <div className="px-5 py-4 border-b border-brand-border flex items-center justify-between gap-3">
        <h2 className="text-sm font-semibold text-brand-text uppercase tracking-wider">
          Most Recently Blocked
        </h2>
        <Link to="/usage/events" className="text-xs text-brand-accent-dark hover:underline shrink-0">
          View all →
        </Link>
      </div>
      {data === null
        ? <p className="px-5 py-4 text-brand-text-muted text-sm">Loading recent blocks…</p>
        : data.length === 0
          ? <div className="px-5 py-4"><EmptyState variant="inline" title="Nothing blocked recently" /></div>
          : (
            <ul className="divide-y divide-brand-border">
              {data.map(row => <RecentlyBlockedRow key={row.id} row={row} />)}
            </ul>
          )
      }
    </section>
  )
}

function RecentlyBlockedRow({ row }: { row: QueryLog }) {
  const who = row.deviceName ?? row.mac ?? 'unknown device'
  return (
    <li data-testid={`recently-blocked-${row.id}`} className="px-5 py-2.5 flex items-center gap-3">
      <span className="text-xs text-brand-text-muted shrink-0 w-16 tabular-nums">
        {formatRelativeTime(row.ts)}
      </span>
      <span className="font-mono text-sm text-brand-ink truncate flex-1 min-w-0">
        <HostCell host={row.host} />
      </span>
      <span className="text-xs text-brand-text-muted truncate hidden sm:block max-w-[40%]">
        {who}{row.profileName ? ` · ${row.profileName}` : ''}
      </span>
      <span className="text-xs text-red-700 shrink-0">{blockReasonText(row.reason, { host: row.host.value })}</span>
    </li>
  )
}

// Relative "Xs/Xm/Xh ago" from an ISO timestamp. Mirrors formatLastSeen, which
// works in elapsed-seconds; here we derive the elapsed from now() at render time
// (React Query re-renders on each 10s poll, so the label stays roughly live).
function formatRelativeTime(tsIso: string): string {
  const elapsedMs = Date.now() - new Date(tsIso).getTime()
  return formatLastSeen(Math.max(0, elapsedMs / 1000))
}

// ── "Now" section — live snapshot, polls every 10s ───────────────────────────
//
// #803: TanStack Query handles the polling cadence + last-known-good fallback
// (it keeps the previous `data` reference when a refetch errors), so the
// imperative setInterval / try-catch from before is gone.

export function NowSection() {
  const { data = null } = useDashboardNow()

  return (
    <section data-testid="now-section" className="space-y-3">
      <h2 className="text-sm font-semibold text-brand-text uppercase tracking-wider">Now</h2>
      {data === null
        ? <p className="text-brand-text-muted text-sm">Loading live activity…</p>
        : data.profiles.length === 0
          ? <EmptyState variant="inline" title="No profiles configured yet." />
          : (
            <div className="grid md:grid-cols-2 gap-4">
              {data.profiles.map(p => <NowProfileCard key={p.id} profile={p} />)}
            </div>
          )
      }
    </section>
  )
}

function NowProfileCard({ profile }: { profile: DashboardNowProfile }) {
  const idle = profile.activeDevices.length === 0
  return (
    <div
      data-testid={`now-profile-${profile.id}`}
      className={`bg-white rounded-2xl border p-5 ${idle ? 'border-brand-border opacity-60' : 'border-brand-accent-dark/50'}`}
    >
      <div className="flex items-center gap-2 mb-3">
        <h3 className="text-base font-semibold text-brand-ink">{profile.name}</h3>
        {profile.paused && (
          <span className="text-[10px] font-bold uppercase tracking-wider bg-amber-100/60 text-amber-700 px-2 py-0.5 rounded">
            Paused
          </span>
        )}
      </div>
      {idle
        ? <EmptyState variant="inline" title="No activity in the last 5 minutes" />
        : (
          <div className="space-y-3">
            {profile.activeDevices.map(d => <NowDeviceRow key={d.mac} device={d} />)}
          </div>
        )
      }
    </div>
  )
}

function NowDeviceRow({ device }: { device: DashboardNowDevice }) {
  return (
    <div data-testid={`now-device-${device.mac}`} className="border-t border-brand-border first:border-0 pt-3 first:pt-0">
      <div className="flex items-baseline justify-between gap-2">
        <p className="text-sm font-medium text-brand-ink truncate">{device.name}</p>
        <p className="text-xs text-brand-text-muted shrink-0">{formatLastSeen(device.lastSeenSeconds)}</p>
      </div>
      <NowActivityLine device={device} />
      {device.topHosts.length > 0 && (
        <ul className="mt-2 space-y-0.5">
          {device.topHosts.map(h => (
            <li key={`${h.host.type}:${h.host.value}`} className="flex justify-between text-xs text-brand-text">
              <span className="font-mono truncate"><HostCell host={h.host} /></span>
              <span className="text-brand-text-muted ml-2 shrink-0">{formatDuration(h.activeSeconds)}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

// #852 — "watching X · Nm" line, the replacement for the old `currentSession` widget.
// Show `(active)` when the device is talking but the latest bucket has no resolvable top host
// (heartbeat-only, all-IP, or the bucket hasn't closed yet) — be honest about the unknown.
function NowActivityLine({ device }: { device: DashboardNowDevice }) {
  const a = device.nowActivity
  if (a == null) {
    return <p className="mt-1 text-xs text-brand-text-muted italic">(active)</p>
  }
  return (
    <p className="mt-1 text-xs text-brand-text">
      <span className="text-brand-text-muted">watching</span>{' '}
      <span className="font-mono"><HostCell host={a.topHost} /></span>
      {a.minutes != null && <span className="text-brand-text-muted"> · {a.minutes}m</span>}
    </p>
  )
}

function formatLastSeen(seconds: number): string {
  if (seconds < 60) return `${Math.max(0, Math.round(seconds))}s ago`
  if (seconds < 3600) return `${Math.round(seconds / 60)}m ago`
  return `${Math.round(seconds / 3600)}h ago`
}

function formatDuration(seconds: number): string {
  if (seconds < 60) return `${Math.round(seconds)}s`
  const mins = Math.round(seconds / 60)
  if (mins < 60) return `${mins}m`
  const h = Math.floor(mins / 60)
  const m = mins % 60
  return m === 0 ? `${h}h` : `${h}h ${m}m`
}

function StatCard({ label, value, accent }: { label: string; value: number; accent: string }) {
  const colors: Record<string, string> = {
    emerald: 'text-brand-accent',
    red: 'text-red-700',
    yellow: 'text-amber-700',
  }
  return (
    <div className="bg-white rounded-2xl border border-brand-border p-5">
      <p className="text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2">{label}</p>
      <p className={`text-3xl font-bold ${colors[accent] ?? 'text-brand-ink'}`}>{value}</p>
    </div>
  )
}

function LogTable({ logs }: { logs: QueryLog[] }) {
  return (
    <table className="w-full text-xs font-mono">
      <thead>
        <tr className="text-brand-text-muted border-b border-brand-border">
          <th className="text-left px-4 py-2">Time</th>
          <th className="text-left px-4 py-2">Device</th>
          <th className="text-left px-4 py-2">Domain</th>
          <th className="text-left px-4 py-2">Status</th>
          <th className="text-left px-4 py-2 hidden md:table-cell">Reason</th>
        </tr>
      </thead>
      <tbody>
        {logs.map(l => (
          <tr key={l.id} className="border-b border-brand-border/50 hover:bg-brand-alt/30">
            <td className="px-4 py-2 text-brand-text-muted">{new Date(l.ts).toLocaleTimeString()}</td>
            <td className="px-4 py-2 text-amber-700">{l.deviceName ?? l.mac ?? '?'}</td>
            <td className="px-4 py-2 text-brand-text max-w-[200px] truncate"><HostCell host={l.host} /></td>
            <td className={`px-4 py-2 ${l.blocked ? 'text-red-700' : 'text-brand-accent-dark'}`}>
              {l.blocked ? '✗ blocked' : '✓ ok'}
            </td>
            <td className="px-4 py-2 text-brand-text-muted hidden md:table-cell">{blockReasonText(l.reason, { host: l.host.value })}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function PageLoader() {
  return (
    <div className="flex items-center justify-center h-64">
      <div className="w-8 h-8 border-2 border-brand-accent border-t-transparent rounded-full animate-spin" />
    </div>
  )
}

export { LogTable, PageLoader, formatRelativeTime }
