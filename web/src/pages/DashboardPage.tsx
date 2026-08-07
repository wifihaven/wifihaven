import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '@/api/client'
import {
  useDashboardNow,
  useRecentBlocked,
  useRouters,
  LIVE_SURFACE_FALLBACK_REFETCH_MS,
  RECENT_BLOCKED_WINDOW_LABEL,
  RECENT_BLOCKED_FETCH_LABEL,
} from '@/api/queries'
import type {
  DashboardNow,
  DashboardNowDevice,
  DashboardNowProfile,
  DashboardStats,
  QueryLog,
} from '@/types/api'
import { HostCell } from '@/components/HostCell'
import { EmptyState } from '@/components/EmptyState'
import { AccessRequestsBanner, NewDevicesHint } from '@/components/AlertsPanel'
import { blockReasonText } from '@/types/blockReason'
import { useWsTopicLive, useWsNow, useWsRecentBlocked, useWsTrafficUsage } from '@/hooks/useWs'
import { useDataScope } from '@/hooks/useDataScope'
import type { WsTrafficUsage } from '@/hooks/useWs'
import { deriveNowKpis, profileRateMap, nowVisibleDevices, isAnomalousAppliance } from '@/api/wsCache'
import type { BandwidthRate } from '@/api/wsCache'
import { LiveBadge } from '@/components/dashboard/LiveBadge'
import { RateBadge } from '@/components/dashboard/RateBadge'
import { BucketSelector } from '@/components/usage/BucketSelector'

// #2056 (§8.5) — the live-bandwidth windows offered on the dashboard. The 12h/1d/1w history
// buckets make no sense for a live B/s gauge; default `1m` (smooth glance, `raw` opt-in).
const BANDWIDTH_WINDOWS = ['raw', '1m', '10m', '1h'] as const

export function DashboardPage() {
  // #1837 (#1098): per-section skeletons replace the page-level PageLoader that
  // gated the WHOLE render on api.logs.stats(). The live sections (NOW, Recently
  // Blocked, Bandwidth) are independently sourced (ws push + poll) and paint
  // immediately; only the stats-backed 24h panel and the 1h KPI tiles wait on
  // `stats`, and they show skeletons — never placeholder zeros (the #1098
  // anti-pattern) — until it resolves. `stats === null` is the loading state.
  const [stats, setStats] = useState<DashboardStats | null>(null)

  // #2069 — role scoping. #2522: `/api/stats` moved to `requireWriter`, so an adult gets the
  // stats-backed panels too; a child still must not call it (the prod 403 storm).
  // The live surfaces below (NOW, Recently Blocked via `/api/dashboard/*`) are
  // already role-filtered server-side, so every role sees them.
  const scope = useDataScope()

  useEffect(() => {
    if (!scope.isWriter) return
    api.logs.stats().then(setStats).catch(() => { /* keep skeleton on error */ })
  }, [scope.isWriter])

  // #2056 (§8.5) — ONE `trafficUsage{groupBy:profile, bucket}` subscription drives the whole
  // page: the selected window (`bucket`) is page-level state that governs BOTH the Bandwidth
  // KPI tile's overall ▲▼ AND every per-profile ▲▼ on the NOW cards (there is no per-card
  // window control). Lifting the hook here is what makes the selection global — the KPI strip
  // and NOW both read this same instance.
  // #2069: `/api/usage/traffic` needs a `profileId` for a non-admin. A child scopes to its
  // linked profiles; with none linked (or while `/api/me` loads) the seed is disabled so it
  // never fires an unscoped 403 — the tile shows "—". Admin/adult stay unscoped.
  const bandwidthEnabled = scope.isChild ? (scope.childProfileIds?.length ?? 0) > 0 : true
  const bandwidth = useWsTrafficUsage('1m', {
    profileIds: scope.isChild ? scope.childProfileIds ?? undefined : undefined,
    enabled: bandwidthEnabled,
  })

  // §9.1 section order: header (title + global window selector) → banners → Recently Blocked
  // (TOP) → KPI strip (5 tiles) → NOW. Recently Blocked leads as the actionable diagnostic.
  // #2073: the "Blocking activity (24h)" panel is removed (operator review — not useful); the
  // window/bucket selector is lifted OUT of the Bandwidth tile into a page-global header control.
  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <h1 className="text-xl font-bold text-brand-ink">Dashboard</h1>
        <DashboardWindowSelector bandwidth={bandwidth} />
      </div>

      {/* #2522: FirstRunHint reads `GET /api/admin/routers`, which is still `requireAdmin` —
          so it stays on the ACCOUNT capability even though the rest of the page is writer-wide. */}
      {scope.isAdmin && <FirstRunHint />}

      <NewDevicesHint />

      <AccessRequestsBanner />

      <RecentlyBlockedSection />

      <KpiStrip stats={stats} bandwidth={bandwidth} showStats={scope.isWriter} />

      <NowSection bandwidth={bandwidth} />
    </div>
  )
}

