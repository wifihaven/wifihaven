import { useEffect, useMemo, useState } from 'react'
import { api } from '@/api/client'
import { useBlocklists, useProfiles } from '@/api/queries'
import type { AppDetail } from '@/types/api'
import { PageLoader } from './DashboardPage'
import { AppIcon } from '@/components/AppIcon'
import { EmptyState } from '@/components/EmptyState'
import { AppBlocklistWarningBadge, AppBlocklistWarningDetail } from '@/components/AppBlocklistWarning'

// #1798 — app *definitions* (name, slug, icon, host-set) are authored only via
// the built-in `AppTemplates` in code (which seed/reconcile server-side); the
// operator-facing create/edit/delete surface was removed. This page is now a
// read-only directory so operators can see apps + their hosts when assigning
// policy (policy assignment still lives on the Profiles page).
export function AppsPage() {
  const [apps, setApps] = useState<AppDetail[]>([])
  const [loading, setLoading] = useState(true)
  const [expandedId, setExpandedId] = useState<number | null>(null)
  const [error, setError] = useState<string | null>(null)
  const { data: profiles = [] } = useProfiles()
  // #1983 — map blocklist id (category slug) → display name for the warnings.
  const { data: blocklists = [] } = useBlocklists()

  const profileNameById = useMemo(() => {
    const m = new Map<number, string>()
    for (const p of profiles) m.set(p.profile.id, p.profile.name)
    return m
  }, [profiles])

  const blocklistNameById = useMemo(() => {
    const m = new Map<string, string>()
    for (const b of blocklists) m.set(b.id, b.name)
    return m
  }, [blocklists])

  useEffect(() => {
    api.apps
      .list()
      .then(list => setApps([...list].sort((a, b) => a.app.name.localeCompare(b.app.name))))
      .catch(e => setError(e instanceof Error ? e.message : 'Failed to load apps'))
      .finally(() => setLoading(false))
  }, [])

  if (loading) return <PageLoader />

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-bold text-brand-ink">Apps</h1>
        <p className="text-sm text-brand-text-muted mt-1">
          Bundles of hosts you can block, allow, or time-limit per profile. App
          definitions come from the built-in template library — assign them to
          profiles from the Profiles page.
        </p>
      </div>

      {error && (
        <div className="bg-red-500/10 border border-red-500/30 text-red-700 text-sm rounded-xl px-4 py-2">
          {error}
        </div>
      )}

      <div className="bg-white rounded-2xl border border-brand-border overflow-hidden">
        {apps.length === 0
          ? <EmptyState title="No apps yet." hint="Apps appear here once the template library is seeded." />
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
                        <p className="font-medium text-brand-ink truncate flex items-center gap-2">
                          {a.app.name}
                          <AppBlocklistWarningBadge blocklisted={a.blocklisted} nameById={blocklistNameById} />
                        </p>
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
                    <AppDetailView
                      detail={a}
                      profileNameById={profileNameById}
                      blocklistNameById={blocklistNameById}
                    />
                  )}
                </div>
              )
            })
        }
      </div>
    </div>
  )
}

// #1798 — read-only detail for an expanded app row: its host set and the
// profiles it's assigned to. No editing controls.
function AppDetailView({ detail, profileNameById, blocklistNameById }: {
  detail: AppDetail
  profileNameById: Map<number, string>
  blocklistNameById: Map<string, string>
}) {
  const assignedProfiles = useMemo(() => {
    const ids = new Set(detail.assignments.map(a => a.profileId))
    return [...ids].map(pid => profileNameById.get(pid) ?? `profile ${pid}`)
  }, [detail.assignments, profileNameById])

  return (
    <div className="px-5 pb-5 pt-1 space-y-4 bg-brand-surface/30 border-t border-brand-border">
      <div>
        <span className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2">
          Hosts
        </span>
        <div className="flex flex-wrap gap-2 min-h-[2rem]" data-testid="hosts-chips">
          {detail.hosts.length === 0
            ? <span className="text-xs text-brand-text-muted italic">No hosts.</span>
            : detail.hosts.map(h => (
                <span key={h} className="inline-flex items-center bg-brand-alt text-brand-accent text-xs font-mono px-2.5 py-1 rounded-lg">
                  {h}
                </span>
              ))}
        </div>
      </div>

      <AppBlocklistWarningDetail blocklisted={detail.blocklisted} nameById={blocklistNameById} />

      <p className="text-xs text-brand-text-muted">
        Used by {assignedProfiles.length === 0
          ? <span className="text-brand-text">no profiles</span>
          : <span className="text-brand-text">{assignedProfiles.join(', ')}</span>}.
      </p>
    </div>
  )
}
