import { useEffect, useMemo, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { api } from '@/api/client'
import { useProfiles, useDevices, useInvalidators } from '@/api/queries'
import { useAuth } from '@/hooks/useAuth'
import type {
  AppDetail, AppMode, AppPolicyAssignment,
  CrossDeviceOverlapMode, Device, FailureMode, HouseholdSettings, ProfileDetail,
  ScheduleRequest, SiteTimeLimitRequest, UpsertProfileRequest, User,
} from '@/types/api'
import { TimezonePicker, browserTimezone } from '@/components/TimezonePicker'
import { PageLoader } from './DashboardPage'

const DAYS = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'] as const

interface FormState {
  name: string
  blockedCategories: string[]
  extraBlocked: string
  extraAllowed: string
  paused: boolean
  timeLimit: string
  schedules: ScheduleRequest[]
  siteTimeLimits: SiteTimeLimitRequest[]
  failureMode: FailureMode
  crossDeviceOverlapMode: CrossDeviceOverlapMode
}

function emptyForm(): FormState {
  return {
    name: '',
    blockedCategories: [],
    extraBlocked: '',
    extraAllowed: '',
    paused: false,
    timeLimit: '',
    schedules: [],
    siteTimeLimits: [],
    // #385: safe default for a brand-new profile is LastKnownGood
    // (matches the DB column default). The editor calls out the
    // BlockAll-for-kids recommendation in copy; admins still have to
    // pick BlockAll explicitly when creating a child profile.
    failureMode: 'last-known-good',
    // #751: default to the historical behaviour ("two siblings on the same
    // profile both active count as two") for new profiles. Admins opt in to
    // dedup explicitly when one profile = one human with multiple devices.
    crossDeviceOverlapMode: 'sum',
  }
}

function detailToForm(pd: ProfileDetail): FormState {
  return {
    name: pd.profile.name,
    blockedCategories: pd.profile.blockedCategories,
    extraBlocked: pd.profile.extraBlocked.join('\n'),
    extraAllowed: pd.profile.extraAllowed.join('\n'),
    paused: pd.profile.paused,
    timeLimit: pd.timeLimit ? String(pd.timeLimit.dailyMinutes) : '',
    schedules: pd.schedules.map(s => ({
      name: s.name, days: s.days, startLocal: s.startLocal, endLocal: s.endLocal, tz: s.tz,
    })),
    siteTimeLimits: pd.siteTimeLimits.map(s => ({
      domainPattern: s.domainPattern,
      dailyMinutes: s.dailyMinutes,
      label: s.label,
      exemptFromDaily: s.exemptFromDaily,
    })),
    failureMode: pd.profile.failureMode,
    crossDeviceOverlapMode: pd.profile.crossDeviceOverlapMode,
  }
}

function formToRequest(f: FormState): UpsertProfileRequest {
  const splitLines = (s: string) => s.split('\n').map(x => x.trim()).filter(Boolean)
  const tl = f.timeLimit.trim() === '' ? null : Number(f.timeLimit)
  return {
    name: f.name.trim(),
    blockedCategories: f.blockedCategories,
    extraBlocked: splitLines(f.extraBlocked),
    extraAllowed: splitLines(f.extraAllowed),
    paused: f.paused,
    timeLimit: tl !== null && Number.isFinite(tl) ? tl : null,
    schedules: f.schedules,
    siteTimeLimits: f.siteTimeLimits,
    failureMode: f.failureMode,
    crossDeviceOverlapMode: f.crossDeviceOverlapMode,
  }
}

export function ProfilesPage() {
  const { isAdmin } = useAuth()
  const invalidators = useInvalidators()
  const profilesQuery = useProfiles()
  const devicesQuery  = useDevices()
  const profiles = profilesQuery.data ?? []
  const devices  = devicesQuery.data  ?? []
  const [categories, setCategories] = useState<string[]>([])
  const [allUsers, setAllUsers] = useState<User[]>([])
  // Aux fetches (blocklists/users/household) aren't part of the #803 hot
  // loop — keep them as one-shot useEffect state. They only need to load
  // once when the page mounts.
  const [auxLoading, setAuxLoading] = useState(true)
  const loading = profilesQuery.isPending || devicesQuery.isPending || auxLoading
  const [editingId, setEditingId] = useState<number | 'new' | null>(null)
  const [form, setForm] = useState<FormState>(emptyForm())
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [editingUsersFor, setEditingUsersFor] = useState<number | null>(null)
  const [userPick, setUserPick] = useState<number[]>([])
  const [household, setHousehold] = useState<HouseholdSettings | null>(null)
  const [apps, setApps] = useState<AppDetail[]>([])

  // #298: LogsPage links to `/profiles?id=...`; scroll + highlight the
  // matching profile card so the parent sees what they clicked from logs.
  const [params] = useSearchParams()
  const queryId = params.get('id')
  const [highlightId, setHighlightId] = useState<number | null>(null)
  useEffect(() => {
    if (!queryId || profiles.length === 0) return
    const id = Number(queryId)
    if (!Number.isFinite(id) || !profiles.some(p => p.profile.id === id)) return
    setHighlightId(id)
    const el = document.querySelector(`[data-testid="profile-card-${id}"]`) as HTMLElement | null
    el?.scrollIntoView?.({ block: 'center', behavior: 'smooth' })
    const t = setTimeout(() => setHighlightId(null), 2000)
    return () => clearTimeout(t)
  }, [queryId, profiles])

  const devicesByProfile = useMemo(() => {
    const m = new Map<number, Device[]>()
    for (const d of devices) {
      if (d.profileId == null) continue
      const arr = m.get(d.profileId) ?? []
      arr.push(d)
      m.set(d.profileId, arr)
    }
    return m
  }, [devices])

  const usersByProfile = useMemo(() => {
    const m = new Map<number, User[]>()
    for (const u of allUsers) {
      for (const pid of u.profileIds) {
        const arr = m.get(pid) ?? []
        arr.push(u)
        m.set(pid, arr)
      }
    }
    return m
  }, [allUsers])

  async function loadAux() {
    const [cats, users, hs, appsList] = await Promise.all([
      api.blocklists.counts().catch(() => []),
      isAdmin ? api.users.list().catch(() => [] as User[]) : Promise.resolve([] as User[]),
      api.household.get().catch(() => null),
      api.apps.list().catch(() => [] as AppDetail[]),
    ])
    setCategories(cats.map(c => c.category))
    setAllUsers(users)
    setHousehold(hs)
    setApps([...appsList].sort((a, b) => a.app.name.localeCompare(b.app.name)))
  }

  async function reloadApps() {
    const list = await api.apps.list().catch(() => [] as AppDetail[])
    setApps([...list].sort((a, b) => a.app.name.localeCompare(b.app.name)))
  }

  useEffect(() => {
    loadAux().finally(() => setAuxLoading(false))
  }, [])

  // After a profile mutation, refetch user→profile links too so the
  // "linked users" badges stay in sync.
  async function refetchAux() {
    if (!isAdmin) return
    const users = await api.users.list().catch(() => [] as User[])
    setAllUsers(users)
  }

  function startNew() {
    setForm(emptyForm())
    setEditingId('new')
    setError(null)
  }

  function startEdit(pd: ProfileDetail) {
    setForm(detailToForm(pd))
    setEditingId(pd.profile.id)
    setError(null)
  }

  const updateMutation = useMutation({
    mutationFn: ({ id, body }: { id: number; body: UpsertProfileRequest }) =>
      api.profiles.update(id, body),
    onSuccess: () => Promise.all([invalidators.profileMutated(), refetchAux()]),
  })

  const createMutation = useMutation({
    mutationFn: (body: UpsertProfileRequest) => api.profiles.create(body),
    onSuccess: () => Promise.all([invalidators.profileMutated(), refetchAux()]),
  })

  const deleteMutation = useMutation({
    mutationFn: (id: number) => api.profiles.delete(id),
    onSuccess: () => Promise.all([invalidators.profileMutated(), refetchAux()]),
  })

  const setUsersMutation = useMutation({
    mutationFn: ({ id, userIds }: { id: number; userIds: number[] }) =>
      api.profiles.setUsers(id, userIds),
    onSuccess: () => Promise.all([invalidators.profiles(), refetchAux()]),
  })

  async function togglePause(pd: ProfileDetail) {
    // #406: setting `paused` explicitly via the full-profile PUT is
    // idempotent under concurrent clicks. The old POST /pause endpoint was
    // a server-side toggle that race-flipped under double-click / retry.
    // #423 tracks adding PATCH so we don't have to send the whole profile.
    const body = formToRequest(detailToForm(pd))
    body.paused = !pd.profile.paused
    await updateMutation.mutateAsync({ id: pd.profile.id, body })
  }

  async function save() {
    if (!form.name.trim()) { setError('Name is required'); return }
    setSaving(true)
    setError(null)
    try {
      const body = formToRequest(form)
      if (editingId === 'new') await createMutation.mutateAsync(body)
      else if (typeof editingId === 'number') await updateMutation.mutateAsync({ id: editingId, body })
      setEditingId(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to save')
    } finally {
      setSaving(false)
    }
  }

  function startEditUsers(profileId: number) {
    const current = usersByProfile.get(profileId) ?? []
    setEditingUsersFor(profileId)
    setUserPick(current.map(u => u.id))
    setError(null)
  }

  async function saveUserLinks() {
    if (editingUsersFor == null) return
    setSaving(true)
    setError(null)
    try {
      await setUsersMutation.mutateAsync({ id: editingUsersFor, userIds: userPick })
      setEditingUsersFor(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to update user links')
    } finally {
      setSaving(false)
    }
  }

  async function del(id: number, name: string) {
    if (!confirm(`Delete profile "${name}"? Devices using it will need a new profile.`)) return
    try {
      await deleteMutation.mutateAsync(id)
    } catch (e) {
      alert(e instanceof Error ? e.message : 'Failed to delete')
    }
  }

  if (loading) return <PageLoader />

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold text-white">Profiles</h1>
        {isAdmin && (
          <button
            onClick={startNew}
            className="bg-emerald-500 hover:bg-emerald-400 text-black text-sm font-semibold px-4 py-2 rounded-xl transition-colors"
          >
            + New Profile
          </button>
        )}
      </div>

      <div className="grid gap-4 md:grid-cols-2">
        {profiles.map(pd => (
          <div key={pd.profile.id} data-testid={`profile-card-${pd.profile.id}`} className={`bg-gray-900 rounded-2xl border border-gray-800 p-5 space-y-4 transition-shadow ${highlightId === pd.profile.id ? 'ring-2 ring-emerald-500/60' : ''}`}>
            <div className="flex items-start justify-between gap-2">
              <div>
                <h3 className="font-bold text-white text-lg">{pd.profile.name}</h3>
                {pd.profile.paused && (
                  <span className="text-xs text-red-400 bg-red-500/10 px-2 py-0.5 rounded mt-1 inline-block">Paused</span>
                )}
              </div>
              {isAdmin && (
                <div className="flex flex-wrap gap-2 shrink-0">
                  <button onClick={() => togglePause(pd)}
                    className={`text-xs px-3 py-1.5 rounded-lg border transition-colors ${
                      pd.profile.paused
                        ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20 hover:bg-emerald-500/20'
                        : 'bg-yellow-500/10 text-yellow-400 border-yellow-500/20 hover:bg-yellow-500/20'
                    }`}>
                    {pd.profile.paused ? '▶ Resume' : '⏸ Pause'}
                  </button>
                  <button onClick={() => startEdit(pd)}
                    className="text-xs text-gray-300 hover:text-white bg-gray-800 px-3 py-1.5 rounded-lg transition-colors">
                    Edit
                  </button>
                  <button onClick={() => startEditUsers(pd.profile.id)}
                    className="text-xs text-gray-300 hover:text-white bg-gray-800 px-3 py-1.5 rounded-lg transition-colors">
                    Edit users
                  </button>
                  <button onClick={() => del(pd.profile.id, pd.profile.name)}
                    className="text-xs text-red-400 hover:text-red-300 bg-red-500/10 px-3 py-1.5 rounded-lg transition-colors">
                    Delete
                  </button>
                </div>
              )}
            </div>

            {pd.profile.blockedCategories.length > 0 && (
              <div>
                <p className="text-xs text-gray-500 uppercase tracking-wider mb-2">Blocked categories</p>
                <div className="flex flex-wrap gap-2">
                  {pd.profile.blockedCategories.map(c => (
                    <span key={c} className="text-xs bg-red-500/10 text-red-400 px-2 py-1 rounded-lg font-mono">{c}</span>
                  ))}
                </div>
              </div>
            )}

            {(pd.profile.extraBlocked.length > 0 || pd.profile.extraAllowed.length > 0) && (
              <div className="grid grid-cols-2 gap-3">
                {pd.profile.extraBlocked.length > 0 && (
                  <div>
                    <p className="text-xs text-gray-500 uppercase tracking-wider mb-1">Blocked domains</p>
                    <p className="text-xs text-gray-400 font-mono">{pd.profile.extraBlocked.length} entr{pd.profile.extraBlocked.length === 1 ? 'y' : 'ies'}</p>
                  </div>
                )}
                {pd.profile.extraAllowed.length > 0 && (
                  <div>
                    <p className="text-xs text-gray-500 uppercase tracking-wider mb-1">Allowed domains</p>
                    <p className="text-xs text-gray-400 font-mono">{pd.profile.extraAllowed.length} entr{pd.profile.extraAllowed.length === 1 ? 'y' : 'ies'}</p>
                  </div>
                )}
              </div>
            )}

            {pd.timeLimit && (
              <p className="text-sm text-gray-400">
                Daily limit: <span className="text-white font-medium">{pd.timeLimit.dailyMinutes} min</span>
              </p>
            )}

            {pd.schedules.length > 0 && (
              <div>
                <p className="text-xs text-gray-500 uppercase tracking-wider mb-2">Schedules</p>
                {pd.schedules.map(s => (
                  <div key={s.id} className="flex justify-between text-sm bg-gray-800/50 rounded-lg px-3 py-2 mb-1">
                    <span className="text-gray-300">{s.name}</span>
                    <span className="text-yellow-400 font-mono text-xs">
                      {s.startLocal} → {s.endLocal} <span className="text-yellow-300/60">({s.tz})</span>
                    </span>
                  </div>
                ))}
              </div>
            )}

            <div data-testid={`profile-devices-${pd.profile.id}`}>
              <p className="text-xs text-gray-500 uppercase tracking-wider mb-2">Devices</p>
              {(devicesByProfile.get(pd.profile.id) ?? []).length === 0
                ? <p className="text-xs text-gray-600">No devices assigned.</p>
                : (
                  <div className="space-y-1">
                    {(devicesByProfile.get(pd.profile.id) ?? []).map(d => (
                      <div key={d.id} data-testid={`profile-device-${d.id}`}
                        className="flex justify-between text-sm bg-gray-800/50 rounded-lg px-3 py-2">
                        <span className="text-gray-300">{d.name}</span>
                        <span className="text-gray-500 font-mono text-xs">{d.mac}</span>
                      </div>
                    ))}
                  </div>
                )
              }
            </div>

            {isAdmin && (
              <div data-testid={`profile-users-${pd.profile.id}`}>
                <p className="text-xs text-gray-500 uppercase tracking-wider mb-2">Linked users</p>
                {(usersByProfile.get(pd.profile.id) ?? []).length === 0
                  ? <p className="text-xs text-gray-600">No users linked.</p>
                  : (
                    <div className="flex flex-wrap gap-2">
                      {(usersByProfile.get(pd.profile.id) ?? []).map(u => (
                        <span key={u.id} data-testid={`profile-user-${u.id}`}
                          className="text-xs bg-gray-800 text-gray-300 border border-gray-700 px-2 py-1 rounded-lg">
                          {u.username}
                          <span className={`ml-2 font-mono px-1.5 py-0.5 rounded ${
                            u.role === 'admin'
                              ? 'bg-emerald-500/10 text-emerald-400'
                              : u.role === 'adult'
                                ? 'bg-blue-500/10 text-blue-400'
                                : 'bg-yellow-500/10 text-yellow-400'
                          }`}>{u.role}</span>
                        </span>
                      ))}
                    </div>
                  )
                }
              </div>
            )}

            {pd.siteTimeLimits.length > 0 && (
              <div>
                <p className="text-xs text-gray-500 uppercase tracking-wider mb-2">Site Limits</p>
                {pd.siteTimeLimits.map(s => (
                  <div key={s.id} className="flex justify-between items-center text-sm bg-gray-800/50 rounded-lg px-3 py-2 mb-1">
                    <span className="text-gray-300">{s.label}</span>
                    <div className="flex items-center gap-2">
                      <span className="text-emerald-400 text-xs font-mono">{s.dailyMinutes}m · {s.domainPattern}</span>
                      <span className={`text-xs px-1.5 py-0.5 rounded font-mono ${
                        s.exemptFromDaily
                          ? 'bg-gray-700 text-gray-400'
                          : 'bg-amber-500/20 text-amber-400'
                      }`}>
                        {s.exemptFromDaily ? 'exempt' : 'counts'}
                      </span>
                    </div>
                  </div>
                ))}
              </div>
            )}
          </div>
        ))}
      </div>

      {editingUsersFor !== null && (
        <div className="fixed inset-0 bg-black/70 flex items-end sm:items-center justify-center z-50 p-4 overflow-y-auto"
          onClick={() => setEditingUsersFor(null)}>
          <div data-testid="edit-users-modal"
            className="bg-gray-900 rounded-2xl border border-gray-700 w-full max-w-lg my-8 p-6 space-y-5 max-h-[90vh] overflow-y-auto"
            onClick={e => e.stopPropagation()}>
            <h3 className="text-lg font-bold text-white">
              Edit users · {profiles.find(p => p.profile.id === editingUsersFor)?.profile.name ?? ''}
            </h3>
            {error && (
              <div className="bg-red-500/10 border border-red-500/30 text-red-300 text-sm rounded-xl px-4 py-2">
                {error}
              </div>
            )}
            {allUsers.length === 0
              ? <p className="text-sm text-gray-500">No users available.</p>
              : (
                <div className="flex flex-wrap gap-2">
                  {allUsers.map(u => {
                    const on = userPick.includes(u.id)
                    return (
                      <button key={u.id} type="button"
                        data-testid={`user-pick-${u.id}`}
                        onClick={() =>
                          setUserPick(on ? userPick.filter(x => x !== u.id) : [...userPick, u.id])
                        }
                        className={`text-sm px-3 py-1.5 rounded-lg border transition-colors ${
                          on
                            ? 'bg-emerald-500/20 text-emerald-300 border-emerald-500/40'
                            : 'bg-gray-800 text-gray-400 border-gray-700 hover:border-gray-600'
                        }`}>
                        {on ? '✓ ' : ''}{u.username} <span className="text-xs opacity-70">({u.role})</span>
                      </button>
                    )
                  })}
                </div>
              )
            }
            <div className="flex gap-3 pt-2">
              <button onClick={() => setEditingUsersFor(null)} disabled={saving}
                className="flex-1 py-3 rounded-xl bg-gray-800 text-gray-300 font-medium disabled:opacity-50">
                Cancel
              </button>
              <button onClick={saveUserLinks} disabled={saving}
                className="flex-1 py-3 rounded-xl bg-emerald-500 text-black font-semibold disabled:opacity-50">
                {saving ? 'Saving…' : 'Save'}
              </button>
            </div>
          </div>
        </div>
      )}

      {editingId !== null && (
        <ProfileEditor
          isNew={editingId === 'new'}
          profileId={typeof editingId === 'number' ? editingId : null}
          form={form}
          setForm={setForm}
          categories={categories}
          apps={apps}
          onAppsChanged={reloadApps}
          saving={saving}
          error={error}
          onCancel={() => setEditingId(null)}
          onSave={save}
          defaultTz={household?.dailyResetTz ?? browserTimezone()}
        />
      )}
    </div>
  )
}

function ProfileEditor({
  isNew, profileId, form, setForm, categories, apps, onAppsChanged,
  saving, error, onCancel, onSave, defaultTz,
}: {
  isNew: boolean
  profileId: number | null
  form: FormState
  setForm: (updater: (f: FormState) => FormState) => void
  categories: string[]
  apps: AppDetail[]
  onAppsChanged: () => void | Promise<void>
  saving: boolean
  error: string | null
  onCancel: () => void
  onSave: () => void
  defaultTz: string
}) {
  function toggleCat(c: string) {
    setForm(f => ({
      ...f,
      blockedCategories: f.blockedCategories.includes(c)
        ? f.blockedCategories.filter(x => x !== c)
        : [...f.blockedCategories, c],
    }))
  }

  function addSchedule() {
    // Default tz comes from the parent (household's daily-reset tz, or
    // browser tz fallback). Most homes want all schedules in the same zone.
    setForm(f => ({
      ...f,
      schedules: [
        ...f.schedules,
        { name: 'Bedtime', days: [...DAYS], startLocal: '21:00', endLocal: '07:00', tz: defaultTz },
      ],
    }))
  }

  function updateSchedule(i: number, patch: Partial<ScheduleRequest>) {
    setForm(f => ({
      ...f,
      schedules: f.schedules.map((s, idx) => idx === i ? { ...s, ...patch } : s),
    }))
  }

  function removeSchedule(i: number) {
    setForm(f => ({ ...f, schedules: f.schedules.filter((_, idx) => idx !== i) }))
  }

  function toggleDay(i: number, d: string) {
    setForm(f => ({
      ...f,
      schedules: f.schedules.map((s, idx) => idx !== i ? s : {
        ...s,
        days: s.days.includes(d) ? s.days.filter(x => x !== d) : [...s.days, d],
      }),
    }))
  }

  function addSiteLimit() {
    setForm(f => ({
      ...f,
      siteTimeLimits: [
        ...f.siteTimeLimits,
        { label: '', domainPattern: '', dailyMinutes: 30, exemptFromDaily: true },
      ],
    }))
  }

  function updateSiteLimit(i: number, patch: Partial<SiteTimeLimitRequest>) {
    setForm(f => ({
      ...f,
      siteTimeLimits: f.siteTimeLimits.map((s, idx) => idx === i ? { ...s, ...patch } : s),
    }))
  }

  function removeSiteLimit(i: number) {
    setForm(f => ({ ...f, siteTimeLimits: f.siteTimeLimits.filter((_, idx) => idx !== i) }))
  }

  return (
    <div className="fixed inset-0 bg-black/70 flex items-end sm:items-center justify-center z-50 p-4 overflow-y-auto">
      <div className="bg-gray-900 rounded-2xl border border-gray-700 w-full max-w-2xl my-8 p-6 space-y-5 max-h-[90vh] overflow-y-auto">
        <h3 className="text-lg font-bold text-white">{isNew ? 'New Profile' : 'Edit Profile'}</h3>

        {error && (
          <div className="bg-red-500/10 border border-red-500/30 text-red-300 text-sm rounded-xl px-4 py-2">
            {error}
          </div>
        )}

        <div>
          <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">Name</label>
          <input type="text" value={form.name}
            onChange={e => setForm(f => ({ ...f, name: e.target.value }))}
            placeholder="Kids"
            className="w-full bg-gray-950 border border-gray-700 rounded-xl px-4 py-3 text-white placeholder-gray-600 focus:outline-none focus:border-emerald-500" />
        </div>

        <label className="flex items-center gap-3 text-sm text-gray-300">
          <input type="checkbox" checked={form.paused}
            onChange={e => setForm(f => ({ ...f, paused: e.target.checked }))}
            className="w-4 h-4 accent-emerald-500" />
          Paused — blocks all internet traffic for devices on this profile.
        </label>

        {/* #385: per-profile failover when the router can't reach the API.
            Three modes (BlockAll / AllowAll / LastKnownGood) — the
            previous binary closed/open collapsed two semantically distinct
            behaviours into one. */}
        <div>
          <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">
            Failure mode
          </label>
          <div className="space-y-2">
            <label className="flex items-start gap-3 text-sm text-gray-300 cursor-pointer">
              <input
                type="radio"
                name="failureMode"
                data-testid="profile-failure-mode-block-all"
                checked={form.failureMode === 'block-all'}
                onChange={() => setForm(f => ({ ...f, failureMode: 'block-all' }))}
                className="mt-1 w-4 h-4 accent-emerald-500"
              />
              <span>
                <span className="font-medium text-white">Block all traffic</span>
                <span className="text-gray-500"> (recommended for children)</span>
                <span className="block text-xs text-gray-400 mt-0.5">
                  when the router can't reach the API for 5 minutes, drop all forwarded traffic for this profile's devices. The block page still loads.
                </span>
              </span>
            </label>
            <label className="flex items-start gap-3 text-sm text-gray-300 cursor-pointer">
              <input
                type="radio"
                name="failureMode"
                data-testid="profile-failure-mode-last-known-good"
                checked={form.failureMode === 'last-known-good'}
                onChange={() => setForm(f => ({ ...f, failureMode: 'last-known-good' }))}
                className="mt-1 w-4 h-4 accent-emerald-500"
              />
              <span>
                <span className="font-medium text-white">Last-known rules</span>
                <span className="text-gray-500"> (recommended for adults — default)</span>
                <span className="block text-xs text-gray-400 mt-0.5">
                  when the router can't reach the API, keep enforcing the cached snapshot exactly — categorical blocks, schedules, and time limits all still apply.
                </span>
              </span>
            </label>
            <label className="flex items-start gap-3 text-sm text-gray-300 cursor-pointer">
              <input
                type="radio"
                name="failureMode"
                data-testid="profile-failure-mode-allow-all"
                checked={form.failureMode === 'allow-all'}
                onChange={() => setForm(f => ({ ...f, failureMode: 'allow-all' }))}
                className="mt-1 w-4 h-4 accent-emerald-500"
              />
              <span>
                <span className="font-medium text-white">Allow all traffic</span>
                <span className="text-gray-500"> (only for trusted profiles)</span>
                <span className="block text-xs text-gray-400 mt-0.5">
                  when the router can't reach the API, clear every block for this profile's devices. The cached categorical / schedule rules stop applying.
                </span>
              </span>
            </label>
          </div>
        </div>

        {/* #751: how the profile's screen-time total handles two devices on
            the same profile being active in the same 5-min bucket. */}
        <div>
          <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">
            Cross-device overlap
          </label>
          <div className="space-y-2">
            <label className="flex items-start gap-3 text-sm text-gray-300 cursor-pointer">
              <input
                type="radio"
                name="crossDeviceOverlapMode"
                data-testid="profile-overlap-mode-sum"
                checked={form.crossDeviceOverlapMode === 'sum'}
                onChange={() => setForm(f => ({ ...f, crossDeviceOverlapMode: 'sum' }))}
                className="mt-1 w-4 h-4 accent-emerald-500"
              />
              <span>
                <span className="font-medium text-white">Count each device separately</span>
                <span className="text-gray-500"> (default)</span>
                <span className="block text-xs text-gray-400 mt-0.5">
                  add per-device totals. Two devices on this profile both active in the same 5-minute window count as 10 minutes against the daily cap.
                </span>
              </span>
            </label>
            <label className="flex items-start gap-3 text-sm text-gray-300 cursor-pointer">
              <input
                type="radio"
                name="crossDeviceOverlapMode"
                data-testid="profile-overlap-mode-dedup"
                checked={form.crossDeviceOverlapMode === 'dedup'}
                onChange={() => setForm(f => ({ ...f, crossDeviceOverlapMode: 'dedup' }))}
                className="mt-1 w-4 h-4 accent-emerald-500"
              />
              <span>
                <span className="font-medium text-white">Combine overlapping device usage</span>
                <span className="text-gray-500"> (one profile = one human)</span>
                <span className="block text-xs text-gray-400 mt-0.5">
                  union the per-device active windows. Two devices both active in the same 5-minute window count as 5 minutes against the daily cap. Right when one person carries an iPad and a phone for the same profile.
                </span>
              </span>
            </label>
          </div>
        </div>

        <div>
          <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">Blocked categories</label>
          {categories.length === 0
            ? <p className="text-sm text-gray-500">No categories loaded yet.</p>
            : (
              <div className="flex flex-wrap gap-2">
                {categories.map(c => {
                  const on = form.blockedCategories.includes(c)
                  return (
                    <button key={c} type="button" onClick={() => toggleCat(c)}
                      className={`text-xs font-mono px-3 py-1.5 rounded-lg border transition-colors ${
                        on
                          ? 'bg-red-500/20 text-red-300 border-red-500/40'
                          : 'bg-gray-800 text-gray-400 border-gray-700 hover:border-gray-600'
                      }`}>
                      {on ? '✓ ' : ''}{c}
                    </button>
                  )
                })}
              </div>
            )
          }
          {form.blockedCategories.filter(c => !categories.includes(c)).length > 0 && (
            <p className="text-xs text-yellow-400 mt-2">
              Also blocked (no longer in blocklist): {form.blockedCategories.filter(c => !categories.includes(c)).join(', ')}
            </p>
          )}
        </div>

        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          <div>
            <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">Extra blocked domains</label>
            <textarea value={form.extraBlocked}
              onChange={e => setForm(f => ({ ...f, extraBlocked: e.target.value }))}
              placeholder="One domain per line"
              rows={4}
              className="w-full bg-gray-950 border border-gray-700 rounded-xl px-4 py-3 text-white text-sm font-mono placeholder-gray-600 focus:outline-none focus:border-emerald-500" />
          </div>
          <div>
            <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">Extra allowed domains</label>
            <textarea value={form.extraAllowed}
              onChange={e => setForm(f => ({ ...f, extraAllowed: e.target.value }))}
              placeholder="One domain per line"
              rows={4}
              className="w-full bg-gray-950 border border-gray-700 rounded-xl px-4 py-3 text-white text-sm font-mono placeholder-gray-600 focus:outline-none focus:border-emerald-500" />
          </div>
        </div>

        <div>
          <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">Daily time limit (minutes)</label>
          <input type="number" min={0} value={form.timeLimit}
            onChange={e => setForm(f => ({ ...f, timeLimit: e.target.value }))}
            placeholder="Leave blank for unlimited"
            className="w-full bg-gray-950 border border-gray-700 rounded-xl px-4 py-3 text-white placeholder-gray-600 focus:outline-none focus:border-emerald-500" />
        </div>

        <div>
          <div className="flex items-center justify-between mb-2">
            <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wider">Schedules</label>
            <button type="button" onClick={addSchedule}
              className="text-xs text-emerald-400 hover:text-emerald-300">+ Add schedule</button>
          </div>
          {form.schedules.length === 0 && <p className="text-xs text-gray-500">No schedules.</p>}
          <div className="space-y-3">
            {form.schedules.map((s, i) => (
              <div key={i} className="bg-gray-950 border border-gray-700 rounded-xl p-3 space-y-2">
                <div className="flex gap-2">
                  <input type="text" value={s.name}
                    onChange={e => updateSchedule(i, { name: e.target.value })}
                    placeholder="Bedtime"
                    className="flex-1 bg-gray-900 border border-gray-700 rounded-lg px-3 py-2 text-white text-sm" />
                  <button type="button" onClick={() => removeSchedule(i)}
                    className="text-xs text-red-400 hover:text-red-300 bg-red-500/10 px-3 rounded-lg">Remove</button>
                </div>
                <div className="flex gap-2 items-center text-sm">
                  <input type="time" value={s.startLocal}
                    onChange={e => updateSchedule(i, { startLocal: e.target.value })}
                    data-testid={`schedule-${i}-start`}
                    className="bg-gray-900 border border-gray-700 rounded-lg px-3 py-2 text-white" />
                  <span className="text-gray-500">→</span>
                  <input type="time" value={s.endLocal}
                    onChange={e => updateSchedule(i, { endLocal: e.target.value })}
                    data-testid={`schedule-${i}-end`}
                    className="bg-gray-900 border border-gray-700 rounded-lg px-3 py-2 text-white" />
                </div>
                <div className="text-xs text-gray-400">
                  Timezone (the same wall-clock window applies every day, even across DST)
                </div>
                <TimezonePicker
                  value={s.tz}
                  onChange={tz => updateSchedule(i, { tz })}
                  testId={`schedule-${i}-tz`}
                />
                <div className="flex flex-wrap gap-1">
                  {DAYS.map(d => {
                    const on = s.days.includes(d)
                    return (
                      <button key={d} type="button" onClick={() => toggleDay(i, d)}
                        className={`text-xs px-2.5 py-1 rounded-lg border ${
                          on
                            ? 'bg-emerald-500/20 text-emerald-300 border-emerald-500/40'
                            : 'bg-gray-800 text-gray-500 border-gray-700'
                        }`}>{d}</button>
                    )
                  })}
                </div>
              </div>
            ))}
          </div>
        </div>

        <div>
          <div className="flex items-center justify-between mb-2">
            <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wider">Site time limits</label>
            <button type="button" onClick={addSiteLimit}
              className="text-xs text-emerald-400 hover:text-emerald-300">+ Add site limit</button>
          </div>
          {form.siteTimeLimits.length === 0 && <p className="text-xs text-gray-500">No site-specific limits.</p>}
          <div className="space-y-2">
            {form.siteTimeLimits.map((s, i) => (
              <div key={i} className="bg-gray-950 border border-gray-700 rounded-xl p-3 space-y-2">
                <div className="grid grid-cols-12 gap-2">
                  <input type="text" value={s.label}
                    onChange={e => updateSiteLimit(i, { label: e.target.value })}
                    placeholder="YouTube"
                    className="col-span-4 bg-gray-900 border border-gray-700 rounded-lg px-3 py-2 text-white text-sm" />
                  <input type="text" value={s.domainPattern}
                    onChange={e => updateSiteLimit(i, { domainPattern: e.target.value })}
                    placeholder="youtube.com"
                    className="col-span-5 bg-gray-900 border border-gray-700 rounded-lg px-3 py-2 text-white text-sm font-mono" />
                  <input type="number" min={0} value={s.dailyMinutes}
                    onChange={e => updateSiteLimit(i, { dailyMinutes: Number(e.target.value) || 0 })}
                    className="col-span-2 bg-gray-900 border border-gray-700 rounded-lg px-3 py-2 text-white text-sm" />
                  <button type="button" onClick={() => removeSiteLimit(i)}
                    className="col-span-1 text-xs text-red-400 hover:text-red-300 bg-red-500/10 rounded-lg">×</button>
                </div>
                <label className="flex items-center gap-2 text-xs text-gray-400 cursor-pointer select-none">
                  <input
                    type="checkbox"
                    checked={!s.exemptFromDaily}
                    onChange={e => updateSiteLimit(i, { exemptFromDaily: !e.target.checked })}
                    className="w-3.5 h-3.5 accent-amber-500"
                  />
                  <span>
                    Counts toward daily limit
                    {!s.exemptFromDaily && (
                      <span className="ml-1 text-amber-400">(usage reduces overall remaining time)</span>
                    )}
                  </span>
                </label>
              </div>
            ))}
          </div>
        </div>

        {/* #767 — apps picker. Lives alongside the legacy extraBlocked /
            extraAllowed / siteTimeLimits inputs above; #764 will remove the
            legacy fields once apps cover everything. */}
        <AppsSection
          profileId={profileId}
          isNew={isNew}
          apps={apps}
          onChanged={onAppsChanged}
        />

        <div className="flex gap-3 pt-2 sticky bottom-0 bg-gray-900">
          <button onClick={onCancel} disabled={saving}
            className="flex-1 py-3 rounded-xl bg-gray-800 text-gray-300 font-medium disabled:opacity-50">
            Cancel
          </button>
          <button onClick={onSave} disabled={saving}
            className="flex-1 py-3 rounded-xl bg-emerald-500 text-black font-semibold disabled:opacity-50">
            {saving ? 'Saving…' : 'Save'}
          </button>
        </div>
      </div>
    </div>
  )
}

function findAssignment(app: AppDetail, profileId: number | null): AppPolicyAssignment | null {
  if (profileId == null) return null
  return app.assignments.find(a => a.profileId === profileId) ?? null
}

function AppsSection({ profileId, isNew, apps, onChanged }: {
  profileId: number | null
  isNew: boolean
  apps: AppDetail[]
  onChanged: () => void | Promise<void>
}) {
  return (
    <div data-testid="apps-section">
      <div className="flex items-center justify-between mb-2">
        <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wider">
          Apps
        </label>
        <Link
          to="/apps"
          data-testid="apps-section-manage-link"
          className="text-xs text-emerald-400 hover:text-emerald-300"
        >
          Manage apps →
        </Link>
      </div>
      {isNew || profileId == null
        ? (
            <p className="text-xs text-gray-500">
              Save this profile first to assign apps.
            </p>
          )
        : apps.length === 0
          ? (
              <p className="text-xs text-gray-500">
                No apps yet.{' '}
                <Link
                  to="/apps"
                  data-testid="apps-section-empty-link"
                  className="text-emerald-400 hover:text-emerald-300 underline"
                >
                  Create one
                </Link>
                {' '}to block, allow, or time-limit a group of hosts.
              </p>
            )
          : (
              <div className="space-y-2">
                {apps.map(a => (
                  <AppRow
                    key={a.app.id}
                    app={a}
                    profileId={profileId}
                    onChanged={onChanged}
                  />
                ))}
              </div>
            )
      }
    </div>
  )
}

function AppRow({ app, profileId, onChanged }: {
  app: AppDetail
  profileId: number
  onChanged: () => void | Promise<void>
}) {
  const current = findAssignment(app, profileId)
  const [minutesDraft, setMinutesDraft] = useState<string>(() =>
    current?.mode === 'time_limited' && current.dailyMinutes != null
      ? String(current.dailyMinutes)
      : '',
  )
  const [busy, setBusy] = useState(false)
  const [localError, setLocalError] = useState<string | null>(null)

  async function apply(mode: AppMode, dailyMinutes: number | null) {
    setBusy(true)
    setLocalError(null)
    try {
      await api.apps.setPolicy(app.app.id, profileId, {
        mode,
        dailyMinutes,
      })
      await onChanged()
    } catch (e) {
      setLocalError(e instanceof Error ? e.message : 'Failed to update')
    } finally {
      setBusy(false)
    }
  }

  async function clear() {
    setBusy(true)
    setLocalError(null)
    try {
      await api.apps.deletePolicy(app.app.id, profileId)
      setMinutesDraft('')
      await onChanged()
    } catch (e) {
      setLocalError(e instanceof Error ? e.message : 'Failed to clear')
    } finally {
      setBusy(false)
    }
  }

  async function applyTimeLimited() {
    const n = Number(minutesDraft)
    if (!Number.isFinite(n) || n <= 0) {
      setLocalError('Enter minutes > 0')
      return
    }
    await apply('time_limited', n)
  }

  const mode = current?.mode ?? null
  const baseBtn = 'text-xs px-2.5 py-1 rounded-lg border transition-colors disabled:opacity-50'
  const off = 'bg-gray-800 text-gray-400 border-gray-700 hover:border-gray-600'
  const onBlocked = 'bg-red-500/20 text-red-300 border-red-500/40'
  const onAllowed = 'bg-emerald-500/20 text-emerald-300 border-emerald-500/40'
  const onTimeLimited = 'bg-amber-500/20 text-amber-300 border-amber-500/40'

  return (
    <div
      data-testid={`app-row-${app.app.id}`}
      className="bg-gray-950 border border-gray-700 rounded-xl p-3 space-y-2"
    >
      <div className="flex items-center gap-3">
        <span className="text-xl w-7 text-center" aria-hidden>{app.app.icon || '◳'}</span>
        <div className="flex-1 min-w-0">
          <p className="text-sm text-white font-medium truncate">{app.app.name}</p>
          <p className="text-xs text-gray-500 font-mono truncate">{app.hosts.length} host{app.hosts.length === 1 ? '' : 's'}</p>
        </div>
        {mode != null && (
          <button
            type="button"
            data-testid={`app-row-${app.app.id}-clear`}
            disabled={busy}
            onClick={clear}
            className={`${baseBtn} ${off}`}
          >Clear</button>
        )}
      </div>
      <div className="flex flex-wrap gap-2 items-center">
        <button
          type="button"
          data-testid={`app-row-${app.app.id}-block`}
          disabled={busy}
          onClick={() => apply('blocked', null)}
          className={`${baseBtn} ${mode === 'blocked' ? onBlocked : off}`}
        >{mode === 'blocked' ? '✓ ' : ''}Block</button>
        <button
          type="button"
          data-testid={`app-row-${app.app.id}-allow`}
          disabled={busy}
          onClick={() => apply('allowed', null)}
          className={`${baseBtn} ${mode === 'allowed' ? onAllowed : off}`}
        >{mode === 'allowed' ? '✓ ' : ''}Allow</button>
        <div className="flex items-center gap-1">
          <input
            type="number"
            min={1}
            value={minutesDraft}
            onChange={e => setMinutesDraft(e.target.value)}
            placeholder="min"
            data-testid={`app-row-${app.app.id}-minutes`}
            className="w-16 bg-gray-900 border border-gray-700 rounded-lg px-2 py-1 text-white text-xs"
          />
          <button
            type="button"
            data-testid={`app-row-${app.app.id}-time-limit`}
            disabled={busy}
            onClick={applyTimeLimited}
            className={`${baseBtn} ${mode === 'time_limited' ? onTimeLimited : off}`}
          >
            {mode === 'time_limited' && current?.dailyMinutes != null
              ? `✓ ${current.dailyMinutes}m / day`
              : 'Time-limit'}
          </button>
        </div>
      </div>
      {localError && (
        <p className="text-xs text-red-400" data-testid={`app-row-${app.app.id}-error`}>{localError}</p>
      )}
    </div>
  )
}
