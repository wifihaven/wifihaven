import { useEffect, useMemo, useState } from 'react'
import { api } from '@/api/client'
import { useProfiles } from '@/api/queries'
import type { AppDetail, IconType } from '@/types/api'
import { PageLoader } from './DashboardPage'
import { RecentApexPicker } from '@/components/RecentApexPicker'
import { IconPicker, type IconValue } from '@/components/IconPicker'
import { AppIcon } from '@/components/AppIcon'
import { EmptyState } from '@/components/EmptyState'
import { useEscapeClose } from '@/hooks/useEscapeClose'
import { useDebouncedSave, type SaveStatus } from '@/hooks/useDebouncedSave'

interface EditFormState {
  name: string
  icon: string
  iconType: IconType
  hosts: string[]
}

function blankCreateForm(): EditFormState {
  return { name: '', icon: '', iconType: 'emoji', hosts: [] }
}

function splitHosts(raw: string): string[] {
  return raw
    .split(/[\s,]+/)
    .map(s => s.trim())
    .filter(Boolean)
}

function sameHosts(a: string[], b: string[]): boolean {
  return a.length === b.length && a.every((h, i) => h === b[i])
}

export function AppsPage() {
  const [apps, setApps] = useState<AppDetail[]>([])
  const [loading, setLoading] = useState(true)
  const [creating, setCreating] = useState(false)
  const [expandedId, setExpandedId] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const { data: profiles = [] } = useProfiles()

  const profileNameById = useMemo(() => {
    const m = new Map<number, string>()
    for (const p of profiles) m.set(p.profile.id, p.profile.name)
    return m
  }, [profiles])

  async function reload() {
    const list = await api.apps.list()
    setApps([...list].sort((a, b) => a.app.name.localeCompare(b.app.name)))
  }

  useEffect(() => {
    reload()
      .catch(e => setError(e instanceof Error ? e.message : 'Failed to load apps'))
      .finally(() => setLoading(false))
  }, [])

  // #1003 — inline autosave persists each field as it changes. Rather than a
  // full reload after every keystroke-debounce, merge the saved field into the
  // open row so the editor's own resync effects see the value it just wrote
  // (no clobber) and the collapsed summary counts stay accurate.
  function applyLocal(id: number, fn: (d: AppDetail) => AppDetail) {
    setApps(prev =>
      [...prev.map(a => (a.app.id === id ? fn(a) : a))]
        .sort((a, b) => a.app.name.localeCompare(b.app.name)),
    )
  }

  if (loading) return <PageLoader />

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-brand-ink">Apps</h1>
          <p className="text-sm text-brand-text-muted mt-1">
            Bundles of hosts you can block, allow, or time-limit per profile.
          </p>
        </div>
        <button
          onClick={() => { setCreating(true); setError(null) }}
          className="bg-brand-accent hover:bg-brand-accent-dark text-white text-sm font-semibold px-4 py-2 rounded-xl transition-colors"
        >
          + New App
        </button>
      </div>

      {error && (
        <div className="bg-red-500/10 border border-red-500/30 text-red-700 text-sm rounded-xl px-4 py-2">
          {error}
        </div>
      )}

      <div className="bg-white rounded-2xl border border-brand-border overflow-hidden">
        {apps.length === 0
          ? <EmptyState title="No apps yet." hint="Create one to start grouping hosts." />
          : apps.map(a => {
              const assignProfiles = new Set(a.assignments.map(x => x.profileId))
              const expanded = expandedId === a.app.id
              return (
                <div key={a.app.id} className="border-b border-brand-border last:border-0">
                  <button
                    type="button"
                    onClick={() => setExpandedId(expanded ? null : a.app.id)}
                    aria-expanded={expanded}
                    className="w-full text-left hover:bg-brand-alt/40 transition-colors"
                  >
                    <div className="flex items-center gap-4 px-5 py-4">
                      <span className="w-8 text-center inline-flex items-center justify-center">
                        <AppIcon icon={a.app.icon} iconType={a.app.iconType} size="lg" />
                      </span>
                      <div className="flex-1 min-w-0">
                        <p className="font-medium text-brand-ink truncate">{a.app.name}</p>
                        <p className="text-xs text-brand-text-muted mt-0.5 font-mono">{a.app.slug}</p>
                      </div>
                      <div className="text-right text-xs text-brand-text shrink-0">
                        <p>{a.hosts.length} host{a.hosts.length === 1 ? '' : 's'}</p>
                        <p className="text-brand-text-muted mt-0.5">
                          {assignProfiles.size === 0
                            ? 'no profiles'
                            : `${assignProfiles.size} profile${assignProfiles.size === 1 ? '' : 's'}`}
                        </p>
                      </div>
                    </div>
                  </button>
                  {expanded && (
                    <InlineAppEditor
                      detail={a}
                      profileNameById={profileNameById}
                      onPatched={applyLocal}
                      onDeleted={async () => { setExpandedId(null); await reload() }}
                    />
                  )}
                </div>
              )
            })
        }
      </div>

      {creating && (
        <CreateAppModal
          onClose={() => setCreating(false)}
          onSaved={async () => { setCreating(false); await reload() }}
        />
      )}
    </div>
  )
}

