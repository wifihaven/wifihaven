import { useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api } from '@/api/client'
import type { ConnectionEventAggRow, Device, ProfileDetail, QueryLog, TrafficUsageBucket } from '@/types/api'
import { HostCell } from '@/components/HostCell'
import { GroupableHeader } from '@/components/usage/GroupableHeader'
import { FilterShelf } from './TrafficUsagePage'
import { localTime, windowFromTo } from '@/components/usage/usageHelpers'

// #846 — Connection Events page. Same look/feel as Traffic Usage; column
// headers double as group-by toggles. apex/app deferred to #856/#857.
type EventsGroupBy = 'domain' | 'device' | 'profile'
type EventsBucket  = TrafficUsageBucket  // shared with Traffic page; raw = /api/logs path

const EVENTS_GROUP_KEYS: EventsGroupBy[] = ['domain', 'device', 'profile']

function parseEventsGroupBy(sp: URLSearchParams): EventsGroupBy[] {
  // #917: repeated ?groupBy=device&groupBy=domain serialization; comma form
  // still accepted for back-compat.
  const raw = sp.getAll('groupBy').flatMap(v => v.split(',')).map(v => v.trim()).filter(Boolean)
  const allowed = new Set<string>(EVENTS_GROUP_KEYS)
  return raw.filter(g => allowed.has(g)) as EventsGroupBy[]
}

function DeviceLink({ mac, deviceName }: { mac: string | null; deviceName: string | null }) {
  if (deviceName && mac) {
    return (
      <Link
        to={`/devices?mac=${encodeURIComponent(mac)}`}
        data-testid={`logs-device-link-${mac}`}
        className="text-yellow-400 hover:underline"
      >
        {deviceName}
      </Link>
    )
  }
  return <span className="text-yellow-400">{mac ?? '?'}</span>
}

export function LogsPage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const [bucket, setBucket]     = useState<EventsBucket>('raw')
  // #917: default groupBy = [] — one row per time bucket. Toggles strictly add rows.
  const [groupBy, setGroupBy]   = useState<EventsGroupBy[]>(() => parseEventsGroupBy(searchParams))
  const [mac, setMac]           = useState<string>('')
  const [profileId, setProfileId] = useState<string>('')
  const [devices, setDevices]   = useState<Device[]>([])
  const [profiles, setProfiles] = useState<ProfileDetail[]>([])

  useEffect(() => {
    api.devices.list().then(setDevices).catch(() => setDevices([]))
    api.profiles.list().then(setProfiles).catch(() => setProfiles([]))
  }, [])

  function toggleGroup(key: string) {
    setGroupBy(prev => {
      const next = prev.includes(key as EventsGroupBy)
        ? prev.filter(g => g !== key)
        : [...prev, key as EventsGroupBy]
      const sp = new URLSearchParams(searchParams)
      sp.delete('groupBy')
      for (const g of next) sp.append('groupBy', g)
      setSearchParams(sp, { replace: true })
      return next
    })
  }

  return (
    <div className="space-y-4 min-w-0" data-testid="connection-events-page">
      <header>
        <h1 className="text-xl sm:text-2xl font-bold text-gray-100">Connection Events</h1>
        <p className="text-xs sm:text-sm text-gray-500">
          Per-query DNS / blocking decisions. Click a column header (Domain / Device /
          Profile) to add it to the aggregation.
        </p>
      </header>

      <FilterShelf
        devices={devices}
        profiles={profiles}
        mac={mac}
        profileId={profileId}
        bucket={bucket}
        onMacChange={v => { setMac(v); if (v) setProfileId('') }}
        onProfileChange={v => { setProfileId(v); if (v) setMac('') }}
        onBucketChange={setBucket}
      />

      {bucket === 'raw'
        ? <>
            <div className="text-xs text-amber-400">
              Showing latest {RAW_EVENTS_LIMIT} events. Switch buckets to aggregate by window.
            </div>
            <RawEventsView mac={mac} profileId={profileId} devices={devices} />
          </>
        : <AggregatedEventsView
            bucket={bucket}
            groupBy={groupBy}
            onToggleGroup={toggleGroup}
            mac={mac}
            profileId={profileId}
          />}
    </div>
  )
}

