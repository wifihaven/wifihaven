import { isoToLocalInput, localInputToIso } from './usageHelpers'

interface Props {
  to: string
  onChange: (next: string) => void
}

// #846 follow-up: no presets — bucket selection decides the window width;
// operator picks only the right edge ("end at"). Default = now (set by the
// parent on mount). Infinite scroll for older history is tracked in #862.
export function EndAtPicker({ to, onChange }: Props) {
  return (
    <div className="flex flex-wrap items-end gap-3" data-testid="end-at-picker">
      <label className="text-xs text-gray-400">
        End at
        <input
          type="datetime-local"
          data-testid="end-at-input"
          value={isoToLocalInput(to)}
          onChange={e => {
            const iso = localInputToIso(e.target.value)
            if (iso) onChange(iso)
          }}
          className="block mt-1 bg-gray-950 border border-gray-800 rounded px-2 py-1 text-sm font-mono"
        />
      </label>
      <button
        type="button"
        data-testid="end-at-now"
        onClick={() => onChange(new Date().toISOString())}
        className="px-2.5 py-1.5 rounded text-xs font-medium bg-gray-800 text-gray-300 hover:bg-gray-700"
      >
        Now
      </button>
    </div>
  )
}