function CreateAppModal({ onClose, onSaved }: {
  onClose: () => void
  onSaved: () => void | Promise<void>
}) {
  const [form, setForm] = useState<EditFormState>(blankCreateForm)
  const [hostsInput, setHostsInput] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [pickerOpen, setPickerOpen] = useState(false)

  const currentHosts = useMemo(() => {
    const fromText = splitHosts(hostsInput)
    return [...new Set([...form.hosts, ...fromText])]
  }, [form.hosts, hostsInput])

  function addFromPicker(picked: string[]) {
    setHostsInput(prev => {
      const existing = new Set(splitHosts(prev))
      const additions = picked.filter(h => !existing.has(h))
      if (additions.length === 0) return prev
      const sep = prev && !prev.endsWith('\n') ? '\n' : ''
      return prev + sep + additions.join('\n')
    })
    setPickerOpen(false)
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    if (!form.name.trim()) { setError('Name is required'); return }
    setSaving(true)
    setError(null)
    try {
      const iconStr = form.icon.trim()
      await api.apps.create({
        name: form.name.trim(),
        icon: iconStr || undefined,
        iconType: iconStr ? form.iconType : undefined,
        hosts: splitHosts(hostsInput),
      })
      await onSaved()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to create app')
    } finally {
      setSaving(false)
    }
  }

  return (
    <Modal title="New App" onClose={onClose}>
      <form onSubmit={submit} className="space-y-5">
        {error && (
          <div className="bg-red-500/10 border border-red-500/30 text-red-700 text-sm rounded-xl px-4 py-2">
            {error}
          </div>
        )}
        <Field label="Name" required>
          <input
            type="text" value={form.name} autoFocus required
            onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
            placeholder="YouTube"
            className="w-full bg-brand-surface border border-brand-border-strong rounded-xl px-4 py-3 text-brand-ink focus:outline-none focus:border-brand-accent"
          />
        </Field>
        <Field label="Icon (optional)">
          <IconPicker
            value={{ icon: form.icon, iconType: form.iconType }}
            onChange={(v: IconValue) => setForm(f => ({ ...f, icon: v.icon, iconType: v.iconType }))}
            hosts={currentHosts}
          />
        </Field>
        <Field label="Initial hosts (optional)">
          <textarea
            rows={4}
            value={hostsInput}
            onChange={e => setHostsInput(e.target.value)}
            placeholder="youtube.com&#10;googlevideo.com"
            className="w-full bg-brand-surface border border-brand-border-strong rounded-xl px-4 py-3 text-brand-ink font-mono text-sm focus:outline-none focus:border-brand-accent"
          />
          <div className="flex items-center justify-between mt-2 gap-3">
            <p
              className="text-xs text-brand-text-muted"
              title="Wildcards are unnecessary — example.com automatically covers every subdomain."
            >
              One per line or comma-separated. Each host also covers its subdomains
              <span className="ml-1 text-brand-text-muted cursor-help" aria-hidden="true">ⓘ</span>
            </p>
            <button
              type="button"
              onClick={() => setPickerOpen(true)}
              className="shrink-0 text-xs font-medium text-brand-accent hover:text-brand-accent bg-brand-accent/10 px-3 py-1.5 rounded-lg"
            >Pick from recent activity</button>
          </div>
        </Field>
        <div className="flex gap-3 pt-2">
          <button type="button" onClick={onClose} disabled={saving}
            className="flex-1 py-3 rounded-xl bg-brand-alt text-brand-text font-medium disabled:opacity-50">
            Cancel
          </button>
          <button type="submit" disabled={saving}
            className="flex-1 py-3 rounded-xl bg-brand-accent text-white font-semibold disabled:opacity-50">
            {saving ? 'Saving…' : 'Create App'}
          </button>
        </div>
      </form>
      {pickerOpen && (
        <RecentApexPicker
          existingHosts={currentHosts}
          onClose={() => setPickerOpen(false)}
          onAdd={addFromPicker}
        />
      )}
    </Modal>
  )
}

