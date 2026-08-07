// #2603 (SECURITY): every React Query key the SPA uses, in one place.
//
// A leaf module on purpose: it imports only the scope helper, so a component that needs a
// key does not pull in `api/queries.ts` (the hooks module, which reaches the API client and
// is mocked wholesale by most page tests).
import { withHouseholdScope } from '@/api/queryScope'
import type { BetaRequestStatus, TrafficUsageBucket, TrafficUsageGroupBy } from '@/types/api'

// #2603 (SECURITY): the raw, unscoped key shapes. NOT exported — `qk` below is this map
// with the session's household scope prefixed onto every entry, so no key can collide
// across two households in the process-wide QueryClient. Add new keys HERE; the scoping is
// applied to the whole map at once so a new key cannot forget it.
const unscopedKeys = {
  me: () => ['me'] as const,
  // #2199 — the server-signed Plain widget identity for the authed admin.
  supportIdentity: () => ['support', 'identity'] as const,
  // #2133 — operator beta-request queue, keyed on the optional status filter.
  betaRequests: (status?: BetaRequestStatus) => ['beta-requests', status ?? 'pending'] as const,
  // #2296 — operator press correspondence log.
  pressMessages: () => ['press', 'messages'] as const,
  profiles: () => ['profiles'] as const,
  devices: () => ['devices'] as const,
  // #2252 — enrolled routers, read by the dashboard first-run banner to tell
  // "no router yet" apart from "enrollment pending, waiting to connect".
  routers: () => ['routers'] as const,
  alerts: (includeAll: boolean) => ['alerts', includeAll] as const,
  schedules: () => ['schedules'] as const,
  dashboardNow: () => ['dashboard', 'now'] as const,
  // #2062: an optional `mac` narrows the feed to one device; the ws patcher
  // (useWsRecentBlocked) MUST key on the same mac so its prepend lands on the
  // active (filtered) cache entry, not the unfiltered one.
  recentBlocked: (mac?: string | null) => ['dashboard', 'recent-blocked', mac ?? null] as const,
  timeStatusToday: () => ['time', 'status', 'today'] as const,
  timeStatusDate: (date: string) => ['time', 'status', 'date', date] as const,
  timeStatusWeek: (to?: string, bucketOffsetMin?: number) =>
    ['time', 'status', 'week', to ?? 'current', bucketOffsetMin ?? 0] as const,
  // #777 — per-profile detail (today/week) cached on first expand so collapse-then-
  // re-expand within the same page mount doesn't refetch.
  timeStatusProfileToday: (profileId: number) =>
    ['time', 'status', 'today', 'profile', profileId] as const,
  timeStatusProfileWeek: (profileId: number, to: string | undefined, bucketOffsetMin: number) =>
    ['time', 'status', 'week', to ?? 'current', bucketOffsetMin, 'profile', profileId] as const,
  timeStatusSummaryToday: () => ['time', 'status', 'summary', 'today'] as const,
  timeStatusSummaryWeek: (to?: string) => ['time', 'status', 'summary', 'week', to ?? 'current'] as const,
  // #776 — hourly chart on the Today card.
  // #1079 — groupBy is part of the cache key so the by-app axis doesn't
  // share a cache slot with the legacy host axis.
  usageSeriesProfile: (profileId: number, date: string, tz: string, groupBy?: string) =>
    ['usage', 'series', 'profile', profileId, date, tz, groupBy ?? 'host'] as const,
  // #1061 — per-app time-used breakdown for one profile over [from,to].
  profileUsageByApp: (profileId: number, from: string, to: string) =>
    ['profiles', profileId, 'usage-by-app', from, to] as const,
  // #1089 — per-app engaged-minutes summed over the trailing 7-day window
  // ending at `to`. Aggregates FROM `app_used_daily`, so by construction it
  // tracks the daily rollup the per-app cap reads.
  profileAppWeekly: (profileId: number, to?: string) =>
    ['profiles', profileId, 'usage', 'app', 'weekly', to ?? 'current'] as const,
  // #1973 (SPA-ws S5): the live trafficUsage series key (design §3.1). The ws push
  // (live edge) and the future S6b Traffic Usage page produce the key HERE so they
  // share one cache entry — the push patches exactly the key the page renders.
  // #2069: `profileIds` scopes the key so a child's linked-profile-scoped series is a
  // distinct cache entry from the admin/adult household-wide one (and the scoped GET
  // seed / ws push patch the same key). Empty = unscoped (admin/adult), the prior shape.
  trafficUsageLive: (params: { groupBy: TrafficUsageGroupBy[]; bucket: TrafficUsageBucket; profileIds?: number[] }) =>
    ['usage', 'traffic', 'live', params.bucket, [...params.groupBy].sort().join(','),
      [...(params.profileIds ?? [])].sort((a, b) => a - b).join(',')] as const,
  // #1973: the live connectionEvents feed key (design §3.1). The dashboard's "Recently
  // Blocked" panel keeps its own `recentBlocked()` key (the 15-min window view); this
  // is the shared key the future S6b Connection Events page streams into.
  connectionEventsLive: (filter: Record<string, unknown>) =>
    ['logs', 'live', JSON.stringify(filter)] as const,
  // #1773 — the household-global sentinel profile. Under the `profiles` prefix so
  // `qk.profiles()` invalidation still busts it (react-query walks the prefix tree).
  profilesGlobal: () => ['profiles', 'global'] as const,
  // #1743 — bucket→grain mapping. Server-wide rather than per-household, but scoped with
  // everything else: a redundant refetch after a household switch is the cheap side of
  // the trade, and a per-key opt-out is the hole this map exists to close.
  usageConfig: () => ['usage', 'config'] as const,
  householdSettings: () => ['household', 'settings'] as const,
  blocklists: () => ['blocklists'] as const,
  // #2135 — Stripe billing status, read by BillingPage and the conversion banner.
  billingStatus: () => ['billing', 'status'] as const,
  // Invalidation PREFIXES. These match a family of keys rather than naming one query, and
  // they live here for the same reason as the rest: a bare inline `['time','status']`
  // would sit outside the scope prefix and silently stop matching anything.
  timeStatusAll: () => ['time', 'status'] as const,
  alertsAll: () => ['alerts'] as const,
}

/**
 * #2603 (SECURITY): the exported key factory. Every key is prefixed with the session's
 * household scope (see api/queryScope.ts) so household B's session can never read
 * household A's cache entry, even if the cache-clear on identity change is missed.
 */
export const qk = withHouseholdScope(unscopedKeys)
