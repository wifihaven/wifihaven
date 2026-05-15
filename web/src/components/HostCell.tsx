import type { HostId } from '@/types/api'

// Renders a HostId so resolved-hostname rows and direct-IP rows are visually
// distinguishable (#391). IP rows get italic + a small "ipv4" / "ipv6" tag so
// an admin scanning the logs sees at a glance that the row is unattributed
// traffic (DoH, Apple Private Relay, etc.) rather than a normal browse.
export function HostCell({ host }: { host: HostId }) {
  if (host.type === 'fqdn') {
    return <span>{host.value}</span>
  }
  return (
    <span className="italic text-gray-400">
      {host.value}
      <span className="ml-2 text-[10px] uppercase tracking-wide text-gray-600">
        {host.type}
      </span>
    </span>
  )
}
