import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api } from '@/api/client'
import type { ConnectionEventAggRow, Device, ProfileDetail, QueryLog, TrafficUsageBucket } from '@/types/api'
import { blockReasonText } from '@/types/blockReason'
import { HostCell } from '@/components/HostCell'
import { GroupableHeader } from '@/components/usage/GroupableHeader'
import { HeaderFilter } from '@/components/usage/HeaderFilter'
import { FilterShelf } from './TrafficUsagePage'
import { localTime } from '@/components/usage/usageHelpers'
import { useInfiniteScroll } from '@/hooks/useInfiniteScroll'

// #862: infinite scroll page sizes + lookback. The /api/logs endpoint still
// requires `hours`, so we ask for a wide band and let the row cap bound.
const RAW_PAGE_SIZE = 200
const AGG_PAGE_SIZE = 500
// Raw uses keyset + LIMIT in SQL → wide band is fine (1y).
// Aggregated still in-memory-buckets the whole band on the server (#809
// will replace with rollup tables), so cap at 30d to stay inside the 31d
// on-the-fly cap. Beyond 30d shows end-of-stream — Jump-to-Date is #951.
const RAW_HOURS_BAND = 24 * 365
const AGG_HOURS_BAND = 24 * 30

// #846 — Connection Events page. Same look/feel as Traffic Usage; column
// headers double as group-by toggles. apex deferred to #856; app turned on
// by #769 (server-side join through app_hosts).
type EventsGroupBy = 'domain' | 'device' | 'profile' | 'app'
type EventsBucket  = TrafficUsageBucket  // shared with Traffic page; raw = /api/logs path

const EVENTS_GROUP_KEYS: EventsGroupBy[] = ['domain', 'device', 'profile', 'app']

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
  // #865: multi-select filters. Empty = no filter on that column.
  const [macs, setMacs]                 = useState<string[]>([])
  const [profileIds, setProfileIds]     = useState<number[]>([])
  // #951: optional anchor for the right edge of the window. null = "now".
  // Round-trips through ?until=<ISO> in the URL.
  const [until, setUntilState]          = useState<string | null>(() => searchParams.get('until'))
  const [devices, setDevices]   = useState<Device[]>([])
  const [profiles, setProfiles] = useState<ProfileDetail[]>([])
  // #769: surface the "no apps yet" empty-state when the operator drills on
  // app but the household hasn't created any. `null` = not yet fetched.
  const [appCount, setAppCount] = useState<number | null>(null)

  useEffect(() => {
    api.devices.list().then(setDevices).catch(() => setDevices([]))
    api.profiles.list().then(setProfiles).catch(() => setProfiles([]))
    api.apps.list().then(xs => setAppCount(xs.length)).catch(() => setAppCount(0))
  }, [])

  function setUntil(next: string | null) {
    setUntilState(next)
    const sp = new URLSearchParams(searchParams)
    if (next) sp.set('until', next)
    else sp.delete('until')
    setSearchParams(sp, { replace: true })
  }

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
        <h1 className="text-xl sm:text-2xl font-bold text-brand-ink">Connection Events</h1>
        <p className="text-xs sm:text-sm text-brand-text-muted">
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
        until={until}
        onMacsChange={setMacs}
        onProfileIdsChange={setProfileIds}
        onBucketChange={setBucket}
        onUntilChange={setUntil}
      />

      {bucket === 'raw'
        ? <RawEventsView
            macs={macs}
            profileIds={profileIds}
            devices={devices}
            profiles={profiles}
            until={until}
            onMacsChange={setMacs}
            onProfileIdsChange={setProfileIds}
          />
        : <AggregatedEventsView
            bucket={bucket}
            groupBy={groupBy}
            onToggleGroup={toggleGroup}
            macs={macs}
            profileIds={profileIds}
            devices={devices}
            profiles={profiles}
            appCount={appCount}
            until={until}
            onMacsChange={setMacs}
            onProfileIdsChange={setProfileIds}
          />}
    </div>
  )
}

