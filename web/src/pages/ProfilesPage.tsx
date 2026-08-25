import { useEffect, useMemo, useRef, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { api } from '@/api/client'
import { newProfileDefaults } from '@/api/profileDefaults'
import { useBlocklists, useGlobalProfile, useProfiles, useDevices, useInvalidators, useNamedSchedules, useProfileUsageByApp, useTimeStatusSummary } from '@/api/queries'
import { isManaged, isUnmanaged } from '@/lib/devices'
import { useAuth } from '@/hooks/useAuth'
import { useWsTimeStatus, useWsTopicLive } from '@/hooks/useWs'
import { useDebouncedSave, type SaveStatus } from '@/hooks/useDebouncedSave'
import { SaveStatusBadge } from '@/components/SaveStatusBadge'
import { SchedulePicker } from '@/components/SchedulePicker'
import type {
  AppDetail, AppMode, AppPolicyAssignment, AppScheduleMode, AppScheduleRule,
  CrossDeviceOverlapMode, Device, PatchProfileRequest, PauseMode, ProfileDetail,
  ProfileTimeSummary, ScheduleWindow,
  UpsertAppAssignmentRequest, UpsertProfileRequest, User,
} from '@/types/api'
import { AppIcon } from '@/components/AppIcon'
import { AppBlocklistWarningBadge } from '@/components/AppBlocklistWarning'
import { ProfileTimelineChart } from '@/components/usage/ProfileTimelineChart'
import { ProfileUsageBreakdown } from '@/components/usage/ProfileUsageBreakdown'
import { EmptyState } from '@/components/EmptyState'
import { Skeleton } from '@/components/Skeleton'
import { PageLoader } from './DashboardPage'
import { formatMins } from '@/lib/timeFormat'

// #972: chip states reflect the at-a-glance "what's this profile doing right
// now" answer. The schedule-active check is approximated locally — but it must
// read the SAME source the server enforces from.
//
// #1539: that source is the household NAMED schedules attached via
// `scheduleIds` (PolicyService folds their windows into the per-MAC `blocked`
// flag), NOT the dead legacy `ProfileDetail.schedules` field. The legacy V1
// `schedules` table stopped being an enforcement source in #1482/#1490 and the
// upsert stopped writing it in #1494, so its rows are stale and vary per
// profile. Driving the chip from it made profiles that share the same named
// schedules show divergent chips ("Paused (schedule)" vs "Active") even though
// enforcement was identical — the prod symptom in #1539. The chip now resolves
// the attached named schedules' windows so display matches enforcement.
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

// `scheduleWindows` are the windows of the NAMED schedules attached to this
// profile (resolved from `pd.scheduleIds` against the household catalog) — the
// enforcement source. See the #1539 note above.
function computeChip(
  pd: ProfileDetail,
  summary: ProfileTimeSummary | undefined,
  scheduleWindows: ScheduleWindow[],
): PauseChip {
  if (pd.profile.paused) return 'paused-manual'
  if (summary && summary.remainingMins != null && summary.remainingMins <= 0) return 'time-exceeded'
  if (scheduleWindows.some(w => isScheduleActiveNow(w))) return 'paused-schedule'
  return 'active'
}

const CHIP_LABEL: Record<PauseChip, string> = {
  'active':          'Active',
  'paused-manual':   'Paused',
  'paused-schedule': 'Paused (schedule)',
  'time-exceeded':   'Time exceeded',
}

const CHIP_CLASS: Record<PauseChip, string> = {
  'active':          'bg-brand-accent/10 text-brand-accent border-brand-accent/20',
  'paused-manual':   'bg-amber-500/10 text-amber-700 border-amber-500/20',
  'paused-schedule': 'bg-blue-500/10 text-blue-700 border-blue-500/20',
  'time-exceeded':   'bg-red-500/10 text-red-700 border-red-500/20',
}

export function ProfilesPage() {
  // #2522 — two capabilities, not one. Almost every surface on this page is PARENTING
  // (`requireWriter`): create/delete a profile, default-deny, categories, apps, devices, time
  // limits, schedules. The lone exception is the per-profile Users picker: its write
  // (PUT /api/profiles/{id}/users) is `requireWriter`, but populating the picker needs
  // GET /api/users, which #2522 deliberately keeps `requireAdmin` (account lifecycle) — so that
  // one subsection stays gated on `isAdmin` rather than showing an adult an empty household
  // (TODO(#2545) — the API grants the write; only the picker's READ is missing).
  const { isAdmin, isWriter } = useAuth()
  const invalidators = useInvalidators()
  const profilesQuery = useProfiles()
  // #1773: the sentinel sits outside `/api/profiles` (#1771 hides it). Writers
  // pull it via `/api/profiles/global` and we prepend it so the operator can
  // edit its app-policy assignments / categories / defaultDeny through the
  // same per-profile shell. #2522: the route is `requireWriter`, so a child skips
  // the fetch (and the page already gates every editing surface on `isWriter`).
  const globalProfileQuery = useGlobalProfile({ enabled: isWriter })
  const devicesQuery  = useDevices()
  // #1974 (SPA-ws S6a): subscribe the live `timeStatus` push (patches the summary cache off the
  // pushed body), and pause the summary's adaptive ladder while that push is streaming (§3.3 — the
  // ladder stays the disconnected fallback, it is not removed until S7).
  useWsTimeStatus()
  const timeStatusLive = useWsTopicLive('timeStatus')
  const summariesQuery = useTimeStatusSummary({ wsLive: timeStatusLive })
  // #2166 — the page-level `loading` gate below intentionally does NOT include
  // the summary query (the profile cards paint before time-status returns), so
  // we thread its pending state down to render a skeleton for the used/cap
  // number instead of coercing a not-yet-loaded summary to "0m". A loading
  // "0m" is indistinguishable from a genuine zero and once masked a slow-query
  // incident as data loss (#1098). See docs/process/loading-states.md.
  const summariesPending = summariesQuery.isPending
  const profiles  = useMemo(() => {
    const list = profilesQuery.data ?? []
    const g    = globalProfileQuery.data
    return g ? [g, ...list] : list
  }, [profilesQuery.data, globalProfileQuery.data])
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
      if (!isManaged(d)) continue
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
    const [users, appsList] = await Promise.all([
      isAdmin ? api.users.list().catch(() => [] as User[]) : Promise.resolve([] as User[]),
      api.apps.list().catch(() => [] as AppDetail[]),
    ])
    setAllUsers(users)
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

  // #1737 — every inline edit + the pause toggle now PATCHes only the field(s)
  // it changed, instead of round-tripping the whole UpsertProfileRequest. This
  // closes the concurrent-edit clobber #423 was opened to fix: two operators
  // editing different fields of the same profile no longer overwrite each
  // other. PUT (api.profiles.update) stays on the client for full-shape replace
  // but ProfilesPage no longer uses it.
  const updateMutation = useMutation({
    mutationFn: ({ id, body }: { id: number; body: PatchProfileRequest }) =>
      api.profiles.patch(id, body),
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
      // overlap, no blocked categories, no schedules, no daily cap). Shared
      // with the Add-Device inline creator (#2367) via newProfileDefaults so
      // the two creation paths can't drift.
      await createMutation.mutateAsync(newProfileDefaults(trimmed))
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

  async function togglePause(pd: ProfileDetail, mode?: PauseMode) {
    // #406: setting `paused` explicitly is idempotent under concurrent clicks.
    // #1737: PATCH carries only `paused` (and `pauseMode` when pausing), so a
    // pause click no longer clobbers a concurrent edit to some other field.
    // #1471: when pausing, the admin picks soft vs hard at click-time; the
    // chosen mode rides this PATCH. Resume omits it (preserve existing).
    const nextPaused = !pd.profile.paused
    const body: PatchProfileRequest = { paused: nextPaused }
    if (nextPaused && mode) body.pauseMode = mode
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
        <h1 className="text-xl font-bold text-brand-ink">Profiles</h1>
        {isWriter && creatingName === null && (
          <button
            onClick={startNew}
            className="bg-brand-accent hover:bg-brand-accent-dark text-white text-sm font-semibold px-4 py-2 rounded-xl transition-colors"
          >
            + New Profile
          </button>
        )}
      </div>

      {/* #978 — inline name-only new-profile form. Replaces the old
          ProfileEditor modal; everything else is filled in via the inline
          subsections on the new profile's expanded card. */}
      {isWriter && creatingName !== null && (
        <div
          data-testid="profile-create-form"
          className="bg-white rounded-2xl border border-brand-accent/30 p-4 space-y-3"
        >
          <div className="flex items-center justify-between">
            <h2 className="text-sm font-semibold text-brand-ink">New Profile</h2>
          </div>
          {creatingError && (
            <div
              data-testid="profile-create-error"
              className="bg-red-500/10 border border-red-500/30 text-red-700 text-sm rounded-xl px-4 py-2"
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
              className="flex-1 bg-brand-surface border border-brand-border-strong rounded-xl px-4 py-2.5 text-brand-ink placeholder-brand-text-muted focus:outline-none focus:border-brand-accent"
            />
            <button
              onClick={cancelNew}
              disabled={creatingSaving}
              data-testid="profile-create-cancel"
              className="px-4 py-2.5 rounded-xl bg-brand-alt text-brand-text text-sm font-medium hover:bg-brand-alt disabled:opacity-50 transition-colors"
            >
              Cancel
            </button>
            <button
              onClick={saveNew}
              disabled={creatingSaving}
              data-testid="profile-create-save"
              className="px-4 py-2.5 rounded-xl bg-brand-accent text-white text-sm font-semibold hover:bg-brand-accent-dark disabled:opacity-50 transition-colors"
            >
              {creatingSaving ? 'Creating…' : 'Create'}
            </button>
          </div>
          <p className="text-[11px] text-brand-text-muted">
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
            summaryLoading={summariesPending}
            devices={devicesByProfile.get(pd.profile.id) ?? []}
            allDevices={devices}
            users={usersByProfile.get(pd.profile.id) ?? []}
            apps={apps}
            allUsers={allUsers}
            isWriter={isWriter}
            isAdmin={isAdmin}
            expanded={expanded.has(pd.profile.id)}
            highlight={highlightId === pd.profile.id}
            onToggle={() => toggleExpanded(pd.profile.id)}
            onDelete={() => del(pd.profile.id, pd.profile.name)}
            onTogglePause={(mode) => togglePause(pd, mode)}
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
          <div className="bg-white rounded-2xl border border-brand-border-strong w-full max-w-sm p-6 space-y-4">
            <h3 className="text-lg font-bold text-brand-ink">Grant Extra Time</h3>
            <p className="text-sm text-brand-text">{profileName(extProfileId)}</p>

            <div>
              <label className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2">
                Extra minutes
              </label>
              <div className="flex gap-2">
                {[15, 30, 45, 60].map(m => (
                  <button
                    key={m}
                    onClick={() => setExtMins(m)}
                    className={`flex-1 py-2 rounded-lg text-sm font-medium transition-colors ${
                      extMins === m
                        ? 'bg-brand-accent text-white'
                        : 'bg-brand-alt text-brand-text hover:bg-brand-alt'
                    }`}
                  >
                    {m}m
                  </button>
                ))}
              </div>
            </div>

            <div>
              <label className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2">
                Note (optional)
              </label>
              <input
                type="text"
                value={extNote}
                onChange={e => setExtNote(e.target.value)}
                placeholder="Homework finished, good behavior…"
                className="w-full bg-brand-surface border border-brand-border-strong rounded-xl px-4 py-3 text-brand-ink text-sm placeholder-brand-text-muted focus:outline-none focus:border-brand-accent"
              />
            </div>

            <div className="flex gap-3 pt-2">
              <button
                onClick={() => setExtProfileId(null)}
                className="flex-1 py-3 rounded-xl bg-brand-alt text-brand-text font-medium hover:bg-brand-alt transition-colors"
              >
                Cancel
              </button>
              <button
                onClick={() => grantExtension(extProfileId)}
                disabled={granting}
                className="flex-1 py-3 rounded-xl bg-brand-accent text-white font-semibold hover:bg-brand-accent-dark disabled:opacity-50 transition-colors"
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
// the summary (name, used/cap + bar, pause chip, +Time, Pause/Delete — #1063).
// Expanded body holds the inline subsections (#973-#977) that replaced the
// old per-profile modal, plus the read-only devices listing.
function ProfileShellRow({
  pd, summary, summaryLoading, devices, allDevices, users, apps, allUsers, isWriter, isAdmin, expanded, highlight,
  onToggle, onDelete, onTogglePause, onGrantTime,
  onAppsChanged, onProfileChanged, updateProfile,
  onToggleUserLink, pendingUserLinks, userLinkError,
}: {
  pd: ProfileDetail
  summary: ProfileTimeSummary | undefined
  summaryLoading: boolean
  devices: Device[]
  allDevices: Device[]
  users: User[]
  apps: AppDetail[]
  allUsers: User[]
  isWriter: boolean
  isAdmin: boolean
  expanded: boolean
  highlight: boolean
  onToggle: () => void
  onDelete: () => void
  onTogglePause: (mode?: PauseMode) => void
  onGrantTime: () => void
  onAppsChanged: () => void | Promise<void>
  onProfileChanged: () => void | Promise<unknown>
  updateProfile: (body: PatchProfileRequest) => Promise<unknown>
  onToggleUserLink: (userId: number) => void
  pendingUserLinks: Set<string>
  userLinkError: string | null
}) {
  const linkedUserIds = useMemo(() => new Set(users.map(u => u.id)), [users])
  // #1773 — the global sentinel profile (#1771) is edited through this same
  // shell, but concepts that only make sense per-MAC are write-rejected at the
  // API for it (schedules / time limits / paused / pauseMode / delete; devices
  // also can't reference it). Hide those subsections + actions so the UI
  // doesn't dangle disabled controls. Apps, blocked categories, defaultDeny,
  // and the name editor stay — they're what the operator actually edits here.
  const isGlobal = pd.profile.isGlobal === true
  // #1539: resolve the windows of the NAMED schedules attached to this profile
  // (the enforcement source) so the chip matches what PolicyService enforces —
  // not the dead legacy `pd.schedules`. The catalog is shared/cached across all
  // cards by useNamedSchedules, so this adds no extra fetch.
  const { data: namedSchedules = [] } = useNamedSchedules()
  const attachedScheduleWindows = useMemo(() => {
    const attached = new Set(pd.scheduleIds ?? [])
    return namedSchedules.filter(s => attached.has(s.id)).flatMap(s => s.windows)
  }, [namedSchedules, pd.scheduleIds])
  const chip = computeChip(pd, summary, attachedScheduleWindows)
  // #2166 — the summary query is still pending and this profile has no summary
  // yet: show a skeleton for the used/cap number rather than "0m". A loaded
  // summary with usedMins 0 is a genuine zero and DOES render "0m" (below).
  const summaryPending = summaryLoading && !summary
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
  // debounced autosave PATCHes only the name (#1737) so it can't clobber a
  // concurrent edit to some other field of the profile.
  const [editingName, setEditingName] = useState(pd.profile.name)
  useEffect(() => { setEditingName(pd.profile.name) }, [pd.profile.name])
  const { status: nameStatus, error: nameError } = useDebouncedSave(
    editingName,
    async (next: string) => {
      const trimmed = next.trim()
      if (!trimmed) throw new Error('Name is required')
      await updateProfile({ name: trimmed })
      await onProfileChanged()
    },
    { key: pd.profile.id },
  )

  // #1471 — soft/hard pause is chosen at click-time via a small picker on the
  // Pause action (resume stays a single click). Close it on outside-click or
  // Escape so it behaves like a normal popover menu.
  const [pausePickerOpen, setPausePickerOpen] = useState(false)
  const pausePickerRef = useRef<HTMLDivElement | null>(null)
  useEffect(() => {
    if (!pausePickerOpen) return
    const onDocClick = (e: MouseEvent) => {
      if (pausePickerRef.current && !pausePickerRef.current.contains(e.target as Node)) {
        setPausePickerOpen(false)
      }
    }
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setPausePickerOpen(false) }
    document.addEventListener('mousedown', onDocClick)
    document.addEventListener('keydown', onKey)
    return () => {
      document.removeEventListener('mousedown', onDocClick)
      document.removeEventListener('keydown', onKey)
    }
  }, [pausePickerOpen])

  return (
    <div
      data-testid={`profile-card-${pd.profile.id}`}
      className={`bg-white rounded-2xl border transition-shadow ${
        overLimit ? 'border-red-500/40' : 'border-brand-border'
      } ${highlight ? 'ring-2 ring-brand-accent/60' : ''}`}
    >
      <div className="flex items-center gap-2 px-5 py-4">
        <button
          type="button"
          onClick={onToggle}
          aria-expanded={expanded}
          aria-label={`${expanded ? 'Collapse' : 'Expand'} ${pd.profile.name}`}
          data-testid={`profile-row-toggle-${pd.profile.id}`}
          className="text-brand-text-muted shrink-0"
        >
          <span className={`inline-block transition-transform ${expanded ? 'rotate-90' : ''}`}>▸</span>
        </button>
        {expanded && isWriter && !isGlobal ? (
          <div className="flex-1 min-w-0 flex items-center gap-2">
            <input
              type="text"
              value={editingName}
              onChange={e => setEditingName(e.target.value)}
              data-testid={`profile-name-input-${pd.profile.id}`}
              aria-label="Profile name"
              className="flex-1 min-w-0 font-semibold text-brand-ink text-lg bg-transparent border-b border-transparent hover:border-brand-border-strong focus:border-brand-accent focus:outline-none px-0 py-0.5"
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
            <span className="font-semibold text-brand-ink text-lg truncate">{pd.profile.name}</span>
          </button>
        )}

        <div className="flex items-center gap-3 shrink-0">
          {/* Used / cap with a thin inline progress bar. Hidden for the global
              sentinel — daily limits don't apply household-wide. */}
          {!isGlobal && (
          <div
            data-testid={`profile-summary-time-${pd.profile.id}`}
            className="hidden sm:flex flex-col items-end min-w-[7rem]"
          >
            {/* #2166 — skeleton while the time-status summary is still loading,
                so a slow query can't render "0m" as if it were real usage. */}
            {summaryPending ? (
              <Skeleton
                className="h-4 w-16"
                testId={`profile-summary-time-loading-${pd.profile.id}`}
                label="Loading usage…"
              />
            ) : (
              <>
                {/* #975: surface granted +Time extensions in the cap text so a
                    fresh grant is visible in the summary row. The denominator is
                    base + extension (matches the bar denominator below); a
                    "(+Xm)" suffix calls out how much of that is a grant so the
                    operator can tell at a glance how much extra is in play. */}
                <span className="text-xs font-mono text-brand-text">
                  {formatMins(usedMins)}
                  {hasLimit ? ` / ${formatMins(limitBase)}` : ''}
                  {hasLimit && (summary!.extensionMins ?? 0) > 0 && (
                    <span className="text-brand-accent"> (+{formatMins(summary!.extensionMins ?? 0)})</span>
                  )}
                </span>
                {hasLimit && (
                  <div className="w-24 h-1 bg-brand-alt rounded-full overflow-hidden mt-1">
                    <div
                      className={`h-full rounded-full ${overLimit ? 'bg-red-500' : 'bg-brand-accent'}`}
                      style={{ width: `${pct}%` }}
                    />
                  </div>
                )}
              </>
            )}
          </div>
          )}

          {!isGlobal && (
          <span
            data-testid={`profile-pause-chip-${pd.profile.id}`}
            data-chip={chip}
            className={`text-xs px-2 py-1 rounded-lg border ${CHIP_CLASS[chip]}`}
          >
            {CHIP_LABEL[chip]}
          </span>
          )}
          {isGlobal && (
            <span
              data-testid={`profile-global-badge-${pd.profile.id}`}
              className="text-xs px-2 py-1 rounded-lg border bg-brand-accent/10 text-brand-accent border-brand-accent/20"
            >
              Global
            </span>
          )}

          {isWriter && hasLimit && !isGlobal && (
            <button
              type="button"
              onClick={onGrantTime}
              data-testid={`profile-row-grant-${pd.profile.id}`}
              className="text-xs bg-brand-accent/10 hover:bg-brand-accent-dark/20 text-brand-accent border border-brand-accent/20 px-3 py-1.5 rounded-lg transition-colors"
            >
              + Time
            </button>
          )}

          {/* #1063 — Pause/Resume + Delete were promoted from the expanded
              card body into the collapsed summary row. Both are high-frequency
              one-shot actions; making the operator expand the card first was
              busywork. Icon-only Pause (chip already says Paused/Active);
              Delete is muted + far right so it's hard to mis-click. */}
          {isWriter && !isGlobal && (
            <div className="relative" ref={pausePickerRef}>
              <button
                type="button"
                // #1471 — Resume is a single click; Pause opens the soft/hard
                // picker so the mode is chosen at the moment of pausing.
                onClick={() => {
                  if (pd.profile.paused) onTogglePause()
                  else setPausePickerOpen(o => !o)
                }}
                data-testid={`profile-row-pause-${pd.profile.id}`}
                aria-label={pd.profile.paused ? 'Resume profile' : 'Pause profile'}
                aria-haspopup={pd.profile.paused ? undefined : 'menu'}
                aria-expanded={pd.profile.paused ? undefined : pausePickerOpen}
                title={pd.profile.paused ? 'Resume profile' : 'Pause profile'}
                className={`text-xs px-2 py-1.5 rounded-lg border transition-colors ${
                  pd.profile.paused
                    ? 'bg-brand-accent/10 text-brand-accent border-brand-accent/20 hover:bg-brand-accent-dark/20'
                    : 'bg-amber-500/10 text-amber-700 border-amber-500/20 hover:bg-amber-500/20'
                }`}
              >
                {pd.profile.paused ? '▶' : '⏸'}
              </button>
              {!pd.profile.paused && pausePickerOpen && (
                <div
                  role="menu"
                  data-testid={`profile-row-pause-menu-${pd.profile.id}`}
                  className="absolute right-0 top-full mt-1 z-20 w-56 bg-white rounded-xl border border-brand-border-strong shadow-lg p-1"
                >
                  <button
                    type="button"
                    role="menuitem"
                    onClick={() => { setPausePickerOpen(false); onTogglePause('soft') }}
                    data-testid={`profile-row-pause-soft-${pd.profile.id}`}
                    className="w-full text-left px-3 py-2 rounded-lg hover:bg-brand-alt transition-colors"
                  >
                    <span className="block text-sm font-medium text-brand-ink">Soft pause</span>
                    <span className="block text-xs text-brand-text-muted">
                      Block the internet but keep allowed apps + the block page reachable.
                    </span>
                  </button>
                  <button
                    type="button"
                    role="menuitem"
                    onClick={() => { setPausePickerOpen(false); onTogglePause('hard') }}
                    data-testid={`profile-row-pause-hard-${pd.profile.id}`}
                    className="w-full text-left px-3 py-2 rounded-lg hover:bg-brand-alt transition-colors"
                  >
                    <span className="block text-sm font-medium text-brand-ink">Hard pause</span>
                    <span className="block text-xs text-brand-text-muted">
                      Cut everything; only the block page stays reachable.
                    </span>
                  </button>
                </div>
              )}
            </div>
          )}
          {isWriter && !isGlobal && (
            <button
              type="button"
              onClick={onDelete}
              data-testid={`profile-row-delete-${pd.profile.id}`}
              title="Delete profile"
              className="text-xs text-brand-text-muted hover:text-red-700 hover:bg-red-500/10 border border-transparent hover:border-red-500/20 px-2 py-1.5 rounded-lg transition-colors"
            >
              Delete
            </button>
          )}
        </div>
      </div>

      {expanded && (
        <div className="px-5 pb-5 border-t border-brand-border pt-4 space-y-4">
          {/* #1036 — always-visible per-profile timeline chart at the top of
              the expanded view. Carries the Today/Week toggle + host/device
              group-by + Other drill-in that the deleted /time page used to
              own. Read-only; lives above the edit subsections.
              #1773 — skipped for the global sentinel: it has no MACs of its
              own, so the per-profile usage feeds return nothing meaningful. */}
          {!isGlobal && <ProfileTimelineChart profileId={pd.profile.id} />}

          {/* #1519/#726 — per-app usage breakdown: one row per configured app
              (drillable to its host-set, the per-FQDN view from #726), then
              one row per non-app host as its own single-host pseudo-app (per
              the App-Centric Model — no semantic "Other" bucket). Top-N + a
              "+N more sites" expander is a presentation rollup only. */}
          {!isGlobal && <ProfileUsageBreakdown profileId={pd.profile.id} enabled={expanded} />}

          {/* #1320 — per-profile default-deny baseline. Block-all; only the
              profile's allowed apps/hosts + the household global allowlist are
              reachable. The inverse of allow-by-default + blocklists. #1472 —
              hoisted to the top of the expanded view: default-deny is the
              profile's most fundamental posture, so it reads first, before
              devices / categories / apps. */}
          {isWriter && (
            <DefaultDenySubsection
              pd={pd}
              onProfileChanged={onProfileChanged}
            />
          )}

          {/* #973: inline devices subsection. Name is edited inline in the
              card header above (no redundant collapsible). Devices autosave
              per-row via PATCH /devices. Post-#978 the Edit-modal escape
              hatch is fully gone — every editable field has an inline
              subsection now; failureMode (#385) is the one orphan, tracked
              separately. The "+ New Profile" name-only form (top of page)
              replaces the modal's create flow. */}
          {isWriter && !isGlobal && (
            <DevicesSubsection pd={pd} assigned={devices} allDevices={allDevices} />
          )}
          {/* #1473 — blocked categories are edited inline here (#2522: any writer — admin or
              adult), replacing the read-only chips. Toggling a category autosaves
              blockedCategories via the same full-profile PUT the Blocklists
              matrix uses. Non-admins keep the read-only chips below. */}
          {isWriter ? (
            <CategoriesSubsection
              pd={pd}
              onProfileChanged={onProfileChanged}
            />
          ) : (
            pd.profile.blockedCategories.length > 0 && (
              <div>
                <p className="text-xs text-brand-text-muted uppercase tracking-wider mb-2">Blocked categories</p>
                <div className="flex flex-wrap gap-2">
                  {pd.profile.blockedCategories.map(c => (
                    <span key={c} className="text-xs bg-red-500/10 text-red-700 px-2 py-1 rounded-lg font-mono">{c}</span>
                  ))}
                </div>
              </div>
            )
          )}

          {/* #976: apps subsection — inline app-policy editor. Post-#764 the
              legacy extraAllowed/extraBlocked textareas are gone; this owns
              the per-host policy surface end-to-end. */}
          {isWriter && (
            <AppsRulesSubsection
              pd={pd}
              apps={apps}
              onAppsChanged={onAppsChanged}
              onProfileChanged={onProfileChanged}
              updateProfile={updateProfile}
            />
          )}

          {/* #975 — inline time-limit + cross-device overlap subsection.
              Replaces the modal's daily-cap + overlap blocks for this profile.
              Schedules split into their own sibling subsection (#1474).
              #1773: daily limits and schedules don't apply household-wide; the
              API rejects writes against the sentinel for both. */}
          {!isGlobal && <TimeSubsection pd={pd} isWriter={isWriter} />}

          {/* #1474 — schedules subsection (bedtime/windows), split out of the
              Time-limits expander into its own top-level disclosure. */}
          {!isGlobal && <ScheduleSubsection pd={pd} isWriter={isWriter} />}

          {/* #973: read-only Devices listing for non-writers (#2522: a child). Writers get the
              editable DevicesSubsection above; keeping a second copy here for
              them would be redundant. */}
          {!isWriter && !isGlobal && (
            <div data-testid={`profile-devices-${pd.profile.id}`}>
              <p className="text-xs text-brand-text-muted uppercase tracking-wider mb-2">Devices</p>
              {devices.length === 0
                ? <p className="text-xs text-brand-text-muted">No devices assigned.</p>
                : (
                  <div className="space-y-1">
                    {devices.map(d => (
                      <div key={d.id} data-testid={`profile-device-${d.id}`}
                        className="flex justify-between text-sm bg-brand-alt/50 rounded-lg px-3 py-2">
                        <span className="text-brand-text">{d.name}</span>
                        <span className="text-brand-text-muted font-mono text-xs">{d.mac}</span>
                      </div>
                    ))}
                  </div>
                )
              }
            </div>
          )}

          {/* #2522 — the ONE admin-gated surface on this page. Linking a user to a profile is
              parenting and its write (PUT /api/profiles/{id}/users) is `requireWriter`, but the
              picker can only be populated from GET /api/users, which stays `requireAdmin`. Showing
              it to an adult would render an empty "No users in this household yet." — a false
              statement — so it stays admin-only until the read has a writer-visible source.
              TODO(#2545): give the picker a writer-visible household-roster read and drop this
              gate to `isWriter`, so an adult can use the capability the API already grants. */}
          {isAdmin && !isGlobal && (
            <div data-testid={`profile-users-${pd.profile.id}`}>
              <p className="text-xs text-brand-text-muted uppercase tracking-wider mb-2">Users</p>
              {userLinkError && (
                <p className="text-xs text-red-700 mb-2"
                  data-testid={`profile-users-error-${pd.profile.id}`}>
                  {userLinkError}
                </p>
              )}
              {allUsers.length === 0
                ? <EmptyState variant="inline" title="No users in this household yet." />
                : (
                  <div className="flex flex-wrap gap-2">
                    {allUsers.map(u => {
                      const on = linkedUserIds.has(u.id)
                      const pending = pendingUserLinks.has(`${pd.profile.id}:${u.id}`)
                      const roleClass = u.role === 'admin'
                        ? 'bg-brand-accent/10 text-brand-accent'
                        : u.role === 'adult'
                          ? 'bg-blue-500/10 text-blue-700'
                          : 'bg-amber-500/10 text-amber-700'
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
                              ? 'bg-brand-accent/20 text-brand-accent border-brand-accent/40 hover:bg-brand-accent-dark/30'
                              : 'bg-brand-alt text-brand-text border-brand-border-strong hover:border-brand-border-strong'
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
// glance summary: "Daily limit: X min" + the cross-device overlap mode.
// Expanded body holds the editable inputs (daily cap, overlap radios, pause
// mode). Schedules used to live here too, but #1474 split them out into the
// sibling ScheduleSubsection below — this component no longer touches them.
// SaveStatus comes from the shared useDebouncedSave hook; SaveStatusBadge is
// the shared component in components/SaveStatusBadge.tsx (#973, #995).

interface TimeFormState {
  timeLimit: string
  crossDeviceOverlapMode: CrossDeviceOverlapMode
}

function timeFormFromDetail(pd: ProfileDetail): TimeFormState {
  return {
    timeLimit: pd.timeLimit ? String(pd.timeLimit.dailyMinutes) : '',
    crossDeviceOverlapMode: pd.profile.crossDeviceOverlapMode,
  }
}

function timeFormsEqual(a: TimeFormState, b: TimeFormState): boolean {
  if (a.timeLimit !== b.timeLimit) return false
  if (a.crossDeviceOverlapMode !== b.crossDeviceOverlapMode) return false
  return true
}

// #1320 — per-profile default-deny toggle. Flips the whole profile to a
// block-all baseline (only its allowed apps/hosts + the household global
// allowlist stay reachable). Persists via the existing full-profile PUT —
// formToRequest carries defaultDeny — so it composes with every other field.
// #1473 — inline blocked-categories editor. Fetches the blocklist catalog
// (the same `GET /api/blocklists` the Blocklists matrix page uses) and renders
// a checklist; toggling a category writes blockedCategories via the
// full-profile PUT. Writer-only (#2522) — `GET /api/blocklists` is `requireWriter`, and this
// is the editing surface (a child falls back to the read-only chips).
function CategoriesSubsection({
  pd, onProfileChanged,
}: {
  pd: ProfileDetail
  onProfileChanged: () => void | Promise<unknown>
}) {
  // Catalog comes from the shared react-query cache (GET /api/blocklists), so
  // all profile cards reuse one fetch rather than each firing its own.
  const blocklistsQuery = useBlocklists()
  const lists = blocklistsQuery.data ?? []
  const loading = blocklistsQuery.isPending
  const loadError = blocklistsQuery.isError
    ? (blocklistsQuery.error instanceof Error ? blocklistsQuery.error.message : 'failed to load categories')
    : null
  const [savingId, setSavingId] = useState<string | null>(null)
  const [saveError, setSaveError] = useState<string | null>(null)
  const error = saveError ?? loadError

  const selected = pd.profile.blockedCategories

  // Bundled first (alphabetical), then operator/test lists — matches the
  // Blocklists matrix ordering so the two surfaces read consistently.
  const sorted = useMemo(
    () => [...lists].sort((a, b) => {
      if (a.bundled !== b.bundled) return a.bundled ? -1 : 1
      return a.id.localeCompare(b.id)
    }),
    [lists],
  )

  async function toggle(id: string) {
    if (savingId) return
    setSavingId(id)
    setSaveError(null)
    try {
      const has = selected.includes(id)
      const next = has ? selected.filter(c => c !== id) : [...selected, id]
      // #1773: PATCH (field-scoped) instead of PUT — the global sentinel rejects
      // PUT /profiles/:id but accepts PATCH for `blockedCategories`; for regular
      // profiles PATCH is also the preferred path per #423/#995 (race-safe).
      await api.profiles.patch(pd.profile.id, { blockedCategories: next })
      await onProfileChanged()
    } catch (e) {
      setSaveError(e instanceof Error ? e.message : 'Failed to update')
    } finally {
      setSavingId(null)
    }
  }

  return (
    <div data-testid={`profile-categories-subsection-${pd.profile.id}`}>
      <p className="text-xs text-brand-text-muted uppercase tracking-wider mb-2">Blocked categories</p>
      {loading ? (
        <p className="text-xs text-brand-text-muted">Loading categories…</p>
      ) : sorted.length === 0 ? (
        <p className="text-xs text-brand-text-muted">
          No blocklist categories available.{' '}
          <Link to="/blocklists" className="text-brand-accent hover:underline">Manage blocklists</Link>
        </p>
      ) : (
        <div className="flex flex-wrap gap-2">
          {sorted.map(b => {
            const on = selected.includes(b.id)
            const saving = savingId === b.id
            return (
              <label
                key={b.id}
                data-testid={`profile-category-${pd.profile.id}-${b.id}`}
                title={b.description ?? undefined}
                className={`inline-flex items-center text-xs px-3 py-1.5 rounded-lg border cursor-pointer transition-colors ${
                  on
                    ? 'bg-red-500/20 text-red-700 border-red-500/40'
                    : 'bg-brand-alt text-brand-text border-brand-border-strong hover:border-brand-border-strong'
                } ${saving ? 'opacity-50 cursor-wait' : ''}`}
              >
                <input
                  type="checkbox"
                  checked={on}
                  disabled={saving}
                  onChange={() => toggle(b.id)}
                  data-testid={`profile-category-toggle-${pd.profile.id}-${b.id}`}
                  aria-label={b.name}
                  className="sr-only"
                />
                <span>{on ? '✓ ' : ''}{b.name}</span>
              </label>
            )
          })}
        </div>
      )}
      {error && (
        <p className="text-xs text-red-700 mt-1" data-testid={`profile-categories-error-${pd.profile.id}`}>
          {error}
        </p>
      )}
    </div>
  )
}

function DefaultDenySubsection({
  pd, onProfileChanged,
}: {
  pd: ProfileDetail
  onProfileChanged: () => void
}) {
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const on = pd.profile.defaultDeny

  async function toggle() {
    if (saving) return
    setSaving(true)
    setError(null)
    try {
      // #1773: PATCH so this works on the global sentinel too (PUT is rejected
      // for the sentinel; PATCH on `defaultDeny` is allowed).
      await api.profiles.patch(pd.profile.id, { defaultDeny: !on })
      onProfileChanged()
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Failed to update')
    } finally {
      setSaving(false)
    }
  }

  return (
    <div data-testid={`profile-default-deny-${pd.profile.id}`}>
      <div className="flex items-start justify-between gap-4 bg-brand-alt/40 rounded-xl px-4 py-3">
        <div className="min-w-0">
          <p className="text-sm font-medium text-brand-ink">Default-deny</p>
          <p className="text-xs text-brand-text-muted mt-0.5">
            {on
              ? 'Everything is blocked except this profile’s allowed apps/hosts and the household global allowlist.'
              : 'Allow by default; only blocked categories and blocked hosts are dropped. Turn on to block everything not explicitly allowed.'}
          </p>
        </div>
        <label className="inline-flex items-center cursor-pointer shrink-0">
          <input
            type="checkbox"
            checked={on}
            disabled={saving}
            onChange={toggle}
            data-testid={`profile-default-deny-toggle-${pd.profile.id}`}
            aria-label="Default-deny"
            className="sr-only peer"
          />
          <span className="w-11 h-6 bg-brand-border rounded-full peer-checked:bg-red-600 relative transition-colors after:content-[''] after:absolute after:top-0.5 after:left-0.5 after:bg-white after:rounded-full after:h-5 after:w-5 after:transition-transform peer-checked:after:translate-x-5" />
        </label>
      </div>
      {error && (
        <p className="text-xs text-red-700 mt-1" data-testid={`profile-default-deny-error-${pd.profile.id}`}>
          {error}
        </p>
      )}
    </div>
  )
}

function TimeSubsection({
  pd, isWriter,
}: {
  pd: ProfileDetail
  isWriter: boolean
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
      // #1737: PATCH only the two fields this subsection owns — the daily cap
      // and the cross-device overlap mode. Schedules (#1474) and pauseMode
      // (#1471) are owned elsewhere, and a field-scoped PATCH preserves them
      // without round-tripping the whole profile (which would clobber a
      // concurrent edit). `timeLimit: null` clears the cap.
      const tl = next.timeLimit.trim() === '' ? null : Number(next.timeLimit)
      const body: PatchProfileRequest = {
        timeLimit: tl !== null && Number.isFinite(tl) && tl > 0 ? tl : null,
        crossDeviceOverlapMode: next.crossDeviceOverlapMode,
      }
      await api.profiles.patch(pd.profile.id, body)
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

  const statusLabel =
    status === 'saving' ? 'Saving…'
    : status === 'saved' ? 'Saved'
    : status === 'error' ? 'Save failed'
    : ''

  return (
    <div data-testid={`profile-time-subsection-${pd.profile.id}`}
      className="bg-brand-surface/40 border border-brand-border rounded-xl">
      <button type="button"
        onClick={() => setExpanded(e => !e)}
        aria-expanded={expanded}
        data-testid={`profile-time-toggle-${pd.profile.id}`}
        className="w-full flex items-center gap-3 px-4 py-3 text-left">
        <span className={`text-brand-text-muted transition-transform ${expanded ? 'rotate-90' : ''}`}>▸</span>
        <span className="text-xs font-semibold text-brand-text uppercase tracking-wider">Time limits</span>
        <span className="flex-1" />
        {statusLabel && (
          <span
            data-testid={`profile-time-status-${pd.profile.id}`}
            data-status={status}
            className={`text-xs font-mono ${
              status === 'error' ? 'text-red-700'
              : status === 'saving' ? 'text-brand-text-muted'
              : 'text-brand-accent'
            }`}>
            {statusLabel}
          </span>
        )}
      </button>

      {!expanded && (
        <div className="px-4 pb-3 space-y-1">
          {pd.timeLimit
            ? (
              <p className="text-sm text-brand-text">
                Daily limit: <span className="text-brand-ink font-medium">{pd.timeLimit.dailyMinutes} min</span>
              </p>
            )
            : <p className="text-xs text-brand-text-muted">No daily limit.</p>
          }
          <p className="text-xs text-brand-text-muted">
            Cross-device overlap: <span className="text-brand-text">
              {pd.profile.crossDeviceOverlapMode === 'sum' ? 'count each device' : 'combine overlap'}
            </span>
          </p>
        </div>
      )}

      {expanded && (
        <div className="px-4 pb-4 space-y-4 border-t border-brand-border pt-3">
          {errorMsg && (
            <div className="bg-red-500/10 border border-red-500/30 text-red-700 text-xs rounded-lg px-3 py-2">
              {errorMsg}
            </div>
          )}

          <div>
            <label className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2">
              Daily time limit (minutes)
            </label>
            <input type="number" min={0}
              value={form.timeLimit}
              disabled={!isWriter}
              data-testid={`profile-time-limit-${pd.profile.id}`}
              onChange={e => update({ timeLimit: e.target.value })}
              placeholder="Leave blank for unlimited"
              className="w-full bg-brand-surface border border-brand-border-strong rounded-xl px-4 py-2.5 text-brand-ink placeholder-brand-text-muted focus:outline-none focus:border-brand-accent disabled:opacity-60" />
          </div>

          {/* #751 cross-device overlap radios, lifted from the modal. */}
          <div>
            <label className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2">
              Cross-device overlap
            </label>
            <div className="space-y-2">
              <label className="flex items-start gap-3 text-sm text-brand-text cursor-pointer">
                <input
                  type="radio"
                  name={`overlap-${pd.profile.id}`}
                  data-testid={`profile-time-overlap-sum-${pd.profile.id}`}
                  disabled={!isWriter}
                  checked={form.crossDeviceOverlapMode === 'sum'}
                  onChange={() => update({ crossDeviceOverlapMode: 'sum' })}
                  className="mt-1 w-4 h-4 accent-brand-accent"
                />
                <span>
                  <span className="font-medium text-brand-ink">Count each device separately</span>
                  <span className="text-brand-text-muted"> (default)</span>
                  <span className="block text-xs text-brand-text mt-0.5">
                    add per-device totals. Two devices on this profile both active in the same 5-minute window count as 10 minutes against the daily cap.
                  </span>
                </span>
              </label>
              <label className="flex items-start gap-3 text-sm text-brand-text cursor-pointer">
                <input
                  type="radio"
                  name={`overlap-${pd.profile.id}`}
                  data-testid={`profile-time-overlap-dedup-${pd.profile.id}`}
                  disabled={!isWriter}
                  checked={form.crossDeviceOverlapMode === 'dedup'}
                  onChange={() => update({ crossDeviceOverlapMode: 'dedup' })}
                  className="mt-1 w-4 h-4 accent-brand-accent"
                />
                <span>
                  <span className="font-medium text-brand-ink">Combine overlapping device usage</span>
                  <span className="text-brand-text-muted"> (one profile = one human)</span>
                  <span className="block text-xs text-brand-text mt-0.5">
                    union the per-device active windows. Two devices both active in the same 5-minute window count as 5 minutes against the daily cap. Right when one person carries an iPad and a phone for the same profile.
                  </span>
                </span>
              </label>
            </div>
          </div>

          {/* #1471 — the persistent soft/hard "Pause mode" radios were removed
              here. The choice is now made at the moment of pausing, via the
              picker on the row Pause action (see ProfileShellRow). */}
        </div>
      )}
    </div>
  )
}


// #1494 — schedules subsection. A profile's block schedules are now #1069
// household named schedules attached as BLOCK rules (downtime while active),
// persisted via PUT /api/profiles/{id}/schedules -> profile_schedule_rules,
// which enforcement reads (#1490). The old inline per-profile window editor
// wrote the dead V1 `schedules` table — editing it was a silent no-op — so it
// is gone, replaced by the reusable SchedulePicker (household schedules +
// "Custom" inline that authors a reusable named schedule). A profile can
// reference many; add/remove autosaves the full id set (replace semantics),
// mirroring the per-app schedule-rule editor (#1380).
function ScheduleSubsection({
  pd, isWriter,
}: {
  pd: ProfileDetail
  isWriter: boolean
}) {
  const invalidators = useInvalidators()
  const { data: namedSchedules = [] } = useNamedSchedules()
  const [expanded, setExpanded] = useState(false)
  const [status, setStatus] = useState<SaveStatus>('idle')
  const [errorMsg, setErrorMsg] = useState<string | null>(null)
  const [pickId, setPickId] = useState<number | null>(null)

  const attachedIds = pd.scheduleIds ?? []
  const scheduleNameById = useMemo(() => {
    const m = new Map<number, string>()
    namedSchedules.forEach(s => m.set(s.id, s.name))
    return m
  }, [namedSchedules])

  const savedTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null)
  useEffect(() => () => {
    if (savedTimerRef.current) clearTimeout(savedTimerRef.current)
  }, [])

  async function save(nextIds: number[]) {
    setStatus('saving')
    setErrorMsg(null)
    try {
      await api.profiles.setSchedules(pd.profile.id, nextIds)
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

  function attach() {
    if (pickId == null || attachedIds.includes(pickId)) return
    const next = [...attachedIds, pickId]
    setPickId(null)
    void save(next)
  }
  function detach(id: number) {
    void save(attachedIds.filter(x => x !== id))
  }

  const statusLabel =
    status === 'saving' ? 'Saving…'
    : status === 'saved' ? 'Saved'
    : status === 'error' ? 'Save failed'
    : ''

  return (
    <div data-testid={`profile-schedule-subsection-${pd.profile.id}`}
      className="bg-brand-surface/40 border border-brand-border rounded-xl">
      <button type="button"
        onClick={() => setExpanded(e => !e)}
        aria-expanded={expanded}
        data-testid={`profile-schedule-toggle-${pd.profile.id}`}
        className="w-full flex items-center gap-3 px-4 py-3 text-left">
        <span className={`text-brand-text-muted transition-transform ${expanded ? 'rotate-90' : ''}`}>▸</span>
        <span className="text-xs font-semibold text-brand-text uppercase tracking-wider">Schedules</span>
        <span className="flex-1" />
        {statusLabel && (
          <span
            data-testid={`profile-schedule-status-${pd.profile.id}`}
            data-status={status}
            className={`text-xs font-mono ${
              status === 'error' ? 'text-red-700'
              : status === 'saving' ? 'text-brand-text-muted'
              : 'text-brand-accent'
            }`}>
            {statusLabel}
          </span>
        )}
      </button>

      {!expanded && (
        <div className="px-4 pb-3 space-y-1">
          {attachedIds.length > 0
            ? attachedIds.map(id => (
              <div key={id} className="flex justify-between text-sm bg-brand-alt/50 rounded-lg px-3 py-2">
                <span className="text-brand-text">{scheduleNameById.get(id) ?? `Schedule ${id}`}</span>
                <span className="text-amber-700 font-mono text-xs">Blocked during</span>
              </div>
            ))
            : <p className="text-xs text-brand-text-muted">No schedules.</p>
          }
        </div>
      )}

      {expanded && (
        <div className="px-4 pb-4 space-y-4 border-t border-brand-border pt-3">
          {errorMsg && (
            <div className="bg-red-500/10 border border-red-500/30 text-red-700 text-xs rounded-lg px-3 py-2">
              {errorMsg}
            </div>
          )}

          <div className="space-y-2">
            <label className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider">
              Block schedules
            </label>
            <p className="text-xs text-brand-text-muted">
              Internet is blocked for this profile while an attached schedule's
              window is active. Schedules are shared household schedules — edit
              one on the Schedules page and it changes everywhere it's used.
            </p>

            {attachedIds.length === 0 && (
              <p className="text-xs text-brand-text-muted italic">No schedules attached.</p>
            )}

            {attachedIds.map(id => (
              <div
                key={id}
                data-testid={`profile-schedule-attached-${pd.profile.id}-${id}`}
                className="flex items-center gap-2 bg-brand-surface border border-brand-border-strong rounded-lg px-3 py-2">
                <span className="flex-1 text-sm text-brand-ink">
                  {scheduleNameById.get(id) ?? `Schedule ${id}`}
                </span>
                {isWriter && (
                  <button type="button"
                    data-testid={`profile-schedule-attached-${pd.profile.id}-${id}-remove`}
                    aria-label={`Remove ${scheduleNameById.get(id) ?? 'schedule'}`}
                    onClick={() => detach(id)}
                    className="text-brand-text-muted hover:text-red-700 transition-colors leading-none text-sm">×</button>
                )}
              </div>
            ))}

            {isWriter && (
              <div className="space-y-2 pt-1">
                <SchedulePicker
                  value={pickId}
                  onChange={setPickId}
                  testId={`profile-schedule-picker-${pd.profile.id}`}
                />
                <button type="button"
                  data-testid={`profile-schedule-add-${pd.profile.id}`}
                  disabled={pickId == null || attachedIds.includes(pickId)}
                  onClick={attach}
                  className="text-xs px-2.5 py-1 rounded-lg bg-brand-accent/10 text-brand-accent font-medium hover:bg-brand-accent/20 disabled:opacity-50">
                  Attach schedule
                </button>
              </div>
            )}
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
  updateProfile: (body: PatchProfileRequest) => Promise<unknown>
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
      className="bg-brand-surface/40 border border-brand-border rounded-xl"
    >
      <button
        type="button"
        aria-expanded={open}
        onClick={() => setOpen(v => !v)}
        data-testid={`profile-apps-toggle-${pd.profile.id}`}
        className="w-full flex items-center justify-between px-4 py-3 text-left"
      >
        <span className="flex items-center gap-2">
          <span className={`text-brand-text-muted transition-transform ${open ? 'rotate-90' : ''}`}>▸</span>
          <span className="text-sm font-semibold text-brand-ink">Apps</span>
        </span>
        <span className="text-xs text-brand-text">{summary}</span>
      </button>

      {open && (
        <div className="px-4 pb-4 space-y-4 border-t border-brand-border pt-3">
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
  // #1983 — blocklist id → display name for the per-app overlap warning badges.
  const { data: blocklists = [] } = useBlocklists()
  const blocklistNameById = useMemo(() => {
    const m = new Map<string, string>()
    for (const b of blocklists) m.set(b.id, b.name)
    return m
  }, [blocklists])
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
      className="text-xs text-brand-accent hover:text-brand-accent"
    >
      {pickerOpen ? 'Close' : '+ Add app'}
    </button>
  )

  return (
    <div data-testid={testIdPrefix}>
      <div className="flex items-center justify-between mb-2 gap-3">
        <label className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider">
          Apps
        </label>
        <div className="flex items-center gap-3">
          {headerCta}
          <Link
            to="/apps"
            data-testid={`${testIdPrefix}-manage-link`}
            className="text-xs text-brand-accent hover:text-brand-accent"
          >
            Manage apps →
          </Link>
        </div>
      </div>
      {isNew || profileId == null ? (
        <p className="text-xs text-brand-text-muted">
          Save this profile first to assign apps.
        </p>
      ) : apps.length === 0 ? (
        <EmptyState
          variant="inline"
          title="No apps yet."
          hint={
            <>
              <Link
                to="/apps"
                data-testid={`${testIdPrefix}-empty-link`}
                className="text-brand-accent hover:text-brand-accent underline"
              >
                Create one
              </Link>
              {' '}to block, allow, or time-limit a group of hosts.
            </>
          }
        />
      ) : (
        <div className="space-y-2">
          {assigned.length === 0 && !pickerOpen && (
            <p className="text-xs text-brand-text-muted" data-testid={`${testIdPrefix}-none-assigned`}>
              No apps assigned to this profile.{' '}
              <button
                type="button"
                data-testid={`${testIdPrefix}-none-assigned-add`}
                onClick={() => setPickerOpen(true)}
                className="text-brand-accent hover:text-brand-accent underline"
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
              blocklistNameById={blocklistNameById}
            />
          ))}
          {pickerOpen && (
            <div
              data-testid={`${testIdPrefix}-picker`}
              className="bg-brand-surface border border-brand-border-strong rounded-xl p-3 space-y-2"
            >
              <input
                type="text"
                autoFocus
                value={pickerFilter}
                onChange={e => setPickerFilter(e.target.value)}
                placeholder="Filter apps…"
                data-testid={`${testIdPrefix}-picker-filter`}
                className="w-full bg-white border border-brand-border-strong rounded-lg px-2 py-1 text-brand-ink text-xs"
              />
              {pickerMatches.length === 0 ? (
                <p className="text-xs text-brand-text-muted" data-testid={`${testIdPrefix}-picker-empty`}>
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
                      className="w-full flex items-center gap-2 px-2 py-1.5 rounded-lg bg-white hover:bg-brand-alt border border-brand-border-strong text-left"
                    >
                      <AppIcon icon={a.app.icon} iconType={a.app.iconType} size="sm" className="w-5 text-center" />
                      <span className="text-sm text-brand-ink flex-1 truncate">{a.app.name}</span>
                      <AppBlocklistWarningBadge blocklisted={a.blocklisted} nameById={blocklistNameById} />
                      <span className="text-xs text-brand-text-muted">{a.hosts.length} host{a.hosts.length === 1 ? '' : 's'}</span>
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

function AppRow({ app, profileId, onChanged, usedMins, blocklistNameById }: {
  app: AppDetail
  profileId: number
  onChanged: () => void | Promise<void>
  // #1061 — today's proportional minutes attributed to this app for this
  // profile. Undefined → not loaded yet (e.g. subsection just opened); the
  // bar simply doesn't render until the value arrives.
  usedMins?: number
  // #1983 — blocklist id → display name for the overlap-warning badge.
  blocklistNameById?: Map<string, string>
}) {
  // #1086 — app-policy edits (mode switch, exemptFromDaily toggle, removal)
  // feed the server-side daily-cap math, so a successful write must invalidate
  // the ['time','status'] subtree to refetch the profile-wide used/cap bar.
  // `onChanged` (reloadApps) only refreshes the component-state apps list, not
  // the react-query time-status caches — so we wrap the writes in mutations
  // that run `profileMutated()` on success, mirroring `grantMutation`.
  const invalidators = useInvalidators()
  const setPolicyMutation = useMutation({
    mutationFn: (req: UpsertAppAssignmentRequest) => api.apps.setPolicy(app.app.id, profileId, req),
    onSuccess: () => invalidators.profileMutated(),
  })
  const deletePolicyMutation = useMutation({
    mutationFn: () => api.apps.deletePolicy(app.app.id, profileId),
    onSuccess: () => invalidators.profileMutated(),
  })

  const current = findAssignment(app, profileId)
  const [minutesDraft, setMinutesDraft] = useState<string>(() =>
    current?.mode === 'time_limited' && current.dailyMinutes != null
      ? String(current.dailyMinutes)
      : '',
  )
  const [busy, setBusy] = useState(false)
  const [localError, setLocalError] = useState<string | null>(null)
  // #1380 — attached schedule rules, seeded from the persisted assignment and
  // re-seeded when it changes from outside (mirrors minutesDraft below).
  const [scheduleRules, setScheduleRules] = useState<AppScheduleRule[]>(
    () => current?.scheduleRules ?? [],
  )
  const rulesSig = JSON.stringify(current?.scheduleRules ?? [])
  // Re-seed the input when the persisted policy changes from outside
  // (e.g. another tab, or after our own setPolicy round-trip lands).
  // Without this the input keeps stale values once `current` updates.
  useEffect(() => {
    if (busy) return
    const next = current?.mode === 'time_limited' && current.dailyMinutes != null
      ? String(current.dailyMinutes) : ''
    setMinutesDraft(next)
  }, [current?.mode, current?.dailyMinutes])
  useEffect(() => {
    if (busy) return
    setScheduleRules(current?.scheduleRules ?? [])
  }, [rulesSig])

  // PUT replaces the whole assignment, so every write must carry the current
  // schedule rules (additive `scheduleRules`, omitted when empty so the no-rule
  // payload shape — and the existing assertions on it — stay unchanged).
  async function apply(
    mode: AppMode,
    dailyMinutes: number | null,
    exemptFromDaily?: boolean,
    rules?: AppScheduleRule[],
    // #1679: when omitted, preserves the current assignment's value (defaulting to
    // true). Callers that don't change this dimension leave it undefined.
    allowedDuringScheduleBlock?: boolean,
  ) {
    const effectiveRules = rules ?? scheduleRules
    // #1679: allowedDuringScheduleBlock is only meaningful for 'allowed' mode (it controls
    // whether the extraAllowed carve-out survives a Schedule block). Only include it in the
    // request when submitting an 'allowed' assignment so it:
    //   - is preserved across exemptFromDaily toggles and schedule-rule edits on an Allowed app;
    //   - is NOT inadvertently included when switching to 'blocked' or 'time_limited' (where it
    //     has no effect and would break the existing setPolicy call assertions in the test suite).
    const effectiveScheduleBlock =
      mode === 'allowed'
        ? allowedDuringScheduleBlock ?? current?.allowedDuringScheduleBlock ?? true
        : undefined
    const req: UpsertAppAssignmentRequest = {
      mode,
      dailyMinutes,
      ...(exemptFromDaily !== undefined ? { exemptFromDaily } : {}),
      ...(effectiveRules.length > 0 ? { scheduleRules: effectiveRules } : {}),
      ...(effectiveScheduleBlock !== undefined ? { allowedDuringScheduleBlock: effectiveScheduleBlock } : {}),
    }
    setBusy(true)
    setLocalError(null)
    try {
      await setPolicyMutation.mutateAsync(req)
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
      await deletePolicyMutation.mutateAsync()
      setMinutesDraft('')
      await onChanged()
    } catch (e) {
      setLocalError(e instanceof Error ? e.message : 'Failed to clear')
    } finally {
      setBusy(false)
    }
  }

  // #1007 / #2747 — the ONE writer of exemptFromDaily on this row. Both surfaces
  // that govern the flag (the "Counts toward daily limit" checkbox below and
  // ScheduleRuleEditor's blocked-mode toggle) call this, so the write can't
  // drift between them. A time_limited app with no cap set yet has nothing to
  // exempt from, so that case is a no-op.
  async function writeExempt(nextExempt: boolean) {
    if (mode == null) return
    if (mode === 'time_limited' && current?.dailyMinutes == null) return
    await apply(mode, current?.dailyMinutes ?? null, nextExempt)
  }

  // The row checkbox is phrased POSITIVELY and inverted here: checked means
  // "counts", i.e. exemptFromDaily: false. Shown for time_limited apps and,
  // since #2747, for Allowed-mode apps, whose usage otherwise silently burns
  // the profile's daily allowance with no way to opt out.
  const toggleExempt = writeExempt

  const mode = current?.mode ?? null
  const isTimeLimited = mode === 'time_limited'
  const currentMinutes = isTimeLimited ? current?.dailyMinutes ?? null : null

  // #1380 — schedule-rule add/remove and the exempt-from-daily toggle persist
  // the whole assignment immediately (autosave, no Save button). Re-applying
  // the current mode/minutes keeps everything but the changed dimension intact.
  async function addRule(scheduleId: number, ruleMode: AppScheduleMode) {
    if (mode == null) return
    if (scheduleRules.some(r => r.scheduleId === scheduleId && r.mode === ruleMode)) return
    const next = [...scheduleRules, { scheduleId, mode: ruleMode }]
    setScheduleRules(next)
    await apply(mode, current?.dailyMinutes ?? null, current?.exemptFromDaily, next)
  }

  async function removeRule(scheduleId: number, ruleMode: AppScheduleMode) {
    if (mode == null) return
    const next = scheduleRules.filter(r => !(r.scheduleId === scheduleId && r.mode === ruleMode))
    setScheduleRules(next)
    await apply(mode, current?.dailyMinutes ?? null, current?.exemptFromDaily, next)
  }

  // ScheduleRuleEditor's toggle names the exemption directly (no inversion) —
  // same single writer, so the two surfaces cannot disagree about the payload.
  const setScheduleExempt = writeExempt

  // #1679: toggle "block during scheduled downtime" for Allowed-mode apps.
  // nextAllowed = !checkbox.checked (checkbox is "block during schedule", NOT "allow during schedule").
  async function toggleScheduleBlock(nextAllowed: boolean) {
    if (mode == null) return
    await apply(mode, current?.dailyMinutes ?? null, current?.exemptFromDaily, undefined, nextAllowed)
  }

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
  const off = 'bg-brand-alt text-brand-text border-brand-border-strong hover:border-brand-border-strong'
  const onBlocked = 'bg-red-500/20 text-red-700 border-red-500/40'
  const onAllowed = 'bg-brand-accent/20 text-brand-accent border-brand-accent/40'

  return (
    <div
      data-testid={`app-row-${app.app.id}`}
      className="bg-brand-surface border border-brand-border-strong rounded-xl p-3 space-y-2"
    >
      <div className="flex items-center gap-3">
        <span className="w-7 text-center inline-flex items-center justify-center">
          <AppIcon icon={app.app.icon} iconType={app.app.iconType} size="md" />
        </span>
        <div className="flex-1 min-w-0">
          <p className="text-sm text-brand-ink font-medium truncate flex items-center gap-2">
            {app.app.name}
            <AppBlocklistWarningBadge blocklisted={app.blocklisted} nameById={blocklistNameById} />
          </p>
          <p className="text-xs text-brand-text-muted font-mono truncate">{app.hosts.length} host{app.hosts.length === 1 ? '' : 's'}</p>
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
            className={`w-16 rounded-lg px-2 py-1 text-brand-ink text-xs border transition-colors disabled:opacity-50 ${
              isTimeLimited
                ? 'bg-amber-500/10 border-amber-500/40 text-amber-800 placeholder-amber-200/40'
                : 'bg-white border-brand-border-strong'
            }`}
          />
          <span className="text-xs text-brand-text-muted">min/day</span>
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
          <div className="flex-1 h-1 bg-brand-alt rounded-full overflow-hidden">
            <div
              className={`h-full rounded-full ${
                usedMins >= currentMinutes ? 'bg-red-500' : 'bg-brand-accent'
              }`}
              style={{
                width: `${Math.min(100, Math.round((usedMins / currentMinutes) * 100))}%`,
              }}
            />
          </div>
          <span className="text-xs font-mono text-brand-text shrink-0">
            {formatMins(usedMins)} / {formatMins(currentMinutes)}
          </span>
        </div>
      )}
      {/* #1433 — surface today's time-used even for apps without a daily
          limit, so the operator has at-a-glance usage visibility regardless
          of whether a limit is set. Plain "Xm today" text — no cap and no
          progress bar (the bar above is reserved for time-limited apps).
          Hidden until usage loads and only when there is some to show. */}
      {!isTimeLimited && usedMins != null && usedMins > 0 && (
        <div
          data-testid={`app-row-${app.app.id}-used`}
          className="text-xs font-mono text-brand-text"
        >
          {formatMins(usedMins)} today
        </div>
      )}
      {/* #1007 / #2747 — the single exempt-from-daily control for this app row.
          Shown for time_limited AND allowed apps; NOT for blocked apps, which
          drop all traffic and so accrue no usage to exempt (a blocked app
          carved open by an allowed_during rule keeps the in-window toggle in
          ScheduleRuleEditor instead). Polarity is positive-and-inverted:
          checked ⇒ exemptFromDaily: false. */}
      {(mode === 'time_limited' || mode === 'allowed') && (
        <label className={`flex gap-2 text-xs text-brand-text cursor-pointer select-none ${
          // Only the allowed row carries a wrapping explanation, so only it needs
          // top alignment; the time_limited row keeps its shipped one-line layout.
          mode === 'allowed' ? 'items-start' : 'items-center'
        }`}>
          <input
            type="checkbox"
            data-testid={`app-row-${app.app.id}-counts-toward-daily`}
            checked={!(current?.exemptFromDaily ?? true)}
            disabled={busy}
            onChange={e => toggleExempt(!e.target.checked)}
            className={`w-3.5 h-3.5 accent-amber-500 ${mode === 'allowed' ? 'mt-0.5' : ''}`}
          />
          <span>
            Counts toward daily limit
            {!(current?.exemptFromDaily ?? true) && (
              <span className="ml-1 text-amber-700">(usage reduces overall remaining time)</span>
            )}
            {/* #2747 — spell the exempt side out only for an Allowed app, whose
                default IS exempt and which has no cap of its own to reason from.
                Budget ONLY: for a plain Allowed app the flag changes nothing about
                reachability. `ProfileAppDispositions.enforcement` carves an
                Allowed app's hosts into extraAllowed unconditionally (capExhausted
                is consulted only on the allowed_during branch), and
                `exemptUnderCapHosts` never sees it — `capGroups` filters
                `state.perApp` to TimeLimited. So the app outlives the cap either
                way; the flag decides only whether its usage counts. The
                time_limited row is left exactly as it shipped. */}
            {mode === 'allowed' && (current?.exemptFromDaily ?? true) && (
              <span className="ml-1 text-brand-text-muted">
                (exempt — this app's usage doesn't reduce the profile's remaining time)
              </span>
            )}
          </span>
        </label>
      )}
      {/* #1679: block-during-schedule toggle — only shown for Allowed-mode apps,
          where the extraAllowed carve-out is the relevant enforcement path. */}
      {mode === 'allowed' && (
        <label className="flex items-center gap-2 text-xs text-brand-text cursor-pointer select-none">
          <input
            type="checkbox"
            data-testid={`app-row-${app.app.id}-block-during-schedule`}
            checked={!(current?.allowedDuringScheduleBlock ?? true)}
            disabled={busy}
            onChange={e => toggleScheduleBlock(!e.target.checked)}
            className="w-3.5 h-3.5 accent-amber-500"
          />
          <span>Block during scheduled downtime</span>
        </label>
      )}
      {mode != null && (
        <ScheduleRuleEditor
          appId={app.app.id}
          rules={scheduleRules}
          exemptFromDaily={current?.exemptFromDaily ?? true}
          showExemptToggle={mode === 'blocked'}
          busy={busy}
          onAdd={addRule}
          onRemove={removeRule}
          onSetExempt={setScheduleExempt}
        />
      )}
      {localError && (
        <p className="text-xs text-red-700" data-testid={`app-row-${app.app.id}-error`}>{localError}</p>
      )}
    </div>
  )
}

// #1380 — per-app schedule rules on an app's profile assignment. Each rule
// attaches a #1069 household named schedule (via the shared SchedulePicker)
// with a mode:
//   • Allowed during — the app stays reachable while the schedule's window is
//     active, even during profile downtime (a carve-out). Still subject to the
//     daily time limit unless the assignment is exempt (rows 9a–9c).
//   • Blocked during — the app is dropped while the window is active, even when
//     the profile is otherwise unrestricted.
// No bespoke time editor here — the named schedule owns its day/time windows
// (the picker's "Custom" flow authors a reusable one). Add/remove autosaves the
// assignment (no Save button), consistent with the block/allow toggles above.
const SCHEDULE_MODE_LABEL: Record<AppScheduleMode, string> = {
  allowed_during: 'Allowed during',
  blocked_during: 'Blocked during',
}

function ScheduleRuleEditor({
  appId, rules, exemptFromDaily, showExemptToggle, busy,
  onAdd, onRemove, onSetExempt,
}: {
  appId: number
  rules: AppScheduleRule[]
  exemptFromDaily: boolean
  // #2747 — true only for blocked-mode apps. time_limited and allowed apps
  // govern the same exemptFromDaily flag from their own "Counts toward daily
  // limit" row checkbox; exactly one control per mode. A blocked app has no row
  // checkbox (nothing to exempt) but CAN be carved open by an allowed_during
  // rule, which is where the in-window copy below is accurate.
  showExemptToggle: boolean
  busy: boolean
  onAdd: (scheduleId: number, mode: AppScheduleMode) => void | Promise<void>
  onRemove: (scheduleId: number, mode: AppScheduleMode) => void | Promise<void>
  onSetExempt: (next: boolean) => void | Promise<void>
}) {
  const [pickMode, setPickMode] = useState<AppScheduleMode>('allowed_during')
  const [pickScheduleId, setPickScheduleId] = useState<number | null>(null)
  const { data: namedSchedules = [] } = useNamedSchedules()

  const scheduleNameById = useMemo(() => {
    const m = new Map<number, string>()
    namedSchedules.forEach(s => m.set(s.id, s.name))
    return m
  }, [namedSchedules])

  const hasAllowedRule = rules.some(r => r.mode === 'allowed_during')

  function add() {
    if (pickScheduleId == null) return
    void onAdd(pickScheduleId, pickMode)
    setPickScheduleId(null)
  }

  const modeBtn = 'text-xs px-2.5 py-1 rounded-lg border border-transparent transition-colors disabled:opacity-50'
  const modeOn = 'bg-brand-accent/20 text-brand-accent'
  const modeOff = 'bg-brand-alt text-brand-text'

  return (
    <div
      data-testid={`app-row-${appId}-schedules`}
      className="border-t border-brand-border pt-2 space-y-2"
    >
      <p className="text-xs font-semibold text-brand-text-muted uppercase tracking-wider">
        Schedules
      </p>

      {rules.length === 0 && (
        <p className="text-xs text-brand-text-muted italic">
          No schedule rules. Attach a named schedule to make this app reachable
          or blocked only during a window.
        </p>
      )}

      {rules.map(r => (
        <div
          key={`${r.scheduleId}-${r.mode}`}
          data-testid={`app-row-${appId}-schedule-rule-${r.scheduleId}-${r.mode}`}
          className="flex items-start gap-2 bg-brand-surface border border-brand-border-strong rounded-lg px-2.5 py-1.5"
        >
          <div className="flex-1 min-w-0">
            <p className="text-xs text-brand-ink">
              <span
                className={`font-medium ${r.mode === 'allowed_during' ? 'text-brand-accent' : 'text-red-700'}`}
              >
                {SCHEDULE_MODE_LABEL[r.mode]}
              </span>
              {' '}
              <span className="font-medium">{scheduleNameById.get(r.scheduleId) ?? `schedule ${r.scheduleId}`}</span>
            </p>
            <p className="text-[11px] text-brand-text-muted">
              {r.mode === 'allowed_during'
                ? 'Reachable while this window is active — even during profile downtime.'
                : 'Blocked while this window is active — even when the profile is otherwise allowed.'}
            </p>
          </div>
          <button
            type="button"
            data-testid={`app-row-${appId}-schedule-rule-${r.scheduleId}-${r.mode}-remove`}
            aria-label={`Remove ${SCHEDULE_MODE_LABEL[r.mode]} ${scheduleNameById.get(r.scheduleId) ?? 'schedule'} rule`}
            disabled={busy}
            onClick={() => onRemove(r.scheduleId, r.mode)}
            className="text-brand-text-muted hover:text-red-700 transition-colors leading-none text-sm disabled:opacity-50"
          >×</button>
        </div>
      ))}

      {/* #1380 — exempt-from-daily surfaced alongside an allowed-during rule so
          the cap-bites-non-exempt behaviour (rows 9a–9c) isn't a surprise. */}
      {hasAllowedRule && showExemptToggle && (
        <label
          data-testid={`app-row-${appId}-schedule-exempt`}
          className="flex items-start gap-2 text-xs text-brand-text cursor-pointer select-none"
        >
          <input
            type="checkbox"
            checked={exemptFromDaily}
            disabled={busy}
            onChange={e => onSetExempt(e.target.checked)}
            className="w-3.5 h-3.5 mt-0.5 accent-amber-500"
          />
          <span>
            {exemptFromDaily
              ? 'Exempt from the daily time limit — reachable in-window even past the cap.'
              : 'Still blocked by the daily time limit — the cap applies even in-window.'}
          </span>
        </label>
      )}

      <div className="space-y-2">
        <div className="inline-flex rounded-lg bg-brand-alt p-0.5">
          {(['allowed_during', 'blocked_during'] as const).map(m => (
            <button
              key={m}
              type="button"
              data-testid={`app-row-${appId}-schedule-mode-${m}`}
              disabled={busy}
              onClick={() => setPickMode(m)}
              className={`${modeBtn} ${pickMode === m ? modeOn : modeOff}`}
            >{SCHEDULE_MODE_LABEL[m]}</button>
          ))}
        </div>
        <SchedulePicker
          value={pickScheduleId}
          onChange={setPickScheduleId}
          disabled={busy}
          testId={`app-row-${appId}-schedule-picker`}
        />
        <button
          type="button"
          data-testid={`app-row-${appId}-schedule-add`}
          disabled={busy || pickScheduleId == null}
          onClick={add}
          className="text-xs px-2.5 py-1 rounded-lg bg-brand-accent/10 text-brand-accent font-medium hover:bg-brand-accent/20 disabled:opacity-50"
        >Add rule</button>
      </div>
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
    <div data-testid={testId} className="bg-brand-surface/40 border border-brand-border rounded-xl">
      <button
        type="button"
        onClick={() => setOpen(o => !o)}
        aria-expanded={open}
        data-testid={`${testId}-toggle`}
        className="w-full flex items-center justify-between px-4 py-2.5 text-left"
      >
        <span className="flex items-center gap-2">
          <span className={`text-brand-text-muted text-xs transition-transform ${open ? 'rotate-90' : ''}`}>▸</span>
          <span className="text-xs font-semibold text-brand-text uppercase tracking-wider">{title}</span>
        </span>
        <SaveStatusBadge status={status} error={error} testId={`${testId}-status`} />
      </button>
      {open && <div className="px-4 pb-4 pt-1 space-y-3">{children}</div>}
    </div>
  )
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
    () => allDevices.filter(isUnmanaged),
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
        ? <p className="text-xs text-brand-text-muted">No devices assigned.</p>
        : (
          <div className="space-y-1">
            {assigned.map(d => (
              <div key={d.id} data-testid={`profile-device-${d.id}`}
                className="flex items-center justify-between text-sm bg-brand-alt/50 rounded-lg px-3 py-2">
                <div className="min-w-0">
                  <span className="text-brand-text truncate">{d.name}</span>
                  <span className="ml-2 text-brand-text-muted font-mono text-xs">{d.mac}</span>
                </div>
                <button
                  type="button"
                  data-testid={`profile-device-${d.id}-detach`}
                  disabled={busyMac === d.mac}
                  onClick={() => setProfile(d, null)}
                  className="text-xs text-red-700 hover:text-red-700 bg-red-500/10 px-2.5 py-1 rounded-lg disabled:opacity-50"
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
          <p className="text-xs text-brand-text-muted mb-1">Unassigned devices</p>
          <div className="flex flex-wrap gap-2">
            {pickable.map(d => (
              <button
                key={d.id}
                type="button"
                data-testid={`profile-device-add-${d.id}`}
                disabled={busyMac === d.mac}
                onClick={() => setProfile(d, pd.profile.id)}
                className="text-xs px-3 py-1.5 rounded-lg border bg-brand-alt text-brand-text border-brand-border-strong hover:border-brand-accent/40 disabled:opacity-50"
              >
                + {d.name} <span className="text-brand-text-muted font-mono">{d.mac}</span>
              </button>
            ))}
          </div>
        </div>
      )}
    </Subsection>
  )
}
