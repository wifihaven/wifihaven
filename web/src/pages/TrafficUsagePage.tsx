import { useEffect, useState } from 'react'
import { api } from '@/api/client'
import type {
  Device,
  ProfileDetail,
  TrafficUsageBucket,
  TrafficUsageGroupBy,
  TrafficUsageResponse,
} from '@/types/api'
import { HostCell } from '@/components/HostCell'
import { BucketSelector } from '@/components/usage/BucketSelector'
import { GroupableHeader } from '@/components/usage/GroupableHeader'
import {
  fmtBytes,
  fmtDuration,
  localTime,
  windowFromTo,
} from '@/components/usage/usageHelpers'

// #846 — Traffic Usage page. Raw + aggregated views over traffic_reports.
// Column headers double as groupBy toggles (Host=domain, Device=device,
// Profile=profile). apex/app are not exposed here — apex deferred to #856,
// app to #857.

const DEFAULT_RAW_LIMIT = 100

export function TrafficUsagePage() {
  const [bucket, setBucket]     = useState<TrafficUsageBucket>('raw')
  const [groupBy, setGroupBy]   = useState<TrafficUsageGroupBy[]>(['domain'])
  const [mac, setMac]           = useState<string>('')
  const [profileId, setProfileId] = useState<string>('')
  const [devices, setDevices]   = useState<Device[]>([])
  const [profiles, setProfiles] = useState<ProfileDetail[]>([])
  const [data, setData]         = useState<TrafficUsageResponse | null>(null)
  const [loading, setLoading]   = useState(false)
  const [error, setError]       = useState<string | null>(null)

  useEffect(() => {
    api.devices.list().then(setDevices).catch(() => setDevices([]))
    api.profiles.list().then(setProfiles).catch(() => setProfiles([]))
  }, [])

  // Window is bucket-derived, anchored at "now-at-fetch-time". No end-at
  // picker — paging older history is #862's job.
  useEffect(() => {
    let cancelled = false
    setLoading(true)
    setError(null)
    const { from, to } = windowFromTo(bucket, new Date().toISOString())
    api.usage
      .traffic({
        mac: mac || undefined,
        profileId: profileId ? Number(profileId) : undefined,
        from,
        to,
        bucket,
        groupBy: bucket === 'raw' ? undefined : groupBy,
        limit: bucket === 'raw' ? DEFAULT_RAW_LIMIT : undefined,
      })
      .then(d => {
        if (!cancelled) setData(d)
      })
      .catch((e: Error) => {
        if (!cancelled) {
          setError(e.message || 'failed to load')
          setData(null)
        }
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => { cancelled = true }
  }, [bucket, groupBy, mac, profileId])

  function toggleGroup(key: string) {
    setGroupBy(prev => {
      if (prev.includes(key as TrafficUsageGroupBy)) {
        const next = prev.filter(g => g !== key)
        // Always keep at least one group dimension on aggregate views.
        return next.length === 0 ? prev : next
      }
      return [...prev, key as TrafficUsageGroupBy]
    })
  }

  return (
    <div className="space-y-4" data-testid="traffic-usage-page">
      <header>
        <h1 className="text-2xl font-bold text-gray-100">Traffic Usage</h1>
        <p className="text-sm text-gray-500">
          Raw <code className="text-emerald-400">traffic_reports</code> rows plus on-the-fly
          aggregation. Click a column header (Host / Device / Profile) to add it to the
          aggregation.
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

      {error && <ErrorBanner message={error} />}
      {loading && <Spinner />}

      {data && bucket === 'raw' && !loading && (
        <RawTable data={data} />
      )}
      {data && bucket !== 'raw' && !loading && (
        <AggregateTable data={data} groupBy={groupBy} onToggleGroup={toggleGroup} />
      )}
    </div>
  )
}

interface ShelfProps {
  devices: Device[]
  profiles: ProfileDetail[]
  mac: string
  profileId: string
  bucket: TrafficUsageBucket
  onMacChange: (v: string) => void
  onProfileChange: (v: string) => void
  onBucketChange: (b: TrafficUsageBucket) => void
}

// Shared filter shelf — same shape used by Connection Events page.
export function FilterShelf({
  devices, profiles, mac, profileId, bucket,
  onMacChange, onProfileChange, onBucketChange,
}: ShelfProps) {
  return (
    <div className="space-y-3 bg-gray-900/40 rounded p-3 border border-gray-800">
      <BucketSelector value={bucket} onChange={onBucketChange} />
      <div className="flex flex-wrap gap-3">
        <label className="text-xs text-gray-400">
          Device
          <select
            data-testid="device-filter"
            value={mac}
            onChange={e => onMacChange(e.target.value)}
            className="block mt-1 bg-gray-950 border border-gray-800 rounded px-2 py-1 text-sm"
          >
            <option value="">all</option>
            {devices.map(d => (
              <option key={d.mac} value={d.mac}>{d.name} ({d.mac})</option>
            ))}
          </select>
        </label>
        <label className="text-xs text-gray-400">
          Profile
          <select
            data-testid="profile-filter"
            value={profileId}
            onChange={e => onProfileChange(e.target.value)}
            className="block mt-1 bg-gray-950 border border-gray-800 rounded px-2 py-1 text-sm"
          >
            <option value="">all</option>
            {profiles.map(p => (
              <option key={p.profile.id} value={p.profile.id}>{p.profile.name}</option>
            ))}
          </select>
        </label>
      </div>
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

function RawTable({ data }: { data: TrafficUsageResponse }) {
  return (
    <div className="overflow-x-auto" data-testid="raw-table">
      {data.rawRowsTruncated && (
        <div className="text-xs text-amber-400 mb-2">
          Showing latest {data.rawRowLimit} rows in window. Narrow the time range to see older ones.
        </div>
      )}
      <table className="min-w-full text-sm">
        <thead className="text-xs uppercase">
          <tr className="text-gray-500">
            <th className="text-left px-2 py-1">Period start</th>
            <th className="text-left px-2 py-1">Device</th>
            <th className="text-left px-2 py-1">Profile</th>
            <th className="text-left px-2 py-1">Host</th>
            <th className="text-right px-2 py-1">Bytes in</th>
            <th className="text-right px-2 py-1">Bytes out</th>
            <th className="text-right px-2 py-1">Seconds</th>
          </tr>
        </thead>
        <tbody className="text-gray-300">
          {data.rawRows.length === 0 && (
            <tr>
              <td colSpan={7} className="text-center text-gray-500 py-4">
                No rows in window.
              </td>
            </tr>
          )}
          {data.rawRows.map((r, i) => (
            <tr key={i} className="border-t border-gray-800">
              <td className="px-2 py-1 font-mono text-xs">{localTime(r.periodStart)}</td>
              <td className="px-2 py-1">{r.deviceName ?? r.mac}</td>
              <td className="px-2 py-1">{r.profileName ?? '-'}</td>
              <td className="px-2 py-1"><HostCell host={r.host} /></td>
              <td className="px-2 py-1 text-right font-mono">{fmtBytes(r.bytesIn)}</td>
              <td className="px-2 py-1 text-right font-mono">{fmtBytes(r.bytesOut)}</td>
              <td className="px-2 py-1 text-right font-mono">{r.activeSeconds}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

interface AggProps {
  data: TrafficUsageResponse
  groupBy: TrafficUsageGroupBy[]
  onToggleGroup: (key: string) => void
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

function AggregateTable({ data, groupBy, onToggleGroup }: AggProps) {
  return (
    <div className="overflow-x-auto" data-testid="aggregate-table">
      <table className="min-w-full text-sm">
        <thead className="text-xs uppercase">
          <tr className="text-gray-500">
            <th className="text-left px-2 py-1">Window start</th>
            <th className="text-left px-2 py-1">
              <GroupableHeader
                label="Device"
                groupKey="device"
                groupBy={groupBy}
                onToggle={onToggleGroup}
                testIdPrefix="traffic-group"
              />
            </th>
            <th className="text-left px-2 py-1">
              <GroupableHeader
                label="Profile"
                groupKey="profile"
                groupBy={groupBy}
                onToggle={onToggleGroup}
                testIdPrefix="traffic-group"
              />
            </th>
            <th className="text-left px-2 py-1">
              <GroupableHeader
                label="Host"
                groupKey="domain"
                groupBy={groupBy}
                onToggle={onToggleGroup}
                testIdPrefix="traffic-group"
              />
            </th>
            <th className="text-right px-2 py-1">Bytes in</th>
            <th className="text-right px-2 py-1">Bytes out</th>
            <th className="text-right px-2 py-1">Total seconds</th>
          </tr>
        </thead>
        <tbody className="text-gray-300">
          {data.aggregateRows.length === 0 && (
            <tr>
              <td colSpan={7} className="text-center text-gray-500 py-4">
                No rows in window.
              </td>
            </tr>
          )}
          {data.aggregateRows.map((r, i) => (
            <tr key={i} className="border-t border-gray-800">
              <td className="px-2 py-1 font-mono text-xs">{localTime(r.windowStart)}</td>
              <td className="px-2 py-1">
                <NonGroupedCell
                  groupedValue={groupBy.includes('device') ? r.groups.device : undefined}
                  sole={r.soleDevice}
                  count={r.distinctDevices}
                />
              </td>
              <td className="px-2 py-1">
                <NonGroupedCell
                  groupedValue={groupBy.includes('profile') ? r.groups.profile : undefined}
                  sole={r.soleProfile}
                  count={r.distinctProfiles}
                />
              </td>
              <td className="px-2 py-1">
                <NonGroupedCell
                  groupedValue={groupBy.includes('domain') ? r.groups.domain : undefined}
                  sole={r.soleDomain}
                  count={r.distinctDomains}
                />
              </td>
              <td className="px-2 py-1 text-right font-mono">{fmtBytes(r.totalBytesIn)}</td>
              <td className="px-2 py-1 text-right font-mono">{fmtBytes(r.totalBytesOut)}</td>
              <td className="px-2 py-1 text-right font-mono">{fmtDuration(r.totalSeconds)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
