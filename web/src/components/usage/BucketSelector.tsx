import type { TrafficUsageBucket } from '@/types/api'

interface BucketOption {
  value: TrafficUsageBucket
  label: string
  disabled?: boolean
  disabledReason?: string
}

// Raw period is whatever the router agent emits — typically 1m in prod,
// 5m in older fixtures. The actual period per row is in `period_end -
// period_start`; we don't promise a value here.
const BUCKETS: BucketOption[] = [
  { value: 'raw', label: 'Raw' },
  {
    value: '1m',
    label: '1m',
    disabled: true,
    disabledReason: 'requires faster router upload cadence — not implemented',
  },
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
}

export function BucketSelector({ value, onChange, hideRaw }: Props) {
  const buckets = hideRaw ? BUCKETS.filter(b => b.value !== 'raw') : BUCKETS
  return (
    <div className="flex flex-wrap gap-2" role="group" aria-label="bucket-selector">
      {buckets.map(b => (
        <button
          key={b.value}
          type="button"
          disabled={b.disabled}
          data-testid={`bucket-${b.value}`}
          onClick={() => {
            if (!b.disabled) onChange(b.value)
          }}
          title={b.disabledReason ?? ''}
          className={`px-3 py-1.5 rounded text-sm font-medium transition-colors ${
            b.disabled
              ? 'bg-gray-900 text-gray-600 cursor-not-allowed'
              : value === b.value
              ? 'bg-emerald-600 text-white'
              : 'bg-gray-800 text-gray-300 hover:bg-gray-700'
          }`}
        >
          {b.label}
        </button>
      ))}
    </div>
  )
}
