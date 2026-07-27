import React, { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, isCurrentPasswordIncorrect } from '@/api/client'
import { useAuth } from '@/hooks/useAuth'

export function AccountPage() {
  const { username, isAdmin, mustChangePassword, logout } = useAuth()
  const navigate = useNavigate()
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword]         = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [error,   setError]   = useState('')
  const [loading, setLoading] = useState(false)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError('')

    if (newPassword !== confirmPassword) {
      setError('New password and confirmation do not match')
      return
    }
    if (newPassword.length < 8) {
      setError('New password must be at least 8 characters')
      return
    }
    if (newPassword === currentPassword) {
      setError('New password must differ from the current password')
      return
    }

    setLoading(true)
    try {
      await api.auth.changePassword(currentPassword, newPassword)
      // #2492: the rotation bumps token_version server-side (#2080), so the JWT this session
      // is holding is revoked the instant the change lands. Navigating into the app (the old
      // `navigate('/dashboard')`) therefore always ended in a bare 401 bounce to /login with
      // no explanation — the "it never completes" half of the first-login report. Sign out
      // cleanly instead and hand the user to /login with a notice. `logout` also clears the
      // persisted must-change flag, so the forced-change gate is released.
      logout()
      navigate('/login', { state: { passwordChanged: true } })
    } catch (err) {
      // #2492: the transport types this case (the server's "Current password incorrect" 401 on
      // this route only), so the message no longer depends on string-matching '401'/'unauth' —
      // which also caught session-expiry 401s and told the user their password was wrong.
      if (isCurrentPasswordIncorrect(err)) {
        setError('Current password is incorrect')
      } else {
        setError(err instanceof Error ? err.message : 'Failed to change password')
      }
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="space-y-6 max-w-xl">
      <h1 className="text-xl font-bold text-brand-ink">Account</h1>

      {/* #586: banner shown when the server-enforced must_change_password flag is set */}
      {mustChangePassword && (
        <div className="bg-amber-500/10 border border-amber-500/30 rounded-xl px-4 py-3 text-amber-700 text-sm">
          <strong>Password change required.</strong> The default password must be changed before you can use the rest of the application.
        </div>
      )}

      <section className="bg-white rounded-2xl border border-brand-border p-5">
        <h2 className="text-sm font-semibold text-brand-text uppercase tracking-wider mb-4">
          Profile
        </h2>
        <dl className="text-sm space-y-2">
          <div className="flex justify-between">
            <dt className="text-brand-text-muted">Username</dt>
            <dd className="text-brand-ink font-mono">{username}</dd>
          </div>
          <div className="flex justify-between">
            <dt className="text-brand-text-muted">Role</dt>
            <dd className="text-brand-ink font-mono">{isAdmin ? 'admin' : 'readonly'}</dd>
          </div>
        </dl>
      </section>

      <section className="bg-white rounded-2xl border border-brand-border p-5">
        <h2 className="text-sm font-semibold text-brand-text uppercase tracking-wider mb-4">
          Change password
        </h2>
        <form onSubmit={handleSubmit} className="space-y-4">
          <PasswordField
            label="Current password"
            value={currentPassword}
            onChange={setCurrentPassword}
            autoComplete="current-password"
            autoFocus
          />
          <PasswordField
            label="New password"
            value={newPassword}
            onChange={setNewPassword}
            autoComplete="new-password"
          />
          <PasswordField
            label="Confirm new password"
            value={confirmPassword}
            onChange={setConfirmPassword}
            autoComplete="new-password"
          />

          {error && (
            <div className="bg-red-500/10 border border-red-500/20 rounded-lg px-4 py-3 text-red-700 text-sm">
              {error}
            </div>
          )}

          <button
            type="submit"
            disabled={loading}
            className="bg-brand-accent hover:bg-brand-accent-dark disabled:opacity-50 text-white font-semibold px-4 py-2 rounded-xl transition-colors"
          >
            {loading ? 'Updating…' : 'Update password'}
          </button>
        </form>
      </section>
    </div>
  )
}

function PasswordField({
  label, value, onChange, autoComplete, autoFocus,
}: {
  label: string
  value: string
  onChange: (v: string) => void
  autoComplete?: string
  autoFocus?: boolean
}) {
  return (
    <div>
      <label className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2">
        {label}
      </label>
      <input
        type="password"
        value={value}
        onChange={e => onChange(e.target.value)}
        autoComplete={autoComplete}
        autoFocus={autoFocus}
        required
        className="w-full bg-brand-surface border border-brand-border-strong rounded-xl px-4 py-3 text-brand-ink placeholder-brand-text-muted focus:outline-none focus:border-brand-accent transition-colors"
      />
    </div>
  )
}
