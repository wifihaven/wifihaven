import { useEffect, useMemo, useState } from 'react'
import { api } from '@/api/client'
import { useDevices } from '@/api/queries'
import type { RecentApex, RecentApexesResponse } from '@/types/api'

// #766: pick recently-visited apexes (PSL-collapsed FQDNs) for a device and
// append them to an app's host list. Operator can also expand the per-apex
// subdomain list and pick at the subdomain granularity.
export function RecentApexPicker({
  existingHosts,
  onClose,
  onAdd,
}: {
  existingHosts: string[]
  onClose: () => void
  onAdd: (hosts: string[]) => void
}) {
  const { data: devices = [] } = useDevices()
  const sortedDevices = useMemo(() => {
    const copy = [...devices]
    copy.sort((a, b) => {
      const at = a.lastSeenAt ? Date.parse(a.lastSeenAt) : 0
      const bt = b.lastSeenAt ? Date.parse(b.lastSeenAt) : 0
      return bt - at
    })
    return copy
  }, [devices])
  const [mac, setMac] = useState<string | null>(null)
  useEffect(() => {
    if (mac == null && sortedDevices.length > 0) setMac(sortedDevices[0].mac)
  }, [sortedDevices, mac])

  const [data, setData] = useState<RecentApexesResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [expanded, setExpanded] = useState<Set<string>>(new Set())
  const [selected, setSelected] = useState<Set<string>>(new Set())

  useEffect(() => {
    if (!mac) return
    setLoading(true)
    setError(null)
    setData(null)
    setSelected(new Set())
    setExpanded(new Set())
    api.apps.recentApexes(mac, { windowDays: 7, limit: 50 })
      .then(setData)
      .catch(e => setError(e instanceof Error ? e.message : 'Failed to load recent activity'))
      .finally(() => setLoading(false))
  }, [mac])

  const existing = useMemo(() => new Set(existingHosts), [existingHosts])

  function toggle(host: string) {
    if (existing.has(host)) return
    setSelected(prev => {
      const next = new Set(prev)
      if (next.has(host)) next.delete(host)
      else next.add(host)
      return next
    })
  }

  function toggleExpand(apex: string) {
    setExpanded(prev => {
      const next = new Set(prev)
      if (next.has(apex)) next.delete(apex)
      else next.add(apex)
      return next
    })
  }

  function addSelected() {
    if (selected.size === 0) return
    onAdd([...selected])
  }

  const totalSelected = selected.size

  return (
    <div className="fixed inset-0 bg-black/70 flex items-end sm:items-center justify-center z-[60] p-4 overflow-y-auto" onClick={onClose}>
      <div
        className="bg-gray-900 rounded-2xl border border-gray-700 w-full max-w-2xl my-8 p-6 space-y-4 max-h-[90vh] flex flex-col"
        onClick={e => e.stopPropagation()}
      >
        <div className="flex items-start justify-between gap-4">
          <div>
            <h3 className="text-lg font-bold text-white">Pick from recent activity</h3>
            <p className="text-xs text-gray-500 mt-1">
              Top apexes a device hit in the last 7 days. Tick the ones to add to this app.
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="text-gray-500 hover:text-white text-xl leading-none"
            aria-label="Close picker"
          >×</button>
        </div>

        <label className="block">
          <span className="block text-xs font-semibold text-gray-500 uppercase tracking-wider mb-2">
            Device
          </span>
          <select
            value={mac ?? ''}
            onChange={e => setMac(e.target.value || null)}
            className="w-full bg-gray-950 border border-gray-700 rounded-xl px-4 py-3 text-white focus:outline-none focus:border-emerald-500"
            data-testid="picker-device-select"
          >
            {sortedDevices.length === 0 && <option value="">No devices yet</option>}
            {sortedDevices.map(d => (
              <option key={d.mac} value={d.mac}>
                {d.name}
              </option>
            ))}
          </select>
        </label>

        {error && (
          <div className="bg-red-500/10 border border-red-500/30 text-red-300 text-sm rounded-xl px-4 py-2">
            {error}
          </div>
        )}

        <div className="flex-1 min-h-0 overflow-y-auto border border-gray-800 rounded-xl">
          {loading && <p className="p-4 text-sm text-gray-500">Loading recent activity…</p>}
          {!loading && data && data.items.length === 0 && (
            <p className="p-4 text-sm text-gray-500">
              No traffic for this device in the last 7 days.
            </p>
          )}
          {!loading && data && data.items.map(item => (
            <ApexRow
              key={item.apex}
              item={item}
              selected={selected}
              existing={existing}
              expanded={expanded.has(item.apex)}
              onToggle={toggle}
              onToggleExpand={() => toggleExpand(item.apex)}
            />
          ))}
        </div>

        <div className="flex gap-3 pt-2">
          <button
            type="button"
            onClick={onClose}
            className="flex-1 py-3 rounded-xl bg-gray-800 text-gray-300 font-medium"
          >Cancel</button>
          <button
            type="button"
            onClick={addSelected}
            disabled={totalSelected === 0}
            data-testid="picker-add-button"
            className="flex-1 py-3 rounded-xl bg-emerald-500 text-black font-semibold disabled:opacity-50"
          >
            {totalSelected === 0 ? 'Pick hosts' : `Add ${totalSelected} host${totalSelected === 1 ? '' : 's'}`}
          </button>
        </div>
      </div>
    </div>
  )
}

