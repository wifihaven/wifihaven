import { useEffect, useState } from 'react'
import { api } from '@/api/client'
import { useAuth } from '@/hooks/useAuth'
import type { Device, ProfileDetail } from '@/types/api'
import { PageLoader } from './DashboardPage'

// ── Devices page ───────────────────────────────────────────────────────────

export function DevicesPage() {
  const { isAdmin } = useAuth()
  const [devices,  setDevices]  = useState<Device[]>([])
  const [profiles, setProfiles] = useState<ProfileDetail[]>([])
  const [loading,  setLoading]  = useState(true)
  const [editing,  setEditing]  = useState<Device | null>(null)
  const [form,     setForm]     = useState({ mac: '', name: '', profileId: 0 })

  useEffect(() => {
    Promise.all([api.devices.list(), api.profiles.list()])
      .then(([d, p]) => { setDevices(d); setProfiles(p) })
      .finally(() => setLoading(false))
  }, [])

  async function save() {
    await api.devices.upsert(form)
    const d = await api.devices.list()
    setDevices(d)
    setEditing(null)
  }

  async function del(mac: string) {
    if (!confirm('Remove this device?')) return
    await api.devices.delete(mac)
    setDevices(d => d.filter(x => x.mac !== mac))
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
              <div key={d.mac} className="flex items-center gap-4 px-5 py-4 border-b border-gray-800 last:border-0">
                <div className="flex-1 min-w-0">
                  <p className="font-medium text-white truncate">{d.name}</p>
                  <p className="text-xs text-gray-500 font-mono">{d.mac}</p>
                </div>
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
        <div>
          <h2 className="text-sm font-semibold text-gray-400 uppercase tracking-wider mb-3">
            Unknown Devices
            <span className="ml-2 text-xs text-gray-600 normal-case font-normal">seen on the network, no profile assigned — traffic is allowed</span>
          </h2>
          <div className="bg-gray-900 rounded-2xl border border-gray-800 overflow-hidden">
            {unknownDevices.map(d => (
              <div key={d.mac} className="flex items-center gap-4 px-5 py-4 border-b border-gray-800 last:border-0">
                <div className="flex-1 min-w-0">
                  <p className="font-medium text-gray-400 truncate">{d.name}</p>
                  <p className="text-xs text-gray-500 font-mono">{d.mac}</p>
                  {d.lastSeenIp && (
                    <p className="text-xs text-gray-600 font-mono">{d.lastSeenIp}</p>
                  )}
                </div>
                <div className="hidden sm:block text-sm">
                  <span className="bg-yellow-500/10 text-yellow-400 border border-yellow-500/20 px-2 py-1 rounded-lg text-xs">
                    No profile
                  </span>
                </div>
                {isAdmin && (
                  <button
                    onClick={() => addUnknown(d.mac)}
                    className="text-xs text-emerald-400 hover:text-emerald-300 bg-emerald-500/10 px-3 py-1.5 rounded-lg transition-colors shrink-0"
                  >Add as device</button>
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

// ── Logs page ──────────────────────────────────────────────────────────────

export function LogsPage() {
  const [logs,     setLogs]     = useState<import('@/types/api').QueryLog[]>([])
  const [loading,  setLoading]  = useState(true)
  const [domain,   setDomain]   = useState('')
  const [blocked,  setBlocked]  = useState<'all' | 'true' | 'false'>('all')

  async function load() {
    setLoading(true)
    try {
      const data = await api.logs.query({
        domain:  domain || undefined,
        blocked: blocked === 'all' ? undefined : blocked === 'true',
        limit:   200,
      })
      setLogs(data)
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { load() }, [domain, blocked])

  return (
    <div className="space-y-6">
      <h1 className="text-xl font-bold text-white">Query Log</h1>

      <div className="flex flex-wrap gap-3">
        <input
          type="text"
          value={domain}
          onChange={e => setDomain(e.target.value)}
          placeholder="Filter by domain…"
          className="bg-gray-900 border border-gray-700 rounded-xl px-4 py-2.5 text-white text-sm font-mono placeholder-gray-600 focus:outline-none focus:border-emerald-500 flex-1 min-w-[160px]"
        />
        <select value={blocked} onChange={e => setBlocked(e.target.value as typeof blocked)}
          className="bg-gray-900 border border-gray-700 rounded-xl px-4 py-2.5 text-white text-sm">
          <option value="all">All queries</option>
          <option value="true">Blocked only</option>
          <option value="false">Allowed only</option>
        </select>
        <button onClick={load} className="bg-gray-800 hover:bg-gray-700 text-white text-sm px-4 py-2.5 rounded-xl transition-colors">
          Refresh
        </button>
      </div>

      <div className="bg-gray-900 rounded-2xl border border-gray-800 overflow-hidden">
        {loading
          ? <div className="p-8 flex justify-center"><div className="w-6 h-6 border-2 border-emerald-500 border-t-transparent rounded-full animate-spin"/></div>
          : <div className="overflow-x-auto">
              <table className="w-full text-xs font-mono">
                <thead>
                  <tr className="text-gray-600 border-b border-gray-800">
                    <th className="text-left px-4 py-3">Time</th>
                    <th className="text-left px-4 py-3">Device</th>
                    <th className="text-left px-4 py-3">Domain</th>
                    <th className="text-left px-4 py-3">Status</th>
                    <th className="text-left px-4 py-3 hidden md:table-cell">Reason</th>
                    <th className="text-left px-4 py-3 hidden lg:table-cell">Location</th>
                  </tr>
                </thead>
                <tbody>
                  {logs.map(l => (
                    <tr key={l.id} className="border-b border-gray-800/50 hover:bg-gray-800/30">
                      <td className="px-4 py-2.5 text-gray-500">{l.ts.slice(11,19)}</td>
                      <td className="px-4 py-2.5 text-yellow-400">{l.deviceName ?? l.mac ?? '?'}</td>
                      <td className="px-4 py-2.5 text-gray-300 max-w-[200px] truncate">{l.domain}</td>
                      <td className={`px-4 py-2.5 ${l.blocked ? 'text-red-400' : 'text-emerald-600'}`}>
                        {l.blocked ? '✗ blocked' : '✓ ok'}
                      </td>
                      <td className="px-4 py-2.5 text-gray-600 hidden md:table-cell">{l.reason}</td>
                      <td className="px-4 py-2.5 text-gray-600 hidden lg:table-cell">{l.location ?? ''}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {logs.length === 0 && <p className="p-6 text-gray-500 text-sm">No logs found.</p>}
            </div>
        }
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