// #2073 — the window/bucket selector is a PAGE-GLOBAL control, not a Bandwidth-tile widget. The
// selected window governs the overall ▲▼ on the Bandwidth KPI tile AND every per-profile ▲▼ on
// the NOW cards (one shared `trafficUsage` subscription, §8.5). Homing it in the page header —
// right of the <h1>, labelled "Window:" — makes that scope legible: it visibly sits above
// everything it drives, instead of hiding inside one tile as if it only governed bandwidth. The
// live-relevant windows only (`raw · 1m · 10m · 1h`), not the 12h/1d/1w history buckets.
function DashboardWindowSelector({ bandwidth }: { bandwidth: WsTrafficUsage }) {
  return (
    <div data-testid="dashboard-window-selector" className="flex items-center gap-2">
      <span className="text-xs font-semibold text-brand-text-muted uppercase tracking-wider">
        Window:
      </span>
      <BucketSelector
        value={bandwidth.bucket}
        onChange={bandwidth.setBucket}
        only={[...BANDWIDTH_WINDOWS]}
      />
    </div>
  )
}

// #2133 (multi-tenant P5-3) / #2252: first-run onboarding banner. A freshly-provisioned household
// (invite-accept → this dashboard) has to enroll a router before anything else works, but the live
// panels below only render their own empty states — none tells the new admin what to DO. This card
// names the next concrete step, and is STATE-AWARE across the enrollment lifecycle (#2252) so it
// stops nagging "enroll a router" once an enrollment already exists:
//   1. no router enrolled yet          → "Set up your router →" CTA (to the /router-setup guide, #2234)
//   2. enrollment created, not connected → "Waiting for your router to connect" (run the install
//      script), NOT the enroll CTA
//   3. a router has checked in          → no banner (onboarding is done)
// A `routers` row is created at enrollment-token minting (POST /api/admin/routers) with a null
// `lastSeenAt`; the router's registration step (`completeEnrollment`, Repos.scala) stamps
// `last_seen_at=NOW()` in the same statement that sets `token_hash` (which flips `enrolled`), and
// every later policy poll re-stamps it. So `lastSeenAt != null` on any router means "the router has
// registered / connected" — equivalently `enrolled` — and pending = token minted, install script
// not yet run. It is gated on `isAdmin` at the call site (FirstRunHint is only mounted for admins,
// and GET /api/admin/routers is admin-only). Loading-states rule (#1098): the query
// must be LOADED before we can conclude a state, so we render nothing while pending — a loading `[]`
// must never be read as a real "no routers".
export function FirstRunHint() {
  const routers = useRouters()
  if (routers.isPending || routers.isError) return null
  const list = routers.data ?? []
  const connected = list.some(r => r.lastSeenAt != null)
  if (connected) return null

  const pending = list.length > 0
  return (
    <section
      data-testid="first-run-hint"
      data-state={pending ? 'pending' : 'none'}
      className="bg-brand-accent/5 border border-brand-accent/20 rounded-2xl p-5"
    >
      {pending ? (
        <>
          <h2 className="text-base font-semibold text-brand-ink">Waiting for your router to connect</h2>
          <p className="text-sm text-brand-text mt-1">
            You've enrolled a router, but it hasn't checked in yet. Run the OpenWRT install script on
            the router and paste the enrollment token when prompted. Once it connects, your devices,
            profiles and screen time appear here automatically.
          </p>
          <Link
            to="/router-setup"
            className="inline-block mt-4 bg-brand-accent hover:bg-brand-accent-dark text-white text-sm font-semibold px-4 py-2 rounded-lg transition-colors"
          >
            View install instructions →
          </Link>
        </>
      ) : (
        <>
          <h2 className="text-base font-semibold text-brand-ink">Welcome — let's get your household online</h2>
          <p className="text-sm text-brand-text mt-1">
            Your household is set up, but no router is connected yet. Enroll your router to start
            managing devices, profiles and screen time. It takes a couple of minutes.
          </p>
          <Link
            to="/router-setup"
            className="inline-block mt-4 bg-brand-accent hover:bg-brand-accent-dark text-white text-sm font-semibold px-4 py-2 rounded-lg transition-colors"
          >
            Set up your router →
          </Link>
        </>
      )}
    </section>
  )
}

// ── "Most recently blocked" — live diagnostic feed, polls every 10s ──────────
//
// #1338: newest-first, un-aggregated list of the connection-layer drops in the
// trailing 15 minutes, the recency complement to "Top Blocked". When a site/app
// suddenly stops working the operator wants to see *what just got dropped* (the gstatic /
// ocsp / akamai dependency, the over-broad blocklist hit) without digging into
// the Connection Events page. A row is a real traffic-layer drop — DNS always
// resolves (memory/blocking_is_traffic_layer_not_dns.md). Reuses the dashboard's
// React Query polling + JWT/household scoping via useRecentBlocked.

// #2073 — a compact fixed max-height so the panel scrolls WITHIN itself and stays a glance panel,
// not a feed that dominates the page (operator review). ≈ the NOW grid's height; overflow is the
// "View all →" deep-link, per §8.5.
const RECENTLY_BLOCKED_MAX_H = 'max-h-72'

