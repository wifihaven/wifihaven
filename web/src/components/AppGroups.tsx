// #2935 — household app groups on a profile (design §10). A group is one shared time budget over
// several apps; this profile's attachment of it carries the limits. Everything here autosaves.
import { useEffect, useMemo, useRef, useState } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { api } from '@/api/client'
import { useGroupAttachmentsOf, useInvalidators, useProfiles } from '@/api/queries'
import { qk } from '@/api/queryKeys'
import { AppIcon } from '@/components/AppIcon'
import { Skeleton } from '@/components/Skeleton'
import { tighterLeft } from '@/lib/budget'
import { formatMins } from '@/lib/timeFormat'
import type { AppDetail, AppGroup, AppUsage, GroupUsage, ProfileAppGroup, UpsertProfileAppGroupRequest } from '@/types/api'

// #2935 — the query state behind the week-to-date figures, threaded down like #2764's UsageStatus so
// a row can tell "not loaded yet" from a genuine zero (AGENTS.md#loading-states).
export type BudgetStatus = 'pending' | 'error' | 'success'
export interface BudgetUsage {
  status: BudgetStatus
  appBySlug: Map<string, AppUsage>
  groupById: Map<number, GroupUsage>
}

function UsageBar({ testId, used, limit, label }: { testId: string; used: number; limit: number; label: string }) {
  return (
    <div data-testid={testId} className="flex items-center gap-2">
      <span className="w-12 text-[10.5px] uppercase tracking-wider text-brand-text-muted shrink-0">{label}</span>
      <div className="flex-1 h-1 bg-brand-alt rounded-full overflow-hidden">
        <div
          className={`h-full rounded-full ${used >= limit ? 'bg-red-500' : 'bg-brand-accent'}`}
          style={{ width: `${Math.min(100, Math.round((used / limit) * 100))}%` }}
        />
      </div>
      <span className="text-xs font-mono text-brand-text shrink-0">{formatMins(used)} / {formatMins(limit)}</span>
    </div>
  )
}
export { UsageBar }

// The D16 hint. Unchecked is the default (D5), so this is what keeps it from surprising a parent.
export const EXEMPT_HINT =
  'Not counted: while under its limits, this group stays reachable during bedtime and a soft pause.'

const chipBase = 'inline-flex items-center gap-1 h-6 px-2 rounded-lg border text-[11px] font-medium tabular-nums disabled:opacity-50'

export function errMsg(e: unknown, fallback: string): string {
  return e instanceof Error ? e.message : fallback
}

// Writes shared by the add menu and the rows. PUT replaces the attachment, so every write carries
// the full limit set.
function useGroupWrites(profileId: number, onChanged: () => void | Promise<void>) {
  const invalidators = useInvalidators()
  async function done() {
    await invalidators.appGroups()
    await onChanged()
  }
  return {
    attach: async (groupId: number, body: UpsertProfileAppGroupRequest) => {
      await api.appGroups.attach(profileId, groupId, body)
      await done()
    },
    detach: async (groupId: number) => {
      await api.appGroups.detach(profileId, groupId)
      await done()
    },
    done,
  }
}

