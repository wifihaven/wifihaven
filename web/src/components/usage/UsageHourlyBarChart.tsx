import {
  Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer,
  Tooltip, XAxis, YAxis,
} from 'recharts'

// Shared 24-hour stacked-bar chart used by:
//   - DeviceTimelinePage (#721) — stack by host
//   - ProfileTimelinePage (#722) — stack by host OR device (toggleable)
//
// The chart is intentionally dumb: it only knows how to render N stacks of
// minutes against the X-axis "hour" key. Callers compute the rows + series
// definitions for the chosen stacking mode and pass them in.

export const HOST_COLORS = [
  '#10b981', // emerald
  '#3b82f6', // blue
  '#a855f7', // purple
  '#f59e0b', // amber
  '#ec4899', // pink
  '#14b8a6', // teal
  '#f97316', // orange
  '#8b5cf6', // violet
]
export const OTHER_COLOR = '#4b5563' // gray-600
export const OTHER_KEY   = '__other'

export interface ChartSeries {
  key: string    // dataKey on each row
  name: string   // legend / tooltip label
  color: string
}

export interface ChartRow {
  hour: string
  total: number
  [key: string]: number | string
}

interface Props {
  rows: ChartRow[]
  series: ChartSeries[]   // named stacks (top-N). "Other" is rendered as a final stack if any row has __other > 0.
  showLegend?: boolean
  legendFormatter?: (name: string) => string
  // #964 — when set, the "Other" bar + legend entry become click targets
  // (cursor=pointer, dotted underline) so the per-device page can pop a
  // drill-in showing what's in the long-tail bucket.
  onOtherClick?: () => void
  testId?: string
}

export function UsageHourlyBarChart({ rows, series, showLegend = false, legendFormatter, onOtherClick, testId }: Props) {
  const hasOther = rows.some(r => Number(r[OTHER_KEY] ?? 0) > 0)
  const otherClickable = !!onOtherClick && hasOther
  return (
    <div data-testid={testId} className="h-72 -ml-2">
      <ResponsiveContainer width="100%" height="100%">
        <BarChart data={rows} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
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
            // Hide 0m rows entirely — they're noise for sparse periods where
            // multiple background series each accrue sub-minute shares.
            formatter={(v, n) => {
              if (Number(v) === 0) return null as unknown as [string, string]
              const label = n === OTHER_KEY ? 'Other' : (legendFormatter ? legendFormatter(String(n)) : String(n))
              return [`${String(v)}m`, label]
            }}
          />
          {showLegend && (
            <Legend
              iconType="square"
              wrapperStyle={{ fontSize: 12, color: '#9ca3af', paddingTop: 8 }}
              formatter={(n: string) => {
                if (n === OTHER_KEY) {
                  return otherClickable ? (
                    <span
                      role="button"
                      tabIndex={0}
                      data-testid="usage-chart-other-legend"
                      onClick={(e) => { e.stopPropagation(); onOtherClick?.() }}
                      onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); onOtherClick?.() } }}
                      className="cursor-pointer underline decoration-dotted underline-offset-2"
                      title="See the hosts inside the Other bucket"
                    >Other ↗</span>
                  ) : 'Other'
                }
                return legendFormatter ? legendFormatter(n) : n
              }}
            />
          )}
          {series.map(s => (
            <Bar key={s.key} dataKey={s.key} stackId="stacks" fill={s.color} name={s.name} />
          ))}
          <Bar
            dataKey={OTHER_KEY}
            stackId="stacks"
            fill={OTHER_COLOR}
            name="Other"
            cursor={otherClickable ? 'pointer' : undefined}
            onClick={otherClickable ? () => onOtherClick?.() : undefined}
          />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}
