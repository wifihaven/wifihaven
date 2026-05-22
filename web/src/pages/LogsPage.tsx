import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '@/api/client'
import type { ConnectionEventAggRow, Device, ProfileDetail, QueryLog, TrafficUsageBucket } from '@/types/api'
import { HostCell } from '@/components/HostCell'
import { BucketSelector } from '@/components/usage/BucketSelector'
import { DateRangePicker } from '@/components/usage/DateRangePicker'
import { GroupableHeader } from '@/components/usage/GroupableHeader'
import { FilterShelf } from './TrafficUsagePage'
import {
  localTime,
  presetRange,
  type RangePreset,
} from '@/components/usage/usageHelpers'

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
  const initial = useMemo(() => presetRange('24h'), [])
  const [from, setFrom]         = useState<string>(initial.from)
  const [to, setTo]             = useState<string>(initial.to)
  const [preset, setPreset]     = useState<RangePreset>('24h')
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
      if (prev.includes(key as EventsGroupBy)) {
        const next = prev.filter(g => g !== key)
        return next.length === 0 ? prev : next
      }
      return [...prev, key as EventsGroupBy]
    })
  }

  return (
    <div className="space-y-4" data-testid="connection-events-page">
      <header>
        <h1 className="text-2xl font-bold text-gray-100">Connection Events</h1>
        <p className="text-sm text-gray-500">
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
        from={from}
        to={to}
        preset={preset}
        onMacChange={v => { setMac(v); if (v) setProfileId('') }}
        onProfileChange={v => { setProfileId(v); if (v) setMac('') }}
        onBucketChange={setBucket}
        onRangeChange={r => { setFrom(r.from); setTo(r.to); setPreset(r.preset) }}
      />

      {bucket === 'raw'
        ? <RawEventsView mac={mac} profileId={profileId} from={from} to={to} devices={devices} />
        : <AggregatedEventsView
            bucket={bucket}
            groupBy={groupBy}
            onToggleGroup={toggleGroup}
            mac={mac}
            profileId={profileId}
            from={from}
            to={to}
          />}
    </div>
  )
}

function hoursBetween(from: string, to: string): number {
  const f = new Date(from).getTime()
  const t = new Date(to).getTime()
  return Math.max(1, Math.ceil((t - f) / (3600 * 1000)))
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
  from: string
  to: string
  devices: Device[]
}

function RawEventsView({ mac, profileId, from, to, devices }: RawProps) {
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
    // /api/logs uses `hours` not from/to; derive from the picked range.
    api.logs.query({
      hours:     hoursBetween(from, to),
      deviceId,
      profileId: profileId ? Number(profileId) : undefined,
      limit:     200,
    })
      .then(d => { if (!cancelled) setLogs(d) })
      .catch(e => { if (!cancelled) { setLogs([]); setError(String(e.message ?? e)) } })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [deviceId, profileId, from, to])

  if (loading) return <Spinner />
  if (error)   return <ErrorBanner message={error} />

  return (
    <div className="overflow-x-auto" data-testid="ce-raw-table">
      <table className="min-w-full text-sm">
        <thead className="text-xs uppercase">
          <tr className="text-gray-500">
            <th className="text-left px-2 py-1">Time</th>
            <th className="text-left px-2 py-1">Device</th>
            <th className="text-left px-2 py-1">Profile</th>
            <th className="text-left px-2 py-1">Domain</th>
            <th className="text-left px-2 py-1">Status</th>
            <th className="text-left px-2 py-1">Reason</th>
            <th className="text-left px-2 py-1">Location</th>
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
              <td className="px-2 py-1 font-mono text-xs">{localTime(l.ts)}</td>
              <td className="px-2 py-1"><DeviceLink mac={l.mac} deviceName={l.deviceName} /></td>
              <td className="px-2 py-1">{l.profileName ?? '-'}</td>
              <td className="px-2 py-1 max-w-[280px] truncate"><HostCell host={l.host} /></td>
              <td className={`px-2 py-1 ${l.blocked ? 'text-red-400' : 'text-emerald-500'}`}>
                {l.blocked ? '✗ blocked' : '✓ ok'}
              </td>
              <td className="px-2 py-1 text-gray-500">{l.reason}</td>
              <td className="px-2 py-1 text-gray-500">{l.location ?? ''}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

interface AggProps {
  bucket: Exclude<EventsBucket, 'raw'>
  groupBy: EventsGroupBy[]
  onToggleGroup: (key: string) => void
  mac: string
  profileId: string
  from: string
  to: string
}

function AggregatedEventsView({ bucket, groupBy, onToggleGroup, mac, profileId, from, to }: AggProps) {
  const [rows, setRows]       = useState<ConnectionEventAggRow[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError]     = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    if (bucket === 'raw') return
    api.logs.series({
      bucket: bucket as Exclude<EventsBucket, 'raw'>,
      groupBy,
      mac:       mac || undefined,
      profileId: profileId ? Number(profileId) : undefined,
      hours:     hoursBetween(from, to),
      limit:     500,
    })
      .then(d => { if (!cancelled) setRows(d) })
      .catch(e => { if (!cancelled) { setRows([]); setError(String(e.message ?? e)) } })
      .finally(() => { if (!cancelled) setLoading(false) })
    return () => { cancelled = true }
  }, [bucket, groupBy.join(','), mac, profileId, from, to])

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
            <th className="text-left px-2 py-1">
              <GroupableHeader label="Profile" groupKey="profile" groupBy={groupBy}
                onToggle={onToggleGroup} testIdPrefix="ce-group" />
            </th>
            <th className="text-left px-2 py-1">
              <GroupableHeader label="Domain" groupKey="domain" groupBy={groupBy}
                onToggle={onToggleGroup} testIdPrefix="ce-group" />
            </th>
            <th className="text-right px-2 py-1">OK</th>
            <th className="text-right px-2 py-1">Blocked</th>
            <th className="text-left px-2 py-1">Last seen</th>
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
          {rows.map((r, i) => (
            <tr key={i} className="border-t border-gray-800">
              <td className="px-2 py-1 font-mono text-xs">{localTime(r.windowStart)}</td>
              <td className="px-2 py-1">
                {groupBy.includes('device')
                  ? r.groups.device ?? '-'
                  : <span className="text-gray-500">{r.distinctDevices ?? 0}</span>}
              </td>
              <td className="px-2 py-1">
                {groupBy.includes('profile')
                  ? r.groups.profile ?? '-'
                  : <span className="text-gray-500">{r.distinctProfiles ?? 0}</span>}
              </td>
              <td className="px-2 py-1 max-w-[280px] truncate">
                {groupBy.includes('domain')
                  ? r.groups.domain ?? '-'
                  : <span className="text-gray-500">{r.distinctDomains ?? 0}</span>}
              </td>
              <td className="px-2 py-1 text-emerald-400 text-right">{r.countSucceeded}</td>
              <td className="px-2 py-1 text-red-400 text-right">{r.countBlocked}</td>
              <td className="px-2 py-1 font-mono text-xs">{localTime(r.lastSeen)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
