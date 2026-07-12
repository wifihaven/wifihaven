import type { ReactNode } from 'react'
import { render } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { UsageHourlyBarChart, type ChartSeries } from './UsageHourlyBarChart'

// #2156 — the hourly Y-axis rendered raw "{n}m" inside a 32px-wide axis, so a
// 3-digit label (e.g. a 180m Sum-mode bar's "200m" tick) clipped to "00m". The
// week chart already renders its Y ticks through formatMins (#791) — the shared
// hourly chart must do the same so ≥60 ticks become "H:MM" and never clip.
//
// Recharts is fully stubbed in jsdom (ResizeObserver / SVG bits), so we capture
// the props the component hands to <YAxis> and assert the tick formatter + width
// directly rather than measuring rendered pixels.
const captured: { yAxisProps?: Record<string, unknown> } = {}

vi.mock('recharts', () => {
  const Passthrough = ({ children }: { children?: ReactNode }) => <div>{children}</div>
  return {
    ResponsiveContainer: Passthrough,
    BarChart: Passthrough,
    Bar: () => null,
    CartesianGrid: () => null,
    XAxis: () => null,
    YAxis: (props: Record<string, unknown>) => {
      captured.yAxisProps = props
      return null
    },
    Tooltip: () => null,
    Legend: () => null,
  }
})

const rows = [
  { hour: '14', total: 200, __youtube: 120, __other: 80 },
]
const series: ChartSeries[] = [{ key: '__youtube', name: 'YouTube', color: '#10b981' }]

describe('UsageHourlyBarChart Y-axis (#2156)', () => {
  it('formats tick labels through formatMins so 3-digit minutes do not clip', () => {
    render(<UsageHourlyBarChart rows={rows} series={series} />)
    const fmt = captured.yAxisProps?.tickFormatter as ((v: number) => string) | undefined
    expect(fmt).toBeTypeOf('function')
    // ≥60 renders as H:MM (never a clipped "00m" / "60m").
    expect(fmt!(200)).toBe('3:20')
    expect(fmt!(180)).toBe('3:00')
    expect(fmt!(60)).toBe('1:00')
    // <60 stays "Nm".
    expect(fmt!(50)).toBe('50m')
    expect(fmt!(0)).toBe('0m')
  })

  it('gives the axis enough width for H:MM labels', () => {
    render(<UsageHourlyBarChart rows={rows} series={series} />)
    expect(Number(captured.yAxisProps?.width)).toBeGreaterThanOrEqual(44)
  })
})
