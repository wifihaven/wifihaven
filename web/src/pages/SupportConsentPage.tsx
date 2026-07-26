import { useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { api, isForbiddenError } from '@/api/client'
import { useAuth } from '@/hooks/useAuth'

/**
 * #2419: the consent page (`/support/consent?g=…`), reached from the link the SERVER posts into a
 * support thread when the assistant asks to look at the account.
 *
 * This page is the customer's half of the consent boundary: the support agent can ASK (its
 * thread-bound token authorises `POST /api/support/agent/request-consent`, which only makes the
 * server post the prompt), but only this authenticated action records the grant. The household is
 * taken server-side from the session — never from the link — so a link cannot aim a grant at
 * another account.
 *
 * The route is public so the link never 404s, but the POST needs a session: signed out, we say so
 * and point at sign-in rather than dropping the customer on the dashboard with their link lost.
 */
export function SupportConsentPage() {
  const [params] = useSearchParams()
  const grant = params.get('g') ?? ''
  const { isAuthenticated } = useAuth()

  const [state, setState] = useState<'idle' | 'saving' | 'granted' | 'revoked'>('idle')
  const [error, setError] = useState('')

  const missingGrant = grant.trim().length === 0

  async function submit(allow: boolean) {
    setError('')
    setState('saving')
    try {
      await api.support.consent(grant, allow)
      setState(allow ? 'granted' : 'revoked')
    } catch (e) {
      // 403 = the link belongs to another account, or this signed-in user is not an admin — a
      // different problem from a stale link, and telling them "the link is broken" would send them
      // in circles. Everything else (400 stale/tampered link, 5xx, network) gets the generic copy:
      // the fix is the same, ask the assistant for a fresh link.
      const forbidden = isForbiddenError(e)
      setError(
        forbidden
          ? 'That permission link is not for this account. Sign in as an admin of the account that opened the support conversation.'
          : 'That permission link is no longer valid. Ask the assistant in your support conversation to send a new one.',
      )
      setState('idle')
    }
  }

  return (
    <div className="min-h-screen bg-brand-surface flex items-center justify-center px-4">
      <div className="w-full max-w-md text-center">
        <h1 className="text-2xl font-bold text-brand-ink mb-3">Support access</h1>

        {missingGrant ? (
          <p className="text-brand-text text-sm">
            This page needs the permission link from your support conversation.{' '}
            <Link to="/dashboard" className="text-brand-accent hover:underline">Back to dashboard</Link>.
          </p>
        ) : !isAuthenticated ? (
          <>
            <p className="text-brand-text text-sm">
              Sign in to confirm this permission, then open the link from your support conversation
              again.
            </p>
            <Link
              to="/login"
              className="inline-block mt-6 bg-brand-accent hover:bg-brand-accent-dark text-white font-semibold px-6 py-3 rounded-xl transition-colors"
            >
              Sign in
            </Link>
          </>
        ) : state === 'granted' ? (
          <>
            {/*
              Deliberately does NOT promise an answer. The server tries to pick the question back
              up on its own (#2460), but that resume can be rate-limited, disabled, or fail — and
              this response is the same 'granted' in every one of those cases, so claiming "it's
              already answering" would be a lie the page cannot check.
            */}
            <p className="text-brand-text text-sm">
              Thanks — the support assistant can now see your account summary for that conversation.
              Head back to the chat: if it hasn't picked your question back up, just ask again. The
              permission expires on its own, and you can withdraw it here at any time.
            </p>
            {/*
              #2460: the granted state used to dead-end — "Withdraw this permission" was the only
              control, so a customer who followed the consent link out of the page hosting the chat
              widget had no route back to their conversation. The widget lives inside the
              authenticated Layout, so the dashboard is where re-opening the chat is one click away.
            */}
            <Link
              to="/dashboard"
              className="inline-block mt-6 bg-brand-accent hover:bg-brand-accent-dark text-white font-semibold px-6 py-3 rounded-xl transition-colors"
            >
              Back to your conversation
            </Link>
            <div>
              <button
                onClick={() => submit(false)}
                className="mt-4 text-sm text-brand-accent hover:underline"
              >
                Withdraw this permission
              </button>
            </div>
          </>
        ) : state === 'revoked' ? (
          <p className="text-brand-text text-sm">
            Permission withdrawn. The support assistant can no longer read your account summary for
            that conversation.
          </p>
        ) : (
          <>
            <p className="text-brand-text text-sm">
              Allow the WifiHaven support assistant to read a summary of your account — your plan,
              your profile names, and how many devices you have — so it can answer your question.
            </p>
            <ul className="text-brand-text text-sm text-left mt-4 space-y-1 list-disc list-inside">
              <li>Read-only. It cannot change any settings.</li>
              <li>This support conversation only.</li>
              <li>Expires on its own, and you can withdraw it here sooner.</li>
              <li>No browsing history, device names, or per-site activity is shared.</li>
            </ul>
            {error && <p className="text-sm text-red-600 mt-4">{error}</p>}
            <div className="flex gap-3 justify-center mt-6">
              <button
                onClick={() => submit(true)}
                disabled={state === 'saving'}
                className="bg-brand-accent hover:bg-brand-accent-dark disabled:opacity-60 text-white font-semibold px-6 py-3 rounded-xl transition-colors"
              >
                {state === 'saving' ? 'Saving…' : 'Allow'}
              </button>
              <Link
                to="/dashboard"
                className="px-6 py-3 rounded-xl border border-brand-border text-brand-text font-semibold hover:bg-brand-alt transition-colors"
              >
                Not now
              </Link>
            </div>
          </>
        )}
      </div>
    </div>
  )
}
