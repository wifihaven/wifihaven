import { useEffect, useState } from 'react'
import { api } from '@/api/client'
import type { HeartbeatFilter, HouseholdSettings } from '@/types/api'
import { TimezonePicker } from '@/components/TimezonePicker'
import { PageLoader } from './DashboardPage'

function formatResetTime(hhmm: string): string {
  const [hStr, mStr] = hhmm.split(':')
  const h = Number(hStr)
  const m = Number(mStr)
  if (!Number.isFinite(h) || !Number.isFinite(m)) return hhmm
  const period = h < 12 ? 'AM' : 'PM'
  const display = h % 12 === 0 ? 12 : h % 12
  return `${display}:${String(m).padStart(2, '0')} ${period}`
}

export function AdminPage() {
  const [hs, setHs] = useState<HouseholdSettings | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    api.household.get()
      .then(setHs)
      .finally(() => setLoading(false))
  }, [])

  if (loading) return <PageLoader />

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-bold text-white">Admin</h1>
        <p className="text-sm text-gray-500 mt-1">Settings that apply to the whole household.</p>
      </div>

      {hs && <DailyResetCard value={hs} onSaved={setHs} />}
      {hs && <HeartbeatFilterCard value={hs} onSaved={setHs} />}
    </div>
  )
}

function HeartbeatFilterCard({
  value, onSaved,
}: {
  value: HouseholdSettings
  onSaved: (next: HouseholdSettings) => void
}) {
  const [editing, setEditing] = useState(false)
  const [form, setForm] = useState<HeartbeatFilter>(value.heartbeatFilter)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  function startEdit() {
    setForm(value.heartbeatFilter)
    setError(null)
    setEditing(true)
  }

  function cancel() {
    setForm(value.heartbeatFilter)
    setError(null)
    setEditing(false)
  }

  const validationError: string | null = (() => {
    if (!Number.isFinite(form.bytesThreshold) || form.bytesThreshold < 0) {
      return 'Bytes threshold must be ≥ 0.'
    }
    return null
  })()

  async function save() {
    if (validationError) return
    setSaving(true)
    setError(null)
    try {
      await api.household.update({
        dailyResetTime: value.dailyResetTime,
        dailyResetTz: value.dailyResetTz,
        heartbeatFilter: {
          enabled: form.enabled,
          bytesThreshold: Math.trunc(form.bytesThreshold),
          heartbeatHostPatterns: form.heartbeatHostPatterns
            .map(p => p.trim())
            .filter(p => p.length > 0),
        },
      })
      const fresh = await api.household.get()
      onSaved(fresh)
      setEditing(false)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save')
    } finally {
      setSaving(false)
    }
  }

  const hf = value.heartbeatFilter

  return (
    <div
      data-testid="heartbeat-filter-card"
      className="bg-gray-900 rounded-2xl border border-gray-800 p-5 space-y-3"
    >
      <div className="flex items-center justify-between gap-3">
        <h2 className="font-bold text-white">Heartbeat filter</h2>
        {!editing && (
          <button
            type="button"
            onClick={startEdit}
            data-testid="heartbeat-filter-edit"
            className="text-xs text-gray-300 hover:text-white bg-gray-800 px-3 py-1.5 rounded-lg transition-colors"
          >
            Edit
          </button>
        )}
      </div>

      {!editing ? (
        <div className="space-y-1">
          <p data-testid="heartbeat-filter-summary" className="text-sm text-gray-300">
            {hf.enabled ? (
              <>
                <span className="font-medium text-white">Enabled</span>
                {' — bytes ≥ '}
                <span className="font-mono text-gray-200">{hf.bytesThreshold}</span>
              </>
            ) : (
              <span className="font-medium text-white">Disabled</span>
            )}
          </p>
          <p
            data-testid="heartbeat-filter-hosts-summary"
            className="text-xs text-gray-500"
          >
            {hf.heartbeatHostPatterns.length} host pattern
            {hf.heartbeatHostPatterns.length === 1 ? '' : 's'}
          </p>
        </div>
      ) : (
        <div className="space-y-3">
          <p className="text-xs text-gray-400">
            Excludes low-traffic background "heartbeat" rows from device/profile screen-time
            totals. A row is classified as a heartbeat when its bytes/minute is below the
            configured floor.
          </p>
          {error && (
            <div className="bg-red-500/10 border border-red-500/30 text-red-300 text-sm rounded-xl px-4 py-2">
              {error}
            </div>
          )}
          {validationError && (
            <div
              data-testid="heartbeat-filter-validation"
              className="bg-amber-500/10 border border-amber-500/30 text-amber-300 text-sm rounded-xl px-4 py-2"
            >
              {validationError}
            </div>
          )}
          <label className="flex items-center gap-2 text-sm text-gray-200">
            <input
              type="checkbox"
              checked={form.enabled}
              onChange={e => setForm({ ...form, enabled: e.target.checked })}
              data-testid="heartbeat-filter-enabled"
              className="h-4 w-4"
            />
            Filter enabled
          </label>
          <div className="flex flex-wrap gap-3 items-end">
            <div>
              <label className="block text-xs text-gray-500 mb-1">Bytes/min threshold</label>
              <input
                type="number"
                min={0}
                step={1}
                value={form.bytesThreshold}
                onChange={e => setForm({ ...form, bytesThreshold: Number(e.target.value) })}
                data-testid="heartbeat-filter-bytes"
                className="bg-gray-900 border border-gray-700 rounded-lg px-3 py-2 text-white text-sm w-32"
              />
            </div>
          </div>
          <div>
            <label className="block text-xs text-gray-500 mb-1">
              Host allowlist (one pattern per line — `*.foo.com` or `foo.com`)
            </label>
            <textarea
              value={form.heartbeatHostPatterns.join('\n')}
              onChange={e =>
                setForm({
                  ...form,
                  heartbeatHostPatterns: e.target.value.split(/\r?\n/),
                })
              }
              data-testid="heartbeat-filter-hosts"
              rows={8}
              spellCheck={false}
              className="bg-gray-900 border border-gray-700 rounded-lg px-3 py-2 text-white text-xs font-mono w-full"
            />
            <p className="text-xs text-gray-500 mt-1">
              Rows whose host matches any pattern are classified as heartbeats regardless of
              bytes / active fraction.
            </p>
          </div>
          <div className="flex gap-3 pt-1">
            <button
              type="button"
              onClick={cancel}
              disabled={saving}
              data-testid="heartbeat-filter-cancel"
              className="px-4 py-2 rounded-lg bg-gray-800 text-gray-300 text-sm font-medium disabled:opacity-50"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={save}
              disabled={saving || validationError !== null}
              data-testid="heartbeat-filter-save"
              className="px-4 py-2 rounded-lg bg-emerald-500 text-black text-sm font-semibold disabled:opacity-50"
            >
              {saving ? 'Saving…' : 'Save'}
            </button>
          </div>
        </div>
      )}
    </div>
  )
}

