import { useEffect, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { api } from '@/api/client'
import { useAlerts, useDevices, useHouseholdSettings, useProfiles, useInvalidators } from '@/api/queries'
import { useAuth } from '@/hooks/useAuth'
import { useEscapeClose } from '@/hooks/useEscapeClose'
import { useNotificationPermission } from '@/hooks/useNotifyOnNewAlerts'
import { useDebouncedSave, mergeSaveStatus } from '@/hooks/useDebouncedSave'
import { EmptyState } from '@/components/EmptyState'
import { ProfilePicker } from '@/components/ProfilePicker'
import { SaveStatusBadge } from '@/components/SaveStatusBadge'
import type { Alert, Device, PatchDeviceRequest, ProfileDetail } from '@/types/api'
import { PageLoader } from './DashboardPage'

// Apply the LogsPage click-through highlight (#298): when the URL carries
// `?mac=...`, scroll the matching device row into view and pulse a ring
// around it so the parent can spot which device they clicked from logs.
function useHighlightFromQuery(devices: Device[]) {
  const [params] = useSearchParams()
  const mac = params.get('mac')
  const [highlightMac, setHighlightMac] = useState<string | null>(null)
  useEffect(() => {
    if (!mac || devices.length === 0) return
    const exists = devices.some(d => d.mac === mac)
    if (!exists) return
    setHighlightMac(mac)
    const el = document.querySelector(`[data-testid="device-row-${mac}"]`) as HTMLElement | null
    el?.scrollIntoView?.({ block: 'center', behavior: 'smooth' })
    const t = setTimeout(() => setHighlightMac(null), 2000)
    return () => clearTimeout(t)
  }, [mac, devices])
  return highlightMac
}

// ── Devices page ───────────────────────────────────────────────────────────

export function DevicesPage() {
  // #2522: PUT /api/devices, PATCH/DELETE /api/devices/{mac} and both alert actions are
  // `requireWriter` — devices are parenting, not account management.
  const { isWriter } = useAuth()
  const devicesQuery  = useDevices()
  const profilesQuery = useProfiles()
  const householdQuery = useHouseholdSettings()
  const invalidators  = useInvalidators()
  const devices  = devicesQuery.data  ?? []
  const profiles = profilesQuery.data ?? []
  const unmanagedPolicy = householdQuery.data?.unmanagedMacPolicy
  const loading  = devicesQuery.isPending || profilesQuery.isPending
  // Modal is create-only now (#1000): "Add Device" and "Enroll" unmanaged.
  // Editing a known device happens inline on its row, autosaved per field.
  const [editing,  setEditing]  = useState<Device | null>(null)
  const [form,     setForm]     = useState({ mac: '', name: '', profileId: 0 })
  const [editingMac, setEditingMac] = useState<string | null>(null)
  // #2367 / #2607 — the modal's profile control is `ProfilePicker`, shared with
  // the row editor and the new-device alert editor. It owns the "+ New profile…"
  // branch and opens straight into the creator on a zero-profile household, so
  // the modal only tracks what it needs to freeze its OWN controls: `commitBlocked`
  // is the picker telling us it has no committable profile to hand over yet.
  const [commitBlocked, setCommitBlocked] = useState(false)
  // Mirrored from the creator so the modal freezes the controls IT owns while a
  // create is in flight — cancelling mid-flight would create a profile and then
  // discard it unassigned, with nothing on screen saying so.
  const [createPending, setCreatePending] = useState(false)
  // Escape is one of those controls: it closes the modal exactly like Cancel,
  // so it has to honour the same guard rather than route around it.
  useEscapeClose(() => setEditing(null), editing !== null && !createPending)
  const highlightMac = useHighlightFromQuery(devices)

  const upsertMutation = useMutation({
    mutationFn: (body: { mac: string; name: string; profileId: number }) =>
      api.devices.upsert(body),
    onSuccess: () => {
      setEditing(null)
      return invalidators.deviceMutated()
    },
  })

  const deleteMutation = useMutation({
    mutationFn: (mac: string) => api.devices.delete(mac),
    onSuccess: () => invalidators.deviceMutated(),
  })

  async function save() {
    await upsertMutation.mutateAsync(form)
  }

  async function del(mac: string) {
    if (!confirm('Remove this device?')) return
    await deleteMutation.mutateAsync(mac)
  }

  // Open the create/enroll modal. The picker mounts with the dialog and decides
  // for itself whether to open into the creator (#2607), so there is no
  // empty-household branch to keep in step here.
  function openCreate(mac: string) {
    setEditing({} as Device)
    // `0` is "nothing picked yet". The picker reconciles it to a real id as soon
    // as it mounts with a non-empty list (it owns that invariant now, #2366), and
    // Save refuses to submit a `0` in the meantime — seeding it here as well
    // would be a second place deciding the modal's default.
    setForm({ mac, name: '', profileId: 0 })
  }

  if (loading) return <PageLoader />

  const knownDevices   = devices.filter(d => d.profileId !== null)
  const unknownDevices = devices.filter(d => d.profileId === null)

  return (
    <div className="space-y-6">
      <NewDeviceAlertsBanner canEdit={isWriter} />

      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold text-brand-ink">Devices</h1>
        {isWriter && (
          <button
            onClick={() => openCreate('')}
            className="bg-brand-accent hover:bg-brand-accent-dark text-white text-sm font-semibold px-4 py-2 rounded-xl transition-colors"
          >
            + Add Device
          </button>
        )}
      </div>

      <div className="bg-white rounded-2xl border border-brand-border overflow-hidden">
        {knownDevices.length === 0
          ? <EmptyState title="No devices yet." />
          : knownDevices.map(d => (
              <div key={d.mac} data-testid={`device-row-${d.mac}`} className={`px-5 py-4 border-b border-brand-border last:border-0 transition-shadow ${highlightMac === d.mac ? 'ring-2 ring-brand-accent/60 ring-inset' : ''}`}>
                <div className="flex items-center gap-4">
                  <Link to={`/devices/${encodeURIComponent(d.mac)}/timeline`} className="flex-1 min-w-0 hover:text-brand-accent transition-colors" data-testid={`device-timeline-link-${d.mac}`}>
                    <p className="font-medium text-brand-ink truncate">{d.name}</p>
                    <p className="text-xs text-brand-text-muted font-mono">{d.mac}</p>
                  </Link>
                  <div className="hidden sm:block text-sm">
                    <span className="bg-brand-accent/10 text-brand-accent border border-brand-accent/20 px-2 py-1 rounded-lg text-xs">
                      {d.profileName ?? 'No profile'}
                    </span>
                  </div>
                  {isWriter && (
                    <div className="flex gap-2 shrink-0">
                      <button
                        onClick={() => setEditingMac(m => (m === d.mac ? null : d.mac))}
                        aria-expanded={editingMac === d.mac}
                        className="text-xs text-brand-text hover:text-brand-ink bg-brand-alt px-3 py-1.5 rounded-lg transition-colors"
                      >{editingMac === d.mac ? 'Done' : 'Edit'}</button>
                      <button
                        onClick={() => del(d.mac)}
                        className="text-xs text-red-700 hover:text-red-700 bg-red-500/10 px-3 py-1.5 rounded-lg transition-colors"
                      >Remove</button>
                    </div>
                  )}
                </div>
                {isWriter && editingMac === d.mac && (
                  <DeviceRowEditor
                    device={d}
                    profiles={profiles}
                    profilesLoading={profilesQuery.isPending}
                    profilesError={profilesQuery.isError}
                    onClose={() => setEditingMac(null)}
                  />
                )}
              </div>
            ))
        }
      </div>

      {unknownDevices.length > 0 && (
        <div data-testid="unmanaged-devices-section">
          <h2 className="text-sm font-semibold text-brand-text uppercase tracking-wider mb-3">
            Unmanaged Devices
            <span
              className="ml-2 text-xs text-brand-text-muted normal-case font-normal"
              data-testid="unmanaged-devices-policy-hint"
            >
              {unmanagedPolicy?.policy === 'allow'
                ? 'seen on the network, no profile assigned — household policy allows them.'
                : 'seen on the network, no profile assigned — blocked by household policy.'}
            </span>
          </h2>
          <div className="bg-white rounded-2xl border border-brand-border overflow-hidden">
            {unknownDevices.map(d => (
              <div key={d.mac} data-testid={`device-row-${d.mac}`} className={`flex items-center gap-4 px-5 py-4 border-b border-brand-border last:border-0 transition-shadow ${highlightMac === d.mac ? 'ring-2 ring-brand-accent/60 ring-inset' : ''}`}>
                <div className="flex-1 min-w-0">
                  <p className="font-medium text-brand-text truncate">{d.name}</p>
                  <p className="text-xs text-brand-text-muted font-mono">{d.mac}</p>
                  {d.lastSeenIp && (
                    <p className="text-xs text-brand-text-muted font-mono">{d.lastSeenIp}</p>
                  )}
                  {d.lastSeenAt && (
                    <p
                      className="text-xs text-brand-text-muted"
                      data-testid={`device-last-seen-${d.mac}`}
                    >
                      last seen {new Date(d.lastSeenAt).toLocaleString()}
                    </p>
                  )}
                </div>
                <div className="hidden sm:block text-sm">
                  <span className={`px-2 py-1 rounded-lg text-xs border ${
                    unmanagedPolicy?.policy === 'allow'
                      ? 'bg-amber-500/10 text-amber-700 border-amber-500/20'
                      : 'bg-red-500/10 text-red-700 border-red-500/20'
                  }`}>
                    {unmanagedPolicy?.policy === 'allow' ? 'No profile' : 'Unmanaged'}
                  </span>
                </div>
                {isWriter && (
                  <button
                    onClick={() => openCreate(d.mac)}
                    data-testid={`unmanaged-enroll-${d.mac}`}
                    className="text-xs text-brand-accent hover:text-brand-accent bg-brand-accent/10 px-3 py-1.5 rounded-lg transition-colors shrink-0"
                  >Enroll</button>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {editing && (
        <div className="fixed inset-0 bg-black/70 flex items-end sm:items-center justify-center z-50 p-4">
          <div className="bg-white rounded-2xl border border-brand-border-strong w-full max-w-sm p-6 space-y-4">
            <h3 className="text-lg font-bold text-brand-ink">{form.mac ? 'Enroll Device' : 'Add Device'}</h3>
            <Field label="MAC Address" value={form.mac} onChange={v => setForm(f => ({...f, mac: v}))} placeholder="aa:bb:cc:dd:ee:ff" mono />
            <Field label="Name" value={form.name} onChange={v => setForm(f => ({...f, name: v}))} placeholder="Kid's iPad" />
            <ProfilePicker
              profiles={profiles}
              isLoading={profilesQuery.isPending}
              isError={profilesQuery.isError}
              // A device added through this dialog is always assigned; `0` is
              // "nothing picked yet", not a state the operator can choose.
              allowNone={false}
              value={form.profileId || null}
              onChange={id => setForm(f => ({ ...f, profileId: id ?? 0 }))}
              selectTestId="add-device-profile-select"
              testIdPrefix="add-device"
              onCommitBlockedChange={setCommitBlocked}
              onPendingChange={setCreatePending}
            />
            <div className="flex gap-3 pt-2">
              {/* Closing mid-create would create the profile and then discard
                  it unassigned, with nothing on screen saying so. */}
              <button onClick={() => setEditing(null)} disabled={createPending} data-testid="add-device-cancel" className="flex-1 py-3 rounded-xl bg-brand-alt text-brand-text font-medium disabled:opacity-60">Cancel</button>
              <button onClick={save} disabled={commitBlocked || form.profileId === 0} className="flex-1 py-3 rounded-xl bg-brand-accent text-white font-semibold disabled:opacity-60">Save</button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

// #1000 — inline, per-field autosave editor for a known device. Name and
// profile each debounce-PATCH /devices/:mac independently; the row's status
// badge aggregates both fields and offers Retry on failure. No Save button.
function DeviceRowEditor({
  device, profiles, profilesLoading, profilesError, onClose,
}: {
  device: Device
  profiles: ProfileDetail[]
  profilesLoading: boolean
  profilesError: boolean
  onClose: () => void
}) {
  const invalidators = useInvalidators()
  const [name, setName] = useState(device.name)
  const [profileId, setProfileId] = useState<number | null>(device.profileId)
  // #2560 — the row's own "+ New profile…" branch, now via the shared picker
  // (#2607). Without it the row could only assign a profile that already
  // existed, so putting a device on a NEW profile meant leaving /devices. The
  // picker raises `commitBlocked` while it has nothing committable to hand over.
  const [commitBlocked, setCommitBlocked] = useState(false)
  useEffect(() => { setName(device.name) }, [device.name])
  useEffect(() => { setProfileId(device.profileId) }, [device.profileId])

  const nameSave = useDebouncedSave(
    name,
    async (next: string) => {
      const trimmed = next.trim()
      if (!trimmed) throw new Error('Name is required')
      await api.devices.patch(device.mac, { name: trimmed })
      await invalidators.deviceMutated()
    },
    { key: device.mac },
  )

  const profileSave = useDebouncedSave(
    profileId,
    async (next: number | null) => {
      await api.devices.patch(device.mac, { profileId: next })
      await invalidators.deviceMutated()
    },
    { key: device.mac },
  )

  const merged = mergeSaveStatus([nameSave, profileSave])

  return (
    <div
      data-testid={`device-editor-${device.mac}`}
      className="mt-3 pt-3 border-t border-brand-border flex flex-wrap items-end gap-3"
    >
      <div className="flex-1 min-w-[12rem]">
        <label className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-1">Name</label>
        <input
          type="text"
          value={name}
          onChange={e => setName(e.target.value)}
          data-testid={`device-name-input-${device.mac}`}
          className="w-full bg-brand-surface border border-brand-border-strong rounded-xl px-4 py-2.5 text-brand-ink focus:outline-none focus:border-brand-accent"
        />
      </div>
      {/* The creator needs the full row width; the select alone does not. On this
          surface `commitBlocked` and "the creator is open" coincide: the row
          always allows null, and it is only ever reached with at least one
          profile (`knownDevices` filters `profileId !== null`), so the creator
          here is always one the operator picked. */}
      <div className={commitBlocked ? 'basis-full order-last' : 'min-w-[10rem]'}>
        <ProfilePicker
          profiles={profiles}
          isLoading={profilesLoading}
          isError={profilesError}
          allowNone
          dense
          value={profileId}
          // Assigning through the same state the <select> writes means the row's
          // existing debounced PATCH {profileId} carries it — no second save path.
          onChange={setProfileId}
          selectTestId={`device-profile-select-${device.mac}`}
          testIdPrefix={`device-${device.mac}`}
          onCommitBlockedChange={setCommitBlocked}
        />
      </div>
      <div className="flex items-center gap-3 pb-2.5">
        <SaveStatusBadge
          status={merged.status}
          error={merged.error}
          testId={`device-save-status-${device.mac}`}
          onRetry={merged.retry}
        />
        <button
          type="button"
          onClick={onClose}
          // Closing while the picker has nothing to hand over would abandon the
          // creator mid-flow, and closing mid-request would leave the profile
          // created but never assigned, with nothing on screen saying so. (The
          // outer Edit/Done toggle stays enabled either way, so this is never a
          // trap.)
          disabled={commitBlocked}
          className="text-xs text-brand-text hover:text-brand-ink bg-brand-alt px-3 py-1.5 rounded-lg transition-colors disabled:opacity-60"
        >Done</button>
      </div>
    </div>
  )
}

// ── New-device alerts banner (#711, now backed by unified /api/alerts) ─────
//
// Shown above the device list whenever pending new-device alerts exist.
// #2522: writers (admin or adult) can approve each one inline — both alert actions are
// `requireWriter`. (Formerly "dismiss" — the unified
// alert model uses approve/deny across all kinds; new_device approval has
// no side effect). The banner refetches on a 30 s interval (see useAlerts)
// so a freshly-connected device shows up without a manual reload.

function NewDeviceAlertsBanner({ canEdit }: { canEdit: boolean }) {
  const alertsQuery  = useAlerts()
  const profilesQuery = useProfiles()
  const invalidators = useInvalidators()
  const alerts: Alert[] = (alertsQuery.data ?? []).filter(a => a.kind === 'new_device')
  const profiles = profilesQuery.data ?? []
  const notificationPermission = useNotificationPermission()
  const [editing, setEditing] = useState<Alert | null>(null)

  const approveMutation = useMutation({
    mutationFn: (id: number) => api.alerts.approve(id),
    onSuccess: () => invalidators.alerts(),
  })

  const denyMutation = useMutation({
    mutationFn: (id: number) => api.alerts.deny(id),
    onSuccess: () => invalidators.alerts(),
  })

  if (alerts.length === 0) return null

  return (
    <div data-testid="new-device-alerts-banner" className="bg-amber-500/5 border border-amber-500/30 rounded-2xl p-5 space-y-3">
      <div className="flex items-center gap-3">
        <span className="text-amber-700 text-lg">●</span>
        <h2 className="text-amber-800 font-semibold flex-1">
          {alerts.length === 1
            ? '1 new device on the network'
            : `${alerts.length} new devices on the network`}
        </h2>
        {notificationPermission.state === 'default' && (
          <button
            data-testid="enable-notifications-btn"
            onClick={() => notificationPermission.request()}
            className="text-xs text-amber-800 hover:text-brand-ink bg-amber-500/10 hover:bg-amber-500/20 border border-amber-500/30 px-3 py-1.5 rounded-lg transition-colors shrink-0"
          >
            Enable browser notifications
          </button>
        )}
      </div>
      <ul className="space-y-2">
        {alerts.map(a => (
          <li
            key={a.id}
            data-testid={`new-device-alert-${a.mac}`}
            className="flex items-center gap-3 text-sm bg-white/60 rounded-lg px-3 py-2"
          >
            {canEdit ? (
              <button
                type="button"
                onClick={() => setEditing(a)}
                data-testid={`new-device-alert-row-${a.mac}`}
                className="flex-1 min-w-0 text-left hover:text-brand-accent transition-colors"
              >
                <p className="text-brand-ink truncate">{a.deviceName ?? a.mac}</p>
                <p className="text-xs text-brand-text-muted font-mono">{a.mac}</p>
                <p className="text-xs text-brand-text-muted">first seen {new Date(a.createdAt).toLocaleString()}</p>
              </button>
            ) : (
              <div className="flex-1 min-w-0">
                <p className="text-brand-ink truncate">{a.deviceName ?? a.mac}</p>
                <p className="text-xs text-brand-text-muted font-mono">{a.mac}</p>
                <p className="text-xs text-brand-text-muted">first seen {new Date(a.createdAt).toLocaleString()}</p>
              </div>
            )}
            {canEdit && (
              <button
                onClick={() => approveMutation.mutate(a.id)}
                disabled={approveMutation.isPending}
                className="text-xs text-brand-text hover:text-brand-ink bg-brand-alt hover:bg-brand-alt px-3 py-1.5 rounded-lg transition-colors shrink-0"
              >
                Dismiss
              </button>
            )}
          </li>
        ))}
      </ul>
      {editing && (
        <NewDeviceAlertEditor
          alert={editing}
          profiles={profiles}
          profilesLoading={profilesQuery.isPending}
          profilesError={profilesQuery.isError}
          onClose={() => setEditing(null)}
          onSaved={async ({ finalProfileId }) => {
            // A profile means the operator has decided this device belongs on
            // the network — approve. No profile means they saw it and chose
            // not to enroll it — deny so it doesn't sit in the pending feed.
            if (finalProfileId !== null) {
              await approveMutation.mutateAsync(editing.id)
            } else {
              await denyMutation.mutateAsync(editing.id)
            }
            setEditing(null)
            await invalidators.deviceMutated()
          }}
        />
      )}
    </div>
  )
}

function NewDeviceAlertEditor({
  alert, profiles, profilesLoading, profilesError, onClose, onSaved,
}: {
  alert: Alert
  profiles: ProfileDetail[]
  profilesLoading: boolean
  profilesError: boolean
  onClose: () => void
  onSaved: (args: { finalProfileId: number | null }) => Promise<void>
}) {
  const [name, setName] = useState(alert.deviceName ?? '')
  // null = leave unassigned (per #841, profileId is optional).
  const [profileId, setProfileId] = useState<number | null>(alert.profileId)
  const [error, setError] = useState<string | null>(null)
  // #2607 — this is the natural onboarding entry point (a device appears, the
  // operator clicks the alert), so it is the surface that most needed to be able
  // to create the profile it is assigning. Both flags come from the shared
  // picker. `commitBlocked` freezes Save only when the picker has nothing
  // committable to hand over — an auto-opened creator on an empty household does
  // NOT block it, because saving with no profile is itself a decision here (it
  // denies the alert). `createPending` freezes Escape/Cancel while a create is in
  // flight: closing then would leave the profile created server-side and never
  // assigned, with nothing on screen saying so.
  const [commitBlocked, setCommitBlocked] = useState(false)
  const [createPending, setCreatePending] = useState(false)
  useEscapeClose(onClose, !createPending)

  const patchMutation = useMutation({
    mutationFn: (data: PatchDeviceRequest) => api.devices.patch(alert.mac, data),
  })

  async function save() {
    setError(null)
    const patch: PatchDeviceRequest = {}
    const trimmed = name.trim()
    if (trimmed && trimmed !== alert.deviceName) patch.name = trimmed
    if (profileId !== alert.profileId) patch.profileId = profileId
    try {
      if (Object.keys(patch).length > 0) {
        await patchMutation.mutateAsync(patch)
      }
      await onSaved({ finalProfileId: profileId })
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Save failed')
    }
  }

  return (
    <div className="fixed inset-0 bg-black/70 flex items-end sm:items-center justify-center z-50 p-4">
      <div
        data-testid="new-device-alert-editor"
        className="bg-white rounded-2xl border border-brand-border-strong w-full max-w-sm p-6 space-y-4"
      >
        <h3 className="text-lg font-bold text-brand-ink">New device</h3>
        <div>
          <label className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2">MAC Address</label>
          <p className="text-sm font-mono text-brand-text bg-brand-surface border border-brand-border rounded-xl px-4 py-3">
            {alert.mac}
          </p>
        </div>
        <Field label="Name" value={name} onChange={setName} placeholder="Kid's iPad" />
        <ProfilePicker
          profiles={profiles}
          isLoading={profilesLoading}
          isError={profilesError}
          // Saving with no profile is a real decision here — it denies the alert.
          allowNone
          value={profileId}
          onChange={setProfileId}
          selectTestId="new-device-alert-profile"
          testIdPrefix="new-device-alert"
          onCommitBlockedChange={setCommitBlocked}
          onPendingChange={setCreatePending}
        />
        {error && (
          <p data-testid="new-device-alert-editor-error" className="text-sm text-red-700">{error}</p>
        )}
        <div className="flex gap-3 pt-2">
          <button
            onClick={onClose}
            disabled={createPending}
            data-testid="new-device-alert-close"
            className="flex-1 py-3 rounded-xl bg-brand-alt text-brand-text font-medium disabled:opacity-60"
          >Cancel</button>
          <button
            onClick={save}
            disabled={patchMutation.isPending || commitBlocked}
            data-testid="new-device-alert-save"
            className="flex-1 py-3 rounded-xl bg-brand-accent text-white font-semibold disabled:opacity-60"
          >Save</button>
        </div>
      </div>
    </div>
  )
}

// ── Shared helpers ─────────────────────────────────────────────────────────

function Field({ label, value, onChange, placeholder, mono = false }: {
  label: string; value: string; onChange: (v: string) => void; placeholder?: string; mono?: boolean
}) {
  return (
    <div>
      <label className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2">{label}</label>
      <input type="text" value={value} onChange={e => onChange(e.target.value)} placeholder={placeholder}
        className={`w-full bg-brand-surface border border-brand-border-strong rounded-xl px-4 py-3 text-brand-ink placeholder-brand-text-muted focus:outline-none focus:border-brand-accent ${mono ? 'font-mono text-sm' : ''}`} />
    </div>
  )
}