export function RecentlyBlockedSection() {
  // #2062 — the "All devices ▾" quick device filter. Picking a device narrows BOTH the live
  // subscription (server-side, §8.5) and the fallback poll to that mac; `null` = all devices.
  const [selectedMac, setSelectedMac] = useState<string | null>(null)

  // §8.5 — the device options are drawn from the NOW snapshot's known devices. Passive read
  // (refetchInterval:false): NowSection's poll / `now` push drives the fetch, this just renders
  // the current list, so the two share one cache entry and no second poller runs.
  const { data: now = null } = useDashboardNow({ refetchInterval: false })
  const devices = useMemo(() => nowDeviceList(now), [now])
  const selectedName = selectedMac
    ? devices.find(d => d.mac === selectedMac)?.name ?? selectedMac
    : null
  // If the filtered device ages out of the NOW snapshot (goes idle > 5 min) while its filter is
  // still active, keep it as a sticky option so the control keeps showing it as selected and the
  // list stays narrowed — rather than the <select> snapping back to "All devices" while the feed
  // is still filtered.
  const deviceOptions = useMemo(
    () => (selectedMac && !devices.some(d => d.mac === selectedMac)
      ? [{ mac: selectedMac, name: selectedName ?? selectedMac }, ...devices]
      : devices),
    [devices, selectedMac, selectedName],
  )

  // #1973: subscribe `connectionEvents{blocked:true, macs?}` — pushes prepend into the same
  // `recentBlocked(mac)` cache this query reads (§3.1). Polling stays the PAUSED fallback
  // (§3.3): gated off only while the topic is actually STREAMING (subscribed + acked), so
  // a role whose subscription the server rejects keeps polling instead of going stale.
  const streaming = useWsTopicLive('connectionEvents')
  useWsRecentBlocked(selectedMac)
  // #2601 — read `isPending` / `isError` explicitly instead of collapsing both into a
  // `data ?? null` sentinel. react-query leaves `data` undefined on error as well as
  // while pending, so the old `const { data = null }` rendered a FAILED query as
  // "Loading recent blocks…" forever (docs/process/loading-states.md §error).
  const { data, isPending, isError, refetch } = useRecentBlocked(selectedMac, {
    refetchInterval: streaming ? false : LIVE_SURFACE_FALLBACK_REFETCH_MS,
  })

  // #2073 — group the flat feed under a per-profile header, preserving newest-first order both
  // across groups (by first appearance) and within each group.
  const groups = useMemo(() => groupByProfile(data?.rows ?? []), [data])
  // #2073/#2062 — "View all →" lands on the Connection Events page pre-filtered to blocked. The
  // page reads `?status=blocked` (LogsPage.parseStatus) — NOT `?blocked=true` — and `?mac=` when
  // a device filter is active, so the destination lands already filtered.
  const viewAllHref = `/usage/events?status=blocked${
    selectedMac ? `&mac=${encodeURIComponent(selectedMac)}` : ''
  }`

  return (
    <section data-testid="recently-blocked-section" className="bg-white rounded-2xl border border-brand-border overflow-hidden">
      <div className="px-5 py-4 border-b border-brand-border flex items-center justify-between gap-3">
        <h2 className="text-sm font-semibold text-brand-text uppercase tracking-wider">
          Recently Blocked
          {/* #2601 — NAME the window. Prod dropped Google Drive traffic for a profile and
              the operator read the panel's silence as "nothing was blocked"; nothing on
              screen said the view was only 15 minutes wide. */}
          <span
            data-testid="recently-blocked-window-label"
            className="ml-2 font-normal normal-case tracking-normal text-brand-text-muted"
          >
            {RECENT_BLOCKED_WINDOW_LABEL}
          </span>
        </h2>
        <div className="flex items-center gap-3 shrink-0">
          {deviceOptions.length > 0 && (
            <select
              data-testid="recently-blocked-device-filter"
              aria-label="Filter recently blocked by device"
              value={selectedMac ?? ''}
              onChange={e => setSelectedMac(e.target.value || null)}
              className="text-xs bg-brand-alt text-brand-text border border-brand-border rounded px-2 py-1 max-w-[10rem]"
            >
              <option value="">All devices</option>
              {deviceOptions.map(d => (
                <option key={d.mac} value={d.mac}>{d.name}</option>
              ))}
            </select>
          )}
          <Link to={viewAllHref} className="text-xs text-brand-accent-dark hover:underline">
            View all →
          </Link>
        </div>
      </div>
      {/* #2601 — a failed poll must never blank a panel that already has real drops on
          screen. react-query flips `status` to 'error' on a failed BACKGROUND refetch
          while `data` stays populated, and this panel exists precisely so enforcement
          isn't invisible, so last-known-good rows keep rendering under an inline error
          strip. The full-panel error is only for a failure with nothing to fall back on. */}
      {isError && (
        <div
          // role="status" (polite) rather than "alert" (assertive): this fires on a
          // background-refetch failure that leaves real rows on screen, so it is a status
          // change, not an emergency — and an assertive live region containing the Retry
          // button would announce the region without reliably exposing the control.
          role="status"
          className="px-5 py-3 flex items-center gap-3 border-b border-brand-border"
          data-testid="recently-blocked-error"
        >
          <p className="text-brand-text-muted text-sm">
            Couldn&rsquo;t load recent blocks{data ? '; showing the last successful read' : ''}.
          </p>
          <button
            type="button"
            onClick={() => { void refetch() }}
            className="text-xs text-brand-accent-dark hover:underline"
          >
            Retry
          </button>
        </div>
      )}
      {isPending
        ? <p className="px-5 py-4 text-brand-text-muted text-sm">Loading recent blocks…</p>
        : !data
          // Errored with nothing cached — the strip above already said so; adding an
          // empty state here would read as "nothing was blocked".
          ? null
          : data.rows.length === 0
            ? (
              <div className="px-5 py-4">
                <EmptyState
                  variant="inline"
                  title={selectedName ? `Nothing blocked recently for ${selectedName}` : 'Nothing blocked recently'}
                  hint={
                    // #2601 — two different reasons this list is empty, and neither is
                    // "nothing was blocked". Say which one applies.
                    data.olderCount > 0
                      // Real drops exist in the fetched hour, just none in the trailing
                      // window. `olderCount` is capped by RECENT_BLOCKED_LIMIT, so a
                      // saturated page reports a floor ("20+") rather than a total it
                      // cannot see. Hand them the wider view instead of dead-ending.
                      ? (
                        <span data-testid="recently-blocked-stale-hint">
                          {data.olderCountTruncated
                            ? `${data.olderCount}+ blocks`
                            : data.olderCount === 1 ? '1 block' : `${data.olderCount} blocks`}
                          {` in ${RECENT_BLOCKED_FETCH_LABEL}, outside this window. `}
                          <Link to={viewAllHref} className="text-brand-accent-dark hover:underline">
                            See all blocks
                          </Link>
                        </span>
                      )
                      // Nothing anywhere in the fetched hour. No caveat copy here on
                      // purpose: the ~26s a just-now block took to arrive is a pipeline
                      // defect, not a fact to apologise for in the UI. Telling the operator
                      // to wait would dress up the latency instead of removing it. The
                      // latency itself is NOT fixed yet — it is the ws sidecar draining its
                      // outbound spool only after the inbound read times out — and is
                      // tracked separately in #2620.
                      : undefined
                  }
                />
              </div>
            )
            : (
            <div
              data-testid="recently-blocked-scroll"
              className={`${RECENTLY_BLOCKED_MAX_H} overflow-y-auto divide-y divide-brand-border`}
            >
              {groups.map(g => (
                <div key={g.profileId ?? 'none'}>
                  <RecentlyBlockedGroupHeader group={g} />
                  <ul className="divide-y divide-brand-border">
                    {g.rows.map(row => <RecentlyBlockedRow key={row.id} row={row} />)}
                  </ul>
                </div>
              ))}
            </div>
          )
      }
    </section>
  )
}

