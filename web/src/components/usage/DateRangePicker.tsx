import {
  PRESET_LABELS,
  PRESET_MS,
  isoToLocalInput,
  localInputToIso,
  presetRange,
  type RangePreset,
} from './usageHelpers'

interface Props {
  from: string
  to: string
  preset: RangePreset
  onChange: (next: { from: string; to: string; preset: RangePreset }) => void
}

// #846 — preset chips + custom datetime-local pickers. Replaces the raw
// ISO text inputs flagged in the audit.
export function DateRangePicker({ from, to, preset, onChange }: Props) {
  const presets = Object.keys(PRESET_MS) as Array<Exclude<RangePreset, 'custom'>>

  return (
    <div className="flex flex-wrap items-end gap-3" data-testid="date-range-picker">
      <div className="flex flex-wrap gap-1">
        {presets.map(p => (
          <button
            key={p}
            type="button"
            data-testid={`range-preset-${p}`}
            onClick={() => {
              const r = presetRange(p)
              onChange({ from: r.from, to: r.to, preset: p })
            }}
            className={`px-2.5 py-1.5 rounded text-xs font-medium transition-colors ${
              preset === p
                ? 'bg-emerald-600 text-white'
                : 'bg-gray-800 text-gray-300 hover:bg-gray-700'
            }`}
          >
            {PRESET_LABELS[p]}
          </button>
        ))}
        <button
          type="button"
          data-testid="range-preset-custom"
          onClick={() => onChange({ from, to, preset: 'custom' })}
          className={`px-2.5 py-1.5 rounded text-xs font-medium transition-colors ${
            preset === 'custom'
              ? 'bg-emerald-600 text-white'
              : 'bg-gray-800 text-gray-300 hover:bg-gray-700'
          }`}
        >
          Custom
        </button>
      </div>
      {preset === 'custom' && (
        <>
          <label className="text-xs text-gray-400">
            From
            <input
              type="datetime-local"
              data-testid="from-input"
              value={isoToLocalInput(from)}
              onChange={e => {
                const iso = localInputToIso(e.target.value)
                if (iso) onChange({ from: iso, to, preset: 'custom' })
              }}
              className="block mt-1 bg-gray-950 border border-gray-800 rounded px-2 py-1 text-sm font-mono"
            />
          </label>
          <label className="text-xs text-gray-400">
            To
            <input
              type="datetime-local"
              data-testid="to-input"
              value={isoToLocalInput(to)}
              onChange={e => {
                const iso = localInputToIso(e.target.value)
                if (iso) onChange({ from, to: iso, preset: 'custom' })
              }}
              className="block mt-1 bg-gray-950 border border-gray-800 rounded px-2 py-1 text-sm font-mono"
            />
          </label>
        </>
      )}
    </div>
  )
}
