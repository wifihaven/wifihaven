import type { ScheduleWindow } from '@/types/api'
import { TimezonePicker } from '@/components/TimezonePicker'

// #1069 — shared editor for a named schedule's window list. A window is the
// typed shape PolicyService evaluates: a day set + a local start/end + tz.
// Used by both the Schedules management page and the SchedulePicker's
// "Custom (define inline)" form so the two author windows identically.
export const DAYS = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'] as const

export const WEEKDAYS: string[] = ['mon', 'tue', 'wed', 'thu', 'fri']

export function emptyWindow(tz: string): ScheduleWindow {
  return { days: [...WEEKDAYS], startLocal: '20:00', endLocal: '07:00', tz }
}

export function ScheduleWindowsEditor({
  windows, onChange, disabled = false, testId, defaultTz,
}: {
  windows: ScheduleWindow[]
  onChange: (next: ScheduleWindow[]) => void
  disabled?: boolean
  testId: string
  defaultTz: string
}) {
  const patch = (i: number, p: Partial<ScheduleWindow>) =>
    onChange(windows.map((w, idx) => (idx === i ? { ...w, ...p } : w)))
  const remove = (i: number) => onChange(windows.filter((_, idx) => idx !== i))
  const add = () => onChange([...windows, emptyWindow(defaultTz)])
  const toggleDay = (i: number, d: string) =>
    patch(i, {
      days: windows[i].days.includes(d)
        ? windows[i].days.filter(x => x !== d)
        : [...windows[i].days, d],
    })

  return (
    <div className="space-y-3" data-testid={testId}>
      {windows.length === 0 && (
        <p className="text-xs text-brand-text-muted">No windows — this schedule is never active.</p>
      )}
      {windows.map((w, i) => (
        <div key={i}
          data-testid={`${testId}-window-${i}`}
          className="bg-brand-surface border border-brand-border-strong rounded-xl p-3 space-y-2">
          <div className="flex gap-2 items-center text-sm">
            <input type="time" value={w.startLocal}
              disabled={disabled}
              onChange={e => patch(i, { startLocal: e.target.value })}
              data-testid={`${testId}-start-${i}`}
              className="bg-white border border-brand-border-strong rounded-lg px-3 py-2 text-brand-ink disabled:opacity-60" />
            <span className="text-brand-text-muted">→</span>
            <input type="time" value={w.endLocal}
              disabled={disabled}
              onChange={e => patch(i, { endLocal: e.target.value })}
              data-testid={`${testId}-end-${i}`}
              className="bg-white border border-brand-border-strong rounded-lg px-3 py-2 text-brand-ink disabled:opacity-60" />
            <span className="flex-1" />
            {!disabled && (
              <button type="button"
                onClick={() => remove(i)}
                data-testid={`${testId}-remove-${i}`}
                className="text-xs text-red-700 hover:text-red-700 bg-red-500/10 px-3 py-2 rounded-lg">
                Remove
              </button>
            )}
          </div>
          <TimezonePicker
            value={w.tz}
            onChange={tz => patch(i, { tz })}
            testId={`${testId}-tz-${i}`}
          />
          <div className="flex flex-wrap gap-1">
            {DAYS.map(d => {
              const on = w.days.includes(d)
              return (
                <button type="button"
                  key={d}
                  disabled={disabled}
                  onClick={() => toggleDay(i, d)}
                  data-testid={`${testId}-day-${i}-${d}`}
                  aria-pressed={on}
                  className={`text-xs px-2 py-1 rounded-lg capitalize disabled:opacity-60 ${
                    on
                      ? 'bg-brand-accent text-white'
                      : 'bg-brand-alt text-brand-text hover:text-brand-ink'
                  }`}>
                  {d}
                </button>
              )
            })}
          </div>
        </div>
      ))}
      {!disabled && (
        <button type="button"
          onClick={add}
          data-testid={`${testId}-add-window`}
          className="text-xs text-brand-accent hover:text-brand-accent">
          + Add window
        </button>
      )}
    </div>
  )
}
