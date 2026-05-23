import { useEffect, useRef, useState } from 'react'
import { Outlet, NavLink, useLocation, useNavigate } from 'react-router-dom'
import { useAuth } from '@/hooks/useAuth'

interface NavItem {
  to: string
  label: string
  icon: string
  adminOnly?: boolean
  // #846: optional inline submenu for the Advanced dropdown — parent link
  // stays clickable (lands on `to`); children render as indented sub-items.
  children?: NavItem[]
}

const primaryNav: NavItem[] = [
  { to: '/dashboard', label: 'Dashboard',   icon: '◈' },
  { to: '/devices',   label: 'Devices',     icon: '⬡' },
  { to: '/profiles',  label: 'Profiles',    icon: '◉' },
  { to: '/time',      label: 'Screen Time', icon: '◷' },
]

const settingsNav: NavItem[] = [
  {
    to: '/usage',
    label: 'Usage',
    icon: '⇅',
    children: [
      { to: '/usage',        label: 'Traffic Reports',   icon: '⇅' },
      { to: '/usage/events', label: 'Connection Events', icon: '≡' },
    ],
  },
  { to: '/users',   label: 'Users',   icon: '◐', adminOnly: true },
  { to: '/routers', label: 'Routers', icon: '⬢', adminOnly: true },
  { to: '/admin',   label: 'Settings', icon: '⚙', adminOnly: true },
]

