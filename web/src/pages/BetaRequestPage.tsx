import React, { useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '@/api/client'

/**
 * #2133 (multi-tenant P5-3, design §3): the PUBLIC beta-access request form (`/beta`).
 *
 * Posts (email, name, note) to `POST /api/beta/request` — unauthenticated, rate-limited and
 * idempotent server-side. The intake ALWAYS returns the same content-free ack whether the email is
 * brand-new or a duplicate (no enumeration leak), so on any 2xx we show the same "request received"
 * success state and set the same expectations (manual approval → an invite link arrives by email).
 * A rate-limited / transient failure surfaces a generic retry message — never anything that would
 * confirm whether a given email already requested.
 */
export function BetaRequestPage() {
  const [email, setEmail] = useState('')
  const [name,  setName]  = useState('')
  const [note,  setNote]  = useState('')
  const [status, setStatus] = useState<'idle' | 'submitting' | 'done' | 'error'>('idle')

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    setStatus('submitting')
    try {
      await api.beta.request({
        email: email.trim(),
        name: name.trim(),
        note: note.trim() || undefined,
      })
      // Idempotent + content-free: a fresh request and a duplicate look identical here.
      setStatus('done')
    } catch {
      // Generic failure (incl. rate-limited) — no enumeration signal either way.
      setStatus('error')
    }
  }

  if (status === 'done') {
    return (
      <div className="min-h-screen bg-brand-surface flex items-center justify-center px-4">
        <div className="w-full max-w-md text-center">
          <div className="inline-flex items-center justify-center w-16 h-16 rounded-2xl bg-brand-accent/10 border border-brand-accent/20 mb-4">
            <img src="/brand/favicon.svg" alt="" className="w-9 h-9" />
          </div>
          <h1 className="text-2xl font-bold text-brand-ink mb-3">Request received</h1>
          <p className="text-brand-text text-sm leading-relaxed">
            Thanks for your interest in WifiHaven. We approve beta requests manually — once yours is
            approved, we'll email you a personal invite link to finish setting up your household.
          </p>
          <p className="text-brand-text-muted text-xs mt-4">
            Already have an invite link? Open it to get started.
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
          <h1 className="text-2xl font-bold text-brand-ink">
            Request beta access
          </h1>
          <p className="text-brand-text-muted text-sm mt-1">
            WifiHaven is in invite-only beta. Tell us a bit about you.
          </p>
        </div>

        <form onSubmit={handleSubmit} className="bg-white rounded-2xl border border-brand-border p-6 space-y-4">
          <div>
            <label htmlFor="beta-email" className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2">
              Email
            </label>
            <input
              id="beta-email"
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
          <div>
            <label htmlFor="beta-name" className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2">
              Household name
            </label>
            <input
              id="beta-name"
              type="text"
              value={name}
              onChange={e => setName(e.target.value)}
              className="w-full bg-brand-surface border border-brand-border-strong rounded-xl px-4 py-3 text-brand-ink placeholder-brand-text-muted focus:outline-none focus:border-brand-accent transition-colors"
              placeholder="The Smith Family"
              required
            />
          </div>
          <div>
            <label htmlFor="beta-note" className="block text-xs font-semibold text-brand-text-muted uppercase tracking-wider mb-2">
              Anything else? <span className="text-brand-text-muted/70 normal-case">(optional)</span>
            </label>
            <textarea
              id="beta-note"
              value={note}
              onChange={e => setNote(e.target.value)}
              rows={3}
              className="w-full bg-brand-surface border border-brand-border-strong rounded-xl px-4 py-3 text-brand-ink placeholder-brand-text-muted focus:outline-none focus:border-brand-accent transition-colors resize-none"
              placeholder="How you'd use WifiHaven, how you heard about us…"
            />
          </div>

          {status === 'error' && (
            <div className="bg-red-500/10 border border-red-500/20 rounded-lg px-4 py-3 text-red-700 text-sm">
              Something went wrong. Please try again in a moment.
            </div>
          )}

          <button
            type="submit"
            disabled={status === 'submitting'}
            className="w-full bg-brand-accent hover:bg-brand-accent-dark disabled:opacity-50 text-white font-semibold py-3 rounded-xl transition-colors"
          >
            {status === 'submitting' ? 'Submitting…' : 'Request access'}
          </button>

          <p className="text-center text-xs text-brand-text-muted">
            Already have an account?{' '}
            <Link to="/login" className="text-brand-accent hover:underline">Sign in</Link>
          </p>
        </form>
      </div>
    </div>
  )
}
