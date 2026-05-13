import { useEffect, useState } from 'react'
import { api } from '@/api/client'
import type { DashboardStats, QueryLog } from '@/types/api'

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
                    <div key={d.domain} className="flex justify-between items-center py-2 border-b border-gray-800 last:border-0">
                      <span className="font-mono text-sm text-gray-300 truncate">{d.domain}</span>
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
            <td className="px-4 py-2 text-gray-300 max-w-[200px] truncate">{l.domain}</td>
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
