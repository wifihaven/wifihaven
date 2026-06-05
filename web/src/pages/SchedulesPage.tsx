import { useEffect, useRef, useState } from 'react'
import { api } from '@/api/client'
import { useNamedSchedules, useProfiles, useInvalidators } from '@/api/queries'
import { useDebouncedSave } from '@/hooks/useDebouncedSave'
import { SaveStatusBadge } from '@/components/SaveStatusBadge'
import { EmptyState } from '@/components/EmptyState'
import { ScheduleWindowsEditor, emptyWindow } from '@/components/ScheduleWindowsEditor'
import { browserTimezone } from '@/components/TimezonePicker'
import type { NamedSchedule, ProfileDetail, ScheduleWindow } from '@/types/api'
import { PageLoader } from './DashboardPage'

// #1069 — Schedules management page. Household-scoped named schedules are the
// reusable time-window primitive ("Bedtime", "School hours") that profiles
// (today) and per-app rules / blocklists (next) reference by id. Editing a
// schedule here reflects everywhere that references it on the next snapshot
// rebuild — the API folds the active windows into the per-MAC BlockRules; the
// router never sees schedules (see AGENTS.md "Architectural model").
//
// Edits autosave (#995/#423) via PATCH carrying the full schedule shape.
// Deleting a referenced schedule cascade-removes its references server-side, so
// the delete is guarded behind a warning listing the profiles that use it.
export function SchedulesPage() {
  const { data: schedules, isLoading } = useNamedSchedules()
  const { data: profiles } = useProfiles()
  const invalidators = useInvalidators()
  const [creating, setCreating] = useState(false)

  if (isLoading || !schedules) return <PageLoader />

  const refsByScheduleId = referencesByScheduleId(profiles ?? [])

  return (
    <div className="space-y-6" data-testid="schedules-page">
      <div className="flex items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-bold text-brand-ink">Schedules</h1>
          <p className="text-sm text-brand-text-muted mt-1">
            Reusable named time windows. Define <strong>Bedtime</strong> once and reference it from
            profiles, apps, and blocklists — edit it here and every reference updates.
          </p>
        </div>
        {!creating && (
          <button type="button"
            onClick={() => setCreating(true)}
            data-testid="schedules-new"
            className="shrink-0 text-sm bg-brand-accent text-white px-3 py-2 rounded-lg">
            + New schedule
          </button>
        )}
      </div>

      {creating && (
        <CreateScheduleCard
          onCancel={() => setCreating(false)}
          onCreated={() => { setCreating(false); void invalidators.schedules() }}
        />
      )}

      {schedules.length === 0 && !creating ? (
        <EmptyState
          data-testid="schedules-empty"
          title="No schedules yet"
          hint="Create a named schedule like “Bedtime” or “School hours” to reuse across profiles."
        />
      ) : (
        <div className="space-y-4">
          {schedules.map(s => (
            <ScheduleCard
              key={s.id}
              schedule={s}
              referencedBy={refsByScheduleId.get(s.id) ?? []}
              onChanged={() => invalidators.schedules()}
            />
          ))}
        </div>
      )}
    </div>
  )
}

// Profiles that reference a given schedule id (via ProfileDetail.scheduleIds).
// Apps / blocklists gain references as those surfaces land (#1380 / #1067);
// they slot in here the same way.
function referencesByScheduleId(profiles: ProfileDetail[]): Map<number, string[]> {
  const m = new Map<number, string[]>()
  for (const pd of profiles) {
    for (const sid of pd.scheduleIds ?? []) {
      const list = m.get(sid) ?? []
      list.push(pd.profile.name)
      m.set(sid, list)
    }
  }
  return m
}

interface ScheduleForm {
  name: string
  description: string
  windows: ScheduleWindow[]
}

function formFrom(s: NamedSchedule): ScheduleForm {
  return { name: s.name, description: s.description ?? '', windows: s.windows }
}