// #2073 — the blocked rows grouped under one profile. `profileId == null` (an unattributed drop)
// buckets into a headerless "No profile" group rather than vanishing.
interface BlockedGroup {
  profileId: number | null
  profileName: string | null
  rows: QueryLog[]
}

// Group newest-first rows by profile, preserving order: groups appear in first-seen order, rows
// stay in feed order within each group.
function groupByProfile(rows: QueryLog[]): BlockedGroup[] {
  const order: (number | null)[] = []
  const byProfile = new Map<number | null, BlockedGroup>()
  for (const r of rows) {
    let g = byProfile.get(r.profileId)
    if (!g) {
      g = { profileId: r.profileId, profileName: r.profileName, rows: [] }
      byProfile.set(r.profileId, g)
      order.push(r.profileId)
    }
    g.rows.push(r)
  }
  return order.map(k => byProfile.get(k)!)
}

// The dedup'd, name-sorted device list from the NOW snapshot (§8.5) — the "All devices ▾" options.
function nowDeviceList(now: DashboardNow | null): { mac: string; name: string }[] {
  if (!now) return []
  const byMac = new Map<string, string>()
  for (const p of now.profiles) {
    for (const d of p.activeDevices) {
      if (!byMac.has(d.mac)) byMac.set(d.mac, d.name)
    }
  }
  return [...byMac.entries()]
    .map(([mac, name]) => ({ mac, name }))
    .sort((a, b) => a.name.localeCompare(b.name))
}

