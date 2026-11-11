import { useEffect, useMemo, useRef, useState } from 'react'
import { api } from '@/api/client'
import { useAppGroups, useBlocklists, useInvalidators, useProfiles } from '@/api/queries'
import type { AppDetail, AppGroup } from '@/types/api'
import { PageLoader } from './DashboardPage'
import { AppIcon } from '@/components/AppIcon'
import { EmptyState } from '@/components/EmptyState'
import { AppBlocklistWarningBadge, AppBlocklistWarningDetail } from '@/components/AppBlocklistWarning'
import { errMsg, GroupMembersEditor, MembersToggle, useGroupMemberWrites } from '@/components/AppGroups'
import { Skeleton } from '@/components/Skeleton'

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

      <GroupsSection apps={apps} appsFailed={error != null} />

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

// #2974 — household app groups: a name and a member list. Their limits live on each profile's
// attachment (the Profiles page); membership is household-wide, so it is edited here as well as
// from a profile's group row, with the same picker.
function GroupsSection({ apps, appsFailed }: { apps: AppDetail[]; appsFailed: boolean }) {
  const groupsQ = useAppGroups()
  const invalidators = useInvalidators()
  const [openId, setOpenId] = useState<number | null>(null)
  const [naming, setNaming] = useState(false)
  const [name, setName] = useState('')
  const [creating, setCreating] = useState(false)
  const [error, setError] = useState<string | null>(null)
  // #2964: `working` guards re-entry synchronously (Enter then blur), and
  // `creating` is the same fact for rendering.
  const working = useRef(false)
  const groups = groupsQ.data ?? []

  function cancelNaming() { setNaming(false); setName('') }

  async function createGroup() {
    const trimmed = name.trim()
    if (!trimmed) { cancelNaming(); return }
    if (working.current) return
    working.current = true
    setCreating(true); setError(null)
    try {
      const g = await api.appGroups.create({ name: trimmed })
      cancelNaming()
      setOpenId(g.id)
      await invalidators.appGroups()
    } catch (e) {
      setError(errMsg(e, 'Failed to create group'))
    } finally { working.current = false; setCreating(false) }
  }

  return (
    <section data-testid="apps-groups" className="space-y-2">
      <div className="flex items-center gap-2">
        <h2 className="text-sm font-semibold text-brand-ink">Groups</h2>
        <span className="text-xs text-brand-text-muted">
          Several apps sharing one time budget. Set each group's limits on a profile.
        </span>
        <span className="ml-auto">
          {naming ? (
            <input
              type="text"
              autoFocus
              value={name}
              disabled={creating}
              onChange={e => setName(e.target.value)}
              onBlur={createGroup}
              onKeyDown={e => {
                if (e.key === 'Enter') { e.preventDefault(); void createGroup() }
                if (e.key === 'Escape') { e.preventDefault(); cancelNaming() }
              }}
              placeholder="Group name"
              aria-label="New group name"
              data-testid="apps-groups-new-name"
              className="bg-white border border-brand-border-strong rounded-lg px-2 py-1 text-xs text-brand-ink"
            />
          ) : (
            <button
              type="button"
              data-testid="apps-groups-new"
              onClick={() => setNaming(true)}
              className="text-xs px-2 py-1 rounded-lg border bg-white border-brand-accent/40 text-brand-accent hover:bg-brand-accent/10"
            >+ New group</button>
          )}
        </span>
      </div>
      {creating && <p className="text-xs text-brand-text-muted" data-testid="apps-groups-saving">Saving…</p>}
      {error && <p className="text-xs text-red-700" data-testid="apps-groups-create-error">{error}</p>}
      {groupsQ.isPending ? (
        <Skeleton className="h-12 w-full rounded-2xl" testId="apps-groups-loading" />
      ) : groupsQ.isError ? (
        <p className="text-xs text-red-700" data-testid="apps-groups-error">Couldn't load groups.</p>
      ) : groups.length === 0 ? (
        <p className="text-xs text-brand-text-muted italic" data-testid="apps-groups-empty">No groups yet.</p>
      ) : (
        <div className="bg-white rounded-2xl border border-brand-border overflow-hidden">
          {groups.map(g => (
            <GroupDirectoryRow
              key={g.id}
              group={g}
              groups={groups}
              apps={apps}
              appsFailed={appsFailed}
              open={openId === g.id}
              onToggle={() => setOpenId(id => (id === g.id ? null : g.id))}
            />
          ))}
        </div>
      )}
    </section>
  )
}

function GroupDirectoryRow({ group, groups, apps, appsFailed, open, onToggle }: {
  group: AppGroup
  groups: AppGroup[]
  apps: AppDetail[]
  appsFailed: boolean
  open: boolean
  onToggle: () => void
}) {
  const tid = `apps-group-${group.id}`
  const writes = useGroupMemberWrites(group.id)

  return (
    <div data-testid={tid} className="border-b border-brand-border last:border-0">
      <div className="flex items-center gap-3 px-5 py-3">
        <p className="font-medium text-brand-ink truncate flex-1">{group.name}</p>
        <MembersToggle tid={tid} count={group.memberAppIds.length} open={open} onClick={onToggle} />
      </div>
      {open && (
        <div className="px-5 pb-4 pt-1 bg-brand-surface/30 border-t border-brand-border">
          {appsFailed ? (
            // Without the app list the picker would read as "every app is already in this group".
            <p className="text-xs text-red-700" data-testid={`${tid}-apps-error`}>
              Couldn't load apps, so this group's members can't be shown or edited.
            </p>
          ) : (
            <GroupMembersEditor
              tid={tid}
              groupId={group.id}
              groupName={group.name}
              memberIds={group.memberAppIds}
              apps={apps}
              groups={groups}
              writes={writes}
            />
          )}
        </div>
      )}
      {writes.error && <p className="text-xs text-red-700 px-5 pb-2" data-testid={`${tid}-error`}>{writes.error}</p>}
    </div>
  )
}
