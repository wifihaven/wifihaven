import { NavLink } from 'react-router-dom'

// #846: Traffic Reports and Connection Events live under /usage as sibling
// tabs — the two surfaces over the same underlying agent data.
export function UsageTabs() {
  const base =
    'px-3 py-1.5 text-sm font-medium rounded transition-colors'
  return (
    <nav className="flex gap-1 border-b border-gray-800 pb-2" aria-label="usage-tabs">
      <NavLink
        to="/usage"
        end
        data-testid="usage-tab-traffic"
        className={({ isActive }) =>
          `${base} ${
            isActive
              ? 'bg-gray-800 text-emerald-400'
              : 'text-gray-400 hover:text-white hover:bg-gray-800'
          }`
        }
      >
        Traffic Reports
      </NavLink>
      <NavLink
        to="/usage/events"
        data-testid="usage-tab-events"
        className={({ isActive }) =>
          `${base} ${
            isActive
              ? 'bg-gray-800 text-emerald-400'
              : 'text-gray-400 hover:text-white hover:bg-gray-800'
          }`
        }
      >
        Connection Events
      </NavLink>
    </nav>
  )
}
