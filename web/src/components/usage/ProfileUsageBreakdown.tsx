import { useMemo, useState } from 'react'
import { useProfileUsageByApp } from '@/api/queries'
import type {
  HostUsage, OrphanHostUsage, ProfileAppUsage, ProfileUsageByApp,
} from '@/types/api'
import { formatMins } from '@/lib/timeFormat'

// #1519 / #726 — per-profile usage breakdown: one row per configured app
// (drillable to its per-host minutes — the #726 per-FQDN breakdown), then one
// row per non-app host (each rendered as its own single-host pseudo-app, per
// the App-Centric Model — there is NO semantic "Other" app).
//
// "Other" only appears here as a PRESENTATION affordance: the orphan list's
// long tail collapses behind a "+N more sites" expander, labelled explicitly
// as a top-N rollup, not as a bucket the API ships.

const ORPHAN_TOP_N = 10

function secondsToMins(s: number): number {
  return Math.round(s / 60)
}

function today(): string {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
}

export function ProfileUsageBreakdown({ profileId, enabled = true, from, to }: {
  profileId: number
  enabled?: boolean
  from?: string
  to?: string
}) {
  const f = from ?? today()
  const t = to ?? f
  const q = useProfileUsageByApp(profileId, f, t, { enabled })

  if (!enabled) return null
  if (q.isLoading) {
    return (
      <div
        data-testid={`profile-usage-breakdown-${profileId}-loading`}
        className="text-xs text-brand-text-muted px-2 py-3"
      >
        Loading usage…
      </div>
    )
  }
  if (q.error || !q.data) {
    return (
      <div
        data-testid={`profile-usage-breakdown-${profileId}-error`}
        className="text-xs text-red-700 px-2 py-3"
      >
        Failed to load usage.
      </div>
    )
  }
  return <BreakdownBody profileId={profileId} data={q.data} />
}

export function BreakdownBody({ profileId, data }: {
  profileId: number
  data: ProfileUsageByApp
}) {
  const apps = data.apps
  const orphans = data.orphanHosts ?? []
  if (apps.length === 0 && orphans.length === 0) {
    return (
      <div
        data-testid={`profile-usage-breakdown-${profileId}-empty`}
        className="text-xs text-brand-text-muted px-2 py-3"
      >
        No usage in this window.
      </div>
    )
  }
  return (
    <div
      data-testid={`profile-usage-breakdown-${profileId}`}
      className="rounded-xl border border-brand-border bg-brand-surface/30"
    >
      <header className="px-4 py-2 text-xs uppercase tracking-wider text-brand-text-muted">
        Usage by app
      </header>
      <ul className="divide-y divide-brand-border">
        {apps.map(a => (
          <AppRow key={`app-${a.appId ?? a.appName}`} profileId={profileId} app={a} />
        ))}
        <OrphanList profileId={profileId} orphans={orphans} />
      </ul>
    </div>
  )
}

function AppRow({ profileId, app }: { profileId: number; app: ProfileAppUsage }) {
  const [open, setOpen] = useState(false)
  const mins = secondsToMins(app.proportionalSeconds)
  return (
    <li data-testid={`profile-usage-breakdown-${profileId}-app-${app.appId ?? 'na'}`}>
      <button
        type="button"
        onClick={() => setOpen(v => !v)}
        aria-expanded={open}
        className="w-full flex items-center justify-between px-4 py-2 text-left"
        data-testid={`profile-usage-breakdown-${profileId}-app-${app.appId ?? 'na'}-toggle`}
      >
        <span className="flex items-center gap-2">
          <span className={`text-brand-text-muted transition-transform text-xs ${open ? 'rotate-90' : ''}`}>▸</span>
          {app.appIcon && <span aria-hidden>{app.appIcon}</span>}
          <span className="text-sm font-medium text-brand-ink">{app.appName}</span>
        </span>
        <span
          className="text-xs text-brand-text"
          data-testid={`profile-usage-breakdown-${profileId}-app-${app.appId ?? 'na'}-mins`}
        >
          {formatMins(mins)}
        </span>
      </button>
      {open && (
        <ul
          data-testid={`profile-usage-breakdown-${profileId}-app-${app.appId ?? 'na'}-hosts`}
          className="px-8 pb-2"
        >
          {app.hosts.length === 0 && (
            <li className="text-xs text-brand-text-muted py-1">No host activity in window.</li>
          )}
          {app.hosts.map(h => <HostRow key={h.host.value} host={h} />)}
        </ul>
      )}
    </li>
  )
}

function HostRow({ host }: { host: HostUsage }) {
  return (
    <li className="flex items-center justify-between text-xs py-1">
      <span className="text-brand-text">{host.host.value}</span>
      <span className="text-brand-text-muted">{formatMins(host.proportionalMins)}</span>
    </li>
  )
}

function OrphanList({ profileId, orphans }: {
  profileId: number
  orphans: OrphanHostUsage[]
}) {
  const [expanded, setExpanded] = useState(false)
  const sorted = useMemo(
    () => [...orphans].sort((a, b) => b.proportionalSeconds - a.proportionalSeconds),
    [orphans],
  )
  if (sorted.length === 0) return null
  const visible = expanded ? sorted : sorted.slice(0, ORPHAN_TOP_N)
  const remainder = sorted.length - visible.length
  return (
    <>
      {visible.map(o => (
        <li
          key={`orphan-${o.host.value}`}
          data-testid={`profile-usage-breakdown-${profileId}-orphan-${o.host.value}`}
          className="flex items-center justify-between px-4 py-2"
        >
          <span className="flex items-center gap-2">
            <span aria-hidden className="text-brand-text-muted text-xs">·</span>
            <span className="text-sm text-brand-ink">{o.host.value}</span>
          </span>
          <span
            className="text-xs text-brand-text"
            data-testid={`profile-usage-breakdown-${profileId}-orphan-${o.host.value}-mins`}
          >
            {formatMins(secondsToMins(o.proportionalSeconds))}
          </span>
        </li>
      ))}
      {remainder > 0 && (
        <li className="px-4 py-2">
          <button
            type="button"
            onClick={() => setExpanded(true)}
            className="text-xs text-brand-text-muted underline"
            data-testid={`profile-usage-breakdown-${profileId}-orphan-expand`}
          >
            {`+${remainder} more sites (top-${ORPHAN_TOP_N} shown — these are individual non-app sites, not an "Other" bucket)`}
          </button>
        </li>
      )}
    </>
  )
}
