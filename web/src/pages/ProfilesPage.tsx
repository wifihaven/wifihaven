import { useEffect, useMemo, useRef, useState } from 'react'
import { useMutation } from '@tanstack/react-query'
import { Link, useSearchParams } from 'react-router-dom'
import { api } from '@/api/client'
import { useProfiles, useDevices, useInvalidators, useTimeStatusSummary } from '@/api/queries'
import { useAuth } from '@/hooks/useAuth'
import { useEscapeClose } from '@/hooks/useEscapeClose'
import type {
  AppDetail, AppMode, AppPolicyAssignment,
  BlocklistSummary, CrossDeviceOverlapMode, Device, FailureMode, HouseholdSettings, ProfileDetail,
  ProfileTimeSummary,
  ScheduleRequest, SiteTimeLimitRequest, UpsertProfileRequest, User,
} from '@/types/api'
import { TimezonePicker, browserTimezone } from '@/components/TimezonePicker'
import { AppIcon } from '@/components/AppIcon'
import { PageLoader } from './DashboardPage'
import { formatMins } from './TimePage'

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
  const [categories, setCategories] = useState<BlocklistSummary[]>([])
  const [allUsers, setAllUsers] = useState<User[]>([])
  const [auxLoading, setAuxLoading] = useState(true)
  const loading = profilesQuery.isPending || devicesQuery.isPending || auxLoading
  const [editingId, setEditingId] = useState<number | 'new' | null>(null)
  const [form, setForm] = useState<FormState>(emptyForm())
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)
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
    const [cats, users, hs, appsList] = await Promise.all([
      api.blocklists.list().catch(() => [] as BlocklistSummary[]),
      isAdmin ? api.users.list().catch(() => [] as User[]) : Promise.resolve([] as User[]),
      api.household.get().catch(() => null),
      api.apps.list().catch(() => [] as AppDetail[]),
    ])
    setCategories(cats)
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
    // idempotent under concurrent clicks. #423 tracks adding PATCH so we
    // don't have to send the whole profile.
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
        {isAdmin && (
          <button
            onClick={startNew}
            className="bg-emerald-500 hover:bg-emerald-400 text-black text-sm font-semibold px-4 py-2 rounded-xl transition-colors"
          >
            + New Profile
          </button>
        )}
      </div>

      <div className="space-y-3">
        {profiles.map(pd => (
          <ProfileShellRow
            key={pd.profile.id}
            pd={pd}
            summary={summaryByProfile.get(pd.profile.id)}
            devices={devicesByProfile.get(pd.profile.id) ?? []}
            users={usersByProfile.get(pd.profile.id) ?? []}
            apps={apps}
            allUsers={allUsers}
            isAdmin={isAdmin}
            expanded={expanded.has(pd.profile.id)}
            highlight={highlightId === pd.profile.id}
            defaultTz={household?.dailyResetTz ?? browserTimezone()}
            onToggle={() => toggleExpanded(pd.profile.id)}
            onEdit={() => startEdit(pd)}
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

      {/* #972 — +Time modal lifted from TimePage so the +Time button in the
          collapsed summary works on /profiles too. */}
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
// the summary (name, used/cap + bar, pause chip, +Time). Expanded body shows
// the existing card content + escape-hatch buttons (Edit, Edit users, Pause,
// Delete) that subsections #973-#977 will replace with inline editors.
function ProfileShellRow({
  pd, summary, devices, users, apps, allUsers, isAdmin, expanded, highlight, defaultTz,
  onToggle, onEdit, onDelete, onTogglePause, onGrantTime,
  onAppsChanged, onProfileChanged, updateProfile,
  onToggleUserLink, pendingUserLinks, userLinkError,
}: {
  pd: ProfileDetail
  summary: ProfileTimeSummary | undefined
  devices: Device[]
  users: User[]
  apps: AppDetail[]
  allUsers: User[]
  isAdmin: boolean
  expanded: boolean
  highlight: boolean
  defaultTz: string
  onToggle: () => void
  onEdit: () => void
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
          data-testid={`profile-row-toggle-${pd.profile.id}`}
          className="flex-1 flex items-center gap-3 text-left min-w-0"
        >
          <span className={`text-gray-500 transition-transform ${expanded ? 'rotate-90' : ''}`}>▸</span>
          <span className="font-semibold text-white text-lg truncate">{pd.profile.name}</span>
        </button>

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
          {/* #972: stub expanded view — subsections #973-#977 replace the
              escape-hatch buttons below with inline editors. */}
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
              <button onClick={onEdit}
                data-testid={`profile-open-editor-${pd.profile.id}`}
                className="text-xs text-gray-300 hover:text-white bg-gray-800 px-3 py-1.5 rounded-lg transition-colors">
                Edit
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

          {/* #976: apps subsection — inline app-policy editor (with the
              transitional extraAllowed/extraBlocked textareas tucked
              underneath until #764 migrates them off the schema).
              Replaces the read-only summary that used to live here; the
              modal Edit still works while #978 cleans it up. */}
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
              this profile. Autosave-default; the subsection itself is
              collapsed-by-default and its header carries the at-a-glance
              summary (limit + schedule list) so the collapsed view still
              reads "Daily limit: 120 min", "Bedtime · 21:00 → 07:00". */}
          <TimeSubsection pd={pd} isAdmin={isAdmin} defaultTz={defaultTz} />

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
type SaveStatus = 'idle' | 'saving' | 'saved' | 'error'

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

function ProfileEditor({
  isNew, profileId, form, setForm, categories, apps, onAppsChanged,
  saving, error, onCancel, onSave, defaultTz,
}: {
  isNew: boolean
  profileId: number | null
  form: FormState
  setForm: (updater: (f: FormState) => FormState) => void
  categories: BlocklistSummary[]
  apps: AppDetail[]
  onAppsChanged: () => void | Promise<void>
  saving: boolean
  error: string | null
  onCancel: () => void
  onSave: () => void
  defaultTz: string
}) {
  useEscapeClose(onCancel)
  function toggleCat(c: string) {
    setForm(f => ({
      ...f,
      blockedCategories: f.blockedCategories.includes(c)
        ? f.blockedCategories.filter(x => x !== c)
        : [...f.blockedCategories, c],
    }))
  }

  function addSchedule() {
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
                  const on = form.blockedCategories.includes(c.id)
                  return (
                    <button key={c.id} type="button" onClick={() => toggleCat(c.id)}
                      title={c.description ?? c.id}
                      className={`text-xs px-3 py-1.5 rounded-lg border transition-colors ${
                        on
                          ? 'bg-red-500/20 text-red-300 border-red-500/40'
                          : 'bg-gray-800 text-gray-400 border-gray-700 hover:border-gray-600'
                      }`}>
                      {on ? '✓ ' : ''}{c.name}
                    </button>
                  )
                })}
              </div>
            )
          }
          {form.blockedCategories.filter(id => !categories.some(c => c.id === id)).length > 0 && (
            <p className="text-xs text-yellow-400 mt-2">
              Also blocked (no longer in blocklist): {form.blockedCategories.filter(id => !categories.some(c => c.id === id)).join(', ')}
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

// #976 — apps subsection of the merged /profiles expanded card.
// Default collapsed; opening reveals the same `<AppsSection>` the modal
// shows, plus the transitional extraBlocked/extraAllowed textareas with
// debounced autosave. The textareas disappear once #764 migrates those
// fields onto apps; PATCH support (#423) would let us send only the
// changed fields instead of a full PUT body, but the textareas are
// short-lived enough that PUT is fine. Subsection is labelled "Apps"
// (not "Rules") because time limits are their own subsection (#975)
// and domain blocklists are on their way out.
function AppsRulesSubsection({
  pd, apps, onAppsChanged, onProfileChanged, updateProfile,
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
          />
          <LegacyDomainListsEditor
            pd={pd}
            updateProfile={updateProfile}
            onSaved={onProfileChanged}
          />
        </div>
      )}
    </div>
  )
}

function profileDetailToUpsert(pd: ProfileDetail): UpsertProfileRequest {
  return {
    name: pd.profile.name,
    blockedCategories: pd.profile.blockedCategories,
    extraBlocked: pd.profile.extraBlocked,
    extraAllowed: pd.profile.extraAllowed,
    paused: pd.profile.paused,
    timeLimit: pd.timeLimit ? pd.timeLimit.dailyMinutes : null,
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

function LegacyDomainListsEditor({
  pd, updateProfile, onSaved,
}: {
  pd: ProfileDetail
  updateProfile: (body: UpsertProfileRequest) => Promise<unknown>
  onSaved: () => void | Promise<unknown>
}) {
  const [blocked, setBlocked] = useState(pd.profile.extraBlocked.join('\n'))
  const [allowed, setAllowed] = useState(pd.profile.extraAllowed.join('\n'))
  const [status, setStatus] = useState<'idle' | 'saving' | 'saved' | 'error'>('idle')
  const [errMsg, setErrMsg] = useState<string | null>(null)
  // Track the persisted values so we can detect dirty state and re-seed
  // when an external mutation lands (e.g. modal Save). Comparing against
  // the raw join lets us avoid stomping in-flight edits.
  const persistedBlocked = pd.profile.extraBlocked.join('\n')
  const persistedAllowed = pd.profile.extraAllowed.join('\n')
  useEffect(() => {
    if (status === 'saving') return
    setBlocked(persistedBlocked)
    setAllowed(persistedAllowed)
  }, [persistedBlocked, persistedAllowed])

  const splitLines = (s: string) => s.split('\n').map(x => x.trim()).filter(Boolean)

  useEffect(() => {
    if (blocked === persistedBlocked && allowed === persistedAllowed) return
    const t = setTimeout(async () => {
      setStatus('saving')
      setErrMsg(null)
      try {
        const body = profileDetailToUpsert(pd)
        body.extraBlocked = splitLines(blocked)
        body.extraAllowed = splitLines(allowed)
        await updateProfile(body)
        await onSaved()
        setStatus('saved')
      } catch (e) {
        setStatus('error')
        setErrMsg(e instanceof Error ? e.message : 'Failed to save')
      }
    }, 800)
    return () => clearTimeout(t)
  }, [blocked, allowed])

  return (
    <div data-testid={`profile-legacy-domains-${pd.profile.id}`}>
      <div className="flex items-center justify-between mb-2">
        <label className="block text-xs font-semibold text-gray-500 uppercase tracking-wider">
          Extra domains <span className="normal-case text-gray-600">(legacy — migrating to apps in #764)</span>
        </label>
        <span
          data-testid={`profile-legacy-domains-status-${pd.profile.id}`}
          className={`text-xs ${
            status === 'saving' ? 'text-gray-400'
              : status === 'saved' ? 'text-emerald-400'
              : status === 'error' ? 'text-red-400'
              : 'text-transparent'
          }`}
        >
          {status === 'saving' ? 'Saving…' : status === 'saved' ? 'Saved' : status === 'error' ? (errMsg ?? 'Save failed') : '·'}
        </span>
      </div>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-3">
        <div>
          <p className="text-xs text-gray-500 mb-1">Blocked domains</p>
          <textarea
            value={blocked}
            onChange={e => setBlocked(e.target.value)}
            placeholder="One domain per line"
            rows={3}
            data-testid={`profile-legacy-blocked-${pd.profile.id}`}
            className="w-full bg-gray-950 border border-gray-700 rounded-xl px-3 py-2 text-white text-sm font-mono placeholder-gray-600 focus:outline-none focus:border-emerald-500"
          />
        </div>
        <div>
          <p className="text-xs text-gray-500 mb-1">Allowed domains</p>
          <textarea
            value={allowed}
            onChange={e => setAllowed(e.target.value)}
            placeholder="One domain per line"
            rows={3}
            data-testid={`profile-legacy-allowed-${pd.profile.id}`}
            className="w-full bg-gray-950 border border-gray-700 rounded-xl px-3 py-2 text-white text-sm font-mono placeholder-gray-600 focus:outline-none focus:border-emerald-500"
          />
        </div>
      </div>
    </div>
  )
}

function findAssignment(app: AppDetail, profileId: number | null): AppPolicyAssignment | null {
  if (profileId == null) return null
  return app.assignments.find(a => a.profileId === profileId) ?? null
}

function AppsSection({ profileId, isNew, apps, onChanged, testIdPrefix = 'apps-section' }: {
  profileId: number | null
  isNew: boolean
  apps: AppDetail[]
  onChanged: () => void | Promise<void>
  testIdPrefix?: string
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
      // Restore to the persisted value so the input doesn't sit empty
      // looking unsaved. Doesn't clear the policy — operator must use
      // Clear / Block / Allow for that.
      setMinutesDraft(currentMinutes != null ? String(currentMinutes) : '')
      setLocalError(null)
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