export function AddGroupMenu({ profileId, testIdPrefix, groups, attached, onChanged, onAttached }: {
  profileId: number
  testIdPrefix: string
  groups: AppGroup[]
  attached: ProfileAppGroup[]
  onChanged: () => void | Promise<void>
  // #2974 — told which group was just attached, so its row can open on the member picker.
  onAttached?: (groupId: number) => void
}) {
  const [open, setOpen] = useState(false)
  const [naming, setNaming] = useState(false)
  const [name, setName] = useState('')
  // The group picked (or just created) that is waiting for its first daily limit. An attachment
  // needs a limit, and none is invented: nothing is written until the parent has typed one.
  const [chosen, setChosen] = useState<{ id: number; name: string } | null>(null)
  const [minutes, setMinutes] = useState('')
  const [error, setError] = useState<string | null>(null)
  // #2964 — `working` guards re-entry synchronously; `saving` is the same fact for rendering.
  const working = useRef(false)
  const [saving, setSaving] = useState(false)
  const w = useGroupWrites(profileId, onChanged)
  const options = groups.filter(g => !attached.some(a => a.groupId === g.id))

  function reset() {
    setOpen(false); setNaming(false); setName(''); setChosen(null); setMinutes('')
  }

  async function createNew() {
    const trimmed = name.trim()
    if (!trimmed || working.current) return
    working.current = true
    setSaving(true)
    setError(null)
    try {
      const g = await api.appGroups.create({ name: trimmed })
      setNaming(false); setName('')
      setChosen({ id: g.id, name: g.name })
    } catch (e) { setError(errMsg(e, 'Failed to create group')) } finally { working.current = false; setSaving(false) }
  }

  async function attachChosen() {
    if (!chosen || working.current) return
    const n = Number(minutes.trim())
    if (minutes.trim() === '') { setError(`Enter a daily limit to add ${chosen.name}`); return }
    if (!Number.isFinite(n) || n <= 0) { setError('Enter minutes > 0'); return }
    working.current = true
    setSaving(true)
    setError(null)
    try {
      await w.attach(chosen.id, { dailyMinutes: Math.round(n), exemptFromDaily: true })
      onAttached?.(chosen.id)
      reset()
    } catch (e) { setError(errMsg(e, 'Failed to add group')) } finally { working.current = false; setSaving(false) }
  }

  return (
    <span className="relative">
      <button
        type="button"
        data-testid={`${testIdPrefix}-add-group`}
        onClick={() => setOpen(v => !v)}
        className="text-xs px-2 py-1 rounded-lg border bg-white transition-colors border-brand-accent/40 text-brand-accent hover:bg-brand-accent/10"
      >{open ? 'Close' : '+ Add group'}</button>
      {open && (
        <div className="absolute right-0 z-10 mt-1 w-56 bg-white border border-brand-border-strong rounded-xl shadow-lg p-1.5 space-y-1">
          {chosen ? (
            <label className="block text-[11px] text-brand-text-muted px-1">
              Minutes per day for {chosen.name}
              <input
                type="number"
                min={1}
                autoFocus
                value={minutes}
                onChange={e => setMinutes(e.target.value)}
                disabled={saving}
                onBlur={attachChosen}
                onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); void attachChosen() } }}
                placeholder="min"
                data-testid={`${testIdPrefix}-add-group-minutes`}
                className="mt-1 w-full bg-white border border-brand-border-strong rounded-lg px-2 py-1 text-xs text-brand-ink"
              />
            </label>
          ) : (<>
            {options.map(g => (
              <button
                key={g.id}
                type="button"
                data-testid={`${testIdPrefix}-add-group-option-${g.id}`}
                onClick={() => setChosen({ id: g.id, name: g.name })}
                className="w-full text-left text-xs px-2 py-1.5 rounded-lg hover:bg-brand-alt text-brand-ink truncate"
              >{g.name}</button>
            ))}
            {naming ? (
              <input
                type="text"
                autoFocus
                value={name}
                onChange={e => setName(e.target.value)}
                disabled={saving}
                onBlur={createNew}
                onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); void createNew() } }}
                placeholder="Group name"
                aria-label="New group name"
                data-testid={`${testIdPrefix}-add-group-name`}
                className="w-full bg-white border border-brand-border-strong rounded-lg px-2 py-1 text-xs text-brand-ink"
              />
            ) : (
              <button
                type="button"
                data-testid={`${testIdPrefix}-add-group-new`}
                onClick={() => setNaming(true)}
                className="w-full text-left text-xs px-2 py-1.5 rounded-lg hover:bg-brand-alt text-brand-accent"
              >New group…</button>
            )}
          </>)}
          {saving && (
            <p className="text-xs text-brand-text-muted px-1" data-testid={`${testIdPrefix}-add-group-saving`}>Saving…</p>
          )}
          {error && <p className="text-xs text-red-700 px-1" data-testid={`${testIdPrefix}-add-group-error`}>{error}</p>}
        </div>
      )}
    </span>
  )
}

