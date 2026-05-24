import { useEffect, useMemo, useRef, useState } from 'react'
import { localTime } from './usageHelpers'

// #862 / #951 — "Jump to" picker. Re-anchors the right edge of the infinite-
// scroll window. `value === null` = "now" (no anchor); the caller passes that
// through as `until = undefined` to the API.
//
// Renders as a trigger button + month-calendar popover. We roll our own
// calendar so it works consistently across browsers (Safari/Firefox don't
// render a calendar UI for type=datetime-local) and so the styling matches
// the rest of the FilterShelf surface.
export function JumpToDate({
  value,
  onChange,
}: {
  value: string | null  // ISO instant or null = "now"
  onChange: (next: string | null) => void
}) {
  const [open, setOpen] = useState(false)
  const ref = useRef<HTMLDivElement>(null)

  useEffect(() => {
    if (!open) return
    function onDoc(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [open])

  const triggerLabel = value ? localTime(value) : 'now'

  return (
    <div ref={ref} className="relative inline-block text-xs" data-testid="jump-to-date">
      <span className="block text-gray-400">Jump to</span>
      <div className="flex items-center gap-1 mt-1">
        <button
          type="button"
          data-testid="jump-to-date-trigger"
          onClick={() => setOpen(o => !o)}
          className={`inline-flex items-center gap-1.5 bg-gray-950 border rounded px-2 py-1 text-sm hover:border-emerald-700 ${
            value ? 'border-emerald-800 text-emerald-200' : 'border-gray-800 text-gray-300'
          }`}
          title="Pick a date / time to anchor the window"
        >
          <span aria-hidden="true">📅</span>
          <span className="font-mono text-xs">{triggerLabel}</span>
        </button>
        {value && (
          <button
            type="button"
            data-testid="jump-to-date-now"
            onClick={() => { setOpen(false); onChange(null) }}
            className="text-xs text-gray-400 hover:text-gray-200 px-2 py-1 border border-gray-800 rounded"
            title="Re-anchor to now"
          >
            now
          </button>
        )}
      </div>
      {open && (
        <CalendarPopover
          value={value}
          onPick={(iso) => { onChange(iso); setOpen(false) }}
          onClose={() => setOpen(false)}
        />
      )}
    </div>
  )
}

function CalendarPopover({
  value,
  onPick,
  onClose,
}: {
  value: string | null
  onPick: (iso: string) => void
  onClose: () => void
}) {
  // Seed the visible month + the selected day from `value`, or from "now"
  // if no anchor is set yet.
  const seed = useMemo(() => (value ? new Date(value) : new Date()), [value])
  const [view, setView]   = useState<{ year: number; month: number }>({
    year:  seed.getFullYear(),
    month: seed.getMonth(),
  })
  const [day, setDay]     = useState<{ y: number; m: number; d: number }>({
    y: seed.getFullYear(),
    m: seed.getMonth(),
    d: seed.getDate(),
  })
  const [hh, setHH] = useState<string>(pad2(seed.getHours()))
  const [mm, setMM] = useState<string>(pad2(seed.getMinutes()))

  const grid = useMemo(() => buildMonthGrid(view.year, view.month), [view])
  const monthLabel = new Date(view.year, view.month, 1)
    .toLocaleString(undefined, { month: 'long', year: 'numeric' })

  function shiftMonth(delta: number) {
    const d = new Date(view.year, view.month + delta, 1)
    setView({ year: d.getFullYear(), month: d.getMonth() })
  }

  function apply() {
    const h = clampInt(hh, 0, 23, 0)
    const min = clampInt(mm, 0, 59, 0)
    const dt = new Date(day.y, day.m, day.d, h, min, 0, 0)
    onPick(dt.toISOString())
  }

  return (
    <div
      data-testid="jump-to-date-popover"
      className="absolute left-0 z-20 mt-1 w-72 rounded border border-gray-800 bg-gray-950 p-2 shadow-lg"
    >
      <div className="flex items-center justify-between mb-2">
        <button
          type="button"
          data-testid="jump-to-date-prev-month"
          onClick={() => shiftMonth(-1)}
          className="px-2 py-0.5 text-gray-400 hover:text-emerald-300"
          aria-label="Previous month"
        >‹</button>
        <span className="text-xs uppercase tracking-wide text-gray-300">{monthLabel}</span>
        <button
          type="button"
          data-testid="jump-to-date-next-month"
          onClick={() => shiftMonth(1)}
          className="px-2 py-0.5 text-gray-400 hover:text-emerald-300"
          aria-label="Next month"
        >›</button>
      </div>
      <div className="grid grid-cols-7 gap-0.5 text-[10px] text-gray-500 mb-1">
        {['S','M','T','W','T','F','S'].map((d, i) => (
          <div key={i} className="text-center">{d}</div>
        ))}
      </div>
      <div className="grid grid-cols-7 gap-0.5 mb-2">
        {grid.map((cell, i) => {
          if (!cell) return <div key={i} />
          const selected = cell.y === day.y && cell.m === day.m && cell.d === day.d
          const iso = `${cell.y}-${pad2(cell.m + 1)}-${pad2(cell.d)}`
          return (
            <button
              key={i}
              type="button"
              data-testid={`jump-to-date-day-${iso}`}
              onClick={() => setDay(cell)}
              className={`text-xs rounded py-1 ${
                selected
                  ? 'bg-emerald-700 text-white'
                  : 'text-gray-300 hover:bg-gray-800'
              }`}
            >
              {cell.d}
            </button>
          )
        })}
      </div>
      <div className="flex items-center gap-1 mb-2 text-xs text-gray-300">
        <span className="text-gray-500">Time</span>
        <input
          data-testid="jump-to-date-hh"
          type="text"
          inputMode="numeric"
          value={hh}
          onChange={e => setHH(e.target.value.replace(/\D/g, '').slice(0, 2))}
          className="w-10 bg-gray-900 border border-gray-800 rounded px-1 py-0.5 text-center font-mono"
          aria-label="Hour"
        />
        <span>:</span>
        <input
          data-testid="jump-to-date-mm"
          type="text"
          inputMode="numeric"
          value={mm}
          onChange={e => setMM(e.target.value.replace(/\D/g, '').slice(0, 2))}
          className="w-10 bg-gray-900 border border-gray-800 rounded px-1 py-0.5 text-center font-mono"
          aria-label="Minute"
        />
        <span className="text-[10px] text-gray-500 ml-1">24h, local</span>
      </div>
      <div className="flex items-center justify-end gap-2">
        <button
          type="button"
          data-testid="jump-to-date-cancel"
          onClick={onClose}
          className="text-xs text-gray-400 hover:text-gray-200 px-2 py-1"
        >
          cancel
        </button>
        <button
          type="button"
          data-testid="jump-to-date-apply"
          onClick={apply}
          className="text-xs bg-emerald-700 hover:bg-emerald-600 text-white rounded px-3 py-1"
        >
          jump
        </button>
      </div>
    </div>
  )
}

function pad2(n: number): string {
  return String(n).padStart(2, '0')
}

function clampInt(s: string, lo: number, hi: number, fallback: number): number {
  const n = parseInt(s, 10)
  if (Number.isNaN(n)) return fallback
  return Math.max(lo, Math.min(hi, n))
}

// Build a 6-row × 7-col grid for the visible month, with leading/trailing
// blanks so the first column is always Sunday.
function buildMonthGrid(year: number, month: number): Array<{ y: number; m: number; d: number } | null> {
  const first = new Date(year, month, 1)
  const startDow = first.getDay() // 0=Sun
  const daysInMonth = new Date(year, month + 1, 0).getDate()
  const cells: Array<{ y: number; m: number; d: number } | null> = []
  for (let i = 0; i < startDow; i++) cells.push(null)
  for (let d = 1; d <= daysInMonth; d++) cells.push({ y: year, m: month, d })
  while (cells.length % 7 !== 0) cells.push(null)
  return cells
}
