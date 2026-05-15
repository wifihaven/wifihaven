import { useEffect, useState } from 'react'
import { api } from '@/api/client'
import type { QueryLog, Session } from '@/types/api'
import { HostCell } from '@/components/HostCell'

type Tab = 'sessions' | 'raw'

export function LogsPage() {
  const [tab, setTab] = useState<Tab>('sessions')

  return (
    <div className="space-y-6">
      <h1 className="text-xl font-bold text-white">Activity</h1>

      <div className="flex gap-2" role="tablist">
        <TabButton id="sessions" current={tab} onClick={setTab}>Sessions</TabButton>
        <TabButton id="raw"      current={tab} onClick={setTab}>Connection events</TabButton>
      </div>

      {tab === 'sessions' ? <SessionsTab /> : <RawEventsTab />}
    </div>
  )
}

function TabButton({
  id, current, onClick, children,
}: {
  id: Tab; current: Tab; onClick: (t: Tab) => void; children: React.ReactNode
}) {
  const active = id === current
  return (
    <button
      role="tab"
      aria-selected={active}
      data-testid={`logs-tab-${id}`}
      onClick={() => onClick(id)}
      className={`px-4 py-2 rounded-xl text-sm font-medium transition-colors ${
        active
          ? 'bg-emerald-600 text-white'
          : 'bg-gray-800 text-gray-300 hover:bg-gray-700'
      }`}
    >
      {children}
    </button>
  )
}

// ── Sessions tab ───────────────────────────────────────────────────────────

