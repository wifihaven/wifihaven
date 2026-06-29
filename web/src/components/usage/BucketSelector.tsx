import type { TrafficUsageBucket } from '@/types/api'
import type { BucketGate } from './retentionGating'

interface BucketOption {
  value: TrafficUsageBucket
  label: string
}

// Raw period is whatever the router agent emits — typically 1m in prod,
// 5m in older fixtures. The actual period per row is in `period_end -
// period_start`; we don't promise a value here.
const BUCKETS: BucketOption[] = [
  { value: 'raw', label: 'Raw' },
  { value: '1m',  label: '1m' },
  { value: '10m', label: '10m' },
  { value: '1h',  label: '1h' },
  { value: '12h', label: '12h' },
  { value: '1d',  label: '1d' },
  { value: '1w',  label: '1w' },
]

interface Props {
  value: TrafficUsageBucket
  onChange: (next: TrafficUsageBucket) => void
  // Hide "Raw" when the consumer endpoint doesn't support it (events agg
  // requires a bucket — there's a separate raw view at /api/logs).
  hideRaw?: boolean
  // #814: per-bucket enable/disable derived from the retention horizon for
  // the chosen date range. A disabled bucket is greyed (not hidden) and its
  // `reason` is surfaced as the tooltip.
  gates?: Partial<Record<TrafficUsageBucket, BucketGate>>
  // #2056: restrict to (and order by) a subset of windows — the dashboard
  // Bandwidth tile offers only the live-relevant `1m · 10m · 1h · raw` (§8.5),
  // not the 12h/1d/1w history buckets that make no sense for a live B/s gauge.
  only?: TrafficUsageBucket[]
}

export function BucketSelector({ value, onChange, hideRaw, gates, only }: Props) {
  const buckets = only
    ? only.flatMap(v => BUCKETS.filter(b => b.value === v))
    : hideRaw ? BUCKETS.filter(b => b.value !== 'raw') : BUCKETS
  return (
    <div className="flex flex-wrap gap-2" role="group" aria-label="bucket-selector">
      {buckets.map(b => {
        const gate = gates?.[b.value]
        const disabled = gate ? !gate.enabled : false
        return (
          <button
            key={b.value}
            type="button"
            disabled={disabled}
            data-testid={`bucket-${b.value}`}
            onClick={() => {
              if (!disabled) onChange(b.value)
            }}
            title={disabled ? gate?.reason ?? '' : ''}
            className={`px-3 py-1.5 rounded text-sm font-medium transition-colors ${
              disabled
                ? 'bg-white text-brand-text-muted cursor-not-allowed'
                : value === b.value
                ? 'bg-brand-accent-dark text-brand-ink'
                : 'bg-brand-alt text-brand-text hover:bg-brand-alt'
            }`}
          >
            {b.label}
          </button>
        )
      })}
    </div>
  )
}
