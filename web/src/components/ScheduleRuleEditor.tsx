import { useMemo, useState } from 'react'
import { useNamedSchedules } from '@/api/queries'
import { SchedulePicker } from '@/components/SchedulePicker'
import type { AppScheduleMode, AppScheduleRule } from '@/types/api'

// #1380 — schedule rules on an app's profile assignment, and (#3005) on a group's attachment.
// Each rule attaches a #1069 household named schedule (via the shared SchedulePicker) with a mode:
//   • Allowed during — the app (or the group's apps) stays reachable while the schedule's window
//     is active, even during profile downtime (a carve-out). Still subject to the daily time limit
//     unless it doesn't count toward it (rows 9a–9c), and to any budget (D17).
//   • Blocked during — dropped while the window is active, even when the profile is otherwise
//     unrestricted.
// No bespoke time editor here — the named schedule owns its day/time windows
// (the picker's "Custom" flow authors a reusable one). Add/remove autosaves the
// assignment (no Save button), consistent with the block/allow toggles above.
const SCHEDULE_MODE_LABEL: Record<AppScheduleMode, string> = {
  allowed_during: 'Allowed during',
  blocked_during: 'Blocked during',
}

export function ScheduleRuleEditor({
  idPrefix, subject, rules, exemptFromDaily, showExemptToggle, busy,
  onAdd, onRemove, onSetExempt,
}: {
  // #3005 — the row's test-id prefix. The editor's root is `<prefix>-schedules` and its inner ids
  // `<prefix>-schedule-…`, the ids an app row had before the editor was shared.
  idPrefix: string
  // What the rules apply to, for the empty-state copy.
  subject: 'app' | 'group'
  rules: AppScheduleRule[]
  exemptFromDaily: boolean
  // #2747 — true only for a Blocked app or (#3005) a Blocked group. TimeLimited and Allowed rows
  // govern the same exemptFromDaily flag from their own "Counts toward daily limit" row checkbox;
  // exactly one control per mode. A Blocked row has no row checkbox (nothing to exempt) but CAN be
  // carved open by an allowed_during rule, which is where the in-window copy below is accurate.
  showExemptToggle: boolean
  busy: boolean
  onAdd: (scheduleId: number, mode: AppScheduleMode) => void | Promise<void>
  onRemove: (scheduleId: number, mode: AppScheduleMode) => void | Promise<void>
  onSetExempt: (next: boolean) => void | Promise<void>
}) {
  const [pickMode, setPickMode] = useState<AppScheduleMode>('allowed_during')
  const [pickScheduleId, setPickScheduleId] = useState<number | null>(null)
  const { data: namedSchedules = [] } = useNamedSchedules()

  const scheduleNameById = useMemo(() => {
    const m = new Map<number, string>()
    namedSchedules.forEach(s => m.set(s.id, s.name))
    return m
  }, [namedSchedules])

  const hasAllowedRule = rules.some(r => r.mode === 'allowed_during')
  const base = idPrefix

  function add() {
    if (pickScheduleId == null) return
    void onAdd(pickScheduleId, pickMode)
    setPickScheduleId(null)
  }

  const modeBtn = 'text-xs px-2.5 py-1 rounded-lg border border-transparent transition-colors disabled:opacity-50'
  const modeOn = 'bg-brand-accent/20 text-brand-accent'
  const modeOff = 'bg-brand-alt text-brand-text'

  return (
    <div
      data-testid={`${idPrefix}-schedules`}
      className="border-t border-brand-border pt-2 space-y-2"
    >
      <p className="text-xs font-semibold text-brand-text-muted uppercase tracking-wider">
        Schedules
      </p>

      {rules.length === 0 && (
        <p className="text-xs text-brand-text-muted italic">
          No schedule rules. Attach a named schedule to make this {subject === 'group' ? 'group' : 'app'} reachable
          or blocked only during a window.
        </p>
      )}

      {rules.map(r => (
        <div
          key={`${r.scheduleId}-${r.mode}`}
          data-testid={`${base}-schedule-rule-${r.scheduleId}-${r.mode}`}
          className="flex items-start gap-2 bg-brand-surface border border-brand-border-strong rounded-lg px-2.5 py-1.5"
        >
          <div className="flex-1 min-w-0">
            <p className="text-xs text-brand-ink">
              <span
                className={`font-medium ${r.mode === 'allowed_during' ? 'text-brand-accent' : 'text-red-700'}`}
              >
                {SCHEDULE_MODE_LABEL[r.mode]}
              </span>
              {' '}
              <span className="font-medium">{scheduleNameById.get(r.scheduleId) ?? `schedule ${r.scheduleId}`}</span>
            </p>
            <p className="text-[11px] text-brand-text-muted">
              {r.mode === 'allowed_during'
                ? (subject === 'group'
                  ? "The group's apps are reachable while this window is active — even during profile downtime. An app's own block or time limit still applies."
                  : 'Reachable while this window is active — even during profile downtime.')
                : 'Blocked while this window is active — even when the profile is otherwise allowed.'}
            </p>
          </div>
          <button
            type="button"
            data-testid={`${base}-schedule-rule-${r.scheduleId}-${r.mode}-remove`}
            aria-label={`Remove ${SCHEDULE_MODE_LABEL[r.mode]} ${scheduleNameById.get(r.scheduleId) ?? 'schedule'} rule`}
            disabled={busy}
            onClick={() => onRemove(r.scheduleId, r.mode)}
            className="text-brand-text-muted hover:text-red-700 transition-colors leading-none text-sm disabled:opacity-50"
          >×</button>
        </div>
      ))}

      {/* #1380 — exempt-from-daily surfaced alongside an allowed-during rule so
          the cap-bites-non-exempt behaviour (rows 9a–9c) isn't a surprise. */}
      {hasAllowedRule && showExemptToggle && (
        <label
          data-testid={`${base}-schedule-exempt`}
          className="flex items-start gap-2 text-xs text-brand-text cursor-pointer select-none"
        >
          <input
            type="checkbox"
            checked={exemptFromDaily}
            disabled={busy}
            onChange={e => onSetExempt(e.target.checked)}
            className="w-3.5 h-3.5 mt-0.5 accent-amber-500"
          />
          <span>
            {exemptFromDaily
              ? 'Exempt from the daily time limit — reachable in-window even past the cap.'
              : 'Still blocked by the daily time limit — the cap applies even in-window.'}
          </span>
        </label>
      )}

      <div className="space-y-2">
        <div className="inline-flex rounded-lg bg-brand-alt p-0.5">
          {(['allowed_during', 'blocked_during'] as const).map(m => (
            <button
              key={m}
              type="button"
              data-testid={`${base}-schedule-mode-${m}`}
              disabled={busy}
              onClick={() => setPickMode(m)}
              className={`${modeBtn} ${pickMode === m ? modeOn : modeOff}`}
            >{SCHEDULE_MODE_LABEL[m]}</button>
          ))}
        </div>
        <SchedulePicker
          value={pickScheduleId}
          onChange={setPickScheduleId}
          disabled={busy}
          testId={`${base}-schedule-picker`}
        />
        <button
          type="button"
          data-testid={`${base}-schedule-add`}
          disabled={busy || pickScheduleId == null}
          onClick={add}
          className="text-xs px-2.5 py-1 rounded-lg bg-brand-accent/10 text-brand-accent font-medium hover:bg-brand-accent/20 disabled:opacity-50"
        >Add rule</button>
      </div>
    </div>
  )
}