export function Layout() {
  const { username, role, logout, isAdmin } = useAuth()
  const visibleSettings = settingsNav.filter(item => !item.adminOnly || isAdmin)
  const visibleDrawer = [...primaryNav, ...visibleSettings.flatMap(item =>
    item.children ? [item, ...item.children] : [item],
  )]
  const navigate = useNavigate()
  const location = useLocation()
  const [menuOpen, setMenuOpen] = useState(false)
  const [settingsOpen, setSettingsOpen] = useState(false)
  const settingsRef = useRef<HTMLDivElement | null>(null)

  useEffect(() => {
    if (!settingsOpen) return
    function onDocClick(e: MouseEvent) {
      if (settingsRef.current && !settingsRef.current.contains(e.target as Node)) {
        setSettingsOpen(false)
      }
    }
    document.addEventListener('mousedown', onDocClick)
    return () => document.removeEventListener('mousedown', onDocClick)
  }, [settingsOpen])

  const settingsActive = visibleSettings.some(item => location.pathname.startsWith(item.to))

  function handleLogout() {
    logout()
    navigate('/login')
  }

  return (
    <div className="min-h-screen bg-gray-950 text-gray-100 flex flex-col">
      {/* Top bar */}
      <header className="bg-gray-900 border-b border-gray-800 sticky top-0 z-50">
        <div className="max-w-7xl mx-auto px-4 h-14 flex items-center justify-between">
          <div className="flex items-center gap-3">
            {/* Mobile hamburger */}
            <button
              aria-label={menuOpen ? 'Close menu' : 'Open menu'}
              className="md:hidden p-2 rounded-lg text-gray-400 hover:text-white hover:bg-gray-800"
              onClick={() => setMenuOpen(!menuOpen)}
            >
              <svg className="w-5 h-5" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2}
                  d={menuOpen ? "M6 18L18 6M6 6l12 12" : "M4 6h16M4 12h16M4 18h16"} />
              </svg>
            </button>
            <span className="font-bold text-emerald-400 text-lg tracking-tight">
              Wifi<span className="text-white">Haven</span>
            </span>
          </div>

          {/* Desktop nav */}
          <nav className="hidden md:flex items-center gap-1">
            {primaryNav.map(item => (
              <NavLink
                key={item.to}
                to={item.to}
                className={({ isActive }) =>
                  `px-3 py-2 rounded-lg text-sm font-medium transition-colors ${
                    isActive
                      ? 'bg-gray-800 text-emerald-400'
                      : 'text-gray-400 hover:text-white hover:bg-gray-800'
                  }`
                }
              >
                {item.label}
              </NavLink>
            ))}
            {visibleSettings.length > 0 && (
              <div className="relative" ref={settingsRef}>
                <button
                  type="button"
                  aria-haspopup="menu"
                  aria-expanded={settingsOpen}
                  onClick={() => setSettingsOpen(o => !o)}
                  className={`px-3 py-2 rounded-lg text-sm font-medium transition-colors flex items-center gap-1 ${
                    settingsActive
                      ? 'bg-gray-800 text-emerald-400'
                      : 'text-gray-400 hover:text-white hover:bg-gray-800'
                  }`}
                >
                  <span>Advanced</span>
                  <span className="text-xs leading-none">▾</span>
                </button>
                {settingsOpen && (
                  <div
                    role="menu"
                    className="absolute right-0 mt-1 min-w-[10rem] bg-gray-900 border border-gray-800 rounded-lg shadow-lg py-1 z-50"
                  >
                    {visibleSettings.map(item => (
                      <div key={item.to}>
                        <NavLink
                          to={item.to}
                          end={!item.children}
                          role="menuitem"
                          onClick={() => setSettingsOpen(false)}
                          className={({ isActive }) =>
                            `flex items-center gap-2 px-3 py-2 text-sm transition-colors ${
                              isActive
                                ? 'text-emerald-400 bg-gray-800'
                                : 'text-gray-300 hover:text-white hover:bg-gray-800'
                            }`
                          }
                        >
                          <span className="text-base w-4 text-center">{item.icon}</span>
                          {item.label}
                        </NavLink>
                        {item.children?.map(child => (
                          <NavLink
                            key={child.to}
                            to={child.to}
                            end
                            role="menuitem"
                            onClick={() => setSettingsOpen(false)}
                            className={({ isActive }) =>
                              `flex items-center gap-2 pl-9 pr-3 py-1.5 text-xs transition-colors ${
                                isActive
                                  ? 'text-emerald-400 bg-gray-800'
                                  : 'text-gray-400 hover:text-white hover:bg-gray-800'
                              }`
                            }
                          >
                            {child.label}
                          </NavLink>
                        ))}
                      </div>
                    ))}
                  </div>
                )}
              </div>
            )}
          </nav>

          <div className="flex items-center gap-3">
            <NavLink
              to="/account"
              className={({ isActive }) =>
                `hidden sm:block text-xs font-mono px-2 py-1 rounded transition-colors ${
                  isActive
                    ? 'text-emerald-400'
                    : 'text-gray-500 hover:text-white'
                }`
              }
              title="Account settings"
            >
              {username} · {role ?? 'guest'}
            </NavLink>
            <button
              onClick={handleLogout}
              className="text-xs text-gray-500 hover:text-white transition-colors px-2 py-1 rounded"
            >
              Logout
            </button>
          </div>
        </div>

        {/* Mobile menu */}
        {menuOpen && (
          <div className="md:hidden border-t border-gray-800 bg-gray-900 px-4 py-2">
            {visibleDrawer.map(item => (
              <NavLink
                key={item.to}
                to={item.to}
                onClick={() => setMenuOpen(false)}
                className={({ isActive }) =>
                  `flex items-center gap-3 px-3 py-3 rounded-lg text-sm font-medium transition-colors ${
                    isActive
                      ? 'bg-gray-800 text-emerald-400'
                      : 'text-gray-400 hover:text-white'
                  }`
                }
              >
                <span className="text-base">{item.icon}</span>
                {item.label}
              </NavLink>
            ))}
          </div>
        )}
      </header>

      {/* Page content */}
      <main className="flex-1 max-w-7xl mx-auto w-full px-4 py-6">
        <Outlet />
      </main>

      {/* Mobile bottom nav */}
      <nav className="md:hidden fixed bottom-0 inset-x-0 bg-gray-900 border-t border-gray-800 z-50">
        <div className="grid" style={{ gridTemplateColumns: `repeat(${primaryNav.length}, minmax(0, 1fr))` }}>
          {primaryNav.map(item => (
            <NavLink
              key={item.to}
              to={item.to}
              className={({ isActive }) =>
                `flex flex-col items-center gap-1 py-3 text-xs transition-colors ${
                  isActive ? 'text-emerald-400' : 'text-gray-500 hover:text-gray-300'
                }`
              }
            >
              <span className="text-lg leading-none">{item.icon}</span>
              <span className="truncate">{item.label.split(' ')[0]}</span>
            </NavLink>
          ))}
        </div>
      </nav>

      {/* Bottom padding for mobile nav */}
      <div className="md:hidden h-16" />
    </div>
  )
}