function Spinner() {
  return (
    <div className="flex items-center gap-2 text-gray-500 text-sm py-6" data-testid="loading">
      <span className="inline-block h-3 w-3 rounded-full border-2 border-gray-700 border-t-emerald-400 animate-spin" />
      Loading…
    </div>
  )
}

function ErrorBanner({ message }: { message: string }) {
  return (
    <div
      data-testid="error"
      className="px-3 py-2 rounded border border-red-800 bg-red-950/30 text-red-300 text-sm"
    >
      {message}
    </div>
  )
}

interface RawProps {
  mac: string
  profileId: string
  devices: Device[]
}

const RAW_EVENTS_LIMIT = 200

// Connection-event rows are point events, not bucketed — so the operator
// just wants "show me the last N". No time window. (#846 audit). An `until=`
// API param to anchor at a specific moment lands in #863.
function RawEventsView({ mac, profileId, devices }: RawProps) {
  const [logs, setLogs]       = useState<QueryLog[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError]     = useState<string | null>(null)

  const deviceId = useMemo(() => {
    if (!mac) return undefined
    return devices.find(d => d.mac === mac)?.id
  }, [mac, devices])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    // /api/logs requires `hours` — pass a wide cap (1y) so the row limit
    // dominates. When #863 lands we can drop the hours hack entirely.
    api.logs.query({
      hours:     24 * 365,
      deviceId,
      profileId: profileId ? Number(profileId) : undefined,
      limit:     RAW_EVENTS_LIMIT,
    })
      .then(d => { if (!cancelled) setLogs(d) })
      .catch(e => { if (!cancelled) { setLogs([]); setError(String(e.message ?? e)) } })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [deviceId, profileId])

  if (loading) return <Spinner />
  if (error)   return <ErrorBanner message={error} />

  return (
    <div className="overflow-x-auto" data-testid="ce-raw-table">
      <table className="min-w-full text-sm">
        <thead className="text-xs uppercase">
          <tr className="text-gray-500">
            <th className="text-left px-2 py-1">Time</th>
            <th className="text-left px-2 py-1">Device</th>
            <th className="text-left px-2 py-1 hidden md:table-cell">Profile</th>
            <th className="text-left px-2 py-1">Domain</th>
            <th className="text-left px-2 py-1">Status</th>
            <th className="text-left px-2 py-1 hidden sm:table-cell">Reason</th>
            <th className="text-left px-2 py-1 hidden lg:table-cell">Location</th>
          </tr>
        </thead>
        <tbody className="text-gray-300">
          {logs.length === 0 && (
            <tr>
              <td colSpan={7} className="text-center text-gray-500 py-4">
                No events in window.
              </td>
            </tr>
          )}
          {logs.map(l => (
            <tr key={l.id} className="border-t border-gray-800">
              <td className="px-2 py-1 font-mono text-xs whitespace-nowrap">{localTime(l.ts)}</td>
              <td className="px-2 py-1"><DeviceLink mac={l.mac} deviceName={l.deviceName} /></td>
              <td className="px-2 py-1 hidden md:table-cell">{l.profileName ?? '-'}</td>
              <td className="px-2 py-1 max-w-[160px] sm:max-w-[280px] truncate"><HostCell host={l.host} /></td>
              <td className={`px-2 py-1 whitespace-nowrap ${l.blocked ? 'text-red-400' : 'text-emerald-500'}`}>
                {l.blocked ? '✗ blocked' : '✓ ok'}
              </td>
              <td className="px-2 py-1 text-gray-500 hidden sm:table-cell">{l.reason}</td>
              <td className="px-2 py-1 text-gray-500 hidden lg:table-cell">{l.location ?? ''}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

// #846 follow-up: render strategy for non-grouped aggregated columns.
//   - column IS in groupBy   → show the per-row value
//   - column NOT in groupBy, distinct == 1 → show the sole value (dim)
//   - else                                  → show the distinct count
function NonGroupedCell({
  groupedValue,
  sole,
  count,
}: {
  groupedValue: string | undefined
  sole: string | null | undefined
  count: number | undefined
}) {
  if (groupedValue !== undefined) return <>{groupedValue}</>
  if (sole)                       return <span className="text-gray-400">{sole}</span>
  return <span className="text-gray-500">{count ?? 0}</span>
}

interface AggProps {
  bucket: Exclude<EventsBucket, 'raw'>
  groupBy: EventsGroupBy[]
  onToggleGroup: (key: string) => void
  mac: string
  profileId: string
}

function AggregatedEventsView({ bucket, groupBy, onToggleGroup, mac, profileId }: AggProps) {
  const [rows, setRows]       = useState<ConnectionEventAggRow[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError]     = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    const { from, to } = windowFromTo(bucket, new Date().toISOString())
    api.logs.series({
      bucket,
      groupBy,
      mac:       mac || undefined,
      profileId: profileId ? Number(profileId) : undefined,
      hours:     Math.max(1, Math.ceil((new Date(to).getTime() - new Date(from).getTime()) / 3600000)),
      limit:     500,
    })
      .then(d => { if (!cancelled) setRows(d) })
      .catch(e => { if (!cancelled) { setRows([]); setError(String(e.message ?? e)) } })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [bucket, groupBy.join(','), mac, profileId])

  if (loading) return <Spinner />
  if (error)   return <ErrorBanner message={error} />

  return (
    <div className="overflow-x-auto" data-testid="ce-agg-table">
      <table className="min-w-full text-sm">
        <thead className="text-xs uppercase">
          <tr className="text-gray-500">
            <th className="text-left px-2 py-1">Window start</th>
            <th className="text-left px-2 py-1">
              <GroupableHeader label="Device" groupKey="device" groupBy={groupBy}
                onToggle={onToggleGroup} testIdPrefix="ce-group" />
            </th>
            <th className="text-left px-2 py-1 hidden md:table-cell">
              <GroupableHeader label="Profile" groupKey="profile" groupBy={groupBy}
                onToggle={onToggleGroup} testIdPrefix="ce-group" />
            </th>
            <th className="text-left px-2 py-1">
              <GroupableHeader label="Domain" groupKey="domain" groupBy={groupBy}
                onToggle={onToggleGroup} testIdPrefix="ce-group" />
            </th>
            <th className="text-right px-2 py-1">OK</th>
            <th className="text-right px-2 py-1">Blocked</th>
            <th className="text-left px-2 py-1 hidden sm:table-cell">Last seen</th>
          </tr>
        </thead>
        <tbody className="text-gray-300">
          {rows.length === 0 && (
            <tr>
              <td colSpan={7} className="text-center text-gray-500 py-4">
                No events in window.
              </td>
            </tr>
          )}
          {rows.map((r, i) => {
            const prevWindow = i > 0 ? rows[i - 1].windowStart : null
            const showWindow = r.windowStart !== prevWindow
            return (
            <tr key={i} className={`${showWindow ? 'border-t-2 border-gray-700' : 'border-t border-gray-800/40'}`}>
              <td className="px-2 py-1 font-mono text-xs whitespace-nowrap">
                {showWindow ? localTime(r.windowStart) : ''}
              </td>
              <td className="px-2 py-1">
                <NonGroupedCell
                  groupedValue={groupBy.includes('device') ? r.groups.device : undefined}
                  sole={r.soleDevice}
                  count={r.distinctDevices}
                />
              </td>
              <td className="px-2 py-1 hidden md:table-cell">
                <NonGroupedCell
                  groupedValue={groupBy.includes('profile') ? r.groups.profile : undefined}
                  sole={r.soleProfile}
                  count={r.distinctProfiles}
                />
              </td>
              <td className="px-2 py-1 max-w-[160px] sm:max-w-[280px] truncate">
                <NonGroupedCell
                  groupedValue={groupBy.includes('domain') ? r.groups.domain : undefined}
                  sole={r.soleDomain}
                  count={r.distinctDomains}
                />
              </td>
              <td className="px-2 py-1 text-emerald-400 text-right">{r.countSucceeded}</td>
              <td className="px-2 py-1 text-red-400 text-right">{r.countBlocked}</td>
              <td className="px-2 py-1 font-mono text-xs whitespace-nowrap hidden sm:table-cell">{localTime(r.lastSeen)}</td>
            </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