function Spinner() {
  return (
    <div className="flex items-center gap-2 text-brand-text-muted text-sm py-6" data-testid="loading">
      <span className="inline-block h-3 w-3 rounded-full border-2 border-brand-border-strong border-t-brand-accent animate-spin" />
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

interface RawProps extends FilterApi {
  until: string | null
}

// #862: infinite scroll. Initial fetch anchored at NOW (or `until`); cursor
// walks back. /api/logs still requires `hours`, so we ask for a wide band
// (1y) and let the row cap bound.
function RawEventsView({
  macs, profileIds, devices, profiles, until, onMacsChange, onProfileIdsChange,
}: RawProps) {
  const [logs, setLogs]       = useState<QueryLog[]>([])
  const [cursor, setCursor]   = useState<string | null>(null)
  const [hasMore, setHasMore] = useState(true)
  const [loading, setLoading] = useState(false)
  const [error, setError]     = useState<string | null>(null)
  const sentinelRef           = useRef<HTMLDivElement | null>(null)

  // #865: translate selected mac list to deviceId list so /api/logs filters
  // on devices.id (the index path) rather than ce.mac.
  const deviceIds = useMemo(() => {
    if (!macs.length) return undefined
    const byMac = new Map(devices.map(d => [d.mac, d.id]))
    const ids = macs.map(m => byMac.get(m)).filter((x): x is number => x !== undefined)
    return ids.length ? ids : undefined
  }, [macs, devices])

  const filterKey = `${deviceIds?.join(',') ?? ''}|${profileIds.join(',')}|${until ?? ''}`

  const load = useCallback(
    async (curs: string | null) => {
      setLoading(true)
      setError(null)
      try {
        const page = await api.logs.query({
          hours:      RAW_HOURS_BAND,
          deviceIds,
          profileIds: profileIds.length ? profileIds : undefined,
          limit:      RAW_PAGE_SIZE,
          until:      until ?? undefined,
          cursor:     curs ?? undefined,
        })
        setLogs(prev => curs ? prev.concat(page.rows) : page.rows)
        setCursor(page.nextCursor ?? null)
        setHasMore(!!page.nextCursor)
      } catch (e) {
        setError(String((e as Error).message ?? e))
        setHasMore(false)
      } finally {
        setLoading(false)
      }
    },
    [deviceIds, profileIds, until],
  )

  useEffect(() => {
    setLogs([])
    setCursor(null)
    setHasMore(true)
    void load(null)
  }, [filterKey])

  useInfiniteScroll({
    sentinelRef,
    hasMore,
    loading,
    onLoadMore: () => { if (cursor) void load(cursor) },
  })

  if (error) return <ErrorBanner message={error} />

  return (
    <>
    <div className="overflow-x-auto" data-testid="ce-raw-table">
      <table className="min-w-full text-sm">
        <thead className="text-xs uppercase">
          <tr className="text-brand-text-muted">
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
        <tbody className="text-brand-text">
          {logs.length === 0 && !loading && (
            <tr>
              <td colSpan={7} className="text-center text-brand-text-muted py-4">
                No events in window.
              </td>
            </tr>
          )}
          {logs.map(l => (
            <tr key={l.id} className="border-t border-brand-border">
              <td className="px-2 py-1 font-mono text-xs whitespace-nowrap">{localTime(l.ts)}</td>
              <td className="px-2 py-1"><DeviceLink mac={l.mac} deviceName={l.deviceName} /></td>
              <td className="px-2 py-1 hidden md:table-cell">{l.profileName ?? '-'}</td>
              <td className="px-2 py-1 max-w-[160px] sm:max-w-[280px] truncate"><HostCell host={l.host} /></td>
              <td className={`px-2 py-1 whitespace-nowrap ${l.blocked ? 'text-red-400' : 'text-brand-accent'}`}>
                {l.blocked ? '✗ blocked' : '✓ ok'}
              </td>
              <td className="px-2 py-1 text-brand-text-muted hidden sm:table-cell">{blockReasonText(l.reason)}</td>
              <td className="px-2 py-1 text-brand-text-muted hidden lg:table-cell">{l.location ?? ''}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
    <div ref={sentinelRef} data-testid="scroll-sentinel" className="h-1" />
    {loading && <Spinner />}
    {!loading && hasMore && cursor && (
      <div className="flex justify-center py-3">
        <button
          type="button"
          data-testid="load-more"
          onClick={() => void load(cursor)}
          className="text-xs text-brand-text hover:text-brand-accent px-3 py-1.5 border border-brand-border-strong hover:border-brand-accent-dark rounded"
        >
          Load more
        </button>
      </div>
    )}
    {!hasMore && !loading && logs.length > 0 && (
      <div className="text-xs text-brand-text-muted text-center py-3" data-testid="end-of-stream">
        — end of history —
      </div>
    )}
    </>
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
  if (sole)                       return <span className="text-brand-text">{sole}</span>
  return <span className="text-brand-text-muted">{count ?? 0}</span>
}

// #769: app-column cell. When grouped, renders the app's icon + display name
// (or "Other" for the synthetic `__other__` bucket). When not grouped, falls
// back to the same sole / distinct-count behaviour as the other columns.
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
  if (sole) return <span className="text-brand-text">{sole}</span>
  return <span className="text-brand-text-muted">{count ?? 0}</span>
}

interface AggProps extends FilterApi {
  bucket: Exclude<EventsBucket, 'raw'>
  groupBy: EventsGroupBy[]
  onToggleGroup: (key: string) => void
  appCount: number | null
  until: string | null
}

function AggregatedEventsView({
  bucket, groupBy, onToggleGroup,
  macs, profileIds, devices, profiles, appCount, until,
  onMacsChange, onProfileIdsChange,
}: AggProps) {
  const [rows, setRows]       = useState<ConnectionEventAggRow[]>([])
  const [cursor, setCursor]   = useState<string | null>(null)
  const [hasMore, setHasMore] = useState(true)
  const [loading, setLoading] = useState(false)
  const [error, setError]     = useState<string | null>(null)
  const sentinelRef           = useRef<HTMLDivElement | null>(null)

  const filterKey = `${bucket}|${groupBy.join(',')}|${macs.join(',')}|${profileIds.join(',')}|${until ?? ''}`

  const load = useCallback(
    async (curs: string | null) => {
      setLoading(true)
      setError(null)
      try {
        const page = await api.logs.series({
          bucket,
          groupBy,
          macs:       macs.length ? macs : undefined,
          profileIds: profileIds.length ? profileIds : undefined,
          hours:      AGG_HOURS_BAND,
          limit:      AGG_PAGE_SIZE,
          until:      until ?? undefined,
          cursor:     curs ?? undefined,
        })
        setRows(prev => curs ? prev.concat(page.rows) : page.rows)
        setCursor(page.nextCursor ?? null)
        setHasMore(!!page.nextCursor)
      } catch (e) {
        setError(String((e as Error).message ?? e))
        setHasMore(false)
      } finally {
        setLoading(false)
      }
    },
    [bucket, groupBy, macs, profileIds, until],
  )

  useEffect(() => {
    setRows([])
    setCursor(null)
    setHasMore(true)
    void load(null)
  }, [filterKey])

  useInfiniteScroll({
    sentinelRef,
    hasMore,
    loading,
    onLoadMore: () => { if (cursor) void load(cursor) },
  })

  if (error) return <ErrorBanner message={error} />

  // #769: drilled into "App" but the household has no apps yet — the rows
  // would all collapse to a single `__other__` bucket. Point the operator
  // at the apps screen instead.
  const showAppEmpty = groupBy.includes('app') && appCount === 0
  if (showAppEmpty) {
    return (
      <div
        data-testid="ce-app-empty"
        className="rounded border border-brand-accent-dark bg-brand-accent/30 p-4 text-sm text-brand-accent"
      >
        No apps defined yet. <Link to="/apps" className="underline">Create one</Link> to group
        connection events by app.
      </div>
    )
  }

  return (
    <>
    <div className="overflow-x-auto" data-testid="ce-agg-table">
      <table className="min-w-full text-sm">
        <thead className="text-xs uppercase">
          <tr className="text-brand-text-muted">
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
            <th className="text-left px-2 py-1">
              <GroupableHeader label="App" groupKey="app" groupBy={groupBy}
                onToggle={onToggleGroup} testIdPrefix="ce-group" />
            </th>
            <th className="text-right px-2 py-1">OK</th>
            <th className="text-right px-2 py-1">Blocked</th>
            <th className="text-left px-2 py-1 hidden sm:table-cell">Last seen</th>
          </tr>
        </thead>
        <tbody className="text-brand-text">
          {rows.length === 0 && !loading && (
            <tr>
              <td colSpan={8} className="text-center text-brand-text-muted py-4">
                No events in window.
              </td>
            </tr>
          )}
          {rows.map((r, i) => {
            const prevWindow = i > 0 ? rows[i - 1].windowStart : null
            const showWindow = r.windowStart !== prevWindow
            return (
            <tr key={i} className={`${showWindow ? 'border-t-2 border-brand-border-strong' : 'border-t border-brand-border/40'}`}>
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
              <td className="px-2 py-1">
                <AppCell
                  active={groupBy.includes('app')}
                  appName={r.appName}
                  appIcon={r.appIcon}
                  sole={r.soleApp}
                  count={r.distinctApps}
                />
              </td>
              <td className="px-2 py-1 text-brand-accent text-right">{r.countSucceeded}</td>
              <td className="px-2 py-1 text-red-400 text-right">{r.countBlocked}</td>
              <td className="px-2 py-1 font-mono text-xs whitespace-nowrap hidden sm:table-cell">{localTime(r.lastSeen)}</td>
            </tr>
            )
          })}
        </tbody>
      </table>
    </div>
    <div ref={sentinelRef} data-testid="scroll-sentinel" className="h-1" />
    {loading && <Spinner />}
    {!loading && hasMore && cursor && (
      <div className="flex justify-center py-3">
        <button
          type="button"
          data-testid="load-more"
          onClick={() => void load(cursor)}
          className="text-xs text-brand-text hover:text-brand-accent px-3 py-1.5 border border-brand-border-strong hover:border-brand-accent-dark rounded"
        >
          Load more
        </button>
      </div>
    )}
    {!hasMore && !loading && rows.length > 0 && (
      <div className="text-xs text-brand-text-muted text-center py-3" data-testid="end-of-stream">
        — end of history —
      </div>
    )}
    </>
  )
}