// §9.2 (#2073) — the per-profile GROUP HEADER is the quick-unblock deep-link to its editor:
// `/profiles?id=<profileId>` reuses the existing scroll-to-and-highlight `?id=` deep-link
// ProfilesPage already honours (#298), so a parent jumps from a block straight to unpausing /
// adjusting the schedule / adding an allow. The `connectionEvents` row already carries
// `profileId`/`profileName` (no protocol change — the profile is NOT re-derived client-side).
function RecentlyBlockedGroupHeader({ group }: { group: BlockedGroup }) {
  const label = group.profileName ?? 'No profile'
  return (
    <div className="px-5 py-1.5 bg-brand-alt/50 sticky top-0 z-10 border-b border-brand-border text-xs font-semibold uppercase tracking-wider">
      {group.profileId != null
        ? (
          <Link
            to={`/profiles?id=${group.profileId}`}
            data-testid={`recently-blocked-group-${group.profileId}`}
            className="text-brand-accent-dark hover:underline"
            title={`Open ${label} in the editor`}
          >
            {label} ↗
          </Link>
        )
        : <span className="text-brand-text-muted">{label}</span>
      }
    </div>
  )
}

// §9.2 row shape (under its profile group): `device · host · reason · <relative time>`. The DEVICE
// deep-links to that device's blocked Connection Events (`?status=blocked&mac=`) — "see everything
// this device hit" — a distinct target from the group header's profile (policy) link.
function RecentlyBlockedRow({ row }: { row: QueryLog }) {
  const who = row.deviceName ?? row.mac ?? 'unknown device'
  return (
    <li data-testid={`recently-blocked-${row.id}`} className="px-5 py-2.5 flex items-center gap-2 text-sm">
      <Link
        to={`/usage/events?status=blocked${row.mac ? `&mac=${encodeURIComponent(row.mac)}` : ''}`}
        className="text-brand-ink hover:underline truncate shrink-0 max-w-[30%]"
      >
        {who}
      </Link>
      <span className="text-brand-text-muted shrink-0" aria-hidden>·</span>
      <span className="font-mono text-brand-ink truncate flex-1 min-w-0">
        <HostCell host={row.host} />
      </span>
      <span className="text-xs text-red-700 shrink-0">{blockReasonText(row.reason)}</span>
      <span className="text-xs text-brand-text-muted shrink-0 tabular-nums w-14 text-right">
        {formatRelativeTime(row.ts)}
      </span>
    </li>
  )
}

// Relative "Xs/Xm/Xh ago" from an ISO timestamp. Mirrors formatLastSeen, which
// works in elapsed-seconds; here we derive the elapsed from now() at render time
// (React Query re-renders on each 10s poll, so the label stays roughly live).
function formatRelativeTime(tsIso: string): string {
  const elapsedMs = Date.now() - new Date(tsIso).getTime()
  return formatLastSeen(Math.max(0, elapsedMs / 1000))
}

// ── "Now" section — live snapshot, polls every 10s ───────────────────────────
//
// #803: TanStack Query handles the polling cadence + last-known-good fallback
// (it keeps the previous `data` reference when a refetch errors), so the
// imperative setInterval / try-catch from before is gone.

// #1835 — top-N device cap. Cards show only the 3 most active devices by default,
// the rest behind an in-place expander, so a card full of chatty IoT devices no
// longer dominates the page (the prod `Family` card listed ~11). Device count
// before the card switches to "show N more".
const NOW_DEVICE_CAP = 3

// #1835 (#825) — a device row is "materially stale" (and keeps its inline "Xs ago"
// flag despite the section freshness pill) when last seen more than this many
// seconds before the snapshot's asOf. The >60s window is the #825 design call.
const NOW_STALE_SECONDS = 60

// #1835 — a device's rank inside its card is its summed top-hosts active-seconds
// over the NOW window, NOT recency (design §7 Q6): recency would float a Sonos
// heartbeat above an actively-streaming MacBook.
function deviceActiveSeconds(d: DashboardNowDevice): number {
  return d.topHosts.reduce((sum, h) => sum + h.activeSeconds, 0)
}

// #1835 — a profile is idle when it has zero VISIBLE active devices. The snapshot's
// activeDevices is already the last-5-min set (§7 Q4); §9.3 then filters appliances out, so a
// profile whose only activity is hidden IoT chatter (no personal device, no anomalous
// appliance) collapses into the idle row rather than showing an empty active card. Paused-and-
// idle profiles are idle too (the paused tag is preserved on render).
function profileIsIdle(p: DashboardNowProfile): boolean {
  return nowVisibleDevices(p.activeDevices).length === 0
}

// #1835 — per-session UI toggle (top-N expander, idle collapse) backed by
// sessionStorage, so it survives SPA navigation within the tab session but resets
// when the tab/session ends (unlike localStorage, which would persist forever).
function useSessionToggle(key: string, initial = false): [boolean, (v: boolean) => void] {
  const [on, setOn] = useState<boolean>(() => {
    try {
      const raw = sessionStorage.getItem(key)
      return raw === null ? initial : raw === '1'
    } catch {
      return initial
    }
  })
  const set = useCallback((v: boolean) => {
    setOn(v)
    try { sessionStorage.setItem(key, v ? '1' : '0') } catch { /* storage unavailable */ }
  }, [key])
  return [on, set]
}

