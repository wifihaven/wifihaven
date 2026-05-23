import { useEffect, useRef, useState } from 'react'

interface Option {
  value: string
  label: string
}

interface Props {
  // testId is also used to derive child testIds (option-X, all, none, clear).
  testId: string
  title: string             // popover heading (e.g. "Filter device")
  options: Option[]
  selected: string[]        // empty = "all" (no filter)
  onChange: (next: string[]) => void
  searchable?: boolean      // show a typeahead search box
}

// #865 — column-header multi-select popover. Renders as a funnel icon next
// to GroupableHeader; clicking it opens a checkbox list with All / None /
// Clear affordances. "Selected = []" is treated as the no-filter sentinel
// (matches the API: empty list = no IN clause).
export function HeaderFilter({ testId, title, options, selected, onChange, searchable }: Props) {
  const [open, setOpen] = useState(false)
  const [search, setSearch] = useState('')
  const ref = useRef<HTMLDivElement>(null)
  const active = selected.length > 0

  useEffect(() => {
    if (!open) return
    function onDoc(e: MouseEvent) {
      if (ref.current && !ref.current.contains(e.target as Node)) setOpen(false)
    }
    document.addEventListener('mousedown', onDoc)
    return () => document.removeEventListener('mousedown', onDoc)
  }, [open])

  const filtered = search
    ? options.filter(o => o.label.toLowerCase().includes(search.toLowerCase()))
    : options

  function toggle(value: string) {
    if (selected.includes(value)) onChange(selected.filter(v => v !== value))
    else onChange([...selected, value])
  }

  return (
    <div ref={ref} className="relative inline-block">
      <button
        type="button"
        data-testid={testId}
        title={active ? `Filtered to ${selected.length}` : 'Filter…'}
        onClick={() => setOpen(o => !o)}
        className={`inline-flex items-center text-[11px] leading-none ${
          active ? 'text-emerald-400 hover:text-emerald-300' : 'text-gray-500 hover:text-gray-300'
        }`}
      >
        {/* Funnel glyph. Filled triangle when active, hollow when not. */}
        <span className="font-mono">{active ? '▼' : '▽'}</span>
        {active && (
          <span data-testid={`${testId}-count`} className="ml-1 text-[10px]">
            {selected.length}
          </span>
        )}
      </button>
      {open && (
        <div
          data-testid={`${testId}-popover`}
          className="absolute left-0 z-20 mt-1 w-64 max-h-80 overflow-auto rounded border border-gray-800 bg-gray-950 p-2 shadow-lg"
        >
          <div className="flex items-center justify-between mb-1">
            <span className="text-[11px] uppercase tracking-wide text-gray-500">{title}</span>
            <div className="flex gap-2 text-[11px]">
              <button
                type="button"
                data-testid={`${testId}-all`}
                onClick={() => onChange(options.map(o => o.value))}
                className="text-gray-400 hover:text-emerald-300"
              >
                all
              </button>
              <button
                type="button"
                data-testid={`${testId}-none`}
                onClick={() => onChange([])}
                className="text-gray-400 hover:text-emerald-300"
              >
                clear
              </button>
            </div>
          </div>
          {searchable && (
            <input
              data-testid={`${testId}-search`}
              type="text"
              value={search}
              onChange={e => setSearch(e.target.value)}
              placeholder="Search…"
              className="w-full mb-2 bg-gray-900 border border-gray-800 rounded px-2 py-1 text-xs text-gray-200"
            />
          )}
          {filtered.length === 0 && (
            <div className="text-xs text-gray-500 py-2 px-1">No matches.</div>
          )}
          <ul className="space-y-1">
            {filtered.map(opt => (
              <li key={opt.value}>
                <label className="flex items-center gap-2 text-xs text-gray-300 hover:bg-gray-900/60 px-1 py-0.5 rounded cursor-pointer">
                  <input
                    type="checkbox"
                    data-testid={`${testId}-opt-${opt.value}`}
                    checked={selected.includes(opt.value)}
                    onChange={() => toggle(opt.value)}
                    className="accent-emerald-500"
                  />
                  <span className="truncate">{opt.label}</span>
                </label>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}
