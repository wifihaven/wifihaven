import { useAuth } from '@/hooks/useAuth'
import { useSupportIdentity } from '@/api/queries'
import { supportWidgetActive } from './SupportWidget'

// #2429 (epic #2197): the only in-product "how do I get help" affordance — a quiet line at the
// bottom of the app shell for logged-in admins.
//
// Three things it must not get wrong:
//   1. Admin-only, matching the widget's gate. `GET /api/support/identity` 403s non-admins, so the
//      query is enabled only for admins and non-admins see nothing (support paths they can't use).
//   2. The address is ENVIRONMENT-CORRECT and never hardcoded here: it rides on the identity
//      response (`supportEmail`, from `support.emailAddress`) so the API is the single source of
//      truth — support@wifihaven.net on prod, support@staging.wifihaven.net on staging.
//   3. It doesn't promise a chat icon the SERVER says isn't there. The Plain widget is dark-able
//      (`widgetEnabled=false`, or an unidentifiable caller), so the "or click the chat icon" half is
//      gated on the SAME predicate the widget boots on ([[supportWidgetActive]]) rather than a
//      second copy of that rule. Note the limit of that guarantee: the predicate proves the
//      server-side config is complete, not that the Plain SDK actually loaded — a blocked script
//      (the #2418 CSP class) would still leave the wording claiming a bubble. Email is the
//      always-valid half, which is why it leads.
//
// It renders in normal flow (not fixed), above the mobile-nav spacer, so it clears the fixed mobile
// nav; the right padding keeps the centered sentence out of the bottom-right Plain bubble's column
// at narrow desktop widths.
export function SupportFooter() {
  const { isAdmin } = useAuth()
  // Same query key as SupportWidget (staleTime: Infinity) — this shares the cached response rather
  // than issuing a second request.
  const { data } = useSupportIdentity({ enabled: isAdmin })

  if (!isAdmin) return null
  const email = data?.supportEmail
  // No hosted support desk configured for this deployment (self-hosted) ⇒ no line at all.
  if (!email) return null

  const chatAvailable = supportWidgetActive(data)

  return (
    <footer
      // `md:pr-20` clears the bottom-right Plain bubble's column on desktop, where the footer can
      // land at the viewport bottom on a short page — only needed when a bubble is actually there.
      className={`mx-auto w-full max-w-7xl px-4 pb-6 pt-2 text-center text-xs text-brand-text-muted ${
        chatAvailable ? 'md:pr-20' : ''
      }`.trim()}
    >
      <p>
        Email{' '}
        <a
          href={`mailto:${email}`}
          // The link itself uses the primary body colour (AA contrast on the page canvas); only the
          // surrounding sentence is muted.
          className="text-brand-text underline underline-offset-2 hover:text-brand-ink focus-visible:text-brand-ink transition-colors"
        >
          {email}
        </a>
        {chatAvailable ? ' or click the chat icon for support' : ' for support'}
      </p>
    </footer>
  )
}