export function GroupsSubList({ profileId, testIdPrefix, status, attached, groups, apps, budget, onChanged, openMembersFor }: {
  profileId: number
  testIdPrefix: string
  status: 'pending' | 'error' | 'success'
  attached: ProfileAppGroup[]
  groups: AppGroup[]
  apps: AppDetail[]
  budget: BudgetUsage
  onChanged: () => void | Promise<void>
  // #2974 — the group just attached from the add menu; its row opens on its members.
  openMembersFor?: number | null
}) {
  if (status === 'success' && attached.length === 0) return null
  return (
    <div data-testid={`${testIdPrefix}-groups-list`} className="space-y-1">
      <div className="flex items-center gap-2 mb-1.5 pb-1 border-b border-brand-border">
        <span className="text-[11px] font-bold uppercase tracking-wider text-brand-ink">Groups</span>
        <span className="ml-auto text-[11px] text-brand-text-muted">One shared time budget for several apps</span>
      </div>
      {status === 'pending' && <Skeleton className="h-10 w-full rounded-xl" testId={`${testIdPrefix}-groups-loading`} />}
      {status === 'error' && (
        <p className="text-xs text-red-700" data-testid={`${testIdPrefix}-groups-error`}>Couldn't load groups.</p>
      )}
      {status === 'success' && attached.map(a => (
        <GroupRow
          key={a.groupId}
          profileId={profileId}
          attachment={a}
          group={groups.find(g => g.id === a.groupId)}
          apps={apps}
          groups={groups}
          budget={budget}
          onChanged={onChanged}
          autoOpenMembers={openMembersFor === a.groupId}
        />
      ))}
    </div>
  )
}

