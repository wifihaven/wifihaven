import type { ReactElement } from 'react'
import { ResponsiveContainer } from 'recharts'

// #2297 — shared frame for every Recharts chart in the usage/timeline views.
//
// Recharts' ResponsiveContainer seeds its measurement at width/height = -1 and
// only corrects it once its ResizeObserver fires (one frame later). With a
// percentage width AND height, that first frame computes both dimensions as -1
// and logs:
//   "The width(-1) and height(-1) of chart should be greater than 0 …"
// Passing a *definite numeric* height makes calculatedHeight > 0 on the very
// first render, so the warning never fires while width stays responsive.
//
// The same px value sizes the wrapper div, so the reserved space matches the
// chart exactly and there is no collapse-then-resize flash on first paint.
// Loading/empty placeholders should reserve the same height (see the h-* on the
// sibling states) so switching between states doesn't jump.
interface Props {
  // Fixed chart height in px. Keep in lockstep with any sibling loading/empty
  // placeholder height so the three states occupy identical space.
  heightPx: number
  testId?: string
  // Extra classes for the wrapper (e.g. negative margins for axis alignment).
  className?: string
  // Exactly one Recharts chart element (BarChart, LineChart, …).
  children: ReactElement
}

export function ChartFrame({ heightPx, testId, className, children }: Props) {
  return (
    <div data-testid={testId} className={className} style={{ height: heightPx }}>
      <ResponsiveContainer width="100%" height={heightPx}>
        {children}
      </ResponsiveContainer>
    </div>
  )
}
