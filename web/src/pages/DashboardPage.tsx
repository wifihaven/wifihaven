import { useEffect, useState } from 'react'
import { api } from '@/api/client'
import { useDashboardNow } from '@/api/queries'
import type {
  DashboardNowDevice,
  DashboardNowProfile,
  DashboardStats,
  QueryLog,
} from '@/types/api'
import { HostCell } from '@/components/HostCell'

export function DashboardPage() {
  const [stats,  setStats]  = useState<DashboardStats | null>(null)
  const [logs,   setLogs]   = useState<QueryLog[]>([])
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    Promise.all([api.logs.stats(), api.logs.query({ limit: 30 })])
      .then(([s, l]) => { setStats(s); setLogs(l) })
      .finally(() => setLoading(false))
  }, [])

  if (loading) return <PageLoader />

  return (
    <div className="space-y-6">
      <h1 className="text-xl font-bold text-white">Dashboard</h1>

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
            <section className="bg-gray-900 rounded-2xl border border-gray-800 p-5">
              <h2 className="text-sm font-semibold text-gray-400 uppercase tracking-wider mb-4">
                Top Blocked (24h)
              </h2>
              {stats.topBlocked.length === 0
                ? <p className="text-gray-600 text-sm">No blocked queries yet</p>
                : stats.topBlocked.map(d => (
                    <div key={`${d.host.type}:${d.host.value}`} className="flex justify-between items-center py-2 border-b border-gray-800 last:border-0">
                      <span className="font-mono text-sm text-gray-300 truncate"><HostCell host={d.host} /></span>
                      <span className="text-red-400 font-mono text-sm ml-4 shrink-0">{d.count}</span>
                    </div>
                  ))
              }
            </section>

            <section className="bg-gray-900 rounded-2xl border border-gray-800 p-5">
              <h2 className="text-sm font-semibold text-gray-400 uppercase tracking-wider mb-4">
                Per Device (24h)
              </h2>
              {stats.perDevice.map(d => (
                <div key={d.mac} className="flex items-center gap-3 py-2 border-b border-gray-800 last:border-0">
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-medium text-white truncate">{d.deviceName}</p>
                    <p className="text-xs text-gray-600 font-mono">{d.mac}</p>
                  </div>
                  <div className="text-right shrink-0">
                    <p className="text-sm text-gray-300">{d.total} queries</p>
                    <p className="text-xs text-red-400">{d.blocked} blocked</p>
                  </div>
                </div>
              ))}
            </section>
          </div>
        </>
      )}

      <section className="bg-gray-900 rounded-2xl border border-gray-800 overflow-hidden">
        <div className="px-5 py-4 border-b border-gray-800">
          <h2 className="text-sm font-semibold text-gray-400 uppercase tracking-wider">Recent Queries</h2>
        </div>
        <div className="overflow-x-auto">
          <LogTable logs={logs} />
        </div>
      </section>
    </div>
  )
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
      <h2 className="text-sm font-semibold text-gray-400 uppercase tracking-wider">Now</h2>
      {data === null
        ? <p className="text-gray-600 text-sm">Loading live activity…</p>
        : data.profiles.length === 0
          ? <p className="text-gray-600 text-sm">No profiles configured yet.</p>
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
      className={`bg-gray-900 rounded-2xl border p-5 ${idle ? 'border-gray-800 opacity-60' : 'border-emerald-900/50'}`}
    >
      <div className="flex items-center gap-2 mb-3">
        <h3 className="text-base font-semibold text-white">{profile.name}</h3>
        {profile.paused && (
          <span className="text-[10px] font-bold uppercase tracking-wider bg-yellow-900/60 text-yellow-300 px-2 py-0.5 rounded">
            Paused
          </span>
        )}
      </div>
      {idle
        ? <p className="text-gray-600 text-xs">No activity in the last 5 minutes</p>
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
    <div data-testid={`now-device-${device.mac}`} className="border-t border-gray-800 first:border-0 pt-3 first:pt-0">
      <div className="flex items-baseline justify-between gap-2">
        <p className="text-sm font-medium text-white truncate">{device.name}</p>
        <p className="text-xs text-gray-500 shrink-0">{formatLastSeen(device.lastSeenSeconds)}</p>
      </div>
      {device.currentSession && (
        <p className="text-xs text-emerald-400 mt-1">
          watching <span className="font-mono"><HostCell host={device.currentSession.host} /></span>
          {' · '}{formatDuration(device.currentSession.durationSeconds)}
        </p>
      )}
      {device.topHosts.length > 0 && (
        <ul className="mt-2 space-y-0.5">
          {device.topHosts.map(h => (
            <li key={`${h.host.type}:${h.host.value}`} className="flex justify-between text-xs text-gray-400">
              <span className="font-mono truncate"><HostCell host={h.host} /></span>
              <span className="text-gray-600 ml-2 shrink-0">{formatDuration(h.activeSeconds)}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
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
    emerald: 'text-emerald-400',
    red: 'text-red-400',
    yellow: 'text-yellow-400',
  }
  return (
    <div className="bg-gray-900 rounded-2xl border border-gray-800 p-5">
      <p className="text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">{label}</p>
      <p className={`text-3xl font-bold ${colors[accent] ?? 'text-white'}`}>{value}</p>
    </div>
  )
}

function LogTable({ logs }: { logs: QueryLog[] }) {
  return (
    <table className="w-full text-xs font-mono">
      <thead>
        <tr className="text-gray-600 border-b border-gray-800">
          <th className="text-left px-4 py-2">Time</th>
          <th className="text-left px-4 py-2">Device</th>
          <th className="text-left px-4 py-2">Domain</th>
          <th className="text-left px-4 py-2">Status</th>
          <th className="text-left px-4 py-2 hidden md:table-cell">Reason</th>
        </tr>
      </thead>
      <tbody>
        {logs.map(l => (
          <tr key={l.id} className="border-b border-gray-800/50 hover:bg-gray-800/30">
            <td className="px-4 py-2 text-gray-500">{new Date(l.ts).toLocaleTimeString()}</td>
            <td className="px-4 py-2 text-yellow-400">{l.deviceName ?? l.mac ?? '?'}</td>
            <td className="px-4 py-2 text-gray-300 max-w-[200px] truncate"><HostCell host={l.host} /></td>
            <td className={`px-4 py-2 ${l.blocked ? 'text-red-400' : 'text-emerald-600'}`}>
              {l.blocked ? '✗ blocked' : '✓ ok'}
            </td>
            <td className="px-4 py-2 text-gray-600 hidden md:table-cell">{l.reason}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

function PageLoader() {
  return (
    <div className="flex items-center justify-center h-64">
      <div className="w-8 h-8 border-2 border-emerald-500 border-t-transparent rounded-full animate-spin" />
    </div>
  )
}

export { LogTable, PageLoader }
