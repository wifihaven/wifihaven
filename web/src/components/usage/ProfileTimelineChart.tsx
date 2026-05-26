import { useMemo, useState } from 'react'
import {
  Bar, BarChart, CartesianGrid, ResponsiveContainer, Tooltip, XAxis, YAxis,
} from 'recharts'
import { api } from '@/api/client'
import {
  useTimeStatusProfileWeek, useUsageSeriesProfileToday,
} from '@/api/queries'
import { useEscapeClose } from '@/hooks/useEscapeClose'
import type {
  UsageBucket, UsageDeviceBucket, UsageSeriesResponse,
} from '@/types/api'
import {
  formatMins, groupBucketsByLocalDay, localBucketOffsetMin,
} from '@/pages/TimePage'
import {
  HOST_COLORS, OTHER_KEY, UsageHourlyBarChart, type ChartSeries,
} from './UsageHourlyBarChart'

// #1036 — per-profile timeline chart restored on the expanded /profiles card.
// Combines the old `/time` Today/Week toggle (#722) with the #964 group-by
// drill-down (host / device / app-gated) and the Other long-tail drill-in
// modal (#966). Pure SPA — reuses /api/usage/series and the profile-week
// status endpoint that already power the legacy pages.

const TOP_N = 5
const DRILL_IN_TOP_N = 500
const DEFAULT_TZ = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'

type Win = 'today' | 'week'
type StackBy = 'total' | 'host' | 'device' | 'app'

interface StackByOption {
  key: StackBy
  label: string
  disabled?: boolean
  title?: string
}

const STACK_BY_OPTIONS: ReadonlyArray<StackByOption> = [
  { key: 'total',  label: 'Total' },
  { key: 'host',   label: 'Host' },
  { key: 'device', label: 'Device' },
  {
    key: 'app',
    label: 'App',
    disabled: true,
    title: 'Grouping by app requires /api/usage/series app support — coming soon.',
  },
]

