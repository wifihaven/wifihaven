import { useMemo, useState } from 'react'

// Curated US-centric short list. The "more…" expander reveals every IANA zone
// the runtime knows about (Intl.supportedValuesOf), filtered by a search box.
// (#334 design choice: short list covers the home-network common case while
// still letting an operator pick any zone.)
const COMMON_ZONES = [
  'America/Los_Angeles',
  'America/Denver',
  'America/Chicago',
  'America/New_York',
  'America/Anchorage',
  'Pacific/Honolulu',
  'UTC',
] as const

interface Props {
  value: string
  onChange: (tz: string) => void
  testId?: string
}

function allZones(): string[] {
  // Intl.supportedValuesOf is in all modern browsers. Fall back to common list
  // if unavailable (very old browser); the operator can still pick from common.
  type IntlWithSupported = typeof Intl & { supportedValuesOf?: (key: string) => string[] }
  const I = Intl as IntlWithSupported
  if (typeof I.supportedValuesOf === 'function') {
    return I.supportedValuesOf('timeZone')
  }
  return [...COMMON_ZONES]
}

export function TimezonePicker({ value, onChange, testId }: Props) {
  const [expanded, setExpanded] = useState(false)
  const [filter, setFilter] = useState('')

  const all = useMemo(allZones, [])
  const filtered = useMemo(() => {
    const f = filter.trim().toLowerCase()
    if (!f) return all
    return all.filter(z => z.toLowerCase().includes(f))
  }, [all, filter])

  // If the current value isn't in the short list, force the expander open so
  // the user can see what they have selected.
  const valueInShortList = (COMMON_ZONES as readonly string[]).includes(value)
  const showExpanded = expanded || !valueInShortList

  return (
    <div className="space-y-1" data-testid={testId}>
      {!showExpanded ? (
        <select
          value={value}
          onChange={e => {
            if (e.target.value === '__more__') setExpanded(true)
            else onChange(e.target.value)
          }}
          data-testid={testId ? `${testId}-select` : undefined}
          className="bg-gray-900 border border-gray-700 rounded-lg px-3 py-2 text-white text-sm w-full"
        >
          {COMMON_ZONES.map(z => (
            <option key={z} value={z}>{z}</option>
          ))}
          <option value="__more__">More…</option>
        </select>
      ) : (
        <>
          <input
            type="text"
            value={filter}
            onChange={e => setFilter(e.target.value)}
            placeholder="Filter (e.g. London, Tokyo)"
            data-testid={testId ? `${testId}-filter` : undefined}
            className="bg-gray-900 border border-gray-700 rounded-lg px-3 py-2 text-white text-sm w-full"
          />
          <select
            value={value}
            onChange={e => onChange(e.target.value)}
            size={6}
            data-testid={testId ? `${testId}-select` : undefined}
            className="bg-gray-900 border border-gray-700 rounded-lg px-3 py-2 text-white text-sm w-full"
          >
            {/* (#571) When the filter narrows to one option and that option
                != value, the browser pre-highlights the lone <option> as the
                de-facto selection — clicking it then fires no `change`
                event and the controlled value never updates. Including a
                hidden option matching the current value gives the select a
                real "current selection" so clicking the filtered option is
                an actual change. */}
            {!filtered.includes(value) && (
              <option value={value} hidden>{value}</option>
            )}
            {filtered.map(z => (
              <option key={z} value={z}>{z}</option>
            ))}
          </select>
        </>
      )}
    </div>
  )
}

/** Best-effort: the browser's current IANA tz (e.g. "America/Los_Angeles"). */
export function browserTimezone(): string {
  try {
    return Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'
  } catch {
    return 'UTC'
  }
}
