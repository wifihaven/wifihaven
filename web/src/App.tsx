import React from 'react'
import { BrowserRouter, Routes, Route, Navigate } from 'react-router-dom'
import { AuthProvider, useAuth } from '@/hooks/useAuth'
import { Layout } from '@/components/layout/Layout'
import { LoginPage } from '@/pages/LoginPage'
import { DashboardPage } from '@/pages/DashboardPage'
import { DevicesPage } from '@/pages/DevicesPage'
import { ProfilesPage } from '@/pages/ProfilesPage'
import { TimePage } from '@/pages/TimePage'
import { LogsPage } from '@/pages/LogsPage'
import { AccountPage } from '@/pages/AccountPage'
import { UsersPage } from '@/pages/UsersPage'

function RequireAuth({ children }: { children: React.ReactNode }) {
  const { isAuthenticated } = useAuth()
  return isAuthenticated ? <>{children}</> : <Navigate to="/login" replace />
}

function RequireAdmin({ children }: { children: React.ReactNode }) {
  const { isAdmin } = useAuth()
  return isAdmin ? <>{children}</> : <Navigate to="/dashboard" replace />
}

function AppRoutes() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/" element={
        <RequireAuth>
          <Layout />
        </RequireAuth>
      }>
        <Route index element={<Navigate to="/dashboard" replace />} />
        <Route path="dashboard" element={<DashboardPage />} />
        <Route path="devices"   element={<DevicesPage />} />
        <Route path="profiles"  element={<ProfilesPage />} />
        <Route path="time"      element={<TimePage />} />
        <Route path="logs"      element={<LogsPage />} />
        <Route path="account"   element={<AccountPage />} />
        <Route path="users"     element={<RequireAdmin><UsersPage /></RequireAdmin>} />
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
