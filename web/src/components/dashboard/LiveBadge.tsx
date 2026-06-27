import { useWsLive } from '@/hooks/useWs'

// #1973 (SPA-ws S5, design §6.2): the small "Live / Reconnecting…" badge driven by the
// ws connection's health. The connection's state IS the "is my dashboard live?" signal
// (§0.1) — a green dot when pushes are flowing, amber while reconnecting, grey when the
// socket is off (unauthenticated / no provider / token expired → polling fallback).
export function LiveBadge() {
  const status = useWsLive()
  const { dot, text, label } =
    status === 'live'
      ? { dot: 'bg-emerald-500', text: 'text-emerald-700', label: 'Live' }
      : status === 'reconnecting'
        ? { dot: 'bg-amber-500 animate-pulse', text: 'text-amber-700', label: 'Reconnecting…' }
        : { dot: 'bg-brand-text-muted', text: 'text-brand-text-muted', label: 'Offline' }
  return (
    <span
      data-testid="ws-live-badge"
      data-status={status}
      className={`inline-flex items-center gap-1.5 text-xs font-medium ${text}`}
    >
      <span className={`w-2 h-2 rounded-full ${dot}`} aria-hidden />
      {label}
    </span>
  )
}
