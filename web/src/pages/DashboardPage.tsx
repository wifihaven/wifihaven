import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '@/api/client'
import { useDashboardNow, useRecentBlocked, LIVE_SURFACE_FALLBACK_REFETCH_MS } from '@/api/queries'
import type {
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

  useEffect(() => {
    api.logs.stats().then(setStats).catch(() => { /* keep skeleton on error */ })
  }, [])

  // #2056 (§8.5) — ONE `trafficUsage{groupBy:profile, bucket}` subscription drives the whole
  // page: the selected window (`bucket`) is page-level state that governs BOTH the Bandwidth
  // KPI tile's overall ▲▼ AND every per-profile ▲▼ on the NOW cards (there is no per-card
  // window control). Lifting the hook here is what makes the selection global — the KPI strip
  // and NOW both read this same instance.
  const bandwidth = useWsTrafficUsage('1m')

  // §9.1 section order: banners → Recently Blocked (TOP) → KPI strip (5 tiles) → NOW →
  // Blocking activity (24h). Recently Blocked leads as the actionable diagnostic.
  return (
    <div className="space-y-6">
      <h1 className="text-xl font-bold text-brand-ink">Dashboard</h1>

      <NewDevicesHint />

      <AccessRequestsBanner />

      <RecentlyBlockedSection />

      <KpiStrip stats={stats} bandwidth={bandwidth} />

      <NowSection bandwidth={bandwidth} />

      <BlockingActivitySection stats={stats} />
    </div>
  )
}

