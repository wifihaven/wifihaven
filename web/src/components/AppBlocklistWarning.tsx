import type { AppBlocklistedHost } from '@/types/api'

// #1983 — warns that an app contains hosts that also belong to one or more
// category blocklists (e.g. gimkit.com on the "games" list while Gimkit is an
// allowed app — see #1980). Two variants:
//   - `badge`: compact "⚠ N blocklisted" pill with a native-title tooltip, for
//     the per-profile app selector / picker where space is tight.
//   - `detail`: an expanded list of each offending host + the blocklist(s) it's
//     on, for the Apps page row expansion.
// `nameById` maps a blocklist id (category slug) to its display name; absent
// entries fall back to the id, so the warning still renders before the
// blocklist metadata loads.

function blocklistLabel(id: string, nameById?: Map<string, string>): string {
  return nameById?.get(id) ?? id
}

function tooltip(blocklisted: AppBlocklistedHost[], nameById?: Map<string, string>): string {
  return blocklisted
    .map(b => `${b.host}: ${b.blocklists.map(id => blocklistLabel(id, nameById)).join(', ')}`)
    .join('\n')
}

export function AppBlocklistWarningBadge({ blocklisted, nameById, className = '' }: {
  blocklisted?: AppBlocklistedHost[]
  nameById?: Map<string, string>
  className?: string
}) {
  if (!blocklisted || blocklisted.length === 0) return null
  const n = blocklisted.length
  return (
    <span
      data-testid="app-blocklist-badge"
      title={tooltip(blocklisted, nameById)}
      className={`inline-flex items-center gap-1 bg-amber-500/15 text-amber-800 border border-amber-500/40 text-[11px] font-medium px-1.5 py-0.5 rounded-md ${className}`}
    >
      <span aria-hidden="true">⚠</span>
      {n} blocklisted host{n === 1 ? '' : 's'}
    </span>
  )
}

export function AppBlocklistWarningDetail({ blocklisted, nameById }: {
  blocklisted?: AppBlocklistedHost[]
  nameById?: Map<string, string>
}) {
  if (!blocklisted || blocklisted.length === 0) return null
  return (
    <div data-testid="app-blocklist-detail">
      <span className="block text-xs font-semibold text-amber-800 uppercase tracking-wider mb-2">
        ⚠ On a blocklist
      </span>
      <p className="text-xs text-brand-text-muted mb-2">
        These hosts are also in a category blocklist. Allowing this app overrides
        the blocklist for them (the app-allow wins).
      </p>
      <ul className="space-y-1">
        {blocklisted.map(b => (
          <li key={b.host} className="text-xs flex flex-wrap items-center gap-1.5">
            <span className="font-mono text-brand-ink">{b.host}</span>
            <span className="text-brand-text-muted">on</span>
            {b.blocklists.map(id => (
              <span
                key={id}
                className="inline-flex items-center bg-amber-500/15 text-amber-800 border border-amber-500/30 px-1.5 py-0.5 rounded text-[11px]"
              >
                {blocklistLabel(id, nameById)}
              </span>
            ))}
          </li>
        ))}
      </ul>
    </div>
  )
}
