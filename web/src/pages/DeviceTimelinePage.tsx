import { useEffect, useMemo, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import {
  Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'
import { api } from '@/api/client'
import type {
  DeviceTimeStatusWeek, SuppressedHostUsage, UsageEntityBucket, UsageSeriesResponse,
} from '@/types/api'
import { PageLoader } from './DashboardPage'
import { HostCell } from '@/components/HostCell'
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

function entityKey(e: { kind: string; id: string }): string {
  return `${e.kind}:${e.id}`
}

function buildChartData(
  buckets: UsageEntityBucket[],
  entityKeys: string[],
) {
  return buckets.map(b => {
    const row: Record<string, number | string> = {
      hour: String(b.hour).padStart(2, '0'),
      total: b.totalMins,
    }
    for (const k of entityKeys) row[k] = 0
    for (const pe of b.perEntity) row[entityKey(pe.entity)] = pe.mins
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
      ? api.usage.series({ mac, date, tz: DEFAULT_TZ, topN: TOP_N, groupBy: 'app' }).then(d => { setDayData(d); setWeekData(null) })
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
      .series({ mac, date, tz: DEFAULT_TZ, topN: DRILL_IN_TOP_N, groupBy: 'app' })
      .then(setOtherData)
      .catch(e => setOtherError(e.message ?? 'Failed to load'))
      .finally(() => setOtherLoading(false))
  }

  const dayChart = useMemo(() => {
    if (!dayData) return { rows: [], series: [] as ChartSeries[] }
    const top  = (dayData.topEntries ?? []).filter(e => e.dayMins > 0)
    const keys = top.map(e => entityKey(e.entity))
    const series: ChartSeries[] = top.map((e, i) => ({
      key: entityKey(e.entity),
      name: e.entity.name,
      color: HOST_COLORS[i % HOST_COLORS.length],
    }))
    return { rows: buildChartData(dayData.bucketsByEntry ?? [], keys), series }
  }, [dayData])

  const weekChart = useMemo(() => {
    if (!weekData) return []
    return groupBucketsByLocalDay(weekData.perBucket, weekData.to)
  }, [weekData])

  if (loading && !dayData && !weekData) return <PageLoader />

  const dayTotal = (dayData?.bucketsByEntry ?? dayData?.buckets ?? []).reduce((a, b) => a + b.totalMins, 0)
  const dayEmpty = window === 'today' && dayTotal === 0
  const weekEmpty = window === 'week' && (weekData?.totalMins ?? 0) === 0
  const titleName = dayData?.deviceName ?? weekData?.deviceName ?? mac
  // Today: unified by-app axis entries (#1079) — mixed apps + non-app hosts.
  // Week: per-host list from the trailing-7d status endpoint, surfaced with
  // bucket-presence in parens so heartbeat-style hosts are visible.
  type TodayEntry = { id: string; kind: 'app' | 'host'; name: string; mins: number }
  const todayEntries: TodayEntry[] = (dayData?.topEntries ?? [])
    .filter(e => e.dayMins > 0)
    .map(e => ({ id: e.entity.id, kind: e.entity.kind, name: e.entity.name, mins: e.dayMins }))
  const weekHosts = (weekData?.hostUsage ?? []).map(h => ({
    host: h.host, mins: h.proportionalMins, presenceMins: h.usedMins,
  }))

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between gap-2 flex-wrap">
        <div className="min-w-0">
          <Link to="/devices" className="text-xs text-brand-text-muted hover:text-brand-accent">
            ← Devices
          </Link>
          <h1 className="text-xl font-bold text-brand-ink truncate" data-testid="device-timeline-name">
            {titleName}
          </h1>
          <p className="text-xs text-brand-text-muted font-mono">{mac}</p>
        </div>
        <div className="flex items-center gap-2">
          <div role="tablist" className="inline-flex rounded-xl bg-white border border-brand-border p-1">
            {(['today', 'week'] as const).map(w => (
              <button
                key={w}
                role="tab"
                aria-selected={window === w}
                data-testid={`device-timeline-window-${w}`}
                onClick={() => setWindowAndPush(w)}
                className={`px-3 py-1 text-sm font-medium rounded-lg transition-colors ${
                  window === w
                    ? 'bg-brand-accent text-white'
                    : 'text-brand-text hover:text-brand-ink'
                }`}
              >
                {w === 'today' ? 'Today' : 'Week'}
              </button>
            ))}
          </div>
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
            data-testid="device-timeline-date"
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
        <div className="flex items-center justify-between mb-4">
          <h2 className="text-sm font-semibold text-brand-text uppercase tracking-wider">
            {window === 'today' ? 'Hourly minutes' : 'Daily minutes (trailing 7 days)'}
          </h2>
          <span className="text-xs text-brand-text-muted font-mono">
            {window === 'today'
              ? `${formatMins(dayTotal)} total · ${dayData?.tz ?? ''}`
              : `${formatMins(weekData?.totalMins ?? 0)} total · ${weekData?.from ?? ''} → ${weekData?.to ?? ''}`}
          </span>
        </div>

        {window === 'today' ? (
          dayEmpty ? (
            <div
              data-testid="device-timeline-empty"
              className="h-64 flex items-center justify-center text-brand-text-muted text-sm border border-dashed border-brand-border rounded-xl"
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
                    className="text-[11px] text-brand-text-muted hover:text-brand-accent underline decoration-dotted underline-offset-2"
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
              className="h-64 flex items-center justify-center text-brand-text-muted text-sm border border-dashed border-brand-border rounded-xl"
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

      {window === 'today' && todayEntries.length > 0 && (
        <div className="bg-white rounded-2xl border border-brand-border p-5">
          <h2 className="text-sm font-semibold text-brand-text uppercase tracking-wider mb-3">
            Top apps &amp; hosts
          </h2>
          <ul className="space-y-1.5">
            {todayEntries.map((e, i) => (
              <li
                key={`${e.kind}:${e.id}`}
                data-testid={`device-timeline-entry-${e.kind}-${e.id}`}
                className="flex items-center justify-between text-xs text-brand-text bg-brand-alt/50 rounded-lg px-3 py-2"
              >
                <span className="flex items-center gap-2 min-w-0">
                  <span
                    className="inline-block w-2.5 h-2.5 rounded-sm shrink-0"
                    style={{ background: HOST_COLORS[i % HOST_COLORS.length] }}
                  />
                  <span className="truncate">{e.name}</span>
                  <span className="text-[10px] text-brand-text-muted uppercase shrink-0">{e.kind}</span>
                </span>
                <span className="text-brand-text-muted font-mono shrink-0 ml-2">{formatMins(e.mins)}</span>
              </li>
            ))}
          </ul>
          <p className="text-[11px] text-brand-text-muted mt-3">
            Apps roll up their member hosts. Non-app hosts surface individually.
            'Other' covers the long tail past the top {TOP_N} — not unmapped hosts.
          </p>
        </div>
      )}

      {window === 'today' && (dayData?.suppressedHosts ?? []).length > 0 && (
        <SuppressedHostsPanel hosts={dayData?.suppressedHosts ?? []} />
      )}

      {window === 'week' && weekHosts.length > 0 && (
        <div className="bg-white rounded-2xl border border-brand-border p-5">
          <h2 className="text-sm font-semibold text-brand-text uppercase tracking-wider mb-3">
            Top hosts
          </h2>
          <ul className="space-y-1.5">
            {weekHosts.map((h, i) => (
              <li
                key={`${h.host.type}:${h.host.value}`}
                data-testid={`device-timeline-host-${h.host.value}`}
                className="flex items-center justify-between text-xs text-brand-text bg-brand-alt/50 rounded-lg px-3 py-2"
              >
                <span className="flex items-center gap-2 min-w-0">
                  <span
                    className="inline-block w-2.5 h-2.5 rounded-sm shrink-0"
                    style={{ background: HOST_COLORS[i % HOST_COLORS.length] }}
                  />
                  <HostCell host={h.host} className="truncate" />
                </span>
                <span
                  className="text-brand-text-muted font-mono shrink-0 ml-2"
                  title={`presence ${formatMins(h.presenceMins)} (every bucket this host appeared in)`}
                >
                  {formatMins(h.mins)}
                  <span className="text-brand-text-muted"> ({formatMins(h.presenceMins)})</span>
                </span>
              </li>
            ))}
          </ul>
          <p className="text-[11px] text-brand-text-muted mt-3">
            Per-host minutes are byte-share-weighted wall-clock attention within each 5-minute
            window (#715); presence in parens is how many buckets the host appeared in at all.
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

// #1507 — collapsed panel surfacing hosts the engaged-time calculation excluded
// (device-level infra: Apple OCSP, Google connectivity probes, push, …; or sub-
// keepalive-threshold bytes). The same `Presence.isHeartbeat` predicate the
// rollup builder uses tags these rows, so the "excluded" view can't drift from
// the "counted" view. Bytes still happened; they just don't drive engagement.
function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`
  if (n < 1024 * 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)} MB`
  return `${(n / (1024 * 1024 * 1024)).toFixed(2)} GB`
}

function SuppressedHostsPanel({ hosts }: { hosts: SuppressedHostUsage[] }) {
  const [open, setOpen] = useState(true)
  const sorted = [...hosts].sort((a, b) => b.bytes - a.bytes)
  const totalBytes = sorted.reduce((a, h) => a + h.bytes, 0)
  return (
    <div
      data-testid="device-timeline-suppressed"
      className="bg-white rounded-2xl border border-brand-border p-5"
    >
      <button
        type="button"
        onClick={() => setOpen(o => !o)}
        className="flex w-full items-center justify-between text-left"
        aria-expanded={open}
      >
        <h2 className="text-sm font-semibold text-brand-text uppercase tracking-wider">
          Background / infra (no engaged time)
        </h2>
        <span className="text-xs text-brand-text-muted font-mono">
          {sorted.length} {sorted.length === 1 ? 'host' : 'hosts'} · {formatBytes(totalBytes)}
          <span className="ml-2">{open ? '▾' : '▸'}</span>
        </span>
      </button>
      {open && (
        <>
          <ul className="mt-3 space-y-1.5">
            {sorted.map(h => (
              <li
                key={`${h.host.type}:${h.host.value}`}
                data-testid={`device-timeline-suppressed-${h.host.value}`}
                className="flex items-center justify-between text-xs text-brand-text bg-brand-alt/50 rounded-lg px-3 py-2"
              >
                <span className="flex items-center gap-2 min-w-0">
                  <span className="truncate font-mono">{h.host.value}</span>
                  <span className="text-[10px] text-brand-text-muted uppercase shrink-0">
                    {h.reason === 'infra' ? 'infra' : 'keepalive'}
                  </span>
                </span>
                <span className="text-brand-text-muted font-mono shrink-0 ml-2 tabular-nums">
                  {formatBytes(h.bytes)}
                  <span className="text-brand-text-muted"> · {h.buckets} bkt</span>
                </span>
              </li>
            ))}
          </ul>
          <p className="text-[11px] text-brand-text-muted mt-3">
            Hosts shown as background are reached during app sessions but didn’t drive engagement
            (CDN, telemetry, keepalives). They contribute 0 to engaged-minutes; their bytes are
            still counted.
          </p>
        </>
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
  const tail = (data?.topEntries ?? []).filter(e => e.dayMins > 0).slice(topN)
  const otherTotal = tail.reduce((a, e) => a + e.dayMins, 0)
  const grandTotal = (data?.topEntries ?? []).reduce((a, e) => a + e.dayMins, 0)
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
        className="bg-white rounded-2xl border border-brand-border-strong w-full max-w-lg p-6 space-y-4 max-h-[80vh] flex flex-col"
        onClick={e => e.stopPropagation()}
      >
        <div className="flex items-start justify-between">
          <div>
            <h3 id="other-drillin-title" className="text-lg font-bold text-brand-ink">
              Inside the “Other” bucket
            </h3>
            <p className="text-xs text-brand-text-muted mt-0.5">
              Apps &amp; hosts past the top {topN} on {date}.
            </p>
          </div>
          <button
            onClick={onClose}
            className="text-brand-text-muted hover:text-brand-text text-xl leading-none"
            aria-label="Close"
          >×</button>
        </div>

        {loading && (
          <div className="text-sm text-brand-text-muted" data-testid="device-timeline-other-loading">
            Loading the long-tail…
          </div>
        )}
        {error && (
          <div className="bg-red-500/10 border border-red-500/30 text-red-700 text-sm rounded-xl px-4 py-3">
            {error}
          </div>
        )}
        {!loading && !error && tail.length === 0 && (
          <div
            data-testid="device-timeline-other-empty"
            className="text-sm text-brand-text-muted"
          >
            Nothing else recorded for this day — the top {topN} already cover everything.
          </div>
        )}
        {!loading && !error && tail.length > 0 && (
          <ul className="space-y-1 overflow-y-auto pr-1 -mr-1">
            {tail.map(e => {
              const shareOther = otherTotal > 0 ? (e.dayMins / otherTotal) * 100 : 0
              const shareTotal = grandTotal > 0 ? (e.dayMins / grandTotal) * 100 : 0
              return (
                <li
                  key={`${e.entity.kind}:${e.entity.id}`}
                  data-testid={`device-timeline-other-entry-${e.entity.kind}-${e.entity.id}`}
                  className="flex items-center justify-between text-xs text-brand-text bg-brand-alt/50 rounded-lg px-3 py-2 gap-2"
                >
                  <span className="truncate min-w-0 flex items-center gap-2">
                    <span className="truncate">{e.entity.name}</span>
                    <span className="text-[10px] text-brand-text-muted uppercase shrink-0">{e.entity.kind}</span>
                  </span>
                  <span className="text-brand-text-muted font-mono shrink-0 tabular-nums">
                    {formatMins(e.dayMins)}
                    <span className="text-brand-text-muted ml-2">
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
            className="w-full py-2.5 rounded-xl bg-brand-alt text-brand-text text-sm font-medium hover:bg-brand-alt transition-colors"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  )
}
