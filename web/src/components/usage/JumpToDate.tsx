import { useState } from 'react'
import { isoToLocalInput, localInputToIso } from './usageHelpers'

// #862 — "Jump to date" picker. Re-anchors the right edge of the infinite-
// scroll window. Empty string = "now" (no anchor); the caller passes that
// through as `until = undefined` to the API. Subsumes the use case from #863.
export function JumpToDate({
  value,
  onChange,
}: {
  value: string | null  // ISO instant or null = "now"
  onChange: (next: string | null) => void
}) {
  const [draft, setDraft] = useState(value ? isoToLocalInput(value) : '')

  function apply(next: string) {
    setDraft(next)
    if (!next) onChange(null)
    else {
      const iso = localInputToIso(next)
      if (iso) onChange(iso)
    }
  }

  return (
    <label className="text-xs text-gray-400 flex flex-col" data-testid="jump-to-date">
      End at
      <div className="flex items-center gap-1 mt-1">
        <input
          type="datetime-local"
          value={draft}
          onChange={e => apply(e.target.value)}
          className="bg-gray-950 border border-gray-800 rounded px-2 py-1 text-sm"
          data-testid="jump-to-date-input"
        />
        {value && (
          <button
            type="button"
            data-testid="jump-to-date-now"
            onClick={() => { setDraft(''); onChange(null) }}
            className="text-xs text-gray-400 hover:text-gray-200 px-2 py-1 border border-gray-800 rounded"
            title="Re-anchor to now"
          >
            now
          </button>
        )}
      </div>
    </label>
  )
}
