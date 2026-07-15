import { useEffect, useRef } from 'react'
import { useAuth } from '@/hooks/useAuth'
import { useSupportIdentity } from '@/api/queries'

// #2199 (support intake B, epic #2197): the identified Plain chat widget, rendered ONLY inside the
// authenticated admin SPA (this component mounts inside the Layout, which is behind RequireAuth).
//
// Household-gating boundary: every value the widget identifies with — the chat-auth `emailHash`, the
// `tenantIdentifier` (household id), the email — is SERVER-SIGNED and comes verbatim from
// `GET /api/support/identity` (SupportService), computed over the caller's OWN JWT-resolved
// household. This component NEVER derives the hash or the tenant client-side, so a household-A admin
// can never obtain household-B's identity. When the widget is unconfigured (no Plain app id /
// identity secret) or the caller isn't identifiable, the endpoint returns `{configured:false}` and
// this renders nothing (feature dark).
//
// The exact Plain chat SDK init surface (script URL + `Plain.init` shape) is finalized at go-live
// against the operator's Plain workspace; this loader forwards the server-signed identity and is a
// no-op until the operator provisions the keys.

const PLAIN_CHAT_SCRIPT = 'https://chat.cdn-plain.com/index.js'
const PLAIN_SCRIPT_ID = 'plain-chat-sdk'

// The minimal shape of the global the Plain chat SDK installs once its script loads.
interface PlainSdk {
  init: (config: Record<string, unknown>) => void
}
declare global {
  interface Window {
    Plain?: PlainSdk
  }
}

export function SupportWidget() {
  const { isAdmin } = useAuth()
  // Admin-only: the API 403s non-admins, so only enable the fetch for admins to avoid a wasted 403.
  const { data } = useSupportIdentity({ enabled: isAdmin })
  // Boot Plain at most once per session even if the identity query re-emits.
  const bootedRef = useRef(false)

  useEffect(() => {
    // Render nothing unless the caller is an admin AND the server says the widget is configured and
    // the caller is identifiable. `configured=false` (dark) short-circuits here — no script, no init.
    if (!isAdmin) return
    if (!data || !data.configured) return
    if (!data.appId || !data.email || !data.emailHash) return
    if (bootedRef.current) return
    bootedRef.current = true

    // Everything below is server-signed identity, forwarded verbatim — never recomputed client-side.
    const config: Record<string, unknown> = {
      appId: data.appId,
      customerDetails: {
        email: data.email,
        emailHash: data.emailHash,
        ...(data.fullName ? { fullName: data.fullName } : {}),
      },
      // Household-gating: the tenant is the server-provided household id, not a client value.
      ...(data.tenantIdentifier ? { tenantIdentifier: data.tenantIdentifier } : {}),
    }

    const boot = () => {
      try {
        window.Plain?.init(config)
      } catch {
        // Support chat is best-effort background context — a Plain SDK hiccup must never break the app.
        bootedRef.current = false
      }
    }

    if (window.Plain) {
      boot()
      return
    }
    // Inject the Plain chat SDK once; boot on load.
    const existing = document.getElementById(PLAIN_SCRIPT_ID) as HTMLScriptElement | null
    if (existing) {
      existing.addEventListener('load', boot, { once: true })
      return
    }
    const script = document.createElement('script')
    script.id = PLAIN_SCRIPT_ID
    script.async = true
    script.src = PLAIN_CHAT_SCRIPT
    script.addEventListener('load', boot, { once: true })
    document.body.appendChild(script)
  }, [isAdmin, data])

  // The widget UI is owned by the Plain SDK; this component renders no DOM of its own.
  return null
}
