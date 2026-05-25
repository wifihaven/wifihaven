// #958: SPA management page for bundled blocklists. Lists every category
// (bundled + operator/test) with display metadata, host count, and a
// per-profile toggle matrix. The "View hosts" disclosure paginates the
// host list since some bundled lists run into the thousands.
//
// Admin-only — gated at the router (RequireAdmin). The underlying API
// (`GET /api/blocklists`) also requires admin.

import { useEffect, useMemo, useState } from 'react'
import { api } from '@/api/client'
import { useProfiles, useInvalidators } from '@/api/queries'
import type { BlocklistSummary, ProfileDetail, ScheduleRequest, SiteTimeLimitRequest, UpsertProfileRequest } from '@/types/api'
import { PageLoader } from './DashboardPage'

const HOSTS_PER_PAGE = 50

export function BlocklistsPage() {
  const profilesQuery = useProfiles()
  const profiles = profilesQuery.data ?? []
  const invalidators = useInvalidators()
  const [lists, setLists] = useState<BlocklistSummary[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [savingCell, setSavingCell] = useState<string | null>(null)
  const [expanded, setExpanded] = useState<string | null>(null)
  const [hostsCache, setHostsCache] = useState<Record<string, string[]>>({})
  const [hostsPage, setHostsPage] = useState<Record<string, number>>({})

  async function load() {
    try {
      setError(null)
      const rs = await api.blocklists.list()
      setLists(rs)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'failed to load blocklists')
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => {
    load()
  }, [])

  async function toggle(blocklistId: string, profile: ProfileDetail) {
    const cellKey = `${blocklistId}:${profile.profile.id}`
    setSavingCell(cellKey)
    setError(null)
    try {
      const has = profile.profile.blockedCategories.includes(blocklistId)
      const nextCats = has
        ? profile.profile.blockedCategories.filter(c => c !== blocklistId)
        : [...profile.profile.blockedCategories, blocklistId]
      const body: UpsertProfileRequest = {
        name: profile.profile.name,
        blockedCategories: nextCats,
        extraBlocked: profile.profile.extraBlocked,
        extraAllowed: profile.profile.extraAllowed,
        paused: profile.profile.paused,
        schedules: profile.schedules.map<ScheduleRequest>(s => ({
          name: s.name,
          days: s.days,
          startLocal: s.startLocal,
          endLocal: s.endLocal,
          tz: s.tz,
        })),
        timeLimit: profile.timeLimit?.dailyMinutes ?? null,
        siteTimeLimits: profile.siteTimeLimits.map<SiteTimeLimitRequest>(s => ({
          domainPattern: s.domainPattern,
          dailyMinutes: s.dailyMinutes,
          label: s.label,
          exemptFromDaily: s.exemptFromDaily,
        })),
        failureMode: profile.profile.failureMode,
        crossDeviceOverlapMode: profile.profile.crossDeviceOverlapMode,
      }
      await api.profiles.update(profile.profile.id, body)
      invalidators.profiles()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'failed to update profile')
    } finally {
      setSavingCell(null)
    }
  }

  async function toggleHosts(id: string) {
    if (expanded === id) {
      setExpanded(null)
      return
    }
    setExpanded(id)
    if (!hostsCache[id]) {
      try {
        const r = await api.blocklists.hosts(id)
        setHostsCache(prev => ({ ...prev, [id]: r.hosts }))
        setHostsPage(prev => ({ ...prev, [id]: 0 }))
      } catch (e) {
        setError(e instanceof Error ? e.message : 'failed to load hosts')
      }
    }
  }

  const sortedLists = useMemo(() => {
    // Bundled first (alphabetical), then non-bundled (alphabetical).
    return [...lists].sort((a, b) => {
      if (a.bundled !== b.bundled) return a.bundled ? -1 : 1
      return a.id.localeCompare(b.id)
    })
  }, [lists])

  if (loading || profilesQuery.isPending) return <PageLoader />

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-xl font-bold text-white">Blocklists</h1>
        <p className="text-sm text-gray-500 mt-1">
          Curated host categories. Toggle per profile — devices in that profile will be blocked
          from every host in the list. Bundled lists ship with the API release; the host content
          is refreshed on every API restart.
        </p>
      </div>

      {error && (
        <div className="bg-red-500/10 border border-red-500/40 text-red-300 text-sm rounded-lg px-3 py-2">
          {error}
        </div>
      )}

      {sortedLists.length === 0 && (
        <p className="text-sm text-gray-500">No blocklists available.</p>
      )}

      <div className="space-y-3">
        {sortedLists.map(b => {
          const hostsLoaded = hostsCache[b.id]
          const page = hostsPage[b.id] ?? 0
          const totalPages = hostsLoaded ? Math.max(1, Math.ceil(hostsLoaded.length / HOSTS_PER_PAGE)) : 1
          const pageHosts = hostsLoaded?.slice(page * HOSTS_PER_PAGE, (page + 1) * HOSTS_PER_PAGE) ?? []
          return (
            <div key={b.id} data-testid={`blocklist-${b.id}`} className="bg-gray-900 border border-gray-800 rounded-lg p-4">
              <div className="flex items-start justify-between gap-3 flex-wrap">
                <div className="min-w-0 flex-1">
                  <div className="flex items-center gap-2">
                    <h2 className="text-base font-semibold text-white">{b.name}</h2>
                    <span className="text-xs font-mono text-gray-500">{b.id}</span>
                    {b.bundled && (
                      <span className="text-[10px] uppercase tracking-wider bg-emerald-500/10 text-emerald-400 border border-emerald-500/30 px-1.5 py-0.5 rounded">
                        bundled
                      </span>
                    )}
                  </div>
                  {b.description && <p className="text-sm text-gray-400 mt-1">{b.description}</p>}
                  <p className="text-xs text-gray-500 mt-1">
                    {b.hostCount.toLocaleString()} hosts
                    {b.lastBuiltAt && ` · built ${new Date(b.lastBuiltAt).toLocaleString()}`}
                    {b.source && ` · ${b.source}`}
                  </p>
                </div>
                <button
                  type="button"
                  onClick={() => toggleHosts(b.id)}
                  className="text-xs text-gray-400 hover:text-white border border-gray-700 hover:border-gray-600 rounded-lg px-3 py-1.5"
                >
                  {expanded === b.id ? 'Hide hosts' : 'View hosts'}
                </button>
              </div>

              {profiles.length > 0 && (
                <div className="mt-3 pt-3 border-t border-gray-800">
                  <p className="text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">
                    Enabled for
                  </p>
                  <div className="flex flex-wrap gap-2">
                    {profiles.map(p => {
                      const cellKey = `${b.id}:${p.profile.id}`
                      const on = p.profile.blockedCategories.includes(b.id)
                      const saving = savingCell === cellKey
                      return (
                        <button
                          key={p.profile.id}
                          type="button"
                          disabled={saving}
                          onClick={() => toggle(b.id, p)}
                          data-testid={`toggle-${b.id}-${p.profile.id}`}
                          className={`text-xs px-3 py-1.5 rounded-lg border transition-colors ${
                            on
                              ? 'bg-red-500/20 text-red-300 border-red-500/40'
                              : 'bg-gray-800 text-gray-400 border-gray-700 hover:border-gray-600'
                          } ${saving ? 'opacity-50 cursor-wait' : ''}`}
                        >
                          {on ? '✓ ' : ''}{p.profile.name}
                        </button>
                      )
                    })}
                  </div>
                </div>
              )}

              {expanded === b.id && (
                <div className="mt-3 pt-3 border-t border-gray-800">
                  {!hostsLoaded ? (
                    <p className="text-xs text-gray-500">Loading hosts…</p>
                  ) : hostsLoaded.length === 0 ? (
                    <p className="text-xs text-gray-500">No hosts in this list.</p>
                  ) : (
                    <>
                      <div className="grid grid-cols-1 sm:grid-cols-2 md:grid-cols-3 gap-x-4 gap-y-1 font-mono text-xs text-gray-400">
                        {pageHosts.map(h => <div key={h}>{h}</div>)}
                      </div>
                      {totalPages > 1 && (
                        <div className="flex items-center justify-between mt-3 text-xs text-gray-500">
                          <span>
                            {page * HOSTS_PER_PAGE + 1}–{Math.min((page + 1) * HOSTS_PER_PAGE, hostsLoaded.length)}
                            {' '}of {hostsLoaded.length.toLocaleString()}
                          </span>
                          <div className="flex gap-2">
                            <button
                              type="button"
                              disabled={page === 0}
                              onClick={() => setHostsPage(prev => ({ ...prev, [b.id]: page - 1 }))}
                              className="px-2 py-1 rounded border border-gray-700 disabled:opacity-30"
                            >
                              Prev
                            </button>
                            <button
                              type="button"
                              disabled={page >= totalPages - 1}
                              onClick={() => setHostsPage(prev => ({ ...prev, [b.id]: page + 1 }))}
                              className="px-2 py-1 rounded border border-gray-700 disabled:opacity-30"
                            >
                              Next
                            </button>
                          </div>
                        </div>
                      )}
                    </>
                  )}
                </div>
              )}
            </div>
          )
        })}
      </div>
    </div>
  )
}
