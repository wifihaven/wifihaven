import React, { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useAuth } from '@/hooks/useAuth'
import { getHouseholdCookie } from '@/api/householdCookie'

/**
 * #2164 (single-identifier login, design §4): compose the string actually posted to the server from
 * what the user typed.
 *   - contains '@' (email) or '/' (explicit slug/username) → posted verbatim.
 *   - bare username → prepend the `wh_household` cookie slug to form `slug/username`, so a member on
 *     a family device logs in with just their name. With no cookie it stays bare, and the server
 *     resolves it to the default household (self-hosted / first-ever login on this browser).
 * The cookie is a client-side UX hint only — the server never reads it.
 */
export function composeIdentifier(entered: string, cookieSlug: string | null): string {
  const value = entered.trim()
  if (value.includes('@') || value.includes('/')) return value
  return cookieSlug ? `${cookieSlug}/${value}` : value
}

/**
 * #2220: cookie-aware guidance under the identifier field, so a member on a non-default household
 * knows whether to prepend their household. Two cases, keyed on the `wh_household` cookie:
 *   - cookie SET → the SPA auto-prepends the slug (composeIdentifier), so the user just types their
 *     username. We surface which household so it's clear which slug is being composed.
 *   - cookie NOT set (fresh device) → a member of a NAMED household should sign in as
 *     `household-name/username`. This is a HINT, not a requirement: a bare username still resolves
 *     server-side to the DEFAULT household (slug `default`), so the primary/self-hosted household —
 *     admin + kids — keeps signing in with just a username. Never phrase it as "you must prepend".
 * The cookie is a client-side UX hint only; it never authenticates.
 */
export type HouseholdHint =
  | { kind: 'cookie'; slug: string; text: string }
  | { kind: 'no-cookie'; text: string }

export function householdHint(cookieSlug: string | null): HouseholdHint {
  const slug = (cookieSlug ?? '').trim()
  if (slug) {
    return {
      kind: 'cookie',
      slug,
      text: `Signing in to the ${slug} household — just enter your username.`,
    }
  }
  return {
    kind: 'no-cookie',
    text: 'If you belong to a named household, sign in as household-name/username.',
  }
}

export function LoginPage() {
  const { login } = useAuth()
  const navigate  = useNavigate()
  const [identifier, setIdentifier] = useState('')
  const [password,   setPassword]   = useState('')
  const [error,      setError]      = useState('')
  const [loading,    setLoading]    = useState(false)
  // #2220: read the cookie once at mount for the guidance line (the SUBMIT path re-reads it in
  // composeIdentifier, so a login mid-session still composes against the freshest value).
  const [hint] = useState(() => householdHint(getHouseholdCookie()))

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setError('')
    setLoading(true)
    try {
      // #2164: read the cookie at submit time and compose a bare username into `slug/username`.
      const posted = composeIdentifier(identifier, getHouseholdCookie())
      const { mustChangePassword } = await login(posted, password)
      // #586: server sets must_change_password on the seeded admin row.
      // Redirect to the account page so the operator is forced to rotate
      // before reaching any other part of the UI.
      if (mustChangePassword) {
        navigate('/account')
      } else {
        navigate('/dashboard')
      }
    } catch {
      setError('Invalid email/username or password')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div className="min-h-screen bg-brand-surface flex items-center justify-center px-4">
      <div className="w-full max-w-sm">
        <div className="text-center mb-8">
          <div className="inline-flex items-center justify-center w-16 h-16 rounded-2xl bg-brand-accent/10 border border-brand-accent/20 mb-4">
            <img src="/brand/favicon.svg" alt="" className="w-9 h-9" />
          </div>
          <h1 className="text-2xl font-bold text-brand-ink">
            Wifi<span className="text-brand-accent">Haven</span>
          </h1>
          <p className="text-brand-text-muted text-sm mt-1">Sign in to manage your network</p>
        </div>

        <form onSubmit={handleSubmit} className="bg-white rounded-2xl border border-brand-border p-6 space-y-4">
          <div>
            <label className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2">
              Email or username
            </label>
            <input
              type="text"
              value={identifier}
              onChange={e => setIdentifier(e.target.value)}
              className="w-full bg-brand-surface border border-brand-border-strong rounded-xl px-4 py-3 text-brand-ink placeholder-brand-text-muted focus:outline-none focus:border-brand-accent transition-colors"
              placeholder="you@example.com or admin"
              autoCapitalize="none"
              autoCorrect="off"
              spellCheck={false}
              autoFocus
              required
            />
            {/* #2220: cookie-aware guidance so a non-default-household member knows whether to
                prepend their household slug. A hint only — the default household still signs in
                with a bare username. */}
            <p
              data-testid="household-hint"
              className="mt-2 text-xs text-brand-text-muted"
            >
              {hint.text}
            </p>
          </div>
          <div>
            <label className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2">
              Password
            </label>
            <input
              type="password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              className="w-full bg-brand-surface border border-brand-border-strong rounded-xl px-4 py-3 text-brand-ink placeholder-brand-text-muted focus:outline-none focus:border-brand-accent transition-colors"
              placeholder="••••••••"
              required
            />
          </div>

          {/* #2164: no household field. The identifier itself carries the household (email / an
              explicit slug/username / the wh_household cookie for a bare username), resolved
              server-side — design §4. */}

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
            {loading ? 'Signing in…' : 'Sign in'}
          </button>
        </form>
      </div>
    </div>
  )
}
