import { useState } from 'react'
import { api } from '@/api/client'
import type { BlocklistSummary, GlobalPolicyAuditEntry, MacBlockReason } from '@/types/api'
import { useGlobalPolicy, useInvalidators } from '@/api/queries'
import { useQuery } from '@tanstack/react-query'
import { PageLoader } from './DashboardPage'

// #1320 / #1308 — household-global policy management + audit view.
//
// `global.extraAllowed` is a SECURITY-SENSITIVE bypass surface: a host here is
// reachable from EVERY device regardless of any block (it sits at the top of
// the precedence ladder — design §5.2). So the allow list leads with a prominent
// audit trail (who added each host, why, and the soft-deleted history). Global
// blocks are mandatory network-wide drops a profile may NOT un-block; the
// network-lockdown + strict-IP toggles are the flat global flags.
//
// None of this is a new router concept — it all collapses into the one
// `PolicySnapshot.global : BlockRules` the router already applies (the *why*
// stays server-side). See AGENTS.md "Architectural model".
export function GlobalPolicyPage() {
  const { data: view, isLoading } = useGlobalPolicy()
  const blocklistsQ = useQuery({
    queryKey: ['blocklists'],
    queryFn: () => api.blocklists.list(),
    staleTime: 60_000,
  })
  const invalidators = useInvalidators()
  const refresh = () => invalidators.globalPolicy()

  if (isLoading || !view) return <PageLoader />

  return (
    <div className="space-y-6" data-testid="global-policy-page">
      <div>
        <h1 className="text-xl font-bold text-brand-ink">Global Policy</h1>
        <p className="text-sm text-brand-text-muted mt-1">
          Fleet-wide allow / block rules applied to <strong>every</strong> device, on top of each
          profile. Resolved server-side into one snapshot the router applies blindly.
        </p>
      </div>

      <AlwaysAllowCard entries={view.allow} onChanged={refresh} />
      <GlobalBlocksCard entries={view.blocks} onChanged={refresh} />
      <GlobalCategoriesCard
        selected={view.blocklistIds}
        all={blocklistsQ.data ?? []}
        onChanged={refresh}
      />
      <NetworkFlagsCard
        blocked={view.blocked}
        blockReason={view.blockReason}
        blockIpOnly={view.blockIpOnly}
        onChanged={refresh}
      />
    </div>
  )
}

const REASONS: MacBlockReason[] = ['Manual', 'Paused', 'Schedule', 'TimeLimit', 'DefaultDeny']

function activeOf(entries: GlobalPolicyAuditEntry[]) {
  return entries.filter(e => e.removedAt === null)
}
function historyOf(entries: GlobalPolicyAuditEntry[]) {
  return entries.filter(e => e.removedAt !== null)
}

// ── always-reachable allow set ──────────────────────────────────────────────

