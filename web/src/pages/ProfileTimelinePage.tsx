import { useEffect, useMemo, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { api } from '@/api/client'
import { HostCell } from '@/components/HostCell'
import type {
  UsageBucket, UsageDeviceBucket, UsageSeriesResponse,
} from '@/types/api'
import { PageLoader } from './DashboardPage'
import {
  HOST_COLORS, OTHER_KEY, UsageHourlyBarChart, type ChartSeries,
} from '@/components/usage/UsageHourlyBarChart'

// #722 — per-profile daily timeline. Hourly stacked-bar chart of minutes-of-use
// across all of a profile's devices, with a stack-by toggle that switches which
// dimension drives the stacks. Colors are derived from a stable index of the
// top-N entries so the same host (or device) keeps its color across re-renders.
//
// #964 — group-by options expanded to {total, host, device, app}. Default is
// `total` (no drill-down, single bar per hour = profile total) to match the
// strictly-additive aggregation model from #917. `app` is gated until the apps
// chain (#769) extends /api/usage/series; rendered as disabled with a tooltip.

const TOP_N = 5
const DEFAULT_TZ =
  Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'

type StackBy = 'total' | 'host' | 'device' | 'app'

const STACK_BY_OPTIONS: ReadonlyArray<{
  key: StackBy
  label: string
  disabled?: boolean
  title?: string
}> = [
  { key: 'total',  label: 'Total' },
  { key: 'host',   label: 'Host' },
  { key: 'device', label: 'Device' },
  {
    key: 'app',
    label: 'App',
    disabled: true,
    title: 'Grouping by app requires the apps chain (#769) — coming soon.',
  },
]

function parseStackBy(s: string | null): StackBy {
  switch (s) {
    case 'host':   return 'host'
    case 'device': return 'device'
    // 'app' isn't selectable yet — fall through to total.
    default:       return 'total'
  }
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

function buildHostRows(buckets: UsageBucket[], keys: string[]) {
  return buckets.map(b => {
    const row: Record<string, number | string> = {
      hour: String(b.hour).padStart(2, '0'),
      total: b.totalMins,
    }
    for (const k of keys) row[k] = 0
    for (const ph of b.perHost) row[ph.host.value] = ph.mins
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
    api.usage.series({ profileId: pid, date, tz: DEFAULT_TZ, topN: TOP_N })
      .then(setData)
      .catch(e => setError(e.message ?? 'Failed to load'))
      .finally(() => setLoading(false))
  }, [profileId, date])

  function pushParam(key: string, val: string) {
    const sp = new URLSearchParams(params)
    sp.set(key, val)
    setParams(sp, { replace: true })
  }
  function setDateAndPush(next: string) { setDate(next); pushParam('date', next) }
  function setStackAndPush(next: StackBy) { setStackBy(next); pushParam('stackBy', next) }

  const chart = useMemo(() => {
    if (!data) return { rows: [], series: [] as ChartSeries[] }
    if (stackBy === 'host') {
      // Top-host indices drive colors; same host across renders keeps the
      // same slot because the server returns topHosts sorted deterministically.
      const keys = (data.topHosts ?? []).filter(h => h.dayMins > 0).map(h => h.host.value)
      const series: ChartSeries[] = keys.map((k, i) => ({
        key: k, name: k, color: HOST_COLORS[i % HOST_COLORS.length],
      }))
      return { rows: buildHostRows(data.buckets, keys), series }
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

  const buckets = stackBy === 'device' ? data?.bucketsByDevice : data?.buckets
  const dayTotal = buckets?.reduce((a, b) => a + b.totalMins, 0) ?? 0
  const isEmpty  = dayTotal === 0

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between gap-2 flex-wrap">
        <div className="min-w-0">
          <Link to="/profiles" className="text-xs text-gray-500 hover:text-emerald-400">
            ← Profiles
          </Link>
          <h1 className="text-xl font-bold text-white truncate" data-testid="profile-timeline-name">
            {data?.profileName ?? `Profile ${profileId}`}
          </h1>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={() => setDateAndPush(addDays(date, -1))}
            className="bg-gray-800 hover:bg-gray-700 text-gray-300 text-sm px-3 py-2 rounded-lg"
            aria-label="Previous day"
          >‹</button>
          <input
            type="date"
            value={date}
            onChange={e => setDateAndPush(e.target.value)}
            className="bg-gray-900 border border-gray-700 text-gray-200 text-sm rounded-lg px-3 py-2 focus:outline-none focus:border-emerald-500"
            data-testid="profile-timeline-date"
          />
          <button
            onClick={() => setDateAndPush(addDays(date, 1))}
            className="bg-gray-800 hover:bg-gray-700 text-gray-300 text-sm px-3 py-2 rounded-lg"
            aria-label="Next day"
          >›</button>
        </div>
      </div>

      {error && (
        <div className="bg-red-500/10 border border-red-500/30 text-red-300 text-sm rounded-xl px-4 py-3">
          {error}
        </div>
      )}

      <div className="bg-gray-900 rounded-2xl border border-gray-800 p-5">
        <div className="flex items-center justify-between mb-4 gap-2 flex-wrap">
          <h2 className="text-sm font-semibold text-gray-400 uppercase tracking-wider">
            Hourly minutes
          </h2>
          <div className="flex items-center gap-3">
            <div className="inline-flex rounded-lg bg-gray-800 p-0.5 text-xs" role="tablist" aria-label="Stack by">
              {STACK_BY_OPTIONS.map(opt => (
                <button
                  key={opt.key}
                  role="tab"
                  aria-selected={stackBy === opt.key}
                  aria-disabled={opt.disabled || undefined}
                  disabled={opt.disabled}
                  title={opt.title}
                  data-testid={`profile-timeline-stack-${opt.key}`}
                  onClick={() => { if (!opt.disabled) setStackAndPush(opt.key) }}
                  className={`px-3 py-1.5 rounded-md font-medium transition-colors ${
                    stackBy === opt.key
                      ? 'bg-emerald-500 text-black'
                      : opt.disabled
                        ? 'text-gray-600 cursor-not-allowed'
                        : 'text-gray-400 hover:text-gray-200'
                  }`}
                >
                  {opt.label}
                </button>
              ))}
            </div>
            <span className="text-xs text-gray-500 font-mono">
              {dayTotal}m total · {data?.tz}
            </span>
          </div>
        </div>

        {isEmpty ? (
          <div
            data-testid="profile-timeline-empty"
            className="h-64 flex items-center justify-center text-gray-600 text-sm border border-dashed border-gray-800 rounded-xl"
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

        {/* Profile total uses sum-of-per-device-minutes semantics. Two siblings
            both active in the same 5-min window count as 10 minutes here, but
            the per-host stack still even-shares within each device's bucket
            (#715). The numbers reconcile with the daily totals shown on the
            /profiles card, within that overlap caveat. */}
        <p className="text-[11px] text-gray-600 mt-3">
          Stacks total to wall-clock minutes per hour. Per-host minutes are proportional
          within each 5-minute window; overlapping device activity counts once per device
          (matches the daily total shown on the profile card).
        </p>
      </div>

      {data && stackBy === 'host' && (data.topHosts ?? []).length > 0 && (
        <div className="bg-gray-900 rounded-2xl border border-gray-800 p-5">
          <h2 className="text-sm font-semibold text-gray-400 uppercase tracking-wider mb-3">
            Top hosts
          </h2>
          <ul className="space-y-1.5">
            {data.topHosts.filter(h => h.dayMins > 0).map((h, i) => {
              return (
                <li
                  key={`${h.host.type}:${h.host.value}`}
                  data-testid={`profile-timeline-host-${h.host.value}`}
                  className="flex items-center justify-between text-xs text-gray-300 bg-gray-800/50 rounded-lg px-3 py-2"
                >
                  <span className="flex items-center gap-2 min-w-0">
                    <span
                      className="inline-block w-2.5 h-2.5 rounded-sm shrink-0"
                      style={{ background: HOST_COLORS[i % HOST_COLORS.length] }}
                    />
                    <HostCell host={h.host} className="truncate" />
                  </span>
                  <span className="text-gray-500 font-mono shrink-0 ml-2">{h.dayMins}m</span>
                </li>
              )
            })}
          </ul>
        </div>
      )}

      {data && (stackBy === 'device' || stackBy === 'total') && (data.topDevices ?? []).length > 0 && (
        <div className="bg-gray-900 rounded-2xl border border-gray-800 p-5">
          <h2 className="text-sm font-semibold text-gray-400 uppercase tracking-wider mb-3">
            Devices
          </h2>
          <ul className="space-y-1.5">
            {data.topDevices!.filter(d => d.dayMins > 0).map((d, i) => (
              <li
                key={d.deviceMac}
                data-testid={`profile-timeline-device-${d.deviceMac}`}
                className="flex items-center justify-between text-xs bg-gray-800/50 rounded-lg px-3 py-2"
              >
                <span className="flex items-center gap-2 min-w-0">
                  <span
                    className="inline-block w-2.5 h-2.5 rounded-sm shrink-0"
                    style={{ background: HOST_COLORS[i % HOST_COLORS.length] }}
                  />
                  <Link
                    to={`/devices/${encodeURIComponent(d.deviceMac)}/timeline?date=${date}`}
                    className="text-gray-300 hover:text-emerald-400 truncate"
                  >
                    {d.deviceName}
                  </Link>
                </span>
                <span className="text-gray-500 font-mono shrink-0 ml-2">{d.dayMins}m</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}
