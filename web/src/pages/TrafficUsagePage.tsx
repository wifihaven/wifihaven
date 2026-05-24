import { useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
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
import { HeaderFilter } from '@/components/usage/HeaderFilter'
import {
  fmtBytes,
  fmtDuration,
  localTime,
  windowFromTo,
} from '@/components/usage/usageHelpers'

// #846 — Traffic Usage page. Raw + aggregated views over traffic_reports.
// Column headers double as groupBy toggles (Host=domain, Device=device,
// Profile=profile, App=app). apex still deferred to #856; app turned on by
// #769 (server-side join through app_hosts).

const DEFAULT_RAW_LIMIT = 100

const TRAFFIC_GROUP_KEYS: TrafficUsageGroupBy[] = ['domain', 'device', 'profile', 'app']

function parseGroupByFromSearch(sp: URLSearchParams): TrafficUsageGroupBy[] {
  // #917: serialize as repeated ?groupBy=host&groupBy=device so links
  // round-trip. We also accept the legacy comma-separated single param.
  const raw = sp.getAll('groupBy').flatMap(v => v.split(',')).map(v => v.trim()).filter(Boolean)
  const allowed = new Set<string>(TRAFFIC_GROUP_KEYS)
  return raw.filter(g => allowed.has(g)) as TrafficUsageGroupBy[]
}

export function TrafficUsagePage() {
  const [searchParams, setSearchParams] = useSearchParams()
  const [bucket, setBucket]     = useState<TrafficUsageBucket>('raw')
  // #917: default is "everything rolled up" — empty groupBy = one row per
  // time bucket. Each column toggle is strictly additive (adds rows).
  const [groupBy, setGroupBy]   = useState<TrafficUsageGroupBy[]>(() => parseGroupByFromSearch(searchParams))
  // #865: multi-select filters. Empty = no filter on that column.
  const [macs, setMacs]                 = useState<string[]>([])
  const [profileIds, setProfileIds]     = useState<number[]>([])
  const [devices, setDevices]   = useState<Device[]>([])
  const [profiles, setProfiles] = useState<ProfileDetail[]>([])
  const [data, setData]         = useState<TrafficUsageResponse | null>(null)
  const [loading, setLoading]   = useState(false)
  const [error, setError]       = useState<string | null>(null)
  // #769: surface the "no apps yet" empty-state on group-by=app.
  const [appCount, setAppCount] = useState<number | null>(null)

  useEffect(() => {
    api.devices.list().then(setDevices).catch(() => setDevices([]))
    api.profiles.list().then(setProfiles).catch(() => setProfiles([]))
    api.apps.list().then(xs => setAppCount(xs.length)).catch(() => setAppCount(0))
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
        macs:       macs.length       ? macs       : undefined,
        profileIds: profileIds.length ? profileIds : undefined,
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
  }, [bucket, groupBy, macs.join(','), profileIds.join(',')])

  function toggleGroup(key: string) {
    setGroupBy(prev => {
      const next = prev.includes(key as TrafficUsageGroupBy)
        ? prev.filter(g => g !== key)
        : [...prev, key as TrafficUsageGroupBy]
      const sp = new URLSearchParams(searchParams)
      sp.delete('groupBy')
      for (const g of next) sp.append('groupBy', g)
      setSearchParams(sp, { replace: true })
      return next
    })
  }

  return (
    <div className="space-y-4 min-w-0" data-testid="traffic-usage-page">
      <header>
        <h1 className="text-xl sm:text-2xl font-bold text-gray-100">Traffic Usage</h1>
        <p className="text-xs sm:text-sm text-gray-500">
          Raw <code className="text-emerald-400">traffic_reports</code> rows plus on-the-fly
          aggregation. Click a column header (Host / Device / Profile) to add it to the
          aggregation, or the funnel icon to filter that column.
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

      {error && <ErrorBanner message={error} />}
      {loading && <Spinner />}

      {data && bucket === 'raw' && !loading && (
        <RawTable
          data={data}
          devices={devices}
          profiles={profiles}
          macs={macs}
          profileIds={profileIds}
          onMacsChange={setMacs}
          onProfileIdsChange={setProfileIds}
        />
      )}
      {data && bucket !== 'raw' && !loading && groupBy.includes('app') && appCount === 0 && (
        <div
          data-testid="traffic-app-empty"
          className="rounded border border-emerald-900 bg-emerald-950/30 p-4 text-sm text-emerald-200"
        >
          No apps defined yet. <Link to="/apps" className="underline">Create one</Link> to group
          traffic by app.
        </div>
      )}
      {data && bucket !== 'raw' && !loading && !(groupBy.includes('app') && appCount === 0) && (
        <AggregateTable
          data={data}
          groupBy={groupBy}
          onToggleGroup={toggleGroup}
          devices={devices}
          profiles={profiles}
          macs={macs}
          profileIds={profileIds}
          onMacsChange={setMacs}
          onProfileIdsChange={setProfileIds}
        />
      )}
    </div>
  )
}

interface ShelfProps {
  devices: Device[]
  profiles: ProfileDetail[]
  macs: string[]
  profileIds: number[]
  bucket: TrafficUsageBucket
  onMacsChange: (v: string[]) => void
  onProfileIdsChange: (v: number[]) => void
  onBucketChange: (b: TrafficUsageBucket) => void
}

// #865: shelf carries the bucket selector + a chip summary of any active
// column-header filters. Device / Profile dropdowns moved into column
// headers as multi-select popovers.
export function FilterShelf({
  devices, profiles, macs, profileIds, bucket,
  onMacsChange, onProfileIdsChange, onBucketChange,
}: ShelfProps) {
  const deviceLabel = (m: string) => devices.find(d => d.mac === m)?.name ?? m
  const profileLabel = (pid: number) =>
    profiles.find(p => p.profile.id === pid)?.profile.name ?? `#${pid}`
  const hasChips = macs.length > 0 || profileIds.length > 0
  return (
    <div className="space-y-3 bg-gray-900/40 rounded p-3 border border-gray-800">
      <BucketSelector value={bucket} onChange={onBucketChange} />
      {hasChips && (
        <div className="flex flex-wrap items-center gap-2" data-testid="active-filters">
          <span className="text-[11px] uppercase tracking-wide text-gray-500">Filters:</span>
          {macs.map(m => (
            <Chip
              key={`mac-${m}`}
              testId={`chip-mac-${m}`}
              label={`Device: ${deviceLabel(m)}`}
              onRemove={() => onMacsChange(macs.filter(x => x !== m))}
            />
          ))}
          {profileIds.map(pid => (
            <Chip
              key={`pid-${pid}`}
              testId={`chip-profile-${pid}`}
              label={`Profile: ${profileLabel(pid)}`}
              onRemove={() => onProfileIdsChange(profileIds.filter(x => x !== pid))}
            />
          ))}
          <button
            type="button"
            data-testid="clear-filters"
            onClick={() => { onMacsChange([]); onProfileIdsChange([]) }}
            className="text-[11px] text-gray-500 hover:text-gray-300 underline"
          >
            clear all
          </button>
        </div>
      )}
    </div>
  )
}

function Chip({ testId, label, onRemove }: { testId: string; label: string; onRemove: () => void }) {
  return (
    <span
      data-testid={testId}
      className="inline-flex items-center gap-1 text-[11px] bg-emerald-950/50 border border-emerald-900 text-emerald-300 rounded px-2 py-0.5"
    >
      {label}
      <button
        type="button"
        onClick={onRemove}
        aria-label="remove"
        className="text-emerald-400 hover:text-red-400"
      >
        ×
      </button>
    </span>
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

interface FilterHeaderProps {
  devices: Device[]
  profiles: ProfileDetail[]
  macs: string[]
  profileIds: number[]
  onMacsChange: (v: string[]) => void
  onProfileIdsChange: (v: number[]) => void
}

// Helpers shared by Raw and Aggregate tables.
function useDeviceOptions(devices: Device[]) {
  return useMemo(
    () => devices.map(d => ({ value: d.mac, label: d.name })),
    [devices],
  )
}

function useProfileOptions(profiles: ProfileDetail[]) {
  return useMemo(
    () => profiles.map(p => ({ value: String(p.profile.id), label: p.profile.name })),
    [profiles],
  )
}

function DeviceHeaderCell({
  label, testIdPrefix, devices, macs, onMacsChange,
}: {
  label: string
  testIdPrefix: string
  devices: Device[]
  macs: string[]
  onMacsChange: (v: string[]) => void
}) {
  const opts = useDeviceOptions(devices)
  return (
    <span className="inline-flex items-center gap-1">
      <span>{label}</span>
      <HeaderFilter
        testId={`${testIdPrefix}-filter-device`}
        title="Filter device"
        options={opts}
        selected={macs}
        onChange={onMacsChange}
        searchable={devices.length > 12}
      />
    </span>
  )
}

function ProfileHeaderCell({
  label, testIdPrefix, profiles, profileIds, onProfileIdsChange,
}: {
  label: string
  testIdPrefix: string
  profiles: ProfileDetail[]
  profileIds: number[]
  onProfileIdsChange: (v: number[]) => void
}) {
  const opts = useProfileOptions(profiles)
  return (
    <span className="inline-flex items-center gap-1">
      <span>{label}</span>
      <HeaderFilter
        testId={`${testIdPrefix}-filter-profile`}
        title="Filter profile"
        options={opts}
        selected={profileIds.map(String)}
        onChange={next => onProfileIdsChange(next.map(Number))}
      />
    </span>
  )
}

interface RawTableProps extends FilterHeaderProps {
  data: TrafficUsageResponse
}

function RawTable({
  data, devices, profiles, macs, profileIds, onMacsChange, onProfileIdsChange,
}: RawTableProps) {
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
            <th className="text-left px-2 py-1">
              <DeviceHeaderCell
                label="Device"
                testIdPrefix="traffic"
                devices={devices}
                macs={macs}
                onMacsChange={onMacsChange}
              />
            </th>
            <th className="text-left px-2 py-1 hidden md:table-cell">
              <ProfileHeaderCell
                label="Profile"
                testIdPrefix="traffic"
                profiles={profiles}
                profileIds={profileIds}
                onProfileIdsChange={onProfileIdsChange}
              />
            </th>
            <th className="text-left px-2 py-1">Host</th>
            <th className="text-right px-2 py-1">Inbound</th>
            <th className="text-right px-2 py-1 hidden sm:table-cell">Outbound</th>
            <th className="text-right px-2 py-1 hidden md:table-cell">Time</th>
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
              <td className="px-2 py-1 font-mono text-xs whitespace-nowrap">{localTime(r.periodStart)}</td>
              <td className="px-2 py-1">{r.deviceName ?? r.mac}</td>
              <td className="px-2 py-1 hidden md:table-cell">{r.profileName ?? '-'}</td>
              <td className="px-2 py-1 max-w-[180px] sm:max-w-none truncate"><HostCell host={r.host} /></td>
              <td className="px-2 py-1 text-right font-mono">{fmtBytes(r.bytesIn)}</td>
              <td className="px-2 py-1 text-right font-mono hidden sm:table-cell">{fmtBytes(r.bytesOut)}</td>
              <td className="px-2 py-1 text-right font-mono hidden md:table-cell">{fmtDuration(r.activeSeconds)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}

interface AggProps extends FilterHeaderProps {
  data: TrafficUsageResponse
  groupBy: TrafficUsageGroupBy[]
  onToggleGroup: (key: string) => void
}

// #769: app-column cell. When grouped, renders the app's icon + display
// name (or "Other" for the synthetic `__other__` bucket). When not grouped,
// falls back to the same sole / distinct-count behaviour as the other
// columns.
function AppCell({
  active,
  appName,
  appIcon,
  sole,
  count,
}: {
  active: boolean
  appName: string | null | undefined
  appIcon: string | null | undefined
  sole: string | null | undefined
  count: number | undefined
}) {
  if (active) {
    return (
      <span className="inline-flex items-center gap-1.5">
        {appIcon && (
          <span aria-hidden="true" className="text-base leading-none">{appIcon}</span>
        )}
        <span>{appName ?? 'Other'}</span>
      </span>
    )
  }
  if (sole) return <span className="text-gray-400">{sole}</span>
  return <span className="text-gray-500">{count ?? 0}</span>
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

function AggregateTable({
  data, groupBy, onToggleGroup,
  devices, profiles, macs, profileIds, onMacsChange, onProfileIdsChange,
}: AggProps) {
  return (
    <div className="overflow-x-auto" data-testid="aggregate-table">
      <table className="min-w-full text-sm">
        <thead className="text-xs uppercase">
          <tr className="text-gray-500">
            <th className="text-left px-2 py-1">Window start</th>
            <th className="text-left px-2 py-1">
              <span className="inline-flex items-center gap-1">
                <GroupableHeader
                  label="Device"
                  groupKey="device"
                  groupBy={groupBy}
                  onToggle={onToggleGroup}
                  testIdPrefix="traffic-group"
                />
                <HeaderFilter
                  testId="traffic-filter-device"
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
                <GroupableHeader
                  label="Profile"
                  groupKey="profile"
                  groupBy={groupBy}
                  onToggle={onToggleGroup}
                  testIdPrefix="traffic-group"
                />
                <HeaderFilter
                  testId="traffic-filter-profile"
                  title="Filter profile"
                  options={profiles.map(p => ({ value: String(p.profile.id), label: p.profile.name }))}
                  selected={profileIds.map(String)}
                  onChange={next => onProfileIdsChange(next.map(Number))}
                />
              </span>
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
            <th className="text-left px-2 py-1">
              <GroupableHeader
                label="App"
                groupKey="app"
                groupBy={groupBy}
                onToggle={onToggleGroup}
                testIdPrefix="traffic-group"
              />
            </th>
            <th className="text-right px-2 py-1">Inbound</th>
            <th className="text-right px-2 py-1 hidden sm:table-cell">Outbound</th>
            <th className="text-right px-2 py-1 hidden md:table-cell">Time</th>
          </tr>
        </thead>
        <tbody className="text-gray-300">
          {data.aggregateRows.length === 0 && (
            <tr>
              <td colSpan={8} className="text-center text-gray-500 py-4">
                No rows in window.
              </td>
            </tr>
          )}
          {data.aggregateRows.map((r, i) => {
            // Collapse repeated window_start values: show only on first row
            // of each window so window boundaries are easy to scan.
            const prevWindow = i > 0 ? data.aggregateRows[i - 1].windowStart : null
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
              <td className="px-2 py-1 max-w-[180px] sm:max-w-none truncate">
                <NonGroupedCell
                  groupedValue={groupBy.includes('domain') ? r.groups.domain : undefined}
                  sole={r.soleDomain}
                  count={r.distinctDomains}
                />
              </td>
              <td className="px-2 py-1">
                <AppCell
                  active={groupBy.includes('app')}
                  appName={r.appName}
                  appIcon={r.appIcon}
                  sole={r.soleApp}
                  count={r.distinctApps}
                />
              </td>
              <td className="px-2 py-1 text-right font-mono">{fmtBytes(r.totalBytesIn)}</td>
              <td className="px-2 py-1 text-right font-mono hidden sm:table-cell">{fmtBytes(r.totalBytesOut)}</td>
              <td className="px-2 py-1 text-right font-mono hidden md:table-cell">{fmtDuration(r.totalSeconds)}</td>
            </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