function ApexRow({
  item,
  selected,
  existing,
  expanded,
  onToggle,
  onToggleExpand,
}: {
  item: RecentApex
  selected: Set<string>
  existing: Set<string>
  expanded: boolean
  onToggle: (h: string) => void
  onToggleExpand: () => void
}) {
  const apexChecked = selected.has(item.apex)
  const apexExisting = existing.has(item.apex)
  return (
    <div className="border-b border-gray-800 last:border-0">
      <div className="flex items-center gap-3 px-4 py-3">
        <input
          type="checkbox"
          checked={apexChecked || apexExisting}
          disabled={apexExisting}
          onChange={() => onToggle(item.apex)}
          aria-label={`Select ${item.apex}`}
          className="h-4 w-4 accent-emerald-500"
        />
        <div className="flex-1 min-w-0">
          <p className="font-mono text-sm text-white truncate">{item.apex}</p>
          <p className="text-xs text-gray-500 mt-0.5">
            {formatBytes(item.bytes)} · {item.hits} hit{item.hits === 1 ? '' : 's'}
            {apexExisting && <span className="ml-2 text-emerald-400">already in app</span>}
          </p>
        </div>
        {item.subdomains.length > 0 && (
          <button
            type="button"
            onClick={onToggleExpand}
            className="text-xs text-gray-400 hover:text-emerald-400"
          >
            {expanded ? 'Hide' : `${item.subdomains.length} subdomain${item.subdomains.length === 1 ? '' : 's'}`}
          </button>
        )}
      </div>
      {expanded && item.subdomains.length > 0 && (
        <div className="pl-12 pr-4 pb-3 space-y-1.5">
          {item.subdomains.map(sub => {
            const subExisting = existing.has(sub)
            const subChecked = selected.has(sub)
            return (
              <label key={sub} className="flex items-center gap-3 text-sm">
                <input
                  type="checkbox"
                  checked={subChecked || subExisting}
                  disabled={subExisting}
                  onChange={() => onToggle(sub)}
                  className="h-3.5 w-3.5 accent-emerald-500"
                />
                <span className={`font-mono text-xs ${subExisting ? 'text-gray-500' : 'text-gray-300'}`}>
                  {sub}
                </span>
              </label>
            )
          })}
        </div>
      )}
    </div>
  )
}

function formatBytes(b: number): string {
  if (b < 1024) return `${b} B`
  if (b < 1024 * 1024) return `${(b / 1024).toFixed(1)} KB`
  if (b < 1024 * 1024 * 1024) return `${(b / 1024 / 1024).toFixed(1)} MB`
  return `${(b / 1024 / 1024 / 1024).toFixed(2)} GB`
}