function GroupRow({ profileId, attachment, group, apps, groups, budget, onChanged, autoOpenMembers }: {
  profileId: number
  attachment: ProfileAppGroup
  group: AppGroup | undefined
  apps: AppDetail[]
  groups: AppGroup[]
  budget: BudgetUsage
  onChanged: () => void | Promise<void>
  autoOpenMembers: boolean
}) {
  const tid = `group-row-${attachment.groupId}`
  const w = useGroupWrites(profileId, onChanged)
  const [open, setOpen] = useState(false)
  // #2974 — members have their own expander beside the limit chip, so neither hides the other.
  const [membersOpen, setMembersOpen] = useState(autoOpenMembers)
  useEffect(() => { if (autoOpenMembers) setMembersOpen(true) }, [autoOpenMembers])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [daily, setDaily] = useState(attachment.dailyMinutes != null ? String(attachment.dailyMinutes) : '')
  const [weekly, setWeekly] = useState(attachment.weeklyMinutes != null ? String(attachment.weeklyMinutes) : '')
  // Re-seed the inputs when the saved attachment changes (a failed save leaves the old values).
  useEffect(() => {
    setDaily(attachment.dailyMinutes != null ? String(attachment.dailyMinutes) : '')
    setWeekly(attachment.weeklyMinutes != null ? String(attachment.weeklyMinutes) : '')
  }, [attachment.dailyMinutes, attachment.weeklyMinutes])
  const memberIds = group?.memberAppIds ?? attachment.memberAppIds
  const members = memberIds
    .map(id => apps.find(a => a.app.id === id))
    .filter((a): a is AppDetail => a != null)

  // Other profiles that attach this group — only fetched once the members are open.
  const { data: profiles = [] } = useProfiles()
  const others = profiles.filter(p => p.profile.id !== profileId)
  const otherAttachments = useGroupAttachmentsOf(others.map(p => p.profile.id), membersOpen)
  const usedBy = useMemo(
    () => others.filter((_, i) => otherAttachments[i]?.data?.some(a => a.groupId === attachment.groupId)),
    [others, otherAttachments, attachment.groupId],
  )
  const usedByLoading = membersOpen && otherAttachments.some(q => q.isPending)

  async function run(fn: () => Promise<void>, fallback: string) {
    setBusy(true); setError(null)
    try { await fn() } catch (e) { setError(errMsg(e, fallback)) } finally { setBusy(false) }
  }

  function parse(v: string): number | null | 'bad' {
    const t = v.trim()
    if (t === '') return null
    const n = Number(t)
    return Number.isFinite(n) && n > 0 ? Math.round(n) : 'bad'
  }

  async function commitLimits() {
    const d = parse(daily), wk = parse(weekly)
    if (d === 'bad' || wk === 'bad') { setError('Enter minutes > 0'); return }
    if (d == null && wk == null) {
      setError('Set a daily or weekly limit')
      setDaily(attachment.dailyMinutes != null ? String(attachment.dailyMinutes) : '')
      setWeekly(attachment.weeklyMinutes != null ? String(attachment.weeklyMinutes) : '')
      return
    }
    if (d === attachment.dailyMinutes && wk === attachment.weeklyMinutes) { setError(null); return }
    await run(() => w.attach(attachment.groupId, {
      dailyMinutes: d, weeklyMinutes: wk, exemptFromDaily: attachment.exemptFromDaily,
    }), 'Failed to update')
  }

  async function setCounts(counts: boolean) {
    await run(() => w.attach(attachment.groupId, {
      dailyMinutes: attachment.dailyMinutes, weeklyMinutes: attachment.weeklyMinutes,
      // Inverted: the label says "counts", the flag says "exempt".
      exemptFromDaily: !counts,
    }), 'Failed to update')
  }

  const memberWrites = useGroupMemberWrites(attachment.groupId, onChanged, attachment.memberAppIds)

  const dGtW = attachment.dailyMinutes != null && attachment.weeklyMinutes != null
    && attachment.dailyMinutes > attachment.weeklyMinutes
  const summary = [
    attachment.dailyMinutes != null ? `${formatMins(attachment.dailyMinutes)}/day` : null,
    attachment.weeklyMinutes != null ? `${formatMins(attachment.weeklyMinutes)}/week` : null,
  ].filter(Boolean).join(' · ')
  // The collapsed chip: the tighter remaining figure once usage is in, the limits until then (a
  // limit is real data; a remaining figure is not, so it waits behind a skeleton instead).
  const gu = budget.groupById.get(attachment.groupId)
  const left = budget.status === 'success' && gu != null && gu.weekUsedMins != null
    // Limits and usage both come from the one status body, so the figure cannot mix two reads.
    ? tighterLeft({
        dailyLimit: gu.limitMins, dailyUsed: gu.usedMins,
        weeklyLimit: gu.weeklyLimitMins, weekUsed: gu.weekUsedMins,
      })
    : null

  return (
    <div data-testid={tid} className="bg-brand-card border border-brand-border rounded-xl">
      <div className="flex items-center gap-2.5 pl-2.5 pr-2 py-1.5 min-h-[40px] flex-wrap sm:flex-nowrap">
        <span className="flex -space-x-1 shrink-0">
          {members.slice(0, 3).map(m => (
            <span key={m.app.id} className="w-5 inline-flex items-center justify-center">
              <AppIcon icon={m.app.icon} iconType={m.app.iconType} size="sm" />
            </span>
          ))}
        </span>
        <div className="flex-1 min-w-[6rem] overflow-hidden flex items-baseline gap-2">
          <p className="text-[13px] text-brand-ink font-medium truncate">{attachment.name}</p>
          <MembersToggle tid={tid} count={memberIds.length} open={membersOpen} onClick={() => setMembersOpen(v => !v)} />
        </div>
        <div className="flex items-center gap-1.5 shrink-0 ml-auto">
          <button
            type="button"
            data-testid={`${tid}-toggle`}
            aria-expanded={open}
            disabled={busy}
            onClick={() => setOpen(v => !v)}
            className={`${chipBase} bg-amber-500/15 border-amber-500/40 text-amber-800`}
          >
            {budget.status === 'pending' ? (
              <span
                data-testid={`${tid}-budget-loading`}
                aria-label="Loading usage"
                className="inline-block w-16 h-2 rounded-full bg-amber-500/30 animate-pulse"
              />
            ) : budget.status === 'error' ? (
              <span data-testid={`${tid}-budget-error`} title="Usage unavailable" className="font-mono">{summary} · —</span>
            ) : (
              <span className="font-mono">{left?.text ?? summary}</span>
            )}
            <span className="text-[8px] opacity-60">{open ? '▲' : '▼'}</span>
          </button>
          <button
            type="button"
            data-testid={`${tid}-detach`}
            aria-label={`Remove ${attachment.name} from this profile`}
            disabled={busy}
            onClick={() => run(() => w.detach(attachment.groupId), 'Failed to remove')}
            className="w-5 h-6 rounded-lg text-brand-text-muted hover:text-red-700 hover:bg-red-500/10 text-sm leading-none disabled:opacity-50"
          >×</button>
        </div>
      </div>

      {open && (
        <div data-testid={`${tid}-drawer`} className="border-t border-brand-border px-3 py-2.5 bg-brand-surface rounded-b-xl space-y-2">
          <p className="text-[10.5px] font-bold uppercase tracking-wider text-brand-text-muted">Limits for this profile</p>
          <div className="flex items-center gap-2 flex-wrap">
            {([['daily', daily, setDaily, 'min/day'], ['weekly', weekly, setWeekly, 'min/week']] as const).map(
              ([key, val, set, unit]) => (
                <span key={key} className="inline-flex items-center gap-1.5">
                  <input
                    type="number"
                    min={1}
                    value={val}
                    disabled={busy}
                    onChange={e => set(e.target.value)}
                    onBlur={commitLimits}
                    onKeyDown={e => { if (e.key === 'Enter') { e.preventDefault(); (e.currentTarget as HTMLInputElement).blur() } }}
                    placeholder="min"
                    aria-label={`${attachment.name} ${key} limit minutes`}
                    data-testid={`${tid}-${key}`}
                    className="w-16 rounded-lg px-2 py-1 text-xs border bg-white border-brand-border-strong text-brand-ink disabled:opacity-50"
                  />
                  <span className="text-xs text-brand-text-muted">{unit}</span>
                </span>
              ),
            )}
          </div>
          {budget.status === 'pending' && (
            <div className="h-1 rounded-full bg-brand-alt animate-pulse" data-testid={`${tid}-usage-loading`} />
          )}
          {budget.status === 'success' && gu != null && gu.weekUsedMins != null && (
            <div className="space-y-1">
              {attachment.dailyMinutes != null && (
                <UsageBar testId={`${tid}-usage-day`} label="Today" used={gu.usedMins} limit={attachment.dailyMinutes} />
              )}
              {attachment.weeklyMinutes != null && (
                <UsageBar testId={`${tid}-usage-week`} label="Week" used={gu.weekUsedMins} limit={attachment.weeklyMinutes} />
              )}
            </div>
          )}
          {dGtW && (
            <p data-testid={`${tid}-daily-over-weekly`} className="text-[11px] text-amber-800">
              The daily limit is higher than the weekly one, so the weekly limit runs out first.
            </p>
          )}

          <label className="flex items-start gap-2 text-xs text-brand-text cursor-pointer select-none">
            <input
              type="checkbox"
              data-testid={`${tid}-counts-toward-daily`}
              checked={!attachment.exemptFromDaily}
              disabled={busy}
              onChange={e => setCounts(e.target.checked)}
              className="w-3.5 h-3.5 mt-0.5 accent-amber-500"
            />
            <span>
              Counts toward daily limit
              {attachment.exemptFromDaily && (
                <span data-testid={`${tid}-exempt-hint`} className="block text-brand-text-muted">{EXEMPT_HINT}</span>
              )}
            </span>
          </label>

        </div>
      )}
      {membersOpen && (
        <div data-testid={`${tid}-members-panel`} className="border-t border-brand-border px-3 py-2.5 bg-brand-surface rounded-b-xl space-y-2">
          <p className="text-[10.5px] font-bold uppercase tracking-wider text-brand-text-muted">Apps in {attachment.name}</p>
          <GroupMembersEditor
            tid={tid}
            groupId={attachment.groupId}
            groupName={attachment.name}
            memberIds={memberIds}
            apps={apps}
            groups={groups}
            writes={memberWrites}
          />
          <p data-testid={`${tid}-also-used-by`} className="text-[11px] text-brand-text-muted">
            {usedByLoading
              ? 'Checking which other profiles use this group…'
              : usedBy.length > 0
                ? `Also used by: ${usedBy.map(p => p.profile.name).join(', ')}. Changing the apps changes them there too.`
                : 'No other profile uses this group.'}
          </p>
        </div>
      )}
      {(error ?? memberWrites.error) && (
        <p className="text-xs text-red-700 px-3 pb-2" data-testid={`${tid}-error`}>{error ?? memberWrites.error}</p>
      )}
    </div>
  )
}