function SessionsTab() {
  const [sessions, setSessions] = useState<Session[]>([])
  const [loading,  setLoading]  = useState(true)
  const [host,     setHost]     = useState('')

  async function load() {
    setLoading(true)
    try {
      const page = await api.sessions.list({
        host:  host || undefined,
        hours: 24,
        limit: 100,
      })
      setSessions(page.sessions)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() }, [host])

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap gap-3">
        <input
          type="text"
          value={host}
          onChange={e => setHost(e.target.value)}
          placeholder="Filter by host…"
          data-testid="sessions-filter-host"
          className="bg-gray-900 border border-gray-700 rounded-xl px-4 py-2.5 text-white text-sm font-mono placeholder-gray-600 focus:outline-none focus:border-emerald-500 flex-1 min-w-[160px]"
        />
        <button onClick={load} className="bg-gray-800 hover:bg-gray-700 text-white text-sm px-4 py-2.5 rounded-xl transition-colors">
          Refresh
        </button>
      </div>

      <div className="bg-gray-900 rounded-2xl border border-gray-800 overflow-hidden">
        {loading
          ? <div className="p-8 flex justify-center"><div className="w-6 h-6 border-2 border-emerald-500 border-t-transparent rounded-full animate-spin"/></div>
          : <div className="overflow-x-auto">
              <table className="w-full text-xs font-mono">
                <thead>
                  <tr className="text-gray-600 border-b border-gray-800">
                    <th className="text-left px-4 py-3">Started</th>
                    <th className="text-left px-4 py-3">Device</th>
                    <th className="text-left px-4 py-3 hidden md:table-cell">Profile</th>
                    <th className="text-left px-4 py-3">Host</th>
                    <th className="text-left px-4 py-3">Duration</th>
                    <th className="text-left px-4 py-3 hidden lg:table-cell">Bytes</th>
                  </tr>
                </thead>
                <tbody>
                  {sessions.map(s => (
                    <tr key={`${s.mac}-${s.host.type}:${s.host.value}-${s.startedAt}`}
                        className="border-b border-gray-800/50 hover:bg-gray-800/30"
                        data-testid="session-row">
                      <td className="px-4 py-2.5 text-gray-500">{fmtStarted(s.startedAt)}</td>
                      <td className="px-4 py-2.5 text-yellow-400">{s.deviceName ?? s.mac}</td>
                      <td className="px-4 py-2.5 text-gray-400 hidden md:table-cell">{s.profileName ?? ''}</td>
                      <td className="px-4 py-2.5 text-gray-300 max-w-[200px] truncate"><HostCell host={s.host} /></td>
                      <td className="px-4 py-2.5 text-emerald-400">{fmtDuration(s.durationSeconds)}</td>
                      <td className="px-4 py-2.5 text-gray-600 hidden lg:table-cell">{fmtBytes(s.bytesIn + s.bytesOut)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {sessions.length === 0 && <p className="p-6 text-gray-500 text-sm">No sessions found.</p>}
            </div>
        }
      </div>
    </div>
  )
}

// ── Connection events tab (every row is a connection_attempt from /api/logs) ─

function RawEventsTab() {
  const [logs,    setLogs]    = useState<QueryLog[]>([])
  const [loading, setLoading] = useState(true)
  const [domain,  setDomain]  = useState('')
  const [blocked, setBlocked] = useState<'all' | 'true' | 'false'>('all')

  async function load() {
    setLoading(true)
    try {
      const data = await api.logs.query({
        domain:  domain || undefined,
        blocked: blocked === 'all' ? undefined : blocked === 'true',
        limit:   200,
      })
      setLogs(data)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() }, [domain, blocked])

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap gap-3">
        <input
          type="text"
          value={domain}
          onChange={e => setDomain(e.target.value)}
          placeholder="Filter by domain…"
          className="bg-gray-900 border border-gray-700 rounded-xl px-4 py-2.5 text-white text-sm font-mono placeholder-gray-600 focus:outline-none focus:border-emerald-500 flex-1 min-w-[160px]"
        />
        <select value={blocked} onChange={e => setBlocked(e.target.value as typeof blocked)}
          className="bg-gray-900 border border-gray-700 rounded-xl px-4 py-2.5 text-white text-sm">
          <option value="all">All queries</option>
          <option value="true">Blocked only</option>
          <option value="false">Allowed only</option>
        </select>
        <button onClick={load} className="bg-gray-800 hover:bg-gray-700 text-white text-sm px-4 py-2.5 rounded-xl transition-colors">
          Refresh
        </button>
      </div>

      <div className="bg-gray-900 rounded-2xl border border-gray-800 overflow-hidden">
        {loading
          ? <div className="p-8 flex justify-center"><div className="w-6 h-6 border-2 border-emerald-500 border-t-transparent rounded-full animate-spin"/></div>
          : <div className="overflow-x-auto">
              <table className="w-full text-xs font-mono">
                <thead>
                  <tr className="text-gray-600 border-b border-gray-800">
                    <th className="text-left px-4 py-3">Time</th>
                    <th className="text-left px-4 py-3">Device</th>
                    <th className="text-left px-4 py-3">Domain</th>
                    <th className="text-left px-4 py-3">Status</th>
                    <th className="text-left px-4 py-3 hidden md:table-cell">Reason</th>
                    <th className="text-left px-4 py-3 hidden lg:table-cell">Location</th>
                  </tr>
                </thead>
                <tbody>
                  {logs.map(l => (
                    <tr key={l.id} className="border-b border-gray-800/50 hover:bg-gray-800/30">
                      <td className="px-4 py-2.5 text-gray-500">{fmtTime(l.ts)}</td>
                      <td className="px-4 py-2.5 text-yellow-400">{l.deviceName ?? l.mac ?? '?'}</td>
                      <td className="px-4 py-2.5 text-gray-300 max-w-[200px] truncate"><HostCell host={l.host} /></td>
                      <td className={`px-4 py-2.5 ${l.blocked ? 'text-red-400' : 'text-emerald-600'}`}>
                        {l.blocked ? '✗ blocked' : '✓ ok'}
                      </td>
                      <td className="px-4 py-2.5 text-gray-600 hidden md:table-cell">{l.reason}</td>
                      <td className="px-4 py-2.5 text-gray-600 hidden lg:table-cell">{l.location ?? ''}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {logs.length === 0 && <p className="p-6 text-gray-500 text-sm">No events found.</p>}
            </div>
        }
      </div>
    </div>
  )
}

// ── formatters ─────────────────────────────────────────────────────────────

function fmtDuration(seconds: number): string {
  if (seconds < 60) return `${seconds}s`
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  if (m < 60) return s === 0 ? `${m}m` : `${m}m ${s}s`
  const h = Math.floor(m / 60)
  const mm = m % 60
  return mm === 0 ? `${h}h` : `${h}h ${mm}m`
}

function fmtBytes(n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  if (n < 1024 * 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)} MB`
  return `${(n / (1024 * 1024 * 1024)).toFixed(2)} GB`
}

function fmtStarted(iso: string): string {
  return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })
}

function fmtTime(iso: string): string {
  return new Date(iso).toLocaleTimeString()
}
