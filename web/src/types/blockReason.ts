import type { BlockReason } from './api'

/** Short human-readable rendering for a typed BlockReason. Used on the
 *  connection events table (LogsPage) and the dashboard recent-activity
 *  panel (DashboardPage). The kid-side block page renders separately via
 *  the server-resolved BlockedInfoResponse (#959).
 *
 *  Observability surfaces that want to group by reason class (#822 / #829)
 *  can key directly on `reason.kind` — it is already the low-cardinality
 *  discriminator. */
export function blockReasonText(r: BlockReason): string {
  switch (r.kind) {
    case 'allow':         return 'allowed'
    case 'blocked':       return 'blocked'
    case 'extraAllowed':  return 'allowed (household)'
    case 'extraBlocked':  return 'blocked (household)'
    case 'extraBlockedBy': return `blocked: matched ${r.host}` // #1645
    case 'noProfile':     return 'no profile'
    case 'unmanaged':     return 'unmanaged device'
    case 'paused':        return 'profile paused'
    case 'schedule':      return 'scheduled quiet time'
    case 'timeLimit':     return 'daily limit reached'
    case 'manual':        return 'blocked by parent'
    case 'category':      return `category: ${r.slug}`
    case 'appTimeLimit':  return `app limit: ${r.label}`
    case 'appBlocked':    return `app blocked: ${r.appId}`
    case 'unknown':       return r.raw || 'unknown'
    // #2846: the SPA deploys separately from the API (Cloudflare Pages), so the API can emit a
    // kind this build predates. Render the generic label rather than `undefined` (a blank cell).
    default:              return 'blocked'
  }
}
