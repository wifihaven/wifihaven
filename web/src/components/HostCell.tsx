import type { HostId } from '@/types/api'

// Renders a HostId so resolved-hostname rows and direct-IP rows are visually
// distinguishable (#391). IP rows get italic + a small "ipv4" / "ipv6" tag so
// an admin scanning the logs sees at a glance that the row is unattributed
// traffic (DoH, Apple Private Relay, etc.) rather than a normal browse.
//
// #826: single source of truth for destination presentation across every
// ranked/log surface. The value is always monospace and carries a full-value
// title so truncated cells stay inspectable; bare-IP rows get the one
// canonical unresolved treatment (italic + text-brand-text + type tag) instead
// of each surface hand-rolling its own muted color. Callers pass only
// structural classes (truncate, min-w-0, sizing) via `className`; they must
// NOT pass a text color for IP rows, since the unresolved color is owned here.
// A bare IP is not "blocked" — this styling never implies block state.
export function HostCell({ host, className = '' }: { host: HostId; className?: string }) {
  if (host.type === 'fqdn') {
    return (
      <span className={`font-mono ${className}`.trim()} title={host.value}>
        {host.value}
      </span>
    )
  }
  return (
    <span className={`font-mono italic text-brand-text ${className}`.trim()} title={host.value}>
      {host.value}
      {' '}
      <span className="text-[10px] uppercase tracking-wide text-brand-text-muted">
        {host.type}
      </span>
    </span>
  )
}
