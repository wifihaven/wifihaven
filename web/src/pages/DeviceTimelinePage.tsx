import { useEffect, useMemo, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import {
  Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'
import { api } from '@/api/client'
import type {
  DeviceTimeStatusWeek, UsageBucket, UsageSeriesResponse,
} from '@/types/api'
import { PageLoader } from './DashboardPage'
import { useEscapeClose } from '@/hooks/useEscapeClose'
import {
  HOST_COLORS, OTHER_KEY, UsageHourlyBarChart, type ChartSeries,
} from '@/components/usage/UsageHourlyBarChart'
import { groupBucketsByLocalDay, formatMins, localBucketOffsetMin } from '@/lib/timeFormat'

// #721 — per-device daily (hourly) timeline.
// #723 — Today/Week toggle: Week renders the trailing-7-day per-device
// bar chart from /api/time/status/{mac}/week. The date picker acts as
// the `to` anchor in Week mode.

const TOP_N = 5
// #964 — when the operator clicks the chart's "Other" bucket we refetch with
// a much larger topN to surface the full long-tail. Server caps topN at 500.
const DRILL_IN_TOP_N = 500
const DEFAULT_TZ =
  Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'

type Window = 'today' | 'week'

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

function buildChartData(
  buckets: UsageBucket[],
  hostKeys: string[],
) {
  return buckets.map(b => {
    const row: Record<string, number | string> = {
      hour: String(b.hour).padStart(2, '0'),
      total: b.totalMins,
    }
    for (const k of hostKeys) row[k] = 0
    for (const ph of b.perHost) row[ph.host.value] = ph.mins
    row[OTHER_KEY] = b.otherMins
    return row as { hour: string; total: number; [k: string]: number | string }
  })
}

export function DeviceTimelinePage() {
  const { mac = '' } = useParams<{ mac: string }>()
  const [params, setParams] = useSearchParams()

  const initialDate = params.get('date') ?? todayISO()
  const initialWindow = (params.get('window') === 'week' ? 'week' : 'today') as Window
  const [date, setDate] = useState<string>(initialDate)
  const [window, setWindow] = useState<Window>(initialWindow)
  const [dayData, setDayData] = useState<UsageSeriesResponse | null>(null)
  const [weekData, setWeekData] = useState<DeviceTimeStatusWeek | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [otherOpen, setOtherOpen] = useState(false)
  const [otherLoading, setOtherLoading] = useState(false)
  const [otherError, setOtherError] = useState<string | null>(null)
  const [otherData, setOtherData] = useState<UsageSeriesResponse | null>(null)

  useEffect(() => {
    setLoading(true)
    setError(null)
    const p = window === 'today'
      ? api.usage.series({ mac, date, tz: DEFAULT_TZ, topN: TOP_N }).then(d => { setDayData(d); setWeekData(null) })
      : api.time.statusDeviceWeek(mac, date, localBucketOffsetMin()).then(d => { setWeekData(d); setDayData(null) })
    p.catch(e => setError(e.message ?? 'Failed to load')).finally(() => setLoading(false))
  }, [mac, date, window])

  function pushParams(next: { date?: string; window?: Window }) {
    const sp = new URLSearchParams(params)
    if (next.date)   sp.set('date', next.date)
    if (next.window) sp.set('window', next.window)
    setParams(sp, { replace: true })
  }
  function setDateAndPush(next: string)     { setDate(next);     pushParams({ date: next }) }
  function setWindowAndPush(next: Window)   { setWindow(next);   pushParams({ window: next }) }

  function openOtherDrillIn() {
    setOtherOpen(true)
    setOtherError(null)
    // Refetch with a high topN so every host (including the long-tail that
    // got folded into "Other" at TOP_N=5) comes back unaggregated. The chart
    // payload itself is left unchanged — we want the modal to be additive,
    // not to swap the chart underneath the operator.
    setOtherLoading(true)
    api.usage
      .series({ mac, date, tz: DEFAULT_TZ, topN: DRILL_IN_TOP_N })
      .then(setOtherData)
      .catch(e => setOtherError(e.message ?? 'Failed to load'))
      .finally(() => setOtherLoading(false))
  }

  const dayChart = useMemo(() => {
    if (!dayData) return { rows: [], series: [] as ChartSeries[] }
    const hostKeys = dayData.topHosts.filter(h => h.dayMins > 0).map(h => h.host.value)
    const series: ChartSeries[] = hostKeys.map((k, i) => ({
      key: k,
      name: k,
      color: HOST_COLORS[i % HOST_COLORS.length],
    }))
    return { rows: buildChartData(dayData.buckets, hostKeys), series }
  }, [dayData])

  const weekChart = useMemo(() => {
    if (!weekData) return []
    return groupBucketsByLocalDay(weekData.perBucket, weekData.to)
  }, [weekData])

  if (loading && !dayData && !weekData) return <PageLoader />

  const dayTotal = dayData?.buckets.reduce((a, b) => a + b.totalMins, 0) ?? 0
  const dayEmpty = window === 'today' && dayTotal === 0
  const weekEmpty = window === 'week' && (weekData?.totalMins ?? 0) === 0
  const titleName = dayData?.deviceName ?? weekData?.deviceName ?? mac
  // `mins` is the wall-clock-share number (#715 proportional). For the weekly
  // breakdown we additionally surface bucket-presence in parens so the operator
  // can spot heartbeat-style hosts that show up everywhere but with tiny bytes.
  const hosts = window === 'today'
    ? (dayData?.topHosts.filter(h => h.dayMins > 0) ?? []).map(h => ({ host: h.host, mins: h.dayMins, presenceMins: null as number | null }))
    : (weekData?.hostUsage ?? []).map(h => ({ host: h.host, mins: h.proportionalMins, presenceMins: h.usedMins }))

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between gap-2 flex-wrap">
        <div className="min-w-0">
          <Link to="/devices" className="text-xs text-gray-500 hover:text-emerald-400">
            ← Devices
          </Link>
          <h1 className="text-xl font-bold text-white truncate" data-testid="device-timeline-name">
            {titleName}
          </h1>
          <p className="text-xs text-gray-500 font-mono">{mac}</p>
        </div>
        <div className="flex items-center gap-2">
          <div role="tablist" className="inline-flex rounded-xl bg-gray-900 border border-gray-800 p-1">
            {(['today', 'week'] as const).map(w => (
              <button
                key={w}
                role="tab"
                aria-selected={window === w}
                data-testid={`device-timeline-window-${w}`}
                onClick={() => setWindowAndPush(w)}
                className={`px-3 py-1 text-sm font-medium rounded-lg transition-colors ${
                  window === w
                    ? 'bg-emerald-500 text-black'
                    : 'text-gray-400 hover:text-gray-200'
                }`}
              >
                {w === 'today' ? 'Today' : 'Week'}
              </button>
            ))}
          </div>
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
            data-testid="device-timeline-date"
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
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-sm font-semibold text-gray-400 uppercase tracking-wider">
            {window === 'today' ? 'Hourly minutes' : 'Daily minutes (trailing 7 days)'}
          </h2>
          <span className="text-xs text-gray-500 font-mono">
            {window === 'today'
              ? `${formatMins(dayTotal)} total · ${dayData?.tz ?? ''}`
              : `${formatMins(weekData?.totalMins ?? 0)} total · ${weekData?.from ?? ''} → ${weekData?.to ?? ''}`}
          </span>
        </div>

        {window === 'today' ? (
          dayEmpty ? (
            <div
              data-testid="device-timeline-empty"
              className="h-64 flex items-center justify-center text-gray-600 text-sm border border-dashed border-gray-800 rounded-xl"
            >
              No usage recorded on {date}.
            </div>
          ) : (
            <>
              <UsageHourlyBarChart
                rows={dayChart.rows}
                series={dayChart.series}
                showLegend
                onOtherClick={openOtherDrillIn}
                testId="device-timeline-chart"
              />
              {dayChart.rows.some(r => Number(r[OTHER_KEY] ?? 0) > 0) && (
                <div className="mt-2 flex justify-end">
                  <button
                    type="button"
                    onClick={openOtherDrillIn}
                    data-testid="device-timeline-other-button"
                    className="text-[11px] text-gray-500 hover:text-emerald-400 underline decoration-dotted underline-offset-2"
                    title="See the hosts inside the Other bucket for this day"
                  >
                    Inside “Other” ↗
                  </button>
                </div>
              )}
            </>
          )
        ) : (
          weekEmpty ? (
            <div
              data-testid="device-timeline-week-empty"
              className="h-64 flex items-center justify-center text-gray-600 text-sm border border-dashed border-gray-800 rounded-xl"
            >
              No usage recorded in this 7-day window.
            </div>
          ) : (
            <div className="h-72 -ml-2" data-testid="device-timeline-week-chart">
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={weekChart} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
                  <CartesianGrid stroke="#1f2937" strokeDasharray="3 3" vertical={false} />
                  <XAxis
                    dataKey="label"
                    tick={{ fill: '#6b7280', fontSize: 11 }}
                    axisLine={{ stroke: '#374151' }}
                    tickLine={false}
                  />
                  <YAxis
                    tick={{ fill: '#6b7280', fontSize: 11 }}
                    axisLine={{ stroke: '#374151' }}
                    tickLine={false}
                    width={44}
                    tickFormatter={(v: number) => formatMins(v)}
                  />
                  <Tooltip
                    cursor={{ fill: '#1f293780' }}
                    contentStyle={{
                      background: '#0a0f1c',
                      border: '1px solid #374151',
                      borderRadius: '8px',
                      fontSize: 12,
                    }}
                    labelFormatter={(_, payload) => String(payload?.[0]?.payload?.date ?? '')}
                    formatter={(v) => [formatMins(Number(v)), 'Used']}
                  />
                  <Bar dataKey="usedMins" fill={HOST_COLORS[0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
          )
        )}
      </div>

      {hosts.length > 0 && (
        <div className="bg-gray-900 rounded-2xl border border-gray-800 p-5">
          <h2 className="text-sm font-semibold text-gray-400 uppercase tracking-wider mb-3">
            Top hosts
          </h2>
          <ul className="space-y-1.5">
            {hosts.map((h, i) => {
              const isIp = h.host.type !== 'fqdn'
              return (
                <li
                  key={`${h.host.type}:${h.host.value}`}
                  data-testid={`device-timeline-host-${h.host.value}`}
                  className="flex items-center justify-between text-xs bg-gray-800/50 rounded-lg px-3 py-2"
                >
                  <span className="flex items-center gap-2 min-w-0">
                    <span
                      className="inline-block w-2.5 h-2.5 rounded-sm shrink-0"
                      style={{ background: HOST_COLORS[i % HOST_COLORS.length] }}
                    />
                    {/* Per #718: bare-IP rows render but de-emphasized so the
                        FQDN attribution gap is visible at a glance. */}
                    <span
                      className={`font-mono truncate ${isIp ? 'italic text-gray-500' : 'text-gray-300'}`}
                      title={h.host.value}
                    >
                      {h.host.value}
                      {isIp && (
                        <span className="ml-1 text-[10px] uppercase tracking-wide text-gray-600">
                          {h.host.type}
                        </span>
                      )}
                    </span>
                  </span>
                  <span
                    className="text-gray-500 font-mono shrink-0 ml-2"
                    title={h.presenceMins !== null
                      ? `presence ${formatMins(h.presenceMins)} (every bucket this host appeared in)`
                      : undefined}
                  >
                    {formatMins(h.mins)}
                    {h.presenceMins !== null && (
                      <span className="text-gray-600"> ({formatMins(h.presenceMins)})</span>
                    )}
                  </span>
                </li>
              )
            })}
          </ul>
          <p className="text-[11px] text-gray-600 mt-3">
            Per-host minutes are byte-share-weighted wall-clock attention within each 5-minute
            window (#715){window === 'week' ? '; presence in parens is how many buckets the host appeared in at all' : ', so the stack sums to the device\'s wall-clock minutes for that hour'}.
          </p>
        </div>
      )}

      {otherOpen && (
        <OtherDrillInModal
          date={date}
          loading={otherLoading}
          error={otherError}
          data={otherData}
          topN={TOP_N}
          onClose={() => setOtherOpen(false)}
        />
      )}
    </div>
  )
}

// #964 — "Other" drill-in modal. Lists the long-tail hosts that the top-N
// chart folded into the Other bucket for the selected day. Reuses the same
// /api/usage/series endpoint with a much larger topN so the SPA can subtract
// the displayed top-N off the front and show the rest, sorted by minutes desc.
interface OtherDrillInModalProps {
  date: string
  loading: boolean
  error: string | null
  data: UsageSeriesResponse | null
  topN: number
  onClose: () => void
}

function OtherDrillInModal({ date, loading, error, data, topN, onClose }: OtherDrillInModalProps) {
  useEscapeClose(onClose)
  const tail = (data?.topHosts ?? []).filter(h => h.dayMins > 0).slice(topN)
  const otherTotal = tail.reduce((a, h) => a + h.dayMins, 0)
  const grandTotal = (data?.topHosts ?? []).reduce((a, h) => a + h.dayMins, 0)
  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="other-drillin-title"
      data-testid="device-timeline-other-modal"
      className="fixed inset-0 bg-black/70 flex items-end sm:items-center justify-center z-50 p-4"
      onClick={onClose}
    >
      <div
        className="bg-gray-900 rounded-2xl border border-gray-700 w-full max-w-lg p-6 space-y-4 max-h-[80vh] flex flex-col"
        onClick={e => e.stopPropagation()}
      >
        <div className="flex items-start justify-between">
          <div>
            <h3 id="other-drillin-title" className="text-lg font-bold text-white">
              Inside the “Other” bucket
            </h3>
            <p className="text-xs text-gray-500 mt-0.5">
              Hosts below the top {topN} on {date}.
            </p>
          </div>
          <button
            onClick={onClose}
            className="text-gray-500 hover:text-gray-300 text-xl leading-none"
            aria-label="Close"
          >×</button>
        </div>

        {loading && (
          <div className="text-sm text-gray-500" data-testid="device-timeline-other-loading">
            Loading the long-tail…
          </div>
        )}
        {error && (
          <div className="bg-red-500/10 border border-red-500/30 text-red-300 text-sm rounded-xl px-4 py-3">
            {error}
          </div>
        )}
        {!loading && !error && tail.length === 0 && (
          <div
            data-testid="device-timeline-other-empty"
            className="text-sm text-gray-500"
          >
            Nothing else recorded for this day — the top {topN} already cover everything.
          </div>
        )}
        {!loading && !error && tail.length > 0 && (
          <ul className="space-y-1 overflow-y-auto pr-1 -mr-1">
            {tail.map(h => {
              const isIp = h.host.type !== 'fqdn'
              const shareOther = otherTotal > 0 ? (h.dayMins / otherTotal) * 100 : 0
              const shareTotal = grandTotal > 0 ? (h.dayMins / grandTotal) * 100 : 0
              return (
                <li
                  key={`${h.host.type}:${h.host.value}`}
                  data-testid={`device-timeline-other-host-${h.host.value}`}
                  className="flex items-center justify-between text-xs bg-gray-800/50 rounded-lg px-3 py-2 gap-2"
                >
                  <span
                    className={`font-mono truncate min-w-0 ${isIp ? 'italic text-gray-500' : 'text-gray-300'}`}
                    title={h.host.value}
                  >
                    {h.host.value}
                    {isIp && (
                      <span className="ml-1 text-[10px] uppercase tracking-wide text-gray-600">
                        {h.host.type}
                      </span>
                    )}
                  </span>
                  <span className="text-gray-500 font-mono shrink-0 tabular-nums">
                    {formatMins(h.dayMins)}
                    <span className="text-gray-600 ml-2">
                      {shareOther.toFixed(0)}% of Other · {shareTotal.toFixed(0)}% of day
                    </span>
                  </span>
                </li>
              )
            })}
          </ul>
        )}

        <div className="pt-2">
          <button
            onClick={onClose}
            className="w-full py-2.5 rounded-xl bg-gray-800 text-gray-300 text-sm font-medium hover:bg-gray-700 transition-colors"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  )
}
