// #2935 — household app groups on a profile (design §10). A group is one shared time budget over
// several apps; this profile's attachment of it carries the limits. Everything here autosaves.
import { useMemo, useState } from 'react'
import { api } from '@/api/client'
import { useGroupAttachmentsOf, useInvalidators, useProfiles } from '@/api/queries'
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

function errMsg(e: unknown, fallback: string): string {
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

export function AddGroupMenu({ profileId, testIdPrefix, groups, attached, onChanged }: {
  profileId: number
  testIdPrefix: string
  groups: AppGroup[]
  attached: ProfileAppGroup[]
  onChanged: () => void | Promise<void>
}) {
  const [open, setOpen] = useState(false)
  const [naming, setNaming] = useState(false)
  const [name, setName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const w = useGroupWrites(profileId, onChanged)
  const options = groups.filter(g => !attached.some(a => a.groupId === g.id))

  // A new attachment needs a limit; 60 min/day is a starting point the row's inputs then edit.
  const starter: UpsertProfileAppGroupRequest = { dailyMinutes: 60, exemptFromDaily: true }

  async function pick(groupId: number) {
    setError(null)
    try {
      await w.attach(groupId, starter)
      setOpen(false)
    } catch (e) { setError(errMsg(e, 'Failed to add group')) }
  }
  async function createNew() {
    const trimmed = name.trim()
    if (!trimmed) return
    setError(null)
    try {
      const g = await api.appGroups.create({ name: trimmed })
      await w.attach(g.id, starter)
      setName(''); setNaming(false); setOpen(false)
    } catch (e) { setError(errMsg(e, 'Failed to create group')) }
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
          {options.map(g => (
            <button
              key={g.id}
              type="button"
              data-testid={`${testIdPrefix}-add-group-option-${g.id}`}
              onClick={() => pick(g.id)}
              className="w-full text-left text-xs px-2 py-1.5 rounded-lg hover:bg-brand-alt text-brand-ink truncate"
            >{g.name}</button>
          ))}
          {naming ? (
            <input
              type="text"
              autoFocus
              value={name}
              onChange={e => setName(e.target.value)}
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
          {error && <p className="text-xs text-red-700 px-1">{error}</p>}
        </div>
      )}
    </span>
  )
}

export function GroupsSubList({ profileId, testIdPrefix, status, attached, groups, apps, budget, onChanged }: {
  profileId: number
  testIdPrefix: string
  status: 'pending' | 'error' | 'success'
  attached: ProfileAppGroup[]
  groups: AppGroup[]
  apps: AppDetail[]
  budget: BudgetUsage
  onChanged: () => void | Promise<void>
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
        />
      ))}
    </div>
  )
}

