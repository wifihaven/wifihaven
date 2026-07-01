import type { BandwidthRate } from '@/api/wsCache'

// #2056 (§8.5/§9.1) — the live throughput badge, the SINGLE source of the `▲<up> ▼<down> /s`
// rendering shared by the KPI "Bandwidth" tile AND every per-profile NOW card header, so the
// two surfaces can never drift apart. ▲ = bytes OUT (upload), ▼ = bytes IN (download), matching
// the §9.1 wireframe (`▲2.4M ▼18M /s`).
//
// `rate == null` ⇒ unknown (no live-edge bucket yet, or an idle profile): the badge shows a
// single `—`, NOT `▲0 ▼0`, so it never implies a measured zero (§8.5).

// Compact humanized bytes for the rate badge — `K`/`M`/`G` with no space, distinct from the
// page's `fmtBytes` ("2.4 MB") so the dense tile/header stays one short token (§8.5 "humanized
// B/s — K/M"). Base-1024 to match `fmtBytes`' units.
export function fmtRateCompact(bytesPerSec: number): string {
  const n = Math.max(0, bytesPerSec)
  if (n < 1024) return `${Math.round(n)}`
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)}K`
  if (n < 1024 * 1024 * 1024) return `${(n / (1024 * 1024)).toFixed(1)}M`
  return `${(n / (1024 * 1024 * 1024)).toFixed(2)}G`
}

export function RateBadge({ rate, testId }: { rate: BandwidthRate | null; testId?: string }) {
  if (rate == null) {
    return (
      <span data-testid={testId} className="font-mono text-sm tabular-nums text-brand-text-muted">
        —
      </span>
    )
  }
  return (
    <span data-testid={testId} className="font-mono text-sm tabular-nums text-brand-text whitespace-nowrap">
      <span className="text-amber-700">▲ {fmtRateCompact(rate.bytesOutPerSec)}</span>
      <span className="text-brand-text-muted"> </span>
      <span className="text-brand-accent-dark">▼ {fmtRateCompact(rate.bytesInPerSec)}</span>
      <span className="text-brand-text-muted"> /s</span>
    </span>
  )
}
