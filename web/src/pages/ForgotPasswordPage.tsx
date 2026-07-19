import React, { useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '@/api/client'

/**
 * #2308: the PUBLIC forgot-password request form (`/forgot-password`), linked from the login page.
 *
 * Posts { email } to `POST /api/auth/forgot-password` — unauthenticated, rate-limited, and
 * enumeration-safe server-side: a registered and an unregistered email get the SAME content-free
 * ack. So on ANY 2xx we show the same "check your email" state and never reveal whether the address
 * had an account. Even a transient/rate-limited failure surfaces the same generic success copy
 * rather than an error that could confirm the address exists — the honest recovery is "check your
 * inbox, and if nothing arrives, try again in a bit."
 */
export function ForgotPasswordPage() {
  const [email, setEmail] = useState('')
  const [status, setStatus] = useState<'idle' | 'submitting' | 'done'>('idle')

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setStatus('submitting')
    try {
      await api.auth.forgotPassword(email.trim())
    } catch {
      // Swallow: never surface whether the request "worked". The generic done state below is shown
      // regardless (no enumeration, even on a rate-limit / transient error).
    }
    setStatus('done')
  }

  if (status === 'done') {
    return (
      <div className="min-h-screen bg-brand-surface flex items-center justify-center px-4">
        <div className="w-full max-w-md text-center">
          <div className="inline-flex items-center justify-center w-16 h-16 rounded-2xl bg-brand-accent/10 border border-brand-accent/20 mb-4">
            <img src="/brand/favicon.svg" alt="" className="w-9 h-9" />
          </div>
          <h1 className="text-2xl font-bold text-brand-ink mb-3">Check your email</h1>
          <p className="text-brand-text text-sm leading-relaxed">
            If that email is registered with WifiHaven, we've sent a link to reset your password.
            The link is single-use and expires soon — open it to choose a new password.
          </p>
          <p className="text-brand-text-muted text-xs mt-4">
            Didn't get anything? Check your spam folder, or try again in a few minutes.
          </p>
          <Link to="/login" className="inline-block mt-6 text-brand-accent hover:underline text-sm">
            Back to sign in
          </Link>
        </div>
      </div>
    )
  }

  return (
    <div className="min-h-screen bg-brand-surface flex items-center justify-center px-4">
      <div className="w-full max-w-md">
        <div className="text-center mb-8">
          <div className="inline-flex items-center justify-center w-16 h-16 rounded-2xl bg-brand-accent/10 border border-brand-accent/20 mb-4">
            <img src="/brand/favicon.svg" alt="" className="w-9 h-9" />
          </div>
          <h1 className="text-2xl font-bold text-brand-ink">Reset your password</h1>
          <p className="text-brand-text-muted text-sm mt-1">
            Enter your email and we'll send you a reset link.
          </p>
        </div>

        <form onSubmit={handleSubmit} className="bg-white rounded-2xl border border-brand-border p-6 space-y-4">
          <div>
            <label htmlFor="forgot-email" className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2">
              Email
            </label>
            <input
              id="forgot-email"
              type="email"
              value={email}
              onChange={e => setEmail(e.target.value)}
              className="w-full bg-brand-surface border border-brand-border-strong rounded-xl px-4 py-3 text-brand-ink placeholder-brand-text-muted focus:outline-none focus:border-brand-accent transition-colors"
              placeholder="you@example.com"
              autoCapitalize="none"
              autoCorrect="off"
              spellCheck={false}
              autoFocus
              required
            />
          </div>

          <button
            type="submit"
            disabled={status === 'submitting'}
            className="w-full bg-brand-accent hover:bg-brand-accent-dark disabled:opacity-50 text-white font-semibold py-3 rounded-xl transition-colors"
          >
            {status === 'submitting' ? 'Sending…' : 'Send reset link'}
          </button>

          <p className="text-center text-xs text-brand-text-muted">
            Remembered it?{' '}
            <Link to="/login" className="text-brand-accent hover:underline">Sign in</Link>
          </p>
        </form>
      </div>
    </div>
  )
}
