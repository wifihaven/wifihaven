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
  UsageDeviceBucket, UsageEntityBucket, UsageSeriesResponse,
} from '@/types/api'
import {
  formatMins, groupBucketsByLocalDay, localBucketOffsetMin,
} from '@/lib/timeFormat'
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
type StackBy = 'total' | 'device' | 'app'

interface StackByOption {
  key: StackBy
  label: string
  disabled?: boolean
  title?: string
}

// #1079 — Host axis retired; the by-app axis is the new combined surface.
const STACK_BY_OPTIONS: ReadonlyArray<StackByOption> = [
  { key: 'total',  label: 'Total' },
  { key: 'device', label: 'Device' },
  { key: 'app',    label: 'App' },
]

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

export function ProfileTimelineChart({ profileId }: { profileId: number }) {
  const [win, setWin] = useState<Win>('today')
  const [stackBy, setStackBy] = useState<StackBy>('total')
  const date = todayISO()

  const todayQuery = useUsageSeriesProfileToday(profileId, date, DEFAULT_TZ, {
    enabled: win === 'today',
    groupBy: stackBy === 'app' ? 'app' : undefined,
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
      .series({ profileId, date, tz: DEFAULT_TZ, topN: DRILL_IN_TOP_N, groupBy: 'app' })
      .then(setOtherData)
      .catch(e => setOtherError(e.message ?? 'Failed to load'))
      .finally(() => setOtherLoading(false))
  }

  const dayChart = useMemo(() => {
    if (!dayData) return { rows: [], series: [] as ChartSeries[] }
    if (stackBy === 'app') {
      const top  = (dayData.topEntries ?? []).filter(e => e.dayMins > 0)
      const keys = top.map(e => entityKey(e.entity))
      const series: ChartSeries[] = top.map((e, i) => ({
        key: entityKey(e.entity), name: e.entity.name, color: HOST_COLORS[i % HOST_COLORS.length],
      }))
      return { rows: buildEntityRows(dayData.bucketsByEntry ?? [], keys), series }
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

  const dayBuckets =
    stackBy === 'device' ? dayData?.bucketsByDevice
    : stackBy === 'app'    ? dayData?.bucketsByEntry
    : dayData?.buckets
  const dayTotal   = dayBuckets?.reduce((a, b) => a + b.totalMins, 0) ?? 0
  const dayEmpty   = win === 'today' && !todayQuery.isPending && dayTotal === 0
  const weekTotal  = weekData?.totalMins ?? 0
  const weekEmpty  = win === 'week' && !weekQuery.isPending && weekTotal === 0

  const hasOther = dayChart.rows.some(r => Number(r[OTHER_KEY] ?? 0) > 0)

  return (
    <div
      data-testid={`profile-timeline-${profileId}`}
      className="bg-brand-surface/40 border border-brand-border rounded-xl p-4 space-y-3"
    >
      <div className="flex items-center justify-between gap-2 flex-wrap">
        <h3 className="text-xs font-semibold text-brand-text uppercase tracking-wider">
          {win === 'today' ? 'Hourly minutes today' : 'Daily minutes (trailing 7 days)'}
        </h3>
        <div className="flex items-center gap-2 flex-wrap">
          <div role="tablist" aria-label="Window"
            className="inline-flex rounded-lg bg-brand-alt p-0.5 text-xs">
            {(['today', 'week'] as const).map(w => (
              <button key={w} type="button" role="tab"
                aria-selected={win === w}
                data-testid={`profile-timeline-${profileId}-window-${w}`}
                onClick={() => setWin(w)}
                className={`px-3 py-1 rounded-md font-medium transition-colors ${
                  win === w
                    ? 'bg-brand-accent text-white'
                    : 'text-brand-text hover:text-brand-ink'
                }`}>
                {w === 'today' ? 'Today' : 'Week'}
              </button>
            ))}
          </div>
          <div role="tablist" aria-label="Stack by"
            className="inline-flex rounded-lg bg-brand-alt p-0.5 text-xs">
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
                      ? 'bg-brand-accent text-white'
                      : inactive
                        ? 'text-brand-text-muted cursor-not-allowed'
                        : 'text-brand-text hover:text-brand-ink'
                  }`}>
                  {opt.label}
                </button>
              )
            })}
          </div>
        </div>
      </div>

      {win === 'today' && todayQuery.error && (
        <div className="text-xs text-red-700">
          Failed to load: {(todayQuery.error as Error).message}
        </div>
      )}
      {win === 'week' && weekQuery.error && (
        <div className="text-xs text-red-700">
          Failed to load: {(weekQuery.error as Error).message}
        </div>
      )}

      {win === 'today' ? (
        dayEmpty ? (
          <div data-testid={`profile-timeline-${profileId}-empty`}
            className="h-48 flex items-center justify-center text-brand-text-muted text-xs border border-dashed border-brand-border rounded-xl">
            {todayQuery.isPending ? 'Loading hourly usage…' : 'No usage recorded yet today.'}
          </div>
        ) : (
          <>
            <UsageHourlyBarChart
              rows={dayChart.rows}
              series={dayChart.series}
              showLegend={stackBy === 'app' || stackBy === 'device'}
              legendFormatter={(k) => dayChart.series.find(s => s.key === k)?.name ?? k}
              onOtherClick={stackBy === 'app' ? openOtherDrillIn : undefined}
              testId={`profile-timeline-${profileId}-chart`}
            />
            <div className="flex items-center justify-between text-[11px] text-brand-text-muted font-mono gap-2">
              <span>{formatMins(dayTotal)} total · {dayData?.tz ?? DEFAULT_TZ}</span>
              {stackBy === 'app' && hasOther && (
                <button type="button"
                  onClick={openOtherDrillIn}
                  data-testid={`profile-timeline-${profileId}-other-button`}
                  className="text-[11px] text-brand-text-muted hover:text-brand-accent underline decoration-dotted underline-offset-2"
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
            className="h-48 flex items-center justify-center text-brand-text-muted text-xs border border-dashed border-brand-border rounded-xl">
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
            <p className="text-[11px] text-brand-text-muted font-mono">
              {formatMins(weekTotal)} total · {weekData?.from} → {weekData?.to}
            </p>
          </>
        )
      )}

      {/* #715/#957 — proportional attention is the wall-clock-share number; the
          Other long-tail drill-in surfaces host-presence for the leftover bucket. */}
      {win === 'today' && (stackBy === 'app' || stackBy === 'device') && (
        <p className="text-[10px] text-brand-text-muted">
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
  const tail = (data?.topEntries ?? []).filter(e => e.dayMins > 0).slice(topN)
  const otherTotal = tail.reduce((a, e) => a + e.dayMins, 0)
  const grandTotal = (data?.topEntries ?? []).reduce((a, e) => a + e.dayMins, 0)
  return (
    <div role="dialog" aria-modal="true"
      data-testid={`${testIdPrefix}-other-modal`}
      className="fixed inset-0 bg-black/70 flex items-end sm:items-center justify-center z-50 p-4"
      onClick={onClose}>
      <div className="bg-white rounded-2xl border border-brand-border-strong w-full max-w-lg p-6 space-y-4 max-h-[80vh] flex flex-col"
        onClick={e => e.stopPropagation()}>
        <div className="flex items-start justify-between">
          <div>
            <h3 className="text-lg font-bold text-brand-ink">Inside the “Other” bucket</h3>
            <p className="text-xs text-brand-text-muted mt-0.5">Apps &amp; hosts past the top {topN} on {date}.</p>
          </div>
          <button onClick={onClose}
            className="text-brand-text-muted hover:text-brand-text text-xl leading-none"
            aria-label="Close">×</button>
        </div>

        {loading && <div className="text-sm text-brand-text-muted">Loading the long-tail…</div>}
        {error && (
          <div className="bg-red-500/10 border border-red-500/30 text-red-700 text-sm rounded-xl px-4 py-3">
            {error}
          </div>
        )}
        {!loading && !error && tail.length === 0 && (
          <div className="text-sm text-brand-text-muted">
            Nothing else recorded for this day — the top {topN} already cover everything.
          </div>
        )}
        {!loading && !error && tail.length > 0 && (
          <ul className="space-y-1 overflow-y-auto pr-1 -mr-1">
            {tail.map(e => {
              const shareOther = otherTotal > 0 ? (e.dayMins / otherTotal) * 100 : 0
              const shareTotal = grandTotal > 0 ? (e.dayMins / grandTotal) * 100 : 0
              return (
                <li key={`${e.entity.kind}:${e.entity.id}`}
                  data-testid={`${testIdPrefix}-other-entry-${e.entity.kind}-${e.entity.id}`}
                  className="flex items-center justify-between text-xs text-brand-text bg-brand-alt/50 rounded-lg px-3 py-2 gap-2">
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
          <button onClick={onClose}
            className="w-full py-2.5 rounded-xl bg-brand-alt text-brand-text text-sm font-medium hover:bg-brand-alt transition-colors">
            Close
          </button>
        </div>
      </div>
    </div>
  )
}
