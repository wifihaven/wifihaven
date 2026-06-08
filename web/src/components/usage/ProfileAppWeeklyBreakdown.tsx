import { useProfileAppWeekly } from '@/api/queries'
import type { ProfileAppWeeklyUsage, ProfileAppWeeklyUsageRow } from '@/types/api'
import { formatMins } from '@/lib/timeFormat'

// #1089 — per-profile weekly screen-time breakdown: one row per app showing
// engaged minutes summed across the trailing 7-day window. Aggregates FROM the
// `app_used_daily` rollup, so by construction the weekly numbers track the daily
// view and the heartbeat filter applied at write time flows through unchanged.
// Sibling to `ProfileUsageBreakdown` (#1519 / #726, which is the per-host
// per-day presence breakdown).

export function ProfileAppWeeklyBreakdown({ profileId, enabled = true, to }: {
  profileId: number
  enabled?: boolean
  to?: string
}) {
  const q = useProfileAppWeekly(profileId, to, { enabled })

  if (!enabled) return null
  if (q.isLoading) {
    return (
      <div
        data-testid={`profile-app-weekly-${profileId}-loading`}
        className="text-xs text-brand-text-muted px-2 py-3"
      >
        Loading weekly usage…
      </div>
    )
  }
  if (q.error || !q.data) {
    return (
      <div
        data-testid={`profile-app-weekly-${profileId}-error`}
        className="text-xs text-red-700 px-2 py-3"
      >
        Failed to load weekly usage.
      </div>
    )
  }
  return <WeeklyBody profileId={profileId} data={q.data} />
}

export function WeeklyBody({ profileId, data }: {
  profileId: number
  data: ProfileAppWeeklyUsage
}) {
  if (data.apps.length === 0) {
    return (
      <div
        data-testid={`profile-app-weekly-${profileId}-empty`}
        className="text-xs text-brand-text-muted px-2 py-3"
      >
        No app usage in {data.from} – {data.to}.
      </div>
    )
  }
  return (
    <div
      data-testid={`profile-app-weekly-${profileId}`}
      className="rounded-xl border border-brand-border bg-brand-surface/30"
    >
      <header className="px-4 py-2 text-xs uppercase tracking-wider text-brand-text-muted flex items-center justify-between">
        <span>Weekly app usage</span>
        <span data-testid={`profile-app-weekly-${profileId}-range`}>
          {data.from} – {data.to}
        </span>
      </header>
      <ul className="divide-y divide-brand-border">
        {data.apps.map(a => (
          <AppRow key={`week-app-${a.appId}`} profileId={profileId} app={a} />
        ))}
      </ul>
    </div>
  )
}

function AppRow({ profileId, app }: { profileId: number; app: ProfileAppWeeklyUsageRow }) {
  return (
    <li
      data-testid={`profile-app-weekly-${profileId}-app-${app.appId}`}
      className="w-full flex items-center justify-between px-4 py-2"
    >
      <span className="flex items-center gap-2">
        {app.appIcon && <span aria-hidden>{app.appIcon}</span>}
        <span className="text-sm font-medium text-brand-ink">{app.appName}</span>
      </span>
      <span
        className="text-xs text-brand-text"
        data-testid={`profile-app-weekly-${profileId}-app-${app.appId}-mins`}
      >
        {formatMins(app.engagedMinutes)}
      </span>
    </li>
  )
}