function DailyResetCard({
  value, onSaved,
}: {
  value: HouseholdSettings
  onSaved: (next: HouseholdSettings) => void
}) {
  const [editing, setEditing] = useState(false)
  const [form, setForm] = useState<HouseholdSettings>(value)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  function startEdit() {
    setForm(value)
    setError(null)
    setEditing(true)
  }

  function cancel() {
    setForm(value)
    setError(null)
    setEditing(false)
  }

  async function save() {
    setSaving(true)
    setError(null)
    try {
      await api.household.update({
        dailyResetTime: form.dailyResetTime,
        dailyResetTz: form.dailyResetTz,
        heartbeatFilter: value.heartbeatFilter,
      })
      // Re-read from the server so the summary reflects what was actually
      // persisted, not just the local form. Without this, a server that
      // silently dropped a field (e.g. a route handler ignoring dailyResetTz)
      // would still show the new value in the UI and confuse the operator
      // (#571).
      const fresh = await api.household.get()
      onSaved(fresh)
      setEditing(false)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div
      data-testid="household-settings-card"
      className="bg-gray-900 rounded-2xl border border-gray-800 p-5 space-y-3"
    >
      <div className="flex items-center justify-between gap-3">
        <h2 className="font-bold text-white">Daily reset</h2>
        {!editing && (
          <button
            type="button"
            onClick={startEdit}
            data-testid="household-edit"
            className="text-xs text-gray-300 hover:text-white bg-gray-800 px-3 py-1.5 rounded-lg transition-colors"
          >
            Edit
          </button>
        )}
      </div>

      {!editing ? (
        <p data-testid="household-summary" className="text-sm text-gray-300">
          Resets daily at{' '}
          <span className="font-medium text-white">{formatResetTime(value.dailyResetTime)}</span>{' '}
          (<span className="font-mono text-gray-400">{value.dailyResetTz}</span>)
        </p>
      ) : (
        <div className="space-y-3">
          <p className="text-xs text-gray-400">
            The wall-clock time at which daily usage limits reset. Both the reset time and
            timezone are stored together — the reset always fires at the configured local clock
            time, even across DST.
          </p>
          {error && (
            <div className="bg-red-500/10 border border-red-500/30 text-red-300 text-sm rounded-xl px-4 py-2">
              {error}
            </div>
          )}
          <div className="flex flex-wrap gap-3 items-end">
            <div>
              <label className="block text-xs text-gray-500 mb-1">Reset time</label>
              <input
                type="time"
                value={form.dailyResetTime}
                onChange={e => setForm({ ...form, dailyResetTime: e.target.value })}
                data-testid="household-reset-time"
                className="bg-gray-900 border border-gray-700 rounded-lg px-3 py-2 text-white text-sm"
              />
            </div>
            <div className="flex-1 min-w-[14rem]">
              <label className="block text-xs text-gray-500 mb-1">Timezone</label>
              <TimezonePicker
                value={form.dailyResetTz}
                onChange={tz => setForm({ ...form, dailyResetTz: tz })}
                testId="household-reset-tz"
              />
            </div>
          </div>
          <div className="flex gap-3 pt-1">
            <button
              type="button"
              onClick={cancel}
              disabled={saving}
              data-testid="household-cancel"
              className="px-4 py-2 rounded-lg bg-gray-800 text-gray-300 text-sm font-medium disabled:opacity-50"
            >
              Cancel
            </button>
            <button
              type="button"
              onClick={save}
              disabled={saving}
              data-testid="household-save"
              className="px-4 py-2 rounded-lg bg-emerald-500 text-black text-sm font-semibold disabled:opacity-50"
            >
              {saving ? 'Saving…' : 'Save'}
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
