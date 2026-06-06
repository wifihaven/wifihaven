import { useEffect, useMemo, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { api } from '@/api/client'
import type {
  UsageDeviceBucket, UsageEntityBucket, UsageSeriesResponse,
} from '@/types/api'
import { PageLoader } from './DashboardPage'
import {
  HOST_COLORS, OTHER_KEY, UsageHourlyBarChart, type ChartSeries,
} from '@/components/usage/UsageHourlyBarChart'

// #722 — per-profile daily timeline. Hourly stacked-bar chart of minutes-of-use
// across all of a profile's devices, with a stack-by toggle that switches which
// dimension drives the stacks. Colors are derived from a stable index of the
// top-N entries so the same entity keeps its color across re-renders.
//
// #1079 — group-by options collapsed to {total, device, app}. The standalone
// 'host' axis was retired in favor of a unified by-app axis: apps roll up
// their member hosts, non-app hosts surface individually, and 'Other' is
// strictly the long tail past top-N (not a catch-all for unmapped hosts).

const TOP_N = 5
const DEFAULT_TZ =
  Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'

type StackBy = 'total' | 'device' | 'app'

const STACK_BY_OPTIONS: ReadonlyArray<{
  key: StackBy
  label: string
}> = [
  { key: 'total',  label: 'Total' },
  { key: 'device', label: 'Device' },
  { key: 'app',    label: 'App' },
]

function parseStackBy(s: string | null): StackBy {
  switch (s) {
    case 'device': return 'device'
    case 'app':    return 'app'
    default:       return 'total'
  }
}

function entityKey(e: { kind: string; id: string }): string {
  return `${e.kind}:${e.id}`
}

function todayISO(): string {
  const d = new Date()
  const yyyy = d.getFullYear()
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  return `${yyyy}-${mm}-${dd}`
}

function addDays(iso: string, n: number): string {
  const [y, m, d] = iso.split('-').map(Number)
  const dt = new Date(Date.UTC(y, m - 1, d))
  dt.setUTCDate(dt.getUTCDate() + n)
  const yy = dt.getUTCFullYear()
  const mm = String(dt.getUTCMonth() + 1).padStart(2, '0')
  const dd = String(dt.getUTCDate()).padStart(2, '0')
  return `${yy}-${mm}-${dd}`
}

function buildEntityRows(buckets: UsageEntityBucket[], keys: string[]) {
  return buckets.map(b => {
    const row: Record<string, number | string> = {
      hour: String(b.hour).padStart(2, '0'),
      total: b.totalMins,
    }
    for (const k of keys) row[k] = 0
    for (const pe of b.perEntity) row[entityKey(pe.entity)] = pe.mins
    row[OTHER_KEY] = b.otherMins
    return row as { hour: string; total: number; [k: string]: number | string }
  })
}

function buildDeviceRows(buckets: UsageDeviceBucket[], keys: string[]) {
  return buckets.map(b => {
    const row: Record<string, number | string> = {
      hour: String(b.hour).padStart(2, '0'),
      total: b.totalMins,
    }
    for (const k of keys) row[k] = 0
    for (const pd of b.perDevice) row[pd.deviceMac] = pd.mins
    row[OTHER_KEY] = b.otherMins
    return row as { hour: string; total: number; [k: string]: number | string }
  })
}

export function ProfileTimelinePage() {
  const { profileId = '' } = useParams<{ profileId: string }>()
  const [params, setParams] = useSearchParams()

  const initialDate = params.get('date') ?? todayISO()
  const initialStack = parseStackBy(params.get('stackBy'))
  const [date, setDate]       = useState<string>(initialDate)
  const [stackBy, setStackBy] = useState<StackBy>(initialStack)
  const [data, setData]       = useState<UsageSeriesResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError]     = useState<string | null>(null)

  useEffect(() => {
    const pid = Number(profileId)
    if (!Number.isFinite(pid)) {
      setError('invalid profile id')
      setLoading(false)
      return
    }
    setLoading(true)
    setError(null)
    api.usage.series({
      profileId: pid, date, tz: DEFAULT_TZ, topN: TOP_N,
      groupBy: stackBy === 'app' ? 'app' : undefined,
    })
      .then(setData)
      .catch(e => setError(e.message ?? 'Failed to load'))
      .finally(() => setLoading(false))
  }, [profileId, date, stackBy])

  function pushParam(key: string, val: string) {
    const sp = new URLSearchParams(params)
    sp.set(key, val)
    setParams(sp, { replace: true })
  }
  function setDateAndPush(next: string) { setDate(next); pushParam('date', next) }
  function setStackAndPush(next: StackBy) { setStackBy(next); pushParam('stackBy', next) }

  const chart = useMemo(() => {
    if (!data) return { rows: [], series: [] as ChartSeries[] }
    if (stackBy === 'app') {
      // #1079 unified axis: apps + non-app hosts are first-class siblings.
      const top  = (data.topEntries ?? []).filter(e => e.dayMins > 0)
      const keys = top.map(e => entityKey(e.entity))
      const series: ChartSeries[] = top.map((e, i) => ({
        key: entityKey(e.entity),
        name: e.entity.name,
        color: HOST_COLORS[i % HOST_COLORS.length],
      }))
      return { rows: buildEntityRows(data.bucketsByEntry ?? [], keys), series }
    } else if (stackBy === 'device') {
      const top = (data.topDevices ?? []).filter(d => d.dayMins > 0)
      const keys = top.map(d => d.deviceMac)
      const series: ChartSeries[] = top.map((d, i) => ({
        key: d.deviceMac, name: d.deviceName, color: HOST_COLORS[i % HOST_COLORS.length],
      }))
      const rows = buildDeviceRows(data.bucketsByDevice ?? [], keys)
      return { rows, series }
    } else {
      // total — single stack per hour = profile total, no drill-down. Reuse
      // the host-mode buckets (totalMins is identical across both bucket
      // arrays since they're aggregations of the same source rows).
      const rows = data.buckets.map(b => ({
        hour: String(b.hour).padStart(2, '0'),
        total: b.totalMins,
        __total: b.totalMins,
        [OTHER_KEY]: 0,
      })) as { hour: string; total: number; [k: string]: number | string }[]
      const series: ChartSeries[] = [
        { key: '__total', name: 'Total', color: HOST_COLORS[0] },
      ]
      return { rows, series }
    }
  }, [data, stackBy])

  if (loading && !data) return <PageLoader />

  const buckets =
    stackBy === 'device' ? data?.bucketsByDevice
    : stackBy === 'app'    ? data?.bucketsByEntry
    : data?.buckets
  // #1492 — the headline is the session-stitch presence total (the same number
  // the profile card shows as time-used), not the sum of the per-hour bars.
  // Summing the floored hourly bars drops sub-minute fractions and reads a few
  // minutes low; `presenceTotalMins` is floored once at the day level so the
  // graph and the card agree. Fall back to the bar sum for older API responses.
  const barSum   = buckets?.reduce((a, b) => a + b.totalMins, 0) ?? 0
  const dayTotal = data?.presenceTotalMins ?? barSum
  const isEmpty  = dayTotal === 0

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between gap-2 flex-wrap">
        <div className="min-w-0">
          <Link to="/profiles" className="text-xs text-brand-text-muted hover:text-brand-accent">
            ← Profiles
          </Link>
          <h1 className="text-xl font-bold text-brand-ink truncate" data-testid="profile-timeline-name">
            {data?.profileName ?? `Profile ${profileId}`}
          </h1>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={() => setDateAndPush(addDays(date, -1))}
            className="bg-brand-alt hover:bg-brand-alt text-brand-text text-sm px-3 py-2 rounded-lg"
            aria-label="Previous day"
          >‹</button>
          <input
            type="date"
            value={date}
            onChange={e => setDateAndPush(e.target.value)}
            className="bg-white border border-brand-border-strong text-brand-ink text-sm rounded-lg px-3 py-2 focus:outline-none focus:border-brand-accent"
            data-testid="profile-timeline-date"
          />
          <button
            onClick={() => setDateAndPush(addDays(date, 1))}
            className="bg-brand-alt hover:bg-brand-alt text-brand-text text-sm px-3 py-2 rounded-lg"
            aria-label="Next day"
          >›</button>
        </div>
      </div>

      {error && (
        <div className="bg-red-500/10 border border-red-500/30 text-red-700 text-sm rounded-xl px-4 py-3">
          {error}
        </div>
      )}

      <div className="bg-white rounded-2xl border border-brand-border p-5">
        <div className="flex items-center justify-between mb-4 gap-2 flex-wrap">
          <h2 className="text-sm font-semibold text-brand-text uppercase tracking-wider">
            Hourly minutes
          </h2>
          <div className="flex items-center gap-3">
            <div className="inline-flex rounded-lg bg-brand-alt p-0.5 text-xs" role="tablist" aria-label="Stack by">
              {STACK_BY_OPTIONS.map(opt => (
                <button
                  key={opt.key}
                  role="tab"
                  aria-selected={stackBy === opt.key}
                  data-testid={`profile-timeline-stack-${opt.key}`}
                  onClick={() => setStackAndPush(opt.key)}
                  className={`px-3 py-1.5 rounded-md font-medium transition-colors ${
                    stackBy === opt.key
                      ? 'bg-brand-accent text-white'
                      : 'text-brand-text hover:text-brand-ink'
                  }`}
                >
                  {opt.label}
                </button>
              ))}
            </div>
            <span className="text-xs text-brand-text-muted font-mono">
              {dayTotal}m total · {data?.tz}
            </span>
          </div>
        </div>

        {isEmpty ? (
          <div
            data-testid="profile-timeline-empty"
            className="h-64 flex items-center justify-center text-brand-text-muted text-sm border border-dashed border-brand-border rounded-xl"
          >
            No usage recorded on {date}.
          </div>
        ) : (
          <UsageHourlyBarChart
            rows={chart.rows}
            series={chart.series}
            showLegend={stackBy !== 'total'}
            // The chart receives device-mac keys when stack-by=device; map them
            // back to friendly names so the legend reads "Kid's iPad" not the mac.
            legendFormatter={stackBy === 'device'
              ? (k) => chart.series.find(s => s.key === k)?.name ?? k
              : undefined}
            testId="profile-timeline-chart"
          />
        )}

        {/* #1492 — the graph is presence-based: hourly bars are the session-stitch
            presence (the same model that drives the time-used total), and the
            headline `presenceTotalMins` equals the number on the profile card.
            Per-app/host stacks are each entity's own session presence, so within a
            device overlapping apps can make the stacks exceed the bar — the total
            is the device union, not the sum of per-app spans (design §4.3). */}
        <p className="text-[11px] text-brand-text-muted mt-3">
          Presence-based: bars are session minutes per hour and the total matches the
          time-used shown on the profile card. Per-app contributions are each app's own
          session time — overlapping apps on one device can sum past the hour's total.
        </p>
      </div>

      {data && stackBy === 'app' && (data.topEntries ?? []).length > 0 && (
        <div className="bg-white rounded-2xl border border-brand-border p-5">
          <h2 className="text-sm font-semibold text-brand-text uppercase tracking-wider mb-3">
            Top apps &amp; hosts
          </h2>
          <ul className="space-y-1.5">
            {data.topEntries!.filter(e => e.dayMins > 0).map((e, i) => {
              const id = `${e.entity.kind}-${e.entity.id}`
              return (
                <li
                  key={entityKey(e.entity)}
                  data-testid={`profile-timeline-entry-${id}`}
                  className="flex items-center justify-between text-xs text-brand-text bg-brand-alt/50 rounded-lg px-3 py-2"
                  title={e.entity.kind === 'app'
                    ? `App: rolls up its member hosts`
                    : `Host: not attached to any app`}
                >
                  <span className="flex items-center gap-2 min-w-0">
                    <span
                      className="inline-block w-2.5 h-2.5 rounded-sm shrink-0"
                      style={{ background: HOST_COLORS[i % HOST_COLORS.length] }}
                    />
                    <span className="truncate">{e.entity.name}</span>
                    <span className="text-[10px] text-brand-text-muted uppercase shrink-0">
                      {e.entity.kind}
                    </span>
                  </span>
                  <span className="text-brand-text-muted font-mono shrink-0 ml-2">{e.dayMins}m</span>
                </li>
              )
            })}
          </ul>
          <p className="text-[10px] text-brand-text-muted mt-2">
            'Other' covers hosts past the top {TOP_N}, not unmapped hosts —
            those still appear individually above.
          </p>
        </div>
      )}

      {data && (stackBy === 'device' || stackBy === 'total') && (data.topDevices ?? []).length > 0 && (
        <div className="bg-white rounded-2xl border border-brand-border p-5">
          <h2 className="text-sm font-semibold text-brand-text uppercase tracking-wider mb-3">
            Devices
          </h2>
          <ul className="space-y-1.5">
            {data.topDevices!.filter(d => d.dayMins > 0).map((d, i) => (
              <li
                key={d.deviceMac}
                data-testid={`profile-timeline-device-${d.deviceMac}`}
                className="flex items-center justify-between text-xs bg-brand-alt/50 rounded-lg px-3 py-2"
              >
                <span className="flex items-center gap-2 min-w-0">
                  <span
                    className="inline-block w-2.5 h-2.5 rounded-sm shrink-0"
                    style={{ background: HOST_COLORS[i % HOST_COLORS.length] }}
                  />
                  <Link
                    to={`/devices/${encodeURIComponent(d.deviceMac)}/timeline?date=${date}`}
                    className="text-brand-text hover:text-brand-accent truncate"
                  >
                    {d.deviceName}
                  </Link>
                </span>
                <span className="text-brand-text-muted font-mono shrink-0 ml-2">{d.dayMins}m</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}