function ScheduleCard({
  schedule, referencedBy, onChanged,
}: {
  schedule: NamedSchedule
  referencedBy: string[]
  onChanged: () => void
}) {
  const [form, setForm] = useState<ScheduleForm>(() => formFrom(schedule))
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [deleteError, setDeleteError] = useState<string | null>(null)

  // Resync from server when there are no pending local edits.
  const baselineRef = useRef<ScheduleForm>(form)
  const formRef = useRef<ScheduleForm>(form)
  formRef.current = form
  useEffect(() => {
    const incoming = formFrom(schedule)
    if (formsEqual(formRef.current, baselineRef.current) && !formsEqual(formRef.current, incoming)) {
      setForm(incoming)
    }
    baselineRef.current = incoming
  }, [schedule])

  const { status, error, retry } = useDebouncedSave(
    form,
    async (v) => {
      await api.schedules.update(schedule.id, {
        name: v.name.trim(),
        description: v.description.trim() || null,
        windows: v.windows,
      })
      onChanged()
    },
    { key: schedule.id, equals: formsEqual },
  )

  const nameInvalid = form.name.trim() === ''

  async function doDelete() {
    setDeleteError(null)
    try {
      await api.schedules.delete(schedule.id)
      onChanged()
    } catch (e) {
      setDeleteError(e instanceof Error ? e.message : 'Failed to delete')
    }
  }

  return (
    <div
      data-testid={`schedule-card-${schedule.id}`}
      className="bg-white border border-brand-border rounded-xl p-4 space-y-3">
      <div className="flex items-center gap-3">
        <input type="text"
          value={form.name}
          onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
          placeholder="Schedule name"
          data-testid={`schedule-name-${schedule.id}`}
          className={`flex-1 bg-white border rounded-lg px-3 py-2 text-brand-ink text-sm font-semibold ${
            nameInvalid ? 'border-red-500' : 'border-brand-border-strong'
          }`} />
        <SaveStatusBadge status={status} error={error} onRetry={retry}
          testId={`schedule-save-${schedule.id}`} />
        <button type="button"
          onClick={() => setConfirmDelete(true)}
          data-testid={`schedule-delete-${schedule.id}`}
          className="text-xs text-red-700 hover:text-red-700 bg-red-500/10 px-3 py-2 rounded-lg">
          Delete
        </button>
      </div>

      <input type="text"
        value={form.description}
        onChange={e => setForm(f => ({ ...f, description: e.target.value }))}
        placeholder="Description (optional)"
        data-testid={`schedule-description-${schedule.id}`}
        className="w-full bg-white border border-brand-border-strong rounded-lg px-3 py-2 text-brand-ink text-sm" />

      <ScheduleWindowsEditor
        windows={form.windows}
        onChange={ws => setForm(f => ({ ...f, windows: ws }))}
        testId={`schedule-windows-${schedule.id}`}
        defaultTz={form.windows[0]?.tz ?? browserTimezone()}
      />

      {referencedBy.length > 0 && (
        <p className="text-xs text-brand-text-muted" data-testid={`schedule-refs-${schedule.id}`}>
          Used by {referencedBy.length} profile{referencedBy.length === 1 ? '' : 's'}: {referencedBy.join(', ')}
        </p>
      )}

      {confirmDelete && (
        <div
          data-testid={`schedule-delete-confirm-${schedule.id}`}
          className="bg-red-500/10 border border-red-500/30 rounded-lg p-3 space-y-2">
          <p className="text-xs text-red-700">
            {referencedBy.length > 0
              ? `“${schedule.name}” is used by ${referencedBy.join(', ')}. Deleting it removes that downtime from ${referencedBy.length === 1 ? 'that profile' : 'those profiles'}. Continue?`
              : `Delete “${schedule.name}”? This can't be undone.`}
          </p>
          {deleteError && <p className="text-xs text-red-700">{deleteError}</p>}
          <div className="flex gap-2">
            <button type="button"
              onClick={doDelete}
              data-testid={`schedule-delete-confirm-yes-${schedule.id}`}
              className="text-xs bg-red-600 text-white px-3 py-2 rounded-lg">
              Delete schedule
            </button>
            <button type="button"
              onClick={() => { setConfirmDelete(false); setDeleteError(null) }}
              data-testid={`schedule-delete-confirm-no-${schedule.id}`}
              className="text-xs text-brand-text-muted hover:text-brand-ink px-3 py-2 rounded-lg">
              Cancel
            </button>
          </div>
        </div>
      )}
    </div>
  )
}

function CreateScheduleCard({
  onCancel, onCreated,
}: {
  onCancel: () => void
  onCreated: () => void
}) {
  const tz = browserTimezone()
  const [name, setName] = useState('')
  const [description, setDescription] = useState('')
  const [windows, setWindows] = useState<ScheduleWindow[]>(() => [emptyWindow(tz)])
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function create() {
    const trimmed = name.trim()
    if (!trimmed) {
      setError('Name is required')
      return
    }
    setSaving(true)
    setError(null)
    try {
      await api.schedules.create({
        name: trimmed,
        description: description.trim() || null,
        windows,
      })
      onCreated()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to create schedule')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div
      data-testid="schedule-create-card"
      className="bg-white border border-brand-border rounded-xl p-4 space-y-3">
      <h2 className="text-sm font-semibold text-brand-ink">New schedule</h2>
      <input type="text"
        value={name}
        onChange={e => setName(e.target.value)}
        placeholder="Schedule name (e.g. Bedtime)"
        data-testid="schedule-create-name"
        className="w-full bg-white border border-brand-border-strong rounded-lg px-3 py-2 text-brand-ink text-sm" />
      <input type="text"
        value={description}
        onChange={e => setDescription(e.target.value)}
        placeholder="Description (optional)"
        data-testid="schedule-create-description"
        className="w-full bg-white border border-brand-border-strong rounded-lg px-3 py-2 text-brand-ink text-sm" />
      <ScheduleWindowsEditor
        windows={windows}
        onChange={setWindows}
        testId="schedule-create-windows"
        defaultTz={tz}
      />
      {error && <p className="text-xs text-red-700" data-testid="schedule-create-error">{error}</p>}
      <div className="flex gap-2">
        <button type="button"
          onClick={create}
          disabled={saving}
          data-testid="schedule-create-submit"
          className="text-xs bg-brand-accent text-white px-3 py-2 rounded-lg disabled:opacity-60">
          {saving ? 'Creating…' : 'Create schedule'}
        </button>
        <button type="button"
          onClick={onCancel}
          data-testid="schedule-create-cancel"
          className="text-xs text-brand-text-muted hover:text-brand-ink px-3 py-2 rounded-lg">
          Cancel
        </button>
      </div>
    </div>
  )
}

function formsEqual(a: ScheduleForm, b: ScheduleForm): boolean {
  return (
    a.name === b.name &&
    a.description === b.description &&
    a.windows.length === b.windows.length &&
    a.windows.every((w, i) => {
      const o = b.windows[i]
      return (
        w.startLocal === o.startLocal &&
        w.endLocal === o.endLocal &&
        w.tz === o.tz &&
        w.days.length === o.days.length &&
        w.days.every((d, j) => d === o.days[j])
      )
    })
  )
}
