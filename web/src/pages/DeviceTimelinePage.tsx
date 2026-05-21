import { useEffect, useMemo, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import {
  Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer,
  Tooltip, XAxis, YAxis,
} from 'recharts'
import { api } from '@/api/client'
import type { UsageBucket, UsageSeriesResponse } from '@/types/api'
import { PageLoader } from './DashboardPage'

// #721 — per-device daily timeline. Hourly stacked-bar chart of minutes-of-use
// for a single device on a chosen day. Hosts beyond topN collapse into "Other";
// bare-IP rows render (italic) so the operator can spot the FQDN gap (#718).

const TOP_N = 5
const DEFAULT_TZ =
  Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'

const HOST_COLORS = [
  '#10b981', // emerald
  '#3b82f6', // blue
  '#a855f7', // purple
  '#f59e0b', // amber
  '#ec4899', // pink
  '#14b8a6', // teal
  '#f97316', // orange
  '#8b5cf6', // violet
]
const OTHER_COLOR = '#4b5563' // gray-600

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

interface ChartRow {
  hour: string
  total: number
  [hostKey: string]: number | string
}

function buildChartData(
  buckets: UsageBucket[],
  hostKeys: string[],
): ChartRow[] {
  return buckets.map(b => {
    const row: ChartRow = { hour: String(b.hour).padStart(2, '0'), total: b.totalMins }
    for (const k of hostKeys) row[k] = 0
    for (const ph of b.perHost) row[ph.host.value] = ph.mins
    row['__other'] = b.otherMins
    return row
  })
}

export function DeviceTimelinePage() {
  const { mac = '' } = useParams<{ mac: string }>()
  const [params, setParams] = useSearchParams()

  const initialDate = params.get('date') ?? todayISO()
  const [date, setDate] = useState<string>(initialDate)
  const [data, setData] = useState<UsageSeriesResponse | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    setLoading(true)
    setError(null)
    api.usage.series({ mac, date, tz: DEFAULT_TZ, topN: TOP_N })
      .then(setData)
      .catch(e => setError(e.message ?? 'Failed to load'))
      .finally(() => setLoading(false))
  }, [mac, date])

  function setDateAndPush(next: string) {
    setDate(next)
    const sp = new URLSearchParams(params)
    sp.set('date', next)
    setParams(sp, { replace: true })
  }

  const chart = useMemo(() => {
    if (!data) return { rows: [] as ChartRow[], hostKeys: [] as string[] }
    // Belt-and-suspenders: skip hosts that floor to 0m in the legend/stack.
    // The server already filters these out, but rendering an invisible bar
    // with a "0m" legend entry looks broken if anything slips through.
    const hostKeys = data.topHosts.filter(h => h.dayMins > 0).map(h => h.host.value)
    return { rows: buildChartData(data.buckets, hostKeys), hostKeys }
  }, [data])

  if (loading && !data) return <PageLoader />

  const dayTotal = data?.buckets.reduce((a, b) => a + b.totalMins, 0) ?? 0
  const isEmpty  = dayTotal === 0

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between gap-2 flex-wrap">
        <div className="min-w-0">
          <Link to="/devices" className="text-xs text-gray-500 hover:text-emerald-400">
            ← Devices
          </Link>
          <h1 className="text-xl font-bold text-white truncate" data-testid="device-timeline-name">
            {data?.deviceName ?? mac}
          </h1>
          <p className="text-xs text-gray-500 font-mono">{mac}</p>
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
            Hourly minutes
          </h2>
          <span className="text-xs text-gray-500 font-mono">
            {dayTotal}m total · {data?.tz}
          </span>
        </div>

        {isEmpty ? (
          <div
            data-testid="device-timeline-empty"
            className="h-64 flex items-center justify-center text-gray-600 text-sm border border-dashed border-gray-800 rounded-xl"
          >
            No usage recorded on {date}.
          </div>
        ) : (
          <div data-testid="device-timeline-chart" className="h-72 -ml-2">
            <ResponsiveContainer width="100%" height="100%">
              <BarChart data={chart.rows} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
                <CartesianGrid stroke="#1f2937" strokeDasharray="3 3" vertical={false} />
                <XAxis
                  dataKey="hour"
                  tick={{ fill: '#6b7280', fontSize: 11 }}
                  axisLine={{ stroke: '#374151' }}
                  tickLine={false}
                />
                <YAxis
                  tick={{ fill: '#6b7280', fontSize: 11 }}
                  axisLine={{ stroke: '#374151' }}
                  tickLine={false}
                  width={32}
                  unit="m"
                />
                <Tooltip
                  cursor={{ fill: '#1f293780' }}
                  contentStyle={{
                    background: '#0a0f1c',
                    border: '1px solid #374151',
                    borderRadius: '8px',
                    fontSize: 12,
                  }}
                  labelFormatter={(h) => `${String(h)}:00 – ${String(h)}:59`}
                  // Hide 0m rows entirely — they're noise for sparse devices
                  // where multiple background hosts each accrue sub-minute
                  // shares within an hour and floor to zero.
                  formatter={(v, n) => {
                    if (Number(v) === 0) return null as unknown as [string, string]
                    return [`${String(v)}m`, n === '__other' ? 'Other' : String(n)]
                  }}
                />
                <Legend
                  iconType="square"
                  wrapperStyle={{ fontSize: 12, color: '#9ca3af', paddingTop: 8 }}
                  formatter={(n: string) => (n === '__other' ? 'Other' : n)}
                />
                {chart.hostKeys.map((k, i) => (
                  <Bar
                    key={k}
                    dataKey={k}
                    stackId="hosts"
                    fill={HOST_COLORS[i % HOST_COLORS.length]}
                  />
                ))}
                <Bar dataKey="__other" stackId="hosts" fill={OTHER_COLOR} name="Other" />
              </BarChart>
            </ResponsiveContainer>
          </div>
        )}
      </div>

      {data && data.topHosts.length > 0 && (
        <div className="bg-gray-900 rounded-2xl border border-gray-800 p-5">
          <h2 className="text-sm font-semibold text-gray-400 uppercase tracking-wider mb-3">
            Top hosts
          </h2>
          <ul className="space-y-1.5">
            {data.topHosts.filter(h => h.dayMins > 0).map((h, i) => {
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
                  <span className="text-gray-500 font-mono shrink-0 ml-2">{h.dayMins}m</span>
                </li>
              )
            })}
          </ul>
          <p className="text-[11px] text-gray-600 mt-3">
            Per-host minutes are proportional within each 5-minute window. The stack sums to
            the device's wall-clock minutes for that hour.
          </p>
        </div>
      )}
    </div>
  )
}
