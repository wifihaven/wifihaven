import { useWsTrafficUsage } from '@/hooks/useWs'
import { BucketSelector } from '@/components/usage/BucketSelector'
import { fmtBytes } from '@/components/usage/usageHelpers'
import { EmptyState } from '@/components/EmptyState'
import type { TrafficUsageAggregateRow } from '@/types/api'
import type { BandwidthRate } from '@/api/wsCache'

// #1973 (SPA-ws S5, design §1.3/§3.1, #747): the live bandwidth gauges — overall +
// per-profile in/out B/s, driven entirely by the `trafficUsage` ws stream. The window
// (bucket) selector re-subscribes on change (#747); the client derives B/s = totalBytes
// / bucketSeconds (server stays the source of bytes). Live throughput has NO poll
// fallback (§3.3) — it shows "—" while disconnected or before the first push.

// "X/s" — reuse the page's byte formatter so the units match the Traffic Usage page.
function fmtRate(bytesPerSec: number): string {
  return `${fmtBytes(Math.round(bytesPerSec))}/s`
}

function RateLine({ rate }: { rate: BandwidthRate }) {
  return (
    <span className="font-mono text-sm tabular-nums text-brand-text">
      <span className="text-brand-accent-dark">↓ {fmtRate(rate.bytesInPerSec)}</span>
      <span className="text-brand-text-muted"> · </span>
      <span className="text-amber-700">↑ {fmtRate(rate.bytesOutPerSec)}</span>
    </span>
  )
}

export function BandwidthGauges() {
  const { live, bucket, setBucket, rows, overall, rate } = useWsTrafficUsage('1m')

  // Profile label: groupBy:profile puts the profile name in `groups.profile`.
  const profileLabel = (row: TrafficUsageAggregateRow) =>
    row.groups.profile ?? row.soleProfile ?? 'Unknown profile'

  return (
    <section data-testid="bandwidth-gauges" className="bg-white rounded-2xl border border-brand-border p-5 space-y-4">
      <div className="flex items-center justify-between gap-3 flex-wrap">
        <h2 className="text-sm font-semibold text-brand-text uppercase tracking-wider">
          Live Bandwidth
        </h2>
        <BucketSelector value={bucket} onChange={setBucket} />
      </div>

      <div className="flex items-baseline justify-between gap-3 border-b border-brand-border pb-3">
        <span className="text-sm font-medium text-brand-ink">Overall</span>
        {live
          ? <RateLine rate={overall} />
          : <span data-testid="bandwidth-overall-idle" className="font-mono text-sm text-brand-text-muted">—</span>}
      </div>

      {!live
        ? (
          <p className="text-xs text-brand-text-muted">
            Live throughput pauses while the connection is down.
          </p>
        )
        : rows.length === 0
          ? <EmptyState variant="inline" title="No live traffic right now" />
          : (
            <ul className="space-y-2">
              {rows.map(row => (
                <li
                  key={row.groups.profile ?? `${row.windowStart}`}
                  data-testid={`bandwidth-profile-${row.groups.profile ?? 'unknown'}`}
                  className="flex items-baseline justify-between gap-3"
                >
                  <span className="text-sm text-brand-text truncate">{profileLabel(row)}</span>
                  <RateLine rate={rate(row)} />
                </li>
              ))}
            </ul>
          )
      }
    </section>
  )
}