// ── "Blocking activity (24h)" — merged ranked-blocked-host panel ─────────────
//
// #1836 (#822): one panel replaces the old "Top Blocked (24h)" + "Per Device
// (24h)" pair. Per-device traffic *volume* was a leaderboard wearing a security-
// panel hat (17-of-18 rows reading "0 blocked" on prod) — it belongs on /devices
// and /profiles, so the dashboard no longer consumes `stats.perDevice`. The body
// is the ranked blocked-host list (existing `topBlocked` shape); the subtitle
// counts the ranked hosts and sums their blocked events. No "0 blocked" row ever:
// an empty 24h renders a single honest line, not two empty cards (design §7).
// Per-host → "which devices triggered it" expansion is out of scope for v1 —
// `topBlocked` carries no per-device linkage (design §7 Q3).
function BlockingActivitySection({ stats }: { stats: DashboardStats | null }) {
  // #1837: until the (now rollup-backed) 24h stats resolve, show a skeleton — not
  // a "0 hosts · 0 blocked events" subtitle, which would flash a false all-clear
  // before the real numbers arrive (#1098). The header stays so the panel doesn't
  // jump on resolve.
  if (stats === null) {
    return (
      <section className="bg-white rounded-2xl border border-brand-border p-5">
        <div className="flex items-baseline justify-between gap-3 mb-4">
          <h2 className="text-sm font-semibold text-brand-text uppercase tracking-wider">
            Blocking activity (24h)
          </h2>
        </div>
        <div data-testid="blocking-activity-skeleton" className="space-y-2" aria-hidden>
          <div className="h-5 bg-brand-border/60 rounded animate-pulse" />
          <div className="h-5 bg-brand-border/60 rounded animate-pulse w-4/5" />
          <div className="h-5 bg-brand-border/60 rounded animate-pulse w-3/5" />
        </div>
      </section>
    )
  }
  const hostCount = stats.topBlocked.length
  const eventCount = stats.topBlocked.reduce((sum, d) => sum + d.count, 0)
  return (
    <section className="bg-white rounded-2xl border border-brand-border p-5">
      <div className="flex items-baseline justify-between gap-3 mb-4">
        <h2 className="text-sm font-semibold text-brand-text uppercase tracking-wider">
          Blocking activity (24h)
        </h2>
        <span className="text-xs text-brand-text-muted shrink-0">
          {hostCount} {hostCount === 1 ? 'host' : 'hosts'} · {eventCount} blocked events
        </span>
      </div>
      {hostCount === 0
        ? <p className="text-sm text-brand-text-muted">No blocked connection events in the last 24h</p>
        : stats.topBlocked.map(d => (
            <div key={`${d.host.type}:${d.host.value}`} className="flex justify-between items-center py-2 border-b border-brand-border last:border-0">
              <span className="font-mono text-sm text-brand-text truncate"><HostCell host={d.host} /></span>
              <span className="text-red-700 font-mono text-sm ml-4 shrink-0">{d.count}</span>
            </div>
          ))
      }
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

export function RecentlyBlockedSection() {
  // #1973: subscribe `connectionEvents{blocked:true}` — pushes prepend into the same
  // `recentBlocked()` cache this query reads (§3.1). Polling stays the PAUSED fallback
  // (§3.3): gated off only while the topic is actually STREAMING (subscribed + acked), so
  // a role whose subscription the server rejects keeps polling instead of going stale.
  const streaming = useWsTopicLive('connectionEvents')
  useWsRecentBlocked()
  const { data = null } = useRecentBlocked({ refetchInterval: streaming ? false : LIVE_SURFACE_FALLBACK_REFETCH_MS })

  return (
    <section data-testid="recently-blocked-section" className="bg-white rounded-2xl border border-brand-border overflow-hidden">
      <div className="px-5 py-4 border-b border-brand-border flex items-center justify-between gap-3">
        <h2 className="text-sm font-semibold text-brand-text uppercase tracking-wider">
          Recently Blocked
        </h2>
        <Link to="/usage/events?blocked=true" className="text-xs text-brand-accent-dark hover:underline shrink-0">
          View all →
        </Link>
      </div>
      {data === null
        ? <p className="px-5 py-4 text-brand-text-muted text-sm">Loading recent blocks…</p>
        : data.length === 0
          ? <div className="px-5 py-4"><EmptyState variant="inline" title="Nothing blocked recently" /></div>
          : (
            <ul className="divide-y divide-brand-border">
              {data.map(row => <RecentlyBlockedRow key={row.id} row={row} />)}
            </ul>
          )
      }
    </section>
  )
}

// §9.2 row shape: `device · *profile*↗ · host · reason · <relative time>`. The PROFILE is a
// quick-unblock deep-link to its editor — `/profiles?id=<profileId>` reuses the existing
// scroll-to-and-highlight `?id=` deep-link ProfilesPage already honours (#298), so a parent
// jumps from a block straight to unpausing / adjusting the schedule / adding an allow. The
// `connectionEvents` row already carries `profileId`/`profileName` (no protocol change — the
// profile is NOT re-derived client-side). The DEVICE keeps its own deep-link to that device's
// filtered Connection Events, so "change the policy" and "see everything this device hit" are
// two distinct, both-useful targets.
function RecentlyBlockedRow({ row }: { row: QueryLog }) {
  const who = row.deviceName ?? row.mac ?? 'unknown device'
  return (
    <li data-testid={`recently-blocked-${row.id}`} className="px-5 py-2.5 flex items-center gap-2 text-sm">
      <Link
        to={`/usage/events?blocked=true${row.mac ? `&mac=${encodeURIComponent(row.mac)}` : ''}`}
        className="text-brand-ink hover:underline truncate shrink-0 max-w-[28%]"
      >
        {who}
      </Link>
      <span className="text-brand-text-muted shrink-0" aria-hidden>·</span>
      {row.profileId != null
        ? (
          <Link
            to={`/profiles?id=${row.profileId}`}
            data-testid={`recently-blocked-profile-${row.id}`}
            className="italic text-brand-accent-dark hover:underline shrink-0 max-w-[20%] truncate"
            title={`Open ${row.profileName ?? 'profile'} in the editor`}
          >
            {row.profileName ?? 'profile'} ↗
          </Link>
        )
        : <span className="italic text-brand-text-muted shrink-0">{row.profileName ?? '—'}</span>
      }
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
function KpiStrip({ stats, bandwidth }: { stats: DashboardStats | null; bandwidth?: WsTrafficUsage }) {
  const { data: now = null } = useDashboardNow({ refetchInterval: false })
  const kpis = deriveNowKpis(now)
  // #1837: every tile passes `null` until its source resolves and renders a
  // skeleton rather than a placeholder "0" (the #1098 false-all-clear) — Online
  // now / Blocked now until the NOW snapshot arrives, Events(1h)/Blocked(1h) until
  // `stats` does. Both sources are independent, so each tile paints on its own.
  // #2056 (§9.1): FIVE tiles — the Bandwidth gauge + its window selector fold into the strip as
  // the 5th tile (the standalone LIVE BANDWIDTH panel is gone), and the selected window governs
  // ▲▼ everywhere (here AND the NOW cards).
  return (
    <div data-testid="kpi-strip" className="grid grid-cols-2 md:grid-cols-5 gap-4">
      <StatCard label="Online now"   value={now === null ? null : kpis.onlineNow}  accent="emerald" />
      <StatCard label="Blocked now"  value={now === null ? null : kpis.blockedNow} accent="red" />
      <StatCard label="Events (1h)"  value={stats?.totalHour ?? null}   accent="emerald" />
      <StatCard label="Blocked (1h)" value={stats?.blockedHour ?? null} accent="yellow" />
      <BandwidthTile bandwidth={bandwidth} />
    </div>
  )
}

// #2056 (§8.5) — the 5th KPI tile: the overall household ▲▼ /s with the page-wide window
// selector on the footer. Three states, mirroring the old gauge (#2041): loading (skeleton,
// never "0 B/s"), live (rate), or paused/no-edge ("—"). The window selector here IS the global
// control — changing it re-subscribes `trafficUsage` and re-derives every ▲▼ on the page.
function BandwidthTile({ bandwidth }: { bandwidth?: WsTrafficUsage }) {
  return (
    <div data-testid="kpi-bandwidth" className="bg-white rounded-2xl border border-brand-border p-5 flex flex-col gap-3">
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
      {bandwidth != null && (
        <BucketSelector
          value={bandwidth.bucket}
          onChange={bandwidth.setBucket}
          only={[...BANDWIDTH_WINDOWS]}
        />
      )}
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