export function NowSection({ bandwidth }: { bandwidth?: WsTrafficUsage }) {
  // #1973: `now` is pushed whole (§3.1) — replace the dashboard-now cache live. Derived
  // "Online now" KPI recomputes client-side off the pushed body. Polling stays the paused
  // fallback (§3.3), gated on the `now` topic actually STREAMING (subscribed + acked) — a
  // role whose `now` subscription is server-rejected keeps polling rather than freezing.
  const streaming = useWsTopicLive('now')
  useWsNow()
  const { data = null, dataUpdatedAt } = useDashboardNow({ refetchInterval: streaming ? false : LIVE_SURFACE_FALLBACK_REFETCH_MS })
  const kpis = deriveNowKpis(data)

  // §8.5 — the per-profile ▲▼ on each card header is keyed by profile NAME against the SAME
  // global window the KPI Bandwidth tile uses (`bandwidth.bucket`). A profile with no live-edge
  // row resolves to `null` → the header shows `—`, not a measured zero.
  const rates = bandwidth ? profileRateMap(bandwidth.rows, bandwidth.bucket) : new Map<string, BandwidthRate>()
  const rateFor = (name: string): BandwidthRate | null => (bandwidth?.live ? rates.get(name) ?? null : null)

  // #1835: split active from idle. A profile is idle when it has zero active
  // devices (the snapshot's activeDevices is already the last-5-min set, §7 Q4);
  // paused-and-idle profiles fall here too. Active order is preserved as-is.
  const active = data?.profiles.filter(p => !profileIsIdle(p)) ?? []
  const idle = data?.profiles.filter(profileIsIdle) ?? []

  return (
    <section data-testid="now-section" className="space-y-3">
      <div className="flex items-center justify-between gap-3">
        <h2 className="text-sm font-semibold text-brand-text uppercase tracking-wider">Now</h2>
        <div className="flex items-center gap-3">
          <span data-testid="now-kpi-online" className="text-xs text-brand-text-muted">
            Online now: <span className="font-semibold text-brand-ink tabular-nums">{kpis.onlineNow}</span>
          </span>
          {data !== null && <FreshnessPill updatedAt={dataUpdatedAt} />}
          <LiveBadge />
        </div>
      </div>
      {data === null
        ? <p className="text-brand-text-muted text-sm">Loading live activity…</p>
        : data.profiles.length === 0
          ? <EmptyState variant="inline" title="No profiles configured yet." />
          : (
            <>
              {active.length > 0 && (
                <div className="grid md:grid-cols-2 gap-4">
                  {active.map(p => <NowProfileCard key={p.id} profile={p} rate={rateFor(p.name)} />)}
                </div>
              )}
              {idle.length > 0 && <IdleCollapseRow profiles={idle} />}
            </>
          )
      }
    </section>
  )
}

// #1835 (#825): single freshness indicator for the whole NOW section, sourced from
// TanStack Query's `dataUpdatedAt` (the last successful fetch/push), NOT a
// per-component self-timer. Elapsed is recomputed at render; the section re-renders
// on every poll/ws push (≤10s), so the label stays current without its own timer.
function FreshnessPill({ updatedAt }: { updatedAt: number }) {
  const elapsed = Math.max(0, (Date.now() - updatedAt) / 1000)
  return (
    <span data-testid="now-freshness" className="text-xs text-brand-text-muted tabular-nums">
      updated {formatLastSeen(elapsed)}
    </span>
  )
}

function NowProfileCard({ profile, dimmed = false, rate = null }: { profile: DashboardNowProfile; dimmed?: boolean; rate?: BandwidthRate | null }) {
  const idle = profileIsIdle(profile)
  const muted = dimmed || idle
  return (
    <div
      data-testid={`now-profile-${profile.id}`}
      className={`bg-white rounded-2xl border p-5 ${muted ? 'border-brand-border opacity-60' : 'border-brand-accent-dark/50'}`}
    >
      {/* §8.5 — name on the left, the per-profile ▲▼ rate right-aligned on the SAME header row
          (no extra row; the device list below is unchanged). The rate is governed by the
          page-wide window selector. */}
      <div className="flex items-center justify-between gap-2 mb-3">
        <div className="flex items-center gap-2 min-w-0">
          <h3 className="text-base font-semibold text-brand-ink truncate">{profile.name}</h3>
          {profile.paused && (
            <span className="text-[10px] font-bold uppercase tracking-wider bg-amber-100/60 text-amber-700 px-2 py-0.5 rounded shrink-0">
              Paused
            </span>
          )}
        </div>
        <RateBadge rate={rate} testId={`now-profile-rate-${profile.id}`} />
      </div>
      {idle
        ? <EmptyState variant="inline" title="No activity in the last 5 minutes" />
        : <NowDeviceList profile={profile} />
      }
    </div>
  )
}

