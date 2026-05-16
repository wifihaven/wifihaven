import { useEffect, useMemo, useState } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api } from '@/api/client'
import type { Device, ProfileDetail, QueryLog, Session } from '@/types/api'
import { HostCell } from '@/components/HostCell'

type Tab = 'sessions' | 'raw'

interface Filters {
  deviceId: number | null
  profileId: number | null
}

export function LogsPage() {
  const [tab, setTab] = useState<Tab>('sessions')
  const [searchParams, setSearchParams] = useSearchParams()
  const [devices, setDevices] = useState<Device[]>([])
  const [profiles, setProfiles] = useState<ProfileDetail[]>([])

  useEffect(() => {
    api.devices.list().then(setDevices).catch(() => setDevices([]))
    api.profiles.list().then(setProfiles).catch(() => setProfiles([]))
  }, [])

  const filters: Filters = useMemo(() => ({
    deviceId:  numOrNull(searchParams.get('deviceId')),
    profileId: numOrNull(searchParams.get('profileId')),
  }), [searchParams])

  function updateFilter(key: keyof Filters, value: number | null) {
    const next = new URLSearchParams(searchParams)
    if (value === null) next.delete(key)
    else next.set(key, String(value))
    setSearchParams(next, { replace: true })
  }

  function clearFilters() {
    const next = new URLSearchParams(searchParams)
    next.delete('deviceId')
    next.delete('profileId')
    setSearchParams(next, { replace: true })
  }

  const hasFilters = filters.deviceId !== null || filters.profileId !== null

  return (
    <div className="space-y-6">
      <h1 className="text-xl font-bold text-white">Activity</h1>

      <FilterBar
        filters={filters}
        devices={devices}
        profiles={profiles.map(pd => pd.profile)}
        onChange={updateFilter}
        onClear={clearFilters}
      />

      <div className="flex gap-2" role="tablist">
        <TabButton id="sessions" current={tab} onClick={setTab}>Sessions</TabButton>
        <TabButton id="raw"      current={tab} onClick={setTab}>Connection events</TabButton>
      </div>

      {tab === 'sessions'
        ? <SessionsTab  filters={filters} hasFilters={hasFilters} onClear={clearFilters} />
        : <RawEventsTab filters={filters} hasFilters={hasFilters} onClear={clearFilters} />}
    </div>
  )
}

function numOrNull(v: string | null): number | null {
  if (v === null) return null
  const n = Number(v)
  return Number.isFinite(n) ? n : null
}

// ── Filter bar ─────────────────────────────────────────────────────────────

function FilterBar({
  filters, devices, profiles, onChange, onClear,
}: {
  filters: Filters
  devices: Device[]
  profiles: { id: number; name: string }[]
  onChange: (key: keyof Filters, value: number | null) => void
  onClear: () => void
}) {
  const active = filters.deviceId !== null || filters.profileId !== null
  return (
    <div className="flex flex-wrap gap-3 items-center" data-testid="logs-filter-bar">
      <label className="text-xs text-gray-500 font-mono">Filter:</label>
      <select
        data-testid="logs-filter-device"
        value={filters.deviceId ?? ''}
        onChange={e => onChange('deviceId', e.target.value === '' ? null : Number(e.target.value))}
        className="bg-gray-900 border border-gray-700 rounded-xl px-3 py-2 text-white text-sm"
      >
        <option value="">All devices</option>
        {devices.map(d => (
          <option key={d.id} value={d.id}>{d.name || d.mac}</option>
        ))}
      </select>
      <select
        data-testid="logs-filter-profile"
        value={filters.profileId ?? ''}
        onChange={e => onChange('profileId', e.target.value === '' ? null : Number(e.target.value))}
        className="bg-gray-900 border border-gray-700 rounded-xl px-3 py-2 text-white text-sm"
      >
        <option value="">All profiles</option>
        {profiles.map(p => (
          <option key={p.id} value={p.id}>{p.name}</option>
        ))}
      </select>
      {active && (
        <button
          type="button"
          onClick={onClear}
          data-testid="logs-filter-clear"
          className="text-xs text-gray-400 hover:text-white underline"
        >
          Clear
        </button>
      )}
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

function SessionsTab({
  filters, hasFilters, onClear,
}: { filters: Filters; hasFilters: boolean; onClear: () => void }) {
  const [sessions, setSessions] = useState<Session[]>([])
  const [loading,  setLoading]  = useState(true)
  const [host,     setHost]     = useState('')

  async function load() {
    setLoading(true)
    try {
      const page = await api.sessions.list({
        host:  host || undefined,
        deviceId:  filters.deviceId  ?? undefined,
        profileId: filters.profileId ?? undefined,
        hours: 24,
        limit: 100,
      })
      setSessions(page.sessions)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() }, [host, filters.deviceId, filters.profileId])

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
              {sessions.length === 0 && (
                <EmptyState
                  message={hasFilters ? 'No matching sessions.' : 'No sessions found.'}
                  hasFilters={hasFilters}
                  onClear={onClear}
                />
              )}
            </div>
        }
      </div>
    </div>
  )
}

// ── Connection events tab (every row is a connection_attempt from /api/logs) ─

function RawEventsTab({
  filters, hasFilters, onClear,
}: { filters: Filters; hasFilters: boolean; onClear: () => void }) {
  const [logs,    setLogs]    = useState<QueryLog[]>([])
  const [loading, setLoading] = useState(true)
  const [domain,  setDomain]  = useState('')
  const [blocked, setBlocked] = useState<'all' | 'true' | 'false'>('all')

  async function load() {
    setLoading(true)
    try {
      const data = await api.logs.query({
        domain:    domain || undefined,
        blocked:   blocked === 'all' ? undefined : blocked === 'true',
        deviceId:  filters.deviceId  ?? undefined,
        profileId: filters.profileId ?? undefined,
        limit:     200,
      })
      setLogs(data)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() }, [domain, blocked, filters.deviceId, filters.profileId])

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
              {logs.length === 0 && (
                <EmptyState
                  message={hasFilters ? 'No matching events.' : 'No events found.'}
                  hasFilters={hasFilters}
                  onClear={onClear}
                />
              )}
            </div>
        }
      </div>
    </div>
  )
}

function EmptyState({
  message, hasFilters, onClear,
}: { message: string; hasFilters: boolean; onClear: () => void }) {
  return (
    <p className="p-6 text-gray-500 text-sm" data-testid="logs-empty-state">
      {message}
      {hasFilters && (
        <>
          {' '}
          <button
            type="button"
            onClick={onClear}
            data-testid="logs-empty-clear"
            className="text-emerald-400 hover:text-emerald-300 underline"
          >
            Clear filters
          </button>
        </>
      )}
    </p>
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