// #2974 — member writes for one group, shared by a profile's group row and Manage apps. A write
// used to hold the whole row disabled until the update AND every refetch it triggered (groups,
// profiles, time status) had finished, so "+ app" went dead for seconds after each add. Now:
// - the member list in the `qk.appGroups()` cache changes at once (optimistic), and only the app
//   being written is disabled;
// - writes to one group run one after another. `memberAppIds` REPLACES the list, so each write
//   sends the cached list with every unsettled change to that group re-applied on top. A refetch
//   that lands mid-chain (from any source) therefore cannot make a write drop an app still queued
//   or in flight;
// - the background refetch waits until no member write is in flight for any group, so our own
//   writes never refetch the list under one another;
// - a failed write takes its own change back out of the cache and says why.
interface MemberOp { appId: number; add: boolean }
const memberWriteChains = new Map<number, Promise<void>>()
const unsettledOps = new Map<number, MemberOp[]>()
let memberWritesInFlight = 0

function applyOps(ids: number[], ops: MemberOp[]): number[] {
  return ops.reduce(
    (acc, op) => (op.add ? (acc.includes(op.appId) ? acc : [...acc, op.appId]) : acc.filter(id => id !== op.appId)),
    ids,
  )
}

export interface GroupMemberWrites {
  pending: ReadonlySet<number>
  error: string | null
  add: (appId: number) => void
  remove: (appId: number) => void
}

