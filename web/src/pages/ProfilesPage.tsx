import { useEffect, useMemo, useRef, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { api } from '@/api/client'
import { useProfiles, useDevices, useInvalidators, useProfileUsageByApp, useTimeStatusSummary } from '@/api/queries'
import { useAuth } from '@/hooks/useAuth'
import { useDebouncedSave, type SaveStatus } from '@/hooks/useDebouncedSave'
import type {
  AppDetail, AppMode, AppPolicyAssignment,
  CrossDeviceOverlapMode, Device, FailureMode, HouseholdSettings, ProfileDetail,
  ProfileTimeSummary,
  ScheduleRequest, UpsertProfileRequest, User,
} from '@/types/api'
import { TimezonePicker, browserTimezone } from '@/components/TimezonePicker'
import { AppIcon } from '@/components/AppIcon'
import { ProfileTimelineChart } from '@/components/usage/ProfileTimelineChart'
import { PageLoader } from './DashboardPage'
import { formatMins } from '@/lib/timeFormat'

const DAYS = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun'] as const

interface FormState {
  name: string
  blockedCategories: string[]
  paused: boolean
  timeLimit: string
  schedules: ScheduleRequest[]
  failureMode: FailureMode
  crossDeviceOverlapMode: CrossDeviceOverlapMode
}

function detailToForm(pd: ProfileDetail): FormState {
  return {
    name: pd.profile.name,
    blockedCategories: pd.profile.blockedCategories,
    paused: pd.profile.paused,
    timeLimit: pd.timeLimit ? String(pd.timeLimit.dailyMinutes) : '',
    schedules: pd.schedules.map(s => ({
      name: s.name, days: s.days, startLocal: s.startLocal, endLocal: s.endLocal, tz: s.tz,
    })),
    failureMode: pd.profile.failureMode,
    crossDeviceOverlapMode: pd.profile.crossDeviceOverlapMode,
  }
}

function formToRequest(f: FormState): UpsertProfileRequest {
  const tl = f.timeLimit.trim() === '' ? null : Number(f.timeLimit)
  return {
    name: f.name.trim(),
    blockedCategories: f.blockedCategories,
    paused: f.paused,
    timeLimit: tl !== null && Number.isFinite(tl) ? tl : null,
    schedules: f.schedules,
    failureMode: f.failureMode,
    crossDeviceOverlapMode: f.crossDeviceOverlapMode,
  }
}

// #972: chip states reflect the at-a-glance "what's this profile doing right
// now" answer. Schedule-active check is approximated locally from the cached
// ProfileDetail.schedules; subsection #973 may move this server-side.
type PauseChip = 'active' | 'paused-manual' | 'paused-schedule' | 'time-exceeded'

const WEEKDAY_SHORT_TO_KEY: Record<string, string> = {
  Mon: 'mon', Tue: 'tue', Wed: 'wed', Thu: 'thu', Fri: 'fri', Sat: 'sat', Sun: 'sun',
}

function isScheduleActiveNow(
  s: { days: string[]; startLocal: string; endLocal: string; tz: string },
  now: Date = new Date(),
): boolean {
  let weekday = ''
  let hh = '00'
  let mm = '00'
  try {
    const fmt = new Intl.DateTimeFormat('en-US', {
      timeZone: s.tz, weekday: 'short', hour: '2-digit', minute: '2-digit', hour12: false,
    })
    for (const p of fmt.formatToParts(now)) {
      if (p.type === 'weekday') weekday = WEEKDAY_SHORT_TO_KEY[p.value] ?? ''
      else if (p.type === 'hour') hh = p.value === '24' ? '00' : p.value
      else if (p.type === 'minute') mm = p.value
    }
  } catch {
    return false
  }
  const minOfDay = parseInt(hh, 10) * 60 + parseInt(mm, 10)
  const [sh, sm] = s.startLocal.split(':').map(Number)
  const [eh, em] = s.endLocal.split(':').map(Number)
  if ([sh, sm, eh, em].some(n => !Number.isFinite(n))) return false
  const start = sh * 60 + sm
  const end   = eh * 60 + em
  if (start === end) return false
  // Same-day window — only active if today's weekday is in the day-set.
  if (start < end) return s.days.includes(weekday) && minOfDay >= start && minOfDay < end
  // Wraps midnight (e.g. 21:00 → 07:00). The "before-midnight" half belongs to
  // the start day; the "after-midnight" half belongs to the next day.
  const prevWeekday = previousWeekday(weekday)
  if (minOfDay >= start) return s.days.includes(weekday)
  if (minOfDay <  end)   return s.days.includes(prevWeekday)
  return false
}

const ORDER = ['mon', 'tue', 'wed', 'thu', 'fri', 'sat', 'sun']
function previousWeekday(k: string): string {
  const i = ORDER.indexOf(k)
  if (i < 0) return ''
  return ORDER[(i + 6) % 7]
}

function computeChip(pd: ProfileDetail, summary: ProfileTimeSummary | undefined): PauseChip {
  if (pd.profile.paused) return 'paused-manual'
  if (summary && summary.remainingMins != null && summary.remainingMins <= 0) return 'time-exceeded'
  if (pd.schedules.some(s => isScheduleActiveNow(s))) return 'paused-schedule'
  return 'active'
}

const CHIP_LABEL: Record<PauseChip, string> = {
  'active':          'Active',
  'paused-manual':   'Paused',
  'paused-schedule': 'Paused (schedule)',
  'time-exceeded':   'Time exceeded',
}

const CHIP_CLASS: Record<PauseChip, string> = {
  'active':          'bg-emerald-500/10 text-emerald-400 border-emerald-500/20',
  'paused-manual':   'bg-yellow-500/10 text-yellow-400 border-yellow-500/20',
  'paused-schedule': 'bg-blue-500/10 text-blue-400 border-blue-500/20',
  'time-exceeded':   'bg-red-500/10 text-red-400 border-red-500/20',
}

export function ProfilesPage() {
  const { isAdmin } = useAuth()
  const invalidators = useInvalidators()
  const profilesQuery = useProfiles()
  const devicesQuery  = useDevices()
  const summariesQuery = useTimeStatusSummary()
  const profiles  = profilesQuery.data  ?? []
  const devices   = devicesQuery.data   ?? []
  const summaries = summariesQuery.data ?? []
  const [allUsers, setAllUsers] = useState<User[]>([])
  const [auxLoading, setAuxLoading] = useState(true)
  const loading = profilesQuery.isPending || devicesQuery.isPending || auxLoading
  // #978 — the old "+ New Profile" modal (ProfileEditor) is gone; new profiles
  // are created from a tiny name-only inline form and then fleshed out via the
  // inline subsections on the expanded card. `creatingName` doubles as the
  // form's open/closed state (null = closed) and its current draft value.
  const [creatingName, setCreatingName] = useState<string | null>(null)
  const [creatingError, setCreatingError] = useState<string | null>(null)
  const [creatingSaving, setCreatingSaving] = useState(false)
  // #977 — per-profile user-link autosave. Track in-flight toggles so the
  // chip can show a spinner and refuse double-clicks without blocking
  // unrelated profiles.
  const [pendingUserLinks, setPendingUserLinks] = useState<Set<string>>(new Set())
  const [userLinkErrorByProfile, setUserLinkErrorByProfile] = useState<Map<number, string>>(new Map())
  const [household, setHousehold] = useState<HouseholdSettings | null>(null)
  const [apps, setApps] = useState<AppDetail[]>([])
  // #972 — collapse-by-default; toggle state lives in-memory only. Persistence
  // across reloads is a deferred enhancement; the design doc calls this out.
  const [expanded, setExpanded] = useState<Set<number>>(new Set())
  const toggleExpanded = (pid: number) => setExpanded(prev => {
    const next = new Set(prev)
    if (next.has(pid)) next.delete(pid); else next.add(pid)
    return next
  })

  // #972 — +Time mutation reused from the screen-time page. #946-fixed pattern:
  // invalidate the full ['time','status'] subtree so both the summary row and
  // any expanded detail re-render with the new remaining minutes.
  const [extProfileId, setExtProfileId] = useState<number | null>(null)
  const [extMins, setExtMins] = useState(30)
  const [extNote, setExtNote] = useState('')
  const grantMutation = useMutation({
    mutationFn: (vars: { profileId: number; extraMinutes: number; note: string | null }) =>
      api.time.grantExtension(vars),
    onSuccess: () => {
      setExtProfileId(null)
      setExtMins(30)
      setExtNote('')
      return invalidators.timeStatus()
    },
  })

  // #298: LogsPage links to `/profiles?id=...`; scroll + highlight the
  // matching profile card so the parent sees what they clicked from logs.
  // Also auto-expand it so the linked content is visible immediately.
  const [params] = useSearchParams()
  const queryId = params.get('id')
  const [highlightId, setHighlightId] = useState<number | null>(null)
  useEffect(() => {
    if (!queryId || profiles.length === 0) return
    const id = Number(queryId)
    if (!Number.isFinite(id) || !profiles.some(p => p.profile.id === id)) return
    setHighlightId(id)
    setExpanded(prev => prev.has(id) ? prev : new Set(prev).add(id))
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

  const summaryByProfile = useMemo(() => {
    const m = new Map<number, ProfileTimeSummary>()
    for (const s of summaries) m.set(s.profileId, s)
    return m
  }, [summaries])

  async function loadAux() {
    // #978 — the old modal pulled the blocklist category list for its picker;
    // the inline app-policy subsection owns that surface now, so we don't need
    // to fan out to /blocklists here anymore.
    const [users, hs, appsList] = await Promise.all([
      isAdmin ? api.users.list().catch(() => [] as User[]) : Promise.resolve([] as User[]),
      api.household.get().catch(() => null),
      api.apps.list().catch(() => [] as AppDetail[]),
    ])
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

  async function refetchAux() {
    if (!isAdmin) return
    const users = await api.users.list().catch(() => [] as User[])
    setAllUsers(users)
  }

  // #973: inline subsections replaced the "Edit existing profile" modal
  // escape-hatch field-by-field. #978: the "+ New Profile" modal is gone too;
  // the inline create form below drops the new profile with defaults so the
  // operator immediately edits it via the same inline surfaces.

  const updateMutation = useMutation({
    mutationFn: ({ id, body }: { id: number; body: UpsertProfileRequest }) =>
      api.profiles.update(id, body),
    onSuccess: () => Promise.all([invalidators.profileMutated(), refetchAux()]),
  })

  const createMutation = useMutation({
    mutationFn: (body: UpsertProfileRequest) => api.profiles.create(body),
    onSuccess: (created) => {
      // #978 — auto-expand the freshly created card so the operator can fill
      // in the rest inline; the modal used to do this implicitly by closing
      // onto the (newly-loaded) list.
      setExpanded(prev => new Set(prev).add(created.id))
      return Promise.all([invalidators.profileMutated(), refetchAux()])
    },
  })

  function startNew() {
    setCreatingName('')
    setCreatingError(null)
  }
  function cancelNew() {
    setCreatingName(null)
    setCreatingError(null)
  }
  async function saveNew() {
    const trimmed = (creatingName ?? '').trim()
    if (!trimmed) { setCreatingError('Name is required'); return }
    setCreatingSaving(true)
    setCreatingError(null)
    try {
      // #978 — defaults mirror the old modal's emptyForm() so the new profile
      // boots in the same safe-by-default state (LastKnownGood failover, Sum
      // overlap, no blocked categories, no schedules, no daily cap).
      await createMutation.mutateAsync({
        name: trimmed,
        blockedCategories: [],
        paused: false,
        timeLimit: null,
        schedules: [],
        failureMode: 'last-known-good',
        crossDeviceOverlapMode: 'sum',
      })
      setCreatingName(null)
    } catch (e) {
      setCreatingError(e instanceof Error ? e.message : 'Failed to create')
    } finally {
      setCreatingSaving(false)
    }
  }

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
    // idempotent under concurrent clicks. #423 tracks adding PATCH so we
    // don't have to send the whole profile.
    const body = formToRequest(detailToForm(pd))
    body.paused = !pd.profile.paused
    await updateMutation.mutateAsync({ id: pd.profile.id, body })
  }

  async function toggleUserLink(profileId: number, userId: number) {
    const key = `${profileId}:${userId}`
    if (pendingUserLinks.has(key)) return
    const current = (usersByProfile.get(profileId) ?? []).map(u => u.id)
    const next = current.includes(userId)
      ? current.filter(x => x !== userId)
      : [...current, userId]
    setPendingUserLinks(prev => {
      const s = new Set(prev); s.add(key); return s
    })
    setUserLinkErrorByProfile(prev => {
      if (!prev.has(profileId)) return prev
      const m = new Map(prev); m.delete(profileId); return m
    })
    try {
      await setUsersMutation.mutateAsync({ id: profileId, userIds: next })
    } catch (e) {
      const msg = e instanceof Error ? e.message : 'Failed to update users'
      setUserLinkErrorByProfile(prev => {
        const m = new Map(prev); m.set(profileId, msg); return m
      })
    } finally {
      setPendingUserLinks(prev => {
        const s = new Set(prev); s.delete(key); return s
      })
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

  const granting = grantMutation.isPending
  async function grantExtension(profileId: number) {
    await grantMutation.mutateAsync({
      profileId,
      extraMinutes: extMins,
      note: extNote || null,
    })
  }

  if (loading) return <PageLoader />

  const profileName = (id: number | null) =>
    id == null ? '' : profiles.find(p => p.profile.id === id)?.profile.name ?? ''

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h1 className="text-xl font-bold text-white">Profiles</h1>
        {isAdmin && creatingName === null && (
          <button
            onClick={startNew}
            className="bg-emerald-500 hover:bg-emerald-400 text-black text-sm font-semibold px-4 py-2 rounded-xl transition-colors"
          >
            + New Profile
          </button>
        )}
      </div>

      {/* #978 — inline name-only new-profile form. Replaces the old
          ProfileEditor modal; everything else is filled in via the inline
          subsections on the new profile's expanded card. */}
      {isAdmin && creatingName !== null && (
        <div
          data-testid="profile-create-form"
          className="bg-gray-900 rounded-2xl border border-emerald-500/30 p-4 space-y-3"
        >
          <div className="flex items-center justify-between">
            <h2 className="text-sm font-semibold text-white">New Profile</h2>
          </div>
          {creatingError && (
            <div
              data-testid="profile-create-error"
              className="bg-red-500/10 border border-red-500/30 text-red-300 text-sm rounded-xl px-4 py-2"
            >
              {creatingError}
            </div>
          )}
          <div className="flex gap-2 items-stretch">
            <input
              type="text"
              value={creatingName}
              onChange={e => setCreatingName(e.target.value)}
              onKeyDown={e => {
                if (e.key === 'Enter' && !creatingSaving) { e.preventDefault(); saveNew() }
                if (e.key === 'Escape') { e.preventDefault(); cancelNew() }
              }}
              autoFocus
              placeholder="Kids"
              data-testid="profile-create-name"
              className="flex-1 bg-gray-950 border border-gray-700 rounded-xl px-4 py-2.5 text-white placeholder-gray-600 focus:outline-none focus:border-emerald-500"
            />
            <button
              onClick={cancelNew}
              disabled={creatingSaving}
              data-testid="profile-create-cancel"
              className="px-4 py-2.5 rounded-xl bg-gray-800 text-gray-300 text-sm font-medium hover:bg-gray-700 disabled:opacity-50 transition-colors"
            >
              Cancel
            </button>
            <button
              onClick={saveNew}
              disabled={creatingSaving}
              data-testid="profile-create-save"
              className="px-4 py-2.5 rounded-xl bg-emerald-500 text-black text-sm font-semibold hover:bg-emerald-400 disabled:opacity-50 transition-colors"
            >
              {creatingSaving ? 'Creating…' : 'Create'}
            </button>
          </div>
          <p className="text-[11px] text-gray-500">
            Fill in time limits, schedules, apps, and users from the new
            profile's expanded card once it's created.
          </p>
        </div>
      )}

      <div className="space-y-3">
        {profiles.map(pd => (
          <ProfileShellRow
            key={pd.profile.id}
            pd={pd}
            summary={summaryByProfile.get(pd.profile.id)}
            devices={devicesByProfile.get(pd.profile.id) ?? []}
            allDevices={devices}
            users={usersByProfile.get(pd.profile.id) ?? []}
            apps={apps}
            allUsers={allUsers}
            isAdmin={isAdmin}
            expanded={expanded.has(pd.profile.id)}
            highlight={highlightId === pd.profile.id}
            defaultTz={household?.dailyResetTz ?? browserTimezone()}
            onToggle={() => toggleExpanded(pd.profile.id)}
            onDelete={() => del(pd.profile.id, pd.profile.name)}
            onTogglePause={() => togglePause(pd)}
            onGrantTime={() => setExtProfileId(pd.profile.id)}
            onAppsChanged={reloadApps}
            onProfileChanged={() => invalidators.profileMutated()}
            updateProfile={(body) => updateMutation.mutateAsync({ id: pd.profile.id, body })}
            onToggleUserLink={(userId) => toggleUserLink(pd.profile.id, userId)}
            pendingUserLinks={pendingUserLinks}
            userLinkError={userLinkErrorByProfile.get(pd.profile.id) ?? null}
          />
        ))}
      </div>

      {/* #972 — +Time modal (originally lifted from the deleted TimePage in
          #978) so the +Time button in the collapsed summary works on
          /profiles too. */}
      {extProfileId !== null && (
        <div className="fixed inset-0 bg-black/70 flex items-end sm:items-center justify-center z-50 p-4">
          <div className="bg-gray-900 rounded-2xl border border-gray-700 w-full max-w-sm p-6 space-y-4">
            <h3 className="text-lg font-bold text-white">Grant Extra Time</h3>
            <p className="text-sm text-gray-400">{profileName(extProfileId)}</p>

            <div>
              <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">
                Extra minutes
              </label>
              <div className="flex gap-2">
                {[15, 30, 45, 60].map(m => (
                  <button
                    key={m}
                    onClick={() => setExtMins(m)}
                    className={`flex-1 py-2 rounded-lg text-sm font-medium transition-colors ${
                      extMins === m
                        ? 'bg-emerald-500 text-black'
                        : 'bg-gray-800 text-gray-400 hover:bg-gray-700'
                    }`}
                  >
                    {m}m
                  </button>
                ))}
              </div>
            </div>

            <div>
              <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">
                Note (optional)
              </label>
              <input
                type="text"
                value={extNote}
                onChange={e => setExtNote(e.target.value)}
                placeholder="Homework finished, good behavior…"
                className="w-full bg-gray-950 border border-gray-700 rounded-xl px-4 py-3 text-white text-sm placeholder-gray-600 focus:outline-none focus:border-emerald-500"
              />
            </div>

            <div className="flex gap-3 pt-2">
              <button
                onClick={() => setExtProfileId(null)}
                className="flex-1 py-3 rounded-xl bg-gray-800 text-gray-300 font-medium hover:bg-gray-700 transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={() => grantExtension(extProfileId)}
                disabled={granting}
                className="flex-1 py-3 rounded-xl bg-emerald-500 text-black font-semibold hover:bg-emerald-400 disabled:opacity-50 transition-colors"
              >
                {granting ? 'Granting…' : `Grant ${extMins}m`}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  )
}

// #972 — collapsed accordion row for the merged Profiles page. Header carries
// the summary (name, used/cap + bar, pause chip, +Time). Expanded body holds
// the inline subsections (#973-#977) that replaced the old per-profile modal,
// plus the read-only devices listing and the Delete control.
function ProfileShellRow({
  pd, summary, devices, allDevices, users, apps, allUsers, isAdmin, expanded, highlight, defaultTz,
  onToggle, onDelete, onTogglePause, onGrantTime,
  onAppsChanged, onProfileChanged, updateProfile,
  onToggleUserLink, pendingUserLinks, userLinkError,
}: {
  pd: ProfileDetail
  summary: ProfileTimeSummary | undefined
  devices: Device[]
  allDevices: Device[]
  users: User[]
  apps: AppDetail[]
  allUsers: User[]
  isAdmin: boolean
  expanded: boolean
  highlight: boolean
  defaultTz: string
  onToggle: () => void
  onDelete: () => void
  onTogglePause: () => void
  onGrantTime: () => void
  onAppsChanged: () => void | Promise<void>
  onProfileChanged: () => void | Promise<unknown>
  updateProfile: (body: UpsertProfileRequest) => Promise<unknown>
  onToggleUserLink: (userId: number) => void
  pendingUserLinks: Set<string>
  userLinkError: string | null
}) {
  const linkedUserIds = useMemo(() => new Set(users.map(u => u.id)), [users])
  const chip = computeChip(pd, summary)
  const hasLimit = summary?.dailyLimitMins != null
  const usedMins = summary?.usedMins ?? 0
  const limitBase = hasLimit ? (summary!.dailyLimitMins ?? 0) + (summary!.extensionMins ?? 0) : 0
  const pct = hasLimit && limitBase > 0
    ? Math.min(100, Math.round((usedMins / limitBase) * 100))
    : 0
  const overLimit = chip === 'time-exceeded'

  // #973 — inline name editor lives in the card header (no redundant
  // "Name" subsection). When the card is expanded and the operator is an
  // admin, the title-line spot becomes an unobtrusive editable input;
  // debounced autosave does a full-profile PUT because PATCH /profiles/:id
  // (#423) hasn't shipped yet.
  const [editingName, setEditingName] = useState(pd.profile.name)
  useEffect(() => { setEditingName(pd.profile.name) }, [pd.profile.name])
  const { status: nameStatus, error: nameError } = useDebouncedSave(
    editingName,
    async (next: string) => {
      const trimmed = next.trim()
      if (!trimmed) throw new Error('Name is required')
      await updateProfile({
        name: trimmed,
        blockedCategories: pd.profile.blockedCategories,
        paused: pd.profile.paused,
        timeLimit: pd.timeLimit ? pd.timeLimit.dailyMinutes : null,
        schedules: pd.schedules.map(s => ({
          name: s.name, days: s.days, startLocal: s.startLocal, endLocal: s.endLocal, tz: s.tz,
        })),
        failureMode: pd.profile.failureMode,
        crossDeviceOverlapMode: pd.profile.crossDeviceOverlapMode,
      })
      await onProfileChanged()
    },
    { key: pd.profile.id },
  )

  return (
    <div
      data-testid={`profile-card-${pd.profile.id}`}
      className={`bg-gray-900 rounded-2xl border transition-shadow ${
        overLimit ? 'border-red-500/40' : 'border-gray-800'
      } ${highlight ? 'ring-2 ring-emerald-500/60' : ''}`}
    >
      <div className="flex items-center gap-2 px-5 py-4">
        <button
          type="button"
          onClick={onToggle}
          aria-expanded={expanded}
          aria-label={`${expanded ? 'Collapse' : 'Expand'} ${pd.profile.name}`}
          data-testid={`profile-row-toggle-${pd.profile.id}`}
          className="text-gray-500 shrink-0"
        >
          <span className={`inline-block transition-transform ${expanded ? 'rotate-90' : ''}`}>▸</span>
        </button>
        {expanded && isAdmin ? (
          <div className="flex-1 min-w-0 flex items-center gap-2">
            <input
              type="text"
              value={editingName}
              onChange={e => setEditingName(e.target.value)}
              data-testid={`profile-name-input-${pd.profile.id}`}
              aria-label="Profile name"
              className="flex-1 min-w-0 font-semibold text-white text-lg bg-transparent border-b border-transparent hover:border-gray-700 focus:border-emerald-500 focus:outline-none px-0 py-0.5"
            />
            <SaveStatusBadge
              status={nameStatus}
              error={nameError}
              testId={`profile-name-status-${pd.profile.id}`}
            />
          </div>
        ) : (
          <button
            type="button"
            onClick={onToggle}
            className="flex-1 text-left min-w-0"
          >
            <span className="font-semibold text-white text-lg truncate">{pd.profile.name}</span>
          </button>
        )}

        <div className="flex items-center gap-3 shrink-0">
          {/* Used / cap with a thin inline progress bar */}
          <div
            data-testid={`profile-summary-time-${pd.profile.id}`}
            className="hidden sm:flex flex-col items-end min-w-[7rem]"
          >
            {/* #975: surface granted +Time extensions in the cap text so a
                fresh grant is visible in the summary row. The denominator is
                base + extension (matches the bar denominator below); a
                "(+Xm)" suffix calls out how much of that is a grant so the
                operator can tell at a glance how much extra is in play. */}
            <span className="text-xs font-mono text-gray-300">
              {formatMins(usedMins)}
              {hasLimit ? ` / ${formatMins(limitBase)}` : ''}
              {hasLimit && (summary!.extensionMins ?? 0) > 0 && (
                <span className="text-emerald-400"> (+{formatMins(summary!.extensionMins ?? 0)})</span>
              )}
            </span>
            {hasLimit && (
              <div className="w-24 h-1 bg-gray-800 rounded-full overflow-hidden mt-1">
                <div
                  className={`h-full rounded-full ${overLimit ? 'bg-red-500' : 'bg-emerald-500'}`}
                  style={{ width: `${pct}%` }}
                />
              </div>
            )}
          </div>

          <span
            data-testid={`profile-pause-chip-${pd.profile.id}`}
            data-chip={chip}
            className={`text-xs px-2 py-1 rounded-lg border ${CHIP_CLASS[chip]}`}
          >
            {CHIP_LABEL[chip]}
          </span>

          {isAdmin && hasLimit && (
            <button
              type="button"
              onClick={onGrantTime}
              data-testid={`profile-row-grant-${pd.profile.id}`}
              className="text-xs bg-emerald-500/10 hover:bg-emerald-500/20 text-emerald-400 border border-emerald-500/20 px-3 py-1.5 rounded-lg transition-colors"
            >
              + Time
            </button>
          )}
        </div>
      </div>

      {expanded && (
        <div className="px-5 pb-5 border-t border-gray-800 pt-4 space-y-4">
          {/* #1036 — always-visible per-profile timeline chart at the top of
              the expanded view. Carries the Today/Week toggle + host/device
              group-by + Other drill-in that the deleted /time page used to
              own. Read-only; lives above the edit subsections. */}
          <ProfileTimelineChart profileId={pd.profile.id} />

          {/* #973: inline devices subsection. Name is edited inline in the
              card header above (no redundant collapsible). Devices autosave
              per-row via PATCH /devices. Post-#978 the Edit-modal escape
              hatch is fully gone — every editable field has an inline
              subsection now; failureMode (#385) is the one orphan, tracked
              separately. The "+ New Profile" name-only form (top of page)
              replaces the modal's create flow. */}
          {isAdmin && (
            <DevicesSubsection pd={pd} assigned={devices} allDevices={allDevices} />
          )}
          {isAdmin && (
            <div className="flex flex-wrap gap-2">
              <button onClick={onTogglePause}
                className={`text-xs px-3 py-1.5 rounded-lg border transition-colors ${
                  pd.profile.paused
                    ? 'bg-emerald-500/10 text-emerald-400 border-emerald-500/20 hover:bg-emerald-500/20'
                    : 'bg-yellow-500/10 text-yellow-400 border-yellow-500/20 hover:bg-yellow-500/20'
                }`}>
                {pd.profile.paused ? '▶ Resume' : '⏸ Pause'}
              </button>
              <button onClick={onDelete}
                className="text-xs text-red-400 hover:text-red-300 bg-red-500/10 px-3 py-1.5 rounded-lg transition-colors">
                Delete
              </button>
            </div>
          )}

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

          {/* #976: apps subsection — inline app-policy editor. Post-#764 the
              legacy extraAllowed/extraBlocked textareas are gone; this owns
              the per-host policy surface end-to-end. */}
          {isAdmin && (
            <AppsRulesSubsection
              pd={pd}
              apps={apps}
              onAppsChanged={onAppsChanged}
              onProfileChanged={onProfileChanged}
              updateProfile={updateProfile}
            />
          )}

          {/* #975 — inline time-limit + cross-device overlap subsection.
              Replaces the modal's daily-cap + schedules + overlap blocks for
              this profile. */}
          <TimeSubsection pd={pd} isAdmin={isAdmin} defaultTz={defaultTz} />

          {/* #973: read-only Devices listing for non-admins. Admins get the
              editable DevicesSubsection above; keeping a second copy here for
              them would be redundant. */}
          {!isAdmin && (
            <div data-testid={`profile-devices-${pd.profile.id}`}>
              <p className="text-xs text-gray-500 uppercase tracking-wider mb-2">Devices</p>
              {devices.length === 0
                ? <p className="text-xs text-gray-600">No devices assigned.</p>
                : (
                  <div className="space-y-1">
                    {devices.map(d => (
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
          )}

          {isAdmin && (
            <div data-testid={`profile-users-${pd.profile.id}`}>
              <p className="text-xs text-gray-500 uppercase tracking-wider mb-2">Users</p>
              {userLinkError && (
                <p className="text-xs text-red-400 mb-2"
                  data-testid={`profile-users-error-${pd.profile.id}`}>
                  {userLinkError}
                </p>
              )}
              {allUsers.length === 0
                ? <p className="text-xs text-gray-600">No users in this household yet.</p>
                : (
                  <div className="flex flex-wrap gap-2">
                    {allUsers.map(u => {
                      const on = linkedUserIds.has(u.id)
                      const pending = pendingUserLinks.has(`${pd.profile.id}:${u.id}`)
                      const roleClass = u.role === 'admin'
                        ? 'bg-emerald-500/10 text-emerald-400'
                        : u.role === 'adult'
                          ? 'bg-blue-500/10 text-blue-400'
                          : 'bg-yellow-500/10 text-yellow-400'
                      return (
                        <button
                          key={u.id}
                          type="button"
                          disabled={pending}
                          onClick={() => onToggleUserLink(u.id)}
                          data-testid={on ? `profile-user-${u.id}` : `profile-user-toggle-${pd.profile.id}-${u.id}`}
                          data-on={on ? 'true' : 'false'}
                          aria-pressed={on}
                          className={`text-xs px-3 py-1.5 rounded-lg border transition-colors disabled:opacity-50 ${
                            on
                              ? 'bg-emerald-500/20 text-emerald-300 border-emerald-500/40 hover:bg-emerald-500/30'
                              : 'bg-gray-800 text-gray-400 border-gray-700 hover:border-gray-600'
                          }`}
                        >
                          {pending ? '… ' : on ? '✓ ' : ''}{u.username}
                          <span className={`ml-2 font-mono px-1.5 py-0.5 rounded ${roleClass}`}>
                            {u.role}
                          </span>
                        </button>
                      )
                    })}
                  </div>
                )
              }
            </div>
          )}

        </div>
      )}
    </div>
  )
}

// #975 — inline time-limit + cross-device overlap subsection on the expanded
// profile card. Autosave via debounced full-profile PUT (PATCH lands with
// #423; this component swaps to it without UI changes when it ships).
//
// Subsection is collapsed-by-default. Collapsed header carries the at-a-
// glance summary: "Daily limit: X min" + one row per schedule. Expanded body
// holds the editable inputs (daily cap, schedules editor, overlap radios).
// SaveStatus + the SaveStatusBadge component are imported from the shared
// useDebouncedSave hook (#973).

interface TimeFormState {
  timeLimit: string
  schedules: ScheduleRequest[]
  crossDeviceOverlapMode: CrossDeviceOverlapMode
}

function timeFormFromDetail(pd: ProfileDetail): TimeFormState {
  return {
    timeLimit: pd.timeLimit ? String(pd.timeLimit.dailyMinutes) : '',
    schedules: pd.schedules.map(s => ({
      name: s.name, days: s.days, startLocal: s.startLocal, endLocal: s.endLocal, tz: s.tz,
    })),
    crossDeviceOverlapMode: pd.profile.crossDeviceOverlapMode,
  }
}

function timeFormsEqual(a: TimeFormState, b: TimeFormState): boolean {
  if (a.timeLimit !== b.timeLimit) return false
  if (a.crossDeviceOverlapMode !== b.crossDeviceOverlapMode) return false
  if (a.schedules.length !== b.schedules.length) return false
  for (let i = 0; i < a.schedules.length; i++) {
    const x = a.schedules[i]
    const y = b.schedules[i]
    if (x.name !== y.name || x.startLocal !== y.startLocal ||
        x.endLocal !== y.endLocal || x.tz !== y.tz) return false
    if (x.days.length !== y.days.length) return false
    for (let j = 0; j < x.days.length; j++) if (x.days[j] !== y.days[j]) return false
  }
  return true
}

function TimeSubsection({
  pd, isAdmin, defaultTz,
}: {
  pd: ProfileDetail
  isAdmin: boolean
  defaultTz: string
}) {
  const invalidators = useInvalidators()
  const [expanded, setExpanded] = useState(false)
  const [form, setForm] = useState<TimeFormState>(() => timeFormFromDetail(pd))
  const [status, setStatus] = useState<SaveStatus>('idle')
  const [errorMsg, setErrorMsg] = useState<string | null>(null)

  // Sync local state when the upstream pd changes AND the local form isn't
  // mid-edit. Compare against the last-saved baseline; if the local form
  // matches that baseline (no pending edits), adopt the new pd. Otherwise
  // leave the user's in-flight edits alone — the next save will overwrite
  // the server. The structural-equality short-circuit on setForm prevents an
  // infinite effect loop when react-query refetches return an identical
  // ProfileDetail with a fresh object identity.
  const baselineRef = useRef<TimeFormState>(form)
  const formRef = useRef<TimeFormState>(form)
  formRef.current = form
  useEffect(() => {
    const incoming = timeFormFromDetail(pd)
    const local = formRef.current
    if (timeFormsEqual(local, baselineRef.current)) {
      if (!timeFormsEqual(local, incoming)) {
        setForm(incoming)
      }
    }
    baselineRef.current = incoming
  }, [pd])

  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  const savedTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(() => () => {
    if (timerRef.current) clearTimeout(timerRef.current)
    if (savedTimerRef.current) clearTimeout(savedTimerRef.current)
  }, [])

  const scheduleSave = (next: TimeFormState) => {
    if (timerRef.current) clearTimeout(timerRef.current)
    timerRef.current = setTimeout(() => { void doSave(next) }, 600)
  }

  async function doSave(next: TimeFormState) {
    setStatus('saving')
    setErrorMsg(null)
    try {
      const body = formToRequest(detailToForm(pd))
      const tl = next.timeLimit.trim() === '' ? null : Number(next.timeLimit)
      body.timeLimit = tl !== null && Number.isFinite(tl) && tl > 0 ? tl : null
      body.schedules = next.schedules
      body.crossDeviceOverlapMode = next.crossDeviceOverlapMode
      await api.profiles.update(pd.profile.id, body)
      baselineRef.current = next
      setStatus('saved')
      void invalidators.profileMutated()
      if (savedTimerRef.current) clearTimeout(savedTimerRef.current)
      savedTimerRef.current = setTimeout(() => {
        setStatus(s => (s === 'saved' ? 'idle' : s))
      }, 2000)
    } catch (e) {
      setStatus('error')
      setErrorMsg(e instanceof Error ? e.message : 'Failed to save')
    }
  }

  const update = (patch: Partial<TimeFormState>) => {
    setForm(prev => {
      const next = { ...prev, ...patch }
      scheduleSave(next)
      return next
    })
  }

  const updateSchedules = (mut: (s: ScheduleRequest[]) => ScheduleRequest[]) => {
    setForm(prev => {
      const next = { ...prev, schedules: mut(prev.schedules) }
      scheduleSave(next)
      return next
    })
  }

  function addSchedule() {
    updateSchedules(s => [
      ...s,
      { name: 'Bedtime', days: [...DAYS], startLocal: '21:00', endLocal: '07:00', tz: defaultTz },
    ])
  }
  function patchSchedule(i: number, p: Partial<ScheduleRequest>) {
    updateSchedules(s => s.map((x, idx) => idx === i ? { ...x, ...p } : x))
  }
  function removeSchedule(i: number) {
    updateSchedules(s => s.filter((_, idx) => idx !== i))
  }
  function toggleDay(i: number, d: string) {
    updateSchedules(s => s.map((x, idx) => idx !== i ? x : {
      ...x, days: x.days.includes(d) ? x.days.filter(y => y !== d) : [...x.days, d],
    }))
  }

  const statusLabel =
    status === 'saving' ? 'Saving…'
    : status === 'saved' ? 'Saved'
    : status === 'error' ? 'Save failed'
    : ''

  return (
    <div data-testid={`profile-time-subsection-${pd.profile.id}`}
      className="bg-gray-950/40 border border-gray-800 rounded-xl">
      <button type="button"
        onClick={() => setExpanded(e => !e)}
        aria-expanded={expanded}
        data-testid={`profile-time-toggle-${pd.profile.id}`}
        className="w-full flex items-center gap-3 px-4 py-3 text-left">
        <span className={`text-gray-500 transition-transform ${expanded ? 'rotate-90' : ''}`}>▸</span>
        <span className="text-xs font-semibold text-gray-400 uppercase tracking-wider">Time limits</span>
        <span className="flex-1" />
        {statusLabel && (
          <span
            data-testid={`profile-time-status-${pd.profile.id}`}
            data-status={status}
            className={`text-xs font-mono ${
              status === 'error' ? 'text-red-400'
              : status === 'saving' ? 'text-gray-500'
              : 'text-emerald-400'
            }`}>
            {statusLabel}
          </span>
        )}
      </button>

      {!expanded && (
        <div className="px-4 pb-3 space-y-1">
          {pd.timeLimit
            ? (
              <p className="text-sm text-gray-400">
                Daily limit: <span className="text-white font-medium">{pd.timeLimit.dailyMinutes} min</span>
              </p>
            )
            : <p className="text-xs text-gray-600">No daily limit.</p>
          }
          {pd.schedules.length > 0 && pd.schedules.map(s => (
            <div key={s.id} className="flex justify-between text-sm bg-gray-800/50 rounded-lg px-3 py-2">
              <span className="text-gray-300">{s.name}</span>
              <span className="text-yellow-400 font-mono text-xs">
                {s.startLocal} → {s.endLocal} <span className="text-yellow-300/60">({s.tz})</span>
              </span>
            </div>
          ))}
          <p className="text-xs text-gray-600">
            Cross-device overlap: <span className="text-gray-400">
              {pd.profile.crossDeviceOverlapMode === 'sum' ? 'count each device' : 'combine overlap'}
            </span>
          </p>
        </div>
      )}

      {expanded && (
        <div className="px-4 pb-4 space-y-4 border-t border-gray-800 pt-3">
          {errorMsg && (
            <div className="bg-red-500/10 border border-red-500/30 text-red-300 text-xs rounded-lg px-3 py-2">
              {errorMsg}
            </div>
          )}

          <div>
            <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">
              Daily time limit (minutes)
            </label>
            <input type="number" min={0}
              value={form.timeLimit}
              disabled={!isAdmin}
              data-testid={`profile-time-limit-${pd.profile.id}`}
              onChange={e => update({ timeLimit: e.target.value })}
              placeholder="Leave blank for unlimited"
              className="w-full bg-gray-950 border border-gray-700 rounded-xl px-4 py-2.5 text-white placeholder-gray-600 focus:outline-none focus:border-emerald-500 disabled:opacity-60" />
          </div>

          <div>
            <div className="flex items-center justify-between mb-2">
              <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wider">
                Schedules
              </label>
              {isAdmin && (
                <button type="button"
                  data-testid={`profile-time-add-schedule-${pd.profile.id}`}
                  onClick={addSchedule}
                  className="text-xs text-emerald-400 hover:text-emerald-300">
                  + Add schedule
                </button>
              )}
            </div>
            {form.schedules.length === 0 && (
              <p className="text-xs text-gray-500">No schedules.</p>
            )}
            <div className="space-y-3">
              {form.schedules.map((s, i) => (
                <div key={i} className="bg-gray-950 border border-gray-700 rounded-xl p-3 space-y-2"
                  data-testid={`profile-time-schedule-${pd.profile.id}-${i}`}>
                  <div className="flex gap-2">
                    <input type="text"
                      value={s.name}
                      disabled={!isAdmin}
                      onChange={e => patchSchedule(i, { name: e.target.value })}
                      placeholder="Bedtime"
                      className="flex-1 bg-gray-900 border border-gray-700 rounded-lg px-3 py-2 text-white text-sm disabled:opacity-60" />
                    {isAdmin && (
                      <button type="button"
                        onClick={() => removeSchedule(i)}
                        className="text-xs text-red-400 hover:text-red-300 bg-red-500/10 px-3 rounded-lg">
                        Remove
                      </button>
                    )}
                  </div>
                  <div className="flex gap-2 items-center text-sm">
                    <input type="time" value={s.startLocal}
                      disabled={!isAdmin}
                      onChange={e => patchSchedule(i, { startLocal: e.target.value })}
                      data-testid={`profile-time-schedule-start-${pd.profile.id}-${i}`}
                      className="bg-gray-900 border border-gray-700 rounded-lg px-3 py-2 text-white disabled:opacity-60" />
                    <span className="text-gray-500">→</span>
                    <input type="time" value={s.endLocal}
                      disabled={!isAdmin}
                      onChange={e => patchSchedule(i, { endLocal: e.target.value })}
                      data-testid={`profile-time-schedule-end-${pd.profile.id}-${i}`}
                      className="bg-gray-900 border border-gray-700 rounded-lg px-3 py-2 text-white disabled:opacity-60" />
                  </div>
                  <TimezonePicker
                    value={s.tz}
                    onChange={tz => patchSchedule(i, { tz })}
                    testId={`profile-time-schedule-tz-${pd.profile.id}-${i}`}
                  />
                  <div className="flex flex-wrap gap-1">
                    {DAYS.map(d => {
                      const on = s.days.includes(d)
                      return (
                        <button key={d} type="button"
                          disabled={!isAdmin}
                          onClick={() => toggleDay(i, d)}
                          className={`text-xs px-2.5 py-1 rounded-lg border ${
                            on
                              ? 'bg-emerald-500/20 text-emerald-300 border-emerald-500/40'
                              : 'bg-gray-800 text-gray-500 border-gray-700'
                          } disabled:opacity-60`}>
                          {d}
                        </button>
                      )
                    })}
                  </div>
                </div>
              ))}
            </div>
          </div>

          {/* #751 cross-device overlap radios, lifted from the modal. */}
          <div>
            <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">
              Cross-device overlap
            </label>
            <div className="space-y-2">
              <label className="flex items-start gap-3 text-sm text-gray-300 cursor-pointer">
                <input
                  type="radio"
                  name={`overlap-${pd.profile.id}`}
                  data-testid={`profile-time-overlap-sum-${pd.profile.id}`}
                  disabled={!isAdmin}
                  checked={form.crossDeviceOverlapMode === 'sum'}
                  onChange={() => update({ crossDeviceOverlapMode: 'sum' })}
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
                  name={`overlap-${pd.profile.id}`}
                  data-testid={`profile-time-overlap-dedup-${pd.profile.id}`}
                  disabled={!isAdmin}
                  checked={form.crossDeviceOverlapMode === 'dedup'}
                  onChange={() => update({ crossDeviceOverlapMode: 'dedup' })}
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
        </div>
      )}
    </div>
  )
}


// #976 — apps subsection of the merged /profiles expanded card.
// Default collapsed; opening reveals the same `<AppsSection>` the modal
// shows. Post-#764 the legacy extraBlocked/extraAllowed textareas are
// gone — per-host policy is owned end-to-end by app assignments.
function AppsRulesSubsection({
  pd, apps, onAppsChanged, onProfileChanged: _onProfileChanged, updateProfile: _updateProfile,
}: {
  pd: ProfileDetail
  apps: AppDetail[]
  onAppsChanged: () => void | Promise<void>
  onProfileChanged: () => void | Promise<unknown>
  updateProfile: (body: UpsertProfileRequest) => Promise<unknown>
}) {
  const [open, setOpen] = useState(false)
  const assignedAppCount = useMemo(
    () => apps.filter(a => a.assignments.some(x => x.profileId === pd.profile.id)).length,
    [apps, pd.profile.id],
  )
  const summary = assignedAppCount > 0
    ? `${assignedAppCount} assigned`
    : 'None assigned'

  // #1061 — today's per-app proportional minutes, so each AppRow with a
  // time-limit can render a usage bar matching the profile-wide one. Only
  // fires once the Apps subsection is opened.
  const today = useMemo(() => {
    const d = new Date()
    return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
  }, [])
  const usageQ = useProfileUsageByApp(pd.profile.id, today, today, { enabled: open })
  const usedMinsByAppId = useMemo(() => {
    const m = new Map<number, number>()
    for (const a of usageQ.data?.apps ?? []) {
      if (a.appId != null) m.set(a.appId, Math.round(a.proportionalSeconds / 60))
    }
    return m
  }, [usageQ.data])

  return (
    <div
      data-testid={`profile-apps-subsection-${pd.profile.id}`}
      className="bg-gray-950/40 border border-gray-800 rounded-xl"
    >
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen(v => !v)}
        data-testid={`profile-apps-toggle-${pd.profile.id}`}
        className="w-full flex items-center justify-between px-4 py-3 text-left"
      >
        <span className="flex items-center gap-2">
          <span className={`text-gray-500 transition-transform ${open ? 'rotate-90' : ''}`}>▸</span>
          <span className="text-sm font-semibold text-white">Apps</span>
        </span>
        <span className="text-xs text-gray-400">{summary}</span>
      </button>

      {open && (
        <div className="px-4 pb-4 space-y-4 border-t border-gray-800 pt-3">
          <AppsSection
            profileId={pd.profile.id}
            isNew={false}
            apps={apps}
            onChanged={onAppsChanged}
            testIdPrefix={`profile-${pd.profile.id}-apps-section`}
            usedMinsByAppId={usedMinsByAppId}
          />
        </div>
      )}
    </div>
  )
}

function findAssignment(app: AppDetail, profileId: number | null): AppPolicyAssignment | null {
  if (profileId == null) return null
  return app.assignments.find(a => a.profileId === profileId) ?? null
}

function AppsSection({ profileId, isNew, apps, onChanged, testIdPrefix = 'apps-section', usedMinsByAppId }: {
  profileId: number | null
  isNew: boolean
  apps: AppDetail[]
  onChanged: () => void | Promise<void>
  testIdPrefix?: string
  // #1061 — per-app today usage, threaded down to AppRow so time-limited rows
  // can render a usage bar. Empty/undefined → bar simply doesn't render.
  usedMinsByAppId?: Map<number, number>
}) {
  // #1007: only show apps that already have an assignment for this profile.
  // Unassigned apps stay manageable via the "+ Add app" picker below.
  const [pickerOpen, setPickerOpen] = useState(false)
  const [pickerFilter, setPickerFilter] = useState('')
  const assigned = useMemo(
    () => (profileId == null ? [] : apps.filter(a => findAssignment(a, profileId) != null)),
    [apps, profileId],
  )
  const unassigned = useMemo(
    () => (profileId == null ? [] : apps.filter(a => findAssignment(a, profileId) == null)),
    [apps, profileId],
  )
  const pickerMatches = useMemo(() => {
    const q = pickerFilter.trim().toLowerCase()
    if (!q) return unassigned
    return unassigned.filter(a => a.app.name.toLowerCase().includes(q))
  }, [unassigned, pickerFilter])

  async function addApp(app: AppDetail) {
    if (profileId == null) return
    // Default to 'allowed' on add — the user can immediately switch to block /
    // time-limit on the now-visible row. We pick a mode (rather than just
    // "make row appear") because every assignment requires one.
    await api.apps.setPolicy(app.app.id, profileId, { mode: 'allowed', dailyMinutes: null })
    setPickerOpen(false)
    setPickerFilter('')
    await onChanged()
  }

  const headerCta = !isNew && profileId != null && apps.length > 0 && (
    <button
      type="button"
      data-testid={`${testIdPrefix}-add`}
      onClick={() => setPickerOpen(v => !v)}
      className="text-xs text-emerald-400 hover:text-emerald-300"
    >
      {pickerOpen ? 'Close' : '+ Add app'}
    </button>
  )

  return (
    <div data-testid={testIdPrefix}>
      <div className="flex items-center justify-between mb-2 gap-3">
        <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wider">
          Apps
        </label>
        <div className="flex items-center gap-3">
          {headerCta}
          <Link
            to="/apps"
            data-testid={`${testIdPrefix}-manage-link`}
            className="text-xs text-emerald-400 hover:text-emerald-300"
          >
            Manage apps →
          </Link>
        </div>
      </div>
      {isNew || profileId == null ? (
        <p className="text-xs text-gray-500">
          Save this profile first to assign apps.
        </p>
      ) : apps.length === 0 ? (
        <p className="text-xs text-gray-500">
          No apps yet.{' '}
          <Link
            to="/apps"
            data-testid={`${testIdPrefix}-empty-link`}
            className="text-emerald-400 hover:text-emerald-300 underline"
          >
            Create one
          </Link>
          {' '}to block, allow, or time-limit a group of hosts.
        </p>
      ) : (
        <div className="space-y-2">
          {assigned.length === 0 && !pickerOpen && (
            <p className="text-xs text-gray-500" data-testid={`${testIdPrefix}-none-assigned`}>
              No apps assigned to this profile.{' '}
              <button
                type="button"
                data-testid={`${testIdPrefix}-none-assigned-add`}
                onClick={() => setPickerOpen(true)}
                className="text-emerald-400 hover:text-emerald-300 underline"
              >
                Add one
              </button>
              {' '}from the {apps.length}-app library.
            </p>
          )}
          {assigned.map(a => (
            <AppRow
              key={a.app.id}
              app={a}
              profileId={profileId}
              onChanged={onChanged}
              usedMins={usedMinsByAppId?.get(a.app.id)}
            />
          ))}
          {pickerOpen && (
            <div
              data-testid={`${testIdPrefix}-picker`}
              className="bg-gray-950 border border-gray-700 rounded-xl p-3 space-y-2"
            >
              <input
                type="text"
                autoFocus
                value={pickerFilter}
                onChange={e => setPickerFilter(e.target.value)}
                placeholder="Filter apps…"
                data-testid={`${testIdPrefix}-picker-filter`}
                className="w-full bg-gray-900 border border-gray-700 rounded-lg px-2 py-1 text-white text-xs"
              />
              {pickerMatches.length === 0 ? (
                <p className="text-xs text-gray-500" data-testid={`${testIdPrefix}-picker-empty`}>
                  {unassigned.length === 0
                    ? 'Every app in the library is already assigned.'
                    : 'No apps match that filter.'}
                </p>
              ) : (
                <div className="space-y-1 max-h-64 overflow-y-auto">
                  {pickerMatches.map(a => (
                    <button
                      key={a.app.id}
                      type="button"
                      data-testid={`${testIdPrefix}-picker-add-${a.app.id}`}
                      onClick={() => addApp(a)}
                      className="w-full flex items-center gap-2 px-2 py-1.5 rounded-lg bg-gray-900 hover:bg-gray-800 border border-gray-700 text-left"
                    >
                      <span className="text-base w-5 text-center" aria-hidden>{a.app.icon || '◳'}</span>
                      <span className="text-sm text-white flex-1 truncate">{a.app.name}</span>
                      <span className="text-xs text-gray-500">{a.hosts.length} host{a.hosts.length === 1 ? '' : 's'}</span>
                    </button>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>
      )}
    </div>
  )
}

function AppRow({ app, profileId, onChanged, usedMins }: {
  app: AppDetail
  profileId: number
  onChanged: () => void | Promise<void>
  // #1061 — today's proportional minutes attributed to this app for this
  // profile. Undefined → not loaded yet (e.g. subsection just opened); the
  // bar simply doesn't render until the value arrives.
  usedMins?: number
}) {
  const current = findAssignment(app, profileId)
  const [minutesDraft, setMinutesDraft] = useState<string>(() =>
    current?.mode === 'time_limited' && current.dailyMinutes != null
      ? String(current.dailyMinutes)
      : '',
  )
  const [busy, setBusy] = useState(false)
  const [localError, setLocalError] = useState<string | null>(null)
  // Re-seed the input when the persisted policy changes from outside
  // (e.g. another tab, or after our own setPolicy round-trip lands).
  // Without this the input keeps stale values once `current` updates.
  useEffect(() => {
    if (busy) return
    const next = current?.mode === 'time_limited' && current.dailyMinutes != null
      ? String(current.dailyMinutes) : ''
    setMinutesDraft(next)
  }, [current?.mode, current?.dailyMinutes])

  async function apply(mode: AppMode, dailyMinutes: number | null, exemptFromDaily?: boolean) {
    setBusy(true)
    setLocalError(null)
    try {
      await api.apps.setPolicy(app.app.id, profileId, {
        mode,
        dailyMinutes,
        ...(exemptFromDaily !== undefined ? { exemptFromDaily } : {}),
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

  async function toggleExempt(nextExempt: boolean) {
    if (current?.mode !== 'time_limited' || current.dailyMinutes == null) return
    await apply('time_limited', current.dailyMinutes, nextExempt)
  }

  const mode = current?.mode ?? null
  const isTimeLimited = mode === 'time_limited'
  const currentMinutes = isTimeLimited ? current?.dailyMinutes ?? null : null

  // Operator feedback: the old UX made you type minutes AND click a
  // separate "Time-limit" button, then showed the duration twice. Now
  // the minutes input IS the time-limit control: editing it and tabbing
  // away (or pressing Enter) saves the policy. Empty input is a no-op
  // (we revert to the current value); 0/negative shows an inline error.
  async function commitMinutes() {
    const trimmed = minutesDraft.trim()
    if (trimmed === '') {
      setLocalError(null)
      // Clearing the input on a time-limited app removes the limit by
      // switching the app to plain Allow (keeps it assigned to the
      // profile so the row stays visible — use the Remove button to drop
      // the assignment entirely). For apps that weren't time-limited in
      // the first place this is a no-op.
      if (isTimeLimited) {
        await apply('allowed', null)
      }
      return
    }
    const n = Number(trimmed)
    if (!Number.isFinite(n) || n <= 0) {
      setLocalError('Enter minutes > 0')
      return
    }
    setLocalError(null)
    // No-op if the policy is already time_limited at exactly this value.
    if (isTimeLimited && currentMinutes === n) return
    // #1007: preserve current exemptFromDaily when re-applying; default
    // to TRUE (matches the schema default and the wire default in
    // #761/#763) when transitioning into time_limited.
    await apply('time_limited', n, current?.exemptFromDaily ?? true)
  }

  const baseBtn = 'text-xs px-2.5 py-1 rounded-lg border transition-colors disabled:opacity-50'
  const off = 'bg-gray-800 text-gray-400 border-gray-700 hover:border-gray-600'
  const onBlocked = 'bg-red-500/20 text-red-300 border-red-500/40'
  const onAllowed = 'bg-emerald-500/20 text-emerald-300 border-emerald-500/40'

  return (
    <div
      data-testid={`app-row-${app.app.id}`}
      className="bg-gray-950 border border-gray-700 rounded-xl p-3 space-y-2"
    >
      <div className="flex items-center gap-3">
        <span className="w-7 text-center inline-flex items-center justify-center">
          <AppIcon icon={app.app.icon} iconType={app.app.iconType} size="md" />
        </span>
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
          >Remove</button>
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
            onBlur={commitMinutes}
            onKeyDown={e => {
              if (e.key === 'Enter') {
                e.preventDefault();
                (e.currentTarget as HTMLInputElement).blur()
              }
            }}
            disabled={busy}
            placeholder="min"
            aria-label="Daily time-limit minutes"
            data-testid={`app-row-${app.app.id}-minutes`}
            className={`w-16 rounded-lg px-2 py-1 text-white text-xs border transition-colors disabled:opacity-50 ${
              isTimeLimited
                ? 'bg-amber-500/10 border-amber-500/40 text-amber-200 placeholder-amber-200/40'
                : 'bg-gray-900 border-gray-700'
            }`}
          />
          <span className="text-xs text-gray-500">min/day</span>
        </div>
      </div>
      {/* #1061 — inline usage bar for time-limited apps. Mirrors the
          profile-wide bar (w-full h-1, emerald on track, red over limit).
          Hidden until today's usage is loaded; hidden entirely for apps
          without a daily limit. */}
      {isTimeLimited && currentMinutes != null && usedMins != null && (
        <div
          data-testid={`app-row-${app.app.id}-usage`}
          className="flex items-center gap-2"
        >
          <div className="flex-1 h-1 bg-gray-800 rounded-full overflow-hidden">
            <div
              className={`h-full rounded-full ${
                usedMins >= currentMinutes ? 'bg-red-500' : 'bg-emerald-500'
              }`}
              style={{
                width: `${Math.min(100, Math.round((usedMins / currentMinutes) * 100))}%`,
              }}
            />
          </div>
          <span className="text-xs font-mono text-gray-400 shrink-0">
            {formatMins(usedMins)} / {formatMins(currentMinutes)}
          </span>
        </div>
      )}
      {mode === 'time_limited' && (
        <label className="flex items-center gap-2 text-xs text-gray-400 cursor-pointer select-none">
          <input
            type="checkbox"
            data-testid={`app-row-${app.app.id}-counts-toward-daily`}
            checked={!(current?.exemptFromDaily ?? true)}
            disabled={busy}
            onChange={e => toggleExempt(!e.target.checked)}
            className="w-3.5 h-3.5 accent-amber-500"
          />
          <span>
            Counts toward daily limit
            {!(current?.exemptFromDaily ?? true) && (
              <span className="ml-1 text-amber-400">(usage reduces overall remaining time)</span>
            )}
          </span>
        </label>
      )}
      {localError && (
        <p className="text-xs text-red-400" data-testid={`app-row-${app.app.id}-error`}>{localError}</p>
      )}
    </div>
  )
}

// #973 — collapsible inline subsection wrapper. Header carries the title and
// a live "Saved / Saving…" indicator. Default open when first mounted (the
// design doc calls out name/icon/color as open-on-first-expand; we apply the
// same default to Devices too since both are common edit targets).
function Subsection({
  testId, title, status, error, children, defaultOpen = true,
}: {
  testId: string
  title: string
  status: SaveStatus
  error: string | null
  children: React.ReactNode
  defaultOpen?: boolean
}) {
  const [open, setOpen] = useState(defaultOpen)
  return (
    <div data-testid={testId} className="bg-gray-950/40 border border-gray-800 rounded-xl">
      <button
        type="button"
        onClick={() => setOpen(o => !o)}
        aria-expanded={open}
        data-testid={`${testId}-toggle`}
        className="w-full flex items-center justify-between px-4 py-2.5 text-left"
      >
        <span className="flex items-center gap-2">
          <span className={`text-gray-500 text-xs transition-transform ${open ? 'rotate-90' : ''}`}>▸</span>
          <span className="text-xs font-semibold text-gray-300 uppercase tracking-wider">{title}</span>
        </span>
        <SaveStatusBadge status={status} error={error} testId={`${testId}-status`} />
      </button>
      {open && <div className="px-4 pb-4 pt-1 space-y-3">{children}</div>}
    </div>
  )
}

function SaveStatusBadge({
  status, error, testId,
}: { status: SaveStatus; error: string | null; testId: string }) {
  if (status === 'saving') {
    return <span data-testid={testId} data-status="saving" className="text-xs text-gray-500">Saving…</span>
  }
  if (status === 'saved') {
    return <span data-testid={testId} data-status="saved" className="text-xs text-emerald-400">Saved</span>
  }
  if (status === 'error') {
    return (
      <span data-testid={testId} data-status="error" className="text-xs text-red-400" title={error ?? ''}>
        Save failed
      </span>
    )
  }
  return <span data-testid={testId} data-status="idle" className="text-xs text-transparent select-none">·</span>
}


// #973 — inline devices editor. Each row toggle issues PATCH /devices/:mac
// with {profileId} (assign) or {profileId: null} (detach). The status badge
// reflects the most-recent toggle.
function DevicesSubsection({
  pd, assigned, allDevices,
}: { pd: ProfileDetail; assigned: Device[]; allDevices: Device[] }) {
  const invalidators = useInvalidators()
  const [status, setStatus] = useState<SaveStatus>('idle')
  const [error, setError]   = useState<string | null>(null)
  const [busyMac, setBusyMac] = useState<string | null>(null)

  // #973 — unassigned-or-elsewhere devices are pickable. Detaching from another
  // profile here would clobber that profile's assignment, so only show
  // currently-unassigned devices in the add-picker.
  const pickable = useMemo(
    () => allDevices.filter(d => d.profileId == null),
    [allDevices],
  )

  async function setProfile(d: Device, nextPid: number | null) {
    setBusyMac(d.mac)
    setStatus('saving')
    setError(null)
    try {
      await api.devices.patch(d.mac, { profileId: nextPid })
      await invalidators.deviceMutated()
      setStatus('saved')
      setTimeout(() => setStatus(s => (s === 'saved' ? 'idle' : s)), 1500)
    } catch (e) {
      setStatus('error')
      setError(e instanceof Error ? e.message : 'Save failed')
    } finally {
      setBusyMac(null)
    }
  }

  return (
    <Subsection
      testId={`profile-devices-subsection-${pd.profile.id}`}
      title={`Devices (${assigned.length})`}
      status={status}
      error={error}
    >
      {assigned.length === 0
        ? <p className="text-xs text-gray-600">No devices assigned.</p>
        : (
          <div className="space-y-1">
            {assigned.map(d => (
              <div key={d.id} data-testid={`profile-device-${d.id}`}
                className="flex items-center justify-between text-sm bg-gray-800/50 rounded-lg px-3 py-2">
                <div className="min-w-0">
                  <span className="text-gray-300 truncate">{d.name}</span>
                  <span className="ml-2 text-gray-500 font-mono text-xs">{d.mac}</span>
                </div>
                <button
                  type="button"
                  data-testid={`profile-device-${d.id}-detach`}
                  disabled={busyMac === d.mac}
                  onClick={() => setProfile(d, null)}
                  className="text-xs text-red-400 hover:text-red-300 bg-red-500/10 px-2.5 py-1 rounded-lg disabled:opacity-50"
                >
                  Remove
                </button>
              </div>
            ))}
          </div>
        )
      }
      {pickable.length > 0 && (
        <div>
          <p className="text-xs text-gray-500 mb-1">Unassigned devices</p>
          <div className="flex flex-wrap gap-2">
            {pickable.map(d => (
              <button
                key={d.id}
                type="button"
                data-testid={`profile-device-add-${d.id}`}
                disabled={busyMac === d.mac}
                onClick={() => setProfile(d, pd.profile.id)}
                className="text-xs px-3 py-1.5 rounded-lg border bg-gray-800 text-gray-300 border-gray-700 hover:border-emerald-500/40 disabled:opacity-50"
              >
                + {d.name} <span className="text-gray-500 font-mono">{d.mac}</span>
              </button>
            ))}
          </div>
        </div>
      )}
    </Subsection>
  )
}
