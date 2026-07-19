import React, { useState } from 'react'
import { useNavigate, useSearchParams, Link } from 'react-router-dom'
import { api } from '@/api/client'
import { MIN_PASSWORD_LENGTH } from '@/pages/WelcomePage'

/**
 * #2308: the PUBLIC reset-password page (`/reset-password?token=…`), reached from the emailed reset
 * link. Mirrors WelcomePage's token flow: read the single-use token from the query string, take a
 * new password (+ confirm), and POST to `POST /api/auth/reset-password`. On success the server sets
 * the new password and bumps token_version (invalidating any old sessions); we show a success state
 * that links to sign-in. A bad/expired/used token or a too-weak password fails with a 400 we
 * surface. The password policy hint reuses WelcomePage's MIN_PASSWORD_LENGTH (server is the source
 * of truth).
 */
export function ResetPasswordPage() {
  const [params] = useSearchParams()
  const token    = params.get('token') ?? ''
  const navigate = useNavigate()

  const [password, setPassword] = useState('')
  const [confirm,  setConfirm]  = useState('')
  const [error,    setError]    = useState('')
  const [loading,  setLoading]  = useState(false)
  const [done,     setDone]     = useState(false)

  const missingToken = token.trim().length === 0

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    if (password.length < MIN_PASSWORD_LENGTH) {
      setError(`Password must be at least ${MIN_PASSWORD_LENGTH} characters.`)
      return
    }
    if (password !== confirm) {
      setError('Passwords do not match.')
      return
    }
    setLoading(true)
    try {
      await api.auth.resetPassword(token, password)
      setDone(true)
    } catch {
      // The token is single-use and TTL'd: an invalid/expired/already-used link (or a server-side
      // weak-password rejection) fails here. Keep the copy generic — the link reveals nothing.
      setError('This reset link is invalid or has expired. Request a new one from the sign-in page.')
      setLoading(false)
    }
  }

  if (missingToken) {
    return (
      <div className="min-h-screen bg-brand-surface flex items-center justify-center px-4">
        <div className="w-full max-w-sm text-center">
          <h1 className="text-2xl font-bold text-brand-ink mb-3">Reset link required</h1>
          <p className="text-brand-text text-sm">
            This page needs a valid reset link. Check the link we emailed you, or{' '}
            <Link to="/forgot-password" className="text-brand-accent hover:underline">request a new one</Link>.
          </p>
        </div>
      </div>
    )
  }

  if (done) {
    return (
      <div className="min-h-screen bg-brand-surface flex items-center justify-center px-4">
        <div className="w-full max-w-sm text-center">
          <div className="inline-flex items-center justify-center w-16 h-16 rounded-2xl bg-brand-accent/10 border border-brand-accent/20 mb-4">
            <img src="/brand/favicon.svg" alt="" className="w-9 h-9" />
          </div>
          <h1 className="text-2xl font-bold text-brand-ink mb-3">Password updated</h1>
          <p className="text-brand-text text-sm">
            Your password has been reset. Any other devices signed in on the old password will need to
            sign in again.
          </p>
          <button
            onClick={() => navigate('/login')}
            className="inline-block mt-6 bg-brand-accent hover:bg-brand-accent-dark text-white font-semibold px-6 py-3 rounded-xl transition-colors"
          >
            Sign in
          </button>
        </div>
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-brand-surface flex items-center justify-center px-4">
      <div className="w-full max-w-sm">
        <div className="text-center mb-8">
          <div className="inline-flex items-center justify-center w-16 h-16 rounded-2xl bg-brand-accent/10 border border-brand-accent/20 mb-4">
            <img src="/brand/favicon.svg" alt="" className="w-9 h-9" />
          </div>
          <h1 className="text-2xl font-bold text-brand-ink">Choose a new password</h1>
          <p className="text-brand-text-muted text-sm mt-1">
            Set a new password for your WifiHaven account.
          </p>
        </div>

        <form onSubmit={handleSubmit} className="bg-white rounded-2xl border border-brand-border p-6 space-y-4">
          <div>
            <label htmlFor="reset-password" className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2">
              New password
            </label>
            <input
              id="reset-password"
              type="password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              className="w-full bg-brand-surface border border-brand-border-strong rounded-xl px-4 py-3 text-brand-ink placeholder-brand-text-muted focus:outline-none focus:border-brand-accent transition-colors"
              placeholder="••••••••••••"
              autoFocus
              required
            />
            <p className="text-xs text-brand-text-muted mt-1">
              At least {MIN_PASSWORD_LENGTH} characters.
            </p>
          </div>
          <div>
            <label htmlFor="reset-confirm" className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2">
              Confirm new password
            </label>
            <input
              id="reset-confirm"
              type="password"
              value={confirm}
              onChange={e => setConfirm(e.target.value)}
              className="w-full bg-brand-surface border border-brand-border-strong rounded-xl px-4 py-3 text-brand-ink placeholder-brand-text-muted focus:outline-none focus:border-brand-accent transition-colors"
              placeholder="••••••••••••"
              required
            />
          </div>

          {error && (
            <div className="bg-red-500/10 border border-red-500/20 rounded-lg px-4 py-3 text-red-700 text-sm">
              {error}
            </div>
          )}

          <button
            type="submit"
            disabled={loading}
            className="w-full bg-brand-accent hover:bg-brand-accent-dark disabled:opacity-50 text-white font-semibold py-3 rounded-xl transition-colors"
          >
            {loading ? 'Updating…' : 'Reset password'}
          </button>
          <p className="text-center text-xs text-brand-text-muted">
            <Link to="/login" className="text-brand-accent hover:underline">Back to sign in</Link>
          </p>
        </form>
      </div>
    </div>
  )
}
