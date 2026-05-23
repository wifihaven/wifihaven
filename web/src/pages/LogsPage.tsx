import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '@/api/client'
import type { ConnectionEventAggRow, Device, ProfileDetail, QueryLog, TrafficUsageBucket } from '@/types/api'
import { HostCell } from '@/components/HostCell'
import { GroupableHeader } from '@/components/usage/GroupableHeader'
import { HeaderFilter } from '@/components/usage/HeaderFilter'
import { FilterShelf } from './TrafficUsagePage'
import { localTime, windowFromTo } from '@/components/usage/usageHelpers'

// #846 — Connection Events page. Same look/feel as Traffic Usage; column
// headers double as group-by toggles. apex/app deferred to #856/#857.
type EventsGroupBy = 'domain' | 'device' | 'profile'
type EventsBucket  = TrafficUsageBucket  // shared with Traffic page; raw = /api/logs path

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
  const [bucket, setBucket]     = useState<EventsBucket>('raw')
  const [groupBy, setGroupBy]   = useState<EventsGroupBy[]>(['domain'])
  const [macs, setMacs]                 = useState<string[]>([])
  const [profileIds, setProfileIds]     = useState<number[]>([])
  const [devices, setDevices]   = useState<Device[]>([])
  const [profiles, setProfiles] = useState<ProfileDetail[]>([])

  useEffect(() => {
    api.devices.list().then(setDevices).catch(() => setDevices([]))
    api.profiles.list().then(setProfiles).catch(() => setProfiles([]))
  }, [])

  function toggleGroup(key: string) {
    setGroupBy(prev => {
      if (prev.includes(key as EventsGroupBy)) {
        const next = prev.filter(g => g !== key)
        return next.length === 0 ? prev : next
      }
      return [...prev, key as EventsGroupBy]
    })
  }

  return (
    <div className="space-y-4 min-w-0" data-testid="connection-events-page">
      <header>
        <h1 className="text-xl sm:text-2xl font-bold text-gray-100">Connection Events</h1>
        <p className="text-xs sm:text-sm text-gray-500">
          Per-query DNS / blocking decisions. Click a column header (Domain / Device /
          Profile) to add it to the aggregation, or the funnel icon to filter that column.
        </p>
      </header>

      <FilterShelf
        devices={devices}
        profiles={profiles}
        macs={macs}
        profileIds={profileIds}
        bucket={bucket}
        onMacsChange={setMacs}
        onProfileIdsChange={setProfileIds}
        onBucketChange={setBucket}
      />

      {bucket === 'raw'
        ? <>
            <div className="text-xs text-amber-400">
              Showing latest {RAW_EVENTS_LIMIT} events. Switch buckets to aggregate by window.
            </div>
            <RawEventsView
              macs={macs}
              profileIds={profileIds}
              devices={devices}
              profiles={profiles}
              onMacsChange={setMacs}
              onProfileIdsChange={setProfileIds}
            />
          </>
        : <AggregatedEventsView
            bucket={bucket}
            groupBy={groupBy}
            onToggleGroup={toggleGroup}
            macs={macs}
            profileIds={profileIds}
            devices={devices}
            profiles={profiles}
            onMacsChange={setMacs}
            onProfileIdsChange={setProfileIds}
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

interface FilterApi {
  macs: string[]
  profileIds: number[]
  devices: Device[]
  profiles: ProfileDetail[]
  onMacsChange: (v: string[]) => void
  onProfileIdsChange: (v: number[]) => void
}

interface RawProps extends FilterApi {}

const RAW_EVENTS_LIMIT = 200

// Connection-event rows are point events, not bucketed — so the operator
// just wants "show me the last N". No time window. (#846 audit). An `until=`
// API param to anchor at a specific moment lands in #863.
function RawEventsView({
  macs, profileIds, devices, profiles, onMacsChange, onProfileIdsChange,
}: RawProps) {
  const [logs, setLogs]       = useState<QueryLog[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError]     = useState<string | null>(null)

  // #865: translate selected mac list to deviceId list so /api/logs filters
  // on devices.id (the index path) rather than ce.mac.
  const deviceIds = useMemo(() => {
    if (!macs.length) return undefined
    const byMac = new Map(devices.map(d => [d.mac, d.id]))
    const ids = macs.map(m => byMac.get(m)).filter((x): x is number => x !== undefined)
    return ids.length ? ids : undefined
  }, [macs, devices])

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    // /api/logs requires `hours` — pass a wide cap (1y) so the row limit
    // dominates. When #863 lands we can drop the hours hack entirely.
    api.logs.query({
      hours:      24 * 365,
      deviceIds,
      profileIds: profileIds.length ? profileIds : undefined,
      limit:      RAW_EVENTS_LIMIT,
    })
      .then(d => { if (!cancelled) setLogs(d) })
      .catch(e => { if (!cancelled) { setLogs([]); setError(String(e.message ?? e)) } })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [deviceIds?.join(','), profileIds.join(',')])

  if (loading) return <Spinner />
  if (error)   return <ErrorBanner message={error} />

  return (
    <div className="overflow-x-auto" data-testid="ce-raw-table">
      <table className="min-w-full text-sm">
        <thead className="text-xs uppercase">
          <tr className="text-gray-500">
            <th className="text-left px-2 py-1">Time</th>
            <th className="text-left px-2 py-1">
              <span className="inline-flex items-center gap-1">
                <span>Device</span>
                <HeaderFilter
                  testId="ce-filter-device"
                  title="Filter device"
                  options={devices.map(d => ({ value: d.mac, label: d.name }))}
                  selected={macs}
                  onChange={onMacsChange}
                  searchable={devices.length > 12}
                />
              </span>
            </th>
            <th className="text-left px-2 py-1 hidden md:table-cell">
              <span className="inline-flex items-center gap-1">
                <span>Profile</span>
                <HeaderFilter
                  testId="ce-filter-profile"
                  title="Filter profile"
                  options={profiles.map(p => ({ value: String(p.profile.id), label: p.profile.name }))}
                  selected={profileIds.map(String)}
                  onChange={next => onProfileIdsChange(next.map(Number))}
                />
              </span>
            </th>
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

interface AggProps extends FilterApi {
  bucket: Exclude<EventsBucket, 'raw'>
  groupBy: EventsGroupBy[]
  onToggleGroup: (key: string) => void
}

function AggregatedEventsView({
  bucket, groupBy, onToggleGroup,
  macs, profileIds, devices, profiles, onMacsChange, onProfileIdsChange,
}: AggProps) {
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
      macs:       macs.length ? macs : undefined,
      profileIds: profileIds.length ? profileIds : undefined,
      hours:      Math.max(1, Math.ceil((new Date(to).getTime() - new Date(from).getTime()) / 3600000)),
      limit:      500,
    })
      .then(d => { if (!cancelled) setRows(d) })
      .catch(e => { if (!cancelled) { setRows([]); setError(String(e.message ?? e)) } })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [bucket, groupBy.join(','), macs.join(','), profileIds.join(',')])

  if (loading) return <Spinner />
  if (error)   return <ErrorBanner message={error} />

  return (
    <div className="overflow-x-auto" data-testid="ce-agg-table">
      <table className="min-w-full text-sm">
        <thead className="text-xs uppercase">
          <tr className="text-gray-500">
            <th className="text-left px-2 py-1">Window start</th>
            <th className="text-left px-2 py-1">
              <span className="inline-flex items-center gap-1">
                <GroupableHeader label="Device" groupKey="device" groupBy={groupBy}
                  onToggle={onToggleGroup} testIdPrefix="ce-group" />
                <HeaderFilter
                  testId="ce-filter-device"
                  title="Filter device"
                  options={devices.map(d => ({ value: d.mac, label: d.name }))}
                  selected={macs}
                  onChange={onMacsChange}
                  searchable={devices.length > 12}
                />
              </span>
            </th>
            <th className="text-left px-2 py-1 hidden md:table-cell">
              <span className="inline-flex items-center gap-1">
                <GroupableHeader label="Profile" groupKey="profile" groupBy={groupBy}
                  onToggle={onToggleGroup} testIdPrefix="ce-group" />
                <HeaderFilter
                  testId="ce-filter-profile"
                  title="Filter profile"
                  options={profiles.map(p => ({ value: String(p.profile.id), label: p.profile.name }))}
                  selected={profileIds.map(String)}
                  onChange={next => onProfileIdsChange(next.map(Number))}
                />
              </span>
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