// `fallbackMemberIds` is the list to start from if the groups query has no entry for this group
// (it errored, or has not loaded): a profile row passes its attachment's members.
export function useGroupMemberWrites(
  groupId: number,
  onChanged?: () => void | Promise<void>,
  fallbackMemberIds?: number[],
): GroupMemberWrites {
  const qc = useQueryClient()
  const invalidators = useInvalidators()
  const [pending, setPending] = useState<ReadonlySet<number>>(new Set())
  const [error, setError] = useState<string | null>(null)
  const fallback = useRef(fallbackMemberIds)
  fallback.current = fallbackMemberIds

  function cached(): number[] | undefined {
    return qc.getQueryData<AppGroup[]>(qk.appGroups())?.find(g => g.id === groupId)?.memberAppIds
  }
  function setCached(ids: number[]) {
    qc.setQueryData<AppGroup[]>(qk.appGroups(), gs =>
      gs?.map(g => (g.id === groupId ? { ...g, memberAppIds: ids } : g)))
  }

  function change(appId: number, add: boolean) {
    const op: MemberOp = { appId, add }
    setError(null)
    setPending(p => new Set(p).add(appId))
    unsettledOps.set(groupId, [...(unsettledOps.get(groupId) ?? []), op])
    memberWritesInFlight += 1
    // A refetch already under way would land on top of the optimistic list.
    void qc.cancelQueries({ queryKey: qk.appGroups(), exact: true })
    const now = cached()
    if (now != null) setCached(applyOps(now, [op]))
    const run = async () => {
      try {
        const base = cached() ?? fallback.current
        if (base == null) throw new Error('Groups are not loaded yet')
        const next = applyOps(base, unsettledOps.get(groupId) ?? [])
        setCached(next)
        await api.appGroups.update(groupId, { memberAppIds: next })
      } catch (e) {
        const now = cached()
        if (now != null) setCached(applyOps(now, [{ appId, add: !add }]))
        setError(errMsg(e, 'Failed to update members'))
      } finally {
        unsettledOps.set(groupId, (unsettledOps.get(groupId) ?? []).filter(o => o !== op))
        setPending(p => { const n = new Set(p); n.delete(appId); return n })
        memberWritesInFlight -= 1
        if (memberWritesInFlight === 0) {
          void invalidators.appGroups()
          void onChanged?.()
        }
      }
    }
    const next = (memberWriteChains.get(groupId) ?? Promise.resolve()).then(run)
    memberWriteChains.set(groupId, next)
  }

  return { pending, error, add: id => change(id, true), remove: id => change(id, false) }
}