function todayISO(): string {
  const d = new Date()
  const yyyy = d.getFullYear()
  const mm = String(d.getMonth() + 1).padStart(2, '0')
  const dd = String(d.getDate()).padStart(2, '0')
  return `${yyyy}-${mm}-${dd}`
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

export function ProfileTimelineChart({ profileId }: { profileId: number }) {
  const [win, setWin] = useState<Win>('today')
  const [stackBy, setStackBy] = useState<StackBy>('total')
  const date = todayISO()

  const todayQuery = useUsageSeriesProfileToday(profileId, date, DEFAULT_TZ, {
    enabled: win === 'today',
  })
  const weekQuery = useTimeStatusProfileWeek(profileId, undefined, localBucketOffsetMin(), {
    enabled: win === 'week',
  })

  const dayData  = todayQuery.data
  const weekData = weekQuery.data

  const [otherOpen, setOtherOpen]       = useState(false)
  const [otherLoading, setOtherLoading] = useState(false)
  const [otherError, setOtherError]     = useState<string | null>(null)
  const [otherData, setOtherData]       = useState<UsageSeriesResponse | null>(null)

  function openOtherDrillIn() {
    setOtherOpen(true)
    setOtherError(null)
    setOtherLoading(true)
    api.usage
      .series({ profileId, date, tz: DEFAULT_TZ, topN: DRILL_IN_TOP_N })
      .then(setOtherData)
      .catch(e => setOtherError(e.message ?? 'Failed to load'))
      .finally(() => setOtherLoading(false))
  }

  const dayChart = useMemo(() => {
    if (!dayData) return { rows: [], series: [] as ChartSeries[] }
    if (stackBy === 'host') {
      const keys = (dayData.topHosts ?? []).filter(h => h.dayMins > 0).map(h => h.host.value)
      const series: ChartSeries[] = keys.map((k, i) => ({
        key: k, name: k, color: HOST_COLORS[i % HOST_COLORS.length],
      }))
      return { rows: buildHostRows(dayData.buckets, keys), series }
    }
    if (stackBy === 'device') {
      const top = (dayData.topDevices ?? []).filter(d => d.dayMins > 0)
      const keys = top.map(d => d.deviceMac)
      const series: ChartSeries[] = top.map((d, i) => ({
        key: d.deviceMac, name: d.deviceName, color: HOST_COLORS[i % HOST_COLORS.length],
      }))
      return { rows: buildDeviceRows(dayData.bucketsByDevice ?? [], keys), series }
    }
    const rows = dayData.buckets.map(b => ({
      hour: String(b.hour).padStart(2, '0'),
      total: b.totalMins,
      __total: b.totalMins,
      [OTHER_KEY]: 0,
    })) as { hour: string; total: number; [k: string]: number | string }[]
    const series: ChartSeries[] = [
      { key: '__total', name: 'Total', color: HOST_COLORS[0] },
    ]
    return { rows, series }
  }, [dayData, stackBy])

  const weekChart = useMemo(
    () => (weekData ? groupBucketsByLocalDay(weekData.perBucket, weekData.to) : []),
    [weekData],
  )

  const dayBuckets = stackBy === 'device' ? dayData?.bucketsByDevice : dayData?.buckets
  const dayTotal   = dayBuckets?.reduce((a, b) => a + b.totalMins, 0) ?? 0
  const dayEmpty   = win === 'today' && !todayQuery.isPending && dayTotal === 0
  const weekTotal  = weekData?.totalMins ?? 0
  const weekEmpty  = win === 'week' && !weekQuery.isPending && weekTotal === 0

  const hasOther = dayChart.rows.some(r => Number(r[OTHER_KEY] ?? 0) > 0)

  return (
    <div
      data-testid={`profile-timeline-${profileId}`}
      className="bg-gray-950/40 border border-gray-800 rounded-xl p-4 space-y-3"
    >
      <div className="flex items-center justify-between gap-2 flex-wrap">
        <h3 className="text-xs font-semibold text-gray-400 uppercase tracking-wider">
          {win === 'today' ? 'Hourly minutes today' : 'Daily minutes (trailing 7 days)'}
        </h3>
        <div className="flex items-center gap-2 flex-wrap">
          <div role="tablist" aria-label="Window"
            className="inline-flex rounded-lg bg-gray-800 p-0.5 text-xs">
            {(['today', 'week'] as const).map(w => (
              <button key={w} type="button" role="tab"
                aria-selected={win === w}
                data-testid={`profile-timeline-${profileId}-window-${w}`}
                onClick={() => setWin(w)}
                className={`px-3 py-1 rounded-md font-medium transition-colors ${
                  win === w
                    ? 'bg-emerald-500 text-black'
                    : 'text-gray-400 hover:text-gray-200'
                }`}>
                {w === 'today' ? 'Today' : 'Week'}
              </button>
            ))}
          </div>
          <div role="tablist" aria-label="Stack by"
            className="inline-flex rounded-lg bg-gray-800 p-0.5 text-xs">
            {STACK_BY_OPTIONS.map(opt => {
              const inactive = opt.disabled || win === 'week'
              const selected = win === 'today' && stackBy === opt.key
              return (
                <button key={opt.key} type="button" role="tab"
                  aria-selected={selected}
                  aria-disabled={inactive || undefined}
                  disabled={opt.disabled}
                  title={opt.title ?? (win === 'week'
                    ? 'Per-day stacked breakdown for Week needs backend support (#1078).'
                    : undefined)}
                  data-testid={`profile-timeline-${profileId}-stack-${opt.key}`}
                  onClick={() => { if (!opt.disabled) setStackBy(opt.key) }}
                  className={`px-3 py-1 rounded-md font-medium transition-colors ${
                    selected
                      ? 'bg-emerald-500 text-black'
                      : inactive
                        ? 'text-gray-600 cursor-not-allowed'
                        : 'text-gray-400 hover:text-gray-200'
                  }`}>
                  {opt.label}
                </button>
              )
            })}
          </div>
        </div>
      </div>

      {win === 'today' && todayQuery.error && (
        <div className="text-xs text-red-400">
          Failed to load: {(todayQuery.error as Error).message}
        </div>
      )}
      {win === 'week' && weekQuery.error && (
        <div className="text-xs text-red-400">
          Failed to load: {(weekQuery.error as Error).message}
        </div>
      )}

      {win === 'today' ? (
        dayEmpty ? (
          <div data-testid={`profile-timeline-${profileId}-empty`}
            className="h-48 flex items-center justify-center text-gray-600 text-xs border border-dashed border-gray-800 rounded-xl">
            {todayQuery.isPending ? 'Loading hourly usage…' : 'No usage recorded yet today.'}
          </div>
        ) : (
          <>
            <UsageHourlyBarChart
              rows={dayChart.rows}
              series={dayChart.series}
              showLegend={stackBy === 'host' || stackBy === 'device'}
              legendFormatter={stackBy === 'device'
                ? (k) => dayChart.series.find(s => s.key === k)?.name ?? k
                : undefined}
              onOtherClick={stackBy === 'host' ? openOtherDrillIn : undefined}
              testId={`profile-timeline-${profileId}-chart`}
            />
            <div className="flex items-center justify-between text-[11px] text-gray-500 font-mono gap-2">
              <span>{formatMins(dayTotal)} total · {dayData?.tz ?? DEFAULT_TZ}</span>
              {stackBy === 'host' && hasOther && (
                <button type="button"
                  onClick={openOtherDrillIn}
                  data-testid={`profile-timeline-${profileId}-other-button`}
                  className="text-[11px] text-gray-500 hover:text-emerald-400 underline decoration-dotted underline-offset-2"
                  title="See the hosts inside the Other bucket">
                  Inside “Other” ↗
                </button>
              )}
            </div>
          </>
        )
      ) : (
        weekEmpty ? (
          <div data-testid={`profile-timeline-${profileId}-week-empty`}
            className="h-48 flex items-center justify-center text-gray-600 text-xs border border-dashed border-gray-800 rounded-xl">
            {weekQuery.isPending ? 'Loading weekly usage…' : 'No usage recorded this week.'}
          </div>
        ) : (
          <>
            <div className="h-48 -ml-2" data-testid={`profile-timeline-${profileId}-week-chart`}>
              <ResponsiveContainer width="100%" height="100%">
                <BarChart data={weekChart} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
                  <CartesianGrid stroke="#1f2937" strokeDasharray="3 3" vertical={false} />
                  <XAxis dataKey="label"
                    tick={{ fill: '#6b7280', fontSize: 11 }}
                    axisLine={{ stroke: '#374151' }}
                    tickLine={false} />
                  <YAxis
                    tick={{ fill: '#6b7280', fontSize: 11 }}
                    axisLine={{ stroke: '#374151' }}
                    tickLine={false}
                    width={44}
                    tickFormatter={(v: number) => formatMins(v)} />
                  <Tooltip
                    cursor={{ fill: '#1f293780' }}
                    contentStyle={{
                      background: '#0a0f1c',
                      border: '1px solid #374151',
                      borderRadius: '8px',
                      fontSize: 12,
                    }}
                    labelFormatter={(_, payload) => String(payload?.[0]?.payload?.date ?? '')}
                    formatter={(v) => [formatMins(Number(v)), 'Used']} />
                  <Bar dataKey="usedMins" fill={HOST_COLORS[0]} />
                </BarChart>
              </ResponsiveContainer>
            </div>
            <p className="text-[11px] text-gray-500 font-mono">
              {formatMins(weekTotal)} total · {weekData?.from} → {weekData?.to}
            </p>
          </>
        )
      )}

      {/* #715/#957 — proportional attention is the wall-clock-share number; the
          Other long-tail drill-in surfaces host-presence for the leftover bucket. */}
      {win === 'today' && (stackBy === 'host' || stackBy === 'device') && (
        <p className="text-[10px] text-gray-600">
          Stacks total to wall-clock minutes per hour. Per-host minutes are byte-share-weighted
          (proportional) within each 5-min window (#715).
        </p>
      )}

      {otherOpen && (
        <OtherDrillInModal
          date={date}
          loading={otherLoading}
          error={otherError}
          data={otherData}
          topN={TOP_N}
          onClose={() => setOtherOpen(false)}
          testIdPrefix={`profile-timeline-${profileId}`}
        />
      )}
    </div>
  )
}

interface OtherDrillInModalProps {
  date: string
  loading: boolean
  error: string | null
  data: UsageSeriesResponse | null
  topN: number
  onClose: () => void
  testIdPrefix: string
}

function OtherDrillInModal({
  date, loading, error, data, topN, onClose, testIdPrefix,
}: OtherDrillInModalProps) {
  useEscapeClose(onClose)
  const tail = (data?.topHosts ?? []).filter(h => h.dayMins > 0).slice(topN)
  const otherTotal = tail.reduce((a, h) => a + h.dayMins, 0)
  const grandTotal = (data?.topHosts ?? []).reduce((a, h) => a + h.dayMins, 0)
  return (
    <div role="dialog" aria-modal="true"
      data-testid={`${testIdPrefix}-other-modal`}
      className="fixed inset-0 bg-black/70 flex items-end sm:items-center justify-center z-50 p-4"
      onClick={onClose}>
      <div className="bg-gray-900 rounded-2xl border border-gray-700 w-full max-w-lg p-6 space-y-4 max-h-[80vh] flex flex-col"
        onClick={e => e.stopPropagation()}>
        <div className="flex items-start justify-between">
          <div>
            <h3 className="text-lg font-bold text-white">Inside the “Other” bucket</h3>
            <p className="text-xs text-gray-500 mt-0.5">Hosts below the top {topN} on {date}.</p>
          </div>
          <button onClick={onClose}
            className="text-gray-500 hover:text-gray-300 text-xl leading-none"
            aria-label="Close">×</button>
        </div>

        {loading && <div className="text-sm text-gray-500">Loading the long-tail…</div>}
        {error && (
          <div className="bg-red-500/10 border border-red-500/30 text-red-300 text-sm rounded-xl px-4 py-3">
            {error}
          </div>
        )}
        {!loading && !error && tail.length === 0 && (
          <div className="text-sm text-gray-500">
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
                <li key={`${h.host.type}:${h.host.value}`}
                  data-testid={`${testIdPrefix}-other-host-${h.host.value}`}
                  className="flex items-center justify-between text-xs bg-gray-800/50 rounded-lg px-3 py-2 gap-2">
                  <span className={`font-mono truncate min-w-0 ${isIp ? 'italic text-gray-500' : 'text-gray-300'}`}
                    title={h.host.value}>
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
          <button onClick={onClose}
            className="w-full py-2.5 rounded-xl bg-gray-800 text-gray-300 text-sm font-medium hover:bg-gray-700 transition-colors">
            Close
          </button>
        </div>
      </div>
    </div>
  )
}
