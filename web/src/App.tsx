import React from 'react'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { AuthProvider, useAuth } from '@/hooks/useAuth'
import { WsProvider, WsStaleInvalidator } from '@/hooks/useWs'
import { AlertsNotifier } from '@/hooks/useNotifyOnNewAlerts'
import { Layout } from '@/components/layout/Layout'
import { LoginPage } from '@/pages/LoginPage'
import { DashboardPage } from '@/pages/DashboardPage'
import { DevicesPage } from '@/pages/DevicesPage'
import { DeviceTimelinePage } from '@/pages/DeviceTimelinePage'
import { ProfilesPage } from '@/pages/ProfilesPage'
import { ProfileTimelinePage } from '@/pages/ProfileTimelinePage'
import { LogsPage } from '@/pages/LogsPage'
import { TrafficUsagePage } from '@/pages/TrafficUsagePage'
import { AccountPage } from '@/pages/AccountPage'
import { UsersPage } from '@/pages/UsersPage'
import { RoutersPage } from '@/pages/RoutersPage'
import { AdminPage } from '@/pages/AdminPage'
import { BlockedPage } from '@/pages/BlockedPage'
import { AppsPage } from '@/pages/AppsPage'
import { BlocklistsPage } from '@/pages/BlocklistsPage'
import { SchedulesPage } from '@/pages/SchedulesPage'
import { BillingPage } from '@/pages/BillingPage'
import { BetaRequestPage } from '@/pages/BetaRequestPage'
import { WelcomePage } from '@/pages/WelcomePage'
import { BetaRequestsPage } from '@/pages/BetaRequestsPage'
import { useMe } from '@/api/queries'

function RequireAuth({ children }: { children: React.ReactNode }) {
  const { isAuthenticated } = useAuth()
  return isAuthenticated ? <>{children}</> : <Navigate to="/login" replace />
}

// #586: redirect to /account when the server-enforced must_change_password flag
// is set so the operator cannot reach any other route until they rotate.
function RequirePwChanged({ children }: { children: React.ReactNode }) {
  const { mustChangePassword } = useAuth()
  return mustChangePassword ? <Navigate to="/account" replace /> : <>{children}</>
}

function RequireAdmin({ children }: { children: React.ReactNode }) {
  const { isAdmin } = useAuth()
  return isAdmin ? <>{children}</> : <Navigate to="/dashboard" replace />
}

// #2133 (design §3.2): the operator beta-request queue is gated on the `isOperator`
// API signal (household-1 admin), not a hardcoded client check. While /me is in
// flight we render nothing rather than flash-redirect; a non-operator lands back on
// the dashboard (and the API independently 403s the queue endpoints).
function RequireOperator({ children }: { children: React.ReactNode }) {
  const { data, isPending } = useMe()
  if (isPending) return null
  return data?.isOperator ? <>{children}</> : <Navigate to="/dashboard" replace />
}

function AppRoutes() {
  return (
    <Routes>
      <Route path="/login"   element={<LoginPage />} />
      <Route path="/blocked" element={<BlockedPage />} />
      {/* #2133: unauthenticated beta signup + invite acceptance (design §3). */}
      <Route path="/beta"    element={<BetaRequestPage />} />
      <Route path="/welcome" element={<WelcomePage />} />
      {/*
        /account is outside RequirePwChanged so the operator can reach the
        change-password form even when the server-side flag is set (#586).
        All other protected routes redirect to /account until the flag clears.
      */}
      <Route path="/" element={<RequireAuth><WsProvider><AlertsNotifier /><WsStaleInvalidator /><Layout /></WsProvider></RequireAuth>}>
        <Route path="account" element={<AccountPage />} />
        <Route index element={<RequirePwChanged><Navigate to="/dashboard" replace /></RequirePwChanged>} />
        <Route path="dashboard" element={<RequirePwChanged><DashboardPage /></RequirePwChanged>} />
        <Route path="devices"   element={<RequirePwChanged><DevicesPage /></RequirePwChanged>} />
        <Route path="devices/:mac/timeline" element={<RequirePwChanged><DeviceTimelinePage /></RequirePwChanged>} />
        <Route path="profiles"  element={<RequirePwChanged><ProfilesPage /></RequirePwChanged>} />
        <Route path="profiles/:profileId/timeline" element={<RequirePwChanged><ProfileTimelinePage /></RequirePwChanged>} />
        <Route path="logs"      element={<Navigate to="/usage/events" replace />} />
        <Route path="usage"          element={<RequirePwChanged><TrafficUsagePage /></RequirePwChanged>} />
        <Route path="usage/events"   element={<RequirePwChanged><LogsPage /></RequirePwChanged>} />
        <Route path="apps"        element={<RequirePwChanged><RequireAdmin><AppsPage /></RequireAdmin></RequirePwChanged>} />
        <Route path="blocklists"  element={<RequirePwChanged><RequireAdmin><BlocklistsPage /></RequireAdmin></RequirePwChanged>} />
        <Route path="schedules"   element={<RequirePwChanged><RequireAdmin><SchedulesPage /></RequireAdmin></RequirePwChanged>} />
        <Route path="users"     element={<RequirePwChanged><RequireAdmin><UsersPage /></RequireAdmin></RequirePwChanged>} />
        <Route path="routers"   element={<RequirePwChanged><RequireAdmin><RoutersPage /></RequireAdmin></RequirePwChanged>} />
        <Route path="billing"   element={<RequirePwChanged><RequireAdmin><BillingPage /></RequireAdmin></RequirePwChanged>} />
        <Route path="admin"     element={<RequirePwChanged><RequireAdmin><AdminPage /></RequireAdmin></RequirePwChanged>} />
        {/* #2133: operator-only beta-request review queue (gated on the isOperator API signal). */}
        <Route path="beta-requests" element={<RequirePwChanged><RequireOperator><BetaRequestsPage /></RequireOperator></RequirePwChanged>} />
      </Route>
    </Routes>
  )
}

export default function App() {
  return (
    <BrowserRouter>
      <AuthProvider>
        <AppRoutes />
      </AuthProvider>
    </BrowserRouter>
  )
}
