import { useState } from 'react'
import { api } from '@/api/client'
import { useNamedSchedules, useInvalidators } from '@/api/queries'
import type { ScheduleWindow } from '@/types/api'
import { browserTimezone } from '@/components/TimezonePicker'
import { ScheduleWindowsEditor, emptyWindow } from '@/components/ScheduleWindowsEditor'

// #1069 — reusable schedule-reference dropdown for any time-bound edit form
// (per-profile, per-app #1380, per-blocklist #1067). Offers the household's
// named schedules + "Always" (value = null) + "Custom (define inline)".
//
// Design decision (the issue defers this to the implementation): "Custom"
// **transparently creates a household-scoped named schedule** rather than an
// inline-only one. Rationale — #1069's whole point is that a schedule is a
// single shared primitive ("edit bedtime once, it changes everywhere"). An
// inline-only window would fork the model and re-introduce the per-reference
// window editing this issue exists to remove. So the inline form authors a
// real named schedule (it then appears on the Schedules page and is reusable),
// and the picker emits its new id.
//
// `value` is the selected named-schedule id, or null for "Always". The
// component fetches the catalog itself (shared cached query) so consumers only
// wire value/onChange.
export const ALWAYS_VALUE = '__always__'
const CUSTOM_VALUE = '__custom__'

export function SchedulePicker({
  value, onChange, disabled = false, allowCustom = true,
  testId = 'schedule-picker', defaultTz = browserTimezone(),
}: {
  value: number | null
  onChange: (id: number | null) => void
  disabled?: boolean
  allowCustom?: boolean
  testId?: string
  defaultTz?: string
}) {
  const { data: schedules = [] } = useNamedSchedules()
  const invalidators = useInvalidators()

  const [customOpen, setCustomOpen] = useState(false)
  const [name, setName] = useState('')
  const [windows, setWindows] = useState<ScheduleWindow[]>(() => [emptyWindow(defaultTz)])
  const [creating, setCreating] = useState(false)
  const [error, setError] = useState<string | null>(null)

  function handleSelect(raw: string) {
    if (raw === ALWAYS_VALUE) {
      setCustomOpen(false)
      onChange(null)
    } else if (raw === CUSTOM_VALUE) {
      setError(null)
      setCustomOpen(true)
    } else {
      setCustomOpen(false)
      onChange(Number(raw))
    }
  }

  async function createCustom() {
    const trimmed = name.trim()
    if (!trimmed) {
      setError('Name is required')
      return
    }
    setCreating(true)
    setError(null)
    try {
      const created = await api.schedules.create({ name: trimmed, windows })
      await invalidators.schedules()
      setCustomOpen(false)
      setName('')
      setWindows([emptyWindow(defaultTz)])
      onChange(created.id)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to create schedule')
    } finally {
      setCreating(false)
    }
  }

  // Reflect the live selection in the <select>. While the custom form is open,
  // the select shows the Custom option so the form's presence is explained.
  const selectValue = customOpen
    ? CUSTOM_VALUE
    : value === null
      ? ALWAYS_VALUE
      : String(value)

  return (
    <div data-testid={testId} className="space-y-2">
      <select
        value={selectValue}
        disabled={disabled}
        data-testid={`${testId}-select`}
        onChange={e => handleSelect(e.target.value)}
        className="w-full bg-white border border-brand-border-strong rounded-lg px-3 py-2 text-sm text-brand-ink disabled:opacity-60">
        <option value={ALWAYS_VALUE}>Always</option>
        {schedules.map(s => (
          <option key={s.id} value={String(s.id)}>{s.name}</option>
        ))}
        {allowCustom && !disabled && (
          <option value={CUSTOM_VALUE}>Custom (define inline)…</option>
        )}
      </select>

      {customOpen && (
        <div
          data-testid={`${testId}-custom-form`}
          className="bg-brand-surface/40 border border-brand-border rounded-xl p-3 space-y-3">
          <p className="text-xs text-brand-text-muted">
            Creates a reusable household schedule you can attach elsewhere.
          </p>
          <input type="text"
            value={name}
            onChange={e => setName(e.target.value)}
            placeholder="Schedule name (e.g. Bedtime)"
            data-testid={`${testId}-custom-name`}
            className="w-full bg-white border border-brand-border-strong rounded-lg px-3 py-2 text-brand-ink text-sm" />
          <ScheduleWindowsEditor
            windows={windows}
            onChange={setWindows}
            testId={`${testId}-custom-windows`}
            defaultTz={defaultTz}
          />
          {error && <p className="text-xs text-red-700" data-testid={`${testId}-custom-error`}>{error}</p>}
          <div className="flex gap-2">
            <button type="button"
              onClick={createCustom}
              disabled={creating}
              data-testid={`${testId}-custom-create`}
              className="text-xs bg-brand-accent text-white px-3 py-2 rounded-lg disabled:opacity-60">
              {creating ? 'Creating…' : 'Create schedule'}
            </button>
            <button type="button"
              onClick={() => { setCustomOpen(false); setError(null) }}
              data-testid={`${testId}-custom-cancel`}
              className="text-xs text-brand-text-muted hover:text-brand-ink px-3 py-2 rounded-lg">
              Cancel
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
