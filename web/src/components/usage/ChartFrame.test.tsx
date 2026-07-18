import { render } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { Bar, BarChart, ResponsiveContainer } from 'recharts'
import { ChartFrame, CHART_HEIGHT_TALL_PX } from './ChartFrame'

// #2297 — regression guard for the ResponsiveContainer "-1/-1" console warning.
//
// jsdom ships no ResizeObserver, so Recharts' ResponsiveContainer is stuck at
// its seed measurement of -1×-1 for the whole test — exactly the transient
// first-frame state a real browser passes through. That makes the warning
// deterministic here: a percentage-height container warns, a numeric-height
// container does not.

const WARN_FRAGMENT = 'width(-1) and height(-1)'
const rows = [{ hour: '0', v: 1 }]

function chart() {
  return (
    <BarChart data={rows}>
      <Bar dataKey="v" />
    </BarChart>
  )
}

describe('ResponsiveContainer -1/-1 warning', () => {
  let warnSpy: ReturnType<typeof vi.spyOn>

  beforeEach(() => {
    warnSpy = vi.spyOn(console, 'warn').mockImplementation(() => {})
  })
  afterEach(() => {
    warnSpy.mockRestore()
  })

  function warnedAboutSize() {
    return warnSpy.mock.calls.some(
      (args: unknown[]) => typeof args[0] === 'string' && args[0].includes(WARN_FRAGMENT),
    )
  }

  it('the percentage-height pattern reproduces the warning (root cause)', () => {
    render(
      <div style={{ height: CHART_HEIGHT_TALL_PX }}>
        <ResponsiveContainer width="100%" height="100%">
          {chart()}
        </ResponsiveContainer>
      </div>,
    )
    expect(warnedAboutSize()).toBe(true)
  })

  it('ChartFrame does not warn — it gives the container a definite height', () => {
    render(<ChartFrame heightPx={CHART_HEIGHT_TALL_PX}>{chart()}</ChartFrame>)
    expect(warnedAboutSize()).toBe(false)
  })
})
