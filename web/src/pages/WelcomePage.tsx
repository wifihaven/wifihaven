import React, { useState } from 'react'
import { useNavigate, useSearchParams, Link } from 'react-router-dom'
import { api } from '@/api/client'
import { useAuth } from '@/hooks/useAuth'
import { setHouseholdCookie } from '@/api/householdCookie'

// #2084: client-side password-policy hint. The server enforces AuthService.MinPasswordLength (12);
// mirror it here for a helpful message before the round-trip. The server remains the source of truth
// (it rejects a weak password with a 400 we surface).
export const MIN_PASSWORD_LENGTH = 12

/**
 * #2133 (multi-tenant P5-3, design §3.4): the PUBLIC invite-acceptance page (`/welcome?token=…`).
 *
 * A newly-approved household's admin lands here from the operator-sent invite link. The accept
 * payload is {token, password} ONLY (merged #2132 shape): the admin's email is bound server-side
 * from the originating `beta_requests.email` and the username defaults to `admin` — the operator
 * never chooses a username here (design §3.4 supersedes the older "choose username + password"
 * wording).
 *
 * On success we:
 *   1. set the long-lived `wh_household` cookie to the returned slug — BEFORE auto-login — so a later
 *      bare-username login on this browser composes to `slug/username` (design §4 form 3);
 *   2. auto-login as `slug/admin` with the just-set password (path 2, slug/username composite — the
 *      admin has an email but the SPA doesn't know it here, and slug/username is unambiguous), which
 *      lands on the new household's empty dashboard.
 */
export function WelcomePage() {
  const [params]   = useSearchParams()
  const token      = params.get('token') ?? ''
  const navigate   = useNavigate()
  const { login }  = useAuth()

  const [password, setPassword] = useState('')
  const [confirm,  setConfirm]  = useState('')
  const [error,    setError]    = useState('')
  const [loading,  setLoading]  = useState(false)

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
      const resp = await api.beta.accept(token, password)
      // Set the household cookie BEFORE auto-login (design §3.4) so the browser remembers the slug
      // for future bare-username logins even if the auto-login step hiccups.
      setHouseholdCookie(resp.slug)
      // Auto-login via the slug/username composite (path 2). The password we just set is the admin's.
      await login(`${resp.slug}/${resp.username}`, password)
      navigate('/dashboard')
    } catch {
      // The token is single-use and TTL'd: an invalid/expired/replayed invite fails here.
      setError('This invite link is invalid or has expired. Ask your operator for a new one.')
      setLoading(false)
    }
  }

  if (missingToken) {
    return (
      <div className="min-h-screen bg-brand-surface flex items-center justify-center px-4">
        <div className="w-full max-w-sm text-center">
          <h1 className="text-2xl font-bold text-brand-ink mb-3">Invite link required</h1>
          <p className="text-brand-text text-sm">
            This page needs a valid invite link. Check the link your operator sent you, or{' '}
            <Link to="/beta" className="text-brand-accent hover:underline">request beta access</Link>.
          </p>
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
          <h1 className="text-2xl font-bold text-brand-ink">
            Welcome to Wifi<span className="text-brand-accent">Haven</span>
          </h1>
          <p className="text-brand-text-muted text-sm mt-1">
            Set a password to finish creating your household admin account.
          </p>
        </div>

        <form onSubmit={handleSubmit} className="bg-white rounded-2xl border border-brand-border p-6 space-y-4">
          <div>
            <label htmlFor="welcome-password" className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2">
              Password
            </label>
            <input
              id="welcome-password"
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
            <label htmlFor="welcome-confirm" className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2">
              Confirm password
            </label>
            <input
              id="welcome-confirm"
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
            {loading ? 'Setting up…' : 'Create my household'}
          </button>
          <p className="text-center text-xs text-brand-text-muted">
            You'll sign in with the email you were invited on.
          </p>
        </form>
      </div>
    </div>
  )
}