// #1835 (#819): rank by active-seconds, cap at top-3, expander for the rest.
// §9.3 — appliances are filtered out first (nowVisibleDevices), so a card of Sonos/Lutron/
// printer chatter no longer dominates; an appliance reappears only when its traffic is
// anomalous, rendered with a ⚠ flag (NowDeviceRow).
function NowDeviceList({ profile }: { profile: DashboardNowProfile }) {
  const [expanded, setExpanded] = useSessionToggle(`wh.now.card.${profile.id}`)
  const ranked = nowVisibleDevices(profile.activeDevices).sort(
    (a, b) => deviceActiveSeconds(b) - deviceActiveSeconds(a),
  )
  const overflow = ranked.length - NOW_DEVICE_CAP
  const shown = expanded ? ranked : ranked.slice(0, NOW_DEVICE_CAP)
  return (
    <div className="space-y-3">
      {shown.map(d => <NowDeviceRow key={d.mac} device={d} />)}
      {overflow > 0 && (
        <button
          type="button"
          onClick={() => setExpanded(!expanded)}
          className="text-xs text-brand-accent-dark hover:underline"
        >
          {expanded ? 'show fewer' : `show ${overflow} more`}
        </button>
      )}
    </div>
  )
}

// #1835 (#820): collapse all zero-active profiles into one line below the active
// grid, expandable in place to the full dimmed cards (paused tags preserved).
function IdleCollapseRow({ profiles }: { profiles: DashboardNowProfile[] }) {
  const [expanded, setExpanded] = useSessionToggle('wh.now.idle')
  return (
    <div data-testid="now-idle-collapse">
      <button
        type="button"
        onClick={() => setExpanded(!expanded)}
        aria-expanded={expanded}
        className="text-sm text-brand-text-muted hover:text-brand-text w-full text-left"
      >
        <span className="font-medium text-brand-text">Idle ({profiles.length}):</span>{' '}
        <span className="truncate">{profiles.map(p => p.name).join(' · ')}</span>{' '}
        <span aria-hidden>{expanded ? '▾' : '▸'}</span>
      </button>
      {expanded && (
        <div className="grid md:grid-cols-2 gap-4 mt-3">
          {profiles.map(p => <NowProfileCard key={p.id} profile={p} dimmed />)}
        </div>
      )}
    </div>
  )
}

