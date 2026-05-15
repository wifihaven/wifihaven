import { useEffect, useState } from 'react'
import { api } from '@/api/client'
import type { CreateRouterResponse, RouterSummary } from '@/types/api'
import { PageLoader } from './DashboardPage'

export function RoutersPage() {
  const [routers, setRouters] = useState<RouterSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [creating, setCreating] = useState(false)
  const [name, setName] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [showToken, setShowToken] = useState<CreateRouterResponse | null>(null)
  const [copyState, setCopyState] = useState<'idle' | 'copied' | 'failed'>('idle')

  async function copyToken(text: string) {
    try {
      if (navigator.clipboard && window.isSecureContext) {
        await navigator.clipboard.writeText(text)
      } else {
        const ta = document.createElement('textarea')
        ta.value = text
        ta.setAttribute('readonly', '')
        ta.style.position = 'fixed'
        ta.style.opacity = '0'
        document.body.appendChild(ta)
        ta.select()
        const ok = document.execCommand('copy')
        document.body.removeChild(ta)
        if (!ok) throw new Error('execCommand copy failed')
      }
      setCopyState('copied')
    } catch {
      setCopyState('failed')
    }
    setTimeout(() => setCopyState('idle'), 2000)
  }

  async function reload() {
    const list = await api.routers.list()
    setRouters(list)
  }

  useEffect(() => {
    reload().finally(() => setLoading(false))
  }, [])

  async function saveCreate() {
    if (!name.trim()) { setError('Name is required'); return }
    setSaving(true)
    setError(null)
    try {
      const out = await api.routers.create({ name: name.trim() })
      setCreating(false)
      setName('')
      setShowToken(out)
      await reload()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to create router')
    } finally {
      setSaving(false)
    }
  }

  async function del(r: RouterSummary) {
    if (!confirm(`Delete router "${r.name}"? Its agent will lose access. This cannot be undone.`)) return
    try {
      await api.routers.delete(r.id)
      await reload()
    } catch (e) {
      alert(e instanceof Error ? e.message : 'Failed to delete')
    }
  }

  if (loading) return <PageLoader />

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold text-white">Routers</h1>
        <button
          onClick={() => { setCreating(true); setError(null); setName('') }}
          className="bg-emerald-500 hover:bg-emerald-400 text-black text-sm font-semibold px-4 py-2 rounded-xl transition-colors"
        >
          + Enroll Router
        </button>
      </div>

      <div className="bg-gray-900 rounded-2xl border border-gray-800 overflow-hidden">
        {routers.length === 0
          ? <p className="p-6 text-gray-500 text-sm">No routers enrolled yet.</p>
          : routers.map(r => (
              <div key={r.id} className="border-b border-gray-800 last:border-0">
                <div className="flex items-center gap-4 px-5 py-4">
                  <div className="flex-1 min-w-0">
                    <p className="font-medium text-white truncate">{r.name}</p>
                    <p className="text-xs text-gray-500 mt-0.5 flex items-center gap-2">
                      <span className={`inline-block px-2 py-0.5 rounded font-mono ${
                        r.enrolled
                          ? 'bg-emerald-500/10 text-emerald-400'
                          : 'bg-yellow-500/10 text-yellow-400'
                      }`}>{r.enrolled ? 'enrolled' : 'pending'}</span>
                      {r.lastSeenAt
                        ? <span>last seen {new Date(r.lastSeenAt).toLocaleString()}</span>
                        : <span>never seen</span>
                      }
                    </p>
                  </div>
                  <button
                    onClick={() => del(r)}
                    className="text-xs text-red-400 hover:text-red-300 bg-red-500/10 px-3 py-1.5 rounded-lg transition-colors"
                  >Delete</button>
                </div>
              </div>
            ))
        }
      </div>

      {creating && (
        <Modal title="Enroll a Router" onClose={() => setCreating(false)}>
          {error && (
            <div className="bg-red-500/10 border border-red-500/30 text-red-300 text-sm rounded-xl px-4 py-2">
              {error}
            </div>
          )}
          <div>
            <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">
              Name <span className="text-red-400">*</span>
            </label>
            <input type="text" value={name} autoFocus required
              onChange={e => setName(e.target.value)}
              placeholder="home-gw"
              className="w-full bg-gray-950 border border-gray-700 rounded-xl px-4 py-3 text-white focus:outline-none focus:border-emerald-500" />
            <p className="text-xs text-gray-500 mt-2">
              This is the only place the router's display name is set — the
              install script on the router doesn't ask for it.
            </p>
          </div>
          <p className="text-sm text-gray-400">
            We'll generate a one-time enrollment token. Run the OpenWRT
            install script on the router and paste the token when prompted;
            no other identifier is needed. The token is single-use.
          </p>
          <div className="flex gap-3 pt-2">
            <button onClick={() => setCreating(false)} disabled={saving}
              className="flex-1 py-3 rounded-xl bg-gray-800 text-gray-300 font-medium disabled:opacity-50">
              Cancel
            </button>
            <button onClick={saveCreate} disabled={saving}
              className="flex-1 py-3 rounded-xl bg-emerald-500 text-black font-semibold disabled:opacity-50">
              {saving ? 'Generating…' : 'Generate Token'}
            </button>
          </div>
        </Modal>
      )}

      {showToken && (
        <Modal title="Save this token now" onClose={() => setShowToken(null)}>
          <div className="bg-yellow-500/10 border border-yellow-500/30 text-yellow-300 text-sm rounded-xl px-4 py-3">
            <strong>This token will not be shown again.</strong> Copy it
            into the router's UCI config before closing this dialog.
          </div>
          <div>
            <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">
              Enrollment token for {showToken.name}
            </label>
            <input
              type="text"
              readOnly
              value={showToken.enrollmentToken}
              onFocus={e => e.currentTarget.select()}
              className="block w-full bg-gray-950 border border-gray-700 rounded-xl px-4 py-3 text-emerald-300 font-mono text-sm break-all"
            />
          </div>
          <button
            onClick={() => copyToken(showToken.enrollmentToken)}
            aria-label="Copy enrollment token to clipboard"
            className={`w-full py-2 rounded-xl text-sm font-medium transition-colors ${
              copyState === 'copied'
                ? 'bg-emerald-500/20 text-emerald-300'
                : copyState === 'failed'
                ? 'bg-red-500/20 text-red-300'
                : 'bg-gray-800 text-gray-200 hover:bg-gray-700'
            }`}
          >
            {copyState === 'copied'
              ? 'Copied!'
              : copyState === 'failed'
              ? 'Copy failed — select the token above and copy manually'
              : 'Copy to clipboard'}
          </button>
          <button
            onClick={() => setShowToken(null)}
            className="w-full py-3 rounded-xl bg-emerald-500 text-black font-semibold"
          >
            I've saved it — close
          </button>
        </Modal>
      )}
    </div>
  )
}

function Modal({ title, children, onClose }: { title: string; children: React.ReactNode; onClose: () => void }) {
  return (
    <div className="fixed inset-0 bg-black/70 flex items-end sm:items-center justify-center z-50 p-4 overflow-y-auto" onClick={onClose}>
      <div
        className="bg-gray-900 rounded-2xl border border-gray-700 w-full max-w-lg my-8 p-6 space-y-5 max-h-[90vh] overflow-y-auto"
        onClick={e => e.stopPropagation()}
      >
        <h3 className="text-lg font-bold text-white">{title}</h3>
        {children}
      </div>
    </div>
  )
}