// #1003 — inline editor for an expanded app row. Name and icon autosave on a
// debounce; the hosts list autosaves on each add/remove (debounced, full
// replace). Each field carries its own save-status badge + Retry. There is no
// Save button — the create flow stays explicit, edit does not.
function InlineAppEditor({ detail, profileNameById, onPatched, onDeleted }: {
  detail: AppDetail
  profileNameById: Map<number, string>
  onPatched: (id: number, fn: (d: AppDetail) => AppDetail) => void
  onDeleted: () => void | Promise<void>
}) {
  const id = detail.app.id
  const [name, setName] = useState(detail.app.name)
  const [icon, setIcon] = useState(detail.app.icon ?? '')
  const [iconType, setIconType] = useState<IconType>(detail.app.iconType ?? 'emoji')
  const [hosts, setHosts] = useState<string[]>([...detail.hosts])
  const [newHost, setNewHost] = useState('')
  const [pickerOpen, setPickerOpen] = useState(false)
  const [confirmingDelete, setConfirmingDelete] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const [deleteError, setDeleteError] = useState<string | null>(null)

  // Resync from the parent after a save merges the value back in (no-op when
  // the field equals what we just wrote, so live edits aren't clobbered).
  useEffect(() => { setName(detail.app.name) }, [detail.app.name])
  useEffect(() => {
    setIcon(detail.app.icon ?? '')
    setIconType(detail.app.iconType ?? 'emoji')
  }, [detail.app.icon, detail.app.iconType])
  useEffect(() => { setHosts([...detail.hosts]) }, [detail.hosts])

  const nameSave = useDebouncedSave(
    name,
    async (next: string) => {
      const trimmed = next.trim()
      if (!trimmed) throw new Error('Name is required')
      await api.apps.patch(id, { name: trimmed })
      onPatched(id, a => ({ ...a, app: { ...a.app, name: trimmed } }))
    },
    { delayMs: 600, key: id },
  )

  const iconValue = useMemo(() => ({ icon, iconType }), [icon, iconType])
  const iconSave = useDebouncedSave(
    iconValue,
    async (v: IconValue) => {
      const iconStr = v.icon.trim()
      if (iconStr) {
        await api.apps.patch(id, { icon: iconStr, iconType: v.iconType })
        onPatched(id, a => ({ ...a, app: { ...a.app, icon: iconStr, iconType: v.iconType } }))
      } else {
        await api.apps.patch(id, { icon: null })
        onPatched(id, a => ({ ...a, app: { ...a.app, icon: null } }))
      }
    },
    { delayMs: 600, key: id, equals: (a, b) => a.icon === b.icon && a.iconType === b.iconType },
  )

  const hostsSave = useDebouncedSave(
    hosts,
    async (next: string[]) => {
      await api.apps.patch(id, { hosts: next })
      onPatched(id, a => ({ ...a, hosts: [...next] }))
    },
    { delayMs: 600, key: id, equals: sameHosts },
  )

  function addHosts() {
    const additions = splitHosts(newHost)
    if (additions.length === 0) return
    setHosts(hs => [...new Set([...hs, ...additions])])
    setNewHost('')
  }

  function removeHost(h: string) {
    setHosts(hs => hs.filter(x => x !== h))
  }

  function addFromPicker(picked: string[]) {
    if (picked.length === 0) return
    setHosts(hs => [...new Set([...hs, ...picked])])
    setPickerOpen(false)
  }

  const assignedProfiles = useMemo(() => {
    const ids = new Set(detail.assignments.map(a => a.profileId))
    return [...ids].map(pid => profileNameById.get(pid) ?? `profile ${pid}`)
  }, [detail.assignments, profileNameById])

  async function performDelete() {
    setDeleting(true)
    setDeleteError(null)
    try {
      await api.apps.delete(id)
      await onDeleted()
    } catch (e) {
      setDeleteError(e instanceof Error ? e.message : 'Failed to delete app')
      setDeleting(false)
    }
  }

  // #768 — template host list lives on the starter-library track; until that
  // ships we can only detect "has a template" via templateId. The button is
  // visible-but-disabled with a tooltip explaining what it'll do once #768
  // lands.
  const hasTemplate = detail.app.templateId !== null

  return (
    <div className="px-5 pb-5 pt-1 space-y-5 bg-brand-surface/30 border-t border-brand-border">
      <div>
        <label
          htmlFor={`app-name-input-${id}`}
          className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2"
        >
          Name
        </label>
        <div className="flex items-center gap-3">
          <input
            id={`app-name-input-${id}`}
            data-testid={`app-name-input-${id}`}
            aria-label="App name"
            type="text"
            value={name}
            onChange={e => setName(e.target.value)}
            className="flex-1 bg-brand-surface border border-brand-border-strong rounded-xl px-4 py-3 text-brand-ink focus:outline-none focus:border-brand-accent"
          />
          <SaveStatusBadge save={nameSave} testId={`app-name-status-${id}`} />
        </div>
      </div>

      <div>
        <div className="flex items-center justify-between mb-2">
          <span className="text-xs font-semibold text-brand-text-muted uppercase tracking-wider">
            Icon
          </span>
          <SaveStatusBadge save={iconSave} testId={`app-icon-status-${id}`} />
        </div>
        <IconPicker
          value={{ icon, iconType }}
          onChange={(v: IconValue) => { setIcon(v.icon); setIconType(v.iconType) }}
          hosts={hosts}
        />
      </div>

      <div>
        <div className="flex items-center justify-between mb-2">
          <span className="text-xs font-semibold text-brand-text-muted uppercase tracking-wider">
            Hosts
          </span>
          <SaveStatusBadge save={hostsSave} testId={`app-hosts-status-${id}`} />
        </div>
        <div className="flex flex-wrap gap-2 mb-3 min-h-[2rem]" data-testid="hosts-chips">
          {hosts.length === 0 && (
            <span className="text-xs text-brand-text-muted italic">No hosts yet.</span>
          )}
          {hosts.map(h => (
            <span key={h} className="inline-flex items-center gap-1 bg-brand-alt text-brand-accent text-xs font-mono px-2.5 py-1 rounded-lg">
              {h}
              <button
                type="button"
                onClick={() => removeHost(h)}
                aria-label={`Remove ${h}`}
                className="text-brand-text-muted hover:text-red-700 transition-colors leading-none"
              >×</button>
            </span>
          ))}
        </div>
        <div className="flex gap-2">
          <input
            type="text"
            value={newHost}
            onChange={e => setNewHost(e.target.value)}
            onKeyDown={e => {
              if (e.key === 'Enter') {
                e.preventDefault()
                addHosts()
              }
            }}
            placeholder="example.com"
            title="Wildcards are unnecessary — example.com automatically covers every subdomain."
            className="flex-1 bg-brand-surface border border-brand-border-strong rounded-xl px-4 py-2 text-brand-ink font-mono text-sm focus:outline-none focus:border-brand-accent"
          />
          <button
            type="button"
            onClick={addHosts}
            className="px-4 py-2 rounded-xl bg-brand-alt text-brand-ink hover:bg-brand-alt text-sm font-medium"
          >Add</button>
        </div>
        <button
          type="button"
          onClick={() => setPickerOpen(true)}
          className="mt-2 text-xs font-medium text-brand-accent hover:text-brand-accent bg-brand-accent/10 px-3 py-1.5 rounded-lg"
        >Pick from recent activity</button>
      </div>

      {hasTemplate && (
        <button
          type="button"
          disabled
          title="Reset to template will be enabled once the starter library (#768) ships."
          className="w-full py-2 rounded-xl bg-brand-alt/60 text-brand-text-muted text-sm font-medium cursor-not-allowed"
        >
          Reset to template (requires #768)
        </button>
      )}

      <div className="border-t border-brand-border pt-4 space-y-3">
        {deleteError && (
          <div className="bg-red-500/10 border border-red-500/30 text-red-700 text-sm rounded-xl px-4 py-2">
            {deleteError}
          </div>
        )}
        <p className="text-xs text-brand-text-muted">
          Used by {assignedProfiles.length === 0
            ? <span className="text-brand-text">no profiles</span>
            : <span className="text-brand-text">{assignedProfiles.join(', ')}</span>}.
        </p>
        {!confirmingDelete
          ? (
              <button
                type="button"
                onClick={() => setConfirmingDelete(true)}
                className="text-xs text-red-700 hover:text-red-700 bg-red-500/10 px-3 py-1.5 rounded-lg transition-colors"
              >Delete app…</button>
            )
          : (
              <div className="bg-red-500/10 border border-red-500/30 rounded-xl p-3 space-y-3">
                {assignedProfiles.length > 0
                  ? (
                      <p className="text-sm text-red-800">
                        This app is currently assigned to{' '}
                        <strong>{assignedProfiles.length} profile{assignedProfiles.length === 1 ? '' : 's'}</strong>
                        {' '}({assignedProfiles.join(', ')}).
                        Deleting it will remove those assignments. Continue?
                      </p>
                    )
                  : (
                      <p className="text-sm text-red-800">
                        Delete <strong>{detail.app.name}</strong>? This cannot be undone.
                      </p>
                    )}
                <div className="flex gap-2">
                  <button
                    type="button"
                    onClick={() => setConfirmingDelete(false)}
                    disabled={deleting}
                    className="flex-1 py-2 rounded-lg bg-brand-alt text-brand-text text-sm font-medium disabled:opacity-50"
                  >Cancel</button>
                  <button
                    type="button"
                    onClick={performDelete}
                    disabled={deleting}
                    className="flex-1 py-2 rounded-lg bg-red-500 text-brand-ink text-sm font-semibold disabled:opacity-50"
                  >{deleting ? 'Deleting…' : 'Delete'}</button>
                </div>
              </div>
            )}
      </div>

      {pickerOpen && (
        <RecentApexPicker
          existingHosts={hosts}
          onClose={() => setPickerOpen(false)}
          onAdd={addFromPicker}
        />
      )}
    </div>
  )
}

// #1003 — per-field save status. "Unsaved" while a change is queued/in flight,
// "Saved just now" transiently after a successful write, "Save failed" + Retry
// on error. Mirrors the profile-subsection autosave indicators (#973).
function SaveStatusBadge({ save, testId }: {
  save: { status: SaveStatus; dirty: boolean; error: string | null; retry: () => Promise<void> }
  testId: string
}) {
  const { status, dirty, error, retry } = save
  if (status === 'error') {
    return (
      <span className="inline-flex items-center gap-2">
        <span data-testid={testId} data-status="error" title={error ?? ''} className="text-xs text-red-700">
          Save failed
        </span>
        <button
          type="button"
          onClick={() => { void retry() }}
          className="text-xs font-medium text-red-700 bg-red-500/10 px-2.5 py-1 rounded-lg hover:bg-red-500/20"
        >Retry</button>
      </span>
    )
  }
  if (status === 'saving') {
    return <span data-testid={testId} data-status="saving" className="text-xs text-brand-text-muted">Saving…</span>
  }
  if (status === 'saved') {
    return <span data-testid={testId} data-status="saved" className="text-xs text-brand-accent">Saved just now</span>
  }
  if (dirty) {
    return <span data-testid={testId} data-status="dirty" className="text-xs text-brand-text-muted">Unsaved</span>
  }
  return <span data-testid={testId} data-status="idle" className="text-xs text-transparent select-none">·</span>
}

function Field({ label, required, children }: {
  label: string
  required?: boolean
  children: React.ReactNode
}) {
  return (
    <div>
      <label className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2">
        {label} {required && <span className="text-red-700">*</span>}
      </label>
      {children}
    </div>
  )
}

function Modal({ title, children, onClose }: { title: string; children: React.ReactNode; onClose: () => void }) {
  useEscapeClose(onClose)
  return (
    <div className="fixed inset-0 bg-black/70 flex items-end sm:items-center justify-center z-50 p-4 overflow-y-auto" onClick={onClose}>
      <div
        className="bg-white rounded-2xl border border-brand-border-strong w-full max-w-lg my-8 p-6 space-y-5 max-h-[90vh] overflow-y-auto"
        onClick={e => e.stopPropagation()}
      >
        <h3 className="text-lg font-bold text-brand-ink">{title}</h3>
        {children}
      </div>
    </div>
  )
}