function AlwaysAllowCard({
  entries, onChanged,
}: {
  entries: GlobalPolicyAuditEntry[]
  onChanged: () => void
}) {
  const [host, setHost] = useState('')
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const active = activeOf(entries)
  const history = historyOf(entries)

  async function add() {
    const h = host.trim()
    if (!h) return
    setBusy(true); setError(null)
    try {
      await api.global.addAllow({ host: h, reason: reason.trim() || null })
      setHost(''); setReason('')
      onChanged()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to add host')
    } finally {
      setBusy(false)
    }
  }

  async function remove(h: string) {
    setBusy(true); setError(null)
    try {
      await api.global.removeAllow(h)
      onChanged()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to remove host')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section
      data-testid="global-allow-card"
      className="bg-white rounded-2xl border border-amber-500/30 p-5 space-y-4"
    >
      <div>
        <h2 className="font-semibold text-brand-ink flex items-center gap-2">
          <span aria-hidden>⚠️</span> Always-allow
        </h2>
        <p className="text-sm text-brand-text-muted mt-1">
          A bypass surface — hosts here are reachable from every device <em>regardless of any
          block</em> (profile, schedule, time limit, pause, or a global block). Use only for
          connectivity / PKI / the WifiHaven UI. Every change is audited below.
        </p>
      </div>

      <div className="flex flex-wrap gap-2 items-end">
        <label className="flex flex-col text-xs text-brand-text-muted">
          Host
          <input
            type="text"
            value={host}
            onChange={e => setHost(e.target.value)}
            placeholder="ocsp.example.com"
            data-testid="global-allow-host-input"
            className="mt-1 bg-brand-surface border border-brand-border-strong rounded-lg px-3 py-2 text-sm text-brand-ink font-mono w-64"
          />
        </label>
        <label className="flex flex-col text-xs text-brand-text-muted">
          Reason (audit)
          <input
            type="text"
            value={reason}
            onChange={e => setReason(e.target.value)}
            placeholder="PKI / OCSP stapling"
            data-testid="global-allow-reason-input"
            className="mt-1 bg-brand-surface border border-brand-border-strong rounded-lg px-3 py-2 text-sm text-brand-ink w-64"
          />
        </label>
        <button
          type="button"
          onClick={add}
          disabled={busy || host.trim() === ''}
          data-testid="global-allow-add"
          className="px-4 py-2 rounded-lg bg-brand-accent text-white text-sm font-semibold disabled:opacity-50"
        >
          Add
        </button>
      </div>
      {error && <p className="text-xs text-red-700" data-testid="global-allow-error">{error}</p>}

      <AuditTable
        active={active}
        history={history}
        onRemove={remove}
        emptyLabel="No global allow hosts."
        testId="global-allow"
      />
    </section>
  )
}

// ── network-wide block set ──────────────────────────────────────────────────

function GlobalBlocksCard({
  entries, onChanged,
}: {
  entries: GlobalPolicyAuditEntry[]
  onChanged: () => void
}) {
  const [host, setHost] = useState('')
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function add() {
    const h = host.trim()
    if (!h) return
    setBusy(true); setError(null)
    try {
      await api.global.addBlock({ host: h, reason: reason.trim() || null })
      setHost(''); setReason('')
      onChanged()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to add host')
    } finally {
      setBusy(false)
    }
  }

  async function remove(h: string) {
    setBusy(true); setError(null)
    try {
      await api.global.removeBlock(h)
      onChanged()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to remove host')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section
      data-testid="global-blocks-card"
      className="bg-white rounded-2xl border border-brand-border p-5 space-y-4"
    >
      <div>
        <h2 className="font-semibold text-brand-ink">Global blocks</h2>
        <p className="text-sm text-brand-text-muted mt-1">
          Hosts blocked on every device. A profile or device may <strong>not</strong> un-block
          these — only an always-allow entry above overrides a global block.
        </p>
      </div>

      <div className="flex flex-wrap gap-2 items-end">
        <label className="flex flex-col text-xs text-brand-text-muted">
          Host
          <input
            type="text"
            value={host}
            onChange={e => setHost(e.target.value)}
            placeholder="malware.example.com"
            data-testid="global-block-host-input"
            className="mt-1 bg-brand-surface border border-brand-border-strong rounded-lg px-3 py-2 text-sm text-brand-ink font-mono w-64"
          />
        </label>
        <label className="flex flex-col text-xs text-brand-text-muted">
          Reason (audit)
          <input
            type="text"
            value={reason}
            onChange={e => setReason(e.target.value)}
            placeholder="operator policy"
            data-testid="global-block-reason-input"
            className="mt-1 bg-brand-surface border border-brand-border-strong rounded-lg px-3 py-2 text-sm text-brand-ink w-64"
          />
        </label>
        <button
          type="button"
          onClick={add}
          disabled={busy || host.trim() === ''}
          data-testid="global-block-add"
          className="px-4 py-2 rounded-lg bg-red-600 text-white text-sm font-semibold disabled:opacity-50"
        >
          Block
        </button>
      </div>
      {error && <p className="text-xs text-red-700" data-testid="global-block-error">{error}</p>}

      <AuditTable
        active={activeOf(entries)}
        history={historyOf(entries)}
        onRemove={remove}
        emptyLabel="No global block hosts."
        testId="global-block"
      />
    </section>
  )
}

// ── household-global category set ───────────────────────────────────────────

function GlobalCategoriesCard({
  selected, all, onChanged,
}: {
  selected: string[]
  all: BlocklistSummary[]
  onChanged: () => void
}) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const selectedSet = new Set(selected)

  async function toggle(id: string) {
    const next = selectedSet.has(id)
      ? selected.filter(x => x !== id)
      : [...selected, id]
    setBusy(true); setError(null)
    try {
      await api.global.setBlocklists(next)
      onChanged()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to update categories')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section
      data-testid="global-categories-card"
      className="bg-white rounded-2xl border border-brand-border p-5 space-y-3"
    >
      <div>
        <h2 className="font-semibold text-brand-ink">Global category blocklists</h2>
        <p className="text-sm text-brand-text-muted mt-1">
          Category lists applied to every device. Like global host blocks, a profile may not
          un-block these.
        </p>
      </div>
      {all.length === 0
        ? <p className="text-xs text-brand-text-muted">No blocklists available.</p>
        : (
          <div className="flex flex-wrap gap-2">
            {all.map(bl => {
              const on = selectedSet.has(bl.id)
              return (
                <button
                  key={bl.id}
                  type="button"
                  disabled={busy}
                  onClick={() => toggle(bl.id)}
                  data-testid={`global-category-${bl.id}`}
                  aria-pressed={on}
                  className={`px-3 py-1.5 rounded-lg text-sm font-medium border transition-colors disabled:opacity-50 ${
                    on
                      ? 'bg-red-500/10 text-red-700 border-red-500/30'
                      : 'bg-brand-alt text-brand-text border-brand-border'
                  }`}
                >
                  {bl.name}
                </button>
              )
            })}
          </div>
        )
      }
      {error && <p className="text-xs text-red-700" data-testid="global-categories-error">{error}</p>}
    </section>
  )
}

// ── flat global flags ───────────────────────────────────────────────────────

function NetworkFlagsCard({
  blocked, blockReason, blockIpOnly, onChanged,
}: {
  blocked: boolean
  blockReason: MacBlockReason | null
  blockIpOnly: boolean
  onChanged: () => void
}) {
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // Local copy of the reason so it can be chosen before lockdown is enabled.
  const [reason, setReason] = useState<MacBlockReason>(blockReason ?? 'Manual')

  async function save(next: { blocked?: boolean; blockReason?: MacBlockReason; blockIpOnly?: boolean }) {
    setBusy(true); setError(null)
    try {
      await api.global.setFlags({
        blocked: next.blocked ?? blocked,
        blockReason: next.blockReason ?? reason,
        blockIpOnly: next.blockIpOnly ?? blockIpOnly,
      })
      onChanged()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to update flags')
    } finally {
      setBusy(false)
    }
  }

  return (
    <section
      data-testid="global-flags-card"
      className="bg-white rounded-2xl border border-brand-border p-5 space-y-4"
    >
      <h2 className="font-semibold text-brand-ink">Network controls</h2>

      <div className="flex items-start justify-between gap-4">
        <div>
          <p className="text-sm font-medium text-brand-ink">Network lockdown</p>
          <p className="text-xs text-brand-text-muted mt-0.5">
            Drop <strong>all</strong> traffic on every device except the always-allow list above. A
            household-wide kill switch.
          </p>
        </div>
        <label className="inline-flex items-center cursor-pointer shrink-0">
          <input
            type="checkbox"
            checked={blocked}
            disabled={busy}
            onChange={e => save({ blocked: e.target.checked })}
            data-testid="global-lockdown-toggle"
            className="sr-only peer"
          />
          <span className="w-11 h-6 bg-brand-border rounded-full peer-checked:bg-red-600 relative transition-colors after:content-[''] after:absolute after:top-0.5 after:left-0.5 after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-transform peer-checked:after:translate-x-5" />
        </label>
      </div>

      {blocked && (
        <label className="flex flex-col text-xs text-brand-text-muted w-64">
          Block-page reason
          <select
            value={reason}
            disabled={busy}
            onChange={e => {
              const r = e.target.value as MacBlockReason
              setReason(r)
              save({ blockReason: r })
            }}
            data-testid="global-lockdown-reason"
            className="mt-1 bg-brand-surface border border-brand-border-strong rounded-lg px-3 py-2 text-sm text-brand-ink"
          >
            {REASONS.map(r => <option key={r} value={r}>{r}</option>)}
          </select>
        </label>
      )}

      <div className="flex items-start justify-between gap-4 border-t border-brand-border pt-4">
        <div>
          <p className="text-sm font-medium text-brand-ink">Strict-IP mode (network-wide)</p>
          <p className="text-xs text-brand-text-muted mt-0.5">
            Drop any destination IP not resolved through our DNS — blocks DoH / hard-coded-IP
            bypass on every device.
          </p>
        </div>
        <label className="inline-flex items-center cursor-pointer shrink-0">
          <input
            type="checkbox"
            checked={blockIpOnly}
            disabled={busy}
            onChange={e => save({ blockIpOnly: e.target.checked })}
            data-testid="global-strict-ip-toggle"
            className="sr-only peer"
          />
          <span className="w-11 h-6 bg-brand-border rounded-full peer-checked:bg-brand-accent relative transition-colors after:content-[''] after:absolute after:top-0.5 after:left-0.5 after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-transform peer-checked:after:translate-x-5" />
        </label>
      </div>
      {error && <p className="text-xs text-red-700" data-testid="global-flags-error">{error}</p>}
    </section>
  )
}

// ── shared audit table ──────────────────────────────────────────────────────

function AuditTable({
  active, history, onRemove, emptyLabel, testId,
}: {
  active: GlobalPolicyAuditEntry[]
  history: GlobalPolicyAuditEntry[]
  onRemove: (host: string) => void
  emptyLabel: string
  testId: string
}) {
  const [showHistory, setShowHistory] = useState(false)
  return (
    <div data-testid={`${testId}-audit`}>
      {active.length === 0
        ? <p className="text-xs text-brand-text-muted">{emptyLabel}</p>
        : (
          <ul className="divide-y divide-brand-border border border-brand-border rounded-lg">
            {active.map(e => (
              <li
                key={e.host}
                data-testid={`${testId}-row-${e.host}`}
                className="flex items-center justify-between gap-3 px-3 py-2 text-sm"
              >
                <div className="min-w-0">
                  <span className="font-mono text-brand-ink">{e.host}</span>
                  {e.reason && <span className="text-brand-text-muted"> — {e.reason}</span>}
                  <span className="block text-xs text-brand-text-muted">
                    added by {e.addedBy ?? 'unknown'} · {e.addedAt}
                  </span>
                </div>
                <button
                  type="button"
                  onClick={() => onRemove(e.host)}
                  data-testid={`${testId}-remove-${e.host}`}
                  className="text-xs text-red-700 hover:bg-red-500/10 border border-transparent hover:border-red-500/20 px-2 py-1 rounded-lg shrink-0"
                >
                  Remove
                </button>
              </li>
            ))}
          </ul>
        )
      }

      {history.length > 0 && (
        <div className="mt-2">
          <button
            type="button"
            onClick={() => setShowHistory(s => !s)}
            data-testid={`${testId}-history-toggle`}
            className="text-xs text-brand-text-muted hover:text-brand-ink"
          >
            {showHistory ? '▾' : '▸'} Audit history ({history.length} removed)
          </button>
          {showHistory && (
            <ul className="mt-1 divide-y divide-brand-border border border-brand-border rounded-lg opacity-70">
              {history.map((e, i) => (
                <li
                  key={`${e.host}-${i}`}
                  data-testid={`${testId}-history-row`}
                  className="px-3 py-2 text-xs text-brand-text-muted"
                >
                  <span className="font-mono line-through">{e.host}</span>
                  {e.reason && <span> — {e.reason}</span>}
                  <span className="block">
                    added by {e.addedBy ?? 'unknown'} · removed by {e.removedBy ?? 'unknown'}
                    {e.removedAt ? ` · ${e.removedAt}` : ''}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>
      )}
    </div>
  )
}