function GroupRow({ profileId, attachment, group, apps, groups, budget, onChanged }: {
  profileId: number
  attachment: ProfileAppGroup
  group: AppGroup | undefined
  apps: AppDetail[]
  groups: AppGroup[]
  budget: BudgetUsage
  onChanged: () => void | Promise<void>
}) {
  const tid = `group-row-${attachment.groupId}`
  const w = useGroupWrites(profileId, onChanged)
  const [open, setOpen] = useState(false)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [daily, setDaily] = useState(attachment.dailyMinutes != null ? String(attachment.dailyMinutes) : '')
  const [weekly, setWeekly] = useState(attachment.weeklyMinutes != null ? String(attachment.weeklyMinutes) : '')
  const memberIds = group?.memberAppIds ?? attachment.memberAppIds
  const members = memberIds
    .map(id => apps.find(a => a.app.id === id))
    .filter((a): a is AppDetail => a != null)
  const inOtherGroup = new Set(groups.filter(g => g.id !== attachment.groupId).flatMap(g => g.memberAppIds))
  const addable = apps.filter(a => !memberIds.includes(a.app.id) && !inOtherGroup.has(a.app.id))

  // Other profiles that attach this group — only fetched once the row is open.
  const { data: profiles = [] } = useProfiles()
  const others = profiles.filter(p => p.profile.id !== profileId)
  const otherAttachments = useGroupAttachmentsOf(others.map(p => p.profile.id), open)
  const usedBy = useMemo(
    () => others.filter((_, i) => otherAttachments[i]?.data?.some(a => a.groupId === attachment.groupId)),
    [others, otherAttachments, attachment.groupId],
  )
  const usedByLoading = open && otherAttachments.some(q => q.isPending)

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

  async function setMembers(next: number[]) {
    await run(async () => {
      await api.appGroups.update(attachment.groupId, { memberAppIds: next })
      await w.done()
    }, 'Failed to update members')
  }

  const dGtW = attachment.dailyMinutes != null && attachment.weeklyMinutes != null
    && attachment.dailyMinutes > attachment.weeklyMinutes
  const summary = [
    attachment.dailyMinutes != null ? `${formatMins(attachment.dailyMinutes)}/day` : null,
    attachment.weeklyMinutes != null ? `${formatMins(attachment.weeklyMinutes)}/week` : null,
  ].filter(Boolean).join(' · ')
  // The collapsed chip: the tighter remaining figure once usage is in, the limits until then (a
  // limit is real data; a remaining figure is not, so it waits behind a skeleton instead).
  const gu = budget.groupById.get(attachment.groupId)
  const left = budget.status === 'success'
    ? tighterLeft({
        dailyLimit: attachment.dailyMinutes, dailyUsed: gu?.usedMins ?? 0,
        weeklyLimit: attachment.weeklyMinutes, weekUsed: gu?.weekUsedMins ?? 0,
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
          <span className="text-[11px] text-brand-text-muted font-mono shrink-0">
            {members.length} app{members.length === 1 ? '' : 's'}
          </span>
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
          {budget.status === 'success' && (
            <div className="space-y-1">
              {attachment.dailyMinutes != null && (
                <UsageBar testId={`${tid}-usage-day`} label="Today" used={gu?.usedMins ?? 0} limit={attachment.dailyMinutes} />
              )}
              {attachment.weeklyMinutes != null && (
                <UsageBar testId={`${tid}-usage-week`} label="Week" used={gu?.weekUsedMins ?? 0} limit={attachment.weeklyMinutes} />
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

          <p className="text-[10.5px] font-bold uppercase tracking-wider text-brand-text-muted pt-2 border-t border-dashed border-brand-border">
            Apps in {attachment.name}
          </p>
          <div data-testid={`${tid}-members`} className="flex flex-wrap gap-1.5">
            {members.map(m => (
              <span key={m.app.id} className="inline-flex items-center gap-1 h-6 pl-1.5 pr-1 rounded-lg border border-brand-border-strong bg-white text-[11px] text-brand-ink">
                <AppIcon icon={m.app.icon} iconType={m.app.iconType} size="sm" />
                {m.app.name}
                <button
                  type="button"
                  data-testid={`${tid}-member-remove-${m.app.id}`}
                  aria-label={`Remove ${m.app.name} from ${attachment.name}`}
                  disabled={busy}
                  onClick={() => setMembers(memberIds.filter(id => id !== m.app.id))}
                  className="px-1 text-brand-text-muted hover:text-red-700"
                >×</button>
              </span>
            ))}
            <select
              value=""
              disabled={busy || addable.length === 0}
              onChange={e => { if (e.target.value) void setMembers([...memberIds, Number(e.target.value)]) }}
              aria-label={`Add an app to ${attachment.name}`}
              data-testid={`${tid}-member-add`}
              className="h-6 rounded-lg border border-dashed border-brand-border-strong bg-white text-[11px] text-brand-text-muted px-1"
            >
              <option value="">+ app</option>
              {addable.map(a => <option key={a.app.id} value={a.app.id}>{a.app.name}</option>)}
            </select>
          </div>
          <p data-testid={`${tid}-also-used-by`} className="text-[11px] text-brand-text-muted">
            {usedByLoading
              ? 'Checking which other profiles use this group…'
              : usedBy.length > 0
                ? `Also used by: ${usedBy.map(p => p.profile.name).join(', ')}. Changing the apps changes them there too.`
                : 'No other profile uses this group.'}
          </p>
        </div>
      )}
      {error && <p className="text-xs text-red-700 px-3 pb-2" data-testid={`${tid}-error`}>{error}</p>}
    </div>
  )
}
