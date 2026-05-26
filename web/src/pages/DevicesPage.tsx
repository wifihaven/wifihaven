import { useEffect, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { api } from '@/api/client'
import { useAlerts, useDevices, useHouseholdSettings, useProfiles, useInvalidators } from '@/api/queries'
import { useAuth } from '@/hooks/useAuth'
import { useEscapeClose } from '@/hooks/useEscapeClose'
import { useNotificationPermission } from '@/hooks/useNotifyOnNewAlerts'
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
  const { isAdmin } = useAuth()
  const devicesQuery  = useDevices()
  const profilesQuery = useProfiles()
  const householdQuery = useHouseholdSettings()
  const invalidators  = useInvalidators()
  const devices  = devicesQuery.data  ?? []
  const profiles = profilesQuery.data ?? []
  const unmanagedPolicy = householdQuery.data?.unmanagedMacPolicy
  const loading  = devicesQuery.isPending || profilesQuery.isPending
  const [editing,  setEditing]  = useState<Device | null>(null)
  useEscapeClose(() => setEditing(null), editing !== null)
  const [form,     setForm]     = useState({ mac: '', name: '', profileId: 0 })
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

  function addUnknown(mac: string) {
    setEditing({} as Device)
    setForm({ mac, name: '', profileId: profiles[0]?.profile.id ?? 0 })
  }

  if (loading) return <PageLoader />

  const knownDevices   = devices.filter(d => d.profileId !== null)
  const unknownDevices = devices.filter(d => d.profileId === null)

  return (
    <div className="space-y-6">
      <NewDeviceAlertsBanner isAdmin={isAdmin} />

      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold text-white">Devices</h1>
        {isAdmin && (
          <button
            onClick={() => { setEditing({} as Device); setForm({ mac: '', name: '', profileId: profiles[0]?.profile.id ?? 0 }) }}
            className="bg-emerald-500 hover:bg-emerald-400 text-black text-sm font-semibold px-4 py-2 rounded-xl transition-colors"
          >
            + Add Device
          </button>
        )}
      </div>

      <div className="bg-gray-900 rounded-2xl border border-gray-800 overflow-hidden">
        {knownDevices.length === 0
          ? <p className="p-6 text-gray-500 text-sm">No devices yet.</p>
          : knownDevices.map(d => (
              <div key={d.mac} data-testid={`device-row-${d.mac}`} className={`flex items-center gap-4 px-5 py-4 border-b border-gray-800 last:border-0 transition-shadow ${highlightMac === d.mac ? 'ring-2 ring-emerald-500/60 ring-inset' : ''}`}>
                <Link to={`/devices/${encodeURIComponent(d.mac)}/timeline`} className="flex-1 min-w-0 hover:text-emerald-400 transition-colors" data-testid={`device-timeline-link-${d.mac}`}>
                  <p className="font-medium text-white truncate">{d.name}</p>
                  <p className="text-xs text-gray-500 font-mono">{d.mac}</p>
                </Link>
                <div className="hidden sm:block text-sm">
                  <span className="bg-emerald-500/10 text-emerald-400 border border-emerald-500/20 px-2 py-1 rounded-lg text-xs">
                    {d.profileName ?? 'No profile'}
                  </span>
                </div>
                {isAdmin && (
                  <div className="flex gap-2 shrink-0">
                    <button
                      onClick={() => { setEditing(d); setForm({ mac: d.mac, name: d.name, profileId: d.profileId ?? profiles[0]?.profile.id ?? 0 }) }}
                      className="text-xs text-gray-400 hover:text-white bg-gray-800 px-3 py-1.5 rounded-lg transition-colors"
                    >Edit</button>
                    <button
                      onClick={() => del(d.mac)}
                      className="text-xs text-red-400 hover:text-red-300 bg-red-500/10 px-3 py-1.5 rounded-lg transition-colors"
                    >Remove</button>
                  </div>
                )}
              </div>
            ))
        }
      </div>

      {unknownDevices.length > 0 && (
        <div data-testid="unmanaged-devices-section">
          <h2 className="text-sm font-semibold text-gray-400 uppercase tracking-wider mb-3">
            Unmanaged Devices
            <span
              className="ml-2 text-xs text-gray-600 normal-case font-normal"
              data-testid="unmanaged-devices-policy-hint"
            >
              {unmanagedPolicy?.policy === 'allow'
                ? 'seen on the network, no profile assigned — household policy allows them.'
                : 'seen on the network, no profile assigned — blocked by household policy.'}
            </span>
          </h2>
          <div className="bg-gray-900 rounded-2xl border border-gray-800 overflow-hidden">
            {unknownDevices.map(d => (
              <div key={d.mac} data-testid={`device-row-${d.mac}`} className={`flex items-center gap-4 px-5 py-4 border-b border-gray-800 last:border-0 transition-shadow ${highlightMac === d.mac ? 'ring-2 ring-emerald-500/60 ring-inset' : ''}`}>
                <div className="flex-1 min-w-0">
                  <p className="font-medium text-gray-400 truncate">{d.name}</p>
                  <p className="text-xs text-gray-500 font-mono">{d.mac}</p>
                  {d.lastSeenIp && (
                    <p className="text-xs text-gray-600 font-mono">{d.lastSeenIp}</p>
                  )}
                  {d.lastSeenAt && (
                    <p
                      className="text-xs text-gray-600"
                      data-testid={`device-last-seen-${d.mac}`}
                    >
                      last seen {new Date(d.lastSeenAt).toLocaleString()}
                    </p>
                  )}
                </div>
                <div className="hidden sm:block text-sm">
                  <span className={`px-2 py-1 rounded-lg text-xs border ${
                    unmanagedPolicy?.policy === 'allow'
                      ? 'bg-yellow-500/10 text-yellow-400 border-yellow-500/20'
                      : 'bg-red-500/10 text-red-300 border-red-500/20'
                  }`}>
                    {unmanagedPolicy?.policy === 'allow' ? 'No profile' : 'Unmanaged'}
                  </span>
                </div>
                {isAdmin && (
                  <button
                    onClick={() => addUnknown(d.mac)}
                    data-testid={`unmanaged-enroll-${d.mac}`}
                    className="text-xs text-emerald-400 hover:text-emerald-300 bg-emerald-500/10 px-3 py-1.5 rounded-lg transition-colors shrink-0"
                  >Enroll</button>
                )}
              </div>
            ))}
          </div>
        </div>
      )}

      {editing && (
        <div className="fixed inset-0 bg-black/70 flex items-end sm:items-center justify-center z-50 p-4">
          <div className="bg-gray-900 rounded-2xl border border-gray-700 w-full max-w-sm p-6 space-y-4">
            <h3 className="text-lg font-bold text-white">{form.mac ? 'Edit Device' : 'Add Device'}</h3>
            <Field label="MAC Address" value={form.mac} onChange={v => setForm(f => ({...f, mac: v}))} placeholder="aa:bb:cc:dd:ee:ff" mono />
            <Field label="Name" value={form.name} onChange={v => setForm(f => ({...f, name: v}))} placeholder="Kid's iPad" />
            <div>
              <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">Profile</label>
              <select value={form.profileId} onChange={e => setForm(f => ({...f, profileId: Number(e.target.value)}))}
                className="w-full bg-gray-950 border border-gray-700 rounded-xl px-4 py-3 text-white">
                {profiles.map(p => <option key={p.profile.id} value={p.profile.id}>{p.profile.name}</option>)}
              </select>
            </div>
            <div className="flex gap-3 pt-2">
              <button onClick={() => setEditing(null)} className="flex-1 py-3 rounded-xl bg-gray-800 text-gray-300 font-medium">Cancel</button>
              <button onClick={save} className="flex-1 py-3 rounded-xl bg-emerald-500 text-black font-semibold">Save</button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

// ── New-device alerts banner (#711, now backed by unified /api/alerts) ─────
//
// Shown above the device list whenever pending new-device alerts exist.
// Admins can approve each one inline (formerly "dismiss" — the unified
// alert model uses approve/deny across all kinds; new_device approval has
// no side effect). The banner refetches on a 30 s interval (see useAlerts)
// so a freshly-connected device shows up without a manual reload.

function NewDeviceAlertsBanner({ isAdmin }: { isAdmin: boolean }) {
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

  if (alerts.length === 0) return null

  return (
    <div data-testid="new-device-alerts-banner" className="bg-yellow-500/5 border border-yellow-500/30 rounded-2xl p-5 space-y-3">
      <div className="flex items-center gap-3">
        <span className="text-yellow-400 text-lg">●</span>
        <h2 className="text-yellow-200 font-semibold flex-1">
          {alerts.length === 1
            ? '1 new device on the network'
            : `${alerts.length} new devices on the network`}
        </h2>
        {notificationPermission.state === 'default' && (
          <button
            data-testid="enable-notifications-btn"
            onClick={() => notificationPermission.request()}
            className="text-xs text-yellow-200 hover:text-white bg-yellow-500/10 hover:bg-yellow-500/20 border border-yellow-500/30 px-3 py-1.5 rounded-lg transition-colors shrink-0"
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
            className="flex items-center gap-3 text-sm bg-gray-900/60 rounded-lg px-3 py-2"
          >
            {isAdmin ? (
              <button
                type="button"
                onClick={() => setEditing(a)}
                data-testid={`new-device-alert-row-${a.mac}`}
                className="flex-1 min-w-0 text-left hover:text-emerald-400 transition-colors"
              >
                <p className="text-white truncate">{a.deviceName ?? a.mac}</p>
                <p className="text-xs text-gray-500 font-mono">{a.mac}</p>
                <p className="text-xs text-gray-600">first seen {new Date(a.createdAt).toLocaleString()}</p>
              </button>
            ) : (
              <div className="flex-1 min-w-0">
                <p className="text-white truncate">{a.deviceName ?? a.mac}</p>
                <p className="text-xs text-gray-500 font-mono">{a.mac}</p>
                <p className="text-xs text-gray-600">first seen {new Date(a.createdAt).toLocaleString()}</p>
              </div>
            )}
            {isAdmin && (
              <button
                onClick={() => approveMutation.mutate(a.id)}
                disabled={approveMutation.isPending}
                className="text-xs text-gray-300 hover:text-white bg-gray-800 hover:bg-gray-700 px-3 py-1.5 rounded-lg transition-colors shrink-0"
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
          onClose={() => setEditing(null)}
          onSaved={async () => {
            await approveMutation.mutateAsync(editing.id)
            setEditing(null)
            await invalidators.deviceMutated()
          }}
        />
      )}
    </div>
  )
}

function NewDeviceAlertEditor({
  alert, profiles, onClose, onSaved,
}: {
  alert: Alert
  profiles: ProfileDetail[]
  onClose: () => void
  onSaved: () => Promise<void>
}) {
  const [name, setName] = useState(alert.deviceName ?? '')
  // null = leave unassigned (per #841, profileId is optional).
  const [profileId, setProfileId] = useState<number | null>(alert.profileId)
  const [error, setError] = useState<string | null>(null)
  useEscapeClose(onClose, true)

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
      await onSaved()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Save failed')
    }
  }

  return (
    <div className="fixed inset-0 bg-black/70 flex items-end sm:items-center justify-center z-50 p-4">
      <div
        data-testid="new-device-alert-editor"
        className="bg-gray-900 rounded-2xl border border-gray-700 w-full max-w-sm p-6 space-y-4"
      >
        <h3 className="text-lg font-bold text-white">New device</h3>
        <div>
          <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">MAC Address</label>
          <p className="text-sm font-mono text-gray-300 bg-gray-950 border border-gray-800 rounded-xl px-4 py-3">
            {alert.mac}
          </p>
        </div>
        <Field label="Name" value={name} onChange={setName} placeholder="Kid's iPad" />
        <div>
          <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">Profile</label>
          <select
            data-testid="new-device-alert-profile"
            value={profileId ?? ''}
            onChange={e => setProfileId(e.target.value === '' ? null : Number(e.target.value))}
            className="w-full bg-gray-950 border border-gray-700 rounded-xl px-4 py-3 text-white"
          >
            <option value="">— No profile —</option>
            {profiles.map(p => (
              <option key={p.profile.id} value={p.profile.id}>{p.profile.name}</option>
            ))}
          </select>
        </div>
        {error && (
          <p data-testid="new-device-alert-editor-error" className="text-sm text-red-400">{error}</p>
        )}
        <div className="flex gap-3 pt-2">
          <button
            onClick={onClose}
            className="flex-1 py-3 rounded-xl bg-gray-800 text-gray-300 font-medium"
          >Cancel</button>
          <button
            onClick={save}
            disabled={patchMutation.isPending}
            className="flex-1 py-3 rounded-xl bg-emerald-500 text-black font-semibold disabled:opacity-60"
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
      <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">{label}</label>
      <input type="text" value={value} onChange={e => onChange(e.target.value)} placeholder={placeholder}
        className={`w-full bg-gray-950 border border-gray-700 rounded-xl px-4 py-3 text-white placeholder-gray-600 focus:outline-none focus:border-emerald-500 ${mono ? 'font-mono text-sm' : ''}`} />
    </div>
  )
}
