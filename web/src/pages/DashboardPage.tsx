import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '@/api/client'
import { useDashboardNow, useRecentBlocked } from '@/api/queries'
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
import { useWsTopicLive, useWsNow, useWsRecentBlocked } from '@/hooks/useWs'
import { deriveNowKpis } from '@/api/wsCache'
import { LiveBadge } from '@/components/dashboard/LiveBadge'
import { BandwidthGauges } from '@/components/dashboard/BandwidthGauges'

export function DashboardPage() {
  const [stats,  setStats]  = useState<DashboardStats | null>(null)
  const [loading, setLoading] = useState(true)

  useEffect(() => {
    api.logs.stats()
      .then(setStats)
      .finally(() => setLoading(false))
  }, [])

  if (loading) return <PageLoader />

  return (
    <div className="space-y-6">
      <h1 className="text-xl font-bold text-brand-ink">Dashboard</h1>

      {stats && <KpiStrip stats={stats} />}

      <NewDevicesHint />

      <AccessRequestsBanner />

      <RecentlyBlockedSection />

      <BandwidthGauges />

      <NowSection />

      {stats && (
        <>
          <div className="grid md:grid-cols-2 gap-6">
            <section className="bg-white rounded-2xl border border-brand-border p-5">
              <h2 className="text-sm font-semibold text-brand-text uppercase tracking-wider mb-4">
                Top Blocked (24h)
              </h2>
              {stats.topBlocked.length === 0
                ? <EmptyState variant="inline" title="No blocked events yet" />
                : stats.topBlocked.map(d => (
                    <div key={`${d.host.type}:${d.host.value}`} className="flex justify-between items-center py-2 border-b border-brand-border last:border-0">
                      <span className="font-mono text-sm text-brand-text truncate"><HostCell host={d.host} /></span>
                      <span className="text-red-700 font-mono text-sm ml-4 shrink-0">{d.count}</span>
                    </div>
                  ))
              }
            </section>

            <section className="bg-white rounded-2xl border border-brand-border p-5">
              <h2 className="text-sm font-semibold text-brand-text uppercase tracking-wider mb-4">
                Per Device (24h)
              </h2>
              {stats.perDevice.map(d => (
                <div key={d.mac} className="flex items-center gap-3 py-2 border-b border-brand-border last:border-0">
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-medium text-brand-ink truncate">{d.deviceName}</p>
                    <p className="text-xs text-brand-text-muted font-mono">{d.mac}</p>
                  </div>
                  <div className="text-right shrink-0">
                    <p className="text-sm text-brand-text">{d.total} events</p>
                    <p className="text-xs text-red-700">{d.blocked} blocked</p>
                  </div>
                </div>
              ))}
            </section>
          </div>
        </>
      )}
    </div>
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
  const { data = null } = useRecentBlocked({ refetchInterval: streaming ? false : 10_000 })

  return (
    <section data-testid="recently-blocked-section" className="bg-white rounded-2xl border border-brand-border overflow-hidden">
      <div className="px-5 py-4 border-b border-brand-border flex items-center justify-between gap-3">
        <h2 className="text-sm font-semibold text-brand-text uppercase tracking-wider">
          Most Recently Blocked
        </h2>
        <Link to="/usage/events" className="text-xs text-brand-accent-dark hover:underline shrink-0">
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

function RecentlyBlockedRow({ row }: { row: QueryLog }) {
  const who = row.deviceName ?? row.mac ?? 'unknown device'
  return (
    <li data-testid={`recently-blocked-${row.id}`} className="px-5 py-2.5 flex items-center gap-3">
      <span className="text-xs text-brand-text-muted shrink-0 w-16 tabular-nums">
        {formatRelativeTime(row.ts)}
      </span>
      <span className="font-mono text-sm text-brand-ink truncate flex-1 min-w-0">
        <HostCell host={row.host} />
      </span>
      <span className="text-xs text-brand-text-muted truncate hidden sm:block max-w-[40%]">
        {who}{row.profileName ? ` · ${row.profileName}` : ''}
      </span>
      <span className="text-xs text-red-700 shrink-0">{blockReasonText(row.reason)}</span>
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

// #1835 — a device's rank inside its card is its summed top-hosts active-seconds
// over the NOW window, NOT recency (design §7 Q6): recency would float a Sonos
// heartbeat above an actively-streaming MacBook.
function deviceActiveSeconds(d: DashboardNowDevice): number {
  return d.topHosts.reduce((sum, h) => sum + h.activeSeconds, 0)
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

export function NowSection() {
  // #1973: `now` is pushed whole (§3.1) — replace the dashboard-now cache live. Derived
  // "Online now" KPI recomputes client-side off the pushed body. Polling stays the paused
  // fallback (§3.3), gated on the `now` topic actually STREAMING (subscribed + acked) — a
  // role whose `now` subscription is server-rejected keeps polling rather than freezing.
  const streaming = useWsTopicLive('now')
  useWsNow()
  const { data = null, dataUpdatedAt } = useDashboardNow({ refetchInterval: streaming ? false : 10_000 })
  const kpis = deriveNowKpis(data)

  // #1835: split active from idle. A profile is idle when it has zero active
  // devices (the snapshot's activeDevices is already the last-5-min set, §7 Q4);
  // paused-and-idle profiles fall here too. Active order is preserved as-is.
  const active = data?.profiles.filter(p => p.activeDevices.length > 0) ?? []
  const idle = data?.profiles.filter(p => p.activeDevices.length === 0) ?? []

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
                  {active.map(p => <NowProfileCard key={p.id} profile={p} />)}
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

function NowProfileCard({ profile, dimmed = false }: { profile: DashboardNowProfile; dimmed?: boolean }) {
  const idle = profile.activeDevices.length === 0
  const muted = dimmed || idle
  return (
    <div
      data-testid={`now-profile-${profile.id}`}
      className={`bg-white rounded-2xl border p-5 ${muted ? 'border-brand-border opacity-60' : 'border-brand-accent-dark/50'}`}
    >
      <div className="flex items-center gap-2 mb-3">
        <h3 className="text-base font-semibold text-brand-ink">{profile.name}</h3>
        {profile.paused && (
          <span className="text-[10px] font-bold uppercase tracking-wider bg-amber-100/60 text-amber-700 px-2 py-0.5 rounded">
            Paused
          </span>
        )}
      </div>
      {idle
        ? <EmptyState variant="inline" title="No activity in the last 5 minutes" />
        : <NowDeviceList profile={profile} />
      }
    </div>
  )
}

// #1835 (#819): rank by active-seconds, cap at top-3, expander for the rest.
function NowDeviceList({ profile }: { profile: DashboardNowProfile }) {
  const [expanded, setExpanded] = useSessionToggle(`wh.now.card.${profile.id}`)
  const ranked = [...profile.activeDevices].sort(
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
  const stale = device.lastSeenSeconds > 60
  return (
    <div data-testid={`now-device-${device.mac}`} className="border-t border-brand-border first:border-0 pt-3 first:pt-0">
      <div className="flex items-baseline justify-between gap-2">
        <p className="text-sm font-medium text-brand-ink truncate">{device.name}</p>
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
function KpiStrip({ stats }: { stats: DashboardStats }) {
  const { data: now = null } = useDashboardNow({ refetchInterval: false })
  const kpis = deriveNowKpis(now)
  return (
    <div data-testid="kpi-strip" className="grid grid-cols-2 md:grid-cols-4 gap-4">
      <StatCard label="Online now"   value={kpis.onlineNow}  accent="emerald" />
      <StatCard label="Blocked now"  value={kpis.blockedNow} accent="red" />
      <StatCard label="Events (1h)"  value={stats.totalHour}  accent="emerald" />
      <StatCard label="Blocked (1h)" value={stats.blockedHour} accent="yellow" />
    </div>
  )
}

function StatCard({ label, value, accent }: { label: string; value: number; accent: string }) {
  const colors: Record<string, string> = {
    emerald: 'text-brand-accent',
    red: 'text-red-700',
    yellow: 'text-amber-700',
  }
  return (
    <div className="bg-white rounded-2xl border border-brand-border p-5">
      <p className="text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2">{label}</p>
      <p className={`text-3xl font-bold ${colors[accent] ?? 'text-brand-ink'}`}>{value}</p>
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
