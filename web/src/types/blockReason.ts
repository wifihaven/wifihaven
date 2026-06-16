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
    // #1637: bare `extraBlocked` collapses every per-host eb_<host> drop
    // (household global_blocks, per-profile extraBlocked, app-driven blocks)
    // into one kind on the conntrack ingest path; "(household)" misled
    // operator triage in #1636 / #1666. The #1645 `extraBlockedBy` arm
    // below carries the matched host explicitly when the router knows it.
    case 'extraBlocked':  return 'blocked (host rule)'
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
  }
}