function NowDeviceRow({ device }: { device: DashboardNowDevice }) {
  // #1835 (#825): no per-row timestamp — the section's single freshness pill
  // covers freshness. The exception: a row materially staler than the snapshot
  // (lastSeenSeconds is measured against the snapshot's asOf) still flags inline.
  const stale = device.lastSeenSeconds > NOW_STALE_SECONDS
  // §9.3 — an appliance surfaced here is, by construction, an ANOMALOUS one (a non-anomalous
  // appliance is filtered out upstream): flag it as a likely-compromise signal.
  const anomalous = isAnomalousAppliance(device)
  return (
    <div data-testid={`now-device-${device.mac}`} className="border-t border-brand-border first:border-0 pt-3 first:pt-0">
      <div className="flex items-baseline justify-between gap-2">
        <p className={`text-sm font-medium truncate ${anomalous ? 'text-amber-700' : 'text-brand-ink'}`}>
          {anomalous && <span aria-hidden>⚠ </span>}{device.name}
          {anomalous && <span className="ml-1 text-xs font-normal text-amber-700">· unusual</span>}
        </p>
        {stale && (
          <p className="text-xs text-brand-text-muted shrink-0">{formatLastSeen(device.lastSeenSeconds)}</p>
        )}
      </div>
      <NowActivityLine device={device} />
      {device.topHosts.length > 0 && (
        <ul className="mt-2 space-y-0.5">
          {device.topHosts.map(h => (
            <li key={`${h.host.type}:${h.host.value}`} className="flex justify-between text-xs text-brand-text">
              <span className="font-mono truncate"><HostCell host={h.host} /></span>
              <span className="text-brand-text-muted ml-2 shrink-0">{formatDuration(h.activeSeconds)}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

// #852 — "watching X · Nm" line, the replacement for the old `currentSession` widget.
// Show `(active)` when the device is talking but the latest bucket has no resolvable top host
// (heartbeat-only, all-IP, or the bucket hasn't closed yet) — be honest about the unknown.
function NowActivityLine({ device }: { device: DashboardNowDevice }) {
  const a = device.nowActivity
  if (a == null) {
    return <p className="mt-1 text-xs text-brand-text-muted italic">(active)</p>
  }
  return (
    <p className="mt-1 text-xs text-brand-text">
      <span className="text-brand-text-muted">watching</span>{' '}
      <span className="font-mono"><HostCell host={a.topHost} /></span>
      {a.minutes != null && <span className="text-brand-text-muted"> · {a.minutes}m</span>}
    </p>
  )
}

function formatLastSeen(seconds: number): string {
  if (seconds < 60) return `${Math.max(0, Math.round(seconds))}s ago`
  if (seconds < 3600) return `${Math.round(seconds / 60)}m ago`
  return `${Math.round(seconds / 3600)}h ago`
}

function formatDuration(seconds: number): string {
  if (seconds < 60) return `${Math.round(seconds)}s`
  const mins = Math.round(seconds / 60)
  if (mins < 60) return `${mins}m`
  const h = Math.floor(mins / 60)
  const m = mins % 60
  return m === 0 ? `${h}h` : `${h}h ${m}m`
}

// #1834: status-first KPI strip, rendered immediately under the <h1> and ABOVE the
// NOW section (design docs/design/dashboard-redesign.md §7.3 Q1). "Online now" /
// "Blocked now" derive from the live NOW snapshot via deriveNowKpis — the same
// dashboard-now cache NowSection populates (10s poll + `now` ws push, wsCache §3.1)
// — so this is a pure cache reader: no new endpoint and no second poller
// (refetchInterval:false; NowSection drives the refresh, both observers re-render).
// The cumulative "today" volume tiles are dropped: daily volume is analytics and
// lives on /usage/events (design §2 non-goals).
function KpiStrip({ stats, bandwidth, showStats = true }: { stats: DashboardStats | null; bandwidth?: WsTrafficUsage; showStats?: boolean }) {
  const { data: now = null } = useDashboardNow({ refetchInterval: false })
  const kpis = deriveNowKpis(now)
  // #1837: every tile passes `null` until its source resolves and renders a
  // skeleton rather than a placeholder "0" (the #1098 false-all-clear) — Online
  // now / Blocked now until the NOW snapshot arrives, Events(1h)/Blocked(1h) until
  // `stats` does. Both sources are independent, so each tile paints on its own.
  // #2056 (§9.1): FIVE tiles — the Bandwidth gauge folds into the strip as the 5th tile (the
  // standalone LIVE BANDWIDTH panel is gone). #2073: its window selector moved OUT to the page-
  // global DashboardWindowSelector in the header; the selected window still governs ▲▼ everywhere
  // (this tile AND the NOW cards) via the one shared `bandwidth` instance.
  // #2069: the Events(1h)/Blocked(1h) tiles are backed by admin-only `/api/stats`; a non-admin
  // (`showStats=false`) drops them rather than skeleton-forever (the #1098 false-loading trap).
  return (
    <div data-testid="kpi-strip" className={`grid grid-cols-2 gap-4 ${showStats ? 'md:grid-cols-5' : 'md:grid-cols-3'}`}>
      <StatCard label="Online now"   value={now === null ? null : kpis.onlineNow}  accent="emerald" />
      <StatCard label="Blocked now"  value={now === null ? null : kpis.blockedNow} accent="red" />
      {showStats && <StatCard label="Events (1h)"  value={stats?.totalHour ?? null}   accent="emerald" />}
      {showStats && <StatCard label="Blocked (1h)" value={stats?.blockedHour ?? null} accent="yellow" />}
      <BandwidthTile bandwidth={bandwidth} />
    </div>
  )
}

// #2056 (§8.5) — the 5th KPI tile: the overall household ▲▼ /s. Three states, mirroring the old
// gauge (#2041): loading (skeleton, never "0 B/s"), live (rate), or paused/no-edge ("—").
// #2073: the window selector no longer lives here — it moved to the page-global
// DashboardWindowSelector in the header, since it governs every rate on the page, not just this
// tile. The rate shown here still reads the same shared `bandwidth.bucket` that selector sets.
function BandwidthTile({ bandwidth }: { bandwidth?: WsTrafficUsage }) {
  return (
    <div data-testid="kpi-bandwidth" className="bg-white rounded-2xl border border-brand-border p-5 flex flex-col gap-2">
      <p className="text-xs font-semibold text-brand-text-muted uppercase tracking-wider">Bandwidth</p>
      <div className="min-h-[2.25rem] flex items-center">
        {bandwidth == null
          ? <span className="font-mono text-sm text-brand-text-muted">—</span>
          : bandwidth.loading
            ? <span data-testid="kpi-bandwidth-loading" className="inline-block h-5 w-28 rounded bg-brand-border/70 animate-pulse" aria-hidden />
            : bandwidth.live
              ? <RateBadge rate={bandwidth.overall} testId="kpi-bandwidth-rate" />
              : <span data-testid="kpi-bandwidth-idle" className="font-mono text-sm text-brand-text-muted">—</span>}
      </div>
    </div>
  )
}

function StatCard({ label, value, accent }: { label: string; value: number | null; accent: string }) {
  const colors: Record<string, string> = {
    emerald: 'text-brand-accent',
    red: 'text-red-700',
    yellow: 'text-amber-700',
  }
  return (
    <div className="bg-white rounded-2xl border border-brand-border p-5">
      <p className="text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2">{label}</p>
      {value === null
        ? <div data-testid="stat-skeleton" className="h-9 w-16 bg-brand-border/60 rounded animate-pulse" aria-hidden />
        : <p className={`text-3xl font-bold ${colors[accent] ?? 'text-brand-ink'}`}>{value}</p>
      }
    </div>
  )
}

function PageLoader() {
  return (
    <div className="flex items-center justify-center h-64">
      <div className="w-8 h-8 border-2 border-brand-accent border-t-transparent rounded-full animate-spin" />
    </div>
  )
}

export { PageLoader, formatRelativeTime }
