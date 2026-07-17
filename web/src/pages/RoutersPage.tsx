import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '@/api/client'
import type { CreateRouterResponse, RouterSummary } from '@/types/api'
import { PageLoader } from './DashboardPage'
import { useEscapeClose } from '@/hooks/useEscapeClose'
import { EmptyState } from '@/components/EmptyState'
import { copyToClipboard } from '@/lib/clipboard'

export function RoutersPage() {
  const [routers, setRouters] = useState<RouterSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [loadError, setLoadError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [name, setName] = useState('')
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [showToken, setShowToken] = useState<CreateRouterResponse | null>(null)
  const [copyState, setCopyState] = useState<'idle' | 'copied' | 'failed'>('idle')

  async function copyToken(text: string) {
    // #2133: shared helper (secure-context Clipboard API + plain-http execCommand fallback).
    setCopyState(await copyToClipboard(text) ? 'copied' : 'failed')
    setTimeout(() => setCopyState('idle'), 2000)
  }

  async function reload() {
    try {
      const list = await api.routers.list()
      setRouters(list)
      setLoadError(null)
    } catch (e) {
      // #1191: keep last-known-good rows so a transient outage doesn't blank
      // the page, but flip the error state so the list shows "couldn't load"
      // instead of an empty state when there was nothing cached.
      setLoadError(e instanceof Error ? e.message : 'Failed to load routers')
    }
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
        <div>
          <h1 className="text-xl font-bold text-brand-ink">Routers</h1>
          {/* #2234: entry point to the install guide (hardware + install command) for
              admins who land here directly rather than via the first-run banner. */}
          <p className="text-sm text-brand-text-muted mt-1">
            New here?{' '}
            <Link to="/router-setup" className="text-brand-accent hover:underline">
              Hardware &amp; install guide →
            </Link>
          </p>
        </div>
        <button
          onClick={() => { setCreating(true); setError(null); setName('') }}
          className="bg-brand-accent hover:bg-brand-accent-dark text-white text-sm font-semibold px-4 py-2 rounded-xl transition-colors"
        >
          + Enroll Router
        </button>
      </div>

      <div className="bg-white rounded-2xl border border-brand-border overflow-hidden">
        {routers.length === 0 && loadError
          ? <EmptyState
              title="Couldn't load routers."
              hint={loadError}
              action={
                <button
                  onClick={() => { setLoading(true); reload().finally(() => setLoading(false)) }}
                  className="bg-brand-accent text-white text-sm font-semibold px-3 py-1.5 rounded-lg"
                >Retry</button>
              }
            />
          : routers.length === 0
          ? <EmptyState title="No routers enrolled yet." />
          : routers.map(r => (
              <div key={r.id} className="border-b border-brand-border last:border-0">
                <div className="flex items-center gap-4 px-5 py-4">
                  <div className="flex-1 min-w-0">
                    <p className="font-medium text-brand-ink truncate">{r.name}</p>
                    <p className="text-xs text-brand-text-muted mt-0.5 flex items-center gap-2">
                      <span className={`inline-block px-2 py-0.5 rounded font-mono ${
                        r.enrolled
                          ? 'bg-brand-accent/10 text-brand-accent'
                          : 'bg-amber-500/10 text-amber-700'
                      }`}>{r.enrolled ? 'enrolled' : 'pending'}</span>
                      {r.lastSeenAt
                        ? <span>last seen {new Date(r.lastSeenAt).toLocaleString()}</span>
                        : <span>never seen</span>
                      }
                      {r.agentVersion && (
                        <span
                          data-testid="router-agent-version"
                          className="inline-block px-2 py-0.5 rounded font-mono bg-brand-alt text-brand-text"
                        >
                          v{r.agentVersion}
                        </span>
                      )}
                    </p>
                  </div>
                  <button
                    onClick={() => del(r)}
                    className="text-xs text-red-700 hover:text-red-700 bg-red-500/10 px-3 py-1.5 rounded-lg transition-colors"
                  >Delete</button>
                </div>
              </div>
            ))
        }
      </div>

      {creating && (
        <Modal title="Enroll a Router" onClose={() => setCreating(false)}>
          <form
            onSubmit={e => { e.preventDefault(); saveCreate() }}
            className="space-y-5"
          >
            {error && (
              <div className="bg-red-500/10 border border-red-500/30 text-red-700 text-sm rounded-xl px-4 py-2">
                {error}
              </div>
            )}
            <div>
              <label className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2">
                Name <span className="text-red-700">*</span>
              </label>
              <input type="text" value={name} autoFocus required
                onChange={e => setName(e.target.value)}
                placeholder="home-gw"
                className="w-full bg-brand-surface border border-brand-border-strong rounded-xl px-4 py-3 text-brand-ink focus:outline-none focus:border-brand-accent" />
              <p className="text-xs text-brand-text-muted mt-2">
                This is the only place the router's display name is set — the
                install script on the router doesn't ask for it.
              </p>
            </div>
            <p className="text-sm text-brand-text">
              We'll generate a one-time enrollment token. Run the OpenWRT
              install script on the router and paste the token when prompted;
              no other identifier is needed. The token is single-use.
            </p>
            <div className="flex gap-3 pt-2">
              <button type="button" onClick={() => setCreating(false)} disabled={saving}
                className="flex-1 py-3 rounded-xl bg-brand-alt text-brand-text font-medium disabled:opacity-50">
                Cancel
              </button>
              <button type="submit" disabled={saving}
                className="flex-1 py-3 rounded-xl bg-brand-accent text-white font-semibold disabled:opacity-50">
                {saving ? 'Generating…' : 'Generate Token'}
              </button>
            </div>
          </form>
        </Modal>
      )}

      {showToken && (
        <Modal title="Save this token now" onClose={() => setShowToken(null)}>
          <div className="bg-amber-500/10 border border-amber-500/30 text-amber-700 text-sm rounded-xl px-4 py-3">
            <strong>This token will not be shown again.</strong> Copy it
            into the router's UCI config before closing this dialog.
          </div>
          <div>
            <label className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2">
              Enrollment token for {showToken.name}
            </label>
            {/* #2252 — inline copy affordance: a small icon on the right of the token field,
                not a full-width button below it. Right padding on the input keeps the token
                text clear of the icon. */}
            <div className="relative">
              <input
                type="text"
                readOnly
                value={showToken.enrollmentToken}
                onFocus={e => e.currentTarget.select()}
                className="block w-full bg-brand-surface border border-brand-border-strong rounded-xl pl-4 pr-11 py-3 text-brand-accent font-mono text-sm break-all"
              />
              <CopyTokenIcon state={copyState} onCopy={() => copyToken(showToken.enrollmentToken)} />
            </div>
            {copyState === 'failed' && (
              <p role="alert" className="mt-2 text-xs text-red-700">
                Copy failed — select the token above and copy manually.
              </p>
            )}
          </div>
          <button
            onClick={() => setShowToken(null)}
            className="w-full py-3 rounded-xl bg-brand-accent text-white font-semibold"
          >
            I've saved it — close
          </button>
        </Modal>
      )}
    </div>
  )
}

// #2252 — the inline copy icon that sits on the right edge of the token field. Keyboard-activatable
// (it's a real <button>, so Enter/Space work) with an aria-label and a focus-visible ring; the icon
// swaps to a check on success and a warning on failure, with the label announcing the state.
function CopyTokenIcon({ state, onCopy }: { state: 'idle' | 'copied' | 'failed'; onCopy: () => void }) {
  const label =
    state === 'copied' ? 'Enrollment token copied' :
    state === 'failed' ? 'Copy failed' :
    'Copy enrollment token to clipboard'
  return (
    <button
      type="button"
      data-testid="copy-token"
      onClick={onCopy}
      aria-label={label}
      title={label}
      className={`absolute right-1.5 top-1/2 -translate-y-1/2 p-2 rounded-lg transition-colors focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-accent ${
        state === 'copied'
          ? 'text-brand-accent'
          : state === 'failed'
          ? 'text-red-700'
          : 'text-brand-text-muted hover:text-brand-ink hover:bg-brand-alt'
      }`}
    >
      {state === 'copied' ? (
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <polyline points="20 6 9 17 4 12" />
        </svg>
      ) : state === 'failed' ? (
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <circle cx="12" cy="12" r="10" />
          <line x1="12" y1="8" x2="12" y2="12" />
          <line x1="12" y1="16" x2="12.01" y2="16" />
        </svg>
      ) : (
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
          <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
        </svg>
      )}
    </button>
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