// #2974 — the row's member control. A group with no apps asks for some instead of reading "0 apps",
// which is what made the editor (then behind the limit chip) look absent.
export function MembersToggle({ tid, count, open, onClick }: {
  tid: string
  count: number
  open: boolean
  onClick: () => void
}) {
  return (
    <button
      type="button"
      data-testid={`${tid}-members-toggle`}
      aria-expanded={open}
      onClick={onClick}
      className={`text-[11px] shrink-0 inline-flex items-center gap-1 rounded-md px-1 hover:bg-brand-alt ${
        count === 0 ? 'text-brand-accent font-medium' : 'text-brand-text-muted font-mono'
      }`}
    >
      {count === 0 ? 'No apps yet. Add apps' : `${count} app${count === 1 ? '' : 's'}`}
      <span className="text-[8px] opacity-60">{open ? '▲' : '▼'}</span>
    </button>
  )
}

// #2974 — a group's members with remove buttons, and the picker that adds more. Shared by a
// profile's group row and the Manage apps page. The picker is the profile's "Allow an app" picker
// in shape: a filter and a list. An app in another group stays listed but disabled with that
// group's name, so the one-group-per-app rule (D2) explains itself rather than hiding the app.
export function GroupMembersEditor({ tid, groupId, groupName, memberIds, apps, groups, writes }: {
  tid: string
  groupId: number
  groupName: string
  memberIds: number[]
  apps: AppDetail[]
  groups: AppGroup[]
  writes: GroupMemberWrites
}) {
  // An empty group opens straight onto the picker; otherwise it waits behind "+ Add apps".
  const [adding, setAdding] = useState(false)
  const [filter, setFilter] = useState('')
  const members = memberIds
    .map(id => apps.find(a => a.app.id === id))
    .filter((a): a is AppDetail => a != null)
  const otherGroupOf = useMemo(() => {
    const m = new Map<number, string>()
    for (const g of groups) if (g.id !== groupId) for (const id of g.memberAppIds) m.set(id, g.name)
    return m
  }, [groups, groupId])
  const candidates = apps.filter(a => !memberIds.includes(a.app.id))
  const q = filter.trim().toLowerCase()
  const matches = q ? candidates.filter(a => a.app.name.toLowerCase().includes(q)) : candidates
  const pickerOpen = adding || memberIds.length === 0

  return (
    <div className="space-y-2">
      {members.length > 0 && (
        <div data-testid={`${tid}-members`} className="flex flex-wrap gap-1.5">
          {members.map(m => (
            <span key={m.app.id} className="inline-flex items-center gap-1 h-6 pl-1.5 pr-1 rounded-lg border border-brand-border-strong bg-white text-[11px] text-brand-ink">
              <AppIcon icon={m.app.icon} iconType={m.app.iconType} size="sm" />
              {m.app.name}
              <button
                type="button"
                data-testid={`${tid}-member-remove-${m.app.id}`}
                aria-label={`Remove ${m.app.name} from ${groupName}`}
                disabled={writes.pending.has(m.app.id)}
                onClick={() => writes.remove(m.app.id)}
                className="px-1 text-brand-text-muted hover:text-red-700"
              >×</button>
            </span>
          ))}
          {!pickerOpen && (
            <button
              type="button"
              data-testid={`${tid}-member-add`}
              onClick={() => setAdding(true)}
              className="h-6 rounded-lg border border-dashed border-brand-accent/50 bg-white text-[11px] text-brand-accent px-2 hover:bg-brand-accent/10"
            >+ Add apps</button>
          )}
        </div>
      )}
      {pickerOpen && (
        <div data-testid={`${tid}-member-picker`} className="bg-white border border-brand-accent/50 rounded-xl p-2 space-y-1.5">
          <div className="flex items-center gap-2">
            <p className="text-[11px] font-semibold uppercase tracking-wider text-brand-accent flex-1">
              Add apps to {groupName}
            </p>
            {memberIds.length > 0 && (
              <button
                type="button"
                data-testid={`${tid}-member-picker-close`}
                onClick={() => { setAdding(false); setFilter('') }}
                className="text-[11px] text-brand-text-muted hover:text-brand-ink"
              >Done</button>
            )}
          </div>
          <input
            type="text"
            value={filter}
            onChange={e => setFilter(e.target.value)}
            placeholder="Filter apps…"
            aria-label={`Filter apps to add to ${groupName}`}
            data-testid={`${tid}-member-filter`}
            className="w-full bg-white border border-brand-border-strong rounded-lg px-2 py-1 text-brand-ink text-xs"
          />
          {matches.length === 0 ? (
            <p className="text-xs text-brand-text-muted" data-testid={`${tid}-member-picker-empty`}>
              {apps.length === 0
                ? 'No apps in the library yet.'
                : candidates.length === 0 ? `Every app is already in ${groupName}.` : 'No apps match that filter.'}
            </p>
          ) : (
            <div className="space-y-1 max-h-56 overflow-y-auto">
              {matches.map(a => {
                const other = otherGroupOf.get(a.app.id)
                return (
                  <button
                    key={a.app.id}
                    type="button"
                    data-testid={`${tid}-member-pick-${a.app.id}`}
                    disabled={writes.pending.has(a.app.id) || other != null}
                    title={other != null ? `An app can be in only one group. ${a.app.name} is in ${other}.` : undefined}
                    onClick={() => { setAdding(true); writes.add(a.app.id) }}
                    className="w-full flex items-center gap-2 px-2 py-1 rounded-lg bg-white hover:bg-brand-alt border border-brand-border-strong text-left disabled:opacity-50 disabled:hover:bg-white"
                  >
                    <AppIcon icon={a.app.icon} iconType={a.app.iconType} size="sm" className="w-5 text-center" />
                    <span className="text-xs text-brand-ink flex-1 truncate">{a.app.name}</span>
                    {other != null && (
                      <span className="text-[10.5px] font-semibold px-1.5 rounded-full bg-amber-500/15 border border-amber-500/40 text-amber-800 shrink-0">
                        In {other}
                      </span>
                    )}
                  </button>
                )
              })}
            </div>
          )}
        </div>
      )}
    </div>
  )
}
